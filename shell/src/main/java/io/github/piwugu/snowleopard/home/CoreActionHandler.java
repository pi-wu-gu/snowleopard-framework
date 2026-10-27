package io.github.piwugu.snowleopard.home;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import io.github.piwugu.snowleopard.item.CoreItems;
import io.github.piwugu.snowleopard.network.CorePayloads;

/**
 * CoreAction 服务端处理（P1，分析 v3 §3.2 四道校验）。
 *
 * <p>四道校验（缺一不可，同 tick 内完成、失败不留半成品）：
 * <ol>
 *   <li>手持确是核心物品；</li>
 *   <li>{@code expectedStackNonce} 与当前手持物品的 {@code home} 值一致（防界面过期）；</li>
 *   <li>{@code homeId} 合法（CREATE：不存在 + ID 规则；BIND：存在）；</li>
 *   <li>该家是否已有绑定核心（一核心一家，R2）。</li>
 * </ol>
 */
public final class CoreActionHandler {

    private CoreActionHandler() {
    }

    public static void handle(CorePayloads.Action pkt, IPayloadContext ctx) {
        Player player = ctx.player();
        if (player == null || player.level().isClientSide) {
            return;
        }

        String action = pkt.action();
        String homeId = pkt.homeId();
        String expectedNonce = pkt.nonce() == null ? "" : pkt.nonce();

        // ── 校验①：手持确是核心 ──
        ItemStack stack = player.getMainHandItem();
        if (!stack.is(CoreItems.SHACKLED_CORE.get())) {
            sendResult(player, false, "请手持受限核心再操作");
            return;
        }

        // ── 校验②：nonce 与当前手持一致（防界面过期）──
        // nonce = home 字段值（未绑定 = ""）
        String currentNonce = getHomeNbt(stack);
        if (!expectedNonce.equals(currentNonce)) {
            sendResult(player, false, "界面已过期，请重新右键打开");
            return;
        }

        // ── 校验③ + ④ 按分支 ──
        if ("CREATE".equals(action)) {
            handleCreate(player, homeId);
        } else if ("BIND".equals(action)) {
            handleBind(player, homeId, stack);
        } else if ("TELEPORT".equals(action)) {
            handleTeleport(player, homeId);
        } else {
            sendResult(player, false, "未知操作：" + action);
        }
    }

    private static void handleCreate(Player player, String homeId) {
        // 校验③：ID 合法 + 不存在
        if (!CoreHomes.isValidId(homeId)) {
            sendResult(player, false, "ID 不合法（2-32 字符，字母/数字/下划线/连字符）");
            return;
        }
        if (CoreHomes.homeExists(homeId)) {
            sendResult(player, false, "家 " + homeId + " 已存在");
            return;
        }
        // 校验④：新建的家必然无绑定，不需检查

        // 创建（不消耗、不绑定——创建家 ≠ 自动绑定）
        if (CoreHomes.createHome(homeId)) {
            sendResult(player, true, "家 " + homeId + " 创建成功（空家，未绑定）");
        } else {
            sendResult(player, false, "创建失败（磁盘错误）");
        }
    }

    private static void handleBind(Player player, String homeId, ItemStack stack) {
        // 校验③：家存在
        if (!CoreHomes.homeExists(homeId)) {
            sendResult(player, false, "家 " + homeId + " 不存在");
            return;
        }
        // 校验④：一核心一家
        if (CoreHomes.isBound(homeId)) {
            sendResult(player, false, "家 " + homeId + " 已有绑定核心");
            return;
        }

        // 执行绑定：shrink(1) + 写 NBT home=<ID>
        if (!CoreHomes.bindCore(homeId,
                player.getUUID().toString(), player.getName().getString())) {
            sendResult(player, false, "绑定失败（磁盘错误）");
            return;
        }
        stack.shrink(1);
        // 给玩家一枚已绑定的核心（新 stack，带 home NBT）
        ItemStack bound = new ItemStack(CoreItems.SHACKLED_CORE.get());
        setHomeNbt(bound, homeId);
        if (!player.getInventory().add(bound)) {
            player.drop(bound, false);
        }
        sendResult(player, true, "已绑定到家 " + homeId + "（消耗 1 核心）");
    }

    /** P5 付费传送：扣载体经验，叫到玩家身边。 */
    private static void handleTeleport(Player player, String homeId) {
        // 找到 homeId 对应的载体（boundModel == homeId）
        if (!(player.level() instanceof net.minecraft.server.level.ServerLevel serverLevel)) {
            sendResult(player, false, "服务端错误");
            return;
        }
        io.github.piwugu.snowleopard.AvatarEntity avatar = null;
        for (var e : serverLevel.getEntities().getAll()) {
            if (e instanceof io.github.piwugu.snowleopard.AvatarEntity av
                    && homeId.equals(av.boundModel())) {
                avatar = av;
                break;
            }
        }
        if (avatar == null) {
            sendResult(player, false, "家 " + homeId + " 的载体不在线");
            return;
        }
        // 同维度
        if (player.level() != avatar.level()) {
            sendResult(player, false, "跨维度无法传送");
            return;
        }
        // 费用
        double dist = player.distanceTo(avatar);
        int cost = (int) Math.ceil(dist * avatar.getTpCostPerBlock());
        cost = Math.min(cost, avatar.getTpCostMax());
        if (avatar.getVitalsXp() < cost) {
            sendResult(player, false,
                    "载体经验不足（需要 " + cost + "，当前 " + avatar.getVitalsXp() + "）");
            return;
        }
        // 停动作
        io.github.piwugu.snowleopard.EntityToolsRuntime.dispatch(
                homeId, "move", "move_stop", java.util.Map.of());
        // 落点检查
        var target = player.blockPosition();
        boolean blocked = !serverLevel.getBlockState(target).getCollisionShape(serverLevel, target).isEmpty()
                || !serverLevel.getBlockState(target.above()).getCollisionShape(serverLevel, target.above()).isEmpty();
        if (blocked) {
            sendResult(player, false, "落点被方块挡住，未扣费");
            return;
        }
        // 扣费 + 传送
        avatar.setVitalsXp(avatar.getVitalsXp() - cost);
        avatar.teleportTo(player.getX(), player.getY(), player.getZ());
        sendResult(player, true, String.format(
                "%s 已叫到身边（距离 %.1f 格，扣 %d 经验，剩余 %d）",
                homeId, dist, cost, avatar.getVitalsXp()));
    }

    // ---------- NBT 读写（home 字段） ----------

    private static String getHomeNbt(ItemStack stack) {
        var tag = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
        if (tag == null) {
            return "";
        }
        var copy = tag.copyTag();
        return copy.contains("home") ? copy.getString("home") : "";
    }

    private static void setHomeNbt(ItemStack stack, String homeId) {
        var tag = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
        var copy = tag != null ? tag.copyTag() : new net.minecraft.nbt.CompoundTag();
        copy.putString("home", homeId);
        stack.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                net.minecraft.world.item.component.CustomData.of(copy));
    }

    private static void sendResult(Player player, boolean success, String message) {
        if (player instanceof net.minecraft.server.level.ServerPlayer sp) {
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                    sp, new CorePayloads.Result(success, message));
        }
    }
}
