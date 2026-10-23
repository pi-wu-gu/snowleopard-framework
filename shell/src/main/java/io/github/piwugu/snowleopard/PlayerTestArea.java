package io.github.piwugu.snowleopard;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;

import snowleopard.core.SnowLeopard;
import snowleopard.core.json.Json;

/**
 * 玩家测试区（2026-10-24 02:10 (UTC+8) 用户规格）：
 * **2×2 区块**（32×32），地板**按区块铺棋盘格**（平滑石 / 石英交替，方便区分区块）、
 * 墙面石英、顶面石英 + **萤石照明网格**（用户补充：记得照明）。
 *
 * <p>只在客户端验收模式（{@code -Dsnowleopard.clienttest=true}）的 {@code ServerStarted} 执行。
 *
 * <p><b>锚点必须存盘，不能每次重算高度图</b>：建完后 {@code WORLD_SURFACE} 会把顶面算进去，
 * 第二次用高度图定锚就会在顶上再盖一层（星期五那边踩过的"棘轮"教训，同构）。
 * 所以坐标记录一次（{@code shared/playtest-area.json}），之后只校验标记块。
 */
public final class PlayerTestArea {

    /** 区块 (8,8) 起点 → x/z 各 128..159（离出生点塔区足够远）。 */
    private static final int BASE_X = 128;
    private static final int BASE_Z = 128;
    /** 2 × 16 = 32。 */
    private static final int SIZE = 32;
    /** 室内净高（空气层数）。 */
    private static final int INNER_TOP = 5;
    /** 顶面照明网格偏移（间隔 7，最远约 5 格 → 光照 ≥10，不会刷怪）。 */
    private static final int[] LIGHT_OFFSETS = {5, 12, 19, 26};

    private static volatile int floorY = Integer.MIN_VALUE;

    private PlayerTestArea() {
    }

    /** 已建测试区的描述（客户端验收播报用；未建返回 null）。 */
    public static String describe() {
        return floorY == Integer.MIN_VALUE ? null
                : "玩家测试区：x " + BASE_X + ".." + (BASE_X + SIZE - 1)
                + " · z " + BASE_Z + ".." + (BASE_Z + SIZE - 1) + " · 地板 y=" + floorY
                + "（棋盘格：平滑石/石英按区块）";
    }

    /** 测试区中心站立点（传送/重生用）；未建返回 null。 */
    public static net.minecraft.core.BlockPos anchor() {
        return floorY == Integer.MIN_VALUE ? null
                : new net.minecraft.core.BlockPos(BASE_X + SIZE / 2, floorY + 1, BASE_Z + SIZE / 2);
    }

    /** 幂等构建：有记录且标记块匹配 → 跳过；否则（首次或世界变了）重建。 */
    public static void ensureBuilt(MinecraftServer server) {
        ServerLevel level = server.overworld();
        if (level == null) {
            return;
        }
        Path record = recordFile();
        if (record != null && Files.isRegularFile(record)) {
            try {
                int y = ((Number) Json.parseObject(Files.readString(record, StandardCharsets.UTF_8))
                        .get("floorY")).intValue();
                if (isBlock(level, BASE_X + 8, y, BASE_Z + 8, Blocks.SMOOTH_STONE)
                        && isBlock(level, BASE_X + 24, y, BASE_Z + 8, Blocks.QUARTZ_BLOCK)) {
                    floorY = y;
                    SnowLeopardMod.LOG.info("[雪豹框架][测试区] 已存在，跳过（地板 y={}）", y);
                    return;
                }
                SnowLeopardMod.LOG.warn("[雪豹框架][测试区] 记录的标记块不在（世界被改过?），重建");
            } catch (Exception e) {
                SnowLeopardMod.LOG.warn("[雪豹框架][测试区] 锚点记录读不出来，重建: {}", e.getMessage());
            }
        }

        int y = surfaceY(level);
        build(level, y);
        floorY = y;
        if (record != null) {
            try {
                Files.createDirectories(record.getParent());
                Files.writeString(record, Json.dump(Map.of("floorY", y)), StandardCharsets.UTF_8);
            } catch (Exception e) {
                SnowLeopardMod.LOG.warn("[雪豹框架][测试区] 锚点记录写盘失败（下次会重建）: {}", e.getMessage());
            }
        }
        SnowLeopardMod.LOG.info("[雪豹框架][测试区] 已就绪 x {}..{} · z {}..{} · 地板 y={} "
                        + "（棋盘格 平滑石/石英 · 石英墙顶 · 萤石照明 · 四边各一门洞）",
                BASE_X, BASE_X + SIZE - 1, BASE_Z, BASE_Z + SIZE - 1, y);
    }

