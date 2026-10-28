package io.github.piwugu.snowleopard.home;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import io.github.piwugu.snowleopard.AvatarEntities;
import io.github.piwugu.snowleopard.AvatarEntity;
import io.github.piwugu.snowleopard.AvatarWake;
import io.github.piwugu.snowleopard.item.CoreItems;
import io.github.piwugu.snowleopard.network.CorePayloads;
import snowleopard.core.result.Result;

/**
 * CoreAction 服务端处理（P1/P5，2026-10-28 语义修正版）。
 *
 * <p><b>绑定（BIND）= 消耗 1 枚核心 + 为该 ID 生成一只载体</b>；该 ID 已有主载体则失败。
 * 旧实现是"把核心绑到一只已存在的实体上、再发一枚带 home 的钥匙"——关系记反了：
 * 身份是模型 ID，实体只是它当下的身体，玩家手里不需要留"钥匙"。
 *
 * <p><b>传送（TELEPORT）</b>：界面选一个 ID，若该 ID 有主载体则把载体叫到玩家身边（按距离扣载体经验），
 * 没有则失败。权限判据是这份家的所有权记录（binding.player），不再是"手里那枚核心"。
 *
 * <p>校验顺序（失败不留半成品）：① 手持确是核心；② nonce 与手持一致（防界面过期）；
 * ③ 动作合法性 + 所有权；④ 具体动作的前置（家存在 / 一 ID 一载体 / 落点与费用）。
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
        // nonce = 手持物品的 home 字段值（新版核心恒为 ""）
        String currentNonce = getHomeNbt(stack);
        if (!expectedNonce.equals(currentNonce)) {
            sendResult(player, false, "界面已过期，请重新右键打开");
            return;
        }

        // ── 校验③：动作合法性 + 所有权 ──
        String ownErr = ownershipIssue(action, homeId, currentNonce,
                CoreHomes.boundPlayer(homeId), player.getUUID().toString());
        if (ownErr != null) {
            sendResult(player, false, ownErr);
            return;
        }

        // ── 校验④ + 执行 ──
        if ("CREATE".equals(action)) {
            handleCreate(player, homeId);
        } else if ("BIND".equals(action)) {
            handleBind(player, homeId, stack);
        } else if ("TELEPORT".equals(action)) {
            handleTeleport(player, homeId);
        } else if ("UNBIND".equals(action)) {
            handleUnbind(player, homeId);
        } else {
            sendResult(player, false, "未知操作：" + action);
        }
    }

    /**
     * 所有权/前置的**纯函数判据**（可被验收直接断言，不依赖 IPayloadContext）。
     *
     * <p>拆出来的原因：这条规则原来藏在 {@link #handle} 里，验收打不到——2026-10-28 的权限缺口
     * 就是这样漏过了几十条断言。规则写在能断言的地方。
     *
     * @param action     CREATE | BIND | UNBIND | TELEPORT
     * @param heldHome   手持核心的 home 字段（新版恒为 ""；非空=旧版"钥匙"核心）
     * @param ownerUuid  该家所有权记录的玩家 UUID（未记录 = null）
     * @param playerUuid 操作者 UUID
     * @return null=通过，否则是拒绝原因
     */
    public static String ownershipIssue(String action, String homeId, String heldHome,
                                        String ownerUuid, String playerUuid) {
        if (homeId == null || homeId.isEmpty()) {
            return null;   // CREATE 不需要 homeId 归属
        }
        String held = heldHome == null ? "" : heldHome;
        if ("BIND".equals(action)) {
            if (!held.isEmpty()) {
                return "这枚核心是旧版『钥匙』（属于 " + held + "），请用未绑定的核心来生成载体";
            }
            return null;   // 一 ID 一载体在 handleBind 里查（要扫真实实体）
        }
        if ("TELEPORT".equals(action) || "UNBIND".equals(action)) {
            if (ownerUuid == null) {
                return "家 " + homeId + " 还没有载体——先在界面里给它生成一只";
            }
            if (!ownerUuid.equals(playerUuid)) {
                return "这不是你的家（不属于你）";
            }
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
        // 创建（不消耗、不绑定——创建家 ≠ 生成载体）
        if (CoreHomes.createHome(homeId)) {
            sendResult(player, true, "家 " + homeId + " 创建成功（空家，还没有载体）");
        } else {
            sendResult(player, false, "创建失败（磁盘错误）");
        }
    }

    /**
     * 绑定 = **消耗 1 枚核心 + 为该 ID 生成载体**（2026-10-28 用户语义修正）。
     *
     * <p>「一 ID 一载体」：已有主载体则失败。失败路径全部回滚——不允许出现
     * "核心没了、载体没生成"或"载体生成了、所有权没记上"的中间态。
     */
    private static void handleBind(Player player, String homeId, ItemStack stack) {
        if (!CoreHomes.homeExists(homeId)) {
            sendResult(player, false, "家 " + homeId + " 不存在");
            return;
        }
        // **判据实时**（2026-10-28 用户要求：载体信息自动更新，不写死在文件里）。
        // 只看"世界里此刻有没有这只载体"；登记表里有记录、实体扫不到 = **孤儿记录**，允许覆盖。
        //
        // 为什么不因孤儿记录拒绝：MC 的 getAll 只返回**已加载**实体，无法可靠区分"载体在远处未加载"
        // 与"载体真的没了"。若按"有记录就拒"，一旦记录成了孤儿，这个 ID 就永远生成不出载体
        // （界面显示无载体 → 只有"生成"按钮 → 点了被记录拦住 → 又没有"释放"入口）。
        // 取舍：宁可允许覆盖（并记 WARN），也不让 ID 死锁。
        if (AvatarWake.hasCarrierNow(homeId)) {
            sendResult(player, false, "家 " + homeId + " 已经有载体了（一个 ID 只能有一个载体）");
            return;
        }
        if (AvatarWake.hasCarrierRecord(homeId)) {
            io.github.piwugu.snowleopard.SnowLeopardMod.LOG.warn(
                    "[受限核心] {} 有孤儿登记记录（实体扫不到），本次生成将覆盖它", homeId);
            // 先清掉同 modelId 的旧条目，否则 bind 之后会留下一条孤儿（指向已不存在的实体）
            AvatarWake.dispatch(homeId, "avatar_unbind", java.util.Map.of());
        }
        // binding 在、载体没了 = 孤儿记录（旧语义遗留 / 载体被杀 / 换世界）：允许重新生成，
        // 但只能由原所有者来（否则谁都能抢别人已经付过核心的 ID）。
        String owner = CoreHomes.boundPlayer(homeId);
        if (owner != null && !owner.equals(player.getUUID().toString())) {
            sendResult(player, false, "家 " + homeId + " 属于其他玩家，不能替它生成载体");
            return;
        }
        if (!(player.level() instanceof ServerLevel level)) {
            sendResult(player, false, "服务端错误");
            return;
        }

        AvatarEntity avatar = AvatarEntities.AVATAR.get().create(level);
        if (avatar == null) {
            sendResult(player, false, "载体生成失败（实体类型未注册？）");
            return;
        }
        // 名字 = ID（ID 唯一 → 天然不撞"禁重名"；名字同时是提及唤醒的依据）
        avatar.setCustomName(Component.literal(homeId));
        avatar.setPersistenceRequired();
        avatar.moveTo(player.getX() + 1.5, player.getY(), player.getZ() + 1.5,
                player.getYRot(), 0f);
        level.addFreshEntity(avatar);

        // 所有权记录（传送/释放的判据）——写不上就回滚，别留空壳
        if (!CoreHomes.bindCore(homeId, player.getUUID().toString(), player.getName().getString())) {
            avatar.discard();
            sendResult(player, false, "绑定失败（所有权记录写不进去）");
            return;
        }
        // 登记主载体（写排程表 + 实体上标 ID + 已知模型登记）
        Result reg = AvatarWake.bindEntityTo(avatar, homeId);
        if (!(reg instanceof Result.Ok)) {
            avatar.discard();
            CoreHomes.unbindCore(homeId);
            String why = reg instanceof Result.Error e ? e.desc() : "未知原因";
            sendResult(player, false, "载体登记失败：" + why);
            return;
        }

        stack.shrink(1);   // 消耗 1 枚核心
        sendResult(player, true, "已为 " + homeId + " 生成载体「" + homeId + "」（消耗 1 核心）");
    }

    /** 传送：界面选 ID → 该 ID 有主载体则叫到身边（按距离扣载体经验），没有则失败。 */
    private static void handleTeleport(Player player, String homeId) {
        if (!(player.level() instanceof ServerLevel serverLevel)) {
            sendResult(player, false, "服务端错误");
            return;
        }
        // 身份=modelId：跨维度也能解析出载体（是否允许跨维度由下面判）
        AvatarEntity avatar = AvatarWake.primaryAvatarOf(homeId);
        if (avatar == null) {
            sendResult(player, false, "家 " + homeId + " 没有载体（先在界面里生成一只）");
            return;
        }
        if (player.level() != avatar.level()) {
            sendResult(player, false, "载体在其它维度，无法传送（先去那个维度）");
            return;
        }
        double dist = player.distanceTo(avatar);
        int cost = (int) Math.ceil(dist * avatar.getTpCostPerBlock());
        cost = Math.min(cost, avatar.getTpCostMax());
        if (avatar.getVitalsXp() < cost) {
            sendResult(player, false,
                    "载体经验不足（需要 " + cost + "，当前 " + avatar.getVitalsXp() + "）");
            return;
        }
        // 停动作（别让"传过来了还在走"）
        io.github.piwugu.snowleopard.EntityToolsRuntime.dispatch(
                homeId, "move", "move_stop", java.util.Map.of());
        // 落点检查：玩家所在格与上方都必须无碰撞
        var target = player.blockPosition();
        boolean blocked = !serverLevel.getBlockState(target)
                .getCollisionShape(serverLevel, target).isEmpty()
                || !serverLevel.getBlockState(target.above())
                .getCollisionShape(serverLevel, target.above()).isEmpty();
        if (blocked) {
            sendResult(player, false, "落点被方块挡住，未扣费");
            return;
        }
        avatar.setVitalsXp(avatar.getVitalsXp() - cost);
        avatar.teleportTo(player.getX(), player.getY(), player.getZ());
        sendResult(player, true, String.format(
                "%s 已叫到身边（距离 %.1f 格，扣 %d 经验，剩余 %d）",
                homeId, dist, cost, avatar.getVitalsXp()));
    }

    /**
     * 释放：移除该 ID 的载体 + 删所有权记录 + 退还 1 枚核心（可重新生成）。
     *
     * <p>必须有这条路径：绑定已消耗核心，若不能释放，模型 ID 就被一个坏载体永久占用。
     */
    private static void handleUnbind(Player player, String homeId) {
        AvatarEntity avatar = AvatarWake.primaryAvatarOf(homeId);
        if (avatar != null) {
            avatar.discard();
        }
        AvatarWake.dispatch(homeId, "avatar_unbind", java.util.Map.of());
        if (!CoreHomes.unbindCore(homeId)) {
            sendResult(player, false, "释放失败（所有权记录删不掉）");
            return;
        }
        ItemStack fresh = new ItemStack(CoreItems.SHACKLED_CORE.get());
        if (!player.getInventory().add(fresh)) {
            player.drop(fresh, false);
        }
        sendResult(player, true, "已释放 " + homeId + " 的载体（退还 1 枚核心）");
    }

    // ---------- NBT 读写（home 字段，仅用于识别旧版"钥匙"核心） ----------

    private static String getHomeNbt(ItemStack stack) {
        var tag = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
        if (tag == null) {
            return "";
        }
        var copy = tag.copyTag();
        return copy.contains("home") ? copy.getString("home") : "";
    }

    private static void sendResult(Player player, boolean success, String message) {
        if (player instanceof net.minecraft.server.level.ServerPlayer sp) {
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                    sp, new CorePayloads.Result(success, message));
        }
    }
}
