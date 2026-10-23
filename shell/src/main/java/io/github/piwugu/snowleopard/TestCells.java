package io.github.piwugu.snowleopard;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * 测试单元房（2026-10-23 用户裁决：测试环境构建<b>封闭空间单元式结构，一个测试一个房间</b>，
 * 以保证测试卫生）。
 *
 * <p>三件事：
 * <ul>
 *   <li><b>清场</b>——建房时把房内残留实体与方块全部重置（世界跨运行持久化，不清场必漏脏）；</li>
 *   <li><b>封壳</b>——黑曜石底/顶/四壁：房内游荡出不去、房外残留进不来；</li>
 *   <li><b>垂直分层</b>——层距 {@link #PITCH}=56 &gt; 2×感知扫描半径(32)，层间互不可见；
 *       同区块柱上下堆叠，实体 tick 语义与地面层完全一致（远区块非 entity-ticking 是踩过的坑）。</li>
 * </ul>
 *
 * <p>基准点 {@code base} = 房内地面中心——各测试沿用既有相对坐标习惯（±18 内）。
 */
final class TestCells {

    /** 内空边长（x/z）。测试相对坐标最大用到 ±17，中心 ±18 覆盖。 */
    static final int INTERIOR = 36;

    /** 内空高（y）：测试最大用到 +6（高差预检），12 充裕。 */
    static final int HEIGHT = 12;

    /** 层距（垂直）：&gt; 2×感知扫描半径(32)——相邻层互不可见，扫描/查询不串房。 */
    static final int PITCH = 56;

    private static final BlockState SHELL = Blocks.OBSIDIAN.defaultBlockState();
    private static final BlockState FILL = Blocks.AIR.defaultBlockState();

    private TestCells() {
    }

    /**
     * 垂直堆叠建 count 个测试房（清场 + 封壳），返回各房基准点（地面中心）。
     *
     * <p><b>高度锚点=世界最低可建高度+4</b>（{@code getMinBuildHeight()+4}），不用高度图——
     * 2026-10-23 踩过"棘轮效应"：房间跨运行残留世界，高度图逐轮抬高（第二轮 groundY=289=首轮塔顶），
     * 新房建到建筑高度以上，setBlock 静默失败、getBlockState 返 void_air，测试全线假失败。
     * 最低可建高度是世界常数——任意轮次锚点恒定。
     */
    static BlockPos[] buildAll(ServerLevel level, int count) {
        BlockPos spawn = level.getSharedSpawnPos();
        int groundY = level.getMinBuildHeight() + 4;
        int topY = groundY + (count - 1) * PITCH + HEIGHT;

        // 超高硬校验：塔必须整体在建筑高度内——出界=环境错，必须炸出来（假成功比失败更坏）
        if (topY > level.getMaxBuildHeight() - 2) {
            throw new IllegalStateException("测试塔超高：顶 " + topY
                    + " 超出世界建筑高度 " + level.getMaxBuildHeight()
                    + "（count=" + count + " 层距=" + PITCH + " 锚点=" + groundY + "）");
        }

        // 塔柱全域清实体：上一轮棘轮残留（塔顶以上的僵尸/假人）一并回收
        AABB column = new AABB(spawn.getX() - INTERIOR / 2 - 2, groundY - 2, spawn.getZ() - INTERIOR / 2 - 2,
                spawn.getX() + INTERIOR / 2 + 2, level.getMaxBuildHeight(), spawn.getZ() + INTERIOR / 2 + 2);
        for (Entity e : level.getEntitiesOfClass(Entity.class, column, e -> true)) {
            e.discard();
        }

        BlockPos[] out = new BlockPos[count];
        for (int i = 0; i < count; i++) {
            out[i] = buildOne(level, spawn, groundY + i * PITCH);
        }

        // 塔顶清扫：旧轮次残留的方块（棘轮塔的房间壳/散块）清回空气
        for (int x = spawn.getX() - INTERIOR / 2 - 1; x <= spawn.getX() + INTERIOR / 2; x++) {
            for (int z = spawn.getZ() - INTERIOR / 2 - 1; z <= spawn.getZ() + INTERIOR / 2; z++) {
                for (int y = topY + 1; y < level.getMaxBuildHeight(); y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    if (!level.getBlockState(p).isAir()) {
                        level.setBlock(p, FILL, 2);
                    }
                }
            }
        }
        return out;
    }

    private static BlockPos buildOne(ServerLevel level, BlockPos spawn, int feetY) {
        int x0 = spawn.getX() - INTERIOR / 2;
        int z0 = spawn.getZ() - INTERIOR / 2;

        // ① 清场：房内（含壳位）残留实体全部丢弃——上一运行的游荡僵尸/假人都在这清
        AABB vol = new AABB(x0 - 2, feetY - 2, z0 - 2,
                x0 + INTERIOR + 2, feetY + HEIGHT + 2, z0 + INTERIOR + 2);
        List<Entity> leftovers = level.getEntitiesOfClass(Entity.class, vol, e -> true);
        for (Entity e : leftovers) {
            e.discard();
        }

        // ② 封壳（底/顶/四壁黑曜石）+ ③ 内空残留方块清为空气
        for (int x = x0 - 1; x <= x0 + INTERIOR; x++) {
            for (int z = z0 - 1; z <= z0 + INTERIOR; z++) {
                for (int y = feetY - 1; y <= feetY + HEIGHT; y++) {
                    boolean shell = y == feetY - 1 || y == feetY + HEIGHT
                            || x == x0 - 1 || x == x0 + INTERIOR
                            || z == z0 - 1 || z == z0 + INTERIOR;
                    BlockPos p = new BlockPos(x, y, z);
                    if (shell) {
                        if (!level.getBlockState(p).equals(SHELL)) {
                            level.setBlock(p, SHELL, 2);   // 2=同步客户端、不刷邻更新（建壳不需要连锁更新）
                        }
                    } else if (!level.getBlockState(p).isAir()) {
                        level.setBlock(p, FILL, 2);        // 只清非空气（残留稀疏，快）
                    }
                }
            }
        }
        return new BlockPos(spawn.getX(), feetY, spawn.getZ());
    }
}
