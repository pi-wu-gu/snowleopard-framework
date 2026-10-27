package io.github.piwugu.snowleopard.host;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import snowleopard.core.json.Json;

/**
 * LLM 配置（{@code config/snowleopard-llm.json}，**永不入库**——仓库公开，key 只进本机）。
 *
 * <p>形状：
 * <pre>{ "providers": [ {"name":"deepseek","base_url":"https://api.deepseek.com/v1",
 *          "api_key":"…","model":"deepseek-flash"},
 *        {"name":"qwen-vl","base_url":"…","api_key":"…","model":"…",
 *          "multimodal": true, "vision_model": "可选"} ],
 *        "roles": { "chat":"deepseek", "vision":"qwen-vl", "compress":"deepseek" },
 *        "vision": { "enabled": true, "width": 384, "height": 216 },
 *        "temperature": 0.7, "max_rounds": 32, "history_window": 40,
 *        "models": { "clienttest": {"provider":"stepfun"} } }</pre>
 *
 * <p><b>为什么要有 {@code roles}</b>（2026-10-27 05:45 (UTC+8)）：原来的故障转移是"全 provider 环"，
 * 且 {@code Compressor} 走 {@code chat("", …)} → {@code start=0} 永远用 {@code providers[0]}。
 * 一旦把视觉模型放进列表首位，**记忆压缩会被换成视觉模型**（贵、慢、未必擅长长文本）；
 * 放进末位则视觉只在前几家全挂时才用上。**用顺序表达用途是行不通的**，所以用途必须显式声明。
 *
 * <p>{@code providers} 顺序仍表示同类之间的优先级（前一个失败/超时自动落下一个）。
 * {@code models} 可按模型 ID 覆盖提供商与模型名（缺省全用第一个）。
 */
