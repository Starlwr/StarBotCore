package com.starlwr.bot.core.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.NonNull;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * StarBot 内置缓存工厂
 */
@Component
public class StarBotCaffeineCacheFactory implements StarBotCacheFactory {
    @Override
    public String getType() {
        return "caffeine";
    }

    @Override
    public <K, V> StarBotCache<K, V> create(StarBotCacheDefinition<K, V> definition) {
        Caffeine<Object, Object> builder = Caffeine.newBuilder();
        Duration ttl = definition.getTtl();
        if (ttl != null) {
            builder.expireAfterWrite(ttl);
        }
        Long maximumSize = definition.getMaximumSize();
        if (maximumSize != null) {
            builder.maximumSize(maximumSize);
        }
        return new CaffeineCache<>(builder.build());
    }

    /**
     * Caffeine 缓存
     */
    private static class CaffeineCache<K, V> implements StarBotCache<K, V> {
        /**
         * 缓存
         */
        private final Cache<K, V> cache;

        private CaffeineCache(Cache<K, V> cache) {
            this.cache = cache;
        }

        @Override
        public Optional<V> get(@NonNull K key) {
            return Optional.ofNullable(cache.getIfPresent(key));
        }

        @Override
        public Map<K, V> getAll(@NonNull Collection<K> keys) {
            return cache.getAllPresent(keys);
        }

        @Override
        public void put(@NonNull K key, @NonNull V value) {
            cache.put(key, value);
        }

        @Override
        public void putAll(@NonNull Map<K, V> entries) {
            cache.putAll(entries);
        }

        @Override
        public boolean putIfAbsent(@NonNull K key, @NonNull V value) {
            return cache.asMap().putIfAbsent(key, value) == null;
        }

        @Override
        public void invalidate(@NonNull K key) {
            cache.invalidate(key);
        }
    }
}
