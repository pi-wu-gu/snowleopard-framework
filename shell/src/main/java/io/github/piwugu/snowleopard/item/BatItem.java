package io.github.piwugu.snowleopard.item;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;

/**
 * 蝙蝠物品（v1.10.0，2026-10-24 18:40 (UTC+8)，可行性见
 * {@code framework docs/蝙蝠捕获与物品拾取可行性分析-2026-10-24.md}）。
 *
 * <p><b>语义（用户裁决）</b>：{@code interact/capture} 工具把活的蝙蝠收进载体随身包得到本物品；
 * 玩家右键<b>地面</b>=放走（在点击面上方生成一只新蝙蝠），右键<b>空气</b>=吃掉（面包数值：
 * 饱食度 5 / 饱和修饰 0.6，见 {@code BatItems} 注册处的 food 属性）。
 *
 * <p><b>吃/放走互斥由原版分发顺序保证</b>：{@code useOn} 返回 SUCCESS/CONSUME 后原版不再调
 * {@code use}——一次右键要么放走要么吃，不需要自写互斥。
 * <b>潜行反转</b>：看着方块其实想吃是常态冲突 → 蹲下+右键方块返回 PASS，落到默认进食流程。
 *
 * <p><b>两条边界</b>（分析 §2.4）：①和平模式放走的蝙蝠会被原版 {@code Bat.checkDespawn}
 * discard（原版行为，非 bug）；②"吃"只对玩家有意义——载体没有 {@code FoodData}。
 */
public class BatItem extends Item {

    /** 放走时沿点击面探出的距离；出生位被占就沿面向外再多找一格。 */
    private static final int SPAWN_PROBE = 2;

    public BatItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        // 潜行反转：蹲下右键方块=想吃（PASS → 落到 Item.use 默认进食）
        if (player != null && player.isShiftKeyDown()) {
            return InteractionResult.PASS;
        }
        Level level = context.getLevel();
        if (!level.isClientSide) {
            BlockPos spawnPos = releasePos(level, context.getClickedPos(), context.getClickedFace());
            if (spawnPos == null) {
                return InteractionResult.FAIL;   // 点击面上方全是实心——无处放走，不吞物品
            }
            Bat bat = EntityType.BAT.create(level);
            if (bat == null) {
                return InteractionResult.FAIL;
            }
            bat.moveTo(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5,
                    level.random.nextFloat() * 360.0F, 0.0F);
            bat.setResting(false);   // Bat 构造器服务端默认 setResting(true)——放走=飞出
            bat.setYHeadRot(bat.getYRot());
            level.addFreshEntity(bat);
            level.playSound(null, spawnPos, SoundEvents.BAT_TAKEOFF, SoundSource.PLAYERS, 0.8F, 1.0F);
            context.getItemInHand().shrink(1);
            if (player != null) {
                player.getCooldowns().addCooldown(this, 10);   // 连点防抖（放走多只时防手滑）
            }
        }
        return InteractionResult.SUCCESS;
    }

    /** 出生位：点击面上方一格；被实体/实心占住则沿面向外再探 1 格。都没有 → null（不吞物品）。 */
    @Nullable
    private static BlockPos releasePos(Level level, BlockPos clicked, Direction face) {
        BlockPos base = clicked.relative(face);
        for (int i = 0; i < SPAWN_PROBE; i++) {
            BlockPos pos = base.above(i);
            if (level.noCollision(new AABB(pos))) {
                return pos;
            }
        }
        return null;
    }

    /** 蝠物品唯一 id（文档/文案引用；勿散落硬编码）。 */
    public static final String REGISTRY_NAME = "snowleopard:bat";

    /** 蝙蝠物品的 ItemStack（capture 入包用；单位=1 只）。 */
    public static ItemStack stackOf() {
        return new ItemStack(BatItems.BAT.get());
    }
}
