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
 */
public final class LlmClient {

    private final LlmConfig config;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    public LlmClient(LlmConfig config) {
        this.config = config;
    }

    public LlmConfig config() {
        return config;
    }

    /**
     * 一次对话补全（带工具）。
     *
     * @param modelId 决策核心的模型编号（选 provider/model 用，不发给 LLM）
     * @param messages OpenAI 消息数组（system/user/assistant/tool，现场装配）
     * @param tools 工具清单（{@code [{"type":"function","function":{…}}]}；空 = 不带工具）
     * @return assistant 消息（含 {@code content} 与可选 {@code tool_calls}）
     */
    public Map<String, Object> chat(String modelId, List<Map<String, Object>> messages,
                                    List<Map<String, Object>> tools) {
        LlmConfig.ModelOverride ov = config.models().get(modelId);
        int start = 0;
        if (ov != null && ov.provider() != null) {
            for (int i = 0; i < config.providers().size(); i++) {
                if (config.providers().get(i).name().equals(ov.provider())) {
                    start = i;
                    break;
                }
            }
        }
        List<String> failures = new ArrayList<>();
        for (int attempt = 0; attempt < config.providers().size(); attempt++) {
            LlmConfig.Provider p = config.providers().get((start + attempt) % config.providers().size());
            String model = ov != null && ov.model() != null ? ov.model() : p.model();
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

    /** 不带工具的单发（Compressor 用）。 */
    public String complete(String systemPrompt, String userContent) {
        Map<String, Object> msg = chat("",
                List.of(Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userContent)),
                List.of());
        Object content = msg.get("content");
        return content == null ? "" : String.valueOf(content);
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
