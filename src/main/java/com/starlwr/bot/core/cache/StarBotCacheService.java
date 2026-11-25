package com.starlwr.bot.core.cache;

import com.starlwr.bot.core.config.StarBotCoreProperties;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * StarBot 缓存服务
 */
@Component
public class StarBotCacheService {
    /**
     * 缓存工厂
     */
    private final StarBotCacheFactory factory;

    /**
     * 已注册的缓存映射
     */
    private final Map<String, RegisteredCache> caches = new ConcurrentHashMap<>();

    /**
     * 已注册的缓存实例
     */
    private static class RegisteredCache {
        /**
         * 缓存定义
         */
        private final StarBotCacheDefinition<?, ?> definition;

        /**
         * 缓存实例
         */
        private final StarBotCache<?, ?> cache;

        private RegisteredCache(StarBotCacheDefinition<?, ?> definition, StarBotCache<?, ?> cache) {
            this.definition = definition;
            this.cache = cache;
        }
    }

    @Autowired
    public StarBotCacheService(StarBotCoreProperties properties, List<StarBotCacheFactory> factories) {
        Map<String, StarBotCacheFactory> factoriesByType = new HashMap<>();
        for (StarBotCacheFactory candidate : factories) {
            String type = candidate.getType();
            if (StringUtils.isBlank(type)) {
                throw new IllegalArgumentException("缓存类型不能为空");
            }

            if (factoriesByType.putIfAbsent(type, candidate) != null) {
                throw new IllegalArgumentException("重复的缓存类型: " + type);
            }
        }

        String type = properties.getCache().getType();
        factory = factoriesByType.get(type);
        if (factory == null) {
            throw new IllegalStateException("无法加载缓存 " + type + ", 可用缓存类型: " + factoriesByType.keySet());
        }
    }

    /**
     * 根据缓存定义获取或创建缓存
     * @param definition 缓存定义
     * @return 缓存
     */
    @SuppressWarnings("unchecked")
    public <K, V> StarBotCache<K, V> getCache(@NonNull StarBotCacheDefinition<K, V> definition) {
        RegisteredCache registered = caches.computeIfAbsent(definition.getName(), name -> new RegisteredCache(definition, Objects.requireNonNull(factory.create(definition))));
        if (!registered.definition.equals(definition)) {
            throw new IllegalArgumentException("缓存定义冲突: " + definition.getName());
        }
        return (StarBotCache<K, V>) registered.cache;
    }
}
