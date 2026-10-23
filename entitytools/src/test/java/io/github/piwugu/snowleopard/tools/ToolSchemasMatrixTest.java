package io.github.piwugu.snowleopard.tools;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import snowleopard.core.registry.ToolRegistry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 权限矩阵逐工具核对（M7）——危险动作的 {@code reversible/state} 标注与审批链说明是
 * 权限门禁/审批链的输入（接核心权限表），改标注=改政策，本测试即政策的可执行快照。
 */
class ToolSchemasMatrixTest {

    /** 危险动作矩阵（reversible/state 的期望值——与核心门禁消费口径一致）。 */
    private static final Map<String, String[]> DANGEROUS = Map.of(
            "attack", new String[]{"false", "changes_world"},
            "use", new String[]{"depends", "changes_world"},
            "place", new String[]{"false", "changes_world"},
            "break", new String[]{"false", "changes_world"},
            "move", new String[]{"true", "changes_world"},
            "goto", new String[]{"true", "changes_world"},
            "move_item", new String[]{"true", "changes_world"},
            "drop", new String[]{"false", "changes_world"});

    private static List<ToolRegistry.Tool> all() {
        List<ToolRegistry.Tool> all = new java.util.ArrayList<>();
        all.addAll(ToolSchemas.SENSE_TOOLS);
        all.addAll(ToolSchemas.ENTITY_TOOLS);
        all.addAll(ToolSchemas.SELF_TOOLS);
        all.addAll(ToolSchemas.VIEW_TOOLS);
        all.addAll(ToolSchemas.INTERACT_TOOLS);
        all.addAll(ToolSchemas.MOVE_TOOLS);
        all.addAll(ToolSchemas.ITEM_TOOLS);
        all.addAll(ToolSchemas.WORLD_TOOLS);
        all.addAll(ToolSchemas.WATCH_TOOLS);
        all.addAll(ToolSchemas.CHAT_TOOLS);
        all.addAll(ToolSchemas.AVATAR_TOOLS);
        all.addAll(ToolSchemas.CRAFT_TOOLS);
        return all;
    }

    @Test
    void tenGroupsFrozen() {
        assertEquals(12, GroupNames.ALL.size(), "十二组名冻结（组名不冒号）");
        assertEquals(41, all().size(),
                "工具面 12 组 41 工具（craft 新增 2 个）——超差即清单漂移，须同步接入文档");
    }

    @Test
    void everyDangerousToolCarriesReversibleState() {
        for (ToolRegistry.Tool t : all()) {
            if (!(t.params() instanceof Map<?, ?> p)) {
                continue;
            }
            boolean claimsWorld = "changes_world".equals(p.get("state"));
            boolean hasRev = p.containsKey("reversible");
            if (claimsWorld) {
                assertTrue(hasRev, t.name() + " 标了 changes_world 必须给 reversible");
            }
            String[] exp = DANGEROUS.get(t.name());
            if (exp != null) {
                assertEquals(exp[0], String.valueOf(p.get("reversible")),
                        t.name() + " reversible 口径（政策快照）");
                assertEquals(exp[1], String.valueOf(p.get("state")),
                        t.name() + " state 口径（政策快照）");
            }
        }
        // 矩阵覆盖对账：每个危险条目都真实存在于清单
        for (String name : DANGEROUS.keySet()) {
            assertTrue(all().stream().anyMatch(t -> t.name().equals(name)),
                    "矩阵条目 " + name + " 在清单中不存在——矩阵与清单漂移");
        }
    }

    @Test
    void attackHasApprovalChainDoc() {
        ToolRegistry.Tool attack = ToolSchemas.INTERACT_TOOLS.stream()
                .filter(t -> ToolSchemas.INTERACT_ATTACK.equals(t.name()))
                .findFirst().orElseThrow();
        assertTrue(attack.params() instanceof Map<?, ?> p
                        && p.containsKey("confirm_token") && p.containsKey("审批"),
                "attack 必须带两段式 confirm_token 与审批说明（攻击真人默认审批）");
    }

    @Test
    void readOnlyGroupsHaveNoWorldClaims() {
        List<ToolRegistry.Tool> readOnly = new java.util.ArrayList<>();
        readOnly.addAll(ToolSchemas.SENSE_TOOLS);
        readOnly.addAll(ToolSchemas.ENTITY_TOOLS);
        readOnly.addAll(ToolSchemas.SELF_TOOLS);
        readOnly.addAll(ToolSchemas.WORLD_TOOLS);
        readOnly.addAll(ToolSchemas.WATCH_TOOLS);
        for (ToolRegistry.Tool t : readOnly) {
            assertNotNull(t.name());
            if (t.params() instanceof Map<?, ?> p) {
                assertTrue(!"changes_world".equals(p.get("state")),
                        t.name() + " 是查询/开关面，不该标 changes_world");
            }
        }
    }
}
