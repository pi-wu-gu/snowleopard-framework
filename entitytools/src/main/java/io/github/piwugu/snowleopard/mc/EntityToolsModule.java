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
import io.github.piwugu.snowleopard.tools.WatchFeed;
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

        /**
         * 视觉能力的一行状态（壳侧注入；{@code null} = 未接）。
         *
         * <p>放在 Ctx 而不是让工具层去问宿主：**entitytools 不许反依赖 shell**
         * （与 {@code PickupCarrier} 同一先例——契约在工具层，实现由壳注入）。
         */
        default String visionStatus() {
            return null;
        }

        /**
         * 视觉产物目录（壳侧注入；{@code null} = 不能落图，只给文字描述）。
         * 放 {@code ids/<模型>/vision/`——**家目录内，不跨 ID**。
         */
        default java.nio.file.Path visionDir() {
            return null;
        }

        /** 视觉渲染后端（壳注入：stub 测试桩 / 客户端离屏渲染）；缺省 = 不可用。 */
        default io.github.piwugu.snowleopard.tools.VisionBackend visionBackend() {
            return io.github.piwugu.snowleopard.tools.VisionBackend.NONE;
        }

        /** 截图宽（宿主配置下发，**客户端不许读自己的本地配置**，否则尺寸对不上）。 */
        default int visionWidth() {
            return 384;
        }

        /** 截图高。 */
        default int visionHeight() {
            return 216;
        }
    }

    private final String modelId;
    private final Ctx ctx;
    private final SenseMachine sense = new SenseMachine();
    private final BindingRegistry bindings = new BindingRegistry();
    private final DormancyManager dormancy = new DormancyManager();
    private final LookLockMachine lookLock = new LookLockMachine();

    /** 视线渐进状态机（2026-10-27）：朝向的唯一出口，锁定/移动/显式 look 都经它。 */
    private final io.github.piwugu.snowleopard.tools.AvatarGaze gaze =
            new io.github.piwugu.snowleopard.tools.AvatarGaze();
    /** {@code look} 设定的持续目标（null = 没有）；到期自动失效，不跨会话永久生效。 */
    private Double explicitYaw;
    private Double explicitPitch;
    private int explicitAge;
    /** {@code look} 目标有效期（tick）= 30s。 */
    private static final int EXPLICIT_TTL_TICKS = 600;
    private final io.github.piwugu.snowleopard.tools.MiningTask mining =
            new io.github.piwugu.snowleopard.tools.MiningTask();
    private final io.github.piwugu.snowleopard.tools.MoveMachine moveM =
            new io.github.piwugu.snowleopard.tools.MoveMachine();
    private final io.github.piwugu.snowleopard.tools.WatchFeed watchFeed =
            new io.github.piwugu.snowleopard.tools.WatchFeed();
    /** 两段式确认闸（M7 攻击玩家审批）：每模块一份——令牌按模型隔离，互不通用。 */
    private final snowleopard.core.confirm.ConfirmGate confirmGate =
            new snowleopard.core.confirm.ConfirmGate();
    private double lastMoveX, lastMoveY, lastMoveZ;
    private boolean movePrimed;
    private float lastSetYaw;
    private float lastSetPitch;
    private String lockFace = "";
    private net.minecraft.core.BlockPos miningTarget;
    // **不要在这里存 GameProfile**（2026-10-28 打包实例实测）：entitytools 是 jarJar 内嵌库，
    // 生产环境里它被装进的是"插件层"，`com.mojang.authlib` 不在那一层的可见模块里 →
    // 类初始化就炸 NoClassDefFoundError: com/mojang/authlib/GameProfile，而且**整个
    // EntityToolsModule 从此不可用**（ExceptionInInitializerError 之后每次都是 "Could not initialize class"）。
    // dev 是平铺 classpath，所以永远看不到这个问题。改用 FakePlayerFactory.getMinecraft(level)。
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
                    case ToolSchemas.INTERACT_CAPTURE -> capture(params, excludeRefs);
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
                    case ToolSchemas.ITEM_PICKUP -> pickup(params);
                    case ToolSchemas.ITEM_PICKUP_AUTO -> pickupAuto(params);
                    default -> Result.error("item 组无此工具: " + tool);
                };
                case io.github.piwugu.snowleopard.tools.GroupNames.WORLD -> switch (tool) {
                    case ToolSchemas.WORLD_QUERY -> worldQuery(params, controllerOf);
                    default -> Result.error("world 组无此工具: " + tool);
                };
                case io.github.piwugu.snowleopard.tools.GroupNames.CRAFT -> switch (tool) {
                    case ToolSchemas.CRAFT_RECIPES -> craftRecipes(params);
                    case ToolSchemas.CRAFT -> craft(params);
                    default -> Result.error("craft 组无此工具: " + tool);
                };
                case io.github.piwugu.snowleopard.tools.GroupNames.VISION -> switch (tool) {
                    case ToolSchemas.VISION_SCAN -> visionScan(params);
                    case ToolSchemas.VISION_SHOT -> visionShot();
                    case ToolSchemas.VISION_SHOW -> visionShow(params);
                    default -> Result.error("vision 组无此工具: " + tool);
                };
                case io.github.piwugu.snowleopard.tools.GroupNames.WATCH -> switch (tool) {
                    case ToolSchemas.WATCH_WATCH -> watchStart(params, controllerOf);
                    case ToolSchemas.WATCH_UNWATCH -> watchStop(params);
                    case ToolSchemas.WATCH_LIST -> watchList();
                    case ToolSchemas.WATCH_EVENTS -> watchEvents(params);
                    default -> Result.error("watch 组无此工具: " + tool);
                };
                default -> Result.error("工具组未实现或未知: " + group);
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
        // 选中者保持活动（裁决）：若它曾被**别的模型**的 pick 休眠，这里唤醒——
        // 否则跨模型会互相把对方要绑的实体睡死（NoAi 随存档落盘，M8 多模型真机场景发现）
        ServerLevel lvPick = ctx.level();
        if (lvPick != null && lvPick.getEntity(java.util.UUID.fromString(target)) instanceof LivingEntity chosen) {
            dormancy.wake(chosen);
        }
        // 动态记录范围含绑定实体（裁决 §1.4）——绑定即自动入册，controller=控制模型编号
        watchFeed.watch(target, modelId);
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
        explicitYaw = null;
        explicitPitch = null;
        gaze.reset(0, 0);   // 解绑后不该保留上一条绑定留下的头/瞳角度
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
        // **宽容解析**（2026-10-27 真机实锤）：schema 里参数一律是 string，模型会发 "20"，
        // 严格 instanceof Number 会直接拒 → 模型白耗一轮改成数字再发一次。
        Double yawNum = num(params, "yaw");
        Double pitchNum = num(params, "pitch");
        if (yawNum == null || pitchNum == null) {
            return Result.error("look 需要数值参数 yaw 与 pitch（度）");
        }
        double yaw = yawNum;
        double pitch = pitchNum;
        explicitYaw = yaw;
        explicitPitch = pitch;
        explicitAge = 0;
        tickViewLock();   // 先推一 tick，回执能给出"已经开始转"的实况
        double need = Math.abs(io.github.piwugu.snowleopard.tools.AvatarGaze.wrap(yaw - self.getYRot()));
        long ticks = (long) Math.ceil(need / io.github.piwugu.snowleopard.tools.AvatarGaze.HEAD_SLEW) + 2;
        java.util.Map<String, Object> d = new java.util.LinkedHashMap<>();
        d.put("yaw", yaw);
        d.put("pitch", pitch);
        d.put("bodyYaw", (double) self.getYRot());
        // **必须如实说"逐步转"**：模型若以为一帧到位，紧接着 vision_shot 会拍到旧方向，
        // 然后判定"工具在骗我"（这类假成功最难查）。
        d.put("note", "已设为注视目标；眼先动、头随后、身体超限才转，约 " + ticks + " tick 到位");
        return Result.ok(d);
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
        // 同上：模型常把数字写成字符串
        Double durNum = num(params, "duration_ticks");
        int duration = durNum == null ? 0 : durNum.intValue();
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
        explicitYaw = null;
        explicitPitch = null;
        LivingEntity self = boundLiving(ctx.level());
        if (self != null) {
            resetGaze(self);   // 头必须回正（含同步字段），否则扫描/截图口径会停在旧方向
        }
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
        // 视觉能力（壳侧注入；缺省不出现）。放在这里而不是新开工具：
        // 模型问"我能不能看图"是状态查询，跟"我在往哪看"同一类问题，不该多一个工具名
        String vision = ctx.visionStatus();
        if (vision != null) {
            d.put("vision", vision);
        }
        return Result.ok(d);
    }

    /**
     * 视觉 B 档：视线射线扫描（纯服务端，**任何距离都成立**，不受"客户端只渲染已加载区块"限制）。
     *
     * <p>结果必带 {@code source=raycast} 与 {@code capability}——**不许混称"截图"**。
     * 颜色取原版 MapColor（材质代表色），没有贴图与光影；模型若把它当实拍，
     * 就会把"石头代表色是灰的"说成"我看过那块石头"。
     */
    Result visionScan(Map<String, Object> params) {
        LivingEntity self = requireBound();
        if (self == null) {
            return Result.error("感官未开启或未绑定——视觉需要载体（当前: " + sense.state() + "）");
        }
        if (!(self.level() instanceof ServerLevel level)) {
            return Result.error("绑定实体不在服务端世界，无法射线扫描");
        }
        String cap = ctx.visionStatus();
        return Result.ok(VisionScan.scan(level, self, params, ctx.visionDir(),
                cap == null ? "level=unknown（宿主未接能力上报）" : cap));
    }

    /**
     * C 档：申请一张载体 POV 截图（**异步受理**）。
     *
     * <p>立刻返回编号，画面由后端渲染完成后**自动注入下一轮**——模型不许在这轮等
     * （等就等于把一次 GPU 帧 + 网络往返塞进服务端主线程，会卡 tick）。
     *
     * <p>失败必须**当场**说清楚并指路：没有后端/客户端 → 让模型改用 {@code vision/scan}，
     * 而不是给它一个"稍后应该有"的空头承诺。
     */
    Result visionShot() {
        LivingEntity self = requireBound();
        if (self == null) {
            return Result.error("感官未开启或未绑定——视觉需要载体（当前: " + sense.state() + "）");
        }
        io.github.piwugu.snowleopard.tools.VisionBackend backend = ctx.visionBackend();
        if (backend == null || !backend.available()) {
            String cap = ctx.visionStatus();
            return Result.error("当前没有可用的截图后端（" + (cap == null ? "未知" : cap)
                    + "）——改用 vision/scan 获取视线方向的方块与实体");
        }
        if (backend.inFlight(modelId)) {
            // 不排队：排队=把失败变成稍后执行，而模型会以为两枪都在路上
            return Result.warn("上一张仍在渲染中，本次未受理（不排队）", "等它注入后再申请");
        }
        try {
            net.minecraft.world.phys.Vec3 eye = self.getEyePosition(1.0f);
            String dim = self.level() instanceof ServerLevel sl
                    ? sl.dimension().location().toString() : "";
            String reqId = backend.request(new io.github.piwugu.snowleopard.tools.VisionBackend.Shot(
                    modelId, self.getId(), dim, ctx.visionWidth(), ctx.visionHeight(),
                    eye.x, eye.y, eye.z, self.getYRot(), self.getXRot()));
            Map<String, Object> d = new java.util.LinkedHashMap<>();
            d.put("reqId", reqId);
            d.put("state", "REQUESTED");
            d.put("size", ctx.visionWidth() + "x" + ctx.visionHeight());
            d.put("note", "画面稍后自动注入（本轮内或下一轮）；不要重复申请，也不要在这轮等");
            String cap = ctx.visionStatus();
            if (cap != null) {
                d.put("capability", cap);
            }
            return Result.ok(d);
        } catch (IllegalStateException e) {
            return Result.error(e.getMessage());
        }
    }

    /** 重看已落盘的图（不重渲、不耗 GPU）：把文件重新排队注入。 */
    Result visionShow(Map<String, Object> params) {
        io.github.piwugu.snowleopard.tools.VisionBackend backend = ctx.visionBackend();
        if (backend == null || !backend.available()) {
            return Result.error("当前没有可用的视觉后端——改用 vision/scan");
        }
        java.nio.file.Path dir = ctx.visionDir();
        if (dir == null) {
            return Result.error("没有可读的视觉目录（宿主未注入）");
        }
        String id = params.get("cap_id") == null ? null : String.valueOf(params.get("cap_id")).trim();
        java.nio.file.Path file = io.github.piwugu.snowleopard.tools.VisionFiles.findLatest(dir, id);
        if (file == null) {
            return Result.error("没有找到截图" + (id == null || id.isBlank() ? "" : " #" + id)
                    + "——先用 vision/capture 拍，或 vision/scan detail=image");
        }
        backend.show(modelId, file);
        Map<String, Object> d = new java.util.LinkedHashMap<>();
        d.put("state", "READY");
        d.put("file", file.getFileName().toString());
        // **回执必须如实**（2026-10-27 16:55 真机踩过）：旧文案"已排队，下一轮注入"会让模型
        // 以为图马上来，于是当轮直接收束——结果这轮没有注入，它反过来把自己上一轮**看过并答对**的图
        // 判定为"我编的"。现在把两种可能都说清楚。
        d.put("note", "已排队：宿主会在**下一轮请求前**注入；若这是本轮最后一次工具调用，"
                + "画面会顺延到下一次会话开头。别在这轮等图——要立刻作答请用 vision_scan。");
        String cap = ctx.visionStatus();
        if (cap != null) {
            d.put("capability", cap);
        }
        return Result.ok(d);
    }

    /**
     * 每 tick 朝向推进（原名 tickViewLock；语义已扩成"**所有朝向来源的唯一出口**"）。
     *
     * <p><b>为什么必须只有一个出口</b>：锁定要转身体、移动要转身体、{@code look} 也要转身体。
     * 三处各写一次 {@code setRotation} 就会互相抢写，表现是头在几个方向之间跳——正是用户点名要防的
     * "多点切换抽搐"。现在只有这里写朝向，其余入口只声明"我想要哪个来源"。
     *
     * <p>三级渐进（眼 → 头 → 身体）与限速/最短弧/驻留都在 {@link io.github.piwugu.snowleopard.tools.AvatarGaze}
     * 里，本方法只负责读世界事实（目标是否存在/距离/漂移）与选源。
     */
    public void tickViewLock() {
        ServerLevel level = ctx.level();
        LivingEntity self = level == null ? null : boundLiving(level);
        if (self == null) {
            return;
        }

        // ① 锁定判定（世界事实 → 状态机）；只有"仍锁着"才把 aim 当目标
        boolean lockedNow = false;
        double aimYaw = 0;
        double aimPitch = 0;
        if (lookLock.locked()) {
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
            // 漂移判据比的是"我们**实际写进去**的朝向"（见 applyPose）。若拿 aim 比，
            // 身体渐进期间每 tick 都不到 aim → 下一 tick 直接被判"外力夺控"并解锁（不报错的死法）。
            boolean drifted = FacingMath.deltaDeg(self.getYRot(), lastSetYaw) > LookLockMachine.DRIFT_EPS_DEG
                    || FacingMath.deltaDeg(self.getXRot(), lastSetPitch) > LookLockMachine.DRIFT_EPS_DEG;
            Result r = lookLock.tick(targetPresent, distance, drifted);
            if (r instanceof Result.Ok ok && ok.data() instanceof Map<?, ?> m
                    && Boolean.TRUE.equals(m.get("locked"))) {
                lockedNow = true;
                aimYaw = aim.yaw();
                aimPitch = aim.pitch();
            }
        }

        // ② 目标来源调度：锁定 > 移动收束 > 显式 look > 归零
        boolean moving = moveM.busy();
        double moveYaw = moving ? travelYaw(self) : self.getYRot();
        io.github.piwugu.snowleopard.tools.AvatarGaze.Source want;
        double targetYaw;
        double targetPitch;
        if (lockedNow) {
            want = io.github.piwugu.snowleopard.tools.AvatarGaze.Source.LOCK;
            targetYaw = aimYaw;
            targetPitch = aimPitch;
        } else if (moving) {
            want = io.github.piwugu.snowleopard.tools.AvatarGaze.Source.MOVE;
            targetYaw = moveYaw;
            targetPitch = self.getXRot();
        } else if (explicitYaw != null && explicitAge <= EXPLICIT_TTL_TICKS) {
            want = io.github.piwugu.snowleopard.tools.AvatarGaze.Source.LOOK;
            targetYaw = explicitYaw;
            targetPitch = explicitPitch == null ? self.getXRot() : explicitPitch;
        } else {
            want = io.github.piwugu.snowleopard.tools.AvatarGaze.Source.IDLE;
            targetYaw = self.getYRot();
            targetPitch = self.getXRot();
        }
        if (explicitYaw != null && ++explicitAge > EXPLICIT_TTL_TICKS) {
            explicitYaw = null;     // 过期：不能十分钟后还扭着头看旧方向
            explicitPitch = null;
        }

        // ③ 三级渐进
        io.github.piwugu.snowleopard.tools.AvatarGaze.Pose pose = gaze.tick(want, targetYaw, targetPitch,
                self.getYRot(), self.getXRot(), moving, moveYaw);
        applyPose(self, pose);
    }

    /** 把一 tick 的结果落进实体：身体朝向 + 同步给客户端的头像对/瞳孔。 */
    private void applyPose(LivingEntity self, io.github.piwugu.snowleopard.tools.AvatarGaze.Pose pose) {
        if (Math.abs(io.github.piwugu.snowleopard.tools.AvatarGaze.wrap(pose.bodyYaw() - self.getYRot())) > 1e-4
                || Math.abs(pose.bodyPitch() - self.getXRot()) > 1e-4) {
            self.setYRot((float) pose.bodyYaw());
            self.setXRot((float) pose.bodyPitch());
        }
        lastSetYaw = self.getYRot();
        lastSetPitch = self.getXRot();
        if (self instanceof io.github.piwugu.snowleopard.tools.GazeCarrier car) {
            car.applyGaze((float) pose.headYawRel(), (float) pose.headPitchRel(),
                    (float) pose.pupilX(), (float) pose.pupilY());
        }
    }

    /**
     * 复位视线（解锁/解绑/换绑时调）。
     *
     * <p><b>必须连同步字段一起清</b>（2026-10-27 18:36 真机踩到）：只 reset 内部状态的话，
     * 实体上还挂着上一 tick 的 {@code headYawRel}，于是"已解锁"但头仍偏着——
     * 表现是扫描射线比身体偏 60°、模型说"我看不到刚才那张脸"却看不出原因（R4 的实体版）。
     *
     * <p>观感仍是平滑的：客户端渲染做 rotLerp 插值，这里立即置 0 只是让**工具口径**立刻一致
     * （服务端权威值不能停留在旧方向）。
     */
    private void resetGaze(LivingEntity self) {
        gaze.reset(self.getYRot(), self.getXRot());
        if (self instanceof io.github.piwugu.snowleopard.tools.GazeCarrier car) {
            car.applyGaze(0f, 0f, 0f, 0f);
        }
    }

    /** 行进方向的世界角（GOTO 用目标点，其余用开始时冻结的方向向量）。 */
    private double travelYaw(LivingEntity self) {
        if (moveM.mode() == io.github.piwugu.snowleopard.tools.MoveMachine.Mode.GOTO) {
            double[] t = moveM.target();
            return Math.toDegrees(Math.atan2(-(t[0] - self.getX()), t[2] - self.getZ()));
        }
        double[] dir = moveM.direction();
        return Math.toDegrees(Math.atan2(-dir[0], dir[1]));
    }

    /**
     * 宽容解析数值参数（{@code "20"} / {@code 20} 都收）。
     *
     * <p>为什么需要：函数 schema 的参数一律按 {@code type:string} 下发（见 AiHost.toSchema），
     * 模型据此发字符串数字；严格的 {@code instanceof Number} 会把它当参数错误拒掉，
     * 而模型通常能自己改对重发——白白多一轮，长会话里很贵。
     */
    /** 可选字符串参数（null / 空白 / "null" 一律视为未给）。 */
    private static String optStr(Map<String, Object> params, String key) {
        Object o = params == null ? null : params.get(key);
        if (o == null) {
            return null;
        }
        String s = String.valueOf(o).trim();
        return (s.isEmpty() || "null".equals(s)) ? null : s;
    }

    private static Double num(Map<String, Object> params, String key) {
        Object o = params == null ? null : params.get(key);
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        if (o == null) {
            return null;
        }
        try {
            return Double.parseDouble(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return null;
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
        // 攻击玩家类型目标默认走审批（M7 危险动作权限分级）：两段式 confirm_token（发给模型自身的
        // 确认，非外部审批）；digest 绑定目标+当前血量——两段之间目标被换/血量变了令牌即失效。
        if (victim instanceof net.minecraft.world.entity.player.Player) {
            String action = "interact.attack_player";
            String digest = snowleopard.core.confirm.ConfirmGate.digest(
                    "attack-player", target + ":" + victim.getHealth());
            String token = String.valueOf(params.get("confirm_token"));
            if (token == null || token.isBlank() || "null".equals(token)) {
                var ch = confirmGate.issue(action,
                        "攻击玩家目标 " + target + "（当前血量 " + victim.getHealth()
                                + "）——不可逆；确认后带 confirm_token 再次调用", digest);
                return Result.warn(java.util.Map.of(
                                "pending", action, "confirm_token", ch.token(),
                                "preview", ch.preview(), "target", target),
                        "攻击玩家默认走审批：两段式确认（首段不执行，核销令牌后执行）");
            }
            Result cr = confirmGate.confirm(action, token, digest);
            if (cr instanceof Result.Error) {
                return cr;   // 令牌无效/过期/目标已变——不执行攻击
            }
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
        // getMinecraft 是**不带 GameProfile 参数**的重载：调用点的签名里一旦出现
        // com.mojang.authlib.GameProfile，插件层同样会解析失败（不只是构造那一下）。
        var player = net.neoforged.neoforge.common.util.FakePlayerFactory.getMinecraft(level);
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
        // **不许把自己埋了**（2026-10-27 19:46 真机事故：载体用石头封僵尸猪灵时把自己的格子也封了，
        // 随后窒息死亡 → 死亡即解绑 → 模型变成空壳、载体再也回不来）。
        // 原版玩家放不了这种位置（射线自眼出发 + 有碰撞检查），服务端 setBlock 却是裸调用，
        // 所以这层必须我们自己挡：**承载身体的格子不能放方块**。
        net.minecraft.world.phys.AABB cell = new net.minecraft.world.phys.AABB(pos);
        if (cell.intersects(self.getBoundingBox())) {
            return Result.error("这个位置会压住你自己（" + pos.toShortString()
                    + "）——你在这一格里，先走开或换一格再放");
        }
        for (net.minecraft.server.level.ServerPlayer pl
                : level.getEntitiesOfClass(net.minecraft.server.level.ServerPlayer.class, cell)) {
            // getScoreboardName() 只返回 String，调用点不出现 authlib 类型（同上的层可见性约束）
            return Result.error("这个位置会压住玩家 " + pl.getScoreboardName()
                    + "，已拒绝（埋人不可逆）");
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
            // 到时长执行移除——**带掉落**（用户 2026-10-24 03:29 反馈"破坏方块没有掉落物"；
            // 旧 removeBlock(pos,false) 什么都不掉）。dropResources 走方块 loot 表并带上手上工具
            // → 原木徒手可掉、石头需镐（原版语义一致；想掉石头就让模型给自己配镐）
            var state = level.getBlockState(miningTarget);
            var be = level.getBlockEntity(miningTarget);
            LivingEntity miner = requireBound();   // tick 上下文：绑定实体可能已解绑（null = 无工具掉落）
            net.minecraft.world.level.block.Block.dropResources(state, level, miningTarget, be, miner,
                    miner == null ? net.minecraft.world.item.ItemStack.EMPTY : miner.getMainHandItem());
            level.removeBlock(miningTarget, false);
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
        // 朝向不在这里写：统一由 tickViewLock 的视线状态机驱动（否则两处抢写=头抖）
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

    /**
     * 槽位引用：{@code x,y,z:槽号}（方块容器）、{@code self:mainhand|offhand|head|chest|legs|feet}
     * 或 {@code self:pack:0..8}（载体随身背包，{@code PackCarrier}）。
     */
    private record SlotRef(boolean self, net.minecraft.core.BlockPos pos, int slot,
                           net.minecraft.world.entity.EquipmentSlot equip, int pack) {
    }

    private static SlotRef parseSlot(String ref) {
        if (ref == null || ref.isBlank()) {
            throw new IllegalArgumentException("槽位引用形如 \"x,y,z:3\" 或 \"self:mainhand\"");
        }
        if (ref.startsWith("self:pack:")) {
            int idx = Integer.parseInt(ref.substring("self:pack:".length()).trim());
            if (idx < 0 || idx > 8) {
                throw new IllegalArgumentException("随身背包槽位 0..8: " + idx);
            }
            return new SlotRef(true, null, idx, null, idx);
        }
        if (ref.startsWith("self:")) {
            String e = ref.substring("self:".length()).toLowerCase(java.util.Locale.ROOT);
            return switch (e) {
                case "mainhand" -> new SlotRef(true, null, -1, net.minecraft.world.entity.EquipmentSlot.MAINHAND, -1);
                case "offhand" -> new SlotRef(true, null, -1, net.minecraft.world.entity.EquipmentSlot.OFFHAND, -1);
                case "head" -> new SlotRef(true, null, -1, net.minecraft.world.entity.EquipmentSlot.HEAD, -1);
                case "chest" -> new SlotRef(true, null, -1, net.minecraft.world.entity.EquipmentSlot.CHEST, -1);
                case "legs" -> new SlotRef(true, null, -1, net.minecraft.world.entity.EquipmentSlot.LEGS, -1);
                case "feet" -> new SlotRef(true, null, -1, net.minecraft.world.entity.EquipmentSlot.FEET, -1);
                default -> throw new IllegalArgumentException("未知装备槽: " + e);
            };
        }
        int i = ref.lastIndexOf(':');
        if (i < 0) {
            throw new IllegalArgumentException("容器槽位引用形如 \"x,y,z:3\"");
        }
        return new SlotRef(false, parseBlock(ref.substring(0, i)),
                Integer.parseInt(ref.substring(i + 1).trim()), null, -1);
    }

    private net.minecraft.world.item.ItemStack stackAt(LivingEntity self, ServerLevel level, SlotRef ref) {
        if (ref.pack() >= 0) {
            return packOf(self).getItem(ref.pack());
        }
        return ref.self() ? self.getItemBySlot(ref.equip())
                : containerAt(level, ref.pos()).getItem(ref.slot());
    }

    private void setStackAt(LivingEntity self, ServerLevel level, SlotRef ref,
                            net.minecraft.world.item.ItemStack stack) {
        if (ref.pack() >= 0) {
            packOf(self).setItem(ref.pack(), stack);
        } else if (ref.self()) {
            self.setItemSlot(ref.equip(), stack);
        } else {
            containerAt(level, ref.pos()).setItem(ref.slot(), stack);
        }
    }

    /** 随身背包（载体必须实现 {@link io.github.piwugu.snowleopard.tools.PackCarrier}）。 */
    private static net.minecraft.world.Container packOf(LivingEntity self) {
        if (self instanceof io.github.piwugu.snowleopard.tools.PackCarrier pc) {
            return pc.pack();
        }
        throw new IllegalArgumentException("绑定的实体没有随身背包（只有 snowleopard:avatar 有九格）");
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
        if (self instanceof io.github.piwugu.snowleopard.tools.PackCarrier pc) {
            for (int i = 0; i < pc.pack().getContainerSize(); i++) {
                stacks.add(new java.util.AbstractMap.SimpleEntry<>("self:pack:" + i, pc.pack().getItem(i)));
            }
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
        int want = countParam(params, "count", src.getCount());   // count 宽容解析（2026-10-24 17:52 真机实锤：字符串 "5" 曾被静默整组丢）
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
        int want = countParam(params, "count", src.getCount());   // count 宽容解析（2026-10-24 17:52 真机实锤：整组丢出 53 木头+18 门）
        want = Math.min(want, src.getCount());
        net.minecraft.world.item.ItemStack dropped = src.copy();
        dropped.setCount(want);
        src.shrink(want);
        setStackAt(self, level, from, src);
        self.spawnAtLocation(dropped, 0);
        int after = stackAt(self, level, from).getCount();
        return Result.ok(Map.of("dropped", want, "fromRemaining", after));
    }

    // ── capture / pickup（v1.10.0，2026-10-24 18:40 (UTC+8)：蝙蝠捕获 + 掉落物拾取，
    //    可行性见 framework docs/蝙蝠捕获与物品拾取可行性分析-2026-10-24.md §3）──

    /** 捕获触达半径（格）。 */
    private static final double CAPTURE_REACH = 3.5;
    /** 主动拾取扫描半径上限（触达判定另算 {@link #PICKUP_TOUCH}）。 */
    private static final double PICKUP_SCAN_MAX = 6.0;
    /** 主动拾取触达半径（格）——超出只报 skipped，不隔空取物。 */
    private static final double PICKUP_TOUCH = 2.5;

    /**
     * 捕获活体进随身包（v1=只蝙蝠）。完成判定=实体消失+包里多一件（服务端事实）。
     * <b>包满报错不落地</b>——蝙蝠还在原地，模型换个槽再捕（假成功家族零容忍）。
     */
    Result capture(Map<String, Object> params, java.util.function.Predicate<String> excludeRefs) {
        LivingEntity self = requireBound();
        if (self == null) {
            return Result.error("感官未开启或未绑定——控制类工具禁用（当前: " + sense.state() + "）");
        }
        if (!(self instanceof io.github.piwugu.snowleopard.tools.PackCarrier carrier)) {
            return Result.error("绑定的实体没有随身背包（只有 snowleopard:avatar 可捕获）");
        }
        String target = String.valueOf(params.get("target"));
        if (target == null || target.isBlank() || "null".equals(target)) {
            return Result.error("capture 需要 target（实体编号，query include_entities 得到）");
        }
        if (excludeRefs != null && excludeRefs.test(target)) {
            return Result.error("红线：其他编号（模型）的实体无法捕获——" + target);
        }
        ServerLevel level = ctx.level();
        Entity e = level == null ? null : level.getEntity(java.util.UUID.fromString(target));
        if (!(e instanceof net.minecraft.world.entity.ambient.Bat bat) || !bat.isAlive()) {
            return Result.error("捕获目标必须是活的蝙蝠（目标不存在或类型不符——v1 只收蝙蝠）");
        }
        if (self.distanceTo(bat) > CAPTURE_REACH) {
            return Result.error("目标太远（" + String.format("%.1f", self.distanceTo(bat))
                    + " 格 > " + CAPTURE_REACH + "）——先 goto 走近");
        }
        var batStack = batStack();
        if (batStack.isEmpty()) {
            return Result.error("snowleopard:bat 物品未注册（安装损坏——蝙蝠物品注册在 shell 侧）");
        }
        var rest = insertIntoPack(carrier.pack(), batStack);
        if (!rest.isEmpty()) {
            return Result.error("随身包已满——捕获取消（蝙蝠还在原地，drop 或 move_item 腾槽后再来）");
        }
        faceToward(self, bat.getX(), bat.getY() + 0.2, bat.getZ());
        level.playSound(null, bat.blockPosition(),
                net.minecraft.sounds.SoundEvents.BAT_TAKEOFF,
                net.minecraft.sounds.SoundSource.PLAYERS, 0.7F, 0.8F);
        bat.discard();
        return Result.ok(Map.of("ok", true, "item", BAT_ITEM_ID, "count", 1));
    }

    /** 蝙蝠物品 registry id（权威注册在 shell 的 item/BatItems；这里只认 id，构造期零 MC 触达）。 */
    public static final String BAT_ITEM_ID = "snowleopard:bat";

    /**
     * 运行期按 registry id 取蝙蝠物品（v1.10.1 真机崩溃教训 18:58 (UTC+8)）：
     * v1.10.0 直接引用 shell 侧不可能——依赖方向反了；直接引用 entitytools 自己的注册类又会在
     * mod 构造期（PLUGIN 层无 minecraft reads）IllegalAccessError 崩客户端。
     * 所以注册住 shell、这里运行期查注册表——游戏期 FML 已放行 reads（1.9.x 运行期工具实证）。
     */
    static net.minecraft.world.item.ItemStack batStack() {
        var rl = net.minecraft.resources.ResourceLocation.parse(BAT_ITEM_ID);
        if (!net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(rl)) {
            return net.minecraft.world.item.ItemStack.EMPTY;
        }
        var it = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(rl);
        if (it == null || it == net.minecraft.world.item.Items.AIR) {
            return net.minecraft.world.item.ItemStack.EMPTY;
        }
        return new net.minecraft.world.item.ItemStack(it);
    }

    /** 主动拾取：扫描半径内按距离由近到远收取；触达外/包满如实报 skipped（不隔空取物）。 */
    Result pickup(Map<String, Object> params) {
        LivingEntity self = requireBound();
        if (self == null) {
            return Result.error("感官未开启或未绑定——控制类工具禁用（当前: " + sense.state() + "）");
        }
        if (!(self instanceof io.github.piwugu.snowleopard.tools.PackCarrier carrier)) {
            return Result.error("绑定的实体没有随身背包（只有 snowleopard:avatar 可拾取）");
        }
        ServerLevel level = ctx.level();
        if (level == null) {
            return Result.error("无关卡上下文");
        }
        int radius = Math.min(countParam(params, "radius", 4), (int) PICKUP_SCAN_MAX);
        String q = optQuery(params);
        List<net.minecraft.world.entity.item.ItemEntity> near = level.getEntitiesOfClass(
                net.minecraft.world.entity.item.ItemEntity.class,
                self.getBoundingBox().inflate(radius));
        near.sort(java.util.Comparator.comparingDouble(self::distanceToSqr));   // 近的先收
        List<Map<String, Object>> picked = new ArrayList<>();
        List<Map<String, Object>> skipped = new ArrayList<>();
        for (net.minecraft.world.entity.item.ItemEntity ie : near) {
            net.minecraft.world.item.ItemStack st = ie.getItem();
            if (st.isEmpty()) {
                continue;
            }
            if (q != null && !itemMatches(st, q)) {
                continue;   // 不匹配的直接不入册（picked/skipped 都不提）
            }
            String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.getItem()).toString();
            if (self.distanceTo(ie) > PICKUP_TOUCH) {
                skipped.add(Map.of("item", id, "count", st.getCount(),
                        "reason", "超触达半径(" + PICKUP_TOUCH + "格)——先 goto 走近"));
                continue;
            }
            var rest = insertIntoPack(carrier.pack(), st.copy());
            if (!rest.isEmpty()) {
                skipped.add(Map.of("item", id, "count", st.getCount(), "reason", "包满/放不下"));
                continue;   // 不 break：后面的掉落物还能收
            }
            onItemPickup(self, ie);
            take(self, ie, st.getCount());
            ie.discard();
            picked.add(Map.of("item", id, "count", st.getCount()));
        }
        return Result.ok(Map.of("picked", picked, "skipped", skipped));
    }

    /** 拾取动效桥（self 是 LivingEntity，方法在实体上）。 */
    private static void onItemPickup(LivingEntity self, net.minecraft.world.entity.item.ItemEntity ie) {
        self.onItemPickup(ie);
    }

    /** 拾取计数桥（同上）。 */
    private static void take(LivingEntity self, net.minecraft.world.entity.item.ItemEntity ie, int count) {
        self.take(ie, count);
    }
    private static boolean itemMatches(net.minecraft.world.item.ItemStack st, String query) {
        String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.getItem()).toString();
        String lower = query.toLowerCase();
        return id.toLowerCase().contains(lower) || st.getHoverName().getString().toLowerCase().contains(lower);
    }

    /**
     * 入随身包（返回装不下的剩余；{@code addItem} 只在 {@code SimpleContainer} 上，
     * {@code PackCarrier.pack()} 是 {@code Container} 接口——载体实包一定是 SimpleContainer，
     * 别的实现走逐槽兜底）。
     */
    private static net.minecraft.world.item.ItemStack insertIntoPack(net.minecraft.world.Container pack,
                                                                     net.minecraft.world.item.ItemStack stack) {
        if (pack instanceof net.minecraft.world.SimpleContainer sc) {
            return sc.addItem(stack);
        }
        // 兜底：先叠同物，再找空槽（语义与 SimpleContainer.addItem 一致）
        net.minecraft.world.item.ItemStack rest = stack.copy();
        for (int i = 0; i < pack.getContainerSize() && !rest.isEmpty(); i++) {
            net.minecraft.world.item.ItemStack slot = pack.getItem(i);
            if (!slot.isEmpty() && net.minecraft.world.item.ItemStack.isSameItemSameComponents(slot, rest)) {
                int room = Math.min(slot.getMaxStackSize(), pack.getMaxStackSize()) - slot.getCount();
                if (room > 0) {
                    int move = Math.min(room, rest.getCount());
                    slot.grow(move);
                    rest.shrink(move);
                    pack.setChanged();
                }
            }
        }
        for (int i = 0; i < pack.getContainerSize() && !rest.isEmpty(); i++) {
            if (pack.getItem(i).isEmpty() && pack.canPlaceItem(i, rest)) {
                int move = Math.min(rest.getCount(), Math.min(rest.getMaxStackSize(), pack.getMaxStackSize()));
                pack.setItem(i, rest.split(move));
            }
        }
        return rest;
    }

    /**
     * 自动拾取开关（走原版 {@code Mob.setCanPickUpLoot}——载体覆盖后路由到自身字段，
     * 随原版 NBT {@code "CanPickUpLoot"} 持久化）。过滤词经 {@code PickupCarrier} 存实体 NBT。
     * 原版扫描前置含 mobGriefing 游戏规则（默认开）——关了会"开关亮着但不捡"，如实注明。
     */
    Result pickupAuto(Map<String, Object> params) {
        LivingEntity self = requireBound();
        if (self == null) {
            return Result.error("感官未开启或未绑定——控制类工具禁用（当前: " + sense.state() + "）");
        }
        if (!(self instanceof io.github.piwugu.snowleopard.tools.PickupCarrier carrier)) {
            return Result.error("绑定的实体不支持自动拾取配置（只有 snowleopard:avatar 可以）");
        }
        if (!(self instanceof net.minecraft.world.entity.Mob mob)) {
            return Result.error("绑定实体不是 Mob——无法接原版拾取扫描");
        }
        String mode = String.valueOf(params.get("enabled"));
        if ("on".equalsIgnoreCase(mode)) {
            mob.setCanPickUpLoot(true);
        } else if ("off".equalsIgnoreCase(mode)) {
            mob.setCanPickUpLoot(false);
        } else if (mode != null && !"null".equals(mode) && !"status".equalsIgnoreCase(mode)) {
            return Result.error("enabled 只收 on|off|status: " + mode);
        }
        Object filter = params.get("filter");
        if (filter != null && !"null".equals(String.valueOf(filter))) {
            carrier.setPickupFilter(String.valueOf(filter));
        }
        return Result.ok(Map.of(
                "enabled", mob.canPickUpLoot(),
                "filter", carrier.pickupFilter(),
                "note", "自动拾取跳过自己丢出的掉落物；过滤词只影响自动拾取"));
    }

    /**
     * 区域查询（单一工具，中心|框选）。双护栏：仅已加载区块（note 声明）+ 返回量上限（截断标记）。
     * 含实体时带 controller 归属标注（裁决 §1.4）。
     */
    // ── craft（H4.6，2026-10-24 03:55：配方直合成；裁决=不要求工作台邻接、网格不做）──

    /** 列可用工作台配方（id/输出/材料；keyword 过滤 id 与输出名）。 */
    Result craftRecipes(Map<String, Object> params) {
        ServerLevel level = ctx.level();
        if (level == null) {
            return Result.error("无关卡上下文");
        }
        // **参数名必须与 schema 同形**（2026-10-27 载体在聊天里报的 bug：schema 写 keyword、
        // 实现读 query → 传了关键字仍返回全量 890 条，且不报错）。现在统一叫 query，
        // 同时兼容 keyword：模型在历史里学过旧名，改一次 schema 不等于它会立刻改口。
        String kw = optQuery(params);
        if (kw == null) {
            kw = optStr(params, "keyword");   // 仅兼容历史用法
        }
        Double limitNum = num(params, "limit");
        int limit = limitNum != null && limitNum.intValue() > 0 ? limitNum.intValue() : 20;
        var access = level.registryAccess();
        var all = level.getServer().getRecipeManager()
                .getAllRecipesFor(net.minecraft.world.item.crafting.RecipeType.CRAFTING);
        List<Map<String, Object>> out = new ArrayList<>();
        int matched = 0;
        for (var holder : all) {
            var recipe = holder.value();
            var result = recipe.getResultItem(access);
            if (result == null || result.isEmpty()) {
                continue;
            }
            String id = holder.id().toString();
            String display = result.getHoverName().getString();
            if (kw != null
                    && !id.toLowerCase(java.util.Locale.ROOT).contains(kw.toLowerCase(java.util.Locale.ROOT))
                    && !display.toLowerCase(java.util.Locale.ROOT).contains(kw.toLowerCase(java.util.Locale.ROOT))) {
                continue;
            }
            matched++;
            if (out.size() < limit) {
                List<String> ing = new ArrayList<>();
                for (var x : recipe.getIngredients()) {
                    if (x.isEmpty()) {
                        continue;
                    }
                    var stacks = x.getItems();
                    ing.add(stacks.length == 0 ? "?"
                            : net.minecraft.core.registries.BuiltInRegistries.ITEM
                                    .getKey(stacks[0].getItem()).toString());
                }
                out.add(Map.of("id", id, "output", display,
                        "outputCount", result.getCount(), "ingredients", ing));
            }
        }
        return Result.ok(Map.of("matched", matched, "truncated", matched > out.size(), "recipes", out));
    }

    /** 配方直合成：扣随身背包料 → 产物（含桶/碗退回）回背包。事务性：先校验后扣，失败全退。 */
    Result craft(Map<String, Object> params) {
        LivingEntity self = requireBound();
        if (self == null) {
            return Result.error("感官未开启或未绑定——控制类工具禁用（当前: " + sense.state() + "）");
        }
        if (!(self instanceof io.github.piwugu.snowleopard.tools.PackCarrier pc)) {
            return Result.error("绑定的实体没有随身背包（只有 snowleopard:avatar 可合成）");
        }
        ServerLevel level = ctx.level();
        var access = level.registryAccess();
        String rid = String.valueOf(params.get("recipe_id"));
        // count 宽容解析（v1.9.4 首修；v1.10.0 收口到 countParam 三处共用——drop/move_item
        // 漏修同款缺陷，17:52 真机整组丢出实锤）
        int count = countParam(params, "count", 1);
        if (count < 1 || count > 8) {
            return Result.error("count 1..8: " + count);
        }
        net.minecraft.resources.ResourceLocation rl;
        try {
            rl = net.minecraft.resources.ResourceLocation.parse(rid);
        } catch (RuntimeException e) {
            return Result.error("recipe_id 形如 minecraft:oak_planks: " + rid);
        }
        var holderOpt = level.getServer().getRecipeManager().byKey(rl);
        if (holderOpt.isEmpty() || !(holderOpt.get().value()
                instanceof net.minecraft.world.item.crafting.CraftingRecipe recipe)) {
            return Result.error("配方不存在或不是工作台合成配方: " + rid
                    + "（用 craft_recipes 查）");
        }
        var pack = pc.pack();

        // ① 材料校验（逐单位贪婪匹配背包槽；缺口按原料聚合列出）
        var ingredients = recipe.getIngredients();
        List<net.minecraft.world.item.crafting.Ingredient> units = new ArrayList<>();
        for (var ing : ingredients) {
            if (!ing.isEmpty()) {
                units.add(ing);
            }
        }
        if (units.isEmpty()) {
            return Result.error("该配方无材料需求，疑似异常配方");
        }
        int[] remaining = new int[pack.getContainerSize()];
        for (int i = 0; i < remaining.length; i++) {
            remaining[i] = pack.getItem(i).getCount();
        }
        Map<String, Integer> missing = new java.util.LinkedHashMap<>();
        List<int[]> deductions = new ArrayList<>();   // {槽号, 数量}
        for (var ing : units) {
            for (int c = 0; c < count; c++) {
                boolean found = false;
                for (int i = 0; i < pack.getContainerSize(); i++) {
                    if (remaining[i] > 0 && ing.test(pack.getItem(i))) {
                        remaining[i]--;
                        deductions.add(new int[]{i, 1});
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    var stacks = ing.getItems();
                    String desc = stacks.length == 0 ? "?"
                            : net.minecraft.core.registries.BuiltInRegistries.ITEM
                                    .getKey(stacks[0].getItem()).toString();
                    missing.merge(desc, 1, Integer::sum);
                }
            }
        }
        if (!missing.isEmpty()) {
            return Result.error("材料不足（需要 " + units.size() * count + " 件），缺口: "
                    + missing + "——先补料或减少 count");
        }

        // ② 组装输入并出产物（不再重复 matches——按 id 直取配方）
        int w = recipe instanceof net.minecraft.world.item.crafting.ShapedRecipe sr
                ? sr.getWidth() : Math.max(1, ingredients.size());
        int h = recipe instanceof net.minecraft.world.item.crafting.ShapedRecipe sr2
                ? sr2.getHeight() : 1;
        List<net.minecraft.world.item.ItemStack> cells = new ArrayList<>();
        for (var ing : ingredients) {
            cells.add(ing.isEmpty() || ing.getItems().length == 0
                    ? net.minecraft.world.item.ItemStack.EMPTY : ing.getItems()[0].copy());
        }
        var input = net.minecraft.world.item.crafting.CraftingInput.of(w, h, cells);
        var result = recipe.assemble(input, access);
        if (result.isEmpty()) {
            return Result.error("配方产物为空（数据包配方异常？）");
        }

        // ③ 事务：先扣料（记录扣减），产物/余料回填失败 → 全量退料
        Map<Integer, Integer> deducted = new java.util.LinkedHashMap<>();
        for (int[] d : deductions) {
            int slot = d[0];
            var s = pack.getItem(slot);
            var left = s.copy();
            left.setCount(s.getCount() - 1);
            pack.setItem(slot, left);
            deducted.merge(slot, 1, Integer::sum);
        }
        // 余料按 count 放大（2026-10-24 11:57 修）：1.9.3 之前 getRemainingItems 在循环外只算一次，
        // count=8 的蛋糕只退 3 只桶里的 1 只——余料是"每合成一次产生一份"，必须进 per-count 循环
        List<net.minecraft.world.item.ItemStack> remainders = new ArrayList<>();
        for (var rem : recipe.getRemainingItems(input)) {
            if (!rem.isEmpty()) {
                remainders.add(rem.copy());
            }
        }
        List<net.minecraft.world.item.ItemStack> toPack = new ArrayList<>();
        for (int c = 0; c < count; c++) {
            toPack.add(result.copy());
            for (var rem : remainders) {
                toPack.add(rem.copy());
            }
        }
        for (var st : toPack) {
            if (!addToContainer(pack, st)) {
                // 回滚：产物塞不下——退料（原样长回，物品不消失）
                for (var e : deducted.entrySet()) {
                    var s = pack.getItem(e.getKey());
                    var back = s.copy();
                    back.setCount(s.getCount() + e.getValue());
                    pack.setItem(e.getKey(), back);
                }
                return Result.error("背包放不下产物（需 " + toPack.size() + " 个位置/堆叠余量）"
                        + "——先腾格或减小 count；材料已原样退回");
            }
        }
        int crafted = result.getCount() * count;
        return Result.ok(Map.of("crafted", crafted,
                "output", result.getHoverName().getString(),
                "toPack", "self:pack"));
    }

    /** 向容器塞物品：先并同类堆叠，再进空位；塞不完返回 false（调用方负责回滚）。 */
    private static boolean addToContainer(net.minecraft.world.Container c,
                                          net.minecraft.world.item.ItemStack stack) {
        for (int i = 0; i < c.getContainerSize() && !stack.isEmpty(); i++) {
            var s = c.getItem(i);
            if (!s.isEmpty() && net.minecraft.world.item.ItemStack.isSameItemSameComponents(s, stack)) {
                int room = s.getMaxStackSize() - s.getCount();
                if (room > 0) {
                    int move = Math.min(room, stack.getCount());
                    s.grow(move);
                    stack.shrink(move);
                }
            }
        }
        for (int i = 0; i < c.getContainerSize() && !stack.isEmpty(); i++) {
            if (c.getItem(i).isEmpty()) {
                c.setItem(i, stack.copy());
                stack.setCount(0);
            }
        }
        return stack.isEmpty();
    }

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
            // 掉落物（minecraft:item）不是 LivingEntity——不单独扫就"看不见"。
            // 2026-10-24 03:38 踩过：模型破坏方块后查证"没有掉落物"，实际掉落就在脚下
            for (net.minecraft.world.entity.item.ItemEntity it
                    : level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, area)) {
                totalEntities++;
                if (entities.size() < cap) {
                    var stack = it.getItem();
                    entities.add(Map.of("ref", it.getUUID().toString(),
                            "type", "minecraft:item",
                            "registry", net.minecraft.core.registries.BuiltInRegistries.ITEM
                                    .getKey(stack.getItem()).toString(),
                            "name", stack.getHoverName().getString(),
                            "count", stack.getCount(),
                            "x", it.getX(), "y", it.getY(), "z", it.getZ(),
                            "controller", ""));
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

    // ── watch（动态感知，M6）──

    /**
     * 开始记录（实体目标操作——§1.3 非 BOUND 态显式拒绝）。controller 归属：绑定实体=控制
     * 模型编号，真人/无主=空（裁决 §1.4）——由壳侧 {@code controllerOf} 注入（红线 6 不可伪造）。
     * 查询面（watch_list/watch_events）是感知面，不受此门（§1.4 使用与感知分层）。
     */
    Result watchStart(Map<String, Object> params,
                      java.util.function.Function<String, String> controllerOf) {
        if (sense.state() != SenseState.BOUND) {
            return Result.error("感官未开启或未绑定——watch 实体目标操作在关闭态禁用（显式拒绝，不静默）");
        }
        String target = optQueryTarget(params);
        if (target == null) {
            return Result.error("watch 需要 target（实体编号）");
        }
        String controller = controllerOf == null ? "" : controllerOf.apply(target);
        watchUnsubscribed.remove(target);   // 显式重开监视=恢复自动入册资格
        Result r = watchFeed.watch(target, controller);
        if (r instanceof Result.Ok) {
            return Result.ok(Map.of("watching", watchFeed.list()));
        }
        return r;
    }

    Result watchStop(Map<String, Object> params) {
        if (sense.state() != SenseState.BOUND) {
            return Result.error("感官未开启或未绑定——unwatch 实体目标操作在关闭态禁用（显式拒绝，不静默）");
        }
        String target = optQueryTarget(params);
        if (target == null) {
            return Result.error("unwatch 需要 target（实体编号）");
        }
        watchUnsubscribed.add(target);   // 退订：不再被自动入册加回
        return watchFeed.unwatch(target);
    }

    Result watchList() {
        return Result.ok(Map.of("watching", watchFeed.list()));
    }

    /** 查询动态：target 缺省=全部（混合归属逐一可见）；limit/since_tick 截断。 */
    Result watchEvents(Map<String, Object> params) {
        String target = optQueryTarget(params);
        int limit = optInt(params, "limit", WatchFeed.DEFAULT_QUERY_LIMIT);
        long since = optLong(params, "since_tick", 0);
        List<Map<String, Object>> evs = new ArrayList<>();
        for (WatchFeed.Event e : watchFeed.events(target, limit, since)) {
            evs.add(e.toMap());
        }
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("events", evs);
        out.put("count", evs.size());
        out.put("kinds", "TRAJECTORY|ATTACKED|DIED（枚举写死）");
        return Result.ok(out);
    }

    /**
     * 每 tick 自动记录（壳侧 tick 钩子）：轨迹采样（限频）+ 自动入册未记录的玩家
     * （"自动记录玩家及攻击源动态"——玩家 controller=空）。
     */
    public void tickWatch() {
        ServerLevel level = ctx.level();
        if (level == null) {
            return;
        }
        long tick = level.getGameTime();
        // 自动记录：玩家入册（已在监视/已退订的不重复动）
        for (Player p : level.players()) {
            String ref = p.getUUID().toString();
            if (!watchFeed.isWatching(ref) && !watchUnsubscribed.contains(ref)) {
                watchFeed.watch(ref, "");
            }
        }
        // 轨迹采样（限频：10 tick 一次）
        for (Map<String, Object> w : watchFeed.list()) {
            String ref = String.valueOf(w.get("entityRef"));
            if (!watchFeed.shouldSample(ref, tick)) {
                continue;
            }
            try {
                Entity e = level.getEntity(java.util.UUID.fromString(ref));
                if (e == null) {
                    continue;
                }
                watchFeed.record(WatchFeed.Kind.TRAJECTORY, ref,
                        Map.of("x", e.getX(), "y", e.getY(), "z", e.getZ()), tick);
            } catch (IllegalArgumentException ignored) {
                // 非 UUID 的候选编号不进轨迹采样
            }
        }
    }

    /** 攻击源动态（壳侧受伤事件钩子）：目标/攻击者在监视中才记；攻击者自动入册。 */
    public void onHurt(String targetRef, String attackerRef, double damage,
                       java.util.function.Function<String, String> controllerOf) {
        ServerLevel level = ctx.level();
        long tick = level == null ? 0 : level.getGameTime();
        if (attackerRef != null && !attackerRef.isBlank()
                && !watchFeed.isWatching(attackerRef) && !watchUnsubscribed.contains(attackerRef)) {
            // 攻击源自动入册；controller=归属查询（绑定实体=模型编号，真人=空）
            watchFeed.watch(attackerRef, controllerOf == null ? "" : controllerOf.apply(attackerRef));
        }
        if (targetRef != null && watchFeed.isWatching(targetRef)) {
            Map<String, Object> data = new java.util.LinkedHashMap<>();
            data.put("attacker", attackerRef == null ? "" : attackerRef);
            data.put("damage", damage);
            watchFeed.record(WatchFeed.Kind.ATTACKED, targetRef, data, tick);
        }
    }

    /** 解除自动入册（unwatch 即退订——不再被玩家扫描/攻击源自动加回）。 */
    private final java.util.Set<String> watchUnsubscribed = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    private static String optQueryTarget(Map<String, Object> params) {
        Object t = params.get("target");
        String s = String.valueOf(t);
        return (t == null || s.isBlank() || "null".equals(s)) ? null : s;
    }

    private static int optInt(Map<String, Object> params, String key, int dflt) {
        Object v = params.get(key);
        return v instanceof Number n ? n.intValue() : dflt;
    }

    /** count 宽容解析（三处共用；纯 Java 单点在 {@link CountParams}——v1.10.0 收口）。 */
    static int countParam(Map<String, Object> params, String key, int dflt) {
        return io.github.piwugu.snowleopard.tools.CountParams.parse(params, key, dflt);
    }

    private static long optLong(Map<String, Object> params, String key, long dflt) {
        Object v = params.get(key);
        return v instanceof Number n ? n.longValue() : dflt;
    }

    // ── 死亡流（壳侧事件钩子调用）──

    /** 实体死亡：绑定实体死亡→感官自动关+解绑（裁决）；其他→候选消亡，状态不动。 */
    public Result onEntityDeath(String entityRef) {
        Result r = sense.death(entityRef);
        bindings.dropEntity(entityRef);
        if (entityRef != null && watchFeed.isWatching(entityRef)) {
            ServerLevel level = ctx.level();
            watchFeed.record(WatchFeed.Kind.DIED, entityRef, Map.of(),
                    level == null ? 0 : level.getGameTime());
        }
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
