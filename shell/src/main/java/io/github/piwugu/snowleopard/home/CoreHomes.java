package io.github.piwugu.snowleopard.home;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import net.neoforged.fml.loading.FMLPaths;

import snowleopard.core.json.Json;

/**
 * 受限核心家目录管理（P1，分析 v3 §3.1/§3.2）。
 *
 * <p>家 = {@code <gameDir>/snowleopard/ids/<ID>/}，含最小骨架（chat-cursor / groups / host / memory）。
 * <b>创建家不消耗、不绑定</b>——绑定是另一条分支（消耗 1 核心 + 写 NBT）。
 *
 * <p><b>一核心一家</b>（R2）：{@code core_binding.json} 存在 = 已绑定。
 * 绑定时写入 {@code {player, boundAt}}；创建家不写此文件。
 */
public final class CoreHomes {

    private CoreHomes() {
    }

    /** ID 规则：字母/数字/下划线/连字符，2–32 字符。点号被排除，`resolve` 天然安全（无路径穿越）。 */
    private static final Pattern ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{2,32}$");

    /** ids 根目录（{@code <gameDir>/snowleopard/ids}）。 */
    public static Path idsRoot() {
        return FMLPaths.GAMEDIR.get().resolve("snowleopard").resolve("ids");
    }

    /** ID 是否合法（**只校验格式**；与既有家重名由 {@link #homeExists} 拦，不在此判）。 */
    public static boolean isValidId(String id) {
        return id != null && ID_PATTERN.matcher(id).matches();
    }

    /** 家目录是否存在。 */
    public static boolean homeExists(String id) {
        return isValidId(id) && Files.isDirectory(idsRoot().resolve(id));
    }

    /** 该家是否已有绑定核心（{@code core_binding.json} 存在 = 已绑定）。 */
    public static boolean isBound(String id) {
        return homeExists(id) && Files.exists(idsRoot().resolve(id).resolve("core_binding.json"));
    }

    /**
     * 该家的所有者玩家 UUID（{@code core_binding.json} 的 {@code player} 字段）。
     *
     * <p>2026-10-28 语义修正后：**绑定 = 消耗核心生成载体**，玩家手里不再有"钥匙"，
     * 于是"谁能传送/释放这个家的载体"只能靠这份所有权记录来判。
     *
     * @return 所有者 UUID 字符串；未绑定或读不出来 → null
     */
    public static String boundPlayer(String id) {
        if (!isBound(id)) {
            return null;
        }
        try {
            var root = Json.parseObject(Files.readString(
                    idsRoot().resolve(id).resolve("core_binding.json"), StandardCharsets.UTF_8));
            Object p = root.get("player");
            return p == null ? null : String.valueOf(p);
        } catch (IOException | RuntimeException e) {
            io.github.piwugu.snowleopard.SnowLeopardMod.LOG.warn(
                    "[受限核心] 读 {}/core_binding.json 失败：{}", id, e.toString());
            return null;
        }
    }

    /** 列出所有家 ID（按字典序）。 */
    public static List<String> listHomes() {
        List<String> out = new ArrayList<>();
        Path root = idsRoot();
        if (!Files.isDirectory(root)) {
            return out;
        }
        try (Stream<Path> s = Files.list(root)) {
            s.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .filter(CoreHomes::isValidId)
                    .sorted()
                    .forEach(out::add);
        } catch (IOException e) {
            // 列表失败不致命（返回已收集的部分），但**必须留痕**：否则模型看到空列表会以为"一个家都没有"
            io.github.piwugu.snowleopard.SnowLeopardMod.LOG.warn(
                    "[受限核心] 列家失败（返回已收集的 {} 个）：{}", out.size(), e.toString());
        }
        return out;
    }

    /**
     * 创建家（最小骨架）。<b>不绑定</b>——绑定是独立分支。
     *
     * <p>骨架必须与记忆的**平级五层**一致（日/周/月/季/年）：只建三层会让季档、年档
     * 写入时才发现目录不存在（2026-10-28 审查发现）。
     *
     * <p><b>失败必须回滚</b>：残留半成品目录会让 {@link #homeExists} 变真 →
     * 这个 ID 从此永远建不起来（每次都在"已存在"处返回 false），只能手删目录。同"清家必须清后重建"一族。
     *
     * @return true=创建成功；false=已存在或失败（失败已回滚、已记日志）
     */
    public static boolean createHome(String id) {
        if (!isValidId(id) || homeExists(id)) {
            return false;
        }
        Path dir = idsRoot().resolve(id);
        boolean rootExisted = Files.isDirectory(dir);
        try {
            // 最小骨架（与 clienttest 目录结构对齐）
            Files.createDirectories(dir.resolve("groups"));
            Files.createDirectories(dir.resolve("host"));
            Path mem = dir.resolve("memory");
            for (String level : List.of("days", "weeks", "months", "quarters", "years")) {
                Files.createDirectories(mem.resolve(level));
            }
            Files.createDirectories(dir.resolve("memory-raw"));
            // 初始空文件
            Files.writeString(dir.resolve("chat-cursor.json"), "{}\n", StandardCharsets.UTF_8);
            Files.writeString(dir.resolve("groups").resolve("state.json"), "{}\n", StandardCharsets.UTF_8);
            Files.writeString(dir.resolve("host").resolve("history.jsonl"), "", StandardCharsets.UTF_8);
            return true;
        } catch (IOException e) {
            if (!rootExisted) {
                deleteRecursively(dir);
            }
            io.github.piwugu.snowleopard.SnowLeopardMod.LOG.warn(
                    "[受限核心] 建家失败并已回滚 {}：{}", id, e.toString());
            return false;
        }
    }

    /** 递归删除（只用于回滚自己刚建的目录；失败也只记日志，不抛）。 */
    private static void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 删不干净只会留下空目录，下次建家仍会因"已存在"失败——记一条就够
                    io.github.piwugu.snowleopard.SnowLeopardMod.LOG.warn("[受限核心] 回滚残留 {}", p);
                }
            });
        } catch (IOException e) {
            io.github.piwugu.snowleopard.SnowLeopardMod.LOG.warn("[受限核心] 回滚失败 {}：{}", dir, e.toString());
        }
    }

    /**
     * 解绑核心（删 {@code core_binding.json}）。丢失钥匙的恢复路径。
     *
     * @return true=解绑成功
     */
    public static boolean unbindCore(String id) {
        if (!homeExists(id)) {
            return false;
        }
        try {
            return Files.deleteIfExists(idsRoot().resolve(id).resolve("core_binding.json"));
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 写所有权记录（{@code core_binding.json}）。
     *
     * <p><b>允许覆盖已有记录</b>（2026-10-28）：孤儿记录（binding 在、载体已经没了——旧语义遗留、
     * 载体被杀、换世界）必须能被"重新生成载体"覆盖，否则那个 ID 永久不可用。
     * 「一 ID 一载体」由调用方用"是否真的有载体"来拦，**不靠这个文件在不在**。
     *
     * @return true=写入成功；false=家不存在或写不进去
     */
    public static boolean bindCore(String id, String playerUuid, String playerName) {
        if (!homeExists(id)) {
            return false;
        }
        Path f = idsRoot().resolve(id).resolve("core_binding.json");
        try {
            var binding = new java.util.LinkedHashMap<String, Object>();
            binding.put("player", playerUuid);
            binding.put("playerName", playerName);
            binding.put("boundAt", System.currentTimeMillis());
            Files.writeString(f, Json.dump(binding) + "\n", StandardCharsets.UTF_8);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
