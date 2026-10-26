package io.github.piwugu.snowleopard.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 视觉产物目录的小工具（找最近一张图 / 清旧图）。
 *
 * <p>为什么单独一个类：{@code vision/show} 与"保留 N 张"两处都要找文件，
 * 各写一遍必漂移（这个仓已经踩过"同一职责多实现"的坑）。
 */
public final class VisionFiles {

    private VisionFiles() {
    }

    /**
     * 在目录里找最近一张 PNG。
     *
     * @param idFragment 文件名片段（{@code cap_id} / {@code reqId}）；null 或空 = 不筛
     * @return 找不到返回 {@code null}（**不抛异常**：这属于"没有"，不是"出错"）
     */
    public static Path findLatest(Path dir, String idFragment) {
        if (dir == null || !Files.isDirectory(dir)) {
            return null;
        }
        try (Stream<Path> s = Files.list(dir)) {
            List<Path> files = s
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".png"))
                    .filter(p -> idFragment == null || idFragment.isBlank()
                            || p.getFileName().toString().contains(idFragment))
                    .sorted(Comparator.comparingLong(VisionFiles::modified).reversed())
                    .toList();
            return files.isEmpty() ? null : files.get(0);
        } catch (IOException e) {
            return null;
        }
    }

    /** 只保留最近 {@code keep} 张，其余删掉（图会积累，长时间跑必须清）。 */
    public static int prune(Path dir, int keep) {
        if (dir == null || !Files.isDirectory(dir) || keep <= 0) {
            return 0;
        }
        try (Stream<Path> s = Files.list(dir)) {
            List<Path> files = s
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".png"))
                    .sorted(Comparator.comparingLong(VisionFiles::modified).reversed())
                    .toList();
            int removed = 0;
            for (int i = keep; i < files.size(); i++) {
                try {
                    Files.deleteIfExists(files.get(i));
                    removed++;
                } catch (IOException ignored) {
                    // 删不掉就留着，不影响主流程
                }
            }
            return removed;
        } catch (IOException e) {
            return 0;
        }
    }

    private static long modified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }
}
