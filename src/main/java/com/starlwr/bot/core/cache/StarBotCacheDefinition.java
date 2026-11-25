package com.starlwr.bot.core.cache;

import jakarta.annotation.Nullable;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;

import java.time.Duration;

/**
 * StarBot 缓存定义
 */
@Getter
@EqualsAndHashCode
public final class StarBotCacheDefinition<K, V> {
    /**
     * 缓存名称
     */
    private final String name;

    /**
     * 缓存键类型
     */
    private final Class<K> keyType;

    /**
     * 缓存值类型
     */
    private final Class<V> valueType;

    /**
     * 有效期，未设置时不自动过期
     */
    @Nullable
    private final Duration ttl;

    /**
     * 缓存最大容量，未设置时不限容量
     */
    @Nullable
    private final Long maximumSize;

    /**
     * 缓存键序列化器
     */
    private final StarBotCacheSerializer<K> keySerializer;

    /**
     * 缓存值序列化器
     */
    private final StarBotCacheSerializer<V> valueSerializer;

    public StarBotCacheDefinition(@NonNull String name, @NonNull Class<K> keyType, @NonNull Class<V> valueType,
                                  @Nullable Duration ttl, @Nullable Long maximumSize,
                                  @NonNull StarBotCacheSerializer<K> keySerializer, @NonNull StarBotCacheSerializer<V> valueSerializer) {
        if (StringUtils.isBlank(name)) {
            throw new IllegalArgumentException("缓存名称不能为空");
        }

        if (ttl != null && (ttl.isZero() || ttl.isNegative())) {
            throw new IllegalArgumentException("缓存有效期必须大于 0");
        }

        if (maximumSize != null && maximumSize <= 0) {
            throw new IllegalArgumentException("缓存容量必须大于 0");
        }

        this.name = name;
        this.keyType = keyType;
        this.valueType = valueType;
        this.ttl = ttl;
        this.maximumSize = maximumSize;
        this.keySerializer = keySerializer;
        this.valueSerializer = valueSerializer;
    }
}
