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

    // ── item（物品，M5）──
    public static final String ITEM_SELF_ITEMS = "self_items";
    public static final String ITEM_CONTAINER_ITEMS = "container_items";
    public static final String ITEM_MOVE_ITEM = "move_item";
    public static final String ITEM_DROP = "drop";

    // ── world（世界，M5）──
    public static final String WORLD_QUERY = "query";

    // ── watch（动态感知，M6）──
    public static final String WATCH_WATCH = "watch";
    public static final String WATCH_UNWATCH = "unwatch";
    public static final String WATCH_LIST = "watch_list";
    public static final String WATCH_EVENTS = "watch_events";

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
                    "interact.attack(target, confirm_token?) → {ok, healthBefore, healthAfter}",
                    Map.of("type", "object", "required", java.util.List.of("target"),
                            "target", "候选编号（显式目标优先）",
                            "confirm_token", "攻击玩家类型目标时必经两段式：首段返回 pending+令牌（不执行），带令牌二次调用才执行",
                            "reversible", "false", "state", "changes_world",
                            "红线", "其他编号实体一律拒绝",
                            "审批", "攻击真人/玩家默认走审批（digest 绑定目标+血量，变化即失效）")),
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

    public static final java.util.List<ToolRegistry.Tool> ITEM_TOOLS = java.util.List.of(
            new ToolRegistry.Tool(ITEM_SELF_ITEMS, "自身携带物品（含装备栏；双索引：registry+显示名）",
                    "item.self_items(query?) → {items:[{slot,registry,display,count}]}",
                    Map.of("type", "object", "query", "可选检索（registry 或显示名子串，忽略大小写）")),
            new ToolRegistry.Tool(ITEM_CONTAINER_ITEMS, "容器物品（容器寻址三选一；双索引检索）",
                    "item.container_items(target?, query?) → {items, address}",
                    Map.of("type", "object",
                            "target", "\"x,y,z\"|\"self\"；缺省=视线 raycast",
                            "query", "可选检索")),
            new ToolRegistry.Tool(ITEM_MOVE_ITEM, "物品移动（容器 slot API；数量拆分/整组）",
                    "item.move_item(from, to, count?) → {moved}；from/to 如 \"x,y,z:3\"|\"self:mainhand\"",
                    Map.of("type", "object", "required", java.util.List.of("from", "to"),
                            "from", "槽位引用", "to", "槽位引用",
                            "count", "可选数量（缺省=整组）",
                            "reversible", "true", "state", "changes_world")),
            new ToolRegistry.Tool(ITEM_DROP, "丢弃物品（从槽位掉落实体）",
                    "item.drop(from, count?) → {dropped}",
                    Map.of("type", "object", "required", java.util.List.of("from"),
                            "from", "槽位引用", "count", "可选数量（缺省=整组）",
                            "reversible", "false", "state", "changes_world")));

    public static final java.util.List<ToolRegistry.Tool> WORLD_TOOLS = java.util.List.of(
            new ToolRegistry.Tool(WORLD_QUERY, "区域查询（单一工具：中心|框选 + 检索 + 含实体；双护栏）",
                    "world.query(center?|box?, radius?, query?, include_entities?, max_chars?) → {blocks, entities, note, truncated?}",
                    Map.of("type", "object",
                            "center", "\"x,y,z\"（缺省=自身位置）", "box", "\"x1,y1,z1,x2,y2,z2\" 与 center 二选一",
                            "radius", "半径（缺省 8）", "query", "可选检索",
                            "include_entities", "含实体（带 controller 归属标注）",
                            "max_chars", "返回上限（缺省 4000，截断带标记）",
                            "护栏", "仅已加载区块（声明在 note）")));

    public static final java.util.List<ToolRegistry.Tool> WATCH_TOOLS = java.util.List.of(
            new ToolRegistry.Tool(WATCH_WATCH, "锁定实体开始记录（命名与视角\"锁定\"分义——watch=监视）",
                    "watch.watch(target) → {watching:[{entityRef, controller, events}]}",
                    Map.of("type", "object", "required", java.util.List.of("target"),
                            "target", "实体编号（候选编号/UUID）",
                            "note", "记录范围含真人玩家与绑定实体；绑定实体自动入监视（裁决 §1.4）")),
            new ToolRegistry.Tool(WATCH_UNWATCH, "解锁：停止记录（历史保留在保留窗口内）",
                    "watch.unwatch(target) → {watching}",
                    Map.of("type", "object", "required", java.util.List.of("target"),
                            "target", "实体编号")),
            new ToolRegistry.Tool(WATCH_LIST, "查询监视列表（每项带归属 controller）",
                    "watch.watch_list() → {watching:[{entityRef, controller, events, lastTick}]}",
                    Map.of("type", "object", "note", "无参数")),
            new ToolRegistry.Tool(WATCH_EVENTS, "查询动态（事件集+轨迹枚举写死；每条带 controller）",
                    "watch.watch_events(target?, limit?, since_tick?) → {events:[{tick, kind, entityRef, controller, data}]}",
                    Map.of("type", "object",
                            "target", "可选实体编号（缺省=全部，混合归属逐一可见）",
                            "limit", "条数上限（缺省 20，留最近）",
                            "since_tick", "只取该 tick 之后（含）",
                            "kind 枚举", "TRAJECTORY|ATTACKED|DIED（写死）",
                            "窗口", "条数 64/实体 + 时间 1200 tick 双淘汰；轨迹限频 10 tick")));

    private ToolSchemas() {
    }
}
