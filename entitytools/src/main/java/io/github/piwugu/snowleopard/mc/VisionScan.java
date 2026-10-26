package io.github.piwugu.snowleopard.mc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import io.github.piwugu.snowleopard.tools.PngWriter;

/**
 * 视线射线扫描（视觉 B 档，纯服务端，2026-10-27 06:05 (UTC+8)）。
 *
 * <p><b>它存在的理由</b>：C 档（真截图）有一条硬限制——**客户端只能渲染自己已加载的区块**。
 * 载体独自跑到离所有玩家很远的地方时，客户端根本没有那片地形，渲染出来是虚空。
 * B 档用服务端的真实方块数据，**任何距离都成立**，是"视觉"的兜底感知。
 *
 * <p>但它**不是截图**：颜色取的是原版 {@link MapColor}（"地图上那一格的颜色"，
 * 为"远看像不像"设计），没有贴图、光影、材质细节。所以结果里必须带
 * {@code source=raycast}，**永不混称"截图"**——混称会让模型把代表色当成真实视觉。
 */
final class VisionScan {

    private static final double FOV_H_DEG = 70.0;

    private VisionScan() {
    }

    /** 参数与射线扫描。返回可直接塞进 {@code Result.ok} 的 Map（不含 base64，图只落盘给路径）。 */
    static Map<String, Object> scan(ServerLevel level, Entity self, Map<String, Object> params,
                                    Path imageDir, String capability) {
        double range = clamp(num(params, "range", 32), 4, 64);
        boolean wantImage = "image".equalsIgnoreCase(String.valueOf(params.getOrDefault("detail", "text")));
        int w = (int) clamp(num(params, "width", 64), 16, 256);
        int h = (int) clamp(num(params, "height", 36), 9, 144);

        Vec3 eye = self.getEyePosition(1.0f);
        Vec3 dir = self.getViewVector(1.0f);
        Vec3 up0 = new Vec3(0, 1, 0);
        Vec3 right = dir.cross(up0);
        if (right.lengthSqr() < 1.0e-6) {
            right = new Vec3(1, 0, 0);          // 正上/正下看时的退化情况
        }
        right = right.normalize();
        Vec3 up = right.cross(dir).normalize();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", "raycast");                       // **不许混称截图**
        out.put("capability", capability);                  // 能力级随结果走（降级可得知）
        out.put("range", range);
        out.put("eye", vec(eye));
        out.put("dir", vec(dir));
        out.put("center", describe(level, self, eye, dir, range));
        Map<String, Object> sides = new LinkedHashMap<>();
        sides.put("left", describe(level, self, eye, yaw(dir, up, -22), range));
        sides.put("right", describe(level, self, eye, yaw(dir, up, 22), range));
        sides.put("up", describe(level, self, eye, pitch(dir, right, 18), range));
        sides.put("down", describe(level, self, eye, pitch(dir, right, -18), range));
        out.put("sides", sides);
        out.put("entities", entities(level, self, eye, dir, range));

        if (wantImage) {
            if (imageDir == null) {
                out.put("delivery", "text");
                out.put("image_note", "本环境没有可写的视觉目录，只给文字描述");
            } else {
                try {
                    int[] px = new int[w * h];
                    double[] depth = new double[w * h];
                    double tanHalf = Math.tan(Math.toRadians(FOV_H_DEG) / 2.0);
                    double aspect = (double) w / h;
                    for (int py = 0; py < h; py++) {
                        double ndcY = 1.0 - 2.0 * (py + 0.5) / h;
                        for (int pxi = 0; pxi < w; pxi++) {
                            double ndcX = 2.0 * (pxi + 0.5) / w - 1.0;
                            Vec3 d = dir
                                    .add(right.scale(ndcX * tanHalf * aspect))
                                    .add(up.scale(ndcY * tanHalf))
                                    .normalize();
                            int idx = py * w + pxi;
                            Hit h2 = hit(level, self, eye, d, range);
                            if (h2 == null) {
                                px[idx] = sky(ndcY);
                                depth[idx] = range;
                            } else {
                                px[idx] = shade(h2.color, h2.dist / range);
                                depth[idx] = h2.dist;
                            }
                        }
                    }
                    drawEntities(level.getEntities(self, box(eye, dir, range),
                            e -> e != self && e.isAlive()), eye, dir, right, up, w, h, range, px, depth);
                    byte[] png = PngWriter.rgb(w, h, px);
                    Path file = imageDir.resolve("scan-" + System.currentTimeMillis() + ".png");
                    Files.createDirectories(imageDir);
                    Files.write(file, png);
                    out.put("delivery", "image");
                    out.put("image", Map.of("file", file.toString(), "width", w, "height", h,
                            "bytes", png.length, "note", "射线色块图：材质代表色，无贴图光影"));
                } catch (Exception e) {
                    // 出图失败不许让整次调用失败——文字描述仍然有效（比"什么都没有"好）
                    out.put("delivery", "text");
                    out.put("image_note", "出图失败: " + e.getClass().getSimpleName() + " " + e.getMessage());
                }
            }
        } else {
            out.put("delivery", "text");
        }
        return out;
    }

