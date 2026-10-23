package io.github.piwugu.snowleopard.tools.chat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import snowleopard.core.json.Json;

/**
 * 聊天记录存储（M12-0/M12-1，2026-10-24 00:50 (UTC+8)，分析 §三）。
 *
 * <p><b>布局</b>：{@code <root>/<yyyy-MM-dd>.jsonl} 一天一个文件 + {@code <root>/state.json} 存 {@code lastSeq}。
 * 按天分文件让"指定天搜索"退化成"打开一个文件"，也让保留策略只是删文件。
 *
 * <p><b>{@code lastSeq} 必须单独持久化</b>：不能从"最后一天文件的最后一行"推——那天的文件被保留策略删掉后，
 * seq 会从头开始，所有游标（{@code lastReadSeq}）随之失效，表现是"永远读不到新消息"，而且**不报错**。
 *
 * <p><b>写序：先 bump 再 append</b>。崩在两步之间只留一个**空洞**（安全）；
 * 反过来会产生**重号**（同一 seq 两条记录）——游标按 seq 比较时会漏读，且事后查不出。
 *
 * <p><b>seq 与 append 在同一临界区</b>（群组那条老教训）：这里虽是单写者（server thread），
 * 但搜索/宿主可能来自其它线程读，临界区保证读到的行数与 seq 一致。
 */
public final class ChatLog {

    /** 保留策略：默认 30 天（裁决 4）。 */
    public static final int DEFAULT_RETENTION_DAYS = 30;

    private final Path root;
    private final ZoneId zone;
    private final int retentionDays;
    private final Clock clock;
    private final Object lock = new Object();

    private long lastSeq;

    public ChatLog(Path root, ZoneId zone, int retentionDays) {
        this(root, zone, retentionDays, Clock.system(zone));
    }

    /** 测试用：注入可控时钟。 */
    public ChatLog(Path root, ZoneId zone, int retentionDays, Clock clock) {
        if (root == null) {
            throw new IllegalArgumentException("需要存储根目录（不允许隐式落到当前目录）");
        }
        if (retentionDays <= 0) {
            throw new IllegalArgumentException("保留天数必须为正（0 天等于不存）");
        }
        this.root = root;
        this.zone = zone == null ? ZoneId.systemDefault() : zone;
        this.retentionDays = retentionDays;
        this.clock = clock == null ? Clock.system(this.zone) : clock;
        this.lastSeq = loadLastSeq();
    }

    public Path root() {
        return root;
    }

    public ZoneId zone() {
        return zone;
    }

    public int retentionDays() {
        return retentionDays;
    }

    public long lastSeq() {
        synchronized (lock) {
            return lastSeq;
        }
    }

    /** 某天的文件（不存在也算路径）。 */
    public Path fileFor(LocalDate day) {
        return root.resolve(day + ".jsonl");
    }

