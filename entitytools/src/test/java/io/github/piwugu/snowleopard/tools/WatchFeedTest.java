package io.github.piwugu.snowleopard.tools;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import snowleopard.core.result.Result;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** WatchFeed 契约测试（M6）：保留窗口/controller 归属/轨迹限频/枚举写死。 */
class WatchFeedTest {

    @Test
    void watchUnwatchAndListCarryController() {
        WatchFeed f = new WatchFeed();
        Result w1 = f.watch("ent-1", "model-a");
        Result w2 = f.watch("ent-2", "");          // 真人=空 controller
        assertTrue(w1 instanceof Result.Ok && w2 instanceof Result.Ok);
        List<Map<String, Object>> list = f.list();
        assertEquals(2, list.size());
        assertEquals("model-a", list.get(0).get("controller"));
        assertEquals("", list.get(1).get("controller"));

        // 幂等：重复 watch=WARN 不涨监视集
        Result again = f.watch("ent-1", "model-a");
        assertTrue(again instanceof Result.Warn);
        assertEquals(2, f.list().size());

        // 解锁后列表一致（历史仍在窗口内）
        f.unwatch("ent-1");
        assertEquals(1, f.list().size());
        assertFalse(f.isWatching("ent-1"));
    }

    @Test
    void everyEventCarriesController() {
        WatchFeed f = new WatchFeed();
        f.watch("ent-1", "model-a");
        f.record(WatchFeed.Kind.TRAJECTORY, "ent-1",
                Map.of("x", 1), 100);
        f.record(WatchFeed.Kind.ATTACKED, "ent-1",
                Map.of("attacker", "ent-9", "damage", 2.0f), 110);
        f.record(WatchFeed.Kind.DIED, "ent-1", Map.of(), 120);
        List<WatchFeed.Event> evs = f.events("ent-1", 0, 0);
        assertEquals(3, evs.size());
        for (WatchFeed.Event e : evs) {
            assertEquals("model-a", e.controller());
            assertTrue(e.toMap().containsKey("tick"));
            assertTrue(e.toMap().containsKey("kind"));
        }
        assertEquals(WatchFeed.Kind.ATTACKED, evs.get(1).kind());
    }

    @Test
    void recordOnlyForWatchedEntities() {
        WatchFeed f = new WatchFeed();
        f.record(WatchFeed.Kind.TRAJECTORY, "ghost", Map.of(), 10);
        assertTrue(f.events("ghost", 0, 0).isEmpty(), "未监视实体不产生动态");
    }

    @Test
    void retentionWindowByCount() {
        WatchFeed f = new WatchFeed();
        f.watch("ent-1", "m");
        for (int i = 0; i < WatchFeed.MAX_EVENTS_PER_ENTITY + 30; i++) {
            f.record(WatchFeed.Kind.TRAJECTORY, "ent-1", Map.of("i", i), i);
        }
        List<WatchFeed.Event> evs = f.events("ent-1", 0, 0);
        assertEquals(WatchFeed.MAX_EVENTS_PER_ENTITY, evs.size(), "条数上限=淘汰最旧");
        // 淘汰的是最旧的：最早事件从 tick=30 起
        assertEquals(30, evs.get(0).tick());
    }

    @Test
    void retentionWindowByAge() {
        WatchFeed f = new WatchFeed();
        f.watch("ent-1", "m");
        f.record(WatchFeed.Kind.TRAJECTORY, "ent-1", Map.of(), 100);
        f.record(WatchFeed.Kind.TRAJECTORY, "ent-1", Map.of(),
                100 + WatchFeed.MAX_AGE_TICKS + 5);   // 该时刻：100 超窗（差 1205>1200）被淘汰
        List<WatchFeed.Event> evs = f.events("ent-1", 0, 0);
        assertEquals(1, evs.size(), "时间窗淘汰：只留窗内");
        assertEquals(100 + WatchFeed.MAX_AGE_TICKS + 5, evs.get(0).tick());
    }

    @Test
    void trajectorySamplingRateLimited() {
        WatchFeed f = new WatchFeed();
        f.watch("ent-1", "m");
        assertTrue(f.shouldSample("ent-1", 100), "首采放行");
        f.record(WatchFeed.Kind.TRAJECTORY, "ent-1", Map.of(), 100);
        assertFalse(f.shouldSample("ent-1", 100 + WatchFeed.SAMPLE_INTERVAL_TICKS - 1),
                "限频期内拒绝");
        assertTrue(f.shouldSample("ent-1", 100 + WatchFeed.SAMPLE_INTERVAL_TICKS),
                "限频期满放行");
    }

    @Test
    void queryLimitAndSinceTick() {
        WatchFeed f = new WatchFeed();
        f.watch("ent-1", "m");
        for (int i = 0; i < 10; i++) {
            f.record(WatchFeed.Kind.TRAJECTORY, "ent-1", Map.of("i", i), i * 10);
        }
        List<WatchFeed.Event> lim = f.events("ent-1", 3, 0);
        assertEquals(3, lim.size(), "limit 截断");
        assertEquals(70, lim.get(0).tick(), "留最近的");
        List<WatchFeed.Event> since = f.events("ent-1", 0, 55);
        assertEquals(4, since.size(), "sinceTick 只取其后（含）：60/70/80/90");
        assertEquals(60, since.get(0).tick());
    }

    @Test
    void mixedControllersVisibleInGlobalQuery() {
        WatchFeed f = new WatchFeed();
        f.watch("bound", "model-a");       // 绑定实体
        f.watch("player", "");             // 真人
        f.record(WatchFeed.Kind.TRAJECTORY, "bound", Map.of(), 50);
        f.record(WatchFeed.Kind.TRAJECTORY, "player", Map.of(), 55);
        List<WatchFeed.Event> all = f.events(null, 0, 0);
        assertEquals(2, all.size());
        assertEquals("model-a", all.get(0).controller(), "绑定实体=控制模型编号");
        assertEquals("", all.get(1).controller(), "真人=空");
    }

    @Test
    void kindEnumIsClosed() {
        assertEquals(3, WatchFeed.Kind.values().length,
                "事件集枚举写死：TRAJECTORY/ATTACKED/DIED（改=契约迁移）");
    }
}
