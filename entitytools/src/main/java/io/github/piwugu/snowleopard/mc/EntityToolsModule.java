package io.github.piwugu.snowleopard.mc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import io.github.piwugu.snowleopard.tools.BindingRegistry;
import io.github.piwugu.snowleopard.tools.CandidateInfo;
import io.github.piwugu.snowleopard.tools.FacingMath;
import io.github.piwugu.snowleopard.tools.ItemIndex;
import io.github.piwugu.snowleopard.tools.LookLockMachine;
import io.github.piwugu.snowleopard.tools.SenseMachine;
import io.github.piwugu.snowleopard.tools.SenseState;
import io.github.piwugu.snowleopard.tools.ToolSchemas;
import snowleopard.core.result.Result;

/**
 * 实体工具模块（M1，2026-10-23 05:40 (UTC+8)）——每模型一份（caller 级）。
 *
 * <p>组合：{@link SenseMachine}（状态语义）+ {@link BindingRegistry}（1:1 双向）+
 * {@link DormancyManager}（未选取休眠）+ 候选采集/自我观测。工具名来自 {@link ToolSchemas}
 * 单点冻结——注册表元数据与调度同源。所有失败走 {@link Result}，不抛到宿主。
 */
public final class EntityToolsModule {

    /** 世界上下文供应（壳侧注入：当前服务器世界与扫描中心——无玩家也可用世界出生点）。 */
    public interface Ctx {
        ServerLevel level();

        net.minecraft.core.BlockPos center();
    }

