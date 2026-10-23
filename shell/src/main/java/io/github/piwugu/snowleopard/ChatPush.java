package io.github.piwugu.snowleopard;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * 聊天栏推送（M11，2026-10-23 22:25 (UTC+8)，分析见 {@code docs/聊天栏推送可行性分析-2026-10-23.md}）。
 *
 * <p><b>内容只有一处来源</b>：核心的 {@code TurnSummary} 行（群未读 / 新消息 / 新唤起 / 记忆待吸收）。
 * 本类只负责"送到哪"——模型上下文尾注与玩家聊天栏**共用同一行**，避免两份文案漂移。
 *
 * <p><b>三条硬约束</b>：
 * <ul>
 *   <li>只推摘要不推正文（群消息是别的模型写的文本，整段展示给人既越界又与"不自动注入"自相矛盾）；</li>
 *   <li>{@link #push} 返回<b>是否真的送达</b>——无玩家时落日志并返回 {@code false}，绝不假装"已推送"；</li>
 *   <li>同内容 5 秒去重 + 每分钟 20 条上限，超限 WARN（"没推出去"必须留痕）。</li>
 * </ul>
 *
 * <p><b>可测性</b>：专用服务器没有客户端，聊天栏在真机上"看不见"——所以留 {@link Sink} 注入点
 * （与 {@code Compressor} / {@code RuntimeView} 同一模式）：自测注入记录器，断言内容与限流，
 * 而不是断言"玩家看见了没"。
 */
public final class ChatPush {

    /** 注入点：非 null 时接管送达（自测/自定渠道用）。 */
    public interface Sink {
        void send(String text);
    }

    /** 同内容去重窗口（毫秒）。 */
    public static final long DEDUP_MS = 5_000L;
    /** 每分钟上限。 */
    public static final int MAX_PER_MINUTE = 20;
    /** 单条上限（字符）。 */
    public static final int MAX_CHARS = 256;

    private static volatile Sink sink;
    private static final Map<String, Long> LAST_SENT = new ConcurrentHashMap<>();
    private static long windowStart;
    private static int windowCount;

    private ChatPush() {
    }

    /** 自测/自定渠道：设 null 恢复真实玩家通道。 */
    public static void setSink(Sink s) {
        sink = s;
        LAST_SENT.clear();
        windowStart = 0;
        windowCount = 0;
    }

    /**
     * 推送一行。
     *
     * @return true=真的送达（注入的 sink 收到 / 至少一个真人玩家收到）；false=没送达（无玩家/被限流/空行）
     */
    public static synchronized boolean push(String line) {
        if (line == null || line.isBlank()) {
            return false;
        }
        String text = truncate(line.trim());
        long now = System.currentTimeMillis();

        // 去重：同内容 5 秒内只发一次
        Long last = LAST_SENT.get(text);
        if (last != null && now - last < DEDUP_MS) {
            return false;
        }
        // 限流：每分钟上限
        if (windowStart == 0 || now - windowStart > 60_000L) {
            windowStart = now;
            windowCount = 0;
        }
        if (windowCount >= MAX_PER_MINUTE) {
            SnowLeopardMod.LOG.warn("[雪豹框架][推送] 超过每分钟 {} 条上限，本条丢弃：{}",
                    MAX_PER_MINUTE, text);
            return false;
        }

        Sink s = sink;
        if (s != null) {
            s.send(text);
            markSent(text, now);
            return true;
        }

        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            SnowLeopardMod.LOG.warn("[雪豹框架][推送] 无服务器上下文，未送达：{}", text);
            return false;
        }
        int delivered = 0;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p instanceof FakePlayer) {
                continue;   // 假人能收包但没有客户端：推给它是"看起来发了"，不是送达
            }
            p.displayClientMessage(Component.literal("[雪豹] " + text), false);
            delivered++;
        }
        if (delivered == 0) {
            // 无在线玩家：落日志（不静默丢），并如实返回 false
            SnowLeopardMod.LOG.info("[雪豹框架][推送] 无在线玩家，落日志：{}", text);
            return false;
        }
        markSent(text, now);
        return true;
    }

    private static void markSent(String text, long now) {
        LAST_SENT.put(text, now);
        windowCount++;
        if (LAST_SENT.size() > 200) {
            LAST_SENT.entrySet().removeIf(e -> now - e.getValue() > DEDUP_MS * 12);
        }
    }

    private static String truncate(String text) {
        if (text.length() <= MAX_CHARS) {
            return text;
        }
        SnowLeopardMod.LOG.warn("[雪豹框架][推送] 超长截断（{} → {} 字符）", text.length(), MAX_CHARS);
        return text.substring(0, MAX_CHARS - 1) + "…";
    }
}
