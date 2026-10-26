package io.github.piwugu.snowleopard.host;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import snowleopard.core.json.Json;

import io.github.piwugu.snowleopard.SnowLeopardMod;

/**
 * OpenAI 兼容 LLM 客户端（H1，2026-10-24 02:45 (UTC+8)）。
 *
 * <p>Java 21 内置 {@link HttpClient} + core 的 {@link Json}——**不新增第三方依赖**（框架红线）。
 * 多提供商按配置顺序**故障转移**：网络错误 / 5xx / 4xx 一律落下一个；全部失败抛
 * {@link IllegalStateException}——**绝不返回假装成功的空消息**（假成功家族铁律）。
 *
 * <p><b>两条能力约束</b>（2026-10-27 05:50 (UTC+8)，见 {@code docs/视觉输入可行性分析}）：
 * <ol>
 *   <li><b>带图请求不许跨能力故障转移</b>：{@code requiresVision=true} 时只在
 *       {@code multimodal=true} 的提供商之间转，全部失败直接抛错。
 *       否则视觉模型超时 → 落到纯文本模型 → 400，或者更糟：端点亮绿灯但**静默忽略图片**，
 *       模型基于"没图"作答而宿主以为它看过。</li>
 *   <li><b>按用途选提供商</b>：{@code roles{chat,vision,compress}} 显式声明，不靠 providers 顺序
 *       （原因见 {@link LlmConfig} 类注释）。</li>
 * </ol>
 */
public final class LlmClient {

    private final LlmConfig config;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    /** 最近一次真正发出请求的提供商与模型名（可观测性：降级必须能被看见）。 */
    private volatile String lastProvider = "";
    private volatile String lastModel = "";

    public LlmClient(LlmConfig config) {
        this.config = config;
    }

    public LlmConfig config() {
        return config;
    }

    public String lastProvider() {
        return lastProvider;
    }

    public String lastModel() {
        return lastModel;
    }

    /** 是否至少有一家声明了多模态能力（视觉能力的存在性判定，唯一来源）。 */
    public boolean canVision() {
        return config.providerFor(LlmConfig.ROLE_VISION) != null
                ? config.providerFor(LlmConfig.ROLE_VISION).multimodal()
                : config.firstMultimodal() != null;
    }

    /** 当前视觉提供商名（不可用返回空串——调用方据此展示"降级原因"）。 */
    public String visionProviderName() {
        LlmConfig.Provider p = config.providerFor(LlmConfig.ROLE_VISION);
        if (p != null && p.multimodal()) {
            return p.name();
        }
        LlmConfig.Provider first = config.firstMultimodal();
        return first == null ? "" : first.name();
    }

    /**
     * 故障转移的实际尝试顺序（只用于日志与自检，不漏 key）。
     *
     * @param requiresVision true 时只列多模态提供商（这正是"能力守卫"的可观测面）
     */
    public List<String> plan(boolean requiresVision) {
        List<String> out = new ArrayList<>();
        for (LlmConfig.Provider p : ordered(requiresVision)) {
            out.add(p.name() + "/" + (requiresVision ? p.modelForVision() : p.model()));
        }
        return out;
    }

    /**
     * 一次对话补全（带工具），默认不带图。
     *
     * @param modelId 决策核心的模型编号（选 provider/model 用，不发给 LLM）
     * @param messages OpenAI 消息数组（system/user/assistant/tool，现场装配）
     * @param tools 工具清单（{@code [{"type":"function","function":{…}}]}；空 = 不带工具）
     * @return assistant 消息（含 {@code content} 与可选 {@code tool_calls}）
     */
    public Map<String, Object> chat(String modelId, List<Map<String, Object>> messages,
                                    List<Map<String, Object>> tools) {
        return chat(modelId, messages, tools, false);
    }

    /**
     * 一次对话补全。
     *
     * @param requiresVision 消息里含图片时为 true——只在多模态提供商之间转移，**禁止降落文本模型**
     */
    public Map<String, Object> chat(String modelId, List<Map<String, Object>> messages,
                                    List<Map<String, Object>> tools, boolean requiresVision) {
        List<LlmConfig.Provider> candidates = ordered(requiresVision);
        if (candidates.isEmpty()) {
            // 显式失败，不静默降级：模型看不到图比"以为看到了"好一万倍
            throw new IllegalStateException(requiresVision
                    ? "无可用视觉提供商（没有 multimodal=true 的 provider，或 roles.vision 指向的provider 未声明多模态）"
                    : "无可用 LLM 提供商");
        }
        LlmConfig.ModelOverride ov = config.models().get(modelId);
        List<String> failures = new ArrayList<>();
        for (LlmConfig.Provider p : candidates) {
            String model = requiresVision ? modelFor(p, ov)
                    : (ov != null && ov.model() != null ? ov.model() : p.model());
            try {
                return call(p, model, messages, tools);
            } catch (Exception e) {
                failures.add(p.name() + ":" + e.getMessage());
                SnowLeopardMod.LOG.warn("[雪豹框架][host] 提供商 {} 失败，尝试下一个: {}",
                        p.name(), e.getMessage());
            }
        }
        throw new IllegalStateException("所有 LLM 提供商都失败: " + String.join(" | ", failures));
    }

