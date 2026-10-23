package io.github.piwugu.snowleopard;

import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

import snowleopard.core.result.Result;

/**
 * M1 真机场景自测（2026-10-23 05:55 (UTC+8)）——专用服务器无头跑，跑完自动关机。
 *
 * <p>场景=开发流程 M1 验收全链：开感官→候选三件套→选取绑定→未选取休眠→自我观测→
 * 死亡自动关感官。断言的是<b>意图</b>（每步 Result 级别与关键字段），假成功比失败更坏——
 * 任一步不符即整体 FAIL。证据链方法沿袭 Friday：日志逐条 PASS/FAIL，同版本重跑定抖动。
 */
public final class SelfTestScenario {

    private SelfTestScenario() {
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        boolean ok = true;
        try {
            ServerLevel level = server.overworld();
            BlockPos base = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING,
                    level.getSharedSpawnPos().offset(8, 0, 8));

            // 造两只候选（僵尸：Mob 可冻结 AI）
            Zombie z1 = new Zombie(level);
            z1.moveTo(base.getX() + 0.5, base.getY(), base.getZ() + 0.5, 0, 0);
            level.addFreshEntity(z1);
            Zombie z2 = new Zombie(level);
            z2.moveTo(base.getX() + 2.5, base.getY(), base.getZ() + 0.5, 0, 0);
            level.addFreshEntity(z2);
            String ref1 = z1.getUUID().toString();
            String ref2 = z2.getUUID().toString();
            step(true, "造两只候选实体", "z1=" + ref1 + " z2=" + ref2);

            String model = "selftest-m1";

            // 1. 开感官 → 候选三件套
            Result open = EntityToolsRuntime.dispatch(model, "sense", "open", Map.of());
            boolean hasBoth = String.valueOf(data(open)).contains(ref1)
                    && String.valueOf(data(open)).contains(ref2);
            ok &= step(open instanceof Result.Ok && hasBoth, "sense.open 返回候选三件套（含两候选）",
                    String.valueOf(data(open)));

            // 2. 选取绑定 z1
            Result pick = EntityToolsRuntime.dispatch(model, "entity", "pick", Map.of("target", ref1));
            ok &= step(pick instanceof Result.Ok, "entity.pick 绑定 z1", String.valueOf(data(pick)));

            // 3. 未选取候选休眠（z2 冻 AI）
            ok &= step(z2.isNoAi(), "未选取候选进入休眠（z2 NoAi=true）", "isNoAi=" + z2.isNoAi());

            // 4. 自我观测
            Result self = EntityToolsRuntime.dispatch(model, "entity_self", "status", Map.of());
            ok &= step(self instanceof Result.Ok
                            && String.valueOf(data(self)).contains("health"),
                    "entity_self.status 全字段可读", String.valueOf(data(self)));

            // 5. 死亡流：杀 z1 → 感官自动关
            z1.hurt(level.damageSources().generic(), 1000f);
            Result st = EntityToolsRuntime.dispatch(model, "sense", "status", Map.of());
            ok &= step(st instanceof Result.Ok
                            && String.valueOf(data(st)).contains("OFF"),
                    "绑定实体死亡→感官自动关闭", String.valueOf(data(st)));

            // 6. 死亡后控制工具禁用
            Result blocked = EntityToolsRuntime.dispatch(model, "entity_self", "status", Map.of());
            ok &= step(blocked instanceof Result.Error, "死亡后控制类工具显式拒绝", String.valueOf(blocked));
        } catch (Throwable t) {
            ok = false;
            SnowLeopardMod.LOG.error("[雪豹框架][SELFTEST-M1] 场景异常", t);
        }
        SnowLeopardMod.LOG.info("[雪豹框架][SELFTEST-M1] ===== {} =====", ok ? "PASS" : "FAIL");
        server.halt(false);
    }

    private static Object data(Result r) {
        return r instanceof Result.Ok o ? o.data()
                : r instanceof Result.Warn w ? w.data()
                : r instanceof Result.Severe s ? s.data() : r;
    }

    private static boolean step(boolean cond, String name, String detail) {
        if (cond) {
            SnowLeopardMod.LOG.info("[雪豹框架][SELFTEST-M1] PASS: {} | {}", name, detail);
        } else {
            SnowLeopardMod.LOG.error("[雪豹框架][SELFTEST-M1] FAIL: {} | {}", name, detail);
        }
        return cond;
    }
}
