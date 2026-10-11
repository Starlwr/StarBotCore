package com.starlwr.bot.core.util;

import org.apache.commons.collections4.MultiSet;
import org.apache.commons.collections4.multiset.HashMultiSet;

import java.util.*;

/**
 * 固定大小的集合队列(滑动窗口)
 * @param <T> 元素类型
 */
public class FixedSizeSetQueue<T> {
    private final int capacity;
    private final Deque<T> queue = new ArrayDeque<>();
    private final MultiSet<T> elements = new HashMultiSet<>();

    public FixedSizeSetQueue(int capacity) {
        this.capacity = capacity;
    }

    /**
     * 向容器添加元素
     * @param element 元素
     * @return 是否添加成功
     */
    public synchronized boolean add(T element) {
        if (queue.size() >= capacity) {
            T oldest = queue.pollFirst();
            elements.remove(oldest);
        }

        queue.offerLast(element);
        elements.add(element);

        return true;
    }

    /**
     * 向容器批量添加元素
     * @param elements 元素集合
     * @return 是否添加成功
     */
    public synchronized boolean addAll(Collection<? extends T> elements) {
        for (T element : elements) {
            add(element);
        }

        return true;
    }

    /**
     * 检查容器中是否包含指定元素
     * @param element 元素
     * @return 容器中是否存在该元素
     */
    public boolean contains(T element) {
        return elements.contains(element);
    }

    /**
     * 获取容器中的元素数量
     * @return 容器中的元素数量
     */
    public int size() {
        return queue.size();
    }

    /**
     * 清空容器
     */
    public synchronized void clear() {
        queue.clear();
        elements.clear();
    }

    @Override
    public String toString() {
        return queue.toString();
    }
}
