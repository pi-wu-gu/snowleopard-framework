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
    public static final String INTERACT_CAPTURE = "capture";

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
    public static final String ITEM_PICKUP = "pickup";
    public static final String ITEM_PICKUP_AUTO = "pickup_auto";

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
                    Map.of("type", "object", "note", "无参数")),
            new ToolRegistry.Tool(INTERACT_CAPTURE, "捕获活体进随身包（v1.10.0：只蝙蝠；包满报错不落地）",
                    "interact.capture(target) → {ok, item, count}；放走/吃掉是玩家对该物品的右键行为",
                    Map.of("type", "object", "required", java.util.List.of("target"),
                            "target", "实体编号（query include_entities 得到；限蝙蝠，≤3.5 格）",
                            "reversible", "false", "state", "changes_world")));

    public static final java.util.List<ToolRegistry.Tool> MOVE_TOOLS = java.util.List.of(
            new ToolRegistry.Tool(MOVE_MOVE, "移动：方向+时长|距离（方向=开始时朝向冻结；**移动时朝向自动对准行进方向**，视角锁定期间不抢）",
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
            new ToolRegistry.Tool(MOVE_GOTO, "移动到坐标（引擎物理滑动；DONE=连续 2 tick 贴住；**朝向沿途对准目标方向**）",
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
                            "count", "可选数量（缺省=整组；数字或数字字符串都收）",
                            "reversible", "true", "state", "changes_world")),
            new ToolRegistry.Tool(ITEM_DROP, "丢弃物品（从槽位掉落实体）",
                    "item.drop(from, count?) → {dropped}",
                    Map.of("type", "object", "required", java.util.List.of("from"),
                            "from", "槽位引用",
                            "count", "可选数量（缺省=整组；数字或数字字符串都收）",
                            "reversible", "false", "state", "changes_world")),
            new ToolRegistry.Tool(ITEM_PICKUP, "主动拾取身边掉落物（≤触达半径才收；远处先 goto）",
                    "item.pickup(query?, radius?) → {picked, skipped}",
                    Map.of("type", "object",
                            "query", "可选过滤（registry id 或显示名子串；缺省=全部）",
                            "radius", "可选扫描半径（缺省 4，上限 6；实际收取限触达 2.5 格）",
                            "reversible", "true", "state", "changes_world")),
            new ToolRegistry.Tool(ITEM_PICKUP_AUTO, "自动拾取开关（走过就捡；自己丢出的不捡回）",
                    "item.pickup_auto(enabled?, filter?) → {enabled, filter}",
                    Map.of("type", "object",
                            "enabled", "on|off|status（缺省=查询当前态）",
                            "filter", "可选 registry id 子串（空=全部；只影响自动拾取）",
                            "note", "开关随实体持久化；依赖 mobGriefing 游戏规则（默认开）")));

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

    // ── chat（M12：注入 / 发言 / 检索）──
    public static final String CHAT_SEND = "chat_send";
    public static final String CHAT_READ = "chat_read";
    public static final String CHAT_SEARCH = "chat_search";
    public static final String CHAT_STATUS = "chat_status";

    /**
     * 聊天组清单（M12-0 冻结，2026-10-24 00:50 (UTC+8)）。
     *
     * <p>**没有 `chat_mentions`**：被提及只进轮末摘要那一行（`· 被提及 M 次`），
     * 想看正文用 `chat_search`。多一个"调了也没内容"的工具只会误导模型。
     */
    public static final java.util.List<ToolRegistry.Tool> CHAT_TOOLS = java.util.List.of(
            new ToolRegistry.Tool(CHAT_SEND, "以载体身份在聊天栏发言（玩家可见，不可撤回）",
                    "chat.send(text) → {delivered, name}；最小间隔 0.5s（可配），超限报错并给剩余等待",
                    Map.of("type", "object", "required", java.util.List.of("text"),
                            "text", "要说的话（≤256 字符；会带上 [雪豹·<名称>] 前缀）")),
            new ToolRegistry.Tool(CHAT_READ, "显式读取未读聊天全文（读取即推进游标，之后不再自动注入）",
                    "chat.read(limit?) → {messages:[{seq, ts, name, text}]}",
                    Map.of("type", "object", "limit", "条数上限（缺省 20）",
                            "note", "只含玩家来源；要查别的模型说过什么用 chat_search")),
            new ToolRegistry.Tool(CHAT_SEARCH, "按天 + 关键词 + 时间窗检索聊天（含模型自己说过的话）",
                    "chat.search(keyword?, date?, since?, until?, source?, limit?) → {matched, scanned, truncated, hits}",
                    Map.of("type", "object",
                            "keyword", "空格分词，全部命中（AND），大小写不敏感",
                            "date", "YYYY-MM-DD（缺省=今天）",
                            "since", "HH:MM 或 yyyy-MM-dd HH:mm（含）",
                            "until", "同上（含）",
                            "source", "PLAYER|MODEL（缺省=全部）",
                            "limit", "命中上限（缺省 20，最大 100）")),
            new ToolRegistry.Tool(CHAT_STATUS, "查询我的聊天游标（未读数 / 被提及次数）",
                    "chat.status() → {lastReadSeq, lastScanSeq, pendingMentions, lastSeq, unread}",
                    Map.of("type", "object", "note", "无参数")));

    // ── avatar（M13：载体绑定 / 定时唤醒）──
    public static final String AVATAR_BIND = "avatar_bind";
    public static final String AVATAR_UNBIND = "avatar_unbind";
    public static final String AVATAR_SCHEDULE = "avatar_schedule";
    public static final String AVATAR_STATUS = "avatar_status";

    /** 载体组清单（M13-0 冻结，2026-10-24 01:10 (UTC+8)）。 */
    public static final java.util.List<ToolRegistry.Tool> AVATAR_TOOLS = java.util.List.of(
            new ToolRegistry.Tool(AVATAR_BIND, "把当前绑定的实体登记为我的载体（唤醒的靶子；重复绑定=换主载体）",
                    "avatar.bind() → {uuid, name, primary}；实体必须是 snowleopard:avatar；重名（与其它载体）→ 拒绝",
                    Map.of("type", "object", "note", "无参数；实体来自 entity.pick")),
            new ToolRegistry.Tool(AVATAR_UNBIND, "解除我的全部载体绑定（含定时）",
                    "avatar.unbind() → {removed}",
                    Map.of("type", "object", "note", "无参数")),
            new ToolRegistry.Tool(AVATAR_SCHEDULE, "设定定时唤醒间隔（真实时间，下限 60 秒）",
                    "avatar.schedule(interval_sec) → {intervalSec, nextFireAt}；实体未加载时跳过不补发",
                    Map.of("type", "object", "required", java.util.List.of("interval_sec"),
                            "interval_sec", "间隔秒数（>=60，下限由配置可抬不可降）")),
            new ToolRegistry.Tool(AVATAR_STATUS, "查询我的载体与唤醒排程",
                    "avatar.status() → {avatars:[{uuid, name, primary, intervalSec, nextFireAt, loaded}]}",
                    Map.of("type", "object", "note", "无参数")));

    // ── craft（合成，2026-10-24 03:55 冻结：配方直合成；裁决=不要求工作台邻接、网格不做）──
    public static final String CRAFT_RECIPES = "craft_recipes";
    public static final String CRAFT = "craft";

    // ── vision（视觉，2026-10-27 06:05 (UTC+8)：B 档射线；C 档截图见后续版本）──
    /** 视线射线扫描：服务端出结果，任何距离都成立（C 档真截图在远处会拍到虚空）。 */
    public static final String VISION_SCAN = "scan";
    /**
     * 载体 POV 截图：**异步受理**，返回 reqId，画面稍后注入（不能同步等渲染）。
     *
     * <p>叫 {@code shot} 而不是 {@code capture}：后者已被 {@code interact/capture}（抓蝙蝠）占用。
     * 暴露名会带组前缀（{@code vision_shot} vs {@code interact_capture}），但模型对同名工具
     * 天然容易混——**同一套工具里不出现两个 capture**。
     */
    public static final String VISION_SHOT = "shot";
    /** 重看已落盘的图：不重渲、不耗 GPU。 */
    public static final String VISION_SHOW = "show";

    /** vision 组清单（3 工具）。
     *
     * <p><b>描述里写的调用名必须与 LLM 侧暴露名同形</b>（`vision_scan` / `vision_shot` / `vision_show`）：
     * 2026-10-27 实证，旧描述写的是 `vision.capture()`，而暴露名是裸名 `shot` →
     * 模型按描述去调不存在的东西，于是"能看图却从不调用"。
     */
    public static final java.util.List<ToolRegistry.Tool> VISION_TOOLS = java.util.List.of(
            new ToolRegistry.Tool(VISION_SCAN,
                    "沿视线射线扫描正前方：中心/上下左右各命中什么方块（id、距离、颜色、面）与近处实体。"
                            + "不依赖任何客户端，任何距离都有效——想看'那边有什么'但没客户端/离得远时用它",
                    "vision_scan(range?, detail?, width?, height?) → {source:\"raycast\", capability, center, sides:{left,right,up,down}, entities, delivery}；"
                            + "detail=text|image；image 只落盘给路径，不回 base64",
                    Map.of("type", "object",
                            "range", "射线距离（4..64，缺省 32）",
                            "detail", "text（缺省）| image（服务端射线色块图）",
                            "width", "仅 image 生效（16..256，缺省 64）",
                            "height", "仅 image 生效（9..144，缺省 36）")),
            new ToolRegistry.Tool(VISION_SHOT,
                    "申请一张**你自己视角**的真实截图（能看清颜色、形状、文字、生物）。"
                            + "异步：立刻返回编号，画面在**下一轮自动注入**——本轮不要等、不要重复申请。"
                            + "需要判断'这是什么颜色/那是什么东西/门在哪边'这类看得见才说得准的问题时用",
                    "vision_shot() → {reqId, state:\"REQUESTED\", note}；无可用渲染客户端时 → Error 并提示改用 vision_scan",
                    Map.of("type", "object", "note", "无参数（尺寸由服务端配置下发）")),
            new ToolRegistry.Tool(VISION_SHOW,
                    "重看已拍过的截图（不重新渲染、不耗 GPU）；不传编号则取最近一张。"
                            + "上一轮注入的图画质/细节不够、想再对照时可以再取一次",
                    "vision_show(cap_id?) → {reqId, state:\"READY\", file}；找不到 → Error 提示先 vision_shot/vision_scan",
                    Map.of("type", "object",
                            "cap_id", "截图编号（缺省=最近一张）")));

    /** craft 组清单（2 工具）。 */
    public static final java.util.List<ToolRegistry.Tool> CRAFT_TOOLS = java.util.List.of(
            new ToolRegistry.Tool(CRAFT_RECIPES, "列可用工作台配方（id/输出/材料清单）",
                    "craft.recipes(keyword?, limit?) → {matched, truncated, recipes:[{id, output, outputCount, ingredients}]}",
                    Map.of("type", "object",
                            "keyword", "按配方 id 或输出名过滤（如 planks / door）",
                            "limit", "返回上限（缺省 20）")),
            new ToolRegistry.Tool(CRAFT, "按配方从随身背包扣料合成，产物（含桶/碗退回）回背包",
                    "craft(recipe_id, count?) → {crafted, output, toPack}；count 1..8；材料不足 → Error 列缺口；背包放不下 → Error 不扣料",
                    Map.of("type", "object", "required", java.util.List.of("recipe_id"),
                            "recipe_id", "配方 id（来自 craft_recipes，如 minecraft:oak_planks）",
                            "count", "合成次数（1..8，缺省 1）")));

    private ToolSchemas() {
    }
}
