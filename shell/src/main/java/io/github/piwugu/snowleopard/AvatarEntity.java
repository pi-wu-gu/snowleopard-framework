package io.github.piwugu.snowleopard;

import javax.annotation.Nullable;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;

/**
 * 载体实体（M13-1，2026-10-24 01:20 (UTC+8)，分析见 `docs/实体载体与唤醒源可行性分析-2026-10-23.md`）。
 *
 * <p><b>不是豹猫</b>：只复用豹猫的模型与贴图（客户端 {@code AvatarRenderer}）。
 * {@code OcelotModel<T extends Entity>} 泛型宽松，所以本类继承 {@link PathfinderMob} 即可——
 * 这样驯服/繁殖/猎鸡/喂鱼刷猫那些原版行为**根本不存在**，无需逐个拆除。
 *
 * <p><b>无原版 AI</b>：{@code registerGoals()} 留空（PathfinderMob 自身不注册 goal），
 * 位移由核心写速度。**三条防坑**（分析 §2.2）：
 * <ul>
 *   <li>{@code removeWhenFarAway} 返回 false——否则 {@code checkDespawn} 会远距静默 discard，
 *       绑定凭空消失且不报错；</li>
 *   <li>NBT 里存 {@code SnowModelId} 镜像（排程权威在 {@code AvatarStore}，加载时以表为准）；</li>
 *   <li>死亡 vs 卸载必须分开：死亡走 {@code AvatarWake.onDeath} 解绑；卸载什么都不做。</li>
 * </ul>
 */
