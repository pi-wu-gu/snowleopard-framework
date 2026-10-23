package io.github.piwugu.snowleopard;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * 客户端验收的自动连接（2026-10-23 23:00 (UTC+8)，仅客户端类）。
 *
 * <p><b>为什么不用 quickPlay</b>：dev 客户端在 1.21.1 下对 {@code --quickPlaySingleplayer / --quickPlayMultiplayer}
 * 完全没有反应（主日志无任何记录、{@code --quickPlayPath} 也不写文件），试了两次都是停在标题界面。
 * 与其和启动参数较劲，不如让模组自己在标题界面出现时发起连接——效果一样是"真实客户端连真实服务器"，
 * 而且完全可控（系统属性 {@code snowleopard.autoconnect=host:port}，不设则不连）。
 *
 * <p>只在客户端加载（{@code EventBusSubscriber(value = Dist.CLIENT)}），专用服务器不会碰这些类。
 */
@EventBusSubscriber(modid = SnowLeopardMod.MODID, value = Dist.CLIENT,
        bus = EventBusSubscriber.Bus.GAME)
public final class ClientAutoConnect {

    private static boolean done;
    private static int attempts;

    private ClientAutoConnect() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (done) {
            return;
        }
        String addr = System.getProperty("snowleopard.autoconnect", "");
        if (addr.isBlank()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() != null) {
            done = true;   // 已经连上了
            return;
        }
        if (!(mc.screen instanceof TitleScreen) && mc.screen != null) {
            return;   // 还在加载/错误界面
        }
        // 标题界面出现后重试若干次：加载覆盖层还没撤时发起连接会被静默吞掉（踩过）
        if (++attempts > 6) {
            done = true;
            SnowLeopardMod.LOG.error("[雪豹框架][CLIENT] 自动连接放弃（6 次尝试均未连上）");
            return;
        }
        if (mc.getOverlay() != null) {
            return;   // 加载覆盖层还在：此时发起连接会被静默吞掉（踩过）
        }
        SnowLeopardMod.LOG.info("[雪豹框架][CLIENT] 自动连接 {}", addr);
        ServerAddress target = ServerAddress.parseString(addr);
        ServerData data = new ServerData("snowleopard-test", addr, ServerData.Type.OTHER);
        mc.execute(() -> ConnectScreen.startConnecting(new TitleScreen(), mc, target, data,
                false, null));
        // 不在这里置 done：连上之后由 getConnection() 判定成功；失败则下轮再试
    }
}
