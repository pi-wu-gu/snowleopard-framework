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
        // 遍历单点登记表（不是句柄缓存）：没调过内置工具的模型也要能被推到（2026-10-24 05:45 (UTC+8)）
        for (String caller : ModelRegistry.known()) {
            // 自测与客户端验收模型都不推：它们的断言要"恰好一次"，巡检插一脚就会变成两次
            // （clienttest 还会让验收自己的 push 少掉唯一性证据，2026-10-23 23:12 补）
            if (caller.startsWith("selftest-") || caller.startsWith("clienttest")) {
                continue;
            }
            // **只提示"这个模型有东西可看"，绝不把 turnLine 本身推给玩家**（2026-10-28 用户报"刷屏"）。
            //
            // 原实现是 `turnLine(caller)` → 推给玩家 → `consumeTurnLine(caller)`，有两个后果：
            // ① 玩家聊天栏反复出现"提醒：新聊天 82 条"这类**给模型的内部提示**；
            // ② 更糟的是它把提醒**提前消费**掉了——等模型真被唤醒时 `turnLine` 返回 null，
            //    模型反而看不到"你有未读"。给模型的上下文被玩家的屏幕吃掉了。
            String line = WakeBridge.turnLine(caller);
            if (line == null) {
                continue;   // 没有提醒：什么都不做，不消费
            }
            // debug 级：这是"有没有东西可看"的巡检，不该刷 INFO
            SnowLeopardMod.LOG.debug("[雪豹框架][推送] {} 有待看内容（不推给玩家）：{}", caller, line);
        }
    }
}
