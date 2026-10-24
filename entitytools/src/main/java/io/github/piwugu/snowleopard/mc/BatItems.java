package io.github.piwugu.snowleopard.mc;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 蝙蝠物品注册（v1.10.0，2026-10-24 18:40 (UTC+8)）。
 *
 * <p>住 entitytools（工具层）而不是 shell：{@code capture} 工具要 new 本物品入包，
 * 依赖方向是 shell → entitytools，注册类跟着消费方走。shell 侧只做两件事：
 * ①{@code SnowLeopardMod} 里 {@code register(modBus)}；②客户端渲染（{@code AvatarClient} 的 BEWLR）
 * 与模型 JSON（{@code assets/snowleopard/models/item/bat.json}，parent=builtin/entity 才会走自定义渲染器）。
 *
 * <p><b>数值=面包</b>（用户裁决）：nutrition 5 / saturationModifier 0.6——抄
 * {@code Foods.BREAD}，饱了自动不响应（原版 {@code Item.use} 免费获得）。
 * stacksTo(16)：1.21.1 蝙蝠无个体数据（变种是 1.21.2+ 的事），可叠但不上 64——
 * 给未来"存个体状态"留路。
 */
public final class BatItems {

    /** 模组 id 镜像（权威在 shell 的 SnowLeopardMod.MODID；entitytools 不反依赖 shell）。 */
    public static final String MODID = "snowleopard";

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(Registries.ITEM, MODID);

    public static final DeferredHolder<Item, BatItem> BAT = ITEMS.register("bat",
            () -> new BatItem(new Item.Properties()
                    .stacksTo(16)
                    .food(new FoodProperties.Builder()
                            .nutrition(5)
                            .saturationModifier(0.6F)
                            .build())));

    private BatItems() {
    }

    /** shell 在 mod 事件总线上调用（与 {@code AvatarEntities.register} 并排）。 */
    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
    }
}
