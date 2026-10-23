package io.github.piwugu.snowleopard;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

/**
 * 载体实体注册（M13-1，2026-10-24 01:20 (UTC+8)）。
 *
 * <p><b>三处缺一不可</b>（分析 §2.1）：类型注册 + 属性（缺了生成即抛）+ 客户端渲染器（在
 * {@code AvatarClient}，仅 {@code Dist.CLIENT} 加载）。注册必须走 **mod 事件总线**——
 * {@code NeoForge.EVENT_BUS} 收不到 {@code EntityAttributeCreationEvent}。
 *
 * <p><b>{@code MobCategory.MISC} + 不注册 spawn placement</b>：载体绝不能自然生成进野外
 * （看着像野猫、实为空壳）；它只经 {@code /summon snowleopard:avatar} 或代码生成。
 * 尺寸照豹猫 0.6×0.7（碰撞箱与模型一致，改一头必须连 renderer 的 scale 一起改）。
 */
public final class AvatarEntities {

    public static final DeferredRegister<EntityType<?>> TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, SnowLeopardMod.MODID);

    public static final Supplier<EntityType<AvatarEntity>> AVATAR = TYPES.register("avatar",
            () -> EntityType.Builder.of(AvatarEntity::new, MobCategory.MISC)
                    .sized(0.6f, 0.7f)
                    .clientTrackingRange(10)
                    .build("snowleopard_avatar"));

    private AvatarEntities() {
    }

    /** 在 mod 构造期调用（mod 事件总线从 {@code ModContainer#getEventBus()} 拿）。 */
    public static void register(IEventBus modBus) {
        TYPES.register(modBus);
        modBus.addListener(AvatarEntities::onAttributes);
    }

    private static void onAttributes(EntityAttributeCreationEvent event) {
        event.put(AVATAR.get(), AvatarEntity.createAttributes().build());
    }
}
