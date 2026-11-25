package com.starlwr.bot.core.cache;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/**
 * StarBot 缓存序列化器
 */
public interface StarBotCacheSerializer<T> {
    /**
     * 将缓存键或值转换为存储字符串
     * @param value 缓存键或值
     * @return 存储字符串
     */
    String encode(T value);

    /**
     * 将存储字符串还原为缓存键或值
     * @param value 存储字符串
     * @return 还原后的缓存键或值
     */
    T decode(String value);

    /**
     * 根据编解码方法创建序列化器
     * @param encoder 编码方法
     * @param decoder 解码方法
     * @param <T> 序列化类型
     * @return 缓存序列化器
     */
    private static <T> StarBotCacheSerializer<T> of(Function<T, String> encoder, Function<String, T> decoder) {
        return new StarBotCacheSerializer<>() {
            @Override
            public String encode(T value) {
                return encoder.apply(value);
            }

            @Override
            public T decode(String value) {
                return decoder.apply(value);
            }
        };
    }

    /**
     * 字符串序列化器
     */
    StarBotCacheSerializer<String> STRING = of(Function.identity(), Function.identity());

    /**
     * 字符序列化器
     */
    StarBotCacheSerializer<Character> CHARACTER = of(Object::toString, value -> {
        if (value.length() != 1) {
            throw new IllegalArgumentException("无效的字符: " + value);
        }

        return value.charAt(0);
    });

    /**
     * 布尔值序列化器
     */
    StarBotCacheSerializer<Boolean> BOOLEAN = of(Object::toString, value -> {
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }

        if ("false".equalsIgnoreCase(value)) {
            return false;
        }

        throw new IllegalArgumentException("无效的布尔值: " + value);
    });

    /**
     * 字节序列化器
     */
    StarBotCacheSerializer<Byte> BYTE = of(Object::toString, Byte::valueOf);

    /**
     * 短整数序列化器
     */
    StarBotCacheSerializer<Short> SHORT = of(Object::toString, Short::valueOf);

    /**
     * 整数序列化器
     */
    StarBotCacheSerializer<Integer> INTEGER = of(Object::toString, Integer::valueOf);

    /**
     * 长整数序列化器
     */
    StarBotCacheSerializer<Long> LONG = of(Object::toString, Long::valueOf);

    /**
     * 单精度浮点数序列化器
     */
    StarBotCacheSerializer<Float> FLOAT = of(Object::toString, Float::valueOf);

    /**
     * 双精度浮点数序列化器
     */
    StarBotCacheSerializer<Double> DOUBLE = of(Object::toString, Double::valueOf);

    /**
     * 高精度整数序列化器
     */
    StarBotCacheSerializer<BigInteger> BIG_INTEGER = of(Object::toString, BigInteger::new);

    /**
     * 高精度小数序列化器
     */
    StarBotCacheSerializer<BigDecimal> BIG_DECIMAL = of(Object::toString, BigDecimal::new);

    /**
     * UUID 序列化器
     */
    StarBotCacheSerializer<UUID> UUID = of(Object::toString, java.util.UUID::fromString);

    /**
     * 本地日期序列化器
     */
    StarBotCacheSerializer<LocalDate> LOCAL_DATE = of(Object::toString, LocalDate::parse);

    /**
     * 本地日期时间序列化器
     */
    StarBotCacheSerializer<LocalDateTime> LOCAL_DATE_TIME = of(Object::toString, LocalDateTime::parse);

    /**
     * 时间戳序列化器
     */
    StarBotCacheSerializer<Instant> INSTANT = of(Object::toString, Instant::parse);

    /**
     * 时长序列化器
     */
    StarBotCacheSerializer<Duration> DURATION = of(Object::toString, Duration::parse);

    /**
     * JSON 对象序列化器
     */
    StarBotCacheSerializer<JSONObject> JSON_OBJECT = of(value -> JSON.toJSONString(value, JSONWriter.Feature.WriteNulls), JSON::parseObject);

    /**
     * JSON 数组序列化器
     */
    StarBotCacheSerializer<JSONArray> JSON_ARRAY = of(value -> JSON.toJSONString(value, JSONWriter.Feature.WriteNulls), JSON::parseArray);

    /**
     * 为指定类型创建 JSON 序列化器
     * @param type 解码后的目标类型
     * @param <T> 序列化类型
     * @return 缓存序列化器
     */
    static <T> StarBotCacheSerializer<T> json(Class<T> type) {
        Objects.requireNonNull(type);
        return of(JSON::toJSONString, value -> JSON.parseObject(value, type));
    }
}