    private final String modelId;
    private final Ctx ctx;
    private final SenseMachine sense = new SenseMachine();
    private final BindingRegistry bindings = new BindingRegistry();
    private final DormancyManager dormancy = new DormancyManager();
    private final LookLockMachine lookLock = new LookLockMachine();
    private final io.github.piwugu.snowleopard.tools.MiningTask mining =
            new io.github.piwugu.snowleopard.tools.MiningTask();
    private final io.github.piwugu.snowleopard.tools.MoveMachine moveM =
            new io.github.piwugu.snowleopard.tools.MoveMachine();
    private double lastMoveX, lastMoveY, lastMoveZ;
    private boolean movePrimed;
    private float lastSetYaw;
    private float lastSetPitch;
    private String lockFace = "";
    private net.minecraft.core.BlockPos miningTarget;
    private static final com.mojang.authlib.GameProfile FAKE_PROFILE = new com.mojang.authlib.GameProfile(
            java.util.UUID.nameUUIDFromBytes("snowleopard-fake".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            "SnowLeopardFake");
    private volatile List<CandidateInfo> lastCandidates = List.of();

    public EntityToolsModule(String modelId, Ctx ctx) {
        this.modelId = modelId;
        this.ctx = ctx;
    }

    public String modelId() {
        return modelId;
    }

    public SenseMachine sense() {
        return sense;
    }

    public BindingRegistry bindings() {
        return bindings;
    }

    /**
     * 工具调度：按（组, 工具）二维分发——三组都有 "status"，只按工具名分发会撞车（M1 编译教训）。
     * 工具名来自 {@link ToolSchemas} 单点冻结——注册表元数据与调度同源。
     * excludeRefs=其他模型已绑定实体；controllerOf=实体→控制模型编号（动态/世界查询归属标注）。
     */
    public Result call(String group, String tool, Map<String, Object> params,
                       java.util.function.Predicate<String> excludeRefs,
                       java.util.function.Function<String, String> controllerOf) {
        if (params == null) {
            params = Map.of();
        }
        try {
            return switch (group) {
                case io.github.piwugu.snowleopard.tools.GroupNames.SENSE -> switch (tool) {
                    case ToolSchemas.SENSE_OPEN -> open(excludeRefs);
                    case ToolSchemas.SENSE_CLOSE -> close();
                    case ToolSchemas.SENSE_STATUS -> senseStatus();
                    default -> Result.error("sense 组无此工具: " + tool);
                };
                case io.github.piwugu.snowleopard.tools.GroupNames.ENTITY -> switch (tool) {
                    case ToolSchemas.ENTITY_PICK -> pick(String.valueOf(params.get("target")));
                    case ToolSchemas.ENTITY_UNBIND -> unbind();
                    case ToolSchemas.ENTITY_STATUS -> bindStatus(params.get("target"));
                    default -> Result.error("entity 组无此工具: " + tool);
                };
                case io.github.piwugu.snowleopard.tools.GroupNames.ENTITY_SELF -> switch (tool) {
                    case ToolSchemas.SELF_STATUS -> selfStatus();
                    default -> Result.error("entity_self 组无此工具: " + tool);
                };
                case io.github.piwugu.snowleopard.tools.GroupNames.VIEW -> switch (tool) {
                    case ToolSchemas.VIEW_LOOK -> look(params);
                    case ToolSchemas.VIEW_LOOK_AT -> lookAt(params);
                    case ToolSchemas.VIEW_UNLOCK -> unlockView();
                    case ToolSchemas.VIEW_STATUS -> viewStatus();
                    default -> Result.error("view 组无此工具: " + tool);
                };
                case io.github.piwugu.snowleopard.tools.GroupNames.INTERACT -> switch (tool) {
                    case ToolSchemas.INTERACT_ATTACK -> attack(params, excludeRefs);
                    case ToolSchemas.INTERACT_USE -> use(params);
                    case ToolSchemas.INTERACT_PLACE -> place(params);
                    case ToolSchemas.INTERACT_BREAK -> breakBlock(params);
                    case ToolSchemas.INTERACT_STATUS -> interactStatus();
                    default -> Result.error("interact 组无此工具: " + tool);
                };
                case io.github.piwugu.snowleopard.tools.GroupNames.MOVE -> switch (tool) {
                    case ToolSchemas.MOVE_MOVE -> move(params);
                    case ToolSchemas.MOVE_SNEAK -> sneak(params);
                    case ToolSchemas.MOVE_JUMP -> jump();
                    case ToolSchemas.MOVE_GOTO -> gotoPos(params);
                    case ToolSchemas.MOVE_STOP -> stopMove();
                    case ToolSchemas.MOVE_STATUS -> moveStatus();
                    default -> Result.error("move 组无此工具: " + tool);
                };
                case io.github.piwugu.snowleopard.tools.GroupNames.ITEM -> switch (tool) {
                    case ToolSchemas.ITEM_SELF_ITEMS -> selfItems(params);
                    case ToolSchemas.ITEM_CONTAINER_ITEMS -> containerItems(params);
                    case ToolSchemas.ITEM_MOVE_ITEM -> moveItem(params);
                    case ToolSchemas.ITEM_DROP -> dropItem(params);
                    default -> Result.error("item 组无此工具: " + tool);
                };
                case io.github.piwugu.snowleopard.tools.GroupNames.WORLD -> switch (tool) {
                    case ToolSchemas.WORLD_QUERY -> worldQuery(params, controllerOf);
                    default -> Result.error("world 组无此工具: " + tool);
                };
                default -> Result.error("工具组未实现或未知: " + group + "（M2–M6 逐步开放）");
            };
        } catch (RuntimeException e) {
            return Result.error("工具执行异常: " + e);
        }
    }

    // ── sense ──

    /** 开启感官：唤醒旧休眠（新一轮公平）→ 采集候选三件套 → 等待选取。 */
    Result open(java.util.function.Predicate<String> excludeRefs) {
        ServerLevel level = ctx.level();
        net.minecraft.core.BlockPos center = ctx.center();
        if (level == null || center == null) {
            return Result.error("世界未就绪（无服务器世界或扫描中心）——无法采集候选");
        }
        // 重开=重新选取：原绑定解绑、全部休眠唤醒
        String prev = bindings.entityOf(modelId);
        if (prev != null) {
            bindings.unbind(modelId);
        }
        for (CandidateInfo c : lastCandidates) {
            Entity e = level.getEntity(java.util.UUID.fromString(c.entityRef()));
            if (e instanceof LivingEntity le) {
                dormancy.wake(le);
            }
        }
        Result st = sense.open();
        if (st instanceof Result.Error) {
            return st;
        }
        List<CandidateInfo> candidates = CandidateScanner.scan(level, center,
                CandidateScanner.DEFAULT_RADIUS, excludeRefs);
        lastCandidates = candidates;
        return Result.ok(Map.of("state", sense.state().name(), "candidates", candidates));
    }

    Result close() {
        Result r = sense.close();
        wakeAllQuietly();
        return r;
    }

    Result senseStatus() {
        Map<String, Object> d = new java.util.LinkedHashMap<>();
        d.put("state", sense.state().name());
        d.put("model", modelId);
        if (sense.boundEntity() != null) {
            d.put("bound", sense.boundEntity());
        }
        d.put("candidates", lastCandidates.size());
        d.put("dormant", dormancy.size());
        return Result.ok(d);
    }

    // ── entity ──

    /** 选取绑定：命中候选 → 1:1 绑定；未选取候选全部休眠（裁决）。 */
    Result pick(String target) {
        if (target == null || target.isBlank() || "null".equals(target)) {
            return Result.error("pick 需要 target（候选编号）");
        }
        boolean isCandidate = lastCandidates.stream().anyMatch(c -> c.entityRef().equals(target));
        if (!isCandidate) {
            return Result.error("实体 " + target + " 不在当前候选集——重新 sense.open 刷新候选");
        }
        Result r = sense.pick(target);
        if (r instanceof Result.Error) {
            return r;
        }
        Result b = bindings.bind(modelId, target);
        if (b instanceof Result.Error) {
            return b;   // 绑定冲突（1:1 被他人占用）
        }
        // 未选取的候选进入休眠（身份消歧）；选中者保持活动
        ServerLevel level = ctx.level();
        if (level != null) {
            for (CandidateInfo c : lastCandidates) {
                if (c.entityRef().equals(target)) {
                    continue;
                }
                Entity e = level.getEntity(java.util.UUID.fromString(c.entityRef()));
                if (e instanceof LivingEntity le) {
                    dormancy.sleep(le);
                }
            }
        }
        return Result.ok(Map.of("state", sense.state().name(), "bound", target,
                "dormant", dormancy.size()));
    }

    Result unbind() {
        String prev = bindings.entityOf(modelId);
        Result r = bindings.unbind(modelId);
        if (r instanceof Result.Ok && prev != null) {
            ServerLevel level = ctx.level();
            Entity e = level == null ? null : level.getEntity(java.util.UUID.fromString(prev));
            if (e instanceof LivingEntity le) {
                dormancy.sleep(le);   // 解绑后进入休眠（工具 schema 注明）
            }
        }
        sense.close();
        return Result.ok(Map.of("state", sense.state().name(), "unbound", String.valueOf(prev)));
    }

    /** 双向反查：缺省查自身；target 形如 {@code model:<id>} 或 {@code entity:<ref>}。 */
    Result bindStatus(Object target) {
        if (target == null || String.valueOf(target).isBlank()) {
            String e = bindings.entityOf(modelId);
            return Result.ok(Map.of("model", modelId, "entity", String.valueOf(e)));
        }
        String t = String.valueOf(target);
        if (t.startsWith("model:")) {
            String m = t.substring("model:".length());
            return Result.ok(Map.of("model", m, "entity", String.valueOf(bindings.entityOf(m))));
        }
        if (t.startsWith("entity:")) {
            String ref = t.substring("entity:".length());
            return Result.ok(Map.of("entity", ref, "model", String.valueOf(bindings.modelOf(ref))));
        }
        return Result.error("target 形如 model:<id> 或 entity:<ref>");
    }

    // ── entity_self ──

    Result selfStatus() {
        if (!sense.toolsEnabled()) {
            return Result.error("感官未开启或未绑定——控制类工具禁用（当前: " + sense.state() + "）");
        }
        ServerLevel level = ctx.level();
        Entity e = level == null ? null : level.getEntity(java.util.UUID.fromString(sense.boundEntity()));
        if (!(e instanceof LivingEntity le) || le.isDeadOrDying()) {
            return Result.error("绑定实体不可用（不在当前世界或已死亡）——死亡会自动关感官");
        }
        Map<String, Object> d = new java.util.LinkedHashMap<>(SelfStatusReader.read(le));
        d.put("model", modelId);
        d.put("entity", sense.boundEntity());
        return Result.ok(d);
    }

    // ── view（M2）──

    /** 转向（度）——头/身/眼同步：pick 与外部观察用头向，只 setYRot 会"身转头不转"（M5 真机逮到）。 */
    private static void setRotation(LivingEntity self, double yaw, double pitch) {
        self.setYRot((float) yaw);
        self.setXRot((float) pitch);
        self.setYHeadRot((float) yaw);
        self.yBodyRot = (float) yaw;
    }

    Result look(Map<String, Object> params) {
        LivingEntity self = requireBound();
        if (self == null) {
            return Result.error("感官未开启或未绑定——控制类工具禁用（当前: " + sense.state() + "）");
        }
        Object y = params.get("yaw");
        Object p = params.get("pitch");
        if (!(y instanceof Number yn) || !(p instanceof Number pn)) {
            return Result.error("look 需要数值参数 yaw 与 pitch（度）");
        }
        float yaw = yn.floatValue();
        float pitch = pn.floatValue();
        setRotation(self, yaw, pitch);
        lastSetYaw = yaw;
        lastSetPitch = pitch;
        return Result.ok(Map.of("yaw", (double) yaw, "pitch", (double) pitch));
    }

    /** 锁定视角到方块/实体（可选面、可选时长）。 */
    Result lookAt(Map<String, Object> params) {
        LivingEntity self = requireBound();
        if (self == null) {
            return Result.error("感官未开启或未绑定——控制类工具禁用（当前: " + sense.state() + "）");
        }
        String type = String.valueOf(params.get("target_type"));
        String target = String.valueOf(params.get("target"));
        if (target == null || target.isBlank() || "null".equals(target)) {
            return Result.error("look_at 需要 target");
        }
        int duration = params.get("duration_ticks") instanceof Number n ? n.intValue() : 0;
        LookLockMachine.Kind kind;
        if ("block".equals(type)) {
            kind = LookLockMachine.Kind.BLOCK;
            lockFace = String.valueOf(params.getOrDefault("face", ""));
            parseBlock(target);   // 非法坐标立即报错，不进锁定
        } else if ("entity".equals(type)) {
            kind = LookLockMachine.Kind.ENTITY;
            lockFace = "";
        } else {
            return Result.error("target_type 需为 block 或 entity");
        }
        Result r = lookLock.start(kind, target, duration);
        if (r instanceof Result.Error) {
            return r;
        }
        // 新锁定以当前朝向为漂移基线——否则上一把锁/look() 留下的基线会把新锁
        // 当"外力夺控"在首 tick 秒杀（2026-10-23 真机自测逮到的真 bug）
        lastSetYaw = self.getYRot();
        lastSetPitch = self.getXRot();
        tickViewLock();   // 立即对准一次
        return Result.ok(Map.of("locked", true, "kind", kind.name(), "target", target,
                "face", lockFace, "durationTicks", duration));
    }

    Result unlockView() {
        return lookLock.unlock();
    }

    Result viewStatus() {
        Map<String, Object> d = new java.util.LinkedHashMap<>();
        LivingEntity self = sense.boundEntity() == null ? null
                : boundLiving(ctx.level());
        if (self != null) {
            d.put("yaw", (double) self.getYRot());
            d.put("pitch", (double) self.getXRot());
        }
        d.put("locked", lookLock.locked());
        if (lookLock.locked()) {
            d.put("kind", lookLock.kind().name());
            d.put("target", lookLock.target());
            d.put("age", lookLock.age());
        }
        if (lookLock.lastEnd() != null) {
            d.put("lastEnd", lookLock.lastEnd().name());
        }
        return Result.ok(d);
    }

    /**
     * 每 tick 锁定执行（壳侧 ServerTickEvent 调；自测同步直调同一方法）。
     * 六条结束路径的输入（目标存在/距离/漂移）在此读世界事实，状态语义在 {@link LookLockMachine}。
     */
    public void tickViewLock() {
        if (!lookLock.locked()) {
            return;
        }
        ServerLevel level = ctx.level();
        LivingEntity self = boundLiving(level);
        if (level == null || self == null) {
            return;
        }
        boolean targetPresent;
        double distance;
        FacingMath.Angle aim;
        String target = lookLock.target();
        if (lookLock.kind() == LookLockMachine.Kind.BLOCK) {
            net.minecraft.core.BlockPos pos = parseBlock(target);
            targetPresent = !level.getBlockState(pos).isAir();
            double cx = pos.getX() + 0.5, cy = pos.getY() + 0.5, cz = pos.getZ() + 0.5;
            double[] faceOff = faceOffset(lockFace);
            cx += faceOff[0];
            cy += faceOff[1];
            cz += faceOff[2];
            distance = Math.sqrt(self.distanceToSqr(cx, cy, cz));
            aim = FacingMath.aim(self.getX(), self.getY() + self.getEyeHeight(), self.getZ(), cx, cy, cz);
        } else {
            Entity e = level.getEntity(java.util.UUID.fromString(target));
            targetPresent = e != null && e.isAlive();
            distance = targetPresent ? self.distanceTo(e) : 0;
            double ex = targetPresent ? e.getX() : 0, ey = targetPresent ? e.getY() + e.getBbHeight() * 0.5 : 0,
                    ez = targetPresent ? e.getZ() : 0;
            aim = FacingMath.aim(self.getX(), self.getY() + self.getEyeHeight(), self.getZ(), ex, ey, ez);
        }
        boolean drifted = FacingMath.deltaDeg(self.getYRot(), lastSetYaw) > LookLockMachine.DRIFT_EPS_DEG
                || FacingMath.deltaDeg(self.getXRot(), lastSetPitch) > LookLockMachine.DRIFT_EPS_DEG;
        Result r = lookLock.tick(targetPresent, distance, drifted);
        if (r instanceof Result.Ok ok && ok.data() instanceof Map<?, ?> m
                && Boolean.TRUE.equals(m.get("locked"))) {
            setRotation(self, aim.yaw(), aim.pitch());
            lastSetYaw = (float) aim.yaw();
            lastSetPitch = (float) aim.pitch();
        }
    }

    private LivingEntity requireBound() {
        if (!sense.toolsEnabled()) {
            return null;
        }
        return boundLiving(ctx.level());
    }

    private LivingEntity boundLiving(ServerLevel level) {
        if (level == null || sense.boundEntity() == null) {
            return null;
        }
        Entity e = level.getEntity(java.util.UUID.fromString(sense.boundEntity()));
        return e instanceof LivingEntity le ? le : null;
    }

    private static net.minecraft.core.BlockPos parseBlock(String target) {
        String[] parts = target.split(",");
        if (parts.length != 3) {
            throw new IllegalArgumentException("block 目标形如 \"x,y,z\"");
        }
        return new net.minecraft.core.BlockPos(
                (int) Math.floor(Double.parseDouble(parts[0].trim())),
                (int) Math.floor(Double.parseDouble(parts[1].trim())),
                (int) Math.floor(Double.parseDouble(parts[2].trim())));
    }

    /** 面偏移：方块中心指向面中心。 */
    private static double[] faceOffset(String face) {
        return switch (face == null ? "" : face.toLowerCase(java.util.Locale.ROOT)) {
            case "up" -> new double[]{0, 0.5, 0};
            case "down" -> new double[]{0, -0.5, 0};
            case "north" -> new double[]{0, 0, -0.5};
            case "south" -> new double[]{0, 0, 0.5};
            case "west" -> new double[]{-0.5, 0, 0};
            case "east" -> new double[]{0.5, 0, 0};
            default -> new double[]{0, 0, 0};   // 缺省=中心
        };
    }

    // ── interact（M3）──

    /** 攻击：显式目标；红线=其他编号实体一律拒绝；完成判定=血量变化回推。 */
    Result attack(Map<String, Object> params, java.util.function.Predicate<String> excludeRefs) {
        LivingEntity self = requireBound();
        if (self == null) {
            return Result.error("感官未开启或未绑定——控制类工具禁用（当前: " + sense.state() + "）");
        }
        String target = String.valueOf(params.get("target"));
        if (target == null || target.isBlank() || "null".equals(target)) {
            return Result.error("attack 需要 target（候选编号）");
        }
        if (excludeRefs != null && excludeRefs.test(target)) {
            return Result.error("红线：其他编号（模型）的实体一律无法使用——" + target);
        }
        ServerLevel level = ctx.level();
        Entity e = level == null ? null : level.getEntity(java.util.UUID.fromString(target));
        if (!(e instanceof LivingEntity victim) || !victim.isAlive()) {
            return Result.error("攻击目标不可用（不存在或已死亡）");
        }
        faceToward(self, victim.getX(), victim.getY() + victim.getBbHeight() * 0.5, victim.getZ());
        float before = victim.getHealth();
        self.doHurtTarget(victim);
        float after = victim.getHealth();
        boolean hurt = after < before || !victim.isAlive();   // 服务端事实
        return hurt ? Result.ok(Map.of("ok", true, "healthBefore", (double) before,
                        "healthAfter", (double) after))
                : Result.error("攻击未生效（血量无变化）——目标可能无敌/距离外");
    }

    /** 使用/交互方块（v1=方块交互；实体交互随后续里程碑）。完成判定=调用结果回执。 */
    Result use(Map<String, Object> params) {
        LivingEntity self = requireBound();
        if (self == null) {
            return Result.error("感官未开启或未绑定——控制类工具禁用（当前: " + sense.state() + "）");
        }
        net.minecraft.core.BlockPos pos = resolveBlock(self, params);
        ServerLevel level = ctx.level();
        if (level == null || level.getBlockState(pos).isAir()) {
            return Result.error("使用目标为空气（方块不存在）");
        }
        faceToward(self, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        net.minecraft.world.item.ItemStack hand = self.getMainHandItem();
        net.minecraft.world.phys.BlockHitResult hit = new net.minecraft.world.phys.BlockHitResult(
                new net.minecraft.world.phys.Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5),
                net.minecraft.core.Direction.UP, pos, false);
        var player = net.neoforged.neoforge.common.util.FakePlayerFactory.get(level, FAKE_PROFILE);
        player.setPos(self.getX(), self.getY(), self.getZ());
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, hand);
        var res = level.getBlockState(pos).useWithoutItem(level, player, hit);
        return Result.ok(Map.of("ok", res.consumesAction(), "result", res.toString()));
    }

