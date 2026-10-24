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
        implements io.github.piwugu.snowleopard.tools.PackCarrier {

    /** 绑定的模型编号（镜像；权威在 AvatarStore）。 */
    private String boundModel = "";

    public AvatarEntity(EntityType<? extends AvatarEntity> type, Level level) {
        super(type, level);
    }

    /** 属性（注册见 {@link AvatarEntities}；缺了生成即抛）。 */
    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.25);
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
    }

    // ── 随身背包（九格，NBT 持久化；经 PackCarrier 接口暴露给物品工具）──

    private final net.minecraft.world.SimpleContainer pack = new net.minecraft.world.SimpleContainer(9);

    @Override
    public net.minecraft.world.Container pack() {
        return pack;
    }

    /** 调试用/文档用：标识这个实体是载体。 */
    @Override
    public String toString() {
        return "AvatarEntity{model=" + boundModel + ", " + super.toString() + "}";
    }
}
