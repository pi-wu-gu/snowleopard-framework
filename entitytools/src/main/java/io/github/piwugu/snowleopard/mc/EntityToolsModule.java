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
    private float lastSetYaw;
    private float lastSetPitch;
    private String lockFace = "";
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
     */
    public Result call(String group, String tool, Map<String, Object> params,
                       java.util.function.Predicate<String> excludeRefs) {
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
        self.setYRot(yaw);
        self.setXRot(pitch);
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
            self.setYRot((float) aim.yaw());
            self.setXRot((float) aim.pitch());
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
