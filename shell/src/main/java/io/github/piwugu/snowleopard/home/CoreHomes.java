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

    /** ID 规则：字母/数字/下划线/连字符，2–32 字符（与目录名安全兼容）。 */
    private static final Pattern ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{2,32}$");

    /** ids 根目录（{@code <gameDir>/snowleopard/ids}）。 */
    public static Path idsRoot() {
        return FMLPaths.GAMEDIR.get().resolve("snowleopard").resolve("ids");
    }

    /** ID 是否合法（格式 + 非保留名）。 */
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
            // 列表失败不致命，返回已收集的部分
        }
        return out;
    }

    /**
     * 创建家（最小骨架）。<b>不绑定</b>——绑定是独立分支。
     *
     * @return true=创建成功；false=已存在或失败
     */
    public static boolean createHome(String id) {
        if (!isValidId(id) || homeExists(id)) {
            return false;
        }
        Path dir = idsRoot().resolve(id);
        try {
            // 最小骨架（与 clienttest 目录结构对齐）
            Files.createDirectories(dir.resolve("groups"));
            Files.createDirectories(dir.resolve("host"));
            Files.createDirectories(dir.resolve("memory").resolve("days"));
            Files.createDirectories(dir.resolve("memory").resolve("months"));
            Files.createDirectories(dir.resolve("memory").resolve("weeks"));
            Files.createDirectories(dir.resolve("memory-raw"));
            // 初始空文件
            Files.writeString(dir.resolve("chat-cursor.json"), "{}\n", StandardCharsets.UTF_8);
            Files.writeString(dir.resolve("groups").resolve("state.json"), "{}\n", StandardCharsets.UTF_8);
            Files.writeString(dir.resolve("host").resolve("history.jsonl"), "", StandardCharsets.UTF_8);
            return true;
        } catch (IOException e) {
            return false;
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
     * 绑定核心到家（写 {@code core_binding.json}）。一核心一家：已绑定则拒绝。
     *
     * @return true=绑定成功；false=家不存在 / 已绑定 / 写入失败
     */
    public static boolean bindCore(String id, String playerUuid, String playerName) {
        if (!homeExists(id) || isBound(id)) {
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