    /** 放置方块（v1 显式方块 id；M5 物品组接通后改从物品栏取）。完成判定=方块出现回推。 */
    Result place(Map<String, Object> params) {
        LivingEntity self = requireBound();
        if (self == null) {
            return Result.error("感官未开启或未绑定——控制类工具禁用（当前: " + sense.state() + "）");
        }
        net.minecraft.core.BlockPos pos;
        try {
            pos = parseBlock(String.valueOf(params.get("target")));
        } catch (RuntimeException e) {
            return Result.error("place 需要 target（x,y,z）: " + e.getMessage());
        }
        String blockId = String.valueOf(params.get("block"));
        var block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(
                net.minecraft.resources.ResourceLocation.parse(blockId));
        if (block == net.minecraft.world.level.block.Blocks.AIR && !"minecraft:air".equals(blockId)) {
            return Result.error("未知方块 id: " + blockId);
        }
        ServerLevel level = ctx.level();
        if (level == null) {
            return Result.error("世界未就绪");
        }
        faceToward(self, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        boolean ok = level.setBlockAndUpdate(pos, block.defaultBlockState());
        boolean present = !level.getBlockState(pos).isAir();   // 服务端事实
        return ok && present ? Result.ok(Map.of("ok", true, "placed", blockId))
                : Result.error("放置后方块未出现（服务端回推不符）");
    }

    /** 破坏：持续动作（硬度→时长），tick 推进；完成=方块真没了；超时报因。 */
    Result breakBlock(Map<String, Object> params) {
        LivingEntity self = requireBound();
        if (self == null) {
            return Result.error("感官未开启或未绑定——控制类工具禁用（当前: " + sense.state() + "）");
        }
        net.minecraft.core.BlockPos pos;
        try {
            pos = parseBlock(String.valueOf(params.get("target")));
        } catch (RuntimeException e) {
            return Result.error("break 需要 target（x,y,z）: " + e.getMessage());
        }
        ServerLevel level = ctx.level();
        if (level == null || level.getBlockState(pos).isAir()) {
            return Result.error("破坏目标为空气（方块不存在）");
        }
        faceToward(self, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        float speed = level.getBlockState(pos).getDestroySpeed(level, pos);
        int required = Math.max(1, (int) Math.ceil(speed * 5));
        int max = params.get("max_ticks") instanceof Number n ? n.intValue() : 200;
        miningTarget = pos;
        return mining.start(pos.getX() + "," + pos.getY() + "," + pos.getZ(), required, max);
    }

    Result interactStatus() {
        Map<String, Object> d = new java.util.LinkedHashMap<>();
        d.put("outcome", mining.outcome().name());
        d.put("target", String.valueOf(mining.target()));
        d.put("elapsed", mining.elapsed());
        d.put("requiredTicks", mining.requiredTicks());
        return Result.ok(d);
    }

    /** 每 tick 挖掘推进（壳侧 tick 钩子调；自测同步直调）。到时长执行 removeBlock 并回推封签 COMPLETED。 */
    public void tickInteract() {
        if (!mining.isBusy()) {
            return;
        }
        ServerLevel level = ctx.level();
        if (level == null || miningTarget == null) {
            return;
        }
        boolean gone = level.getBlockState(miningTarget).isAir();
        var outcome = mining.tick(gone);
        if (outcome == io.github.piwugu.snowleopard.tools.MiningTask.Outcome.READY) {
            level.removeBlock(miningTarget, false);   // 到时长执行移除
            // 服务端回推封签：方块真没了才算 COMPLETED——不留 READY 半态（占着挖掘位挡新任务）
            mining.tick(level.getBlockState(miningTarget).isAir());
        }
    }

    private net.minecraft.core.BlockPos resolveBlock(LivingEntity self, Map<String, Object> params) {
        Object t = params.get("target");
        if (t != null && !String.valueOf(t).isBlank() && !"null".equals(t)) {
            return parseBlock(String.valueOf(t));
        }
        // 缺省=视线 raycast（显式目标优先）
        var hit = self.pick(8.0, 1.0f, false);
        if (hit instanceof net.minecraft.world.phys.BlockHitResult bh
                && bh.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
            return bh.getBlockPos();
        }
        throw new IllegalArgumentException("视线内无方块——请显式给 target");
    }

    /** 自动面向（FridayFacing 语义：锁定期间跳过——锁定优先）。 */
    private void faceToward(LivingEntity self, double x, double y, double z) {
        if (lookLock.locked()) {
            return;
        }
        FacingMath.Angle a = FacingMath.aim(
                self.getX(), self.getY() + self.getEyeHeight(), self.getZ(), x, y, z);
        setRotation(self, a.yaw(), a.pitch());
        lastSetYaw = (float) a.yaw();
        lastSetPitch = (float) a.pitch();
    }

    // ── move（M4）──

    /** 时长/距离驱动移动。方向在开始时冻结为绝对向量（随转身不变）。 */
    Result move(Map<String, Object> params) {
        LivingEntity self = requireBound();
        if (self == null) {
            return Result.error("感官未开启或未绑定——控制类工具禁用（当前: " + sense.state() + "）");
        }
        Object dur = params.get("duration_ticks");
        Object dist = params.get("distance");
        Result r = moveM.startMove(String.valueOf(params.get("direction")),
                dur instanceof Number n ? n.doubleValue() : null,
                dist instanceof Number d ? d.doubleValue() : null,
                self.getYRot());
        if (r instanceof Result.Error) {
            return r;
        }
        primeMove(self);
        return r;
    }

    Result sneak(Map<String, Object> params) {
        LivingEntity self = requireBound();
        if (self == null) {
            return Result.error("感官未开启或未绑定——控制类工具禁用（当前: " + sense.state() + "）");
        }
        boolean on = Boolean.parseBoolean(String.valueOf(params.get("on")));
        self.setShiftKeyDown(on);
        return Result.ok(Map.of("sneaking", self.isShiftKeyDown()));
    }

    /** 跳跃：vy≤0.08 ∧ 脚下支撑才起跳（Friday 口径）。 */
    Result jump() {
        LivingEntity self = requireBound();
        if (self == null) {
            return Result.error("感官未开启或未绑定——控制类工具禁用（当前: " + sense.state() + "）");
        }
        if (!self.onGround()) {
            return Result.error("脚下无支撑——跳不起来");
        }
        if (self.getDeltaMovement().y > 0.08) {
            return Result.error("仍在上升中（vy>0.08）——等落稳再跳");
        }
        var v = self.getDeltaMovement();
        self.setDeltaMovement(v.x, 0.42, v.z);
        return Result.ok(Map.of("ok", true, "y", self.getY()));
    }

    /** goto：不可达预检（高差/流体/未加载）→ 显式报因；然后物理滑动趋近（禁 setpos）。 */
    Result gotoPos(Map<String, Object> params) {
        LivingEntity self = requireBound();
        if (self == null) {
            return Result.error("感官未开启或未绑定——控制类工具禁用（当前: " + sense.state() + "）");
        }
        net.minecraft.core.BlockPos target;
        try {
            target = parseBlock(String.valueOf(params.get("target")));
        } catch (RuntimeException e) {
            return Result.error("goto 需要 target（x,y,z）: " + e.getMessage());
        }
        ServerLevel level = ctx.level();
        if (level == null) {
            return Result.error("世界未就绪");
        }
        if (!level.isLoaded(target)) {
            return Result.error("不可达：目标区块未加载");
        }
        if (Math.abs(target.getY() - self.getY()) > 3.0) {
            return Result.error("不可达：高差超阈值（>3，跳跃/攀爬未实现）");
        }
        if (!level.getBlockState(target).getFluidState().isEmpty()) {
            return Result.error("不可达：目标在流体中（游泳未实现）");
        }
        int max = params.get("max_ticks") instanceof Number n ? n.intValue() : 400;
        Result r = moveM.startGoto(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, max);
        if (r instanceof Result.Error) {
            return r;
        }
        primeMove(self);
        return r;
    }

    Result stopMove() {
        LivingEntity self = requireBound();
        if (self != null) {
            var v = self.getDeltaMovement();
            self.setDeltaMovement(0, v.y, 0);
        }
        return moveM.stop();
    }

    Result moveStatus() {
        Map<String, Object> d = new java.util.LinkedHashMap<>();
        LivingEntity self = sense.boundEntity() == null ? null : boundLiving(ctx.level());
        if (self != null) {
            d.put("x", self.getX());
            d.put("y", self.getY());
            d.put("z", self.getZ());
            d.put("speed", Math.sqrt(self.getDeltaMovement().x * self.getDeltaMovement().x
                    + self.getDeltaMovement().z * self.getDeltaMovement().z));
        }
        d.putAll(moveM.describe(self == null ? 0 : self.getX(), self == null ? 0 : self.getY(),
                self == null ? 0 : self.getZ()));
        return Result.ok(d);
    }

    /** 每 tick 移动推进（壳侧 tick 钩子；自测在真 tick 间驱动）。 */
    public void tickMove() {
        if (!moveM.busy()) {
            return;
        }
        ServerLevel level = ctx.level();
        LivingEntity self = boundLiving(level);
        if (level == null || self == null) {
            return;
        }
        if (!movePrimed) {
            primeMove(self);
        }
        double moved = Math.sqrt((self.getX() - lastMoveX) * (self.getX() - lastMoveX)
                + (self.getZ() - lastMoveZ) * (self.getZ() - lastMoveZ));
        boolean arrived = false;
        if (moveM.mode() == io.github.piwugu.snowleopard.tools.MoveMachine.Mode.GOTO) {
            double[] t = moveM.target();
            arrived = Math.sqrt((self.getX() - t[0]) * (self.getX() - t[0])
                            + (self.getZ() - t[2]) * (self.getZ() - t[2]))
                    < io.github.piwugu.snowleopard.tools.MoveMachine.ARRIVE_EPS
                    && Math.abs(self.getY() - t[1]) < 1.5;
        }
        var outcome = moveM.tick(moved, arrived);
        lastMoveX = self.getX();
        lastMoveY = self.getY();
        lastMoveZ = self.getZ();
        if (outcome == io.github.piwugu.snowleopard.tools.MoveMachine.Outcome.PROGRESS) {
            actuate(self);
        } else {
            var v = self.getDeltaMovement();
            self.setDeltaMovement(0, v.y, 0);   // 结束即停水平速度
        }
    }

    /** 物理驱动：给水平速度，碰撞/摩擦交给引擎（Mover 唯一底层=引擎物理，禁 setpos）。 */
    private void actuate(LivingEntity self) {
        double speed = 0.22;
        double vx;
        double vz;
        if (moveM.mode() == io.github.piwugu.snowleopard.tools.MoveMachine.Mode.GOTO) {
            double[] t = moveM.target();
            double dx = t[0] - self.getX();
            double dz = t[2] - self.getZ();
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len < 1e-6) {
                return;
            }
            vx = dx / len * speed;
            vz = dz / len * speed;
        } else {
            double[] dir = moveM.direction();
            vx = dir[0] * speed;
            vz = dir[1] * speed;
        }
        var v = self.getDeltaMovement();
        self.setDeltaMovement(vx, v.y, vz);
    }

    private void primeMove(LivingEntity self) {
        lastMoveX = self.getX();
        lastMoveY = self.getY();
        lastMoveZ = self.getZ();
        movePrimed = true;
    }

    // ── item / world（M5）──

    /** 可选检索串：缺省/"null"/空白 → null（=不过滤）。String.valueOf(null)="null" 的坑。 */
    private static String optQuery(Map<String, Object> params) {
        Object q = params.get("query");
        String s = String.valueOf(q);
        return (q == null || s.isBlank() || "null".equals(s)) ? null : s;
    }

    /** 槽位引用：{@code x,y,z:槽号}（容器）或 {@code self:mainhand|offhand|head|chest|legs|feet}。 */
    private record SlotRef(boolean self, net.minecraft.core.BlockPos pos, int slot,
                           net.minecraft.world.entity.EquipmentSlot equip) {
    }

    private static SlotRef parseSlot(String ref) {
        if (ref == null || ref.isBlank()) {
            throw new IllegalArgumentException("槽位引用形如 \"x,y,z:3\" 或 \"self:mainhand\"");
        }
        if (ref.startsWith("self:")) {
            String e = ref.substring("self:".length()).toLowerCase(java.util.Locale.ROOT);
            return switch (e) {
                case "mainhand" -> new SlotRef(true, null, -1, net.minecraft.world.entity.EquipmentSlot.MAINHAND);
                case "offhand" -> new SlotRef(true, null, -1, net.minecraft.world.entity.EquipmentSlot.OFFHAND);
                case "head" -> new SlotRef(true, null, -1, net.minecraft.world.entity.EquipmentSlot.HEAD);
                case "chest" -> new SlotRef(true, null, -1, net.minecraft.world.entity.EquipmentSlot.CHEST);
                case "legs" -> new SlotRef(true, null, -1, net.minecraft.world.entity.EquipmentSlot.LEGS);
                case "feet" -> new SlotRef(true, null, -1, net.minecraft.world.entity.EquipmentSlot.FEET);
                default -> throw new IllegalArgumentException("未知装备槽: " + e);
            };
        }
        int i = ref.lastIndexOf(':');
        if (i < 0) {
            throw new IllegalArgumentException("容器槽位引用形如 \"x,y,z:3\"");
        }
        return new SlotRef(false, parseBlock(ref.substring(0, i)),
                Integer.parseInt(ref.substring(i + 1).trim()), null);
    }

    private net.minecraft.world.item.ItemStack stackAt(LivingEntity self, ServerLevel level, SlotRef ref) {
        return ref.self() ? self.getItemBySlot(ref.equip())
                : containerAt(level, ref.pos()).getItem(ref.slot());
    }

    private void setStackAt(LivingEntity self, ServerLevel level, SlotRef ref,
                            net.minecraft.world.item.ItemStack stack) {
        if (ref.self()) {
            self.setItemSlot(ref.equip(), stack);
        } else {
            containerAt(level, ref.pos()).setItem(ref.slot(), stack);
        }
    }

    private net.minecraft.world.Container containerAt(ServerLevel level, net.minecraft.core.BlockPos pos) {
        if (!level.isLoaded(pos)) {
            throw new IllegalArgumentException("目标区块未加载: " + pos);
        }
        if (level.getBlockEntity(pos) instanceof net.minecraft.world.Container c) {
            return c;
        }
        throw new IllegalArgumentException("目标不是容器: " + pos);
    }

    private static java.util.List<ItemIndex.Entry> entriesOf(
            java.util.List<java.util.AbstractMap.SimpleEntry<String, net.minecraft.world.item.ItemStack>> stacks) {
        java.util.List<ItemIndex.Entry> out = new java.util.ArrayList<>();
        for (var e : stacks) {
            net.minecraft.world.item.ItemStack s = e.getValue();
            if (s.isEmpty()) {
                continue;
            }
            out.add(new ItemIndex.Entry(e.getKey(),
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()).toString(),
                    s.getHoverName().getString(), s.getCount()));
        }
        return out;
    }

