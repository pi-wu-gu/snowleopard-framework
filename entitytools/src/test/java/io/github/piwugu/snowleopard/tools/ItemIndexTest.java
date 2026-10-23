package io.github.piwugu.snowleopard.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * 双索引检索契约（M5，2026-10-23 14:35 (UTC+8)）：registry 与显示名任一命中。
 */
class ItemIndexTest {

    private final ItemIndex index = new ItemIndex(List.of(
            new ItemIndex.Entry("0", "minecraft:stone", "Stone", 10),
            new ItemIndex.Entry("self:mainhand", "minecraft:dirt", "泥土", 5)));

    @Test
    void registryAndDisplayBothHit() {
        assertEquals(1, index.search("minecraft:stone").size(), "registry 命中");
        assertEquals(1, index.search("stone").size(), "registry 子串命中");
        assertEquals(1, index.search("泥土").size(), "本地化显示名命中");
        assertEquals(1, index.search("dirt").size(), "同一物件的 registry 命中（双索引同归一件）");
    }

    @Test
    void caseInsensitiveAndMiss() {
        assertEquals(1, index.search("STONE").size(), "忽略大小写");
        assertEquals(0, index.search("diamond").size(), "不命中=空");
        assertEquals(2, index.search("").size(), "空查询=全部");
    }

    @Test
    void entryCounts() {
        assertEquals(10, index.search("stone").get(0).count());
        assertTrue(index.search("泥土").get(0).slot().equals("self:mainhand"));
    }
}
