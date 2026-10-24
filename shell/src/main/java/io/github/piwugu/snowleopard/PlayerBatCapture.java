package io.github.piwugu.snowleopard;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * 玩家右键抓蝙蝠（v1.10.2，2026-10-24 19:20 (UTC+8) 用户真机裁决）。
 *
 * <p><b>需求对称</b>：v1.10.0 只给了模型 capture（{@code interact/capture}），用户真机实测
 * 要玩家也能抓——<b>空手右键活蝙蝠 → snowleopard:bat 进玩家背包、蝙蝠消失</b>。
 *
 * <p>规则：
 * <ul>
 *   <li><b>空手</b>（主手 ItemStack 空）才抓——手里有东西右键蝙蝠不打扰原版交互；</li>
 *   <li><b>主手</b>限定——副手拿蝙蝠物品放生时不会误触发；</li>
 *   <li><b>包满不抓</b>（物品不落地、蝙蝠不消失）——假成功零容忍，同 capture 工具；</li>
 *   <li>服务端事件侧才触发（{@code level.isClientSide} 拦截）；
 *       {@link #tryCapture} 本体双端可跑，供自测在客户端直接断言。</li>
 * </ul>
 */
public final class PlayerBatCapture {

    private PlayerBatCapture() {
    }

    /** 服务端事件入口：空手右键活蝙蝠 → 抓（成功则取消事件，防后续交互）。 */
    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer)) {
            return;
        }
        if (event.getHand() != InteractionHand.MAIN_HAND || !event.getItemStack().isEmpty()) {
            return;
        }
        if (!(event.getTarget() instanceof Bat bat)) {
            return;
        }
        if (tryCapture(event.getEntity(), bat)) {
            event.setCanceled(true);
        }
    }

    /**
     * 抓取本体（双端可跑：事件侧限服务端，自测在客户端直接调用断言）。
     *
     * @return true=抓成（蝙蝠已消失、物品已入包）；false=没抓（活着/包满——蝙蝠保留）
     */
    public static boolean tryCapture(Player player, Bat bat) {
        if (!bat.isAlive()) {
            return false;
        }
        if (!player.getInventory().add(new ItemStack(
                io.github.piwugu.snowleopard.item.BatItems.BAT.get()))) {
            return false;   // 包满不抓：蝙蝠还在，物品也不落地
        }
        player.level().playSound(null, bat.blockPosition(),
                SoundEvents.BAT_TAKEOFF, SoundSource.PLAYERS, 0.7F, 1.3F);
        bat.discard();
        return true;
    }
}