public record LlmConfig(List<Provider> providers, double temperature, int maxRounds,
                        int historyWindow, Map<String, ModelOverride> models,
                        Map<String, String> roles, VisionSettings vision) {

    /** 普通对话（工具循环）。 */
    public static final String ROLE_CHAT = "chat";
    /** 带图请求：只允许落在 {@code multimodal=true} 的提供商。 */
    public static final String ROLE_VISION = "vision";
    /** 记忆压缩：必须待在文本模型上，别被视觉模型带偏。 */
    public static final String ROLE_COMPRESS = "compress";

    /**
     * 一个提供商。
     *
     * <p>{@code multimodal} 是**能力声明**，不是猜测：只有它为 true 的提供商才会收到带图请求
     * （带图请求不许跨能力故障转移，见 {@link LlmClient#chat(String, List, List, boolean)}）。
     * {@code visionModel} 可选——同一家可以有"文本模型 + 视觉模型"两个型号。
     */
    public record Provider(String name, String baseUrl, String apiKey, String model,
                           boolean multimodal, String visionModel) {

        /** 兼容旧配置形状（纯文本提供商）。 */
        public Provider(String name, String baseUrl, String apiKey, String model) {
            this(name, baseUrl, apiKey, model, false, null);
        }

        /** 实际用于视觉请求的模型名（未单独指定时用 {@code model}）。 */
        public String modelForVision() {
            return visionModel == null || visionModel.isBlank() ? model : visionModel;
        }
    }

    /** 按模型 ID 的覆盖（两个字段都可空 = 用默认）。 */
    public record ModelOverride(String provider, String model) {
    }

    /**
     * 视觉设置。{@code backend}: {@code none|stub|client}——
     * {@code stub} 只在客户端验收模式下可用（它会产出**假画面**，必须能被识别出来）。
     */
    public record VisionSettings(boolean enabled, int width, int height, int timeoutMs,
                                 int maxCaptures, int maxPending, int keepImages,
                                 String backend, String defaultDetail) {

        /** 未配置时的默认值（关闭）。 */
        public static final VisionSettings OFF =
                new VisionSettings(false, 384, 216, 2000, 2, 2, 20, "none", "image");

        /** 渲染后端是否可用（{@code none} = 没接）。 */
        public boolean renderAvailable() {
            return enabled && !"none".equals(backend);
        }
    }

    /**
     * 某个角色的提供商；未配置或不认识返回 {@code null}（调用方回落 {@code providers[0]}）。
     */
    public Provider providerFor(String role) {
        String name = roles.get(role);
        if (name == null) {
            return null;
        }
        for (Provider p : providers) {
            if (p.name().equals(name)) {
                return p;
            }
        }
        return null;
    }

    /** 第一个声明了多模态能力的提供商（没有则 null）。 */
    public Provider firstMultimodal() {
        for (Provider p : providers) {
            if (p.multimodal()) {
                return p;
            }
        }
        return null;
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
            providers.add(new Provider(name, url, key, model,
                    bool(m.get("multimodal")), str(m, "vision_model")));
        }
        Map<String, ModelOverride> models = new LinkedHashMap<>();
        if (root.get("models") instanceof Map<?, ?> mm) {
            for (Map.Entry<?, ?> e : mm.entrySet()) {
                if (e.getValue() instanceof Map<?, ?> ov) {
                    models.put(String.valueOf(e.getKey()), new ModelOverride(
                            ov.get("provider") == null ? null : String.valueOf(ov.get("provider")),
                            ov.get("model") == null ? null : String.valueOf(ov.get("model"))));
                }
            }
        }
        Map<String, String> roles = new LinkedHashMap<>();
        if (root.get("roles") instanceof Map<?, ?> rm) {
            for (Map.Entry<?, ?> e : rm.entrySet()) {
                String role = String.valueOf(e.getKey());
                String prov = e.getValue() == null ? null : String.valueOf(e.getValue()).trim();
                if (prov == null || prov.isEmpty()) {
                    continue;
                }
                // 名字打错必须当场炸：否则会静默回落到 providers[0]，而"回落到哪家"正是本字段要治的病
                boolean found = false;
                for (Provider p : providers) {
                    if (p.name().equals(prov)) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    throw new IllegalArgumentException("roles." + role + " 指向不存在的提供商: " + prov);
                }
                roles.put(role, prov);
            }
        }
        return new LlmConfig(providers,
                root.get("temperature") instanceof Number n ? n.doubleValue() : 0.7,
                root.get("max_rounds") instanceof Number n && n.intValue() > 0 ? n.intValue() : 32,
                root.get("history_window") instanceof Number n && n.intValue() > 0 ? n.intValue() : 40,
                models, roles, vision(root.get("vision")));
    }

    /** 视觉段：缺省关闭；非法值夹取或报错，**不许算出 0 尺寸**（渲染会直接崩）。 */
    private static VisionSettings vision(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) {
            return VisionSettings.OFF;
        }
        String backend = m.get("backend") == null ? "none" : String.valueOf(m.get("backend")).trim();
        if (!List.of("none", "stub", "client").contains(backend)) {
            throw new IllegalArgumentException("vision.backend 只认 none|stub|client: " + backend);
        }
        return new VisionSettings(
                bool(m.get("enabled")),
                clamp(m.get("width"), 64, 640, 384),
                clamp(m.get("height"), 36, 360, 216),
                clamp(m.get("timeout_ms"), 500, 10000, 2000),
                clamp(m.get("max_captures_per_session"), 0, 8, 2),
                clamp(m.get("max_pending"), 1, 4, 2),
                clamp(m.get("keep_images"), 1, 200, 20),
                backend,
                m.get("default_detail") == null ? "image" : String.valueOf(m.get("default_detail")).trim());
    }

    private static int clamp(Object v, int lo, int hi, int dflt) {
        if (!(v instanceof Number n)) {
            return dflt;
        }
        int i = n.intValue();
        if (i <= 0) {
            return dflt;   // 0/负数一律当"没配"，别让它变成 0 尺寸
        }
        return Math.max(lo, Math.min(hi, i));
    }

    private static boolean bool(Object v) {
        if (v instanceof Boolean b) {
            return b;
        }
        return v != null && "true".equalsIgnoreCase(String.valueOf(v).trim());
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
