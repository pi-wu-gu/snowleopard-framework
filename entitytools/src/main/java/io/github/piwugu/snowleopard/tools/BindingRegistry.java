package io.github.piwugu.snowleopard.tools;

import java.util.LinkedHashMap;
import java.util.Map;

import snowleopard.core.result.Result;

/**
 * 实体-模型绑定登记（M1，2026-10-23 05:40 (UTC+8)）——纯 Java，可 headless 测试。
 *
 * <p>裁决 05:10 语义：<b>1:1</b>（一模型一实体、一实体至一模型）；死亡=自动关感官（由
 * {@link SenseMachine} 处理，本表解绑）；双向反查 model→entity / entity→model。
 * 实体引用是字符串（MC 层传 UUID 串）——本类不认识 MC（同一职责单实现，防漂移）。
 */
public final class BindingRegistry {

    private final Map<String, String> byModel = new LinkedHashMap<>();
    private final Map<String, String> byEntity = new LinkedHashMap<>();

    /** 绑定：两向都空闲才成功（1:1）。 */
    public synchronized Result bind(String modelId, String entityRef) {
        if (modelId == null || modelId.isBlank() || entityRef == null || entityRef.isBlank()) {
            return Result.error("绑定需要 modelId 与 entityRef 两个非空键");
        }
        String curEntity = byModel.get(modelId);
        if (curEntity != null) {
            return Result.error("模型 " + modelId + " 已绑定 " + curEntity
                    + "——先解绑或走感官重新选取");
        }
        String curModel = byEntity.get(entityRef);
        if (curModel != null) {
            return Result.error("实体 " + entityRef + " 已被模型 "
                    + curModel + " 占用（1:1）");
        }
        byModel.put(modelId, entityRef);
        byEntity.put(entityRef, modelId);
        return Result.ok(Map.of("model", modelId, "entity", entityRef));
    }

    /** 解绑（幂等：无绑定返回 WARN）。 */
    public synchronized Result unbind(String modelId) {
        String entityRef = byModel.remove(modelId);
        if (entityRef == null) {
            return Result.warn(Map.of("model", String.valueOf(modelId)),
                    "模型无绑定——无需解绑");
        }
        byEntity.remove(entityRef);
        return Result.ok(Map.of("model", modelId, "entity", entityRef));
    }

    /** 实体死亡/消失时的清理（由死亡流调用；无记录=WARN 幂等）。 */
    public synchronized Result dropEntity(String entityRef) {
        String modelId = byEntity.remove(entityRef);
        if (modelId == null) {
            return Result.warn(Map.of("entity", String.valueOf(entityRef)),
                    "该实体无绑定记录");
        }
        byModel.remove(modelId);
        return Result.ok(Map.of("model", modelId, "entity", entityRef));
    }

    public synchronized String entityOf(String modelId) {
        return byModel.get(modelId);
    }

    public synchronized String modelOf(String entityRef) {
        return byEntity.get(entityRef);
    }

    public synchronized Map<String, String> snapshot() {
        return Map.copyOf(byModel);
    }
}