    // ------------------------------------------------------------- 构建

    private static void build(ServerLevel level, int y) {
        for (int dx = 0; dx < SIZE; dx++) {
            for (int dz = 0; dz < SIZE; dz++) {
                int x = BASE_X + dx;
                int z = BASE_Z + dz;
                Block floor = floorMaterial(x, z);
                set(level, x, y, z, floor);            // 地板（按区块棋盘格）
                set(level, x, y - 1, z, floor);        // 双层，防漏光防塌
                boolean edge = dx == 0 || dz == 0 || dx == SIZE - 1 || dz == SIZE - 1;
                for (int h = 1; h <= INNER_TOP; h++) {
                    set(level, x, y + h, z, Blocks.AIR);   // 先清室内（含墙位置）
                }
                if (edge) {
                    for (int h = 1; h <= INNER_TOP + 1; h++) {
                        set(level, x, y + h, z, Blocks.QUARTZ_BLOCK);   // 墙到顶
                    }
                } else {
                    set(level, x, y + INNER_TOP + 1, z, Blocks.QUARTZ_BLOCK);   // 顶
                }
            }
        }
        carveDoors(level, y);
        placeLights(level, y);
    }

    /** 四边正中各一个 2 宽 × 3 高门洞（封闭房间会把自己困住；也顺带透光）。 */
    private static void carveDoors(ServerLevel level, int y) {
        int mid = SIZE / 2;
        for (int h = 1; h <= 3; h++) {
            for (int d = 0; d < 2; d++) {
                int o = mid - 1 + d;
                set(level, BASE_X + o, y + h, BASE_Z, Blocks.AIR);                    // 北
                set(level, BASE_X + o, y + h, BASE_Z + SIZE - 1, Blocks.AIR);         // 南
                set(level, BASE_X, y + h, BASE_Z + o, Blocks.AIR);                    // 西
                set(level, BASE_X + SIZE - 1, y + h, BASE_Z + o, Blocks.AIR);         // 东
            }
        }
    }

    /** 顶面嵌萤石网格（替换部分石英；边缘留白不贴墙）。 */
    private static void placeLights(ServerLevel level, int y) {
        for (int gx : LIGHT_OFFSETS) {
            for (int gz : LIGHT_OFFSETS) {
                set(level, BASE_X + gx, y + INNER_TOP + 1, BASE_Z + gz, Blocks.GLOWSTONE);
            }
        }
    }

    /** 棋盘格：按**区块**奇偶（cx+cz）——平滑石 / 石英，站在界线上就能看出区块边界。 */
    private static Block floorMaterial(int x, int z) {
        return (((long) (x >> 4)) + (z >> 4)) % 2 == 0 ? Blocks.SMOOTH_STONE : Blocks.QUARTZ_BLOCK;
    }

    private static int surfaceY(ServerLevel level) {
        int max = Integer.MIN_VALUE;
        for (int dx = 0; dx < SIZE; dx += 4) {
            for (int dz = 0; dz < SIZE; dz += 4) {
                max = Math.max(max, level.getHeight(Heightmap.Types.WORLD_SURFACE, BASE_X + dx, BASE_Z + dz));
            }
        }
        return max;   // getHeight 返回"最高方块之上"的 y → 正好是地板层
    }

    private static boolean isBlock(ServerLevel level, int x, int y, int z, Block b) {
        return level.getBlockState(new BlockPos(x, y, z)).is(b);
    }

    private static void set(ServerLevel level, int x, int y, int z, Block b) {
        level.setBlock(new BlockPos(x, y, z), b.defaultBlockState(), 3);
    }

    private static Path recordFile() {
        SnowLeopard core = SnowLeopardMod.core();
        return core == null ? null : core.ids().shared().resolve("playtest-area.json");
    }
}
