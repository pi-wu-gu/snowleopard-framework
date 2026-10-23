package io.github.piwugu.snowleopard.host;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import snowleopard.core.json.Json;

/**
 * LLM 配置（{@code config/snowleopard-llm.json}，**永不入库**——仓库公开，key 只进本机）。
 *
 * <p>形状：
 * <pre>{ "providers": [ {"name":"deepseek","base_url":"https://api.deepseek.com/v1",
 *          "api_key":"…","model":"deepseek-flash"}, … ],
 *        "temperature": 0.7, "max_rounds": 12, "history_window": 40,
 *        "models": { "clienttest": {"provider":"stepfun"} } }</pre>
 *
 * <p>{@code providers} 顺序即优先级（前一个失败/超时自动落下一个）；{@code models} 可按模型 ID
 * 覆盖用的提供商与模型名（缺省全用第一个）。
 */
public record LlmConfig(List<Provider> providers, double temperature, int maxRounds,
                        int historyWindow, Map<String, ModelOverride> models) {

    public record Provider(String name, String baseUrl, String apiKey, String model) {
    }

    /** 按模型 ID 的覆盖（两个字段都可空 = 用默认）。 */
    public record ModelOverride(String provider, String model) {
    }

    /** 解析失败抛 {@link IllegalArgumentException}（配置坏了不该静默降级）。 */
    @SuppressWarnings("unchecked")
    public static LlmConfig load(Path file) {
        Map<String, Object> root;
        try {
            root = Json.parseObject(Files.readString(file, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalArgumentException("LLM 配置读不了: " + file + " (" + e.getMessage() + ")");
        }
        Object provObj = root.get("providers");
        if (!(provObj instanceof List<?> provList) || provList.isEmpty()) {
            throw new IllegalArgumentException("LLM 配置缺 providers（非空数组）");
        }
        List<Provider> providers = new ArrayList<>();
        for (Object o : provList) {
            Map<String, Object> m = (Map<String, Object>) o;
            String name = str(m, "name");
            String url = str(m, "base_url");
            String key = str(m, "api_key");
            String model = str(m, "model");
            if (name == null || url == null || key == null || model == null) {
                throw new IllegalArgumentException("provider 缺字段（name/base_url/api_key/model 都必填）");
            }
            providers.add(new Provider(name, url, key, model));
        }
        Map<String, ModelOverride> models = new java.util.LinkedHashMap<>();
        if (root.get("models") instanceof Map<?, ?> mm) {
            for (Map.Entry<?, ?> e : mm.entrySet()) {
                if (e.getValue() instanceof Map<?, ?> ov) {
                    models.put(String.valueOf(e.getKey()), new ModelOverride(
                            ov.get("provider") == null ? null : String.valueOf(ov.get("provider")),
                            ov.get("model") == null ? null : String.valueOf(ov.get("model"))));
                }
            }
        }
        return new LlmConfig(providers,
                root.get("temperature") instanceof Number n ? n.doubleValue() : 0.7,
                root.get("max_rounds") instanceof Number n && n.intValue() > 0 ? n.intValue() : 12,
                root.get("history_window") instanceof Number n && n.intValue() > 0 ? n.intValue() : 40,
                models);
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }
}
