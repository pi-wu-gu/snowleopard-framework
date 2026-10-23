package io.github.piwugu.snowleopard.tools.chat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import snowleopard.core.json.Json;

/**
 * 每模型的"未注入 / 未提示"与游标（M12-0，2026-10-24 00:50 (UTC+8)，分析 §2.2 / §2.6）。
 *
 * <p><b>两个游标，不是两个数字的巧合</b>：
 * <ul>
 *   <li>{@code lastReadSeq} —— 注入/显式读取走到哪（决定"新聊天"）；</li>
 *   <li>{@code lastScanSeq} + {@code pendingMentions} —— 被点名提示走到哪（决定"被提及 N 次"）。</li>
 * </ul>
 * 读过正文不代表提示过，提示过也不代表读过。用一个游标做两件事，必错一个——这是群组那边的老教训
 * （{@code lastReadSeq} 与 {@code notifiedSeqs}），这里原样复用。
 *
 * <p><b>{@link #pull} 不推进游标</b>：宿主可能因预算把刚注入的段落压掉；取走就推进会让那批消息
 * 静默消失。重复注入是可见的（模型会发现"这条看过"），静默丢失是不可见的——宁可重复。
 * 推进只发生在 {@link #ack}。
 *
 * <p><b>首次见到某模型：游标初始化为当前 {@code lastSeq}</b>，不补历史（分析 §2.3）。
 */
public final class ChatInbox {

    /**
     * 游标状态。
     *
     * @param lastReadSeq      已注入/已读到的最大 seq
     * @param lastScanSeq      已扫描提及的最大 seq
     * @param pendingMentions  已记但还没在摘要行里报过的提及次数
     */
    public record Cursor(long lastReadSeq, long lastScanSeq, int pendingMentions) {
        static Cursor initial(long seq) {
            return new Cursor(seq, seq, 0);
        }
    }

    /**
     * {@code pull} 的结果。
     *
     * @param block    注入块文本（空串 = 没有未注入的玩家消息）
     * @param lastSeq  本次取到的最大 seq（原样回传给 {@link #ack}）
     * @param count    实际渲染条数
     * @param omitted  因上限没渲染的条数
     */
    public record Pulled(String block, long lastSeq, int count, int omitted) {
    }

    private final ChatLog log;
    private final Function<String, Path> cursorFileOf;
    private final Object lock = new Object();

    /**
     * @param log         聊天存储
     * @param cursorFileOf 模型编号 → 游标文件路径（由调用方按 {@code <id>/chat-cursor.json} 决定；
     *                     路径规则属于宿主，不该写死在这里）
     */
    public ChatInbox(ChatLog log, Function<String, Path> cursorFileOf) {
        if (log == null || cursorFileOf == null) {
            throw new IllegalArgumentException("ChatInbox 需要 log 与 cursorFileOf");
        }
        this.log = log;
        this.cursorFileOf = cursorFileOf;
    }

    public ChatLog log() {
        return log;
    }

    /**
     * 确保某模型的游标已存在（幂等）。
     *
     * <p><b>必须在"模型被登记"时调用，而不是"第一次提及/读取"时</b>：
     * 懒到被提及才建，会把"登记之后、被提及之前"的消息当成历史跳过——
     * 表现为"我明明说了三句，它只看见一句"。这个 bug 不报错、只是少看见（2026-10-24 踩过）。
     */
    public void ensure(String model) {
        cursor(model);
    }

    /** 读游标；文件不存在时**初始化为当前 lastSeq**（不补历史）并落盘。 */
    public Cursor cursor(String model) {
        requireModel(model);
        synchronized (lock) {
            Path f = cursorFileOf.apply(model);
            if (!Files.isRegularFile(f)) {
                Cursor c = Cursor.initial(log.lastSeq());
                write(f, c);
                return c;
            }
            try {
                Map<String, Object> m = Json.parseObject(Files.readString(f, StandardCharsets.UTF_8));
                return new Cursor(asLong(m.get("lastReadSeq")), asLong(m.get("lastScanSeq")),
                        (int) asLong(m.get("pendingMentions")));
            } catch (IOException e) {
                throw new UncheckedIOException("读聊天游标失败: " + f + " (" + e.getMessage() + ")", e);
            }
        }
    }

    /**
     * 取未注入的玩家消息并渲染成块，**不推进游标**。
     */
    public Pulled pull(String model, int limit) {
        Cursor c = cursor(model);
        List<ChatRecord> unread = log.readPlayerAfter(c.lastReadSeq(), limit);
        if (unread.isEmpty()) {
            return new Pulled("", c.lastReadSeq(), 0, 0);
        }
        ChatBlock.Rendered r = ChatBlock.render(unread, log.zone());
        long lastSeq = unread.get(unread.size() - 1).seq();
        // count 用渲染条数；omitted 用"取到了但没渲染"（超上限）
        return new Pulled(r.text(), lastSeq, r.lines(), r.omitted());
    }

