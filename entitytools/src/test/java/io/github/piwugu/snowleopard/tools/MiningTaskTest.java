package io.github.piwugu.snowleopard.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import snowleopard.core.result.Result;

/**
 * 挖掘任务契约（M3，2026-10-23 13:25 (UTC+8)）。
 */
class MiningTaskTest {

    @Test
    void progressReadyComplete() {
        MiningTask m = new MiningTask();
        assertTrue(m.start("0,64,0", 3, 100) instanceof Result.Ok);
        assertEquals(MiningTask.Outcome.PROGRESS, m.tick(false));
        assertEquals(MiningTask.Outcome.PROGRESS, m.tick(false));
        assertEquals(MiningTask.Outcome.READY, m.tick(false), "到时长=READY（MC 层执行移除）");
        assertEquals(MiningTask.Outcome.COMPLETED, m.tick(true), "方块真没了=完成（服务端事实）");
    }

    @Test
    void timeoutReports() {
        MiningTask m = new MiningTask();
        m.start("0,64,0", 50, 3);   // 硬方块：需求 50 tick，预算只有 3
        m.tick(false);
        m.tick(false);
        assertEquals(MiningTask.Outcome.TIMEOUT, m.tick(false));
        assertEquals(MiningTask.Outcome.TIMEOUT, m.outcome(), "超时报因（终态）");
    }

    @Test
    void otherMinedIsComplete() {
        MiningTask m = new MiningTask();
        m.start("0,64,0", 10, 100);
        assertEquals(MiningTask.Outcome.COMPLETED, m.tick(true), "他人提前挖掉=目标达成");
    }

    @Test
    void busyGuard() {
        MiningTask m = new MiningTask();
        m.start("0,64,0", 10, 100);
        assertTrue(m.start("1,64,0", 5, 5) instanceof Result.Warn, "进行中拒新任务");
    }
}
