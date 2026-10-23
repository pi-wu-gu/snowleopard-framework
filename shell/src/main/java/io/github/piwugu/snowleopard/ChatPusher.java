package io.github.piwugu.snowleopard;

import net.neoforged.bus.api.SubscribeEvent;

/**
 * 汇总行 → 聊天栏的自动驱动（M11 收口，2026-10-23 22:40 (UTC+8)）。
 *
 * <p>没有这一步，`ChatPush` 只是个"能被调用的通道"——推送本身得有人按时发起。壳里没有轮次概念，
 * 所以用固定的节奏（{@link #INTERVAL_TICKS}，10 秒）扫一遍已知模型：
 * 有汇总行就推，推成功才记账。
 *
 * <p><b>为什么"推成功才记账"</b>：模型上下文的提示与玩家聊天栏是**两个出口、一本账**。
 * 若无在线玩家（专用服务器/单人未进服），推不出去就不该标"已提示"——否则玩家回来永远看不到那一条。
 * 去重窗口（5 秒）足以防止"没推出去→下轮重试"变成刷屏。
 *
 * <p><b>自测模型不推</b>：ID 以 {@code selftest-} 开头的一律跳过——自测里注入的 sink 要断言
 * "恰好送达一次"，巡检顺带推一条会把它打成假失败。
 */
final class ChatPusher {

    /** 巡检间隔（tick）：10 秒。 */
    static final int INTERVAL_TICKS = 200;

    private static long lastTick = -1;

    private ChatPusher() {
    }

    @SubscribeEvent
    public static void onServerTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        long now = event.getServer().getTickCount();
        if (lastTick >= 0 && now - lastTick < INTERVAL_TICKS) {
            return;
        }
        lastTick = now;
        for (String caller : BuiltinRuntime.knownCallers()) {
            // 自测与客户端验收模型都不推：它们的断言要"恰好一次"，巡检插一脚就会变成两次
            // （clienttest 还会让验收自己的 push 少掉唯一性证据，2026-10-23 23:12 补）
            if (caller.startsWith("selftest-") || caller.startsWith("clienttest")) {
                continue;
            }
            String line = WakeBridge.turnLine(caller);
            if (line == null) {
                continue;
            }
            if (ChatPush.push(line)) {
                WakeBridge.consumeTurnLine(caller);   // 只有真的送达才标"已提示"
            }
        }
    }
}
