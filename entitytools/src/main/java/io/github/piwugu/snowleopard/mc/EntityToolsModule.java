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
