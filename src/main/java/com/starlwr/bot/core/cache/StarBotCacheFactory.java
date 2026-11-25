package com.starlwr.bot.core.cache;

/**
 * StarBot 缓存工厂
 */
public interface StarBotCacheFactory {
    /**
     * 获取缓存类型
     * @return 缓存类型
     */
    String getType();

    /**
     * 根据缓存定义创建缓存
     * @param definition 缓存定义
     * @param <K> 缓存键类型
     * @param <V> 缓存值类型
     * @return 缓存
     */
    <K, V> StarBotCache<K, V> create(StarBotCacheDefinition<K, V> definition);
}
