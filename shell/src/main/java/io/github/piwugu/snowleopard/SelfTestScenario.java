package io.github.piwugu.snowleopard;

import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

import io.github.piwugu.snowleopard.tools.FacingMath;
import snowleopard.core.result.Result;

/**
 * 真机场景自测（M1 2026-10-23 05:55 / M2 12:55 (UTC+8)）——专用服务器无头跑，跑完自动关机。
 *
 * <p>M1：开感官→候选三件套→选取绑定→未选取休眠→自我观测→死亡自动关→禁用显式拒。
 * M2：look 一致性 + look_at + <b>六条结束路径逐条触发并核对原因</b>（FridayLock 同款断言）。
 * 断言的是意图（Result 级别与关键字段）；假成功比失败更坏——任一步不符即整体 FAIL。
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

            Zombie z1 = new Zombie(level);
            z1.moveTo(base.getX() + 0.5, base.getY(), base.getZ() + 0.5, 0, 0);
            level.addFreshEntity(z1);
            Zombie z2 = new Zombie(level);
            z2.moveTo(base.getX() + 2.5, base.getY(), base.getZ() + 0.5, 0, 0);
            level.addFreshEntity(z2);
            String ref1 = z1.getUUID().toString();
            String ref2 = z2.getUUID().toString();
            step("M1", true, "造两只候选实体", "z1=" + ref1 + " z2=" + ref2);

            String model = "selftest-m1";

            Result open = EntityToolsRuntime.dispatch(model, "sense", "open", Map.of());
            boolean hasBoth = String.valueOf(data(open)).contains(ref1)
                    && String.valueOf(data(open)).contains(ref2);
            ok &= step("M1", open instanceof Result.Ok && hasBoth,
                    "sense.open 返回候选三件套（含两候选）", String.valueOf(data(open)));

            Result pick = EntityToolsRuntime.dispatch(model, "entity", "pick", Map.of("target", ref1));
            ok &= step("M1", pick instanceof Result.Ok, "entity.pick 绑定 z1", String.valueOf(data(pick)));

            ok &= step("M1", z2.isNoAi(), "未选取候选进入休眠（z2 NoAi=true）", "isNoAi=" + z2.isNoAi());

            Result self = EntityToolsRuntime.dispatch(model, "entity_self", "status", Map.of());
            ok &= step("M1", self instanceof Result.Ok
                            && String.valueOf(data(self)).contains("health"),
                    "entity_self.status 全字段可读", String.valueOf(data(self)));

            z1.hurt(level.damageSources().generic(), 1000f);
            Result st = EntityToolsRuntime.dispatch(model, "sense", "status", Map.of());
            ok &= step("M1", st instanceof Result.Ok
                            && String.valueOf(data(st)).contains("OFF"),
                    "绑定实体死亡→感官自动关闭", String.valueOf(data(st)));

            Result blocked = EntityToolsRuntime.dispatch(model, "entity_self", "status", Map.of());
            ok &= step("M1", blocked instanceof Result.Error, "死亡后控制类工具显式拒绝", String.valueOf(blocked));

            ok &= runM2(level, base, z2, ref2);
        } catch (Throwable t) {
            ok = false;
            SnowLeopardMod.LOG.error("[雪豹框架][SELFTEST] 场景异常", t);
        }
        SnowLeopardMod.LOG.info("[雪豹框架][SELFTEST] ===== {} =====", ok ? "PASS" : "FAIL");
        server.halt(false);
    }

    /** M2：look 一致性 + 六条结束路径。z2 为绑定实体（M1 死亡流后重新开感官选取）。 */
    private static boolean runM2(ServerLevel level, BlockPos base, Zombie z2, String ref2) {
        boolean ok = true;
        String model = "selftest-m2";

        Result open = EntityToolsRuntime.dispatch(model, "sense", "open", Map.of());
        ok &= step("M2", open instanceof Result.Ok, "重开感官", String.valueOf(data(open)));
        Result pick = EntityToolsRuntime.dispatch(model, "entity", "pick", Map.of("target", ref2));
        ok &= step("M2", pick instanceof Result.Ok, "绑定 z2", String.valueOf(data(pick)));

        // look 一致性
        Result look = EntityToolsRuntime.dispatch(model, "view", "look", Map.of("yaw", 90.0, "pitch", 10.0));
        Result ls = EntityToolsRuntime.dispatch(model, "view", "status", Map.of());
        ok &= step("M2", look instanceof Result.Ok
                        && String.valueOf(data(ls)).contains("yaw=90.0"),
                "look 设定与查询一致（yaw=90,pitch=10）", String.valueOf(data(ls)));

        // 目标实体 z3
        Zombie z3 = new Zombie(level);
        z3.moveTo(base.getX() + 4.5, base.getY(), base.getZ() + 0.5, 0, 0);
        level.addFreshEntity(z3);
        String ref3 = z3.getUUID().toString();

        // 路径 1：显式解锁
        EntityToolsRuntime.dispatch(model, "view", "look_at",
                Map.of("target_type", "entity", "target", ref3));
        Result un = EntityToolsRuntime.dispatch(model, "view", "unlock", Map.of());
        ok &= step("M2", String.valueOf(data(un)).contains("EXPLICIT_UNLOCK"),
                "路径1 显式解锁→EXPLICIT_UNLOCK", String.valueOf(data(un)));

        // 路径 2：方块消失
        BlockPos stone = base.offset(6, 0, 0);
        level.setBlockAndUpdate(stone, Blocks.STONE.defaultBlockState());
        EntityToolsRuntime.dispatch(model, "view", "look_at",
                Map.of("target_type", "block",
                        "target", stone.getX() + "," + stone.getY() + "," + stone.getZ()));
        level.setBlockAndUpdate(stone, Blocks.AIR.defaultBlockState());
        EntityToolsRuntime.module(model).tickViewLock();
        Result s2 = EntityToolsRuntime.dispatch(model, "view", "status", Map.of());
        ok &= step("M2", String.valueOf(data(s2)).contains("BLOCK_GONE"),
                "路径2 方块消失→BLOCK_GONE", String.valueOf(data(s2)));

        // 路径 3：实体消失
        EntityToolsRuntime.dispatch(model, "view", "look_at",
                Map.of("target_type", "entity", "target", ref3));
        z3.hurt(level.damageSources().generic(), 1000f);
        EntityToolsRuntime.module(model).tickViewLock();
        Result s3 = EntityToolsRuntime.dispatch(model, "view", "status", Map.of());
        ok &= step("M2", String.valueOf(data(s3)).contains("ENTITY_GONE"),
                "路径3 实体消失→ENTITY_GONE", String.valueOf(data(s3)));

        // 路径 4：超距 64——用 70 格外的方块。真机陷阱（2026-10-23 踩过）：无玩家服务器上
        // 传远/远生成的实体会因目标区块非 entity-ticking 被移除（走了 ENTITY_GONE）；
        // 方块在 setChunkForced+getChunk 同步加载后可读——TOO_FAR 对方块/实体同一语义。
        BlockPos far = base.offset(70, 0, 0);
        level.setChunkForced(far.getX() >> 4, far.getZ() >> 4, true);
        level.getChunk(far.getX() >> 4, far.getZ() >> 4);
        level.setBlockAndUpdate(far, Blocks.BEDROCK.defaultBlockState());
        EntityToolsRuntime.dispatch(model, "view", "look_at",
                Map.of("target_type", "block",
                        "target", far.getX() + "," + far.getY() + "," + far.getZ()));
        Result s4 = EntityToolsRuntime.dispatch(model, "view", "status", Map.of());
        ok &= step("M2", String.valueOf(data(s4)).contains("TOO_FAR"),
                "路径4 超距 64→TOO_FAR", String.valueOf(data(s4)));

        // 路径 5：超时（duration=2；lookAt 内置首 tick=age1，再 tick=age2→TIMEOUT）
        level.setBlockAndUpdate(stone, Blocks.STONE.defaultBlockState());
        EntityToolsRuntime.dispatch(model, "view", "look_at",
                Map.of("target_type", "block",
                        "target", stone.getX() + "," + stone.getY() + "," + stone.getZ(),
                        "duration_ticks", 2));
        EntityToolsRuntime.module(model).tickViewLock();
        Result s5 = EntityToolsRuntime.dispatch(model, "view", "status", Map.of());
        ok &= step("M2", String.valueOf(data(s5)).contains("TIMEOUT"),
                "路径5 超时→TIMEOUT", String.valueOf(data(s5)));

        // 路径 6：被外力夺控（直接改朝向角）
        EntityToolsRuntime.dispatch(model, "view", "look_at",
                Map.of("target_type", "block",
                        "target", stone.getX() + "," + stone.getY() + "," + stone.getZ(),
                        "face", "up"));
        z2.setYRot(45f);
        z2.setXRot(0f);
        EntityToolsRuntime.module(model).tickViewLock();
        Result s6 = EntityToolsRuntime.dispatch(model, "view", "status", Map.of());
        ok &= step("M2", String.valueOf(data(s6)).contains("EXTERNAL_CONTROL"),
                "路径6 外力夺控→EXTERNAL_CONTROL", String.valueOf(data(s6)));

        // look_at 实体的对准角≈FacingMath 预期（facing 组件共用出处）——用新鲜 z5
        // （z4 在 70 格外可能被卸载，复用被卸载实体测不出对准——2026-10-23 踩过）
        Zombie z5 = new Zombie(level);
        z5.moveTo(base.getX() + 3.5, base.getY(), base.getZ() + 2.5, 0, 0);
        level.addFreshEntity(z5);
        Result la = EntityToolsRuntime.dispatch(model, "view", "look_at",
                Map.of("target_type", "entity", "target", z5.getUUID().toString()));
        FacingMath.Angle expected = FacingMath.aim(
                z2.getX(), z2.getY() + z2.getEyeHeight(), z2.getZ(),
                z5.getX(), z5.getY() + z5.getBbHeight() * 0.5, z5.getZ());
        double yawNow = z2.getYRot();
        Result s7 = EntityToolsRuntime.dispatch(model, "view", "status", Map.of());
        ok &= step("M2", FacingMath.deltaDeg(yawNow, expected.yaw()) < 2.0,
                "look_at 对准角≈FacingMath 预期（yaw=" + yawNow + " expected=" + expected.yaw()
                        + "） look_at=" + data(la),
                String.valueOf(data(s7)));
        EntityToolsRuntime.dispatch(model, "view", "unlock", Map.of());
        return ok;
    }

    private static Object data(Result r) {
        return r instanceof Result.Ok o ? o.data()
                : r instanceof Result.Warn w ? w.data()
                : r instanceof Result.Severe s ? s.data() : r;
    }

    private static boolean step(String phase, boolean cond, String name, String detail) {
        if (cond) {
            SnowLeopardMod.LOG.info("[雪豹框架][SELFTEST-{}] PASS: {} | {}", phase, name, detail);
        } else {
            SnowLeopardMod.LOG.error("[雪豹框架][SELFTEST-{}] FAIL: {} | {}", phase, name, detail);
        }
        return cond;
    }
}
