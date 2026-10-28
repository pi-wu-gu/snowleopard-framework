package io.github.piwugu.snowleopard;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import snowleopard.core.SnowLeopard;
import snowleopard.core.json.Json;
import snowleopard.core.result.Result;

import io.github.piwugu.snowleopard.tools.GroupNames;
import io.github.piwugu.snowleopard.tools.chat.ChatBlock;
import io.github.piwugu.snowleopard.tools.chat.ChatInbox;
import io.github.piwugu.snowleopard.tools.chat.ChatLog;
import io.github.piwugu.snowleopard.tools.chat.ChatMention;
import io.github.piwugu.snowleopard.tools.chat.ChatRecord;

/**
 * 聊天工具组的壳侧执行面（M12-3，2026-10-24 01:00 (UTC+8)，分析见
 * {@code docs/聊天工具组可行性分析-2026-10-23.md}）。
 *
 * <p><b>为什么执行面在壳而不是核心</b>：聊天是 MC 概念（玩家 / 服务器 / 维度）。
 * 纯逻辑（存储、游标、注入块、提及匹配）已经放到 {@code entitytools} 的 {@code tools.chat} 包里，
 * 那部分**零 MC 依赖**、可 headless 测；这里只做"接线"：路径从哪来、玩家列表怎么遍历、事件怎么订阅。
 *
 * <p><b>三条裁决在这里落地</b>：
 * <ol>
 *   <li>注入白名单只认 {@code PLAYER}（{@link ChatLog#readPlayerAfter} 已内建）；</li>
 *   <li>被提到**编号或名称**只给"提示"（{@link #turnSegment}），**不产生唤醒**——唤醒是 M13 的事，
 *       而且只认名称（`实体载体与唤醒源可行性分析` §3.3）；</li>
 *   <li>{@code chat_send} 不确认，但最小间隔默认 0.5 秒（可配）＋每分钟上限（可配）。</li>
 * </ol>
 *
 * <p><b>没有接入方时的行为</b>：一切都只到"文件里能被查到"为止。本类**不做**任何"假装注入"的事——
 * 壳没有模型，注入是把块交给接入方（{@link #pull}）。
 */
public final class ChatRuntime {

    /** 会话（注入块）默认取多少条。 */
    private static final int DEFAULT_PULL_LIMIT = 20;
    /**
     * 单条发言上限（2026-10-27 16:30 (UTC+8)：256 → 512）。
     *
     * <p>实测 250–290 字符的中文汇报会被 256 卡掉，模型于是改用"把工具调用写成正文"绕过，
     * 反而制造了假调用。**长度限制不该成为逼模型造假的压力源**——玩家读长一点远好过它编。
     */
    private static final int MAX_SEND_CHARS = 512;

    private static final Map<String, Long> LAST_SEND_AT = new ConcurrentHashMap<>();
    private static final Object WINDOW_LOCK = new Object();
    private static long windowStart;
    private static int windowCount;

    private static ChatLog log;
    private static ChatInbox inbox;
    private static ZoneId zone = ZoneId.systemDefault();

    private ChatRuntime() {
    }

    /** 配置（三键，缺省即默认；文件不存在不是错误）。 */
    private record Config(int minIntervalMs, int maxPerMinute, int retentionDays) {
        static Config defaults() {
            return new Config(500, 30, ChatLog.DEFAULT_RETENTION_DAYS);
        }
    }

    private static Config config = Config.defaults();

    /**
     * 装配（{@code bootstrap} 之后调用一次）。
     *
     * @param gameDir 游戏目录（配置在 {@code config/snowleopard-chat.json}）
     * @param core    已装配的核心（路径与共享面都从它取）
     */
    public static void init(Path gameDir, SnowLeopard core) {
        if (core == null) {
            SnowLeopardMod.LOG.warn("[雪豹框架][聊天] 核心未装配——聊天组不可用（不静默降级成\"没有这个工具\"）");
            return;
        }
        config = loadConfig(gameDir);
        zone = ZoneId.systemDefault();
        Path root = core.ids().shared().resolve("chat");
        log = new ChatLog(root, zone, config.retentionDays());
        inbox = new ChatInbox(log, model -> {
            Path home = BuiltinRuntime.home(model);
            if (home == null) {
                throw new IllegalStateException("模型 " + model + " 的家不可用（核心未装配？）");
            }
            return home.resolve("chat-cursor.json");
        });
        SnowLeopardMod.LOG.info("[雪豹框架][聊天] 存储 {} · 限流 间隔 {}ms / 每分钟 {} 条 · 保留 {} 天",
                root, config.minIntervalMs(), config.maxPerMinute(), config.retentionDays());
    }

