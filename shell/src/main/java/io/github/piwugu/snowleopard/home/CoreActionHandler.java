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

        // ── 校验②b：**核心归属**（服务端权威，不信包里的 homeId）──
        // 2026-10-28 12:20 审查发现：只验 nonce 不验归属 → 手持任意一枚核心即可
        // 发 `UNBIND homeId=别人的家` 解绑他人（`core_binding.json` 被删、自己白拿一枚未绑定核心），
        // `TELEPORT` 同理可把别人的载体强行叫到身边并扣它的经验。
        // 判据很直接：同一系统里 CoreItem.interactLivingEntity 早就有 `home.equals(boundModel())`，
        // **网络入口漏了同一道校验**——两处口径分家就是这类漏洞的温床。
        String ownErr = ownershipError(action, homeId, currentNonce);
        if (ownErr != null) {
            sendResult(player, false, ownErr);
            return;
        }

        // ── 校验③ + ④ 按分支 ──
        if ("CREATE".equals(action)) {
            handleCreate(player, homeId);
        } else if ("BIND".equals(action)) {
            handleBind(player, homeId, stack);
        } else if ("TELEPORT".equals(action)) {
            handleTeleport(player, homeId);
        } else if ("UNBIND".equals(action)) {
            handleUnbind(player, homeId, stack);
        } else {
            sendResult(player, false, "未知操作：" + action);
        }
    }

    /**
     * 归属校验（**纯函数**，可被验收直接断言）。{@code null} = 通过，否则是拒绝原因。
     *
     * <p>抽成纯函数的原因：这条规则原本只活在 {@link #handle} 里，而 handle 要 {@code IPayloadContext}，
     * 验收根本打不到它——2026-10-28 的权限缺口正是这样漏过了 57 条断言。规则写在能断言的地方。
     *
     * @param action    CREATE | BIND | UNBIND | TELEPORT
     * @param heldHome  手持核心的 home 字段（未绑定 = ""）
     */
    public static String ownershipError(String action, String homeId, String heldHome) {
        if (homeId == null || homeId.isEmpty()) {
            return null;   // CREATE 不涉及归属
        }
        String held = heldHome == null ? "" : heldHome;
        if ("BIND".equals(action) && !held.isEmpty()) {
            // 只有**未绑定**的核心才能去绑新家；否则旧家的 core_binding.json 会残留，
            // 变成谁也指不回去的"僵尸绑定"（没有核心能再指向它，UNBIND 又要求手持核心）。
            return "这枚核心已绑定到 " + held + "，请先解绑再用于绑定新家";
        }
        if (("UNBIND".equals(action) || "TELEPORT".equals(action)) && !homeId.equals(held)) {
            return "这枚核心不是 " + homeId + " 的钥匙（它绑定的是 "
                    + (held.isEmpty() ? "无" : held) + "）";
        }
        return null;
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

    /** 解绑：删除 core_binding.json，消耗绑定核心还你一枚未绑定核心。 */
    private static void handleUnbind(Player player, String homeId, ItemStack stack) {
        if (!CoreHomes.isBound(homeId)) {
            sendResult(player, false, "家 " + homeId + " 未绑定");
            return;
        }
        if (!CoreHomes.unbindCore(homeId)) {
            sendResult(player, false, "解绑失败（磁盘错误）");
            return;
        }
        // 消耗绑定核心 → 还一枚未绑定核心
        stack.shrink(1);
        ItemStack fresh = new ItemStack(CoreItems.SHACKLED_CORE.get());
        if (!player.getInventory().add(fresh)) {
            player.drop(fresh, false);
        }
        sendResult(player, true, "已解除 " + homeId + " 的绑定（还你 1 枚未绑定核心）");
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
