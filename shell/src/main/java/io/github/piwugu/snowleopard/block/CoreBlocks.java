package io.github.piwugu.snowleopard.block;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 受限核心装饰方块注册（P0，2026-10-27，可行性见
 * {@code framework docs/受限核心与载体生存属性可行性分析-2026-10-27.md} §二/§四）。
 *
 * <p><b>纯装饰</b>（v3 §二）：无方块实体、无逻辑；潜行右键由 {@code CoreItem} 放置。
 *
 * <p><b>两个属性是分析定案</b>：
 * <ul>
 *   <li>{@code noOcclusion}——视觉约 2.1 格、超出所在格，禁遮挡剔除；</li>
 *   <li>{@code noCollission}（R10）——2.1 格不能挡通路，取"直接无碰撞"分支（纯装饰）。</li>
 * </ul>
 *
 * <p><b>注册类必须住 shell</b>（v1.10.1 真机崩溃教训，同 {@code BatItems}）。
 */
public final class CoreBlocks {

    /** 模组 id 镜像（权威在 shell 的 SnowLeopardMod.MODID）。 */
    public static final String MODID = "snowleopard";

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(Registries.BLOCK, MODID);

    public static final DeferredHolder<Block, ShackledCoreBlock> SHACKLED_CORE = BLOCKS.register(
            "shackled_core",
            () -> new ShackledCoreBlock(BlockBehaviour.Properties.of()
                    .noOcclusion()
                    .noCollission()
                    .strength(0.8F)
                    .sound(SoundType.STONE)));

    private CoreBlocks() {
    }

    /** shell 在 mod 事件总线上调用（与 {@code BatItems.register} 并排，先于物品注册）。 */
    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
    }
}
