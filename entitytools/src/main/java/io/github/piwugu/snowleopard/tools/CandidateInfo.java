package io.github.piwugu.snowleopard.tools;

import java.util.Map;

/**
 * 候选实体三件套信息结构（M0 冻结，2026-10-23 05:30 (UTC+8)；裁决 05:10"提供实体信息、位置信息、
 * 周围环境信息给模型"）。
 *
 * <p>字段表冻结：{@code entityInfo}（实体是什么：类型/名称/血量/归属 controller）、
 * {@code position}（位置：x/y/z/维度）、{@code surroundings}（周围环境：所在方块/光照/附近实体数）。
 * M1 起由 MC 适配层填充真实数据——结构不许再改（改=契约破坏）。
 *
 * @param entityRef    候选编号（pick 用）
 * @param entityInfo   实体信息（冻结键：type/name/health/controller）
 * @param position     位置信息（冻结键：x/y/z/dimension）
 * @param surroundings 周围环境信息（冻结键：block/light/nearbyEntities）
 */
public record CandidateInfo(String entityRef,
                            Map<String, Object> entityInfo,
                            Map<String, Object> position,
                            Map<String, Object> surroundings) {

    public CandidateInfo {
        if (entityRef == null || entityRef.isBlank()) {
            throw new IllegalArgumentException("候选编号不能为空（pick 的寻址键）");
        }
        entityInfo = entityInfo == null ? Map.of() : Map.copyOf(entityInfo);
        position = position == null ? Map.of() : Map.copyOf(position);
        surroundings = surroundings == null ? Map.of() : Map.copyOf(surroundings);
    }
}
