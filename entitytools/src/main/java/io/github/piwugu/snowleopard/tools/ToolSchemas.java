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

    // ── view（视角，M2）──
    public static final String VIEW_LOOK = "look";
    public static final String VIEW_LOOK_AT = "look_at";
    public static final String VIEW_UNLOCK = "unlock";
    public static final String VIEW_STATUS = "status";

    // ── interact（交互，M3）──
    public static final String INTERACT_ATTACK = "attack";
    public static final String INTERACT_USE = "use";
    public static final String INTERACT_PLACE = "place";
    public static final String INTERACT_BREAK = "break";
    public static final String INTERACT_STATUS = "status";

    // ── move（移动，M4）──
    public static final String MOVE_MOVE = "move";
    public static final String MOVE_SNEAK = "sneak";
    public static final String MOVE_JUMP = "jump";
    public static final String MOVE_GOTO = "goto";
    public static final String MOVE_STOP = "stop";
    public static final String MOVE_STATUS = "status";

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

    public static final java.util.List<ToolRegistry.Tool> VIEW_TOOLS = java.util.List.of(
            new ToolRegistry.Tool(VIEW_LOOK, "设置视角朝向角度（度）",
                    "view.look(yaw, pitch) → {yaw, pitch}",
                    Map.of("type", "object", "required", java.util.List.of("yaw", "pitch"),
                            "yaw", "度（0=+z 南，-90=+x 东）", "pitch", "度（负=抬头）")),
            new ToolRegistry.Tool(VIEW_LOOK_AT, "锁定视角到方块/实体（六条结束路径自动上报原因）",
                    "view.look_at(target_type, target, face?, duration_ticks?) → {locked, kind, target}",
                    Map.of("type", "object", "required", java.util.List.of("target_type", "target"),
                            "target_type", "block|entity",
                            "target", "block: \"x,y,z\"；entity: 候选编号",
                            "face", "可选 up|down|north|south|east|west（缺省=方块中心）",
                            "duration_ticks", "可选时长（缺省=不限时，仍受其余五条约束）")),
            new ToolRegistry.Tool(VIEW_UNLOCK, "显式解锁（结束原因=EXPLICIT_UNLOCK）",
                    "view.unlock() → {locked:false, reason}",
                    Map.of("type", "object", "note", "无参数")),
            new ToolRegistry.Tool(VIEW_STATUS, "查询视角与锁定状态",
                    "view.status() → {yaw, pitch, locked, kind?, target?, age?, lastEnd?}",
                    Map.of("type", "object", "note", "无参数")));

    public static final java.util.List<ToolRegistry.Tool> INTERACT_TOOLS = java.util.List.of(
            new ToolRegistry.Tool(INTERACT_ATTACK, "攻击目标实体（完成判定=血量变化回推）",
                    "interact.attack(target) → {ok, healthBefore, healthAfter}",
                    Map.of("type", "object", "required", java.util.List.of("target"),
                            "target", "候选编号（显式目标优先）",
                            "reversible", "false", "state", "changes_world",
                            "红线", "其他编号实体一律拒绝")),
            new ToolRegistry.Tool(INTERACT_USE, "对方块使用/交互（完成判定=方块状态回推）",
                    "interact.use(target) → {ok, result}",
                    Map.of("type", "object", "required", java.util.List.of("target"),
                            "target", "\"x,y,z\"（缺省=视线 raycast）",
                            "reversible", "depends", "state", "changes_world")),
            new ToolRegistry.Tool(INTERACT_PLACE, "放置方块（完成判定=方块出现回推；自动面向目标）",
                    "interact.place(target, block) → {ok, placed}",
                    Map.of("type", "object", "required", java.util.List.of("target", "block"),
                            "target", "\"x,y,z\" 放置位", "block", "方块 id 如 minecraft:stone",
                            "reversible", "false", "state", "changes_world")),
            new ToolRegistry.Tool(INTERACT_BREAK, "破坏方块（持续动作：按硬度计时长；超时报因）",
                    "interact.break(target, max_ticks?) → {target, requiredTicks, maxTicks}；进度查 interact.status",
                    Map.of("type", "object", "required", java.util.List.of("target"),
                            "target", "\"x,y,z\"", "max_ticks", "可选预算（缺省 200）",
                            "reversible", "false", "state", "changes_world")),
            new ToolRegistry.Tool(INTERACT_STATUS, "查询挖掘进行状态与结果",
                    "interact.status() → {outcome, target, elapsed, requiredTicks}",
                    Map.of("type", "object", "note", "无参数")));

    public static final java.util.List<ToolRegistry.Tool> MOVE_TOOLS = java.util.List.of(
            new ToolRegistry.Tool(MOVE_MOVE, "移动：方向+时长|距离（三驱动之二；方向=开始时朝向冻结）",
                    "move.move(direction, duration_ticks?|distance?) → {mode, direction}",
                    Map.of("type", "object", "required", java.util.List.of("direction"),
                            "direction", "forward|back|left|right（相对开始时朝向）",
                            "duration_ticks", "时长驱动", "distance", "距离驱动（二选一）",
                            "reversible", "true", "state", "changes_world")),
            new ToolRegistry.Tool(MOVE_SNEAK, "蹲伏姿态开关（独立姿态，非方向）",
                    "move.sneak(on) → {sneaking}",
                    Map.of("type", "object", "required", java.util.List.of("on"),
                            "on", "true|false")),
            new ToolRegistry.Tool(MOVE_JUMP, "跳跃（需脚下支撑；完成后可再跳）",
                    "move.jump() → {ok, y}",
                    Map.of("type", "object", "note", "无参数；vy≤0.08 且脚下支撑才起跳")),
            new ToolRegistry.Tool(MOVE_GOTO, "移动到坐标（引擎物理滑动；DONE=连续 2 tick 贴住）",
                    "move.goto(target, max_ticks?) → {mode, target}；进度查 move.status",
                    Map.of("type", "object", "required", java.util.List.of("target"),
                            "target", "\"x,y,z\"", "max_ticks", "可选超时（缺省 400）",
                            "reversible", "true", "state", "changes_world")),
            new ToolRegistry.Tool(MOVE_STOP, "中断当前移动（立即停）",
                    "move.stop() → {stopped}",
                    Map.of("type", "object", "note", "无参数")),
            new ToolRegistry.Tool(MOVE_STATUS, "查询移动状态（位置/速度/进行中/结果）",
                    "move.status() → {x,y,z, speed, mode, outcome, age, distToTarget?}",
                    Map.of("type", "object", "note", "无参数")));

    private ToolSchemas() {
    }
}