public class AvatarEntity extends PathfinderMob
        implements io.github.piwugu.snowleopard.tools.PickupCarrier,
        io.github.piwugu.snowleopard.tools.GazeCarrier {

    /** 绑定的模型编号（镜像；权威在 AvatarStore）。 */
    private String boundModel = "";

    // ── Vitals（P2，分析 v3 §3.3/§3.4）──
    //
    // 裁决④：属性在实体而非家。所有字段 NBT 持久化（跨重启保留）。
    // 默认值：xpMax=1000, xpPerHp=2, hpPerTick=0.5, xpPerSecCap=20, hunger=20, hungerDecaySec=30

    private int xp = 0;
    private int xpMax = 1000;
    private int xpPerHp = 2;           // 2 经验 = 1 血
    private float hpPerTick = 0.5F;    // 回血速率
    private int xpPerSecCap = 20;      // 每秒经验上限（防刷）
    private int hunger = 20;           // 0-20
    private int hungerDecaySec = 30;   // 每 30 秒 -1
    private long lastHungerDecayMs = System.currentTimeMillis();

    // ── 视线表现量（v1.14.0，2026-10-27 18:35 (UTC+8)）──
    //
    // 头/瞳孔是**纯客户端表现**，但"看向哪里"由服务端算（服务端有目标坐标、锁定状态、行进方向）。
    // 所以必须有同步通道：原来本类一个自定义 SynchedEntityData 都没有，服务端算出的角度根本到不了
    // 客户端——这正是"头永远正、瞳孔永远居中"的另一半原因（前一半是 tickViewLock 直接整体转身体）。
    //
    // 只同步**最终值**（头像对 + 瞳孔像素），不同步世界角：客户端不需要知道视线世界角，也不需要复算
    // 映射函数，避免两边算法各写一份后漂移。

    private static final net.minecraft.network.syncher.EntityDataAccessor<Float> DATA_HEAD_YAW =
            net.minecraft.network.syncher.SynchedEntityData.defineId(AvatarEntity.class,
                    net.minecraft.network.syncher.EntityDataSerializers.FLOAT);
    private static final net.minecraft.network.syncher.EntityDataAccessor<Float> DATA_HEAD_PITCH =
            net.minecraft.network.syncher.SynchedEntityData.defineId(AvatarEntity.class,
                    net.minecraft.network.syncher.EntityDataSerializers.FLOAT);
    private static final net.minecraft.network.syncher.EntityDataAccessor<Float> DATA_PUPIL_X =
            net.minecraft.network.syncher.SynchedEntityData.defineId(AvatarEntity.class,
                    net.minecraft.network.syncher.EntityDataSerializers.FLOAT);
    private static final net.minecraft.network.syncher.EntityDataAccessor<Float> DATA_PUPIL_Y =
            net.minecraft.network.syncher.SynchedEntityData.defineId(AvatarEntity.class,
                    net.minecraft.network.syncher.EntityDataSerializers.FLOAT);

    /** 变化阈值：小于它的写入直接丢弃（否则 20Hz 每 tick 发四个包，远处实体更新抖动）。 */
    private static final float GAZE_SYNC_EPS_DEG = 0.5F;
    private static final float GAZE_SYNC_EPS_PX = 0.02F;

    public AvatarEntity(EntityType<? extends AvatarEntity> type, Level level) {
        super(type, level);
    }

    /** 属性（注册见 {@link AvatarEntities}；缺了生成即抛）。 */
    public static AttributeSupplier.Builder createAttributes() {
        // ATTACK_DAMAGE 必须显式加：Mob.createMobAttributes() 不带它（只有怪物族带），而
        // LivingEntity.doHurtTarget 无条件读它——缺了 attack 工具一调用就抛
        // "Can't find attribute minecraft:generic.attack_damage"（2026-10-24 17:35 真机抓到，
        // M6/M11 的 attack 自测绑的全是僵尸猪灵所以一直没暴露）。1.0 = 空手玩家同级。
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.25)
                .add(Attributes.ATTACK_DAMAGE, 1.0)
                .add(Attributes.ATTACK_KNOCKBACK, 0.0);
    }

    @Override
    protected void registerGoals() {
        // 无原版 AI：所有行为由核心控制（用户裁决）。PathfinderMob 自身不注册 goal，
        // 这里显式留空是为了把"没有 AI"变成写死的契约，而不是"恰好没有"。
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;   // 载体不允许远距消失（绑定会凭空没掉）
    }

    @Override
    protected void defineSynchedData(net.minecraft.network.syncher.SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_HEAD_YAW, 0.0F);
        builder.define(DATA_HEAD_PITCH, 0.0F);
        builder.define(DATA_PUPIL_X, 0.0F);
        builder.define(DATA_PUPIL_Y, 0.0F);
    }

    /**
     * 写入视线表现量（服务端每 tick 调；只在变化超过阈值时才真的发包）。
     *
     * <p>阈值不是优化洁癖：{@code SynchedEntityData.set} 每次变更都会进脏表并发给跟踪者，
     * 头每 tick 平滑移动 8°，若每 tick 都发，一个载体就是 4×20=80 包/秒。
     */
    @Override
    public void applyGaze(float headYawRel, float headPitchRel, float pupilX, float pupilY) {
        setIfChanged(DATA_HEAD_YAW, headYawRel, GAZE_SYNC_EPS_DEG);
        setIfChanged(DATA_HEAD_PITCH, headPitchRel, GAZE_SYNC_EPS_DEG);
        setIfChanged(DATA_PUPIL_X, pupilX, GAZE_SYNC_EPS_PX);
        setIfChanged(DATA_PUPIL_Y, pupilY, GAZE_SYNC_EPS_PX);
    }

    private void setIfChanged(net.minecraft.network.syncher.EntityDataAccessor<Float> key,
                              float value, float eps) {
        if (Math.abs(this.entityData.get(key) - value) >= eps) {
            this.entityData.set(key, value);
        }
    }

    @Override
    public float headYawRel() {
        return this.entityData.get(DATA_HEAD_YAW);
    }

    @Override
    public float headPitchRel() {
        return this.entityData.get(DATA_HEAD_PITCH);
    }

    @Override
    public float pupilX() {
        return this.entityData.get(DATA_PUPIL_X);
    }

    @Override
    public float pupilY() {
        return this.entityData.get(DATA_PUPIL_Y);
    }

    public String boundModel() {
        return boundModel;
    }

    public void setBoundModel(String model) {
        this.boundModel = model == null ? "" : model;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        net.minecraft.nbt.CompoundTag pt = new net.minecraft.nbt.CompoundTag();
        for (int i = 0; i < pack.getContainerSize(); i++) {
            net.minecraft.world.item.ItemStack s = pack.getItem(i);
            // **空格子不写**：`ItemStack.save` 对空格直接抛 IllegalStateException——
            // 九格全写会在每次自动存档时炸一次（v1.9.3 修：真机上每次 saveAllChunks 都有一坨
            // "Cannot encode empty ItemStack"，还以为只是噪音）
            if (!s.isEmpty()) {
                pt.put("s" + i, s.save(this.registryAccess()));
            }
        }
        tag.put("Pack", pt);
        if (!boundModel.isEmpty()) {
            tag.putString("SnowModelId", boundModel);
        }
        // 自动拾取开关不需要自写：Mob.addAdditionalSaveData 经本类 canPickUpLoot() 写原版键 "CanPickUpLoot"
        if (!pickupFilter.isEmpty()) {
            tag.putString("PickupFilter", pickupFilter);
        }
        // Vitals（P2）
        tag.putInt("VitalsXp", xp);
        tag.putInt("VitalsXpMax", xpMax);
        tag.putInt("VitalsXpPerHp", xpPerHp);
        tag.putFloat("VitalsHpPerTick", hpPerTick);
        tag.putInt("VitalsXpPerSecCap", xpPerSecCap);
        tag.putInt("VitalsHunger", hunger);
        tag.putInt("VitalsHungerDecaySec", hungerDecaySec);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("Pack")) {
            net.minecraft.nbt.CompoundTag pt = tag.getCompound("Pack");
            for (int i = 0; i < pack.getContainerSize(); i++) {
                pack.setItem(i, net.minecraft.world.item.ItemStack.parseOptional(this.registryAccess(), pt.getCompound("s" + i)));
            }
        }
        boundModel = tag.contains("SnowModelId") ? tag.getString("SnowModelId") : "";
        // 开关同理由原版 "CanPickUpLoot" → setCanPickUpLoot 回来
        pickupFilter = tag.contains("PickupFilter") ? tag.getString("PickupFilter") : "";
        // Vitals（P2）
        xp = tag.getInt("VitalsXp");
        xpMax = tag.contains("VitalsXpMax") ? tag.getInt("VitalsXpMax") : 1000;
        xpPerHp = tag.contains("VitalsXpPerHp") ? tag.getInt("VitalsXpPerHp") : 2;
        hpPerTick = tag.contains("VitalsHpPerTick") ? tag.getFloat("VitalsHpPerTick") : 0.5F;
        xpPerSecCap = tag.contains("VitalsXpPerSecCap") ? tag.getInt("VitalsXpPerSecCap") : 20;
        hunger = tag.contains("VitalsHunger") ? tag.getInt("VitalsHunger") : 20;
        hungerDecaySec = tag.contains("VitalsHungerDecaySec") ? tag.getInt("VitalsHungerDecaySec") : 30;
    }

    // ── 随身背包（九格，NBT 持久化；经 PackCarrier 接口暴露给物品工具）──

    private final net.minecraft.world.SimpleContainer pack = new net.minecraft.world.SimpleContainer(9);

    @Override
    public net.minecraft.world.Container pack() {
        return pack;
    }

    // ── 自动拾取（v1.10.0，2026-10-24 18:50 (UTC+8)，分析 §3.2/§3.3）──

    /**
     * 自动拾取开关（镜像到原版 {@code Mob} 的 {@code canPickUpLoot} 字段——原版 NBT 键
     * {@code "CanPickUpLoot"} 读写都走本对覆盖，<b>持久化白拿</b>）。
     *
     * <p>驱动方式：原版 {@code Mob.aiStep} 自带 "looting" 扫描（{@code canPickUpLoot()==true} 时
     * 扫身边 ItemEntity → {@code wantsToPickUp} → {@code pickUpItem}）。这是 {@code aiStep} 内联
     * 逻辑、<b>不经 goal 系统</b>——不违反"无原版 AI"契约（{@link #registerGoals()} 仍空）。
     * 注意原版扫描前置还有 {@code EventHooks.canEntityGrief}（mobGriefing 游戏规则，默认 true）。
     */
    private boolean autoPickup = false;

    /** 拾取过滤（registry id 子串，忽略大小写；空=全部。NBT {@code "PickupFilter"}）。 */
    private String pickupFilter = "";

    @Override
    public boolean canPickUpLoot() {
        return autoPickup;
    }

    @Override
    public void setCanPickUpLoot(boolean canPickUpLoot) {
        this.autoPickup = canPickUpLoot;
    }

    public String pickupFilter() {
        return pickupFilter;
    }

    public void setPickupFilter(String filter) {
        this.pickupFilter = filter == null ? "" : filter.trim();
    }

    @Override
    public boolean wantsToPickUp(net.minecraft.world.item.ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        String filter = pickupFilter;
        if (filter.isEmpty()) {
            return true;   // 默认拾取一切（模型开了开关=明确授权；自丢反馈环在 pickUpItem 里挡）
        }
        String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        return id.toLowerCase().contains(filter.toLowerCase());
    }

    /**
     * 入九格包（<b>必须覆盖</b>：原版默认按装备槽 equip——会把物品穿到手上/身上，背包永远是空的）。
     *
     * <p><b>自丢反馈环判据（分析 §3.3，真机 17:52 场景）</b>：{@code drop} 工具丢出的掉落物
     * {@code thrower==自己}（{@link #spawnAtLocation} 覆盖里打的标记）→ 自动拾取跳过，
     * 否则 0.5 秒后被自己捡回——模型永远给不了玩家东西，且不报错。
     *
     * <p><b>只整件收</b>：装不下就整件不收（留下实体原样），不做拆半件——部分收取会让
     * "包里多 3 个、地上剩 2 个"这类中间态难解释；模型可用 {@code move_item} 精细分。
     */
    @Override
    protected void pickUpItem(net.minecraft.world.entity.item.ItemEntity itemEntity) {
        var thrower = itemEntity.getOwner();
        if (thrower == this) {
            return;   // 自己刚丢出去的：自动拾取不回头捡（主动 pickup 工具不受此限）
        }
        net.minecraft.world.item.ItemStack stack = itemEntity.getItem();
        if (stack.isEmpty()) {
            return;
        }
        var rest = pack.addItem(stack.copy());
        if (!rest.isEmpty()) {
            return;   // 包满/放不下：整件不收，实体留在原地（模型查 self_items 可见没收到）
        }
        onItemPickup(itemEntity);
        take(itemEntity, stack.getCount());
        itemEntity.discard();
    }

    /**
     * 掉落打标记：本实体 {@code spawnAtLocation} 出的掉落物 {@code setThrower(this)}——
     * {@code pickUpItem} 据此挡"自己丢自己捡"。原版 NBT 持久化 {@code "Thrower"}，跨存档有效。
     */
    @Override
    public net.minecraft.world.entity.item.ItemEntity spawnAtLocation(net.minecraft.world.item.ItemStack stack, float yOffset) {
        net.minecraft.world.entity.item.ItemEntity itemEntity = super.spawnAtLocation(stack, yOffset);
        if (itemEntity != null) {
            itemEntity.setThrower(this);
        }
        return itemEntity;
    }

    // ── Vitals getter/setter（P2）──

    public int getVitalsXp() { return xp; }
    public void setVitalsXp(int v) { this.xp = Math.max(0, Math.min(v, xpMax)); }
    public int getVitalsXpMax() { return xpMax; }
    public void setVitalsXpMax(int v) { this.xpMax = Math.max(1, v); }
    public int getVitalsXpPerHp() { return xpPerHp; }
    public void setVitalsXpPerHp(int v) { this.xpPerHp = Math.max(1, v); }
    public float getVitalsHpPerTick() { return hpPerTick; }
    public void setVitalsHpPerTick(float v) { this.hpPerTick = Math.max(0.01F, v); }
    public int getVitalsXpPerSecCap() { return xpPerSecCap; }
    public void setVitalsXpPerSecCap(int v) { this.xpPerSecCap = Math.max(1, v); }
    public int getVitalsHunger() { return hunger; }
    public void setVitalsHunger(int v) { this.hunger = Math.max(0, Math.min(v, 20)); }
    public int getVitalsHungerDecaySec() { return hungerDecaySec; }
    public void setVitalsHungerDecaySec(int v) { this.hungerDecaySec = Math.max(1, v); }

    /** 经验充值（来源：击杀/经验瓶/测试充值）。返回实际增加量。 */
    public int addXp(int amount) {
        int before = xp;
        setVitalsXp(xp + amount);
        return xp - before;
    }

    /** 消耗经验回血。返回实际回血量（hunger==0 时禁用；经验不足时按剩余算）。 */
    public float healByXp(float requestedHp) {
        if (hunger <= 0) {
            return 0;   // 饥饿归零：禁用经验回血（裁决⑤）
        }
        float maxHp = getMaxHealth();
        float currentHp = getHealth();
        float missing = maxHp - currentHp;
        float want = Math.min(requestedHp, missing);
        int cost = (int) Math.ceil(want * xpPerHp);
        cost = Math.min(cost, xp);
        if (cost <= 0) {
            return 0;
        }
        float healed = cost / (float) xpPerHp;
        xp -= cost;
        setHealth(currentHp + healed);
        return healed;
    }

    /** 进食：按 nutrition 回饥饿。返回实际回的饥饿值。 */
    public int eatFood(int nutrition) {
        int before = hunger;
        setVitalsHunger(hunger + nutrition);
        return hunger - before;
    }

    /**
     * Hunger 递减（由 AvatarWake 的 serverTick 调用）。
     * 缺省每 30 秒 -1；hunger==0 时移速 ×0.85（裁决⑤"连带"）。
     */
    public void tickVitals() {
        long now = System.currentTimeMillis();
        if (now - lastHungerDecayMs >= hungerDecaySec * 1000L) {
            lastHungerDecayMs = now;
            if (hunger > 0) {
                setVitalsHunger(hunger - 1);
            }
        }
        // hunger 影响移速：hunger==0 → ×0.85（裁决⑤）
        var speedAttr = getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
        if (speedAttr != null) {
            double base = 0.25;
            double target = hunger <= 0 ? base * 0.85 : base;
            if (Math.abs(speedAttr.getValue() - target) > 0.001) {
                speedAttr.setBaseValue(target);
            }
        }
    }

    /** 调试用/文档用：标识这个实体是载体。 */
    @Override
    public String toString() {
        return "AvatarEntity{model=" + boundModel + ", " + super.toString() + "}";
    }
}
