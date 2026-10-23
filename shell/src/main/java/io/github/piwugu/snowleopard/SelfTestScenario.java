package io.github.piwugu.snowleopard;

import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.block.Blocks;
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
            // 一测一房（2026-10-23 裁决）：封闭单元塔——建房=清场+封壳，层距 56>2×扫描半径，
            // 层间互不可见。M1→M8 各占一层（index 0..7），残留/游荡不串房。
            BlockPos[] rooms = TestCells.buildAll(level, 8);
            m4Room = rooms[3];   // 先定 M4 房——后面同步段异常也不能让 tick 驱动器拿到 null
            m8Room = rooms[7];   // M8 同理（它由 M4 的收口 phase 启动）
            base = rooms[0];
            step("M1", true, "建 8 座封闭测试房（一测一房）",
                    "层距=" + TestCells.PITCH + " 内空=" + TestCells.INTERIOR + "x" + TestCells.HEIGHT);
            EntityToolsRuntime.setScanAnchor("selftest-m1", rooms[0]);

            Zombie z1 = new Zombie(level);

            z1.setPersistenceRequired();   // 测试实体=固定装置：禁止自然消失（checkDespawn 远距静默 discard 坑，2026-10-23 踩过）
            z1.moveTo(base.getX() + 0.5, base.getY(), base.getZ() + 0.5, 0, 0);
            level.addFreshEntity(z1);
            z1.goalSelector.removeAllGoals(g -> true);   // 剥 AI=环境确定性（游荡会堵 M4 goto 目标）
            z1.targetSelector.removeAllGoals(g -> true);
            Zombie z2 = new Zombie(level);
            z2.setPersistenceRequired();   // 测试实体=固定装置：禁止自然消失（checkDespawn 远距静默 discard 坑，2026-10-23 踩过）
            z2.moveTo(base.getX() + 2.5, base.getY(), base.getZ() + 0.5, 0, 0);
            level.addFreshEntity(z2);
            z2.goalSelector.removeAllGoals(g -> true);
            z2.targetSelector.removeAllGoals(g -> true);
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

            ok &= runM2(level, rooms[1], z2, ref2);
            ok &= runM3(level, rooms[2]);
            ok &= runM5(level, rooms[4]);
            ok &= runM6(level, rooms[5]);
            ok &= runM7(level, rooms[6]);
            ok &= runM9();   // 内置四组走壳侧统一入口（文件能力，不需要房间）
            ok &= runM10();  // 协作面：唤醒 + 群组（同样不需要房间）
            ok &= runM11(level, rooms[7]);   // 聊天栏推送 + 中立生物绑定（借 M8 房，用完即杀）
            ok &= runM12();  // 聊天工具组：记录/注入块/两段式/提示与已读两维/搜索（文件能力）
            ok &= runM13(level, rooms[6]);   // 载体：绑定/定时/提及唤醒（借 M7 房，用完即杀）
        } catch (Throwable t) {
            ok = false;
            SnowLeopardMod.LOG.error("[雪豹框架][SELFTEST] 场景异常", t);
        }
        // M1–M3 同步段完成；M4 需要真实物理 tick——交给 tick 驱动器（结束时统一 halt）
        M4.start(server, level, m4Room, ok);
    }

    /** M4 的房间基准（tick 驱动器在同步段之后才启动，经此中转）。 */
    private static BlockPos m4Room;

    /** M8 的房间基准（两个模型同居一房——隔离要验的是模型级，不是房间级）。 */
    private static BlockPos m8Room;

    /** M4 每 tick 驱动（与实体物理同拍）。 */
    @SubscribeEvent
    public static void onServerTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        M4.tick();
        M8.tick();
    }

    /** M2：look 一致性 + 六条结束路径。z2 为绑定实体（M1 死亡流后重新开感官选取，从 M1 房搬入）。 */
    private static boolean runM2(ServerLevel level, BlockPos base, Zombie z2, String ref2) {
        boolean ok = true;
        String model = "selftest-m2";
        EntityToolsRuntime.setScanAnchor(model, base);
        z2.teleportTo(base.getX() + 2.5, base.getY(), base.getZ() + 0.5);   // 搬进本房（一测一房）

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
        z3.setPersistenceRequired();   // 测试实体=固定装置：禁止自然消失（checkDespawn 远距静默 discard 坑，2026-10-23 踩过）
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
        level.setBlockAndUpdate(far, Blocks.AIR.defaultBlockState());   // 远距方块用后即清（房外专用位，防跨运行残留）

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
        z5.setPersistenceRequired();   // 测试实体=固定装置：禁止自然消失（checkDespawn 远距静默 discard 坑，2026-10-23 踩过）
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
        EntityToolsRuntime.setScanAnchor(model, base);
        EntityToolsRuntime.setScanAnchor("selftest-m3-other", base);

        // 造化身 z6 与受害者 z7
        Zombie z6 = new Zombie(level);
        z6.setPersistenceRequired();   // 测试实体=固定装置：禁止自然消失（checkDespawn 远距静默 discard 坑，2026-10-23 踩过）
        z6.moveTo(base.getX() + 0.5, base.getY(), base.getZ() + 4.5, 0, 0);
        level.addFreshEntity(z6);
        Zombie z7 = new Zombie(level);
        z7.setPersistenceRequired();   // 测试实体=固定装置：禁止自然消失（checkDespawn 远距静默 discard 坑，2026-10-23 踩过）
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
        z8.setPersistenceRequired();   // 测试实体=固定装置：禁止自然消失（checkDespawn 远距静默 discard 坑，2026-10-23 踩过）
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
        // 防御式读取：方块不是音符盒（如建房超建筑高度 setBlock 静默失败）→ FAIL 带因，不炸整个场景
        if (!level.getBlockState(p3).hasProperty(net.minecraft.world.level.block.NoteBlock.NOTE)) {
            ok &= step("M3", false, "use 方块交互生效",
                    "p3 处不是音符盒（实际 " + level.getBlockState(p3) + "）——setBlock 可能静默失败（超高？）");
            return ok;
        }
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
        private static BlockPos cagePos;
        private static double sx, sy, sz;
        private static double maxY;

        static void start(net.minecraft.server.MinecraftServer s, ServerLevel lv, BlockPos b, boolean okSoFar) {
            server = s;
            level = lv;
            base = b;
            ok = okSoFar;
            phase = 0;
            wait = 0;
            EntityToolsRuntime.setScanAnchor("selftest-m4", b);
            level.setDayTime(18000);   // 夜间——防僵尸白日燃烧干扰测量
        }

        static void tick() {
            if (server == null || level == null || base == null || phase > 8) {
                return;
            }
            try {
                String model = "selftest-m4";
                switch (phase) {
                    case 0 -> {   // 造化身（剥 AI 目标=压掉游荡/攻击但保留物理——
                        // setNoAi 会连物理一起冻住（僵尸飘空、脚下无支撑），2026-10-23 踩过）
                        z9 = new Zombie(level);
                        z9.setPersistenceRequired();   // 测试实体=固定装置：禁止自然消失（checkDespawn 远距静默 discard 坑，2026-10-23 踩过）
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
                            // 居中到格心再封笼：偏心时命中框蹭墙→碰撞微推抖动骗过阻塞计数
                            // （2026-10-23 踩过：位移≈0 却 PROGRESS——产品口径是冻结契约，修的是测试几何）
                            z9.teleportTo(c.getX() + 0.5, z9.getY(), c.getZ() + 0.5);
                            cagePos = c;
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
                            // 拆笼用建笼坐标（按 z9 现位拆会错位留墙——2026-10-23 踩过）
                            BlockPos c = cagePos != null ? cagePos : z9.blockPosition();
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
                        // 收口交给 M8（多模型并发）：它结束时统一打印总结并 halt
                        M8.start(server, level, m8Room, ok);
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

    /**
     * M8：多模型并发（两个模型**同居一房**——要验的是模型级隔离，不是房间级）。
     *
     * <p>六件事：① 各自绑定互不影响（含"别人的绑定实体进不了我的候选集"）；② 归属反查对得上；
     * ③ 跨模型红线（互相攻击都被拒）；④ 监视流互不串；⑤ 两台移动机器同 tick 并发各自推进；
     * ⑥ 一个模型 stop 不影响另一个（各自的机器是各自一份）。
     *
     * <p>为什么必须真机：隔离靠的是"每模型一个 EntityToolsModule"这个结构事实，
     * headless 单测造不出两个模块共用世界的情形。
     */
    private static final class M8 {

        private static net.minecraft.server.MinecraftServer server;
        private static ServerLevel level;
        private static BlockPos base;
        private static boolean ok;
        private static int phase;
        private static int wait;
        private static final String MA = "selftest-m8a";
        private static final String MB = "selftest-m8b";
        private static Zombie zA;
        private static Zombie zB;
        private static String refA;
        private static String refB;

        static void start(net.minecraft.server.MinecraftServer s, ServerLevel lv, BlockPos b, boolean okSoFar) {
            server = s;
            level = lv;
            base = b;
            ok = okSoFar;
            phase = 0;
            wait = 0;
            EntityToolsRuntime.setScanAnchor(MA, b);
            EntityToolsRuntime.setScanAnchor(MB, b);
        }

        static void tick() {
            if (server == null || level == null || base == null || phase > 6) {
                return;
            }
            try {
                switch (phase) {
                    case 0 -> {   // 造两实体 → 两模型各自 open/pick（同房共存）
                        Zombie a = new Zombie(level);
                        a.setPersistenceRequired();   // 测试实体=固定装置：禁止远距静默 discard
                        a.moveTo(base.getX() - 6.5, base.getY(), base.getZ() + 0.5, 0, 0);
                        level.addFreshEntity(a);
                        a.goalSelector.removeAllGoals(g -> true);
                        a.targetSelector.removeAllGoals(g -> true);
                        Zombie b = new Zombie(level);
                        b.setPersistenceRequired();
                        b.moveTo(base.getX() + 6.5, base.getY(), base.getZ() + 0.5, 0, 0);
                        level.addFreshEntity(b);
                        b.goalSelector.removeAllGoals(g -> true);
                        b.targetSelector.removeAllGoals(g -> true);
                        zA = a;
                        zB = b;
                        refA = a.getUUID().toString();
                        refB = b.getUUID().toString();

                        Result oa = EntityToolsRuntime.dispatch(MA, "sense", "open", Map.of());
                        ok &= step("M8", oa instanceof Result.Ok
                                        && String.valueOf(data(oa)).contains(refA),
                                "A 开感官见本房候选", String.valueOf(data(oa)));
                        Result pa = EntityToolsRuntime.dispatch(MA, "entity", "pick",
                                Map.of("target", refA));
                        ok &= step("M8", pa instanceof Result.Ok, "A 绑定 zA", String.valueOf(data(pa)));

                        Result ob = EntityToolsRuntime.dispatch(MB, "sense", "open", Map.of());
                        ok &= step("M8", ob instanceof Result.Ok
                                        && !String.valueOf(data(ob)).contains(refA),
                                "B 的候选集不含 A 的绑定实体（扫描级隔离）", String.valueOf(data(ob)));
                        Result pb = EntityToolsRuntime.dispatch(MB, "entity", "pick",
                                Map.of("target", refB));
                        ok &= step("M8", pb instanceof Result.Ok, "B 绑定 zB", String.valueOf(data(pb)));

                        // 跨模型休眠不互相睡死：A 的 pick 会休眠它自己的其余候选（含 zB），
                        // B 绑定 zB 时必须把它唤醒（pick 里补的 wake——本场景发现的产品缺陷）
                        ok &= step("M8", !zA.isNoAi() && !zB.isNoAi(),
                                "两绑定实体都保持活动（跨模型不睡死）",
                                "noAi zA=" + zA.isNoAi() + " zB=" + zB.isNoAi());
                        next();
                    }
                    case 1 -> {   // 归属反查：各自指向自己的实体
                        Result sa = EntityToolsRuntime.dispatch(MA, "entity", "status", Map.of());
                        Result sb = EntityToolsRuntime.dispatch(MB, "entity", "status", Map.of());
                        ok &= step("M8", String.valueOf(data(sa)).contains(refA)
                                        && !String.valueOf(data(sa)).contains(refB),
                                "A 归属指向 zA（不含 zB）", String.valueOf(data(sa)));
                        ok &= step("M8", String.valueOf(data(sb)).contains(refB)
                                        && !String.valueOf(data(sb)).contains(refA),
                                "B 归属指向 zB（不含 zA）", String.valueOf(data(sb)));
                        next();
                    }
                    case 2 -> {   // 跨模型红线：互相攻击都被拒
                        Result atkA = EntityToolsRuntime.dispatch(MB, "interact", "attack",
                                Map.of("target", refA));
                        ok &= step("M8", atkA instanceof Result.Error
                                        && String.valueOf(atkA).contains("红线"),
                                "B 攻击 A 的实体被拒（红线）", String.valueOf(atkA));
                        Result atkB = EntityToolsRuntime.dispatch(MA, "interact", "attack",
                                Map.of("target", refB));
                        ok &= step("M8", atkB instanceof Result.Error
                                        && String.valueOf(atkB).contains("红线"),
                                "A 攻击 B 的实体被拒（红线）", String.valueOf(atkB));
                        next();
                    }
                    case 3 -> {   // 监视流互不串（绑定即入册，各自只含自己）
                        Result la = EntityToolsRuntime.dispatch(MA, "watch", "watch_list", Map.of());
                        Result lb = EntityToolsRuntime.dispatch(MB, "watch", "watch_list", Map.of());
                        ok &= step("M8", String.valueOf(data(la)).contains(refA)
                                        && !String.valueOf(data(la)).contains(refB),
                                "A 的监视列表只含 zA", String.valueOf(data(la)));
                        ok &= step("M8", String.valueOf(data(lb)).contains(refB)
                                        && !String.valueOf(data(lb)).contains(refA),
                                "B 的监视列表只含 zB", String.valueOf(data(lb)));
                        next();
                    }
                    case 4 -> {   // 并发移动：同一 tick 各发一个 goto（相反方向）
                        String ta = (base.getX() - 2.5) + "," + base.getY() + "," + (base.getZ() + 0.5);
                        String tb = (base.getX() + 2.5) + "," + base.getY() + "," + (base.getZ() + 0.5);
                        Result ga = EntityToolsRuntime.dispatch(MA, "move", "goto",
                                Map.of("target", ta, "max_ticks", 80));
                        Result gb = EntityToolsRuntime.dispatch(MB, "move", "goto",
                                Map.of("target", tb, "max_ticks", 80));
                        ok &= step("M8", ga instanceof Result.Ok && gb instanceof Result.Ok,
                                "两模型同 tick 各起一台移动机器", "A=" + data(ga) + " B=" + data(gb));
                        next();
                    }
                    case 5 -> {   // 半途停 A：A 真中断，B 继续（两台机器各自一份，互不覆盖）
                        wait++;
                        if (wait < 6) {
                            return;
                        }
                        Result stopA = EntityToolsRuntime.dispatch(MA, "move", "stop", Map.of());
                        ok &= step("M8", stopA instanceof Result.Ok
                                        && String.valueOf(data(stopA)).contains("stopped"),
                                "A 半途停机（有移动在进行中）", String.valueOf(stopA));
                        Result sa = EntityToolsRuntime.dispatch(MA, "move", "status", Map.of());
                        Result sb = EntityToolsRuntime.dispatch(MB, "move", "status", Map.of());
                        ok &= step("M8", String.valueOf(data(sa)).contains("outcome=TIMEOUT"),
                                "A 报中断（TIMEOUT，不是假到达）", String.valueOf(data(sa)));
                        ok &= step("M8", String.valueOf(data(sb)).contains("mode=GOTO")
                                        && !String.valueOf(data(sb)).contains("outcome=TIMEOUT"),
                                "B 不受 A 停机影响（仍在 GOTO）", String.valueOf(data(sb)));
                        next();
                    }
                    default -> {   // 6：B 继续到达 + A 停在半途 → 收口
                        wait++;
                        if (wait < 40) {
                            return;
                        }
                        Result sa = EntityToolsRuntime.dispatch(MA, "move", "status", Map.of());
                        Result sb = EntityToolsRuntime.dispatch(MB, "move", "status", Map.of());
                        ok &= step("M8", String.valueOf(data(sb)).contains("outcome=DONE"),
                                "B 继续推进并到达（A 停机没打断 B）", String.valueOf(data(sb)));
                        ok &= step("M8", String.valueOf(data(sa)).contains("outcome=TIMEOUT")
                                        && !String.valueOf(data(sa)).contains("outcome=DONE"),
                                "A 停在半途未到达（状态未被 B 覆盖）", String.valueOf(data(sa)));
                        phase = 7;   // halt 在 tick 末生效，先关闸防重入
                        SnowLeopardMod.LOG.info("[雪豹框架][SELFTEST] ===== {} =====", ok ? "PASS" : "FAIL");
                        server.halt(false);
                    }
                }
            } catch (Throwable t) {
                ok = false;
                phase = 7;
                SnowLeopardMod.LOG.error("[雪豹框架][SELFTEST-M8] 场景异常", t);
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
        EntityToolsRuntime.setScanAnchor(model, base);
        String modelB = "selftest-m5b";   // 归属标注对照：第二模型编号（房内自足，不跨房借实体）
        EntityToolsRuntime.setScanAnchor(modelB, base);

        Zombie z10 = new Zombie(level);

        z10.setPersistenceRequired();   // 测试实体=固定装置：禁止自然消失（checkDespawn 远距静默 discard 坑，2026-10-23 踩过）
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

        // 归属标注对照实体 z11：挂 modelB（世界查询须标 controller=selftest-m5b）
        Zombie z11 = new Zombie(level);
        z11.setPersistenceRequired();   // 测试实体=固定装置：禁止自然消失（checkDespawn 远距静默 discard 坑，2026-10-23 踩过）
        z11.moveTo(base.getX() + 3.5, base.getY(), base.getZ() - 10.5, 0, 0);
        level.addFreshEntity(z11);
        z11.goalSelector.removeAllGoals(g -> true);
        z11.targetSelector.removeAllGoals(g -> true);
        EntityToolsRuntime.dispatch(modelB, "sense", "open", Map.of());
        EntityToolsRuntime.dispatch(modelB, "entity", "pick",
                Map.of("target", z11.getUUID().toString()));

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

        // 世界查询①：中心+半径+含实体（归属标注：z11 挂 selftest-m5b——房内自足对照）
        Result w1 = EntityToolsRuntime.dispatch(model, "world", "query",
                Map.of("radius", 20, "include_entities", true));
        ok &= step("M5", w1 instanceof Result.Ok
                        && String.valueOf(data(w1)).contains("controller=" + modelB)
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

    /** M6：动态感知——自动入册/controller 归属/轨迹限频/保留窗口/退订/坏钩子隔离。 */
    private static boolean runM6(ServerLevel level, BlockPos base) {
        boolean ok = true;
        String model = "selftest-m6";
        EntityToolsRuntime.setScanAnchor(model, base);

        Zombie z13 = new Zombie(level);

        z13.setPersistenceRequired();   // 测试实体=固定装置：禁止自然消失（checkDespawn 远距静默 discard 坑，2026-10-23 踩过）
        z13.moveTo(base.getX() + 9.5, base.getY(), base.getZ() - 16.5, 0, 0);
        level.addFreshEntity(z13);
        z13.goalSelector.removeAllGoals(g -> true);
        z13.targetSelector.removeAllGoals(g -> true);
        String ref13 = z13.getUUID().toString();
        Zombie z14 = new Zombie(level);
        z14.setPersistenceRequired();   // 测试实体=固定装置：禁止自然消失（checkDespawn 远距静默 discard 坑，2026-10-23 踩过）
        z14.moveTo(base.getX() + 11.5, base.getY(), base.getZ() - 16.5, 0, 0);
        level.addFreshEntity(z14);
        z14.goalSelector.removeAllGoals(g -> true);
        z14.targetSelector.removeAllGoals(g -> true);
        String ref14 = z14.getUUID().toString();
        Zombie z15 = new Zombie(level);
        z15.setPersistenceRequired();   // 测试实体=固定装置：禁止自然消失（checkDespawn 远距静默 discard 坑，2026-10-23 踩过）
        z15.moveTo(base.getX() + 13.5, base.getY(), base.getZ() - 16.5, 0, 0);
        level.addFreshEntity(z15);
        z15.goalSelector.removeAllGoals(g -> true);
        z15.targetSelector.removeAllGoals(g -> true);
        String ref15 = z15.getUUID().toString();

        // 绑定 z13（绑定实体自动入册，controller=控制模型编号——裁决 §1.4）
        EntityToolsRuntime.dispatch(model, "sense", "open", Map.of());
        EntityToolsRuntime.dispatch(model, "entity", "pick", Map.of("target", ref13));
        Result l1 = EntityToolsRuntime.dispatch(model, "watch", "watch_list", Map.of());
        ok &= step("M6", String.valueOf(data(l1)).contains(ref13)
                        && String.valueOf(data(l1)).contains("controller=" + model),
                "绑定实体自动入册 + controller=控制模型编号", String.valueOf(data(l1)));

        // 显式监视未绑定实体 → controller=空（混合归属：绑定=模型编号 / 无主=空）
        EntityToolsRuntime.dispatch(model, "watch", "watch", Map.of("target", ref14));
        Result l2 = EntityToolsRuntime.dispatch(model, "watch", "watch_list", Map.of());
        ok &= step("M6", String.valueOf(data(l2)).contains(ref14),
                "watch 显式入册 z14（列表含 z14）", String.valueOf(data(l2)));

        // 轨迹限频：同 tick 两次 tickWatch → 每监视实体恰 1 条 TRAJECTORY（10 tick 内不重采）
        // 计数按 kind=TRAJECTORY 精确匹配（kinds 枚举说明字段含同名字样，2026-10-23 计数踩过）
        EntityToolsRuntime.module(model).tickWatch();
        EntityToolsRuntime.module(model).tickWatch();
        Result e1 = EntityToolsRuntime.dispatch(model, "watch", "watch_events",
                Map.of("target", ref13, "limit", 100));
        String s1 = String.valueOf(data(e1));
        long traj13 = s1.split("kind=TRAJECTORY", -1).length - 1;
        ok &= step("M6", traj13 == 1,
                "轨迹限频：同 tick 二次采样被拒（TRAJECTORY×" + traj13 + "）", s1);

        // 攻击源动态：z15 伤 z13 → ATTACKED 记录 + 攻击者自动入册
        z13.hurt(level.damageSources().mobAttack(z15), 2f);
        Result e2 = EntityToolsRuntime.dispatch(model, "watch", "watch_events",
                Map.of("target", ref13, "limit", 100));
        ok &= step("M6", String.valueOf(data(e2)).contains("ATTACKED")
                        && String.valueOf(data(e2)).contains(ref15),
                "攻击源动态：ATTACKED 带攻击者", String.valueOf(data(e2)));
        Result l3 = EntityToolsRuntime.dispatch(model, "watch", "watch_list", Map.of());
        ok &= step("M6", String.valueOf(data(l3)).contains(ref15),
                "攻击者自动入册（z15 进监视列表）", String.valueOf(data(l3)));

        // 退订：unwatch 后攻击者不再被自动加回（unwatch=退订语义）
        EntityToolsRuntime.dispatch(model, "watch", "unwatch", Map.of("target", ref15));
        z13.hurt(level.damageSources().mobAttack(z15), 1f);
        Result l4 = EntityToolsRuntime.dispatch(model, "watch", "watch_list", Map.of());
        ok &= step("M6", !String.valueOf(data(l4)).contains(ref15),
                "unwatch 退订：攻击源不再自动加回", String.valueOf(data(l4)));

        // 保留窗口：70 次攻击记录 → 条数上限 64，不涨内存
        for (int i = 0; i < 70; i++) {
            EntityToolsRuntime.module(model).onHurt(ref13, "", 1.0, EntityToolsRuntime::controllerOf);
        }
        Result e3 = EntityToolsRuntime.dispatch(model, "watch", "watch_events",
                Map.of("target", ref13, "limit", 100));
        String s3 = String.valueOf(data(e3));
        long total13 = s3.split("tick=", -1).length - 1;
        ok &= step("M6", total13 == 64,
                "保留窗口条数上限（70 攻击后存 " + total13 + " ≤64）", "count in feed=" + total13);

        // 死亡 → DIED 动态
        z14.hurt(level.damageSources().generic(), 1000f);
        Result e4 = EntityToolsRuntime.dispatch(model, "watch", "watch_events",
                Map.of("target", ref14, "limit", 100));
        ok &= step("M6", String.valueOf(data(e4)).contains("DIED"),
                "死亡 → DIED 动态记录", String.valueOf(data(e4)));

        // 触发挂点：坏钩子注入不杀主链（TriggerRegistry 钩子级隔离——坏钩子先跑也无妨）
        final boolean[] goodRan = {false};
        EntityToolsRuntime.triggers().register(new snowleopard.core.trigger.TriggerRegistry.Hook() {
            @Override
            public String groupFull() {
                return "mod:snowleopard:bad";
            }

            @Override
            public void fire(snowleopard.core.trigger.TriggerRegistry.Point point) {
                throw new RuntimeException("故意坏钩子");
            }
        });
        EntityToolsRuntime.triggers().register(new snowleopard.core.trigger.TriggerRegistry.Hook() {
            @Override
            public String groupFull() {
                return "mod:snowleopard:good";
            }

            @Override
            public void fire(snowleopard.core.trigger.TriggerRegistry.Point point) {
                goodRan[0] = true;
            }
        });
        var hits = EntityToolsRuntime.triggers().fire(snowleopard.core.trigger.TriggerRegistry.Point.SESSION_END);
        boolean badIsolated = hits.stream().anyMatch(h -> "mod:snowleopard:bad".equals(h.groupFull())
                && h.result() instanceof Result.Severe);
        boolean chainAlive = goodRan[0]
                && EntityToolsRuntime.dispatch(model, "watch", "watch_list", Map.of()) instanceof Result.Ok;
        ok &= step("M6", badIsolated && chainAlive,
                "坏钩子不杀主链（SEVERE 隔离 + 后序钩子照跑 + 工具面存活）",
                "badIsolated=" + badIsolated + " goodRan=" + goodRan[0] + " hits=" + hits.size());

        // 枚举与记录对账：动态 kind 全在写死枚举内
        Result e5 = EntityToolsRuntime.dispatch(model, "watch", "watch_events", Map.of("limit", 100));
        String s5 = String.valueOf(data(e5));
        boolean kindsClosed = !s5.contains("kind=") || s5.contains("TRAJECTORY")
                || s5.contains("ATTACKED") || s5.contains("DIED");
        ok &= step("M6", kindsClosed && e5 instanceof Result.Ok,
                "主动查询枚举对账（kind∈TRAJECTORY|ATTACKED|DIED）",
                String.valueOf(data(e5)).length() > 200
                        ? String.valueOf(data(e5)).substring(0, 200) : String.valueOf(data(e5)));
        return ok;
    }

    /** M7：攻击玩家审批链（两段式 confirm_token）——首段签发不执行/坏令牌拒/跨目标 digest 失效/核销执行/单次使用。 */
    private static boolean runM7(ServerLevel level, BlockPos base) {
        boolean ok = true;
        String model = "selftest-m7";
        EntityToolsRuntime.setScanAnchor(model, base);

        Zombie z16 = new Zombie(level);

        z16.setPersistenceRequired();   // 测试实体=固定装置：禁止自然消失（checkDespawn 远距静默 discard 坑，2026-10-23 踩过）
        z16.moveTo(base.getX() + 15.5, base.getY(), base.getZ() - 16.5, 0, 0);
        level.addFreshEntity(z16);
        z16.goalSelector.removeAllGoals(g -> true);
        z16.targetSelector.removeAllGoals(g -> true);
        String ref16 = z16.getUUID().toString();
        EntityToolsRuntime.dispatch(model, "sense", "open", Map.of());
        EntityToolsRuntime.dispatch(model, "entity", "pick", Map.of("target", ref16));

        // ① 非玩家目标直通（审批只针对玩家类型目标）
        Zombie z17 = new Zombie(level);
        z17.setPersistenceRequired();   // 测试实体=固定装置：禁止自然消失（checkDespawn 远距静默 discard 坑，2026-10-23 踩过）
        z17.moveTo(base.getX() + 16.8, base.getY(), base.getZ() - 16.5, 0, 0);
        level.addFreshEntity(z17);
        z17.goalSelector.removeAllGoals(g -> true);
        z17.targetSelector.removeAllGoals(g -> true);
        String ref17 = z17.getUUID().toString();
        Result direct = EntityToolsRuntime.dispatch(model, "interact", "attack",
                Map.of("target", ref17));
        ok &= step("M7", direct instanceof Result.Ok
                        && !String.valueOf(data(direct)).contains("pending"),
                "非玩家目标直通（无需审批）", String.valueOf(data(direct)));

        // 造玩家类型目标（FakePlayer extends ServerPlayer → instanceof Player 命中审批）。
        // 显式放房内——不 moveTo 会落世界原点，房外残留跨运行存续（测试卫生）。
        var fp1 = new net.neoforged.neoforge.common.util.FakePlayer(level,
                new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "m7-victim1"));
        fp1.setPos(base.getX() + 6.5, base.getY(), base.getZ() + 2.5);
        level.addFreshEntity(fp1);
        String refFp1 = fp1.getUUID().toString();
        var fp2 = new net.neoforged.neoforge.common.util.FakePlayer(level,
                new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "m7-victim2"));
        fp2.setPos(base.getX() + 8.5, base.getY(), base.getZ() + 2.5);
        level.addFreshEntity(fp2);
        String refFp2 = fp2.getUUID().toString();

        // ② 首段：无令牌 → Warn 签发确认单，不执行（血量不动）
        float hp1 = fp1.getHealth();
        Result first = EntityToolsRuntime.dispatch(model, "interact", "attack",
                Map.of("target", refFp1));
        String fs = String.valueOf(data(first));
        String token1 = data(first) instanceof Map<?, ?> m1 && m1.get("confirm_token") != null
                ? String.valueOf(m1.get("confirm_token")) : "";
        ok &= step("M7", first instanceof Result.Warn && !token1.isEmpty()
                        && fp1.getHealth() == hp1,
                "首段签发不执行（Warn+令牌，血量 " + hp1 + " 不动）",
                fs.length() > 160 ? fs.substring(0, 160) : fs);

        // ③ 坏令牌拒绝（真令牌不受影响）
        Result bad = EntityToolsRuntime.dispatch(model, "interact", "attack",
                Map.of("target", refFp1, "confirm_token", "bogus-token"));
        ok &= step("M7", bad instanceof Result.Error,
                "坏令牌拒绝（Error，不执行）", String.valueOf(bad));

        // ④ 跨目标：fp1 的令牌拿去打 fp2 → digest 不符（防看 A 打 B）
        Result cross = EntityToolsRuntime.dispatch(model, "interact", "attack",
                Map.of("target", refFp2, "confirm_token", token1));
        ok &= step("M7", cross instanceof Result.Error
                        && String.valueOf(cross).contains("变化"),
                "跨目标令牌失效（digest 绑定目标，防看 A 打 B）", String.valueOf(cross));

        // ⑤ 重签 + 核销：带令牌二次调用 → 过审批（结果不再是 pending 签发）
        Result reissue = EntityToolsRuntime.dispatch(model, "interact", "attack",
                Map.of("target", refFp1));
        String token2 = data(reissue) instanceof Map<?, ?> m2 && m2.get("confirm_token") != null
                ? String.valueOf(m2.get("confirm_token")) : "";
        Result exec = EntityToolsRuntime.dispatch(model, "interact", "attack",
                Map.of("target", refFp1, "confirm_token", token2));
        ok &= step("M7", exec != null && !token2.isEmpty()
                        && !String.valueOf(data(exec)).contains("默认走审批"),
                "核销令牌过审批（执行段，不再是签发）",
                String.valueOf(data(exec)));

        // ⑥ 单次使用：同令牌再来 → 无效
        Result reuse = EntityToolsRuntime.dispatch(model, "interact", "attack",
                Map.of("target", refFp1, "confirm_token", token2));
        ok &= step("M7", reuse instanceof Result.Error
                        && String.valueOf(reuse).contains("无效"),
                "令牌单次使用（复用→无效）", String.valueOf(reuse));
        return ok;
    }

    /**
     * M9：内置四组（会话/记忆/任务/笔记）走壳侧统一入口。
     *
     * <p>验的是**接线**而不是核心能力（核心侧 42 条已锁）：① 清单进注册表、组简介进上下文；
     * ② 调用经 {@code EntityToolsRuntime.dispatch} 分流到核心执行面；③ 两个模型的家**盘上分开**
     * （不只看返回值——内存隔离盘上串是最难发现的形态）；④ 两段式确认在壳里照样生效；
     * ⑤ 失败是一个失败（未知工具 → Error 带查清单提示）。
     *
     * <p>不需要测试房：这四组是文件能力，产物落在 {@code <gameDir>/snowleopard/ids/<模型>/}。
     * 开工先清本测试自己的两个家（只删测试 ID 目录），否则跨运行残留会让"看不见"断言假失败。
     */
    private static boolean runM9() {
        boolean ok = true;
        String ma = "selftest-m9a";
        String mb = "selftest-m9b";

        if (SnowLeopardMod.core() == null) {
            return step("M9", false, "核心已装配（内置四组全靠它）", "core=null——见启动日志装配失败原因");
        }

        for (String id : new String[]{ma, mb}) {
            java.nio.file.Path home = BuiltinRuntime.home(id);
            if (home != null) {
                deleteTree(home);
                BuiltinRuntime.forget(id);   // 句柄一起丢：重建即恢复（文件视图）
            }
        }

        // ① 注入面：四组已在注册表，且上下文只进组简介（详解不注入）
        java.util.List<String> fulls = java.util.List.copyOf(
                SnowLeopardMod.core().registry().names().fullNames());
        boolean allGroups = true;
        for (String g : snowleopard.core.builtin.BuiltinGroups.GROUP_NAMES) {
            allGroups &= fulls.contains("builtin:core:" + g);
        }
        ok &= step("M9", allGroups, "注册表含内置四组（builtin:core:*）", fulls.toString());
        java.util.List<String> briefs = BuiltinRuntime.briefs();
        ok &= step("M9", briefs.size() == 6
                        && !String.join("\n", briefs).contains("ISO 周跨月"),
                "注入面只有组简介（详解不注入）", briefs.toString());

        // ② 执行面：待办 A 写 A 读，B 看不见
        Result t1 = EntityToolsRuntime.dispatch(ma, "todo", "todo_add", Map.of("text", "M9-待办"));
        ok &= step("M9", t1 instanceof Result.Ok, "壳侧分发 todo_add → Ok", String.valueOf(t1));
        Result la = EntityToolsRuntime.dispatch(ma, "todo", "todo_list", Map.of());
        Result lb = EntityToolsRuntime.dispatch(mb, "todo", "todo_list", Map.of());
        ok &= step("M9", String.valueOf(data(la)).contains("M9-待办"),
                "A 看到自己的待办", String.valueOf(data(la)));
        ok &= step("M9", lb instanceof Result.Ok && !String.valueOf(data(lb)).contains("M9-待办"),
                "B 看不到 A 的待办（跨 ID 隔离）", String.valueOf(data(lb)));

        // ③ 笔记：A 写读通，B 读不到（各自的家）
        Result c1 = EntityToolsRuntime.dispatch(ma, "notes", "note_create",
                Map.of("path", "m9/note.md", "content", "第一段"));
        ok &= step("M9", c1 instanceof Result.Ok, "A note_create → Ok", String.valueOf(c1));
        Result ra = EntityToolsRuntime.dispatch(ma, "notes", "note_read", Map.of("path", "m9/note.md"));
        ok &= step("M9", String.valueOf(data(ra)).contains("第一段"),
                "A 读回自己的笔记", String.valueOf(data(ra)));
        Result rb = EntityToolsRuntime.dispatch(mb, "notes", "note_read", Map.of("path", "m9/note.md"));
        ok &= step("M9", rb instanceof Result.Error, "B 读不到 A 的笔记", String.valueOf(rb));

        // ④ 两段式：首段给令牌且不删 → 带令牌移入回收站
        Result d1 = EntityToolsRuntime.dispatch(ma, "notes", "note_delete",
                Map.of("path", "m9/note.md"));
        String token = tokenOf(d1);
        boolean stillThere = EntityToolsRuntime.dispatch(ma, "notes", "note_read",
                Map.of("path", "m9/note.md")) instanceof Result.Ok;
        ok &= step("M9", d1 instanceof Result.Ok && !token.isBlank() && stillThere,
                "note_delete 首段给令牌且未删", String.valueOf(d1));
        Result d2 = EntityToolsRuntime.dispatch(ma, "notes", "note_delete",
                Map.of("path", "m9/note.md", "confirm_token", token));
        ok &= step("M9", d2 instanceof Result.Ok && String.valueOf(data(d2)).contains(".trash"),
                "带令牌 → 移入回收站", String.valueOf(d2));

        // ⑤ 记忆：空档 → Error 且说明"稀疏是常态"；纠错两段式
        Result m1 = EntityToolsRuntime.dispatch(ma, "memory", "memory_read",
                Map.of("level", "day", "period", "2000-01-01"));
        ok &= step("M9", m1 instanceof Result.Error && String.valueOf(m1).contains("稀疏"),
                "空档记忆 → Error 且说明稀疏（不是故障）", String.valueOf(m1));
        Result m2 = EntityToolsRuntime.dispatch(ma, "memory", "memory_correct",
                Map.of("action", "add", "target_ref", "day:2000-01-01", "note", "M9-口径"));
        String ctoken = tokenOf(m2);
        ok &= step("M9", m2 instanceof Result.Ok && !ctoken.isBlank(),
                "memory_correct 首段给令牌", String.valueOf(m2));
        Result m3 = EntityToolsRuntime.dispatch(ma, "memory", "memory_correct", Map.of(
                "action", "add", "target_ref", "day:2000-01-01", "note", "M9-口径",
                "confirm_token", ctoken));
        ok &= step("M9", m3 instanceof Result.Ok && String.valueOf(data(m3)).contains("M9-口径"),
                "带令牌 → 纠错落盘", String.valueOf(m3));

        // ⑥ 失败形态：未知工具 → Error 且提示查清单（不许静默成功）
        Result u1 = EntityToolsRuntime.dispatch(ma, "todo", "todo_boom", Map.of());
        ok &= step("M9", u1 instanceof Result.Error && String.valueOf(u1).contains("tool_search"),
                "未知工具 → Error 且提示查清单", String.valueOf(u1));

        // ⑦ 盘上事实：两个家的 todo.json 各自独立（内存隔离不代表盘上隔离）
        java.nio.file.Path ta = BuiltinRuntime.home(ma).resolve("todo").resolve("todo.json");
        java.nio.file.Path tb = BuiltinRuntime.home(mb).resolve("todo").resolve("todo.json");
        String ca = readFile(ta);
        String cb = readFile(tb);
        ok &= step("M9", ca.contains("M9-待办") && !cb.contains("M9-待办"),
                "盘上分开落盘（A 有 / B 无）", ta + "=" + ca.length() + "B · " + tb + "=" + cb.length() + "B");
        return ok;
    }

    private static String creatorOf(java.nio.file.Path meta) {
        return fieldOf(meta, "creator");
    }

    private static String fieldOf(java.nio.file.Path jsonFile, String key) {
        try {
            String text = readFile(jsonFile);
            if (text.isBlank()) {
                return "";
            }
            Object v = snowleopard.core.json.Json.parseObject(text).get(key);
            return v == null ? "" : String.valueOf(v);
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    private static String tokenOf(Result r) {
        Object d = data(r);
        if (d instanceof Map<?, ?> m) {
            Object v = m.get("confirm_token");
            return v == null ? "" : String.valueOf(v);
        }
        return "";
    }

    private static String readFile(java.nio.file.Path p) {
        try {
            return java.nio.file.Files.exists(p)
                    ? java.nio.file.Files.readString(p, java.nio.charset.StandardCharsets.UTF_8)
                    : "";
        } catch (java.io.IOException e) {
            return "";
        }
    }

    /** 只用于清掉本测试自己的家目录（测试卫生）；失败不炸场景，交由后续断言报。 */
    private static void deleteTree(java.nio.file.Path dir) {
        if (dir == null || !java.nio.file.Files.exists(dir)) {
            return;
        }
        try (var walk = java.nio.file.Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    java.nio.file.Files.deleteIfExists(p);
                } catch (java.io.IOException ignored) {
                    // 单个文件删不掉不阻断（后续断言会暴露）
                }
            });
        } catch (java.io.IOException ignored) {
            // 同上
        }
    }


    /**
     * M10：协作面（唤醒 + 群组）从 MC 侧走通（M5 接线验收）。
     *
     * <p>关键一条：**壳不假装执行唤起**——`WakeBridge` 只给 `pending()` 与"明确拒绝并说明原因"，
     * 真执行属于外部 LLM 宿主。所以这里断言的是"请求登记 → 可见 → 有据可查地失败"，
     * 而不是"唤起成功"（那会是假成功）。
     */
    private static boolean runM10() {
        boolean ok = true;
        String ma = "selftest-m10a";
        String mb = "selftest-m10b";
        String mc = "selftest-m10c";
        String md = "selftest-m10d";

        // 卫生：只清本测试自己造的东西（家目录 + 本测试创建的群/唤醒请求）
        for (String id : new String[]{ma, mb, mc, md}) {
            java.nio.file.Path home = BuiltinRuntime.home(id);
            if (home != null) {
                deleteTree(home);
                BuiltinRuntime.forget(id);
                try {
                    // 清完必须重建空家：group_add 要求"目标已登记"（家目录存在）——
                    // 只清不建会让第一次加人报"未登记"（2026-10-23 踩过，连锁 6 条假失败）
                    java.nio.file.Files.createDirectories(BuiltinRuntime.home(id));
                } catch (java.io.IOException e) {
                    ok &= step("M10", false, "重建测试 ID 的家", String.valueOf(e));
                }
            }
        }
        java.nio.file.Path shared = SnowLeopardMod.core().ids().shared();
        cleanTestGroups(shared, "selftest-m10");
        cleanTestWakes(shared, "selftest-m10");

        // ① 六组都在注册表（唤醒/群组随核心 0.5.0 自动注册，壳无需复制 schema）
        java.util.List<String> fulls = java.util.List.copyOf(
                SnowLeopardMod.core().registry().names().fullNames());
        ok &= step("M10", fulls.contains("builtin:core:wake") && fulls.contains("builtin:core:group"),
                "注册表含唤醒与群组两组", fulls.toString());

        // ② 群组：建群 → 加人 → 普通成员加人被拒 → 任命管理 → 管理加人
        Result c1 = EntityToolsRuntime.dispatch(ma, "group", "group_create", Map.of("name", "M10组"));
        String gid = String.valueOf(data(c1));
        gid = gid.contains("gid=") ? gid.replaceAll(".*gid=([^,}]+).*", "$1") : "";
        ok &= step("M10", c1 instanceof Result.Ok && gid.matches("g\\d{5}"),
                "建群 → g<seq>", String.valueOf(c1));
        ok &= step("M10", EntityToolsRuntime.dispatch(ma, "group", "group_add",
                        Map.of("gid", gid, "model_id", mb)) instanceof Result.Ok,
                "创建者加人 → Ok", "");
        Result memberAdd = EntityToolsRuntime.dispatch(mb, "group", "group_add",
                Map.of("gid", gid, "model_id", mc));
        ok &= step("M10", memberAdd instanceof Result.Error
                        && String.valueOf(memberAdd).contains("管理"),
                "普通成员加人 → Error（需管理级）", String.valueOf(memberAdd));
        ok &= step("M10", EntityToolsRuntime.dispatch(ma, "group", "group_grant",
                        Map.of("gid", gid, "model_id", mb, "role", "admin")) instanceof Result.Ok,
                "创建者任命管理 → Ok", "");
        ok &= step("M10", EntityToolsRuntime.dispatch(mb, "group", "group_add",
                        Map.of("gid", gid, "model_id", mc)) instanceof Result.Ok,
                "管理加人 → Ok", "");

        // ③ 发消息 / 读未读 / 非成员拒绝
        Result s1 = EntityToolsRuntime.dispatch(ma, "group", "group_send",
                Map.of("gid", gid, "text", "M10 第一条"));
        ok &= step("M10", s1 instanceof Result.Ok && String.valueOf(data(s1)).contains("seq=1"),
                "发消息 → seq=1", String.valueOf(s1));
        Result readC = EntityToolsRuntime.dispatch(mc, "group", "group_read",
                Map.of("gid", gid, "limit", 10));
        ok &= step("M10", String.valueOf(data(readC)).contains("M10 第一条"),
                "成员读到消息", String.valueOf(data(readC)));
        Result readD = EntityToolsRuntime.dispatch(md, "group", "group_read",
                Map.of("gid", gid, "limit", 10));
        ok &= step("M10", readD instanceof Result.Error
                        && String.valueOf(readD).contains("非成员"),
                "非成员读 → Error", String.valueOf(readD));

        // ④ 引用三态（本 ID 家的笔记 → ok → stale → missing）
        EntityToolsRuntime.dispatch(ma, "notes", "note_create",
                Map.of("path", "m10/ref.md", "content", "初始"));
        Result refOk = EntityToolsRuntime.dispatch(ma, "group", "group_send",
                Map.of("gid", gid, "text", "带引用", "ref", "note:m10/ref.md"));
        ok &= step("M10", String.valueOf(data(refOk)).contains("state=ok"),
                "引用 → state=ok", String.valueOf(data(refOk)));
        EntityToolsRuntime.dispatch(ma, "notes", "note_create",
                Map.of("path", "m10/ref.md", "content", "改过"));
        Result refStale = EntityToolsRuntime.dispatch(mc, "group", "group_read",
                Map.of("gid", gid, "limit", 10, "since_seq", 0));
        ok &= step("M10", String.valueOf(data(refStale)).contains("state=stale"),
                "源变更 → state=stale", String.valueOf(data(refStale)));
        Result delNote = EntityToolsRuntime.dispatch(ma, "notes", "note_delete",
                Map.of("path", "m10/ref.md"));
        String tok = String.valueOf(data(delNote)).replaceAll(".*confirm_token=([^,}]+).*", "$1");
        EntityToolsRuntime.dispatch(ma, "notes", "note_delete",
                Map.of("path", "m10/ref.md", "confirm_token", tok));
        Result refMiss = EntityToolsRuntime.dispatch(mc, "group", "group_read",
                Map.of("gid", gid, "limit", 10, "since_seq", 0));
        ok &= step("M10", String.valueOf(data(refMiss)).contains("state=missing"),
                "源丢失 → state=missing", String.valueOf(data(refMiss)));

        // ⑤ 唤醒：登记 → pending 可见 → 在运行则失败 → 明确拒绝（不假装成功）
        Result w1 = EntityToolsRuntime.dispatch(ma, "wake", "wake",
                Map.of("model_id", mb, "prompt", "巡检", "reason", "M10", "client_req_id", "m10-1"));
        String reqId = String.valueOf(data(w1)).replaceAll(".*reqId=([^,}]+).*", "$1");
        ok &= step("M10", w1 instanceof Result.Ok && reqId.startsWith("w"),
                "唤起 → 登记为 pending", String.valueOf(w1));
        ok &= step("M10", WakeBridge.pending().stream().anyMatch(r -> r.reqId().equals(reqId)),
                "宿主 poll 能看到待执行请求（壳不自动执行）", "pending=" + WakeBridge.pending().size());

        ok &= step("M10", WakeBridge.beginSession(mb), "β 会话开始（运行态权威：锁文件）", "");
        Result w2 = EntityToolsRuntime.dispatch(ma, "wake", "wake",
                Map.of("model_id", mb, "reason", "M10-dup"));
        ok &= step("M10", w2 instanceof Result.Error && String.valueOf(w2).contains("正在运行"),
                "目标在运行 → Error（不排队）", String.valueOf(w2));
        WakeBridge.endSession(mb);

        Result w3 = EntityToolsRuntime.dispatch(ma, "wake", "wake", Map.of("model_id", ma));
        ok &= step("M10", w3 instanceof Result.Error && String.valueOf(w3).contains("自身"),
                "自唤起 → Error", String.valueOf(w3));

        Result reject = WakeBridge.rejectUnrunnable(reqId,
                "框架壳没有 LLM 会话驱动——请求已登记，需外部宿主执行");
        ok &= step("M10", reject instanceof Result.Ok
                        && String.valueOf(data(reject)).contains("failed"),
                "无法执行时明确回写 failed（不造假成功）", String.valueOf(reject));
        Result st = EntityToolsRuntime.dispatch(ma, "wake", "wake_status", Map.of("req_id", reqId));
        ok &= step("M10", String.valueOf(data(st)).contains("failed")
                        && String.valueOf(data(st)).contains("LLM"),
                "发起方查状态 → failed 且原因可见", String.valueOf(data(st)));

        // ⑥ 汇总一行（群未读每轮报；新消息只报一次）——先制造一条未读（γ 已读完 1、2）
        Result s3 = EntityToolsRuntime.dispatch(mb, "group", "group_send",
                Map.of("gid", gid, "text", "M10 第三条"));
        ok &= step("M10", s3 instanceof Result.Ok, "管理再发一条（制造未读）", String.valueOf(s3));
        String line = WakeBridge.turnLine(mc);
        ok &= step("M10", line != null && line.contains("群组未读"),
                "轮末汇总含群未读", String.valueOf(line));
        WakeBridge.consumeTurnLine(mc);
        String line2 = WakeBridge.turnLine(mc);
        ok &= step("M10", line2 != null && line2.contains("群组未读")
                        && !line2.contains("新消息"),
                "提示一次：新消息段消失、未读仍报", String.valueOf(line2));

        // ⑦ 盘上事实：消息 append-only 落盘、唤醒请求落盘
        java.nio.file.Path msgs = shared.resolve("groups").resolve(gid).resolve("messages.jsonl");
        ok &= step("M10", java.nio.file.Files.exists(msgs) && lines(msgs) >= 2,
                "群消息 append-only 落盘（≥2 行）", msgs + "=" + lines(msgs));
        ok &= step("M10", java.nio.file.Files.exists(shared.resolve("wake").resolve(reqId + ".json")),
                "唤醒请求落盘 shared/wake/", reqId);

        // ⑧ 解散（创建者）；非创建者被拒
        Result disbandByAdmin = EntityToolsRuntime.dispatch(mb, "group", "group_disband",
                Map.of("gid", gid));
        ok &= step("M10", disbandByAdmin instanceof Result.Error,
                "非创建者解散 → Error", String.valueOf(disbandByAdmin));
        Result disband = EntityToolsRuntime.dispatch(ma, "group", "group_disband", Map.of("gid", gid));
        ok &= step("M10", disband instanceof Result.Ok
                        && String.valueOf(data(disband)).contains(".recycled"),
                "创建者解散 → 进回收区", String.valueOf(disband));
        return ok;
    }

    private static long lines(java.nio.file.Path p) {
        try {
            return java.nio.file.Files.readAllLines(p).stream().filter(s -> !s.isBlank()).count();
        } catch (java.io.IOException e) {
            return -1;
        }
    }

    /** 清掉本测试创建的群（只认创建者前缀；别人的群一条不动）。 */
    private static void cleanTestGroups(java.nio.file.Path shared, String prefix) {
        java.nio.file.Path dir = shared.resolve("groups");
        if (!java.nio.file.Files.isDirectory(dir)) {
            return;
        }
        try (var list = java.nio.file.Files.list(dir)) {
            for (java.nio.file.Path p : list.toList()) {
                java.nio.file.Path meta = p.resolve("meta.json");
                if (!java.nio.file.Files.isRegularFile(meta)) {
                    continue;
                }
                if (creatorOf(meta).startsWith(prefix)) {
                    deleteTree(p);
                }
            }
        } catch (java.io.IOException ignored) {
            // 清理失败不影响断言（断言只看本次 gid）
        }
    }

    /** 清掉本测试发出的唤醒请求（只认 from/to 前缀）。 */
    private static void cleanTestWakes(java.nio.file.Path shared, String prefix) {
        java.nio.file.Path dir = shared.resolve("wake");
        if (!java.nio.file.Files.isDirectory(dir)) {
            return;
        }
        try (var list = java.nio.file.Files.list(dir)) {
            for (java.nio.file.Path p : list.toList()) {
                String from = fieldOf(p, "from");
                String to = fieldOf(p, "to");
                if (from.startsWith(prefix) || to.startsWith(prefix)) {
                    java.nio.file.Files.deleteIfExists(p);
                }
            }
        } catch (java.io.IOException ignored) {
            // 同上
        }
    }


    /**
     * M11：聊天栏推送（2026-10-23 22:25 (UTC+8)，分析 §八）。
     *
     * <p>先"随意绑定一个中立生物"（僵尸猪灵：受击才反击）验证绑定链在非敌对生物上一样通，
     * 再把 {@code TurnSummary} 那一行推给聊天栏——**内容同源、只推摘要、送达有返回值**。
     *
     * <p>为什么用 M8 的房间：同步段时它还是空的（M8 在 tick 驱动器里才跑），
     * 用完把猪灵打死，M8 的环境不受影响。
     */
    private static boolean runM11(ServerLevel level, BlockPos base) {
        boolean ok = true;
        String model = "selftest-m11";
        EntityToolsRuntime.setScanAnchor(model, base);

        // 清场 + 重建家（与 M10 同一教训：清完必须建，否则"未登记"）
        java.nio.file.Path home = BuiltinRuntime.home(model);
        if (home != null) {
            deleteTree(home);
            BuiltinRuntime.forget(model);
            try {
                java.nio.file.Files.createDirectories(BuiltinRuntime.home(model));
            } catch (java.io.IOException e) {
                return step("M11", false, "重建测试 ID 的家", String.valueOf(e));
            }
        }

        // ① 中立生物：僵尸猪灵（受击才反击）
        net.minecraft.world.entity.monster.ZombifiedPiglin pig =
                new net.minecraft.world.entity.monster.ZombifiedPiglin(
                        net.minecraft.world.entity.EntityType.ZOMBIFIED_PIGLIN, level);
        pig.setPersistenceRequired();   // 固定装置：禁止远距静默 discard
        pig.moveTo(base.getX() + 0.5, base.getY(), base.getZ() + 0.5, 0, 0);
        level.addFreshEntity(pig);
        String ref = pig.getUUID().toString();

        Result open = EntityToolsRuntime.dispatch(model, "sense", "open", Map.of());
        ok &= step("M11", open instanceof Result.Ok && String.valueOf(data(open)).contains(ref),
                "中立生物进候选（僵尸猪灵）", String.valueOf(data(open)));
        Result pick = EntityToolsRuntime.dispatch(model, "entity", "pick", Map.of("target", ref));
        ok &= step("M11", pick instanceof Result.Ok, "绑定中立生物 → Ok", String.valueOf(pick));
        Result self = EntityToolsRuntime.dispatch(model, "entity_self", "status", Map.of());
        // entity_self.status 不带实体类型（那是 sense 候选三件套的职责），它报的是"我绑的是谁"
        ok &= step("M11", self instanceof Result.Ok
                        && String.valueOf(data(self)).contains(ref),
                "entity_self 指向绑定的中立生物", String.valueOf(data(self)));
        ok &= step("M11", pig.getTarget() == null, "中立旁证：未受击不锁定目标",
                "target=" + pig.getTarget());

        // ② 制造一条带正文的群消息 → 汇总行只该有计数，不该有正文
        Result g = EntityToolsRuntime.dispatch(model, "group", "group_create", Map.of("name", "M11组"));
        String gid = String.valueOf(data(g)).replaceAll(".*gid=([^,}]+).*", "$1");
        EntityToolsRuntime.dispatch(model, "group", "group_send",
                Map.of("gid", gid, "text", "秘密正文XYZ"));
        String line = WakeBridge.turnLine(model);
        ok &= step("M11", line != null && line.contains("群组未读"),
                "汇总行含群未读", String.valueOf(line));
        ok &= step("M11", line != null && !line.contains("秘密正文XYZ"),
                "汇总行不含正文（只推计数）", String.valueOf(line));

        // ③ 推送到聊天栏（注入 sink：专用服务器没有客户端，断言内容而非"看见"）
        java.util.List<String> recorded = new java.util.ArrayList<>();
        ChatPush.setSink(recorded::add);
        boolean sent = ChatPush.push(line);
        ok &= step("M11", sent && recorded.size() == 1 && recorded.get(0).equals(line),
                "推送送达且内容与汇总行同源", recorded.toString());
        ok &= step("M11", recorded.get(0).length() <= ChatPush.MAX_CHARS,
                "单条不超过 " + ChatPush.MAX_CHARS + " 字符", "len=" + recorded.get(0).length());

        // ④ 去重：同内容 5 秒内再推 → 不送达
        boolean again = ChatPush.push(line);
        ok &= step("M11", !again && recorded.size() == 1,
                "同内容 5 秒内去重（只送达一次）", "again=" + again + " size=" + recorded.size());

        // ⑤ 限流：每分钟 20 条（用 21 条不同内容压上去）
        int delivered = 0;
        for (int i = 0; i < 21; i++) {
            if (ChatPush.push("M11 限流压测 #" + i)) {
                delivered++;
            }
        }
        ok &= step("M11", delivered <= ChatPush.MAX_PER_MINUTE - 1,
                "每分钟上限生效（≤19 条新内容送达）", "delivered=" + delivered);

        // ⑥ 无 sink 且无在线玩家 → 如实返回 false（不谎报送达）
        ChatPush.setSink(null);
        boolean noPlayer = ChatPush.push("M11 无玩家时的推送");
        ok &= step("M11", !noPlayer, "无玩家/无 sink → false（不假装已推送）", "sent=" + noPlayer);

        // ⑦ 收尾：杀掉中立生物（让 M8 的环境保持干净）+ 解散群
        pig.hurt(level.damageSources().generic(), 1000f);
        EntityToolsRuntime.dispatch(model, "group", "group_disband", Map.of("gid", gid));
        ok &= step("M11", !pig.isAlive(), "用完即杀（不污染后续 M8 房间）", "alive=" + pig.isAlive());
        return ok;
    }


    /**
     * M12：聊天工具组（2026-10-24 01:10 (UTC+8)，分析见 `docs/聊天工具组可行性分析-2026-10-23.md`）。
     *
     * <p>不需要测试房（纯文件能力）。**要验的是四件容易被做错的事**而不是"能不能存"：
     * ① `pull` 不推进游标（重复可见优于静默丢失）；② `ack` 之后才消失；
     * ③ "新聊天"与"被提及"是两个维度（consume 只清后者）；④ 搜索必须回 scanned/truncated。
     *
     * <p>卫生：开工先删本模型的家（游标在里面），收工把本次写入的行按 seq 截掉——
     * 否则下一次运行会带着上次的未读，把"新聊天 3 条"打成假失败（M10 的老教训）。
     */
    private static boolean runM12() {
        boolean ok = true;
        String model = "selftest-m12";

        java.nio.file.Path home = BuiltinRuntime.home(model);
        if (home != null) {
            deleteTree(home);
            BuiltinRuntime.forget(model);
            try {
                java.nio.file.Files.createDirectories(BuiltinRuntime.home(model));
            } catch (java.io.IOException e) {
                return step("M12", false, "重建测试 ID 的家", String.valueOf(e));
            }
        }
        io.github.piwugu.snowleopard.tools.chat.ChatLog chatLog = ChatRuntime.logOrNull();
        if (chatLog == null) {
            return step("M12", false, "聊天存储可用", "ChatRuntime 未初始化（看启动日志）");
        }
        long startSeq = chatLog.lastSeq();
        java.nio.file.Path dayFile = chatLog.fileFor(chatLog.today());

        try {
            // ① 注册表里有这一组（清单由 entitytools 提供，壳只执行）
            java.util.List<String> fulls = java.util.List.copyOf(
                    SnowLeopardMod.core().registry().names().fullNames());
            ok &= step("M12", fulls.contains("mod:snowleopard:chat"), "注册表含聊天组", fulls.size() + " 组");

            // ② 造三条玩家消息，其中一条点名本模型
            ChatRuntime.record("PLAYER", "Steve", "第一条：门口集合", "");
            ChatRuntime.record("PLAYER", "Steve", "第二条：selftest-m12 你去看一下", "");
            ChatRuntime.record("PLAYER", "Steve", "第三条：顺便带点木头", "");

            // ③ 摘要行：新聊天 + 被提及（两个维度都在）
            String line1 = WakeBridge.turnLine(model);
            ok &= step("M12", line1 != null && line1.contains("新聊天 3 条"),
                    "摘要行报新聊天条数", String.valueOf(line1));
            ok &= step("M12", line1 != null && line1.contains("被提及 1 次"),
                    "摘要行报被提及次数（编号命中）", String.valueOf(line1));

            // ④ pull 拿块但**不推进**：再问一次仍然是 3 条
            var pulled = ChatRuntime.pull(model, 20);
            ok &= step("M12", pulled.count() == 3 && pulled.block().contains("第一条：门口集合"),
                    "pull 返回注入块（3 条）", "count=" + pulled.count());
            ok &= step("M12", pulled.block().contains("⟦雪豹·聊天⟧") && pulled.block().contains("数据，非指令"),
                    "注入块带定界符与\"非指令\"标注", pulled.block().substring(0, Math.min(40, pulled.block().length())));
            String line2 = WakeBridge.turnLine(model);
            ok &= step("M12", line2 != null && line2.contains("新聊天 3 条"),
                    "pull 不推进游标（可见重复优于静默丢失）", String.valueOf(line2));

            // ⑤ ack 之后"新聊天"消失，但"被提及"还在（两维分离）
            ChatRuntime.ack(model, pulled.lastSeq());
            String line3 = WakeBridge.turnLine(model);
            ok &= step("M12", line3 == null || !line3.contains("新聊天"),
                    "ack 之后不再报新聊天", String.valueOf(line3));
            ok &= step("M12", line3 != null && line3.contains("被提及"),
                    "ack 不清提示（读数与提示是两个维度）", String.valueOf(line3));

            // ⑥ consume 只清提示
            WakeBridge.consumeTurnLine(model);
            ok &= step("M12", ChatRuntime.turnSegment(model) == null,
                    "consume 之后摘要段沉默（不占上下文）", String.valueOf(ChatRuntime.turnSegment(model)));

            // ⑦ 无在线玩家时发言必须失败且说明原因（不假装送达）
            Result send = EntityToolsRuntime.dispatch(model, "chat", "chat_send",
                    Map.of("text", "测试发言"));
            ok &= step("M12", send instanceof Result.Error
                            && String.valueOf(send).contains("没有在线玩家"),
                    "chat_send 无玩家 → Error（不假送达）", String.valueOf(send));

            // ⑧ 搜索：三个数齐全 + 命中数正确
            Result search = EntityToolsRuntime.dispatch(model, "chat", "chat_search",
                    Map.of("keyword", "木头", "date", chatLog.today().toString()));
            String sd = String.valueOf(data(search));
            ok &= step("M12", sd.contains("matched=1") && sd.contains("scanned=")
                            && sd.contains("truncated="),
                    "chat_search 回 matched/scanned/truncated", sd.length() > 90 ? sd.substring(0, 90) : sd);
            Result miss = EntityToolsRuntime.dispatch(model, "chat", "chat_search",
                    Map.of("keyword", "不存在的词", "date", chatLog.today().toString()));
            ok &= step("M12", String.valueOf(data(miss)).contains("matched=0")
                            && String.valueOf(data(miss)).contains("scanned="),
                    "未命中也要回 scanned（否则\"没搜到\"与\"没搜完\"分不清）", String.valueOf(data(miss)));

            // ⑨ 状态工具：两个游标都在
            Result st = EntityToolsRuntime.dispatch(model, "chat", "chat_status", Map.of());
            String std = String.valueOf(data(st));
            ok &= step("M12", std.contains("lastReadSeq") && std.contains("pendingMentions")
                            && std.contains("unread"),
                    "chat_status 给出两个游标 + 未读数", std);

            // ⑩ 盘上事实：聊天按天落盘、游标落在自己的家里
            ok &= step("M12", java.nio.file.Files.exists(dayFile) && lines(dayFile) >= 3,
                    "聊天按天落盘 shared/chat/<date>.jsonl", dayFile + "=" + lines(dayFile));
            ok &= step("M12", home != null
                            && java.nio.file.Files.exists(home.resolve("chat-cursor.json")),
                    "游标落在模型自己的家（不是共享面）", String.valueOf(home));
        } finally {
            // 收工把本次写入的行截掉（只保留 seq <= startSeq），下次运行从零开始
            truncateChatFrom(dayFile, startSeq);
        }
        return ok;
    }

    /** 只保留 seq &le; keepUpTo 的行；解析不了的行一并丢掉（本测试自己造的数据，安全）。 */
    private static void truncateChatFrom(java.nio.file.Path file, long keepUpTo) {
        if (!java.nio.file.Files.isRegularFile(file)) {
            return;
        }
        try {
            java.util.List<String> kept = new java.util.ArrayList<>();
            for (String line : java.nio.file.Files.readAllLines(file)) {
                if (line.isBlank()) {
                    continue;
                }
                Object seq = snowleopard.core.json.Json.parseObject(line).get("seq");
                long v = seq instanceof Number n ? n.longValue() : 0L;
                if (v > 0 && v <= keepUpTo) {
                    kept.add(line);
                }
            }
            java.nio.file.Files.write(file, kept);
        } catch (java.io.IOException ignored) {
            // 清理失败不影响断言（断言只看本次内容）
        }
    }


    /**
     * M13：载体与唤醒源（2026-10-24 01:30 (UTC+8)，分析见 `docs/实体载体与唤醒源可行性分析-2026-10-23.md`）。
     *
     * <p>验四件事：① 绑定链在载体上走通（pick → avatar_bind → 主载体唯一）；
     * ② 定时按真实时间推进、**跳过不堆积**；③ 提及唤醒只认名称（编号只提示）；
     * ④ 死亡解绑。右键/被打的事件路径留给真机（事件对象难以自造，核心逻辑与提及同一条 requestWake）。
     */
    private static boolean runM13(ServerLevel level, BlockPos base) {
        boolean ok = true;
        String model = "selftest-m13";
        String model2 = "selftest-m13b";

        for (String id : new String[]{model, model2}) {
            java.nio.file.Path h = BuiltinRuntime.home(id);
            if (h != null) {
                deleteTree(h);
                BuiltinRuntime.forget(id);
                try {
                    java.nio.file.Files.createDirectories(BuiltinRuntime.home(id));
                } catch (java.io.IOException e) {
                    return step("M13", false, "重建家", String.valueOf(e));
                }
            }
        }

        EntityToolsRuntime.setScanAnchor(model, base);   // 漏了它 open 就扫不到房里的载体（M11 同款）
        EntityToolsRuntime.setScanAnchor(model2, base);

        // ① 生成两只载体（/summon snowleopard:avatar 的等价路径）
        io.github.piwugu.snowleopard.AvatarEntity a1 = new io.github.piwugu.snowleopard.AvatarEntity(
                io.github.piwugu.snowleopard.AvatarEntities.AVATAR.get(), level);
        a1.setPersistenceRequired();
        a1.moveTo(base.getX() + 0.5, base.getY(), base.getZ() + 0.5, 0, 0);
        level.addFreshEntity(a1);
        ok &= step("M13", a1.isAlive(), "载体生成（/summon snowleopard:avatar 的等价路径）", a1.getStringUUID());

        // ② 绑定链：open → pick → avatar_bind
        Result open = EntityToolsRuntime.dispatch(model, "sense", "open", Map.of());
        ok &= step("M13", String.valueOf(data(open)).contains(a1.getStringUUID()),
                "载体进候选", String.valueOf(data(open)));
        ok &= step("M13", EntityToolsRuntime.dispatch(model, "entity", "pick",
                        Map.of("target", a1.getStringUUID())) instanceof Result.Ok,
                "pick 载体", "");
        Result bind1 = EntityToolsRuntime.dispatch(model, "avatar", "avatar_bind", Map.of());
        ok &= step("M13", bind1 instanceof Result.Ok && String.valueOf(data(bind1)).contains("primary=true"),
                "avatar_bind → 主载体", String.valueOf(data(bind1)));

        // ③ 主载体唯一：同一模型绑第二只 → 旧主降级
        io.github.piwugu.snowleopard.AvatarEntity a2 = new io.github.piwugu.snowleopard.AvatarEntity(
                io.github.piwugu.snowleopard.AvatarEntities.AVATAR.get(), level);
        a2.setPersistenceRequired();
        a2.moveTo(base.getX() + 2.5, base.getY(), base.getZ() + 0.5, 0, 0);
        level.addFreshEntity(a2);
        EntityToolsRuntime.dispatch(model, "entity", "unbind", Map.of());
        EntityToolsRuntime.dispatch(model, "sense", "open", Map.of());
        EntityToolsRuntime.dispatch(model, "entity", "pick", Map.of("target", a2.getStringUUID()));
        Result bind2 = EntityToolsRuntime.dispatch(model, "avatar", "avatar_bind", Map.of());
        ok &= step("M13", bind2 instanceof Result.Ok, "换绑第二只", String.valueOf(data(bind2)));
        io.github.piwugu.snowleopard.tools.avatar.AvatarStore store = ChatRuntime.avatarStore();
        ok &= step("M13", store.primaryOf(model).uuid().equals(a2.getStringUUID())
                        && store.byUuid(a1.getStringUUID()) != null
                        && !store.byUuid(a1.getStringUUID()).primary(),
                "主载体唯一（旧主降级但保留）", "");

        // ④ 禁重名（裁决 4）：a2 起名"小白"（重绑生效），a1 也叫"小白"由 m13b 绑 → 拒绝
        a2.setCustomName(net.minecraft.network.chat.Component.literal("小白"));
        Result rename = EntityToolsRuntime.dispatch(model, "avatar", "avatar_bind", Map.of());
        ok &= step("M13", rename instanceof Result.Ok
                        && String.valueOf(data(rename)).contains("name=小白"),
                "载体改名经重绑生效（身份单点：chat_send/摘要/提及全用它）", String.valueOf(data(rename)));
        a1.setCustomName(net.minecraft.network.chat.Component.literal("小白"));
        EntityToolsRuntime.dispatch(model2, "sense", "open", Map.of());
        EntityToolsRuntime.dispatch(model2, "entity", "pick", Map.of("target", a1.getStringUUID()));
        Result conflict = EntityToolsRuntime.dispatch(model2, "avatar", "avatar_bind", Map.of());
        ok &= step("M13", conflict instanceof Result.Error && String.valueOf(conflict).contains("重名"),
                "重名绑定 → Error（提及唤醒无法区分谁被点名）", String.valueOf(conflict));

        // ⑤ 定时：下限 60 秒 + 到期触发 + 不堆积
        Result sched = EntityToolsRuntime.dispatch(model, "avatar", "avatar_schedule",
                Map.of("interval_sec", 60));
        ok &= step("M13", sched instanceof Result.Ok, "定时 60 秒 → Ok", String.valueOf(data(sched)));
        Result tooFast = EntityToolsRuntime.dispatch(model, "avatar", "avatar_schedule",
                Map.of("interval_sec", 10));
        ok &= step("M13", tooFast instanceof Result.Error, "10 秒 → Error（下限 60）",
                String.valueOf(tooFast));
        store.setNextFireAt(a2.getStringUUID(), System.currentTimeMillis() - 1);
        AvatarWake.runScheduler(level.getServer());
        long pendingClock = WakeBridge.pendingCount("ext-clock", model);
        ok &= step("M13", pendingClock >= 1, "到期 → ext-clock 的 pending 请求", "count=" + pendingClock);
        AvatarWake.runScheduler(level.getServer());
        ok &= step("M13", WakeBridge.pendingCount("ext-clock", model) == pendingClock,
                "再跑一次不堆积（跳过不补发）", "");

        // ⑥ 提及唤醒：只认名称（编号只提示）——先清冷却，别让⑤的定时唤醒把这里吞掉
        AvatarWake.resetCooldowns();
        long before = WakeBridge.pendingCount("any", model);
        ChatRuntime.record("PLAYER", "Steve", "小白 过来一下", "");
        ok &= step("M13", WakeBridge.pendingCount("any", model) == before + 1,
                "名称点名 → 唤醒请求", "");
        int mentionsBefore = ChatRuntime.mentionsPending(model);
        ChatRuntime.record("PLAYER", "Steve", "selftest-m13 你也在吧", "");
        ok &= step("M13", WakeBridge.pendingCount("any", model) == before + 1,
                "编号点名 → 只提示、不唤醒", "");
        ok &= step("M13", ChatRuntime.mentionsPending(model) == mentionsBefore + 1,
                "但提示 +1（提示 ≠ 唤醒）", "");

        // ⑦ 死亡解绑
        a2.hurt(level.damageSources().generic(), 1000f);
        ok &= step("M13", !a2.isAlive() && store.primaryOf(model) == null,
                "载体死亡 → 解绑", "");
        EntityToolsRuntime.dispatch(model, "avatar", "avatar_unbind", Map.of());
        a1.discard();
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
