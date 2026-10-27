package io.github.piwugu.snowleopard.item;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
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
 * <p><b>P0 反馈铁律</b>（2026-10-28 "右键均无效"教训）：MC 1.21.1 分发链中
 * ENTITY 命中<b>不会</b>调 {@code useOn}（{@code Minecraft.startUseItem} 按 hitResult 分流，
 * ENTITY 走 interactAt→interact→<b>落空后才</b> useItem 兜底）；潜行时 {@code use} 又返回 pass
 * → 三条路径全部静默。修复：{@code interactLivingEntity} 拦截潜行+实体、给行动栏提示并消耗；
 * 所有占位点都带行动栏文字，绝不只靠挥手（挥手太弱会被视为"无效"）。
 *
 * <p><b>P0 边界</b>：界面未实装，普通右键=挥手+行动栏占位（不消耗、不放置）；
 * 界面/四道校验/经验/饥饿都在 P1+，届时在两处占位点换成打开 {@code CoreScreen}。
 */
public class CoreItem extends BlockItem {

    private static final Logger LOGGER = LoggerFactory.getLogger("snowleopard/CoreItem");

    /** P0 占位文案（P1 换 CoreScreen 时同步移除）。 */
    private static final Component MSG_PLACEHOLDER =
            Component.literal("§e[受限核心] §f界面将在 P1 开放");
    /** 潜行但未对准方块面时的引导文案。 */
    private static final Component MSG_AIM_AT_BLOCK =
            Component.literal("§e[受限核心] §f对准方块面潜行放置");

    public CoreItem(Block block, Properties properties) {
        super(block, properties);
    }

    /**
     * 潜行右键<b>实体</b>的唯一出口。
     *
     * <p>MC 1.21.1 命中 ENTITY 时 {@code useOn} 永远不会被调用（{@code startUseItem} 的
     * ENTITY 分支只走 interactAt→interact→useItem），潜行放置在实体上无处落脚。
     * 此处给明确提示并 {@code SUCCESS} 消耗，防止落入 {@code use} 的 sneak-pass 静默分支。
     */
    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player,
                                                  LivingEntity target, InteractionHand hand) {
        if (player.isShiftKeyDown()) {
            if (!player.level().isClientSide) {
                LOGGER.info("[CoreItem] sneak+entity target={} — advising block face",
                        target.getType().getDescriptionId());
                player.displayClientMessage(MSG_AIM_AT_BLOCK, true);
            }
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player != null && !player.isShiftKeyDown()) {
            // 普通右键=弹界面（P1）——P0 占位：挥手+行动栏，绝不放置
            if (!context.getLevel().isClientSide) {
                player.displayClientMessage(MSG_PLACEHOLDER, true);
            }
            return InteractionResult.sidedSuccess(context.getLevel().isClientSide);
        }
        // 潜行右键=放置装饰方块（原版 BlockItem 流程；§二"摆放（次）"形态）
        if (!context.getLevel().isClientSide) {
            LOGGER.info("[CoreItem] sneak useOn pos={} face={}",
                    context.getClickedPos(), context.getClickedFace());
        }
        InteractionResult result = super.useOn(context);
        if (!context.getLevel().isClientSide) {
            LOGGER.info("[CoreItem] place result={}", result);
            if (result == InteractionResult.FAIL && player != null) {
                // BlockItem.place 失败原版零反馈（startUseItem 对 FAIL 直接 return）
                player.displayClientMessage(
                        Component.literal("§e[受限核心] §c放置失败：目标位置不可放置"), true);
            }
        }
        return result;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (!player.isShiftKeyDown()) {
            // 右键空气=弹界面（P1）——P0 占位同 useOn
            if (!level.isClientSide) {
                player.displayClientMessage(MSG_PLACEHOLDER, true);
            }
            return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide);
        }
        // 潜行右键空气：没有方块面可放置，给引导而不是静默 pass
        if (!level.isClientSide) {
            LOGGER.info("[CoreItem] sneak+air — advising block face");
            player.displayClientMessage(MSG_AIM_AT_BLOCK, true);
        }
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide);
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
