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
        ServerLevel level = null;
        BlockPos base = null;
        try {
            level = server.overworld();
            base = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING,
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
            ok &= runM3(level, base);
            ok &= runM5(level, base);
        } catch (Throwable t) {
            ok = false;
            SnowLeopardMod.LOG.error("[雪豹框架][SELFTEST] 场景异常", t);
        }
        // M1–M3 同步段完成；M4 需要真实物理 tick——交给 tick 驱动器（结束时统一 halt）
        M4.start(server, level, base, ok);
    }

    /** M4 每 tick 驱动（与实体物理同拍）。 */
    @SubscribeEvent
    public static void onServerTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        M4.tick();
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

    /** M3：交互四动作（服务端回推）+ 寻址红线 + 硬方块超时报因。 */
    private static boolean runM3(ServerLevel level, BlockPos base) {
        boolean ok = true;
        String model = "selftest-m3";

        // 造化身 z6 与受害者 z7
        Zombie z6 = new Zombie(level);
        z6.moveTo(base.getX() + 0.5, base.getY(), base.getZ() + 4.5, 0, 0);
        level.addFreshEntity(z6);
        Zombie z7 = new Zombie(level);
        z7.moveTo(base.getX() + 1.5, base.getY(), base.getZ() + 6.5, 0, 0);
        level.addFreshEntity(z7);
        EntityToolsRuntime.dispatch(model, "sense", "open", Map.of());
        Result pick = EntityToolsRuntime.dispatch(model, "entity", "pick",
                Map.of("target", z6.getUUID().toString()));
        ok &= step("M3", pick instanceof Result.Ok, "绑定化身 z6", String.valueOf(data(pick)));

        // 攻击：完成判定=血量变化回推
        Result atk = EntityToolsRuntime.dispatch(model, "interact", "attack",
                Map.of("target", z7.getUUID().toString()));
        ok &= step("M3", atk instanceof Result.Ok
                        && String.valueOf(data(atk)).contains("healthAfter="),
                "attack 命中（血量回推）", String.valueOf(data(atk)));

        // 寻址红线：他人编号实体一律拒绝
        String other = "selftest-m3-other";
        Zombie z8 = new Zombie(level);
        z8.moveTo(base.getX() + 2.5, base.getY(), base.getZ() + 6.5, 0, 0);
        level.addFreshEntity(z8);
        EntityToolsRuntime.dispatch(other, "sense", "open", Map.of());
        EntityToolsRuntime.dispatch(other, "entity", "pick",
                Map.of("target", z8.getUUID().toString()));
        Result deny = EntityToolsRuntime.dispatch(model, "interact", "attack",
                Map.of("target", z8.getUUID().toString()));
        ok &= step("M3", deny instanceof Result.Error
                        && String.valueOf(deny).contains("其他编号"),
                "红线：攻击他人编号实体被拒", String.valueOf(deny));

        // 放置：完成判定=方块出现回推；自动面向（未锁定时）
        BlockPos p1 = base.offset(3, 1, 3);
        Result place = EntityToolsRuntime.dispatch(model, "interact", "place",
                Map.of("target", p1.getX() + "," + p1.getY() + "," + p1.getZ(),
                        "block", "minecraft:stone"));
        ok &= step("M3", place instanceof Result.Ok && !level.getBlockState(p1).isAir(),
                "place 方块出现（服务端回推）", String.valueOf(data(place)));
        FacingMath.Angle aim = FacingMath.aim(
                z6.getX(), z6.getY() + z6.getEyeHeight(), z6.getZ(),
                p1.getX() + 0.5, p1.getY() + 0.5, p1.getZ() + 0.5);
        ok &= step("M3", FacingMath.deltaDeg(z6.getYRot(), aim.yaw()) < 2.0,
                "place 自动面向目标（FacingMath）", "yaw=" + z6.getYRot() + " expected=" + aim.yaw());

        // 破坏：持续动作（石头硬度 1.5 → 8 tick）→ 完成=方块真没了（移除后同 tick 回推封签）
        Result brk = EntityToolsRuntime.dispatch(model, "interact", "break",
                Map.of("target", p1.getX() + "," + p1.getY() + "," + p1.getZ()));
        for (int i = 0; i < 12 && !level.getBlockState(p1).isAir(); i++) {
            EntityToolsRuntime.module(model).tickInteract();
        }
        Result bs = EntityToolsRuntime.dispatch(model, "interact", "status", Map.of());
        ok &= step("M3", brk instanceof Result.Ok && level.getBlockState(p1).isAir()
                        && String.valueOf(data(bs)).contains("COMPLETED"),
                "break 硬方块挖完（方块真没了+COMPLETED）",
                "brk=" + data(brk) + " " + data(bs));

        // 硬方块超时报因（黑曜石硬度 50 → 需求 250 tick，预算 3）
        BlockPos p2 = base.offset(-3, 1, 3);
        level.setBlockAndUpdate(p2, net.minecraft.world.level.block.Blocks.OBSIDIAN.defaultBlockState());
        Result brk2 = EntityToolsRuntime.dispatch(model, "interact", "break",
                Map.of("target", p2.getX() + "," + p2.getY() + "," + p2.getZ(), "max_ticks", 3));
        for (int i = 0; i < 5; i++) {
            EntityToolsRuntime.module(model).tickInteract();
        }
        Result ts = EntityToolsRuntime.dispatch(model, "interact", "status", Map.of());
        ok &= step("M3", brk2 instanceof Result.Ok
                        && String.valueOf(data(ts)).contains("TIMEOUT")
                        && !level.getBlockState(p2).isAir(),
                "硬方块超时报因（黑曜石仍在）", "brk2=" + data(brk2) + " " + data(ts));

        // 使用：音符盒按一下→音高变化（服务端状态回推）
        BlockPos p3 = base.offset(0, 1, -3);
        level.setBlockAndUpdate(p3, net.minecraft.world.level.block.Blocks.NOTE_BLOCK.defaultBlockState());
        int noteBefore = level.getBlockState(p3).getValue(
                net.minecraft.world.level.block.NoteBlock.NOTE);
        Result use = EntityToolsRuntime.dispatch(model, "interact", "use",
                Map.of("target", p3.getX() + "," + p3.getY() + "," + p3.getZ()));
        int noteAfter = level.getBlockState(p3).getValue(
                net.minecraft.world.level.block.NoteBlock.NOTE);
        ok &= step("M3", use instanceof Result.Ok && noteAfter != noteBefore,
                "use 方块交互生效（音高 " + noteBefore + "→" + noteAfter + "）", String.valueOf(data(use)));
        return ok;
    }

    /**
     * M4 tick 驱动器（2026-10-23 14:15 (UTC+8)）：三驱动/goto/阻塞/不可达需要真实物理位移——
     * 每 tick 推进一个阶段（装配→等待真 tick→断言），跑完统一报告并关机。
     */
    private static final class M4 {

        private static net.minecraft.server.MinecraftServer server;
        private static ServerLevel level;
        private static BlockPos base;
        private static boolean ok;
        private static int phase;
        private static int wait;
        private static Zombie z9;
        private static double sx, sy, sz;
        private static double maxY;

        static void start(net.minecraft.server.MinecraftServer s, ServerLevel lv, BlockPos b, boolean okSoFar) {
            server = s;
            level = lv;
            base = b;
            ok = okSoFar;
            phase = 0;
            wait = 0;
            level.setDayTime(18000);   // 夜间——防僵尸白日燃烧干扰测量
        }

        static void tick() {
            if (server == null || phase > 8) {
                return;
            }
            try {
                String model = "selftest-m4";
                switch (phase) {
                    case 0 -> {   // 造化身（剥 AI 目标=压掉游荡/攻击但保留物理——
                        // setNoAi 会连物理一起冻住（僵尸飘空、脚下无支撑），2026-10-23 踩过）
                        z9 = new Zombie(level);
                        z9.moveTo(base.getX() + 0.5, base.getY(), base.getZ() - 8.5, 180, 0);
                        level.addFreshEntity(z9);
                        z9.goalSelector.removeAllGoals(g -> true);
                        z9.targetSelector.removeAllGoals(g -> true);
                        EntityToolsRuntime.dispatch(model, "sense", "open", Map.of());
                        Result p = EntityToolsRuntime.dispatch(model, "entity", "pick",
                                Map.of("target", z9.getUUID().toString()));
                        ok &= step("M4", p instanceof Result.Ok, "绑定化身 z9（剥 AI 保物理）", String.valueOf(data(p)));
                        sx = z9.getX();
                        sy = z9.getY();
                        sz = z9.getZ();
                        next();
                    }
                    case 1 -> {   // 距离驱动（净位移判向，不绑轴——方向随朝向而变）
                        if (wait == 0) {
                            sx = z9.getX();
                            sz = z9.getZ();
                            EntityToolsRuntime.dispatch(model, "move", "move",
                                    Map.of("direction", "forward", "distance", 1.5));
                        }
                        wait++;
                        Result st = EntityToolsRuntime.dispatch(model, "move", "status", Map.of());
                        if (String.valueOf(data(st)).contains("DONE") || wait > 40) {
                            double moved = Math.sqrt(Math.pow(z9.getX() - sx, 2) + Math.pow(z9.getZ() - sz, 2));
                            ok &= step("M4", String.valueOf(data(st)).contains("DONE") && moved >= 1.0,
                                    "距离驱动 DONE（净位移 " + String.format("%.2f", moved) + "）", String.valueOf(data(st)));
                            sx = z9.getX();
                            sz = z9.getZ();
                            next();
                        }
                    }
                    case 2 -> {   // 时长驱动（净位移判向）
                        if (wait == 0) {
                            sx = z9.getX();
                            sz = z9.getZ();
                            EntityToolsRuntime.dispatch(model, "move", "move",
                                    Map.of("direction", "left", "duration_ticks", 10));
                        }
                        wait++;
                        Result st = EntityToolsRuntime.dispatch(model, "move", "status", Map.of());
                        if (String.valueOf(data(st)).contains("DONE") || wait > 30) {
                            double moved = Math.sqrt(Math.pow(z9.getX() - sx, 2) + Math.pow(z9.getZ() - sz, 2));
                            ok &= step("M4", String.valueOf(data(st)).contains("DONE") && moved >= 0.8,
                                    "时长驱动 DONE（净位移 " + String.format("%.2f", moved) + "）", String.valueOf(data(st)));
                            next();
                        }
                    }
                    case 3 -> {   // 蹲是独立姿态
                        EntityToolsRuntime.dispatch(model, "move", "sneak", Map.of("on", true));
                        boolean c1 = z9.isShiftKeyDown();
                        EntityToolsRuntime.dispatch(model, "move", "sneak", Map.of("on", false));
                        boolean c2 = !z9.isShiftKeyDown();
                        ok &= step("M4", c1 && c2, "蹲伏姿态独立开关", "on=" + c1 + " off=" + c2);
                        next();
                    }
                    case 4 -> {   // 跳跃（峰值对起跳前高度）
                        if (wait == 0) {
                            sy = z9.getY();
                            maxY = sy;
                            Result j = EntityToolsRuntime.dispatch(model, "move", "jump", Map.of());
                            ok &= step("M4", j instanceof Result.Ok, "跳跃起跳（脚下支撑）", String.valueOf(data(j)));
                        }
                        wait++;
                        maxY = Math.max(maxY, z9.getY());
                        if (wait >= 10) {
                            ok &= step("M4", maxY > sy + 0.5,
                                    "跳跃离地（峰值 " + String.format("%.2f", maxY)
                                            + " 起跳 " + String.format("%.2f", sy) + "）", "");
                            sy = z9.getY();
                            next();
                        }
                    }
                    case 5 -> {   // goto：DONE=连续 2 tick 贴住
                        if (wait == 0) {
                            EntityToolsRuntime.dispatch(model, "move", "goto",
                                    Map.of("target", (base.getX() + 3) + "," + base.getY() + "," + base.getZ(),
                                            "max_ticks", 60));
                        }
                        wait++;
                        Result st = EntityToolsRuntime.dispatch(model, "move", "status", Map.of());
                        if (String.valueOf(data(st)).contains("DONE") || wait > 60) {
                            double d = Math.sqrt(Math.pow(z9.getX() - (base.getX() + 3.5), 2)
                                    + Math.pow(z9.getZ() - base.getZ(), 2));
                            ok &= step("M4", String.valueOf(data(st)).contains("DONE") && d < 1.5,
                                    "goto DONE（距目标 " + String.format("%.2f", d) + "）", String.valueOf(data(st)));
                            next();
                        }
                    }
                    case 6 -> {   // stop 即停
                        if (wait == 0) {
                            EntityToolsRuntime.dispatch(model, "move", "goto",
                                    Map.of("target", (base.getX() + 15) + "," + base.getY() + "," + base.getZ()));
                        }
                        wait++;
                        if (wait == 3) {
                            EntityToolsRuntime.dispatch(model, "move", "stop", Map.of());
                        }
                        if (wait >= 5) {
                            Result st = EntityToolsRuntime.dispatch(model, "move", "status", Map.of());
                            ok &= step("M4", !String.valueOf(data(st)).contains("PROGRESS"),
                                    "stop 即停（中断移动）", String.valueOf(data(st)));
                            next();
                        }
                    }
                    case 7 -> {   // 阻塞：封进石笼 → 12 tick 无水平位移 → BLOCKED
                        if (wait == 0) {
                            BlockPos c = z9.blockPosition();
                            for (var d : new net.minecraft.core.Direction[]{
                                    net.minecraft.core.Direction.NORTH, net.minecraft.core.Direction.SOUTH,
                                    net.minecraft.core.Direction.EAST, net.minecraft.core.Direction.WEST}) {
                                level.setBlockAndUpdate(c.relative(d),
                                        net.minecraft.world.level.block.Blocks.OBSIDIAN.defaultBlockState());
                            }
                            EntityToolsRuntime.dispatch(model, "move", "goto",
                                    Map.of("target", (base.getX() + 15) + "," + base.getY() + "," + base.getZ(),
                                            "max_ticks", 60));
                        }
                        wait++;
                        if (wait >= 25) {
                            Result st = EntityToolsRuntime.dispatch(model, "move", "status", Map.of());
                            ok &= step("M4", String.valueOf(data(st)).contains("BLOCKED"),
                                    "卡墙上报阻塞（非假成功）", String.valueOf(data(st)));
                            BlockPos c = z9.blockPosition();
                            for (var d : new net.minecraft.core.Direction[]{
                                    net.minecraft.core.Direction.NORTH, net.minecraft.core.Direction.SOUTH,
                                    net.minecraft.core.Direction.EAST, net.minecraft.core.Direction.WEST}) {
                                level.setBlockAndUpdate(c.relative(d), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
                            }
                            next();
                        }
                    }
                    default -> {   // 8：三种不可达显式报因（预检，同步即可）→ 收口
                        Result r1 = EntityToolsRuntime.dispatch(model, "move", "goto",
                                Map.of("target", base.getX() + "," + (base.getY() + 6) + "," + base.getZ()));
                        ok &= step("M4", r1 instanceof Result.Error && String.valueOf(r1).contains("高差"),
                                "不可达：高差超阈值报因", String.valueOf(r1));
                        BlockPos water = base.offset(6, 0, -6);
                        level.setBlockAndUpdate(water, net.minecraft.world.level.block.Blocks.WATER.defaultBlockState());
                        Result r2 = EntityToolsRuntime.dispatch(model, "move", "goto",
                                Map.of("target", water.getX() + "," + water.getY() + "," + water.getZ()));
                        ok &= step("M4", r2 instanceof Result.Error && String.valueOf(r2).contains("流体"),
                                "不可达：目标在流体中报因", String.valueOf(r2));
                        Result r3 = EntityToolsRuntime.dispatch(model, "move", "goto",
                                Map.of("target", (base.getX() + 500) + "," + base.getY() + "," + base.getZ()));
                        ok &= step("M4", r3 instanceof Result.Error && String.valueOf(r3).contains("未加载"),
                                "不可达：目标区块未加载报因", String.valueOf(r3));
                        next();
                        SnowLeopardMod.LOG.info("[雪豹框架][SELFTEST] ===== {} =====", ok ? "PASS" : "FAIL");
                        server.halt(false);
                    }
                }
            } catch (Throwable t) {
                ok = false;
                SnowLeopardMod.LOG.error("[雪豹框架][SELFTEST-M4] 场景异常", t);
                SnowLeopardMod.LOG.info("[雪豹框架][SELFTEST] ===== FAIL =====");
                server.halt(false);
            }
        }

        private static void next() {
            phase++;
            wait = 0;
        }
    }

    /** M5：物品（双索引/容器三寻址/slot 移动）+ 世界（两种形状/归属标注/双护栏）。全同步。 */
    private static boolean runM5(ServerLevel level, BlockPos base) {
        boolean ok = true;
        String model = "selftest-m5";

        Zombie z10 = new Zombie(level);
        z10.moveTo(base.getX() + 0.5, base.getY(), base.getZ() - 14.5, 0, 0);
        level.addFreshEntity(z10);
        z10.goalSelector.removeAllGoals(g -> true);
        z10.targetSelector.removeAllGoals(g -> true);
        z10.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE, 10));
        EntityToolsRuntime.dispatch(model, "sense", "open", Map.of());
        Result pick = EntityToolsRuntime.dispatch(model, "entity", "pick",
                Map.of("target", z10.getUUID().toString()));
        ok &= step("M5", pick instanceof Result.Ok, "绑定化身 z10", String.valueOf(data(pick)));

        // 双索引：registry 与显示名各命中同一件
        Result q1 = EntityToolsRuntime.dispatch(model, "item", "self_items",
                Map.of("query", "minecraft:stone"));
        Result q2 = EntityToolsRuntime.dispatch(model, "item", "self_items",
                Map.of("query", "Stone"));
        ok &= step("M5", String.valueOf(data(q1)).contains("minecraft:stone")
                        && String.valueOf(data(q2)).contains("minecraft:stone"),
                "双索引：registry 与显示名命中同一件", "q1=" + data(q1) + " q2=" + data(q2));

        // 容器三寻址
        BlockPos cp = z10.blockPosition().offset(1, 0, 0);   // 正东贴身——raycast 确定性命中
        level.setBlockAndUpdate(cp, net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState());
        net.minecraft.world.Container chest =
                (net.minecraft.world.Container) level.getBlockEntity(cp);
        chest.setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIRT, 10));
        Result c1 = EntityToolsRuntime.dispatch(model, "item", "container_items",
                Map.of("target", cp.getX() + "," + cp.getY() + "," + cp.getZ()));
        ok &= step("M5", String.valueOf(data(c1)).contains("minecraft:dirt"),
                "容器寻址①：坐标命中", String.valueOf(data(c1)));
        Result c2 = EntityToolsRuntime.dispatch(model, "item", "container_items",
                Map.of("target", "self"));
        ok &= step("M5", String.valueOf(data(c2)).contains("self:mainhand"),
                "容器寻址②：自身命中", String.valueOf(data(c2)));
        FacingMath.Angle aim = FacingMath.aim(z10.getX(), z10.getY() + z10.getEyeHeight(), z10.getZ(),
                cp.getX() + 0.5, cp.getY() + 0.5, cp.getZ() + 0.5);
        EntityToolsRuntime.dispatch(model, "view", "look",
                Map.of("yaw", aim.yaw(), "pitch", aim.pitch()));   // 走产品路径（头/身同步）
        Result c3 = EntityToolsRuntime.dispatch(model, "item", "container_items", Map.of());
        ok &= step("M5", String.valueOf(data(c3)).contains("raycast:")
                        && String.valueOf(data(c3)).contains("minecraft:dirt"),
                "容器寻址③：视线 raycast 命中",
                "aim=" + aim + " rot=" + z10.getYRot() + "/" + z10.getXRot()
                        + " pos=" + z10.position() + " eye=" + z10.getEyePosition(1.0f)
                        + " view=" + z10.getViewVector(1.0f) + " cp=" + cp + " → " + data(c3));

        // slot 移动：拆分数量精确 + 服务端槽位核实
        Result mv = EntityToolsRuntime.dispatch(model, "item", "move_item",
                Map.of("from", cp.getX() + "," + cp.getY() + "," + cp.getZ() + ":0",
                        "to", "self:offhand", "count", 3));
        ok &= step("M5", mv instanceof Result.Ok
                        && String.valueOf(data(mv)).contains("fromRemaining=7")
                        && String.valueOf(data(mv)).contains("toCount=3"),
                "move_item 拆分精确（10→7+3）", String.valueOf(data(mv)));

        // 丢弃：槽位减少（服务端事实）
        Result dp = EntityToolsRuntime.dispatch(model, "item", "drop",
                Map.of("from", "self:mainhand", "count", 2));
        ok &= step("M5", dp instanceof Result.Ok
                        && String.valueOf(data(dp)).contains("fromRemaining=8"),
                "drop 槽位核实（10→8）", String.valueOf(data(dp)));

        // 世界查询①：中心+半径+含实体（归属标注：z2 仍绑定 selftest-m2；半径要罩住 15 格外的 z2）
        Result w1 = EntityToolsRuntime.dispatch(model, "world", "query",
                Map.of("radius", 20, "include_entities", true));
        ok &= step("M5", w1 instanceof Result.Ok
                        && String.valueOf(data(w1)).contains("controller=selftest-m2")
                        && String.valueOf(data(w1)).contains("仅已加载区块"),
                "world.query 中心形状（实体带 controller 归属 + 已加载声明）", String.valueOf(data(w1)));

        // 世界查询②：框选命中箱子
        Result w2 = EntityToolsRuntime.dispatch(model, "world", "query",
                Map.of("box", (cp.getX()) + "," + (cp.getY()) + "," + (cp.getZ()) + ","
                        + (cp.getX() + 1) + "," + (cp.getY() + 1) + "," + (cp.getZ() + 1)));
        ok &= step("M5", String.valueOf(data(w2)).contains("minecraft:chest"),
                "world.query 框选形状命中箱子", String.valueOf(data(w2)));

        // 双护栏：超量截断标记
        Result w3 = EntityToolsRuntime.dispatch(model, "world", "query",
                Map.of("radius", 30, "include_entities", true, "max_chars", 100));
        ok &= step("M5", String.valueOf(data(w3)).contains("truncated=true"),
                "返回量上限截断标记", "truncated in " + String.valueOf(data(w3)).substring(0, 80));
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
