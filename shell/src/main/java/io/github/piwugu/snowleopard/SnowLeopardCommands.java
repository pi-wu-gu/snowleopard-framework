package io.github.piwugu.snowleopard;

import com.mojang.brigadier.arguments.StringArgumentType;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * 载体运维命令（2026-10-27 19:50 (UTC+8)）。
 *
 * <p>为什么需要：载体死亡会连带解绑（LivingDeathEvent → unbindByModel），而在此之前
 * **生产环境没有任何重新生成载体的入口**——一旦被打死或窒息，模型就永久变成空壳
 * （`entity_status` 返回 null、`avatar_status` 空列表），只能删档重来。
 * 真机事故：载体用石头封僵尸猪灵时把自己也封进去，19:46 窒息死亡并解绑。
 *
 * <p>用法：
 * <pre>
 * /snowleopard avatar spawn              # 用默认模型编号 clienttest
 * /snowleopard avatar spawn &lt;modelId&gt;    # 指定模型编号
 * </pre>
 * 生成后会立刻登记到 {@code shared/avatars.json}（唤醒/排程也随之恢复）。
 * 模型仍需自行 {@code sense open} + {@code pick} 才能控制它——那是"谁在开这台机器"的决定，
 * 不该由运维命令代劳。
 */
@EventBusSubscriber(modid = SnowLeopardMod.MODID)
public final class SnowLeopardCommands {

    /** 默认模型编号（当前仓里只有这一个模型）。 */
    private static final String DEFAULT_MODEL = "clienttest";
    /** 默认显示名（提及唤 wake 用；重名会自动加后缀）。 */
    private static final String DEFAULT_NAME = "小白";

    private SnowLeopardCommands() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("snowleopard")
                .then(Commands.literal("avatar")
                        .then(Commands.literal("spawn")
                                .executes(ctx -> spawn(ctx.getSource(), DEFAULT_MODEL))
                                .then(Commands.argument("model", StringArgumentType.word())
                                        .executes(ctx -> spawn(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "model")))))
                        .then(Commands.literal("list")
                                .executes(ctx -> list(ctx.getSource())))));
    }

    private static int spawn(CommandSourceStack src, String model) {
        ServerPlayer player;
        try {
            player = src.getPlayerOrException();
        } catch (Exception e) {
            src.sendFailure(Component.literal("需要玩家执行（要玩家位置当落点）"));
            return 0;
        }
        ServerLevel level = player.serverLevel();
        BlockPos spot = findSpot(level, player);
        if (spot == null) {
            src.sendFailure(Component.literal("周围找不到落脚点（脚下要有方块、上方两格空气）"));
            return 0;
        }
        AvatarEntity avatar = AvatarEntities.AVATAR.get().create(level);
        if (avatar == null) {
            src.sendFailure(Component.literal("载体实体创建失败（实体类型未注册？）"));
            return 0;
        }
        avatar.moveTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5,
                player.getYRot(), player.getXRot());
        // **必须持久化**：不设的话远处会被 checkDespawn 静默 discard（绑定凭空消失，不报错）
        avatar.setPersistenceRequired();
        avatar.setBoundModel(model);
        level.addFreshEntity(avatar);

        String name = uniqueName(model);
        try {
            AvatarWake.store().bind(avatar.getStringUUID(), model, name);
        } catch (RuntimeException e) {
            avatar.discard();   // 登记失败就别留一个"野载体"在世界上
            src.sendFailure(Component.literal("登记失败：" + e.getMessage()));
            return 0;
        }
        SnowLeopardMod.LOG.info("[雪豹框架][载体] 命令重生 {}（{}）于 {}，已登记 {}",
                name, model, spot.toShortString(), avatar.getStringUUID());
        src.sendSuccess(() -> Component.literal(
                "已生成载体 " + name + "（模型 " + model + "）于 " + spot.toShortString()
                        + "，并登记唤醒名。让它接管：在聊天里点名它，模型会自行 sense open + pick。"), false);
        return 1;
    }

    private static int list(CommandSourceStack src) {
        var entries = AvatarWake.store().all();
        if (entries.isEmpty()) {
            src.sendSuccess(() -> Component.literal("没有已登记的载体"), false);
            return 0;
        }
        StringBuilder b = new StringBuilder("已登记载体 ").append(entries.size()).append(" 个：");
        for (var e : entries) {
            b.append("\n- ").append(e.name()).append(" / 模型 ").append(e.modelId())
                    .append(" / ").append(e.uuid()).append(e.primary() ? "（主）" : "");
        }
        String text = b.toString();
        src.sendSuccess(() -> Component.literal(text), false);
        return entries.size();
    }

    /** 落点：脚下有方块、本体与头部都是空气；先试身前，再试四周（避免生成在墙里）。 */
    private static BlockPos findSpot(ServerLevel level, ServerPlayer player) {
        BlockPos base = player.blockPosition();
        BlockPos[] tries = {
                base.relative(player.getDirection(), 2),
                base.relative(player.getDirection(), 3),
                base.north(2), base.south(2), base.east(2), base.west(2),
                base   // 最后兜底：玩家所在格（若玩家站在实心方块上则本体格是空气）
        };
        for (BlockPos pos : tries) {
            if (!level.getBlockState(pos.below()).isSolid()
                    || !level.getBlockState(pos).isAir()
                    || !level.getBlockState(pos.above()).isAir()) {
                continue;
            }
            // 别生成在别的生物身上
            if (!level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
                    new net.minecraft.world.phys.AABB(pos)).isEmpty()) {
                continue;
            }
            return pos;
        }
        return null;
    }

    /** 名字唯一：提及唤醒靠名字区分谁被点名，重名会让它自己都分不清。 */
    private static String uniqueName(String model) {
        var store = AvatarWake.store();
        String base = DEFAULT_NAME;
        if (!store.nameTaken(base, null)) {
            return base;
        }
        for (int i = 2; i < 100; i++) {
            String candidate = base + i;
            if (!store.nameTaken(candidate, null)) {
                return candidate;
            }
        }
        return base + "-" + model;
    }
}