    // ------------------------------------------------------------------ 单条射线

    private record Hit(String id, double dist, String face, BlockPos pos, int color, boolean fluid) {
    }

    private static Map<String, Object> describe(ServerLevel level, Entity self, Vec3 eye, Vec3 d, double range) {
        Hit h = hit(level, self, eye, d, range);
        Map<String, Object> m = new LinkedHashMap<>();
        if (h == null) {
            m.put("id", "minecraft:air");
            m.put("distance", range);
            m.put("note", "无遮挡（可视距离已到上限）");
            return m;
        }
        m.put("id", h.id);
        m.put("distance", round(h.dist, 2));
        m.put("face", h.face);
        m.put("pos", vec(Vec3.atCenterOf(h.pos)));
        m.put("map_color", String.format("#%06X", h.color));
        return m;
    }

    private static Hit hit(ServerLevel level, Entity self, Vec3 eye, Vec3 d, double range) {
        Vec3 to = eye.add(d.scale(range));
        // Block.VISUAL = getVisualShape（能看到的东西），Fluid.NONE = 不把水当墙
        BlockHitResult hr = level.clip(new ClipContext(eye, to,
                ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, self));
        if (hr.getType() == HitResult.Type.MISS) {
            return null;
        }
        BlockPos pos = hr.getBlockPos();
        BlockState st = level.getBlockState(pos);
        MapColor mc = st.getMapColor(level, pos);
        String id = BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString();
        return new Hit(id, hr.getLocation().distanceTo(eye), hr.getDirection().getName(),
                pos, mc == null ? 0x808080 : mc.col, false);
    }

    // ------------------------------------------------------------------ 近处实体

