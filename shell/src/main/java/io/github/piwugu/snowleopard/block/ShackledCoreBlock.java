package io.github.piwugu.snowleopard.block;

import net.minecraft.world.level.block.Block;

/**
 * 受限核心装饰方块（P0，2026-10-27，分析 v3 §二）——纯装饰、无方块实体、无逻辑。
 *
 * <p>碰撞/遮挡口径在 {@link CoreBlocks} 注册处（无碰撞 + noOcclusion，R10）；
 * 放置语义在 {@code CoreItem}（潜行右键才放置）。刻意不覆写任何行为：
 * 家目录创建/绑定（P1）走界面，不走方块。
 */
public class ShackledCoreBlock extends Block {

    public ShackledCoreBlock(Properties properties) {
        super(properties);
    }
}