    /** 不带工具的单发（Compressor 用；{@code role} 决定首选提供商，别用视觉模型烧钱）。 */
    public String complete(String role, String systemPrompt, String userContent) {
        Map<String, Object> msg = chatByRole(role,
                List.of(Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userContent)),
                List.of());
        return textOf(msg.get("content"));
    }

    /** 兼容旧调用（= role 用 chat）。 */
    public String complete(String systemPrompt, String userContent) {
        return complete(LlmConfig.ROLE_CHAT, systemPrompt, userContent);
    }

    private Map<String, Object> chatByRole(String role, List<Map<String, Object>> messages,
                                          List<Map<String, Object>> tools) {
        List<LlmConfig.Provider> all = config.providers();
        LlmConfig.Provider preferred = config.providerFor(role);
        int start = preferred == null ? 0 : indexOf(preferred);
        List<String> failures = new ArrayList<>();
        for (int attempt = 0; attempt < all.size(); attempt++) {
            LlmConfig.Provider p = all.get((start + attempt) % all.size());
            try {
                return call(p, p.model(), messages, tools);
            } catch (Exception e) {
                failures.add(p.name() + ":" + e.getMessage());
                SnowLeopardMod.LOG.warn("[雪豹框架][host] 提供商 {}（role={}）失败: {}",
                        p.name(), role, e.getMessage());
            }
        }
        throw new IllegalStateException("所有 LLM 提供商都失败（role=" + role + "）: "
                + String.join(" | ", failures));
    }

    /**
     * 按用途排出候选顺序：起点由 roles / per-model 覆盖决定，环形展开。
     * {@code requiresVision} 时**过滤掉非多模态提供商**——这就是能力守卫本身。
     */
    private List<LlmConfig.Provider> ordered(boolean requiresVision) {
        List<LlmConfig.Provider> all = config.providers();
        int start = 0;
        LlmConfig.Provider vision = config.providerFor(LlmConfig.ROLE_VISION);
        if (requiresVision) {
            LlmConfig.Provider preferred = vision != null && vision.multimodal()
                    ? vision : config.firstMultimodal();
            start = preferred == null ? 0 : indexOf(preferred);
        } else {
            LlmConfig.Provider chat = config.providerFor(LlmConfig.ROLE_CHAT);
            start = chat == null ? 0 : indexOf(chat);
        }
        List<LlmConfig.Provider> out = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            LlmConfig.Provider p = all.get((start + i) % all.size());
            if (requiresVision && !p.multimodal()) {
                continue;
            }
            out.add(p);
        }
        return out;
    }

    private int indexOf(LlmConfig.Provider target) {
        for (int i = 0; i < config.providers().size(); i++) {
            if (config.providers().get(i).name().equals(target.name())) {
                return i;
            }
        }
        return 0;
    }

    /** 视觉优先 {@code vision_model}，其次按模型 ID 的覆盖，最后才是默认 model。 */
    private static String modelFor(LlmConfig.Provider p, LlmConfig.ModelOverride ov) {
        if (p.visionModel() != null && !p.visionModel().isBlank()) {
            return p.modelForVision();
        }
        return ov != null && ov.model() != null ? ov.model() : p.model();
    }

    /**
     * 把 assistant 的 {@code content} 归一化成纯文本。
     *
     * <p>文本模型返回字符串；**部分视觉模型返回数组式 content**
     * （{@code [{"type":"text","text":"…"}]}）。直接 {@code String.valueOf} 会把
     * {@code [{type=text, text=…}]} 这种垃圾灌进上下文（P0 断言 37 的由来）。
     */
    public static String textOf(Object content) {
        if (content == null) {
            return "";
        }
        if (content instanceof String s) {
            return s;
        }
        if (content instanceof List<?> parts) {
            StringBuilder b = new StringBuilder();
            for (Object o : parts) {
                if (o instanceof Map<?, ?> m) {
                    Object t = m.get("text");
                    if (t != null) {
                        b.append(t);
                    }
                } else if (o != null) {
                    b.append(o);
                }
            }
            return b.toString();
        }
        return String.valueOf(content);
    }

    private Map<String, Object> call(LlmConfig.Provider p, String model,
                                     List<Map<String, Object>> messages,
                                     List<Map<String, Object>> tools) throws Exception {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("temperature", config.temperature());
        if (!tools.isEmpty()) {
            body.put("tools", tools);
        }
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(p.baseUrl().replaceAll("/+$", "") + "/chat/completions"))
                .timeout(Duration.ofSeconds(90))
                .header("Authorization", "Bearer " + p.apiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.dump(body)))
                .build();
        HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException("HTTP " + resp.statusCode()
                    + " " + snippet(resp.body()));
        }
        Map<String, Object> root = Json.parseObject(resp.body());
        if (!(root.get("choices") instanceof List<?> choices) || choices.isEmpty()
                || !(choices.get(0) instanceof Map<?, ?> choice)
                || !(choice.get("message") instanceof Map<?, ?> message)) {
            throw new IllegalStateException("响应缺 choices[0].message: " + snippet(resp.body()));
        }
        lastProvider = p.name();
        lastModel = model;
        @SuppressWarnings("unchecked")
        Map<String, Object> msg = (Map<String, Object>) message;
        return msg;
    }

    private static String snippet(String s) {
        if (s == null) {
            return "";
        }
        String one = s.replace('\n', ' ');
        return one.length() > 120 ? one.substring(0, 120) + "…" : one;
    }
}
