package com.starlwr.bot.core.cache;

import com.starlwr.bot.core.config.StarBotCoreProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 缓存服务测试
 */
class StarBotCacheServiceTest {
    /**
     * 验证同名缓存复用与定义冲突检查
     */
    @Test
    void reusesNamedCacheAndRejectsConflictingDefinitions() {
        StarBotCacheService service = new StarBotCacheService(new StarBotCoreProperties(),
                List.of(new StarBotCaffeineCacheFactory()));
        StarBotCache<String, String> cache = service.getCache(definition("test.users", 10));
        cache.putAll(Map.of("1", "甲", "2", "乙"));

        assertSame(cache, service.getCache(definition("test.users", 10)));
        assertEquals(Map.of("1", "甲"), cache.getAll(List.of("1", "3")));
        assertTrue(service.getCache(definition("test.rooms", 10)).get("1").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> service.getCache(definition("test.users", 20)));

        assertFalse(cache.putIfAbsent("1", "丙"));
        assertEquals("甲", cache.get("1").orElseThrow());
        cache.invalidate("1");
        assertTrue(cache.putIfAbsent("1", "丙"));
        assertEquals("丙", cache.get("1").orElseThrow());
    }

    /**
     * 验证根据配置选择缓存后端
     */
    @Test
    void createsCacheUsingTheConfiguredFactory() {
        StarBotCoreProperties properties = new StarBotCoreProperties();
        properties.getCache().setType("redis");
        StarBotCaffeineCacheFactory caffeine = new StarBotCaffeineCacheFactory();
        StarBotCacheDefinition<String, String> definition = definition("test.users", 10);
        StarBotCache<String, String> cache = caffeine.create(definition);
        StarBotCacheFactory factory = mock(StarBotCacheFactory.class);
        when(factory.getType()).thenReturn("redis");
        when(factory.create(definition)).thenReturn(cache);

        StarBotCacheService service = new StarBotCacheService(properties, List.of(caffeine, factory));

        assertSame(cache, service.getCache(definition));
        assertSame(cache, service.getCache(definition));
        verify(factory).create(definition);
    }

    /**
     * 验证未设置有效期和容量的缓存可以写入和读取
     */
    @Test
    void createsCacheWithoutExpirationOrCapacityLimit() {
        StarBotCacheService service = new StarBotCacheService(new StarBotCoreProperties(),
                List.of(new StarBotCaffeineCacheFactory()));
        StarBotCacheDefinition<String, String> definition = new StarBotCacheDefinition<>("test.catalog",
                String.class, String.class, null, null, StarBotCacheSerializer.STRING, StarBotCacheSerializer.STRING);
        StarBotCache<String, String> cache = service.getCache(definition);

        cache.putAll(Map.of("1", "甲", "2", "乙"));

        assertEquals(Map.of("1", "甲", "2", "乙"), cache.getAll(List.of("1", "2")));
    }

    /**
     * 创建测试缓存定义
     * @param name 缓存名称
     * @param maximumSize 本地缓存最大容量
     * @return 缓存定义
     */
    private StarBotCacheDefinition<String, String> definition(String name, long maximumSize) {
        return new StarBotCacheDefinition<>(name, String.class, String.class, Duration.ofHours(1), maximumSize,
                StarBotCacheSerializer.STRING, StarBotCacheSerializer.STRING);
    }
}