    private static ItemIndex.Entry entryOf(String slot, net.minecraft.world.item.ItemStack s) {
        return new ItemIndex.Entry(slot,
                net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()).toString(),
                s.getHoverName().getString(), s.getCount());
    }

    Result selfItems(Map<String, Object> params) {
        LivingEntity self = requireBound();
        if (self == null) {
            return Result.error("感官未开启或未绑定——控制类工具禁用（当前: " + sense.state() + "）");
        }
        java.util.List<java.util.AbstractMap.SimpleEntry<String, net.minecraft.world.item.ItemStack>> stacks =
                new java.util.ArrayList<>();
        stacks.add(new java.util.AbstractMap.SimpleEntry<>("self:mainhand", self.getMainHandItem()));
        stacks.add(new java.util.AbstractMap.SimpleEntry<>("self:offhand", self.getOffhandItem()));
        for (var slot : new net.minecraft.world.entity.EquipmentSlot[]{
                net.minecraft.world.entity.EquipmentSlot.HEAD, net.minecraft.world.entity.EquipmentSlot.CHEST,
                net.minecraft.world.entity.EquipmentSlot.LEGS, net.minecraft.world.entity.EquipmentSlot.FEET}) {
            stacks.add(new java.util.AbstractMap.SimpleEntry<>("self:" + slot.getName(), self.getItemBySlot(slot)));
        }
        ItemIndex index = new ItemIndex(entriesOf(stacks));
        List<ItemIndex.Entry> hits = index.search(optQuery(params));
        java.util.List<Map<String, Object>> items = new java.util.ArrayList<>();
        for (ItemIndex.Entry e : hits) {
            items.add(Map.of("slot", e.slot(), "registry", e.registry(),
                    "display", e.display(), "count", e.count()));
        }
        return Result.ok(Map.of("items", items));
    }

    Result containerItems(Map<String, Object> params) {
        LivingEntity self = requireBound();
        if (self == null) {
            return Result.error("感官未开启或未绑定——控制类工具禁用（当前: " + sense.state() + "）");
        }
        ServerLevel level = ctx.level();
        String target = String.valueOf(params.get("target"));
        String address;
        java.util.List<java.util.AbstractMap.SimpleEntry<String, net.minecraft.world.item.ItemStack>> stacks =
                new java.util.ArrayList<>();
        if ("self".equals(target)) {
            address = "self";
            stacks.add(new java.util.AbstractMap.SimpleEntry<>("self:mainhand", self.getMainHandItem()));
        } else if (target != null && !target.isBlank() && !"null".equals(target)) {
            net.minecraft.core.BlockPos pos = parseBlock(target);
            address = "coord:" + pos;
            net.minecraft.world.Container c = containerAt(level, pos);
            for (int i = 0; i < c.getContainerSize(); i++) {
                stacks.add(new java.util.AbstractMap.SimpleEntry<>(String.valueOf(i), c.getItem(i)));
            }
        } else {
            var hit = self.pick(8.0, 1.0f, false);
            if (!(hit instanceof net.minecraft.world.phys.BlockHitResult bh)
                    || bh.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) {
                return Result.error("视线内无容器——请显式给 target（pick 实际: " + hit
                        + " 眼位 " + self.getEyePosition(1.0f) + " 朝向 " + self.getViewVector(1.0f) + "）");
            }
            address = "raycast:" + bh.getBlockPos();
            net.minecraft.world.Container c = containerAt(level, bh.getBlockPos());
            for (int i = 0; i < c.getContainerSize(); i++) {
                stacks.add(new java.util.AbstractMap.SimpleEntry<>(String.valueOf(i), c.getItem(i)));
            }
        }
        ItemIndex index = new ItemIndex(entriesOf(stacks));
        java.util.List<Map<String, Object>> items = new java.util.ArrayList<>();
        for (ItemIndex.Entry e : index.search(optQuery(params))) {
            items.add(Map.of("slot", e.slot(), "registry", e.registry(),
                    "display", e.display(), "count", e.count()));
        }
        return Result.ok(Map.of("items", items, "address", address));
    }

    Result moveItem(Map<String, Object> params) {
        LivingEntity self = requireBound();
        if (self == null) {
            return Result.error("感官未开启或未绑定——控制类工具禁用（当前: " + sense.state() + "）");
        }
        ServerLevel level = ctx.level();
        SlotRef from = parseSlot(String.valueOf(params.get("from")));
        SlotRef to = parseSlot(String.valueOf(params.get("to")));
        net.minecraft.world.item.ItemStack src = stackAt(self, level, from);
        if (src.isEmpty()) {
            return Result.error("来源槽为空: " + params.get("from"));
        }
        int want = params.get("count") instanceof Number n ? n.intValue() : src.getCount();
        want = Math.min(want, src.getCount());
        if (want <= 0) {
            return Result.error("移动数量非法");
        }
        net.minecraft.world.item.ItemStack moved = src.copy();
        moved.setCount(want);
        src.shrink(want);                       // 拆分
        setStackAt(self, level, from, src);
        net.minecraft.world.item.ItemStack dst = stackAt(self, level, to);
        if (dst.isEmpty()) {
            setStackAt(self, level, to, moved);
        } else if (net.minecraft.world.item.ItemStack.isSameItemSameComponents(dst, moved)) {
            dst.grow(want);
            setStackAt(self, level, to, dst);
        } else {
            src.grow(want);                     // 目标异物——回滚
            setStackAt(self, level, from, src);
            return Result.error("目标槽有异物——已回滚");
        }
        // 服务端槽位核实
        int afterFrom = stackAt(self, level, from).getCount();
        int afterTo = stackAt(self, level, to).getCount();
        return Result.ok(Map.of("moved", want, "fromRemaining", afterFrom, "toCount", afterTo));
    }

    Result dropItem(Map<String, Object> params) {
        LivingEntity self = requireBound();
        if (self == null) {
            return Result.error("感官未开启或未绑定——控制类工具禁用（当前: " + sense.state() + "）");
        }
        ServerLevel level = ctx.level();
        SlotRef from = parseSlot(String.valueOf(params.get("from")));
        net.minecraft.world.item.ItemStack src = stackAt(self, level, from);
        if (src.isEmpty()) {
            return Result.error("来源槽为空: " + params.get("from"));
        }
        int want = params.get("count") instanceof Number n ? n.intValue() : src.getCount();
        want = Math.min(want, src.getCount());
        net.minecraft.world.item.ItemStack dropped = src.copy();
        dropped.setCount(want);
        src.shrink(want);
        setStackAt(self, level, from, src);
        self.spawnAtLocation(dropped, 0);
        int after = stackAt(self, level, from).getCount();
        return Result.ok(Map.of("dropped", want, "fromRemaining", after));
    }

    /**
     * 区域查询（单一工具，中心|框选）。双护栏：仅已加载区块（note 声明）+ 返回量上限（截断标记）。
     * 含实体时带 controller 归属标注（裁决 §1.4）。
     */
    Result worldQuery(Map<String, Object> params,
                      java.util.function.Function<String, String> controllerOf) {
        LivingEntity self = requireBound();
        if (self == null) {
            return Result.error("感官未开启或未绑定——控制类工具禁用（当前: " + sense.state() + "）");
        }
        ServerLevel level = ctx.level();
        if (level == null) {
            return Result.error("世界未就绪");
        }
        String box = params.get("box") == null ? "" : String.valueOf(params.get("box"));
        net.minecraft.world.phys.AABB area;
        if (!box.isBlank() && !"null".equals(box)) {
            String[] p = box.split(",");
            if (p.length != 6) {
                return Result.error("box 形如 \"x1,y1,z1,x2,y2,z2\"");
            }
            double x1 = Double.parseDouble(p[0]), y1 = Double.parseDouble(p[1]), z1 = Double.parseDouble(p[2]);
            double x2 = Double.parseDouble(p[3]), y2 = Double.parseDouble(p[4]), z2 = Double.parseDouble(p[5]);
            area = new net.minecraft.world.phys.AABB(x1, y1, z1, x2, y2, z2);
        } else {
            double cx = self.getX(), cy = self.getY(), cz = self.getZ();
            Object c = params.get("center");
            if (c != null && !String.valueOf(c).isBlank() && !"null".equals(c)) {
                net.minecraft.core.BlockPos pos = parseBlock(String.valueOf(c));
                cx = pos.getX() + 0.5;
                cy = pos.getY() + 0.5;
                cz = pos.getZ() + 0.5;
            }
            double r = params.get("radius") instanceof Number n ? n.doubleValue() : 8.0;
            area = new net.minecraft.world.phys.AABB(cx, cy, cz, cx, cy, cz).inflate(r);
        }
        String query = String.valueOf(params.get("query"));
        boolean withEntities = Boolean.parseBoolean(String.valueOf(params.get("include_entities")));
        int maxChars = params.get("max_chars") instanceof Number n ? n.intValue() : 4000;

        java.util.List<Map<String, Object>> blocks = new java.util.ArrayList<>();
        int totalBlocks = 0;
        int unloadedSkipped = 0;
        java.util.Set<Long> unloadedChunks = new java.util.HashSet<>();
        int cap = 64;
        for (net.minecraft.core.BlockPos pos : net.minecraft.core.BlockPos.betweenClosed(
                net.minecraft.core.BlockPos.containing(area.minX, area.minY, area.minZ),
                net.minecraft.core.BlockPos.containing(area.maxX, area.maxY, area.maxZ))) {
            if (!level.isLoaded(pos)) {
                unloadedChunks.add(net.minecraft.world.level.ChunkPos.asLong(pos));
                continue;
            }
            var state = level.getBlockState(pos);
            if (state.isAir()) {
                continue;
            }
            String registry = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            String display = state.getBlock().getName().getString();
            if (!query.isBlank() && !"null".equals(query)
                    && !registry.toLowerCase(java.util.Locale.ROOT).contains(query.toLowerCase(java.util.Locale.ROOT))
                    && !display.toLowerCase(java.util.Locale.ROOT).contains(query.toLowerCase(java.util.Locale.ROOT))) {
                continue;
            }
            totalBlocks++;
            if (blocks.size() < cap) {
                blocks.add(Map.of("x", pos.getX(), "y", pos.getY(), "z", pos.getZ(),
                        "registry", registry, "display", display));
            }
        }
        unloadedSkipped = unloadedChunks.size();

        java.util.List<Map<String, Object>> entities = new java.util.ArrayList<>();
        int totalEntities = 0;
        if (withEntities) {
            for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, area)) {
                if (e == self) {
                    continue;
                }
                String ref = e.getUUID().toString();
                totalEntities++;
                if (entities.size() < cap) {
                    String controller = controllerOf == null ? "" : String.valueOf(controllerOf.apply(ref));
                    entities.add(Map.of("ref", ref,
                            "type", net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString(),
                            "name", e.getName().getString(),
                            "x", e.getX(), "y", e.getY(), "z", e.getZ(),
                            "controller", controller));
                }
            }
        }

        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("blocks", blocks);
        out.put("blocksTotal", totalBlocks);
        if (withEntities) {
            out.put("entities", entities);
            out.put("entitiesTotal", totalEntities);
        }
        out.put("note", "仅已加载区块（" + unloadedSkipped + " 个未加载区块已跳过）");
        String rendered = String.valueOf(out);
        if (rendered.length() > maxChars) {
            out.put("truncated", true);
            out.put("note", out.get("note") + "；返回超 " + maxChars + " 字符已截断（调 max_chars 放开）");
        } else {
            out.put("truncated", false);
        }
        return Result.ok(out);
    }

    // ── 死亡流（壳侧事件钩子调用）──

    /** 实体死亡：绑定实体死亡→感官自动关+解绑（裁决）；其他→候选消亡，状态不动。 */
    public Result onEntityDeath(String entityRef) {
        Result r = sense.death(entityRef);
        bindings.dropEntity(entityRef);
        if (r instanceof Result.Ok) {
            lastCandidates = List.of();
            return Result.ok(Map.of("state", SenseState.OFF.name(),
                    "reason", "绑定实体死亡，感官自动关闭"));
        }
        return r;
    }

    private void wakeAllQuietly() {
        ServerLevel level = ctx.level();
        if (level == null) {
            return;
        }
        for (CandidateInfo c : lastCandidates) {
            Entity e = level.getEntity(java.util.UUID.fromString(c.entityRef()));
            if (e instanceof LivingEntity le) {
                dormancy.wake(le);
            }
        }
        lastCandidates = new ArrayList<>();
    }
}
