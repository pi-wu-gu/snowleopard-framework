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

import net.neoforged.neoforge.network.PacketDistributor;

import io.github.piwugu.snowleopard.home.CoreHomes;
import io.github.piwugu.snowleopard.network.CorePayloads;

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
 *
 * <p><b>P1 更新</b>（2026-10-28）：普通右键 → 发 {@code OpenScreen} 包打开 {@code CoreScreen}。
 * 未绑定核心 → 家管理界面（创建/绑定）；已绑定 → 配置界面占位（P2）。
 * 所有校验在服务端 {@code CoreActionHandler}（四道校验）。
 */
public class CoreItem extends BlockItem {

    private static final Logger LOGGER = LoggerFactory.getLogger("snowleopard/CoreItem");

    /** 潜行但未对准方块面时的引导文案。 */
    private static final Component MSG_AIM_AT_BLOCK =
            Component.literal("§e[受限核心] §f对准方块面潜行放置");

    public CoreItem(Block block, Properties properties) {
        super(block, properties);
    }

    /**
     * 右键<b>实体</b>的出口（P5：绑定核心右键载体 = 经验传输/付费传送）。
     *
     * <p>MC 1.21.1 命中 ENTITY 时 {@code useOn} 永远不会被调用。
     * 分流规则：
     * <ul>
     *   <li>绑定核心 + 普通右键载体 → 经验传输（玩家 XP → 载体 xp 池）</li>
     *   <li>绑定核心 + 潜行右键载体 → 付费传送（扣载体经验，叫到玩家身边）</li>
     *   <li>未绑定核心 + 潜行右键载体 → 放置引导</li>
     *   <li>未绑定核心 + 普通右键载体 → PASS（落 use() → CoreScreen）</li>
     * </ul>
     */
    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player,
                                                  LivingEntity target, InteractionHand hand) {
        if (!(target instanceof io.github.piwugu.snowleopard.AvatarEntity avatar)) {
            return InteractionResult.PASS;
        }
        String home = getHomeNbt(stack);

        // ── 绑定核心 + 载体 → 经验传输 / 付费传送 ──
        if (!home.isEmpty()) {
            if (!home.equals(avatar.boundModel())) {
                if (!player.level().isClientSide) {
                    player.displayClientMessage(Component.literal(
                            "§e[受限核心] §c这枚核心绑定的是 " + home + "，不是 " + avatar.boundModel()), true);
                }
                return InteractionResult.SUCCESS;
            }
            if (player.isShiftKeyDown()) {
                // 潜行+绑定核心+载体 → 打开配置界面（传送按钮在里面）
                if (!player.level().isClientSide) {
                    openCoreScreen(player, stack);
                }
                return InteractionResult.SUCCESS;
            }
            return handleXpTransfer(player, avatar);
        }

        // ── 未绑定（新版核心恒此态）：右键载体也开界面 ──
        // 2026-10-28 语义修正后核心不再是"钥匙"，右键载体与右键方块必须走同一个入口，
        // 否则玩家对着载体右键只会看到挥手（P0 反馈不足的老坑）。
        if (!player.level().isClientSide) {
            openCoreScreen(player, stack);
        }
        return InteractionResult.SUCCESS;
    }

    /** P5 经验传输：玩家 XP → 载体 xp 池（每次 10 点）。 */
    private static InteractionResult handleXpTransfer(Player player,
                                                       io.github.piwugu.snowleopard.AvatarEntity avatar) {
        if (player.level().isClientSide) {
            return InteractionResult.SUCCESS;
        }
        int transfer = 10;
        if (player.totalExperience < transfer) {
            player.displayClientMessage(Component.literal(
                    "§e[受限核心] §c经验不足（需要 " + transfer + "，当前 " + player.totalExperience + "）"), true);
            return InteractionResult.SUCCESS;
        }
        int room = avatar.getVitalsXpMax() - avatar.getVitalsXp();
        int actual = Math.min(transfer, room);
        if (actual <= 0) {
            player.displayClientMessage(Component.literal(
                    "§e[受限核心] §c载体经验已满"), true);
            return InteractionResult.SUCCESS;
        }
        player.giveExperiencePoints(-actual);
        avatar.addXp(actual);
        player.displayClientMessage(Component.literal(String.format(
                "§a[经验传输] 转移 %d 经验 → %s（载体 %d/%d，剩余 %d）",
                actual, avatar.boundModel(),
                avatar.getVitalsXp(), avatar.getVitalsXpMax(),
                player.totalExperience)), true);
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player != null && !player.isShiftKeyDown()) {
            // 普通右键=弹核心界面（P1）
            if (!context.getLevel().isClientSide) {
                openCoreScreen(player, context.getItemInHand());
            }
            return InteractionResult.sidedSuccess(context.getLevel().isClientSide);
        }
        // 潜行右键=放置装饰方块（原版 BlockItem 流程；§二"摆放（次）"形态）
        // **绑定核心禁放置**：绑定核心是"家钥匙"，放出去=丢钥匙
        String home = getHomeNbt(context.getItemInHand());
        if (!home.isEmpty()) {
            if (player != null && !context.getLevel().isClientSide) {
                player.displayClientMessage(Component.literal(
                        "§e[受限核心] §c绑定核心不能放置（它是 " + home + " 的钥匙）"), true);
            }
            return InteractionResult.sidedSuccess(context.getLevel().isClientSide);
        }
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
            // 普通右键=弹核心界面（P1）
            if (!level.isClientSide) {
                openCoreScreen(player, player.getItemInHand(hand));
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

    /** 打开核心界面（服务端发 OpenScreen 包，客户端打开 GUI）。 */
    private static void openCoreScreen(Player player, ItemStack stack) {
        String home = getHomeNbt(stack);
        var homes = CoreHomes.listHomes();
        // 哪些家已经有载体（界面据此显示"传送"还是"生成载体"）
        var withAvatar = new java.util.ArrayList<String>(homes.size());
        for (String h : homes) {
            // **实时**判据（2026-10-28 用户要求）：存在性来自世界里的实体，不是 avatars.json
            if (io.github.piwugu.snowleopard.AvatarWake.hasCarrierNow(h)) {
                withAvatar.add(h);
            }
        }
        if (player instanceof net.minecraft.server.level.ServerPlayer sp) {
            PacketDistributor.sendToPlayer(sp, new CorePayloads.OpenScreen(homes, withAvatar, home));
        }
    }

    /** 读取 home NBT（未绑定返回空串）。 */
    private static String getHomeNbt(ItemStack stack) {
        var tag = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
        if (tag == null) {
            return "";
        }
        var copy = tag.copyTag();
        return copy.contains("home") ? copy.getString("home") : "";
    }

    /** 受限核心物品唯一 id（文档/文案引用；勿散落硬编码）。 */
    public static final String REGISTRY_NAME = "snowleopard:shackled_core";
}
