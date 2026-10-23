package io.github.piwugu.snowleopard.tools.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link ChatLog} 的 headless 验收（M12-1，对应分析 §十 的 ①②⑥）。
 *
 * <p>全部在临时目录里跑，不碰 MC、不碰真实存档——聊天存储是纯文件逻辑，
 * 没有理由等到真机才发现"跨天写错文件"。
 */
class ChatLogTest {

    private static final ZoneId ZONE = ZoneOffset.ofHours(8);

    /** 可拨动的时钟：跨天/时间窗断言必须能"造过去"。 */
    private static final class MovableClock extends Clock {
        private Instant now;

        MovableClock(Instant start) {
            this.now = start;
        }

        void set(Instant t) {
            this.now = t;
        }

        @Override
        public ZoneId getZone() {
            return ZONE;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static Instant at(int day, int hour, int minute, int second) {
        return LocalDate.of(2026, 10, day).atTime(hour, minute, second).toInstant(ZoneOffset.ofHours(8));
    }

    @Test
    void seq递增且跨实例不回退(@TempDir Path dir) {
        ChatLog log1 = new ChatLog(dir, ZONE, 30);
        assertEquals(1, log1.append(ChatRecord.PLAYER, "a", "第一条", "").seq());
        assertEquals(2, log1.append(ChatRecord.PLAYER, "a", "第二条", "").seq());

        // 新实例（模拟重启）：seq 必须从盘上接上，不能从 1 重来
        ChatLog log2 = new ChatLog(dir, ZONE, 30);
        assertEquals(3, log2.append(ChatRecord.PLAYER, "b", "第三条", "").seq());
        assertEquals(3, log2.lastSeq());
    }

    @Test
    void 跨天写进各自文件(@TempDir Path dir) {
        MovableClock clock = new MovableClock(at(23, 23, 59, 0));
        ChatLog log = new ChatLog(dir, ZONE, 30, clock);
        log.append(ChatRecord.PLAYER, "a", "昨天最后一句", "");
        clock.set(at(24, 0, 0, 30));
        log.append(ChatRecord.PLAYER, "a", "今天第一句", "");

        assertTrue(Files.isRegularFile(dir.resolve("2026-10-23.jsonl")));
        assertTrue(Files.isRegularFile(dir.resolve("2026-10-24.jsonl")));
        assertEquals(1, log.readDay(LocalDate.of(2026, 10, 23)).size());
        assertEquals(1, log.readDay(LocalDate.of(2026, 10, 24)).size());
    }

    @Test
    void readPlayerAfter跨天不漏(@TempDir Path dir) {
        MovableClock clock = new MovableClock(at(23, 23, 50, 0));
        ChatLog log = new ChatLog(dir, ZONE, 30, clock);
        log.append(ChatRecord.PLAYER, "a", "昨天", "");
        long mark = log.lastSeq();
        clock.set(at(24, 0, 10, 0));
        log.append(ChatRecord.PLAYER, "a", "今天", "");
        log.append(ChatRecord.MODEL, "m", "模型说的", "");

        List<ChatRecord> after = log.readPlayerAfter(mark, 10);
        assertEquals(1, after.size(), "跨天只能取到玩家来源的那一条");
        assertEquals("今天", after.get(0).text());
    }

    @Test
    void 坏行跳过但不炸(@TempDir Path dir) throws IOException {
        ChatLog log = new ChatLog(dir, ZONE, 30);
        log.append(ChatRecord.PLAYER, "a", "好的", "");
        Files.writeString(dir.resolve(log.today() + ".jsonl"), "{这不是 json\n",
                StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
        assertEquals(1, log.readDay(log.today()).size());
        assertNull(ChatRecord.fromMap(snowleopard.core.json.Json.parseObject("{\"seq\":0}")),
                "seq<=0 视为无效记录（0 = 还没写过）");
    }

    @Test
    void 保留策略只删过期的天文件(@TempDir Path dir) throws IOException {
        MovableClock clock = new MovableClock(at(24, 12, 0, 0));
        ChatLog log = new ChatLog(dir, ZONE, 30, clock);
        // 30 天保留 → cutoff = 2026-09-24；用 10-01 作为"仍在保留期内"的样本
        for (String day : List.of("2026-08-01", "2026-10-01", "2026-10-24")) {
            Files.writeString(dir.resolve(day + ".jsonl"), "{\"seq\":1}\n", StandardCharsets.UTF_8);
        }
        Files.writeString(dir.resolve("notes.txt"), "不是我们的文件", StandardCharsets.UTF_8);

        int removed = log.prune();

        assertEquals(1, removed, "只有 2026-08-01 早于 30 天前（2026-10-01 还在窗口内）");
        assertFalse(Files.exists(dir.resolve("2026-08-01.jsonl")));
        assertTrue(Files.exists(dir.resolve("2026-10-01.jsonl")));
        assertTrue(Files.exists(dir.resolve("2026-10-24.jsonl")));
        assertTrue(Files.exists(dir.resolve("notes.txt")), "非 .jsonl 文件一律不动");
    }

    @Test
    void 搜索_按天_多词AND_大小写_时间窗_来源(@TempDir Path dir) {
        MovableClock clock = new MovableClock(at(24, 10, 0, 0));
        ChatLog log = new ChatLog(dir, ZONE, 30, clock);
        log.append(ChatRecord.PLAYER, "Steve", "帮我挖石头", "");
        clock.set(at(24, 10, 5, 0));
        log.append(ChatRecord.PLAYER, "Steve", "把门口的树砍了", "");
        clock.set(at(24, 10, 9, 0));
        log.append(ChatRecord.MODEL, "m1", "好的 Steve 我去挖石头", "");

        // 多词 AND：只有第三条同时含"挖"和"石头"，但它是 MODEL 来源
        ChatLog.SearchResult both = log.search(log.today(), List.of("挖", "石头"), null, null, null, 10);
        assertEquals(2, both.matched());
        // 只搜玩家来源
        ChatLog.SearchResult playerOnly = log.search(log.today(), List.of("挖", "石头"), null, null,
                ChatRecord.PLAYER, 10);
        assertEquals(1, playerOnly.matched());
        assertEquals("帮我挖石头", playerOnly.hits().get(0).text());

        // 大小写不敏感（玩家名也参与匹配）
        assertEquals(3, log.search(log.today(), List.of("steve"), null, null, null, 10).matched());

        // 时间窗（含边界）
        long from = at(24, 10, 5, 0).toEpochMilli();
        long to = at(24, 10, 9, 0).toEpochMilli();
        assertEquals(2, log.search(log.today(), List.of(), from, to, null, 10).matched());

        // 未命中也要回 scanned（"没搜到"与"没搜完"是两件事）
        ChatLog.SearchResult miss = log.search(log.today(), List.of("不存在"), null, null, null, 10);
        assertEquals(0, miss.matched());
        assertTrue(miss.scanned() > 0);
        assertFalse(miss.truncated());

        // 指定天没有文件 → 0/0/false（不炸）
        ChatLog.SearchResult other = log.search(LocalDate.of(2020, 1, 1), List.of(), null, null, null, 10);
        assertEquals(0, other.matched());
        assertEquals(0, other.scanned());
    }

    @Test
    void 命中上限时置truncated(@TempDir Path dir) {
        ChatLog log = new ChatLog(dir, ZONE, 30);
        for (int i = 0; i < 5; i++) {
            log.append(ChatRecord.PLAYER, "a", "相同的句子 " + i, "");
        }
        ChatLog.SearchResult r = log.search(log.today(), List.of("相同"), null, null, null, 2);
        assertEquals(2, r.matched());
        assertTrue(r.truncated(), "还有没看完的行，必须显式告诉调用方");
    }

    @Test
    void 原文保真_换行不被存储层清洗(@TempDir Path dir) {
        ChatLog log = new ChatLog(dir, ZONE, 30);
        log.append(ChatRecord.PLAYER, "a", "第一行\n第二行", "");
        assertEquals("第一行\n第二行", log.readDay(log.today()).get(0).text(),
                "存储保真：单行化只发生在渲染注入块时");
    }
}
