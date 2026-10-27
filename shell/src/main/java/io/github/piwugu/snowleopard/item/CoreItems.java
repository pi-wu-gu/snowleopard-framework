package io.github.piwugu.snowleopard.item;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import io.github.piwugu.snowleopard.block.CoreBlocks;

/**
 * 受限核心物品注册（P0，2026-10-27，分析 v3 §四）——照 {@code BatItems}。
 *
 * <p>裁决①之后核心=家目录的创建/绑定凭证；配方=<b>单木棍</b>（裁决⑦，
 * {@code data/snowleopard/recipe/shackled_core.json}）。
 * BlockItem 指向装饰方块（§二"物品（主）"形态：inventory 里就是那个立体装置）。
 *
 * <p><b>注册类必须住 shell</b>（v1.10.1 真机崩溃教训，同 {@code BatItems}）。
 */
public final class CoreItems {

    /** 模组 id 镜像（权威在 shell 的 SnowLeopardMod.MODID）。 */
    public static final String MODID = "snowleopard";

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(Registries.ITEM, MODID);

    public static final DeferredHolder<Item, CoreItem> SHACKLED_CORE = ITEMS.register("shackled_core",
            () -> new CoreItem(CoreBlocks.SHACKLED_CORE.get(), new Item.Properties()));

    private CoreItems() {
    }

    /** shell 在 mod 事件总线上调用（必须在 {@code CoreBlocks.register} 之后）。 */
    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
    }

    /** 受限核心的 ItemStack（P1 界面确认发放用）。 */
    public static ItemStack stackOf() {
        return new ItemStack(SHACKLED_CORE.get());
    }
}
