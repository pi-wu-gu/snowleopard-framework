package io.github.piwugu.snowleopard.host;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import snowleopard.core.json.Json;

/**
 * 对话历史（H2）：每模型一个 {@code ids/<模型>/host/history.jsonl}，**append-only**。
 *
 * <p>每行 = 一条 OpenAI 形状的消息（{@code role/content/tool_calls/tool_call_id}）+ {@code ts}。
 * 装配历史窗口时**从最后一条 {@code user} 消息开始**截取——这保证 assistant 的
 * {@code tool_calls} 与对应 {@code tool} 结果永远成对出现在窗口里（API 对孤儿工具消息会直接报错）。
 */
public final class ConversationStore {

    private final Path base;   // ids 根（root() 本身就是 …/snowleopard/ids——不是存储根！）

    public ConversationStore(Path idsRoot) {
        this.base = idsRoot;
    }

    private Path fileOf(String model) {
        return base.resolve(model).resolve("host").resolve("history.jsonl");
    }

    /** 追加一条消息（坏盘只抛出，不静默丢——历史丢了模型就"失忆"且不可察觉）。 */
    public synchronized void append(String model, Map<String, Object> message) {
        try {
            Path f = fileOf(model);
            Files.createDirectories(f.getParent());
            Map<String, Object> line = new java.util.LinkedHashMap<>();
            line.put("ts", System.currentTimeMillis());
            line.putAll(message);
            Files.writeString(f, Json.dump(line) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException("对话历史写盘失败: " + e.getMessage(), e);
        }
    }

    /**
     * 装配历史窗口：从**最后一条 user 消息**起取（保证工具消息成对），最多 {@code limit} 条。
     * 坏行跳过（半个写入不该锁死会话）。
     */
    public synchronized List<Map<String, Object>> window(String model, int limit) {
        Path f = fileOf(model);
        if (!Files.isRegularFile(f)) {
            return List.of();
        }
        List<Map<String, Object>> all = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    all.add(Json.parseObject(line));
                } catch (RuntimeException ignored) {
                    // 坏行：跳过（append-only 日志的一条损坏不该让整个会话失败）
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("对话历史读不了: " + e.getMessage(), e);
        }
        // 起点 = 最后一条 user 消息（保证工具消息成对）；再向前整段扩，扩前先看会不会超上限
        int start = all.size() - 1;
        for (int i = all.size() - 1; i >= 0; i--) {
            if ("user".equals(all.get(i).get("role"))) {
                start = i;
                break;
            }
        }
        while (true) {
            int prev = -1;
            for (int i = start - 1; i >= 0; i--) {
                if ("user".equals(all.get(i).get("role"))) {
                    prev = i;
                    break;
                }
            }
            if (prev < 0 || all.size() - prev > limit) {
                break;
            }
            start = prev;
        }
        return new ArrayList<>(all.subList(start, all.size()));
    }

    /** 清空某模型的历史（自测卫生用）。 */
    public synchronized void clear(String model) {
        try {
            Files.deleteIfExists(fileOf(model));
        } catch (IOException ignored) {
            // 清不掉就留着（不影响断言的正确性，只是多了历史）
        }
    }
}