    public static boolean ready() {
        return log != null && inbox != null;
    }

    /** 是否本组的工具（壳侧分流用，必须在实体组解析之前）。 */
    public static boolean isChat(String group) {
        return GroupNames.CHAT.equals(group);
    }

    // ------------------------------------------------------------- 事件：记录 + 提及

    /**
     * 玩家发言 → 落盘 + 算提及。
     *
     * <p>注册在 **LOWEST** 优先级并要求"被取消也收到"：其它模组可能取消聊天（禁言/过滤），
     * 被取消的消息玩家自己都没发出去，记下来会让模型"回应一句没人看见的话"。
     */
    public static void onServerChat(ServerChatEvent event) {
        if (!ready() || event.isCanceled()) {
            return;
        }
        ServerPlayer p = event.getPlayer();
        String text = event.getRawText();
        if (p == null || text == null || text.isBlank()) {
            return;
        }
        try {
            record(ChatRecord.PLAYER, p.getScoreboardName(), text,
                    String.valueOf(p.level().dimension().location()));
        } catch (RuntimeException e) {
            // 记录失败不该影响聊天本身（玩家说话是 MC 的事，存档是附加能力）
            SnowLeopardMod.LOG.warn("[雪豹框架][聊天] 记录失败（不影响玩家发言）: {}", e.getMessage());
        }
    }

    /**
     * 记录一条聊天并算提及（**唯一写入口**）。
     *
     * <p>为什么要公开：事件路径（{@link #onServerChat}）只是它的一种调用方。外部集成
     * （脚本/自测/别的模组转发）需要同一条路径——否则"提及计数"会在两条路径里算出两种结果，
     * 而这种漂移不报错、只出错。
     *
     * @return 落盘记录 + 被提及的模型编号（M13 用它触发唤醒）
     */
    public static Recorded record(String source, String name, String text, String dim) {
        if (!ready()) {
            throw new IllegalStateException("聊天组未装配（ChatRuntime.init 没跑）");
        }
        // 先给所有"已登记"的模型建游标（幂等），再写这条消息——
        // 否则游标会懒到"第一次被提及"才建，把中间那些消息当成历史跳过
        Map<String, String> targets = mentionTargets();
        for (String m : targets.keySet()) {
            inbox.ensure(m);
        }
        ChatRecord r = log.append(source, name, text, dim);
        List<String> mentioned = ChatRecord.PLAYER.equals(source)
                ? inbox.noteMention(r, targets)
                : List.of();
        // 名称命中（且模型有载体）→ 触发"点名唤醒"（编号命中只提示，不进这里）
        if (!mentioned.isEmpty()) {
            String player = ChatRecord.PLAYER.equals(source) ? name : "";
            AvatarWake.onMentions(mentioned, text, player);
        }
        return new Recorded(r, mentioned);
    }

    /** {@link #record} 的结果。 */
    public record Recorded(ChatRecord record, List<String> mentioned) {
    }

    /**
     * 提及候选：已知模型编号 → 实体显示名。
     *
     * <p>M12 阶段还没有载体实体（M13 才有），所以名称一律为空 —— **编号精确匹配仍然可用**。
     * M13 接进来时只改这一处：从绑定的载体读 {@code CustomName}。
     */
    private static Map<String, String> mentionTargets() {
        Map<String, String> m = new LinkedHashMap<>();
        // **候选来自单点登记表，不是句柄缓存**（2026-10-24 05:45 (UTC+8)）：
        // 句柄缓存只在模型调用过内置组工具后才有它 → 新模型永不在册 → 玩家 @它唤不醒（自举死锁）。
        for (String caller : ModelRegistry.known()) {
            m.put(caller, displayName(caller));
        }
        return m;
    }

