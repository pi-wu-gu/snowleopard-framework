package io.github.piwugu.snowleopard.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 物品双索引检索（M5，2026-10-23 14:35 (UTC+8)）——纯 Java 可测。
 *
 * <p>裁决口径："多语言检索"降级为**双索引**：registry name（语言无关、模型用）+
 * 显示名（本地化、人读）。任一命中即收；查询忽略大小写、子串匹配。
 */
public final class ItemIndex {

    /** 一件物品条目。slot=槽位标识（容器槽号或 self:mainhand 等）。 */
    public record Entry(String slot, String registry, String display, int count) {
    }

    private final List<Entry> entries;

    public ItemIndex(List<Entry> entries) {
        this.entries = List.copyOf(entries);
    }

    public List<Entry> entries() {
        return entries;
    }

    /** 检索：空查询=全部；registry 或 display 含查询串（忽略大小写）即命中。 */
    public List<Entry> search(String query) {
        if (query == null || query.isBlank()) {
            return entries;
        }
        String q = query.toLowerCase(Locale.ROOT);
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries) {
            if (e.registry().toLowerCase(Locale.ROOT).contains(q)
                    || e.display().toLowerCase(Locale.ROOT).contains(q)) {
                out.add(e);
            }
        }
        return out;
    }
}
