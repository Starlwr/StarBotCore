package com.starlwr.bot.core.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 固定大小集合队列测试
 */
class FixedSizeSetQueueTest {
    /**
     * 验证移除重复元素的旧副本后，窗口内剩余副本仍可被查询
     */
    @Test
    void keepsDuplicateUntilItsLastOccurrenceLeavesWindow() {
        FixedSizeSetQueue<String> queue = new FixedSizeSetQueue<>(3);
        queue.addAll(List.of("a", "a", "b"));

        queue.add("c");
        assertTrue(queue.contains("a"));
        assertEquals(3, queue.size());
        assertEquals("[a, b, c]", queue.toString());

        queue.add("d");
        assertFalse(queue.contains("a"));
        assertTrue(queue.contains("b"));
        assertEquals("[b, c, d]", queue.toString());
    }

    /**
     * 验证批量添加超出窗口容量时仅保留最后的元素
     */
    @Test
    void batchInsertionKeepsMostRecentElements() {
        FixedSizeSetQueue<Integer> queue = new FixedSizeSetQueue<>(2);

        assertTrue(queue.addAll(List.of(1, 2, 3, 4)));

        assertEquals(2, queue.size());
        assertFalse(queue.contains(1));
        assertFalse(queue.contains(2));
        assertTrue(queue.contains(3));
        assertTrue(queue.contains(4));
    }

    /**
     * 验证清空时同步清除窗口和重复计数，并支持重新使用
     */
    @Test
    void clearResetsWindowAndOccurrenceCounts() {
        FixedSizeSetQueue<String> queue = new FixedSizeSetQueue<>(2);
        queue.addAll(List.of("a", "a"));

        queue.clear();

        assertEquals(0, queue.size());
        assertFalse(queue.contains("a"));
        queue.add("b");
        assertEquals(1, queue.size());
        assertTrue(queue.contains("b"));
        assertEquals("[b]", queue.toString());
    }
}