    /**
     * 身份解析（**唯一一处**）：有载体名称用名称，否则用模型编号。
     *
     * <p>三处都用它：{@code chat_send} 前缀、{@code ChatPush} 归属、提及回执。
     * 各拼一份就会漂（"改名之后前缀没变"这类问题最难查）。
     */
    public static String displayName(String model) {
        // M13：载体有名称用名称（这是"身份解析单点"——chat_send 前缀 / 摘要 / 提及回执三处共用）
        if (model != null && AvatarWake.ready()) {
            io.github.piwugu.snowleopard.tools.avatar.AvatarStore.Entry e = AvatarWake.primaryName(model);
            if (e != null && !e.name().isBlank()) {
                return e.name();
            }
        }
        return model == null ? "" : model;
    }

    /** 唤醒失败时的兜底：给该模型补一条"被提及"（裁决——这件事不能凭空消失）。 */
    public static void addPendingMention(String model) {
        if (ready() && model != null && !model.isBlank()) {
            try {
                inbox.addPendingMention(model);
            } catch (RuntimeException e) {
                SnowLeopardMod.LOG.warn("[雪豹框架][聊天] 补提示失败: {}", e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------- 工具执行

    public static Result dispatch(String modelId, String tool, Map<String, Object> params) {
        if (!ready()) {
            return Result.error("聊天组未装配（核心未启动或初始化失败）——查启动日志");
        }
        String model = modelId == null ? "" : modelId.trim();
        if (model.isEmpty()) {
            return Result.error("聊天工具需要调用方模型编号（core 注入，不可伪造）");
        }
        try {
            inbox.ensure(model);                       // 调用工具即登记（不补历史）
        } catch (RuntimeException e) {
            return Result.error("聊天游标不可用: " + e.getMessage());
        }
        return switch (tool) {
            case "chat_send" -> send(model, str(params, "text"));
            case "chat_read" -> read(model, intOf(params, "limit", DEFAULT_PULL_LIMIT));
            case "chat_search" -> search(params);
            case "chat_status" -> Result.ok(inbox.status(model));
            default -> Result.error("未知的聊天工具: " + tool + "（可用: chat_send / chat_read / chat_search / chat_status）");
        };
    }

    private static Result send(String model, String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("chat_send 需要非空 text");
        }
        if (text.codePointCount(0, text.length()) > MAX_SEND_CHARS) {
            throw new IllegalArgumentException("单条发言超过 " + MAX_SEND_CHARS + " 字符（拆成几条再说，每条 ≤512）");
        }
        long waitMs = throttle(model);
        if (waitMs > 0) {
            return Result.error("发得太快：请等待 " + String.format("%.2f", waitMs / 1000.0) + " 秒"
                    + "（最小间隔 " + config.minIntervalMs() + "ms，可在 config/snowleopard-chat.json 调整）");
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return Result.error("没有服务器上下文——现在没有地方可说这句话");
        }
        String name = displayName(model);
        Component line = Component.literal("[雪豹·" + name + "] " + text);
        int delivered = 0;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p instanceof FakePlayer) {
                continue;   // 假人能收包但没有客户端，"看起来发了"不是送达
            }
            p.displayClientMessage(line, false);
            delivered++;
        }
        if (delivered == 0) {
            return Result.error("没有在线玩家——话没有送达（也不写进记录，避免自己搜到自己没说过的话）");
        }
        ChatRecord r = log.append(ChatRecord.MODEL, name, text, "");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("delivered", delivered);
        out.put("name", name);
        out.put("seq", r.seq());
        return Result.ok(out);
    }

    /**
     * 限流：最小间隔 + 每分钟上限（**两个都要**）。
     *
     * @return 还需要等多少毫秒；0 = 可以发
     */
    private static long throttle(String model) {
        long now = System.currentTimeMillis();
        int interval = config.minIntervalMs();
        if (interval > 0) {
            Long last = LAST_SEND_AT.get(model);
            if (last != null && now - last < interval) {
                return interval - (now - last);
            }
        }
        synchronized (WINDOW_LOCK) {
            if (windowStart == 0 || now - windowStart > 60_000L) {
                windowStart = now;
                windowCount = 0;
            }
            if (config.maxPerMinute() > 0 && windowCount >= config.maxPerMinute()) {
                return 60_000L - (now - windowStart);
            }
            windowCount++;
        }
        LAST_SEND_AT.put(model, now);
        return 0;
    }

    private static Result read(String model, int limit) {
        int n = Math.max(1, Math.min(limit, 100));
        List<ChatRecord> got = inbox.read(model, n);
        List<Map<String, Object>> out = new ArrayList<>();
        for (ChatRecord r : got) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("seq", r.seq());
            m.put("ts", Instant.ofEpochMilli(r.epoch()).atZone(zone)
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            m.put("name", r.name());
            m.put("text", r.text());
            out.add(m);
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("messages", out);
        res.put("count", out.size());
        return Result.ok(res);
    }

    private static Result search(Map<String, Object> params) {
        String keyword = str(params, "keyword");
        List<String> keys = keyword == null ? List.of() : List.of(keyword.split("\\s+"));

        String dateStr = str(params, "date");
        LocalDate day;
        try {
            day = dateStr == null ? log.today() : LocalDate.parse(dateStr);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("date 需要 YYYY-MM-DD: " + dateStr);
        }
        Long since = timeOf(str(params, "since"), day);
        Long until = timeOf(str(params, "until"), day);
        String source = str(params, "source");
        if (source != null && !source.equalsIgnoreCase(ChatRecord.PLAYER)
                && !source.equalsIgnoreCase(ChatRecord.MODEL)) {
            throw new IllegalArgumentException("source 只能是 PLAYER 或 MODEL: " + source);
        }
        int limit = Math.max(1, Math.min(intOf(params, "limit", DEFAULT_PULL_LIMIT), 100));

        ChatLog.SearchResult r = log.search(day, keys, since, until, source, limit);
        List<Map<String, Object>> hits = new ArrayList<>();
        for (ChatRecord c : r.hits()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("seq", c.seq());
            m.put("ts", Instant.ofEpochMilli(c.epoch()).atZone(zone)
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            m.put("source", c.source());
            m.put("name", c.name());
            m.put("text", ChatBlock.sanitize(c.text()));
            hits.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("date", day.toString());
        out.put("matched", r.matched());
        out.put("scanned", r.scanned());
        out.put("truncated", r.truncated());   // 只回 matched 会把"没搜到"和"没搜完"混成一件事
        out.put("hits", hits);
        return Result.ok(out);
    }

    /** 接受 {@code HH:mm} 或 {@code yyyy-MM-dd HH:mm}；缺省 null（= 不限）。 */
    private static Long timeOf(String raw, LocalDate day) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            if (raw.length() <= 5) {
                return LocalDateTime.of(day, LocalTime.parse(raw))
                        .atZone(zone).toInstant().toEpochMilli();
            }
            return LocalDateTime.parse(raw, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                    .atZone(zone).toInstant().toEpochMilli();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("时间格式需要 HH:mm 或 yyyy-MM-dd HH:mm: " + raw);
        }
    }

    // ------------------------------------------------------------- 轮末摘要 / 注入

    /**
     * 摘要行里的聊天段（**两个维度**）：
     * {@code 新聊天 N 条} 每轮都报，直到真的被 {@code pull}+{@code ack} 掉；
     * {@code 被提及 M 次} 只报一次（{@link #consume} 之后清零）。
     *
     * @return 段文本；没有可说的返回 {@code null}（沉默，不占上下文）
     */
    public static String turnSegment(String model) {
        if (!ready() || model == null || model.isBlank()) {
            return null;
        }
        StringBuilder b = new StringBuilder();
        try {
            inbox.ensure(model);                       // 本轮第一次见到也算"登记"
            ChatInbox.Cursor c = inbox.cursor(model);
            int unread = log.countPlayerAfter(c.lastReadSeq());
            if (unread > 0) {
                b.append("新聊天 ").append(unread).append(" 条");
            }
            int mentions = c.pendingMentions();
            if (mentions > 0) {
                if (b.length() > 0) {
                    b.append(" · ");
                }
                b.append("被提及 ").append(mentions).append(" 次");
            }
        } catch (RuntimeException e) {
            SnowLeopardMod.LOG.warn("[雪豹框架][聊天] 摘要段生成失败: {}", e.getMessage());
            return null;
        }
        return b.length() == 0 ? null : b.toString();
    }

    /** 摘要行已经报过 → 清零"被提及"（**不动读游标**：读过 ≠ 提示过）。 */
    public static void consume(String model) {
        if (!ready() || model == null || model.isBlank()) {
            return;
        }
        try {
            inbox.consumeMentions(model);
        } catch (RuntimeException e) {
            SnowLeopardMod.LOG.warn("[雪豹框架][聊天] 清零提及失败: {}", e.getMessage());
        }
    }

    /**
     * 取注入块（给接入方用；**不推进游标**，要 {@link #ack} 才算注入完成）。
     */
    public static ChatInbox.Pulled pull(String model, int limit) {
        if (!ready()) {
            return new ChatInbox.Pulled("", 0, 0, 0);
        }
        return inbox.pull(model, Math.max(1, Math.min(limit, ChatBlock.MAX_LINES)));
    }

    /** 接入方确认"这批已经进上下文了"。 */
    public static void ack(String model, long seq) {
        if (ready()) {
            inbox.ack(model, seq);
        }
    }

    /** 启动时清一次过期记录（**不在 tick 里扫盘**）。 */
    public static void pruneOnStart() {
        if (!ready()) {
            return;
        }
        try {
            int n = log.prune();
            if (n > 0) {
                SnowLeopardMod.LOG.info("[雪豹框架][聊天] 清理过期记录 {} 个文件（保留 {} 天）",
                        n, config.retentionDays());
            }
        } catch (RuntimeException e) {
            SnowLeopardMod.LOG.warn("[雪豹框架][聊天] 清理失败: {}", e.getMessage());
        }
    }

    /** 待提示的提及次数（自测/诊断用）。 */
    public static int mentionsPending(String model) {
        return ready() && model != null && !model.isBlank() ? inbox.pendingMentions(model) : 0;
    }

    /** 载体登记表（自测/诊断用；null = 未装配）。 */
    public static io.github.piwugu.snowleopard.tools.avatar.AvatarStore avatarStore() {
        return AvatarWake.store();
    }

    public static ChatLog logOrNull() {
        return log;
    }

    // ------------------------------------------------------------- 配置与参数

    private static Config loadConfig(Path gameDir) {
        Config d = Config.defaults();
        if (gameDir == null) {
            return d;
        }
        Path f = gameDir.resolve("config").resolve("snowleopard-chat.json");
        if (!Files.isRegularFile(f)) {
            return d;
        }
        try {
            Map<String, Object> m = Json.parseObject(Files.readString(f, StandardCharsets.UTF_8));
            return new Config(
                    (int) num(m, "min_interval_ms", d.minIntervalMs()),
                    (int) num(m, "max_per_minute", d.maxPerMinute()),
                    (int) num(m, "retention_days", d.retentionDays()));
        } catch (Exception e) {
            SnowLeopardMod.LOG.warn("[雪豹框架][聊天] 配置读不懂，用默认值: {} ({})", f, e.getMessage());
            return d;
        }
    }

    private static long num(Map<String, Object> m, String key, long dflt) {
        Object v = m.get(key);
        if (v instanceof Number n) {
            return n.longValue();
        }
        if (v instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                return dflt;
            }
        }
        return dflt;
    }

    private static String str(Map<String, Object> p, String key) {
        if (p == null) {
            return null;
        }
        Object v = p.get(key);
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    private static int intOf(Map<String, Object> p, String key, int dflt) {
        if (p == null) {
            return dflt;
        }
        Object v = p.get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return dflt;
            }
        }
        return dflt;
    }
}
