package io.github.piwugu.snowleopard.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import snowleopard.core.result.Result;

/**
 * 六条结束路径的可执行规格（M2，2026-10-23 12:50 (UTC+8)）。
 */
class LookLockMachineTest {

    @Test
    void path1ExplicitUnlock() {
        LookLockMachine m = new LookLockMachine();
        m.start(LookLockMachine.Kind.BLOCK, "0,64,0", 0);
        Result r = m.unlock();
        assertTrue(r instanceof Result.Ok);
        assertTrue(String.valueOf(r).contains("EXPLICIT_UNLOCK"));
        assertEquals(LookLockMachine.End.EXPLICIT_UNLOCK, m.lastEnd());
        assertTrue(m.unlock() instanceof Result.Warn, "重复解锁=WARN 幂等");
    }

    @Test
    void path2BlockGone() {
        LookLockMachine m = new LookLockMachine();
        m.start(LookLockMachine.Kind.BLOCK, "0,64,0", 0);
        Result r = m.tick(false, 5, false);
        assertTrue(String.valueOf(r).contains("BLOCK_GONE"), String.valueOf(r));
        assertEquals(LookLockMachine.End.BLOCK_GONE, m.lastEnd());
    }

    @Test
    void path3EntityGone() {
        LookLockMachine m = new LookLockMachine();
        m.start(LookLockMachine.Kind.ENTITY, "e1", 0);
        Result r = m.tick(false, 5, false);
        assertTrue(String.valueOf(r).contains("ENTITY_GONE"), String.valueOf(r));
    }

    @Test
    void path4TooFar() {
        LookLockMachine m = new LookLockMachine();
        m.start(LookLockMachine.Kind.ENTITY, "e1", 0);
        Result r = m.tick(true, 64.5, false);
        assertTrue(String.valueOf(r).contains("TOO_FAR"), String.valueOf(r));
    }

    @Test
    void path5Timeout() {
        LookLockMachine m = new LookLockMachine();
        m.start(LookLockMachine.Kind.BLOCK, "0,64,0", 2);
        Result first = m.tick(true, 5, false);
        assertTrue(String.valueOf(first).contains("locked=true"), "第 1 tick 仍在: " + first);
        Result r = m.tick(true, 5, false);
        assertTrue(String.valueOf(r).contains("TIMEOUT"), String.valueOf(r));
    }

    @Test
    void path6ExternalControl() {
        LookLockMachine m = new LookLockMachine();
        m.start(LookLockMachine.Kind.BLOCK, "0,64,0", 0);
        Result r = m.tick(true, 5, true);
        assertTrue(String.valueOf(r).contains("EXTERNAL_CONTROL"), String.valueOf(r));
    }

    @Test
    void unlimitedDurationStays() {
        LookLockMachine m = new LookLockMachine();
        m.start(LookLockMachine.Kind.BLOCK, "0,64,0", 0);
        for (int i = 0; i < 10; i++) {
            assertTrue(String.valueOf(m.tick(true, 5, false)).contains("locked=true"));
        }
        assertEquals(10, m.age());
    }
}
