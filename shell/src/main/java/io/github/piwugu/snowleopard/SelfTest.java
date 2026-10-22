package io.github.piwugu.snowleopard;

import java.util.List;

import io.github.piwugu.snowleopard.tools.EntityToolsProvider;
import io.github.piwugu.snowleopard.tools.GroupNames;
import snowleopard.core.SnowLeopard;

/**
 * 空场景自测（M0 测试设施，2026-10-23 05:30 (UTC+8)）——真机自测通道的第一块砖。
 *
 * <p>断言的是<b>装配意图</b>而非现状：核心已装配、九个冻结工具组骨架已按限定名注册。
 * 失败即 FAIL 日志（假成功比失败更坏）。M1 起在此追加感官/绑定场景断言；
 * 自动化（启动客户端等空场景跑）随 M1 的真机通道落地。
 */
final class SelfTest {

    private SelfTest() {
    }

    /** 空场景自测：返回 true=PASS（结果同时进日志）。 */
    static boolean run(SnowLeopard core) {
        boolean ok = true;
        if (core == null) {
            SnowLeopardMod.LOG.error("[雪豹框架][SELFTEST] FAIL: 核心未装配");
            return false;
        }
        List<String> fulls = List.copyOf(core.registry().names().fullNames());
        for (String g : GroupNames.ALL) {
            String full = "mod:snowleopard:" + g;
            boolean present = fulls.contains(full);
            if (!present) {
                ok = false;
                SnowLeopardMod.LOG.error("[雪豹框架][SELFTEST] FAIL: 工具组骨架缺失 {}", full);
            }
        }
        SnowLeopardMod.LOG.info("[雪豹框架][SELFTEST] {}（空场景：装配+{} 个组骨架）",
                ok ? "PASS" : "FAIL", GroupNames.ALL.size());
        return ok;
    }
}
