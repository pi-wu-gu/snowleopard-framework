package io.github.piwugu.snowleopard.tools;

import java.util.Map;

import snowleopard.core.registry.ToolRegistry;

/**
 * 工具清单 schema（M1 首批：sense / entity / entity_self 三组；2026-10-23 05:40 (UTC+8)）。
 *
 * <p>name/summary/usage/params 在此单点冻结——注册表元数据与 {@code EntityToolsModule} 调度
 * 的工具名同源（防"清单教一套、实现接一套"的漂移）。其余六组随 M2–M6 追加。
 */
public final class ToolSchemas {

    // ── sense（感官开关）──
    public static final String SENSE_OPEN = "open";
    public static final String SENSE_CLOSE = "close";
    public static final String SENSE_STATUS = "status";

    // ── entity（绑定生命周期）──
    public static final String ENTITY_PICK = "pick";
    public static final String ENTITY_UNBIND = "unbind";
    public static final String ENTITY_STATUS = "status";

    // ── entity_self（自我观测）──
    public static final String SELF_STATUS = "status";

    public static final java.util.List<ToolRegistry.Tool> SENSE_TOOLS = java.util.List.of(
            new ToolRegistry.Tool(SENSE_OPEN, "开启感官：进入等待选取，返回候选三件套",
                    "sense.open() → 候选列表[{entityRef, entityInfo, position, surroundings}]",
                    Map.of("type", "object", "note", "无参数")),
            new ToolRegistry.Tool(SENSE_CLOSE, "关闭感官：解绑并禁用控制类工具",
                    "sense.close() → {state}",
                    Map.of("type", "object", "note", "无参数")),
            new ToolRegistry.Tool(SENSE_STATUS, "查询感官状态",
                    "sense.status() → {state, bound?}",
                    Map.of("type", "object", "note", "无参数")));

    public static final java.util.List<ToolRegistry.Tool> ENTITY_TOOLS = java.util.List.of(
            new ToolRegistry.Tool(ENTITY_PICK, "选取候选并绑定 1:1（未选取候选进入休眠）",
                    "entity.pick(target) → {state, bound}；target=候选编号",
                    Map.of("type", "object", "required", java.util.List.of("target"),
                            "target", "候选编号（sense.open 返回的 entityRef）")),
            new ToolRegistry.Tool(ENTITY_UNBIND, "解绑当前绑定（绑定实体进入休眠）",
                    "entity.unbind() → {state, unbound}",
                    Map.of("type", "object", "note", "无参数")),
            new ToolRegistry.Tool(ENTITY_STATUS, "双向反查：model→entity / entity→model",
                    "entity.status(target?) → {model, entity}；缺省查自身",
                    Map.of("type", "object", "target", "可选：model:<id> 或 entity:<ref>")));

    public static final java.util.List<ToolRegistry.Tool> SELF_TOOLS = java.util.List.of(
            new ToolRegistry.Tool(SELF_STATUS, "自我状态观测：位置朝向/血饥饿/状态效果/装备/维度环境",
                    "entity_self.status() → {x,y,z,yaw,pitch,health,maxHealth,food,effects,armor,dimension,onFire,inWater}",
                    Map.of("type", "object", "note", "无参数；仅 BOUND 态可用")));

    private ToolSchemas() {
    }
}
