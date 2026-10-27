package io.github.piwugu.snowleopard.item;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

/**
 * 受限核心物品（P0，2026-10-27，分析 v3 §二）。
 *
 * <p><b>右键语义与原版相反</b>（裁决① + §二）：普通右键=弹核心界面（P1），
 * <b>潜行右键=放置装饰方块</b>。tooltip 必须写明（§二明文），否则玩家第一反应是"右键没放下"。
 *
 * <p><b>目标类型分流</b>（§二）：落在方块/空气走本类 {@code useOn}/{@code use}；
 * 落在<b>载体实体</b>上走 EntityInteract（付费传送，P3）——不能只在 {@code use} 里判潜行。
 * 本类的普通右键占位在 EntityInteract 返回 PASS 之后才会触发，故 P3 的实体分支不会被抢。
 *
 * <p><b>P0 边界</b>：界面未实装，普通右键=挥手占位（不消耗、不放置）；
 * 界面/四道校验/经验/饥饿都在 P1+，届时在两处占位点换成打开 {@code CoreScreen}。
 */
public class CoreItem extends BlockItem {

    public CoreItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player != null && !player.isShiftKeyDown()) {
            // 普通右键=弹界面（P1）——P0 占位：挥手示意"有反应"，绝不放置
            if (context.getLevel().isClientSide) {
                player.swing(context.getHand());
            }
            return InteractionResult.sidedSuccess(context.getLevel().isClientSide);
        }
        // 潜行右键=放置装饰方块（原版 BlockItem 流程；§二"摆放（次）"形态）
        return super.useOn(context);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (!player.isShiftKeyDown()) {
            // 右键空气=弹界面（P1）——P0 占位同 useOn
            if (level.isClientSide) {
                player.swing(hand);
            }
            return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide);
        }
        return InteractionResultHolder.pass(player.getItemInHand(hand));
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext tooltipContext,
                                List<Component> tooltip, TooltipFlag tooltipFlag) {
        super.appendHoverText(stack, tooltipContext, tooltip, tooltipFlag);
        tooltip.add(Component.translatable("item.snowleopard.shackled_core.tooltip.right")
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.snowleopard.shackled_core.tooltip.sneak")
                .withStyle(ChatFormatting.GRAY));
    }

    /** 受限核心物品唯一 id（文档/文案引用；勿散落硬编码）。 */
    public static final String REGISTRY_NAME = "snowleopard:shackled_core";
}