    private static List<Map<String, Object>> entities(ServerLevel level, Entity self, Vec3 eye,
                                                      Vec3 dir, double range) {
        List<Map<String, Object>> out = new ArrayList<>();
        List<Entity> cand = level.getEntities(self, box(eye, dir, range), e -> e != self && e.isAlive());
        var scored = new ArrayList<double[]>();       // [index, distance]
        var list = new ArrayList<Entity>();
        for (Entity e : cand) {
            Vec3 v = e.position().add(0, e.getBbHeight() * 0.5, 0).subtract(eye);
            double along = v.dot(dir);
            if (along <= 0.2 || along > range) {
                continue;
            }
            double cos = along / Math.max(1.0e-6, v.length());
            if (cos < Math.cos(Math.toRadians(FOV_H_DEG / 2.0 + 10))) {
                continue;                             // 视野外（留 10° 余量，模型不关心余光）
            }
            scored.add(new double[]{list.size(), v.length()});
            list.add(e);
        }
        scored.sort((a, b) -> Double.compare(a[1], b[1]));
        int keep = Math.min(3, scored.size());        // 只报最近 3 个，避免刷屏
        for (int i = 0; i < keep; i++) {
            int idx = (int) scored.get(i)[0];
            Entity e = list.get(idx);
            Vec3 v = e.position().add(0, e.getBbHeight() * 0.5, 0).subtract(eye);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString());
            m.put("name", e.getName().getString());
            m.put("distance", round(scored.get(i)[1], 2));
            m.put("same_level", Math.abs(e.getY() - eye.y) < 1.2);
            out.add(m);
        }
        return out;
    }

    private static void drawEntities(List<Entity> list, Vec3 eye, Vec3 dir, Vec3 right, Vec3 up,
                                      int w, int h, double range, int[] px, double[] depth) {
        double tanHalf = Math.tan(Math.toRadians(FOV_H_DEG) / 2.0);
        double aspect = (double) w / h;
        for (Entity e : list) {
            Vec3 v = e.position().add(0, e.getBbHeight() * 0.5, 0).subtract(eye);
            double z = v.dot(dir);
            if (z <= 0.2 || z > range) {
                continue;
            }
            double ndcX = (v.dot(right) / z) / (tanHalf * aspect);
            double ndcY = (v.dot(up) / z) / tanHalf;
            if (Math.abs(ndcX) > 1 || Math.abs(ndcY) > 1) {
                continue;
            }
            int cx = (int) ((ndcX + 1) / 2 * w);
            int cy = (int) ((1 - ndcY) / 2 * h);
            int half = Math.max(1, (int) Math.min(9, e.getBbWidth() / z / (2 * tanHalf) * w));
            for (int dy = -half; dy <= half; dy++) {
                for (int dx = -half; dx <= half; dx++) {
                    int x = cx + dx;
                    int y = cy + dy;
                    if (x < 0 || y < 0 || x >= w || y >= h) {
                        continue;
                    }
                    int i = y * w + x;
                    if (depth[i] < z) {
                        continue;                     // 被方块挡住
                    }
                    px[i] = 0xD04040;                 // 实体剪影：固定红，别用实体本身颜色（会误导材质）
                }
            }
        }
    }

    // ------------------------------------------------------------------ 小工具

    private static AABB box(Vec3 eye, Vec3 dir, double range) {
        Vec3 to = eye.add(dir.scale(range));
        return new AABB(eye, to).inflate(2.0);
    }

    private static Vec3 yaw(Vec3 dir, Vec3 up, double deg) {
        double a = Math.toRadians(deg);
        return dir.scale(Math.cos(a)).add(up.cross(dir).scale(Math.sin(a))).normalize();
    }

    private static Vec3 pitch(Vec3 dir, Vec3 right, double deg) {
        double a = Math.toRadians(deg);
        return dir.scale(Math.cos(a)).add(right.cross(dir).scale(-Math.sin(a))).normalize();
    }

    private static int sky(double ndcY) {
        // 上方深一点、靠地平线浅一点——给模型"这是天空"的直觉，避免它把天空当成白墙
        double t = Math.max(0, Math.min(1, (ndcY + 1) / 2));
        int r = (int) (150 + 70 * (1 - t));
        int g = (int) (190 + 45 * (1 - t));
        int b = (int) (240 + 15 * (1 - t));
        return (r << 16) | (g << 8) | b;
    }

    private static int shade(int color, double t) {
        double f = 1.0 - Math.min(1.0, t) * 0.55;      // 远处更暗：给一点距离线索
        int r = (int) (((color >> 16) & 0xFF) * f);
        int g = (int) (((color >> 8) & 0xFF) * f);
        int b = (int) ((color & 0xFF) * f);
        return (r << 16) | (g << 8) | b;
    }

    private static List<Double> vec(Vec3 v) {
        return List.of(round(v.x, 2), round(v.y, 2), round(v.z, 2));
    }

    private static double round(double v, int digits) {
        double p = Math.pow(10, digits);
        return Math.round(v * p) / p;
    }

    private static double num(Map<String, Object> params, String key, double dflt) {
        Object v = params.get(key);
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v != null) {
            try {
                return Double.parseDouble(String.valueOf(v).trim());
            } catch (NumberFormatException ignored) {
                // 保持默认
            }
        }
        return dflt;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** 供模组层判断"能不能扫描"（绑定实体要做 raycast 的排除对象）。 */
    static boolean usable(LivingEntity self) {
        return self != null && self.level() instanceof ServerLevel;
    }
}
