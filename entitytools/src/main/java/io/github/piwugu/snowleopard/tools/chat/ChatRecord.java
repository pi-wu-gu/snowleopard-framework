package io.github.piwugu.snowleopard.tools.chat;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一条聊天记录（M12-0 契约冻结，2026-10-24 00:50 (UTC+8)，分析见
 * {@code docs/聊天工具组可行性分析-2026-10-23.md}）。
 *
 * <p><b>为什么 {@code seq} 是全局单调而不是"每天从 1 开始"</b>：游标（{@code lastReadSeq}）是跨天的，
 * 每天重置会让"昨天读到 118、今天又从 1 开始"变成永远读不到新消息。所以 seq 由 {@link ChatLog} 持久化递增。
 *
 * <p><b>{@code text} 存原文</b>：存储要保真（换行、定界符都留着），
 * 单行化/截断只在渲染注入块时做（见 {@link ChatBlock}）。存的时候就清洗，等于把证据毁掉。
 *
 * @param seq   全局递增序号（跨天不重置）
 * @param epoch 时间戳毫秒（只用于排序与时间窗比较，**不比字符串**）
 * @param source {@link #PLAYER} 或 {@link #MODEL}
 * @param name   说话者：玩家名 / 模型编号
 * @param text   原文
 * @param dim    维度（如 {@code minecraft:overworld}）；非 MC 场景可为空串
 */
public record ChatRecord(long seq, long epoch, String source, String name, String text, String dim) {

    /** 真人玩家发言。只有这个来源会进入注入白名单（防模型互相回声）。 */
    public static final String PLAYER = "PLAYER";
    /** 模型自己/别的模型的发言。不注入，但可被 {@code chat_search} 搜到。 */
    public static final String MODEL = "MODEL";

    public ChatRecord {
        if (seq <= 0) {
            throw new IllegalArgumentException("seq 必须为正（0 表示\"还没写过\"）");
        }
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("source 不能为空（PLAYER / MODEL）");
        }
        if (name == null) {
            name = "";
        }
        if (text == null) {
            text = "";
        }
        if (dim == null) {
            dim = "";
        }
    }

    public boolean fromPlayer() {
        return PLAYER.equals(source);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("seq", seq);
        m.put("epoch", epoch);
        m.put("source", source);
        m.put("name", name);
        m.put("text", text);
        m.put("dim", dim);
        return m;
    }

    /**
     * 从一行 JSON 还原。
     *
     * <p>坏行**不抛异常**而是返回 {@code null}——聊天文件是 append-only 的日志，
     * 一行损坏不该让整个搜索/注入瘫掉（但同时调用方必须能看见"少了多少行"，
     * 所以 {@link ChatLog} 会把坏行计入统计）。
     */
    public static ChatRecord fromMap(Map<String, Object> m) {
        if (m == null) {
            return null;
        }
        try {
            long seq = asLong(m.get("seq"));
            long epoch = asLong(m.get("epoch"));
            if (seq <= 0) {
                return null;
            }
            return new ChatRecord(seq, epoch,
                    String.valueOf(m.getOrDefault("source", "")),
                    String.valueOf(m.getOrDefault("name", "")),
                    String.valueOf(m.getOrDefault("text", "")),
                    String.valueOf(m.getOrDefault("dim", "")));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static long asLong(Object v) {
        if (v instanceof Number n) {
            return n.longValue();
        }
        if (v instanceof String s && !s.isBlank()) {
            return Long.parseLong(s.trim());
        }
        return 0L;
    }
}
