package io.github.piwugu.snowleopard.host;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import snowleopard.core.SnowLeopard;
import snowleopard.core.memory.RawLog;
import snowleopard.core.result.Result;

import io.github.piwugu.snowleopard.SnowLeopardMod;

/**
 * 会话记忆记录器（v1.11.0，2026-10-25 04:00 (UTC+8)）。
 *
 * <p><b>补的是 core 的写侧断点</b>：`Rollup.onRoundEnd` 与 `RawLog.openRound` 在 core/框架
 * 都没有生产调用方（2026-10-25 03:55 核查）——原始记录不产生、压缩永不触发、`memory_repair`
 * 永远无事可做。本类把"一轮会话"落成一条原始记录并触发轮压缩，接在 {@link AiHost#runSession} 上。
 *
 * <p><b>为什么必须筛内容</b>（用户 2026-10-25 03:53 明确要求"筛选掉工具返回的大量信息以节省空间"）：
 * 工具结果是压缩原料，不是审计日志。`sense_query`/`world_blocks`/`container_items` 单次返回
 * 动辄几十 KB，原样落盘会让天档输入爆掉、把一次压缩变成几十万 token 的调用。所以：
 * <ul>
 *   <li>每条都折叠空白 + 截断（用户 400 / 模型 800 / 工具 300 / 参数 200 字符），并标 {@code …(原 N 字符)}；</li>
 *   <li>单轮总字符预算 {@value #MAX_ROUND_CHARS}、条数上限 {@value #MAX_ENTRIES}——超了只记名字，
 *       最后补一条"另有 N 条仅记名"；</li>
 *   <li>空轮不落盘（core 的稀疏约束：无内容=无轮，不是缺口）。</li>
 * </ul>
 *
 * <p><b>失败不拖垮会话</b>：记录/压缩异常只 WARN——记忆是副产品，不能把玩家的一次对话搞挂。
 */
public final class MemoryRecorder {

    /** 用户消息上限（含注入块，注入块可能很长）。 */
    public static final int MAX_USER_CHARS = 400;
    /** 模型回复上限。 */
    public static final int MAX_MODEL_CHARS = 800;
    /** 工具结果上限——工具是"大输出"的主要来源，这里最紧。 */
    public static final int MAX_TOOL_CHARS = 300;
    /** 工具参数上限。 */
    public static final int MAX_ARGS_CHARS = 200;
    /** 单轮总字符预算。 */
    public static final int MAX_ROUND_CHARS = 6000;
    /** 单轮条数上限（12 轮 × 多工具调用会爆）。 */
    public static final int MAX_ENTRIES = 40;

    private MemoryRecorder() {
    }

    /** 开一个会话记录作用域（不落盘；{@link Scope#finish} 时才写）。 */
    public static Scope begin(SnowLeopard core, String model) {
        return new Scope(core, model);
    }

    /** 一轮会话的记录作用域。非线程安全：只在持有会话的那个工作线程里用。 */
    public static final class Scope {

        private final SnowLeopard core;
        private final String model;
        private final RawLog log;
        private final List<String[]> entries = new ArrayList<>();   // [kindWire, payload]
        private int chars;
        private int omitted;

        private Scope(SnowLeopard core, String model) {
            this.core = core;
            this.model = model;
            this.log = core.forCaller(model).rawLog();
        }

        /** 用户侧输入（唤醒提示 + 注入块 + 尾注）。 */
        public void user(String prompt) {
            add("thought", "[user] " + squeeze(prompt, MAX_USER_CHARS));
        }

        /** 模型侧输出（每轮正文；纯工具调用的轮次可能为空，空则跳过）。 */
        public void model(String text) {
            if (text == null || text.isBlank()) {
                return;
            }
            add("thought", "[model] " + squeeze(text, MAX_MODEL_CHARS));
        }

        /** 一次工具调用与它的返回（返回可能巨大——这里就是省空间的地方）。 */
        public void tool(String name, String args, String result) {
            String line = "[" + name + "] " + squeeze(args, MAX_ARGS_CHARS)
                    + " → " + squeeze(result, MAX_TOOL_CHARS);
            if (entries.size() >= MAX_ENTRIES || chars + line.length() > MAX_ROUND_CHARS) {
                omitted++;
                return;
            }
            add("tool_call", line);
        }

        /** 备注（失败原因、收束原因等）。 */
        public void note(String text) {
            if (text == null || text.isBlank()) {
                return;
            }
            add("thought", "[note] " + squeeze(text, MAX_MODEL_CHARS));
        }

        /**
         * 落盘：写轮文件 → 触发轮压缩（含惰性归档）。
         *
         * @return 压缩结果（{@code Ok}=天档已追加；{@code Warn}=压缩失败进冷却，原始记录仍在）；
         *         空轮或写盘异常返回 {@code null}
         */
        public Result finish() {
            try {
                if (omitted > 0) {
                    add("thought", "（另有 " + omitted + " 条工具调用仅记名，未入记忆）");
                }
                if (entries.isEmpty()) {
                    return null;   // 空轮不落盘：稀疏约束
                }
                long now = System.currentTimeMillis();
                RawLog.RoundWriter w = log.openRound(now);
                for (String[] e : entries) {
                    w.add(RawLog.Kind.of(e[0]), e[1]);
                }
                RawLog.Round round = w.closeRound();
                // 用与 RawLog 同一个时区口径（+08:00）判"今天"，别用 LocalDate.now() 的机器时区
                return core.forCaller(model).rollup().onRoundEnd(round.key(), RawLog.dayOf(now));
            } catch (RuntimeException e) {
                SnowLeopardMod.LOG.warn("[雪豹框架][记忆] {} 记录失败（不影响会话）: {}", model, e.getMessage());
                return null;
            }
        }

        private void add(String kindWire, String payload) {
            entries.add(new String[]{kindWire, payload});
            chars += payload.length();
        }
    }

    /**
     * 折叠空白 + 截断。工具返回常见形态是紧凑 JSON 或带缩进的多行——折叠后同样信息占更少字符，
     * 截断时标出原始长度，压缩器（LLM）看到 {@code …(原 12345 字符)} 就知道自己只拿到片段。
     */
    static String squeeze(String s, int cap) {
        if (s == null) {
            return "";
        }
        String flat = s.replaceAll("\\s+", " ").trim();
        if (flat.length() <= cap) {
            return flat;
        }
        return flat.substring(0, cap) + "…(原 " + flat.length() + " 字符)";
    }
}
