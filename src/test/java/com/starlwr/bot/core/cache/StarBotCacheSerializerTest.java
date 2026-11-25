package com.starlwr.bot.core.cache;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 缓存序列化器测试
 */
class StarBotCacheSerializerTest {
    /**
     * 验证缓存值可以编码为字符串并还原
     * @param serializer 缓存序列化器
     * @param value 缓存值
     * @param <T> 缓存值类型
     */
    @ParameterizedTest
    @MethodSource("values")
    <T> void encodesAndDecodes(StarBotCacheSerializer<T> serializer, T value) {
        assertEquals(value, serializer.decode(serializer.encode(value)));
    }

    /**
     * 验证非法布尔值和字符会导致解码失败
     */
    @Test
    void rejectsInvalidBooleanAndCharacter() {
        assertThrows(IllegalArgumentException.class, () -> StarBotCacheSerializer.BOOLEAN.decode("未知"));
        assertThrows(IllegalArgumentException.class, () -> StarBotCacheSerializer.CHARACTER.decode(""));
        assertThrows(IllegalArgumentException.class, () -> StarBotCacheSerializer.CHARACTER.decode("昵称"));
    }

    /**
     * 验证 JSON 对象中值为 null 的属性可以还原
     */
    @Test
    void preservesNullObjectValues() {
        StarBotCacheSerializer<JSONObject> serializer = StarBotCacheSerializer.JSON_OBJECT;
        JSONObject value = new JSONObject();
        value.put("1", null);

        assertEquals(value, serializer.decode(serializer.encode(value)));
    }

    /**
     * 获取内置序列化器和对应的测试值
     * @return 序列化测试参数
     */
    private static Stream<Arguments> values() {
        return Stream.of(
                Arguments.of(StarBotCacheSerializer.STRING, "观众昵称"),
                Arguments.of(StarBotCacheSerializer.BOOLEAN, true),
                Arguments.of(StarBotCacheSerializer.BOOLEAN, false),
                Arguments.of(StarBotCacheSerializer.CHARACTER, '猫'),
                Arguments.of(StarBotCacheSerializer.BYTE, Byte.MIN_VALUE),
                Arguments.of(StarBotCacheSerializer.SHORT, Short.MAX_VALUE),
                Arguments.of(StarBotCacheSerializer.INTEGER, Integer.MIN_VALUE),
                Arguments.of(StarBotCacheSerializer.LONG, Long.MAX_VALUE),
                Arguments.of(StarBotCacheSerializer.FLOAT, -0.0f),
                Arguments.of(StarBotCacheSerializer.DOUBLE, Double.POSITIVE_INFINITY),
                Arguments.of(StarBotCacheSerializer.BIG_INTEGER, new BigInteger("123456789012345678901")),
                Arguments.of(StarBotCacheSerializer.BIG_DECIMAL, new BigDecimal("42.0100")),
                Arguments.of(StarBotCacheSerializer.UUID, UUID.fromString("123e4567-e89b-12d3-a456-426614174000")),
                Arguments.of(StarBotCacheSerializer.INSTANT, Instant.parse("2026-11-24T00:10:00.123456789Z")),
                Arguments.of(StarBotCacheSerializer.LOCAL_DATE, LocalDate.of(2026, 11, 24)),
                Arguments.of(StarBotCacheSerializer.LOCAL_DATE_TIME, LocalDateTime.of(2026, 11, 24, 8, 10, 0, 1)),
                Arguments.of(StarBotCacheSerializer.DURATION, Duration.ofSeconds(60, 123456789)),
                Arguments.of(StarBotCacheSerializer.JSON_OBJECT, JSON.parseObject("{\"items\":[1,2]}")),
                Arguments.of(StarBotCacheSerializer.JSON_ARRAY, JSON.parseArray("[{\"uname\":null},42,true]"))
        );
    }
}