    /**
     * 追加一条记录。
     *
     * @return 落盘后的记录（seq 已分配）
     */
    public ChatRecord append(String source, String name, String text, String dim) {
        synchronized (lock) {
            long seq = lastSeq + 1;
            long epoch = clock.millis();
            ChatRecord r = new ChatRecord(seq, epoch, source, name, text, dim);
            try {
                Files.createDirectories(root);
                writeState(seq);                       // 先 bump：崩在这里只留空洞
                lastSeq = seq;
                Files.writeString(fileFor(dayOf(epoch)), Json.dump(r.toMap()) + "\n",
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                return r;
            } catch (IOException e) {
                throw new UncheckedIOException("聊天记录落盘失败: " + e.getMessage(), e);
            }
        }
    }

    /** 读某一天的全部记录（坏行跳过但不补）。 */
    public List<ChatRecord> readDay(LocalDate day) {
        return read(day, Long.MIN_VALUE);
    }

    /** 读某一天里 {@code seq > afterSeq} 的记录。 */
    public List<ChatRecord> read(LocalDate day, long afterSeq) {
        Path f = fileFor(day);
        if (!Files.isRegularFile(f)) {
            return List.of();
        }
        List<ChatRecord> out = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                ChatRecord r = parseLine(line);
                if (r != null && r.seq() > afterSeq) {
                    out.add(r);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("读聊天记录失败: " + f + " (" + e.getMessage() + ")", e);
        }
        out.sort(Comparator.comparingLong(ChatRecord::seq));
        return out;
    }

    /**
     * 取 {@code seq > afterSeq} 的玩家来源记录，最多 {@code limit} 条。
     *
     * <p>跨天也成立：从"该 seq 所在那天"开始往后读（最多读 {@code retentionDays} 天），
     * 否则玩家隔夜说的话会被漏掉（只读今天就会漏昨天 23:59 之后的）。
     */
    public List<ChatRecord> readPlayerAfter(long afterSeq, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        List<ChatRecord> out = new ArrayList<>();
        LocalDate today = today();
        for (int back = retentionDays - 1; back >= 0 && out.size() < limit; back--) {
            for (ChatRecord r : read(today.minusDays(back), afterSeq)) {
                if (r.fromPlayer()) {
                    out.add(r);
                    if (out.size() >= limit) {
                        break;
                    }
                }
            }
        }
        return out;
    }

    /**
     * 数 {@code seq > afterSeq} 的**玩家来源**条数（进摘要行用，不需要正文）。
     *
     * <p>只读"游标所在天起"的几天，不扫全历史：摘要行每轮都要拼，扫全库会把 tick 拖死。
     */
    public int countPlayerAfter(long afterSeq) {
        int n = 0;
        LocalDate today = today();
        for (int back = retentionDays - 1; back >= 0; back--) {
            for (ChatRecord r : read(today.minusDays(back), afterSeq)) {
                if (r.fromPlayer()) {
                    n++;
                }
            }
        }
        return n;
    }

    /** 搜索结果（三个数缺一不可：只回 matched 会把"没搜到"和"没搜完"混成一件事）。 */
    public record SearchResult(int matched, int scanned, boolean truncated, List<ChatRecord> hits) {
    }

    /**
     * 按天 + 关键词 + 时间窗 + 来源过滤。
     *
     * @param day      指定天（{@code null} = 今天）
     * @param keywords 空格分词后**全部命中**（AND），大小写不敏感；空 = 不过滤
     * @param since    起始 epoch（含），{@code null} = 不限
     * @param until    截止 epoch（含），{@code null} = 不限
     * @param source   {@code PLAYER}/{@code MODEL}，{@code null} = 全部
     * @param limit    命中上限
     */
    public SearchResult search(LocalDate day, List<String> keywords, Long since, Long until,
                               String source, int limit) {
        LocalDate target = day == null ? today() : day;
        Path f = fileFor(target);
        if (!Files.isRegularFile(f)) {
            return new SearchResult(0, 0, false, List.of());
        }
        List<String> keys = new ArrayList<>();
        if (keywords != null) {
            for (String k : keywords) {
                if (k != null && !k.isBlank()) {
                    keys.add(k.toLowerCase(Locale.ROOT));
                }
            }
        }
        int scanned = 0;
        boolean truncated = false;
        List<ChatRecord> hits = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                scanned++;
                if (scanned > MAX_SCAN_LINES) {
                    truncated = true;
                    break;
                }
                ChatRecord r = parseLine(line);
                if (r == null) {
                    continue;
                }
                if (source != null && !source.isBlank() && !source.equalsIgnoreCase(r.source())) {
                    continue;
                }
                if (since != null && r.epoch() < since) {
                    continue;
                }
                if (until != null && r.epoch() > until) {
                    continue;
                }
                String hay = (r.text() + " " + r.name()).toLowerCase(Locale.ROOT);
                boolean all = true;
                for (String k : keys) {
                    if (!hay.contains(k)) {
                        all = false;
                        break;
                    }
                }
                if (!all) {
                    continue;
                }
                hits.add(r);
                if (hits.size() >= limit) {
                    truncated = scanned < lineCount(f);
                    break;
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("搜索聊天记录失败: " + f + " (" + e.getMessage() + ")", e);
        }
        return new SearchResult(hits.size(), scanned, truncated, hits);
    }

    /** 单次扫描行数上限（防拖死工具线程）。 */
    public static final int MAX_SCAN_LINES = 200_000;

    /**
     * 清理超过保留期的天文件。
     *
     * @return 删掉的文件数
     */
    public int prune() {
        LocalDate cutoff = today().minusDays(retentionDays);
        int removed = 0;
        if (!Files.isDirectory(root)) {
            return 0;
        }
        try (var list = Files.list(root)) {
            for (Path p : list.toList()) {
                String name = p.getFileName().toString();
                if (!name.endsWith(".jsonl")) {
                    continue;
                }
                LocalDate day;
                try {
                    day = LocalDate.parse(name.substring(0, name.length() - ".jsonl".length()));
                } catch (RuntimeException ignored) {
                    continue;   // 不是我们的命名，别动
                }
                if (day.isBefore(cutoff) && Files.deleteIfExists(p)) {
                    removed++;
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("清理聊天记录失败: " + e.getMessage(), e);
        }
        return removed;
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    // ------------------------------------------------------------- 内部

    private LocalDate dayOf(long epoch) {
        return Instant.ofEpochMilli(epoch).atZone(zone).toLocalDate();
    }

    private Path stateFile() {
        return root.resolve("state.json");
    }

    private long loadLastSeq() {
        Path f = stateFile();
        if (!Files.isRegularFile(f)) {
            return 0L;
        }
        try {
            Map<String, Object> m = Json.parseObject(Files.readString(f, StandardCharsets.UTF_8));
            Object v = m.get("lastSeq");
            return v instanceof Number n ? n.longValue() : 0L;
        } catch (IOException e) {
            throw new UncheckedIOException("读聊天 seq 状态失败: " + e.getMessage(), e);
        }
    }

    private void writeState(long seq) throws IOException {
        Files.writeString(stateFile(), Json.dump(Map.of("lastSeq", seq)),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    /**
     * 解析一行；坏行返回 {@code null}。
     *
     * <p>聊天文件是 append-only 日志：一行损坏（半个写入、外部编辑）不该让整次注入/搜索失败——
     * 那会把"一条坏数据"升级成"聊天功能不可用"。
     */
    private static ChatRecord parseLine(String line) {
        try {
            return ChatRecord.fromMap(Json.parseObject(line));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static int lineCount(Path f) {
        try {
            return (int) Files.lines(f, StandardCharsets.UTF_8).filter(s -> !s.isBlank()).count();
        } catch (IOException e) {
            return -1;
        }
    }
}
