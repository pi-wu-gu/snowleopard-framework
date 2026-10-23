package io.github.piwugu.snowleopard;

import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.monster.ZombifiedPiglin;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import snowleopard.core.SnowLeopard;
import snowleopard.core.result.Result;

/**
 * 客户端验收（真机 GUI，2026-10-23 22:45 (UTC+8)）。
 *
 * <p><b>为什么不能直接用全量自测</b>：{@code SelfTestScenario} 收尾会 {@code server.halt(false)}，
 * 在集成服务器上等于把自己客户端关掉；而且它建 8 座黑曜石塔，不适合人看的场景。
 *
 * <p>所以客户端模式只做一件事：**在真人玩家进世界后跑一遍"绑定中立生物 → 推一行到聊天栏"**，
 * 并断言 {@code ChatPush.push} 返回 true——这是专用服务器**做不到**的断言
 * （那边没有客户端，只能返回 false 落日志）。聊天栏里应当看到
 * {@code [雪豹] 提醒：群组未读 g00001(1) · 新消息 1 条}。
 *
 * <p>开关：系统属性 {@code snowleopard.clienttest=true}（build.gradle 的 client run 里给）。
 */
final class ClientSmokeTest {

    private static boolean done;
    private static int wait;

    private ClientSmokeTest() {
    }

