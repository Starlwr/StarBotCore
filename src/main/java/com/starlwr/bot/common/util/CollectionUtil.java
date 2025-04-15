package com.starlwr.bot.common.util;

import org.springframework.util.CollectionUtils;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 集合工具类
 */
public class CollectionUtil {
    /**
     * 将集合拆分为多个子列表，每个子列表最多含有 size 个元素
     *
     * @param collection 原始集合
     * @param size 每个子列表的最大元素数量
     * @return 拆分后的子列表
     */
    public static <T> List<List<T>> splitCollection(Collection<T> collection, int size) {
        if (CollectionUtils.isEmpty(collection)) {
            return Collections.emptyList();
        }

        if (size <= 0) {
            throw new IllegalArgumentException("子列表的最大元素数量必须大于 0");
        }

        AtomicInteger counter = new AtomicInteger(0);
        return new ArrayList<>(collection.stream()
                .collect(Collectors.groupingBy(
                        item -> counter.getAndIncrement() / size,
                        LinkedHashMap::new,
                        Collectors.toList()
                ))
                .values());
    }
}
