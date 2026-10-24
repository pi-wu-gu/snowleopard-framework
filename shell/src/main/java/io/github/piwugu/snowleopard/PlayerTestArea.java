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

import snowleopard.core.SnowLeopard;
import snowleopard.core.json.Json;

/**
 * 玩家测试区（2026-10-24 02:10 (UTC+8) 用户规格）：
 * **2×2 区块**（32×32），地板**按区块铺棋盘格**（平滑石 / 石英交替，方便区分区块）、
 * 墙面石英、顶面石英 + **萤石照明网格**（用户补充：记得照明）。
 *
 * <p>只在客户端验收模式（{@code -Dsnowleopard.clienttest=true}）的 {@code ServerStarted} 执行。
 *
 * <p><b>锚点是世界常数，不是高度图</b>（2026-10-24 05:30 (UTC+8) 改）：{@code WORLD_SURFACE}
 * 会把本区自己的顶面算进去，第二次用它定锚就在顶上再盖一层——**棘轮**（星期五那边踩过的同构教训）。
 * 实测：04:27 建在 y=-64、04:32 叠到 y=-57，旧区连同"底下没基岩"的缺陷被埋在下面，
 * 模型的破坏测试里掉落物直接穿到 y=-65.6 掉进虚空。
 * 现在锚点= {@code getMinBuildHeight() + FLOOR_RISE}（常数），重建即原位重建；
 * 并且**铺之前先清场**（不清则新楼盖在旧楼顶上）。
 * 记录（{@code shared/playtest-area.json}）只用来校验"这一层还在不在"，**不再决定建在哪**。
 */
public final class PlayerTestArea {

    /** 区块 (8,8) 起点 → x/z 各 128..159（离出生点塔区足够远）。 */
    private static final int BASE_X = 128;
    private static final int BASE_Z = 128;
    /** 2 × 16 = 32。 */
    private static final int SIZE = 32;
    /** 室内净高（空气层数）。 */
    private static final int INNER_TOP = 5;
    /**
     * 地板相对世界**最低可建层**的抬升（{@code floorY = getMinBuildHeight() + FLOOR_RISE}）。
     *
     * <p>锚点是**世界常数，不是高度图**（2026-10-24 05:30 (UTC+8) 改）。旧做法取
     * {@code WORLD_SURFACE} 的高度，而那一层的最高方块正是上一轮自己盖的屋顶 →
     * 每次重建往上叠（实测 04:27 建在 y=-64、04:32 叠到 y=-57），旧结构被埋在下面，
     * 新加的基岩永远垫不到模型脚下那一层。抬 4 层：地板与超平坦地表齐平，
     * 底下正好留 2 层给基岩（min+2/min+3 之外还有原世界基岩，绝不会是虚空）。
     */
    private static final int FLOOR_RISE = 4;
    /** 垫基岩的层数（往下铺，见 {@link #build}）。 */
    private static final int BEDROCK_LAYERS = 2;
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

    /** 当前地板层 y（未建返回 {@code Integer.MIN_VALUE}）——破坏/掉落断言的基准。 */
    public static int floorLevel() {
        return floorY;
    }

    /** 垫基岩的层数（往下铺；验收按它逐层核对）。 */
    public static int bedrockLayers() {
        return BEDROCK_LAYERS;
    }

    /** 测试区中心站立点（传送/重生用）；未建返回 null。 */
    public static net.minecraft.core.BlockPos anchor() {
        return floorY == Integer.MIN_VALUE ? null
                : new net.minecraft.core.BlockPos(BASE_X + SIZE / 2, floorY + 1, BASE_Z + SIZE / 2);
    }

    /** 锚点（世界常数）：地板层 y。 */
    private static int anchorY(ServerLevel level) {
        return level.getMinBuildHeight() + FLOOR_RISE;
    }

    /**
     * 幂等构建：记录存在 + 记录的 y 就是当前锚点 + 标记块（只验**已加载**区块）都对 → 跳过；
     * 否则清场后在**固定锚点**重建（不重算高度图，见 {@link #FLOOR_RISE}）。
     */
    public static void ensureBuilt(MinecraftServer server) {
        ServerLevel level = server.overworld();
        if (level == null) {
            return;
        }
        int y = anchorY(level);
        Path record = recordFile();
        if (record != null && Files.isRegularFile(record)) {
            try {
                int recorded = ((Number) Json.parseObject(Files.readString(record, StandardCharsets.UTF_8))
                        .get("floorY")).intValue();
                if (recorded == y && markersOk(level, y)) {
                    floorY = y;
                    SnowLeopardMod.LOG.info("[雪豹框架][测试区] 已存在，跳过（地板 y={}）", y);
                    return;
                }
                SnowLeopardMod.LOG.warn("[雪豹框架][测试区] 与记录不符（记录 y={} / 锚点 y={}，或标记块不在），"
                        + "在固定锚点重建", recorded, y);
            } catch (Exception e) {
                SnowLeopardMod.LOG.warn("[雪豹框架][测试区] 锚点记录读不出来，在固定锚点重建: {}", e.getMessage());
            }
        }

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

    /**
     * 标记块是否都在（三个标记：地板平滑石 / 地板石英 / 地板下**基岩**）。
     *
     * <p><b>只验已加载的区块</b>：未加载区块 {@code getBlockState} 返回空气且不报错，
     * 据它判"被人改过"会每次开服都重建（这一条正是旧版棘轮的帮凶）。
     */
    private static boolean markersOk(ServerLevel level, int y) {
        return markerOk(level, BASE_X + 8, y, BASE_Z + 8, Blocks.SMOOTH_STONE)
                && markerOk(level, BASE_X + 24, y, BASE_Z + 8, Blocks.QUARTZ_BLOCK)
                && markerOk(level, BASE_X + 12, y - 1, BASE_Z + 12, Blocks.BEDROCK);
    }

    private static boolean markerOk(ServerLevel level, int x, int y, int z, Block expect) {
        if (!level.hasChunk(x >> 4, z >> 4)) {
            return true;   // 没加载 = 没法证伪，信记录
        }
        return isBlock(level, x, y, z, expect);
    }

    // ------------------------------------------------------------- 构建

    private static void build(ServerLevel level, int y) {
        // **先清场，再铺**（2026-10-24 05:30 (UTC+8)）：旧结构若不先清掉，新测试区会直接盖在旧的
        // 屋顶上（棘轮），旧的那一层连同"底下没基岩"的缺陷一起被埋起来，永远修不到。
        // 清的波段要盖住"历史上叠过的那一层"，所以上界留到屋顶之上 4 层。
        for (int cy = level.getMinBuildHeight(); cy <= y + INNER_TOP + 4; cy++) {
            for (int dx = 0; dx < SIZE; dx++) {
                for (int dz = 0; dz < SIZE; dz++) {
                    set(level, BASE_X + dx, cy, BASE_Z + dz, Blocks.AIR);
                }
            }
        }
        for (int dx = 0; dx < SIZE; dx++) {
            for (int dz = 0; dz < SIZE; dz++) {
                int x = BASE_X + dx;
                int z = BASE_Z + dz;
                Block floor = floorMaterial(x, z);
                set(level, x, y, z, floor);            // 地板（按区块棋盘格）
                // **垫基岩**（用户 2026-10-24 04:22 要求）：地板下逐层垫到没有虚空为止
                // 锚点在 min+4，底下两层是基岩，再往下是原世界基岩——挖穿地板也不会掉进虚空
                for (int b = 1; b <= BEDROCK_LAYERS; b++) {
                    set(level, x, y - b, z, Blocks.BEDROCK);
                }
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