    @SubscribeEvent
    public static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (done || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        done = true;   // 只跑一次
        // 等世界与客户端 UI 都稳定（进服那一刻聊天栏还没准备好）
        new Thread(() -> {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return;
            }
            player.server.execute(() -> run(player));
        }, "snowleopard-clienttest").start();
    }

    private static void run(ServerPlayer player) {
        String model = "clienttest";
        ServerLevel level = player.serverLevel();
        BlockPos base = player.blockPosition();
        EntityToolsRuntime.setScanAnchor(model, base);
        SnowLeopardMod.LOG.info("[雪豹框架][CLIENT] ===== 客户端验收开始（玩家 {}，位置 {}）=====",
                player.getGameProfile().getName(), base);

        // 清掉上次验收残留的载体（同名会撞"禁重名"，残骸也没必要留）
        java.util.List<io.github.piwugu.snowleopard.AvatarEntity> leftovers = new java.util.ArrayList<>();
        for (net.minecraft.world.entity.Entity e : level.getAllEntities()) {
            if (e instanceof io.github.piwugu.snowleopard.AvatarEntity av) {
                leftovers.add(av);
            }
        }
        leftovers.forEach(net.minecraft.world.entity.Entity::discard);
        // 登记表不随实体消失（卸载≠解绑）：上一轮的"小白"还挂在表里，先清自己的旧登记，
        // 否则同名绑定撞"禁重名"（只清 clienttest 的，别人的不动）
        EntityToolsRuntime.dispatch(model, "avatar", "avatar_unbind", Map.of());

        // ① 中立生物：僵尸猪灵（受击才反击），放在玩家侧前方 3 格
        ZombifiedPiglin pig = new ZombifiedPiglin(
                net.minecraft.world.entity.EntityType.ZOMBIFIED_PIGLIN, level);
        pig.setPersistenceRequired();
        pig.moveTo(player.getX() + 3, player.getY(), player.getZ() + 0.5, 0, 0);
        level.addFreshEntity(pig);
        String ref = pig.getUUID().toString();
        ok("造中立生物（僵尸猪灵）", true, ref);

        // ② 绑定
        Result open = EntityToolsRuntime.dispatch(model, "sense", "open", Map.of());
        ok("sense.open 候选可见", String.valueOf(data(open)).contains(ref), brief(open));
        Result pick = EntityToolsRuntime.dispatch(model, "entity", "pick", Map.of("target", ref));
        ok("绑定中立生物", pick instanceof Result.Ok, brief(pick));
        ok("中立旁证（未受击不锁目标）", pig.getTarget() == null, "target=" + pig.getTarget());

        // ③ 制造一条群消息 → 汇总行
        Result created = EntityToolsRuntime.dispatch(model, "group", "group_create",
                Map.of("name", "客户端验收组"));
        String gid = String.valueOf(data(created)).replaceAll(".*gid=([^,}]+).*", "$1");
        EntityToolsRuntime.dispatch(model, "group", "group_send",
                Map.of("gid", gid, "text", "客户端验收消息"));
        String line = WakeBridge.turnLine(model);
        ok("汇总行生成", line != null, String.valueOf(line));

        // ④ 推到聊天栏——真客户端下必须真的送达（专用服务器这里会返回 false）
        boolean sent = ChatPush.push(line);
        ok("聊天栏推送送达（真客户端可达）", sent, "sent=" + sent);

        // ⑤ 一条更花的、便于肉眼确认的行（含点击建议不可用，只做视觉锚点）
        ChatPush.push("客户端验收完成：绑定 " + ref.substring(0, 8) + "… · 聊天栏推送已接通");

        // ⑥ 载体（M13）：生成一只载体并绑为主载体——玩家可直接右键它测互动唤醒，
        //    在聊天栏说"小白"可测点名唤醒；名字同时是身份（[雪豹·小白]）
        io.github.piwugu.snowleopard.AvatarEntity avatar =
                new io.github.piwugu.snowleopard.AvatarEntity(
                        io.github.piwugu.snowleopard.AvatarEntities.AVATAR.get(), level);
        avatar.setPersistenceRequired();
        avatar.moveTo(player.getX() + 1.5, player.getY(), player.getZ() + 2.5, 0, 0);
        level.addFreshEntity(avatar);
        avatar.setCustomName(net.minecraft.network.chat.Component.literal("小白"));
        // 重新扫描：pick 只认 open 候选集里的 ref（不 open 直接 pick 会静默失败，
        // 绑定还停留在猪灵上 → avatar_bind 报"不是载体"）
        EntityToolsRuntime.dispatch(model, "sense", "open", Map.of());
        EntityToolsRuntime.dispatch(model, "entity", "pick",
                Map.of("target", avatar.getStringUUID()));
        Result bindAv = EntityToolsRuntime.dispatch(model, "avatar", "avatar_bind", Map.of());
        ok("载体就位（豹猫外观，右键/点名可唤醒）", bindAv instanceof Result.Ok,
                brief(bindAv) + " | 位置：你身前 2.5 格");

        // ⑦ 播报玩家测试区坐标（聊天栏可直接看到去哪）
        String area = PlayerTestArea.describe();
        if (area != null) {
            ChatPush.push(area);
        }

        // ⑧ host 自检唤醒（AI 执行端 H4）：配置了 LLM 时，host 会接走这条请求、
        //    开真会话、模型自己 chat_send 向玩家问好——真机全链路的最后一块
        SnowLeopard core = SnowLeopardMod.core();
        if (core != null && io.github.piwugu.snowleopard.host.AiHost.ready()) {
            Result wake = core.wake().request(
                    new snowleopard.core.wake.WakeStore.Chain(java.util.List.of("ext-clock")),
                    "ext-clock", model,
                    "host 自检：请用 chat_send 向玩家 " + player.getGameProfile().getName()
                            + " 打一句招呼（简短自然），然后结束本轮。",
                    "host-selftest", "host-selftest-" + System.currentTimeMillis());
            ok("host 自检唤醒已登记", !(wake instanceof Result.Error), brief(wake));
        }

        SnowLeopardMod.LOG.info("[雪豹框架][CLIENT] ===== 客户端验收结束（看游戏内聊天栏）=====");
    }

    private static void ok(String name, boolean pass, String detail) {
        if (pass) {
            SnowLeopardMod.LOG.info("[雪豹框架][CLIENT] PASS: {} | {}", name, detail);
        } else {
            SnowLeopardMod.LOG.error("[雪豹框架][CLIENT] FAIL: {} | {}", name, detail);
        }
    }

    private static Object data(Result r) {
        return switch (r) {
            case Result.Ok o -> o.data();
            case Result.Warn w -> w.data();
            case Result.Severe s -> s.data();
            case Result.Error e -> e.desc();
        };
    }

    private static String brief(Result r) {
        String s = String.valueOf(data(r));
        return s.length() > 160 ? s.substring(0, 160) + "…" : s;
    }
}