    /** 确认这批已经进上下文了 → 推进 {@code lastReadSeq}（只前进不后退）。 */
    public void ack(String model, long seq) {
        requireModel(model);
        synchronized (lock) {
            Cursor c = cursor(model);
            long next = Math.max(c.lastReadSeq(), Math.min(seq, log.lastSeq()));
            write(cursorFileOf.apply(model), new Cursor(next, c.lastScanSeq(), c.pendingMentions()));
        }
    }

    /**
     * 显式读取（{@code chat_read}）：拿到全文并**推进游标**——模型明确要看 = 已经看过，
     * 再让自动注入重复一遍纯属浪费上下文。
     */
    public List<ChatRecord> read(String model, int limit) {
        Cursor c = cursor(model);
        List<ChatRecord> unread = log.readPlayerAfter(c.lastReadSeq(), limit);
        if (unread.isEmpty()) {
            return List.of();
        }
        ack(model, unread.get(unread.size() - 1).seq());
        return unread;
    }

    /**
     * 记一次提及（**只玩家来源**；模型自己说的话不算，否则互相点名会刷成风暴）。
     *
     * @param record     新落盘的记录
     * @param nameOf     模型编号 → 实体显示名（可为空串；名称要 &ge; 2 码点才算可命中）
     * @return 命中的模型编号列表（供 M13 触发唤醒用；顺序与入参一致）
     */
    public List<String> noteMention(ChatRecord record, Map<String, String> nameOf) {
        List<String> hit = new ArrayList<>();
        if (record == null || !record.fromPlayer() || nameOf == null) {
            return hit;
        }
        synchronized (lock) {
            for (Map.Entry<String, String> e : nameOf.entrySet()) {
                String model = e.getKey();
                String name = e.getValue();
                boolean byId = ChatMention.mentionsId(record.text(), model);
                boolean byName = ChatMention.mentionsName(record.text(), name);
                if (!byId && !byName) {
                    continue;
                }
                hit.add(model);
                Cursor c = cursor(model);
                write(cursorFileOf.apply(model),
                        new Cursor(c.lastReadSeq(), Math.max(c.lastScanSeq(), record.seq()),
                                c.pendingMentions() + 1));
            }
        }
        return hit;
    }

    /**
     * 直接 +1（**唤醒失败补提示**用）：不走扫描，因为触发原因不是聊天正文而是唤醒结果。
     */
    public void addPendingMention(String model) {
        requireModel(model);
        synchronized (lock) {
            Cursor c = cursor(model);
            write(cursorFileOf.apply(model), new Cursor(c.lastReadSeq(), c.lastScanSeq(),
                    c.pendingMentions() + 1));
        }
    }

    /** 待提示的提及次数（进摘要行 {@code · 被提及 M 次}）。 */
    public int pendingMentions(String model) {
        return cursor(model).pendingMentions();
    }

    /** 已经把"被提及"写进摘要行 → 清零计数（不动任何读游标）。 */
    public void consumeMentions(String model) {
        requireModel(model);
        synchronized (lock) {
            Cursor c = cursor(model);
            if (c.pendingMentions() == 0) {
                return;
            }
            write(cursorFileOf.apply(model), new Cursor(c.lastReadSeq(), c.lastScanSeq(), 0));
        }
    }

    /** 诊断用：一次性把两个游标与待提示数报出来。 */
    public Map<String, Object> status(String model) {
        Cursor c = cursor(model);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("lastReadSeq", c.lastReadSeq());
        m.put("lastScanSeq", c.lastScanSeq());
        m.put("pendingMentions", c.pendingMentions());
        m.put("lastSeq", log.lastSeq());
        m.put("unread", Math.max(0, log.lastSeq() - c.lastReadSeq()));
        return m;
    }

    // ------------------------------------------------------------- 内部

    private static void requireModel(String model) {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("需要模型编号（core 注入，不可伪造）");
        }
    }

    private void write(Path f, Cursor c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("lastReadSeq", c.lastReadSeq());
        m.put("lastScanSeq", c.lastScanSeq());
        m.put("pendingMentions", c.pendingMentions());
        try {
            if (f.getParent() != null) {
                Files.createDirectories(f.getParent());
            }
            Files.writeString(f, Json.dump(m), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("写聊天游标失败: " + f + " (" + e.getMessage() + ")", e);
        }
    }

    private static long asLong(Object v) {
        if (v instanceof Number n) {
            return n.longValue();
        }
        if (v instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }
}
