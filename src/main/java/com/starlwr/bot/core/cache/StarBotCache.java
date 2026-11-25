package com.starlwr.bot.core.cache;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * StarBot 缓存
 */
public interface StarBotCache<K, V> {
    /**
     * 获取缓存
     * @param key 缓存键
     * @return 缓存值，未命中时返回空
     */
    Optional<V> get(K key);

    /**
     * 批量获取缓存
     * @param keys 缓存键集合
     * @return 命中的缓存值映射
     */
    Map<K, V> getAll(Collection<K> keys);

    /**
     * 写入缓存并重新计算有效期
     * @param key 缓存键
     * @param value 缓存值
     */
    void put(K key, V value);

    /**
     * 批量写入缓存
     * @param entries 缓存值映射
     */
    void putAll(Map<K, V> entries);

    /**
     * 缓存键不存在时原子写入缓存
     * @param key 缓存键
     * @param value 缓存值
     * @return 是否写入缓存
     */
    boolean putIfAbsent(K key, V value);

    /**
     * 移除缓存
     * @param key 缓存键
     */
    void invalidate(K key);
}
