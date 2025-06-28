package com.starlwr.bot.core.util;

import lombok.Getter;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.util.Pair;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Redis 工具类
 */
@Slf4j
public class RedisUtil {
    @Getter
    private final String platform;

    private final StringRedisTemplate redis;

    public RedisUtil(String platform, StringRedisTemplate redis) {
        this.platform = platform;
        this.redis = redis;
    }

    /**
     * 获取是否正常连接到 Redis
     * @return 是否正常连接到 Redis
     */
    public boolean ping() {
        try {
            RedisConnectionFactory factory = redis.getConnectionFactory();
            if (factory == null) {
                return false;
            }

            factory.getConnection().ping();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ================ 键值相关操作 ================

    /**
     * 根据匹配规则获取键集合
     * @param pattern 匹配规则
     * @return 键集合
     */
    public Set<String> keys(@NonNull String pattern) {
        return redis.keys(pattern);
    }

    /**
     * 获取键是否存在
     * @param key 键
     * @return 是否存在
     */
    public boolean exists(@NonNull String key) {
        return redis.hasKey(key);
    }

    /**
     * 设置键过期时间
     * @param key 键
     * @param seconds 过期时间，单位：秒
     */
    public void expire(@NonNull String key, int seconds) {
        redis.expire(key, seconds, TimeUnit.SECONDS);
    }

    /**
     * 获取键的值
     * @param key 键
     * @return 值
     */
    public Optional<String> get(@NonNull String key) {
        return Optional.ofNullable(redis.opsForValue().get(key));
    }

    /**
     * 获取整数值
     * @param key 键
     * @return 整数值，若键不存在返回 0
     */
    public int getInt(@NonNull String key) {
        try {
            return get(key).map(Integer::parseInt).orElse(0);
        } catch (Exception e) {
            log.error("从 Redis 读取数值异常, 键: {}", key, e);
            return 0;
        }
    }

    /**
     * 设置键值
     * @param key 键
     * @param value 值
     */
    public void set(@NonNull String key, @NonNull String value) {
        redis.opsForValue().set(key, value);
    }

    /**
     * 设置键值并设置过期时间
     * @param key 键
     * @param value 值
     * @param seconds 过期时间，单位：秒
     */
    public void setWithExpire(@NonNull String key, @NonNull String value, int seconds) {
        redis.opsForValue().set(key, value, seconds, TimeUnit.SECONDS);
    }

    /**
     * 递增指定键的值
     * @param key 键
     */
    public void increment(@NonNull String key) {
        increment(key, 1);
    }

    /**
     * 递增指定键的值
     * @param key 键
     * @param value 增加的数值
     */
    public void increment(@NonNull String key, int value) {
        redis.opsForValue().increment(key, value);
    }

    /**
     * 删除键
     * @param key 键
     * @return 是否成功删除
     */
    public boolean delete(@NonNull String key) {
        return redis.delete(key);
    }

    /**
     * 批量删除键
     * @param keys 键集合
     * @return 成功删除的键数量
     */
    public long delete(@NonNull Set<String> keys) {
        return redis.delete(keys);
    }

    // ================ 列表相关操作 ================

    /**
     * 获取列表长度
     * @param key 键
     * @return 列表长度
     */
    public long listSize(@NonNull String key) {
        return Optional.ofNullable(redis.opsForList().size(key)).orElse(0L);
    }

    /**
     * 获取列表指定索引位置的元素
     * @param key 键
     * @param index 索引
     * @return 元素
     */
    public Optional<String> listGet(@NonNull String key, int index) {
        return Optional.ofNullable(redis.opsForList().index(key, index));
    }

    /**
     * 设置列表指定索引位置的值
     * @param key 键
     * @param index 索引
     * @param value 值
     */
    public void listSet(@NonNull String key, int index, @NonNull String value) {
        redis.opsForList().set(key, index, value);
    }

    /**
     * 获取列表全部元素
     * @param key 键
     * @return 元素列表
     */
    public List<String> listRange(@NonNull String key) {
        return listRange(key, 0, -1);
    }

    /**
     * 获取列表指定范围内的元素
     * @param key 键
     * @param start 开始位置
     * @param end 结束位置
     * @return 元素列表
     */
    public List<String> listRange(@NonNull String key, int start, int end) {
        return Optional.ofNullable(redis.opsForList().range(key, start, end)).orElse(List.of());
    }

    /**
     * 获取整数列表全部元素
     * @param key 键
     * @return 元素列表
     */
    public List<Integer> listRangeInt(@NonNull String key) {
        return listRangeInt(key, 0, -1);
    }

    /**
     * 获取整数列表指定范围内的元素
     * @param key 键
     * @param start 开始位置
     * @param end 结束位置
     * @return 元素列表
     */
    public List<Integer> listRangeInt(@NonNull String key, int start, int end) {
        return Optional.ofNullable(redis.opsForList().range(key, start, end))
                .map(list -> list.stream().map(Integer::valueOf).collect(Collectors.toList()))
                .orElse(List.of());
    }

    /**
     * 获取小数列表全部元素
     * @param key 键
     * @return 元素列表
     */
    public List<Double> listRangeDouble(@NonNull String key) {
        return listRangeDouble(key, 0, -1);
    }

    /**
     * 获取小数列表指定范围内的元素
     * @param key 键
     * @param start 开始位置
     * @param end 结束位置
     * @return 元素列表
     */
    public List<Double> listRangeDouble(@NonNull String key, int start, int end) {
        return Optional.ofNullable(redis.opsForList().range(key, start, end))
                .map(list -> list.stream().map(Double::valueOf).collect(Collectors.toList()))
                .orElse(List.of());
    }

    /**
     * 从左侧向列表添加元素
     * @param key 键
     * @param value 值
     */
    public void leftPush(@NonNull String key, @NonNull String value) {
        redis.opsForList().leftPush(key, value);
    }

    /**
     * 从右侧向列表添加元素
     * @param key 键
     * @param value 值
     */
    public void rightPush(@NonNull String key, @NonNull String value) {
        redis.opsForList().rightPush(key, value);
    }

    /**
     * 从左侧弹出列表元素
     * @param key 键
     * @return 弹出的元素
     */
    public Optional<String> leftPop(@NonNull String key) {
        return Optional.ofNullable(redis.opsForList().leftPop(key));
    }

    /**
     * 从右侧弹出列表元素
     * @param key 键
     * @return 弹出的元素
     */
    public Optional<String> rightPop(@NonNull String key) {
        return Optional.ofNullable(redis.opsForList().rightPop(key));
    }

    /**
     * 从列表中删除所有指定元素
     * @param key 键
     * @param value 值
     * @return 删除的元素数量
     */
    public long listRemove(@NonNull String key, @NonNull String value) {
        return listRemove(key, value, 0);
    }

    /**
     * 从列表中删除指定数量的指定元素
     * @param key 键
     * @param value 值
     * @param count 数量，正数为从左向右删除，负数为从右向左删除
     * @return 删除的元素数量
     */
    public long listRemove(@NonNull String key, @NonNull String value, int count) {
        return Optional.ofNullable(redis.opsForList().remove(key, count, value)).orElse(0L);
    }

    // ================ 哈希相关操作 ================

    /**
     * 判断哈希表字段是否存在
     * @param key 键
     * @param field 字段
     * @return 是否存在
     */
    public boolean hExists(@NonNull String key, @NonNull String field) {
        return redis.opsForHash().hasKey(key, field);
    }

    /**
     * 获取哈希表字段数量
     * @param key 键
     * @return 字段数量
     */
    public long hSize(@NonNull String key) {
        return redis.opsForHash().size(key);
    }

    /**
     * 获取哈希表中的值
     * @param key 键
     * @param field 字段
     * @return 值
     */
    public Optional<String> hGet(@NonNull String key, @NonNull String field) {
        return Optional.ofNullable(redis.opsForHash().get(key, field)).map(Object::toString);
    }

    /**
     * 获取整数哈希表中的值
     * @param key 键
     * @param field 字段
     * @return 值，若键或字段不存在返回 0
     */
    public int hGetInt(@NonNull String key, @NonNull String field) {
        return Optional.ofNullable(redis.opsForHash().get(key, field))
                .map(Object::toString)
                .map(Integer::valueOf)
                .orElse(0);
    }

    /**
     * 获取小数哈希表中的值
     * @param key 键
     * @param field 字段
     * @return 值，若键或字段不存在返回 0
     */
    public double hGetDouble(@NonNull String key, @NonNull String field) {
        return Optional.ofNullable(redis.opsForHash().get(key, field))
                .map(Object::toString)
                .map(Double::valueOf)
                .orElse(0D);
    }

    /**
     * 获取哈希表所有字段
     * @param key 键
     * @return 字段集合
     */
    public Set<String> hKeys(@NonNull String key) {
        return redis.opsForHash().keys(key).stream().map(String::valueOf).collect(Collectors.toSet());
    }

    /**
     * 获取哈希表所有值
     * @param key 键
     * @return 值列表
     */
    public List<String> hValues(@NonNull String key) {
        return redis.opsForHash().values(key).stream().map(String::valueOf).collect(Collectors.toList());
    }

    /**
     * 获取整数哈希表所有值
     * @param key 键
     * @return 值列表
     */
    public List<Integer> hValuesInt(@NonNull String key) {
        return redis.opsForHash().values(key).stream()
                .map(String::valueOf)
                .map(Integer::valueOf)
                .collect(Collectors.toList());
    }

    /**
     * 获取小数哈希表所有值
     * @param key 键
     * @return 值列表
     */
    public List<Double> hValuesDouble(@NonNull String key) {
        return redis.opsForHash().values(key).stream()
                .map(String::valueOf)
                .map(Double::valueOf)
                .collect(Collectors.toList());
    }

    /**
     * 获取哈希表所有键值对
     * @param key 键
     * @return 所有键值对
     */
    public Map<String, String> hEntries(@NonNull String key) {
        return redis.opsForHash().entries(key).entrySet().stream()
                .collect(Collectors.toMap(
                        e -> String.valueOf(e.getKey()),
                        e -> String.valueOf(e.getValue())
                ));
    }

    /**
     * 设置哈希表字段值
     * @param key 键
     * @param field 字段
     * @param value 值
     */
    public void hSet(@NonNull String key, @NonNull String field, @NonNull String value) {
        redis.opsForHash().put(key, field, value);
    }

    /**
     * 批量设置哈希表字段值
     * @param key 键
     * @param map 键值对
     */
    public void hSetAll(@NonNull String key, @NonNull Map<String, String> map) {
        redis.opsForHash().putAll(key, map);
    }

    /**
     * 递增哈希表字段值
     * @param key 键
     * @param field 字段
     */
    public void hIncrement(@NonNull String key, @NonNull String field) {
        hIncrement(key, field, 1);
    }

    /**
     * 递增哈希表字段值
     * @param key 键
     * @param field 字段
     * @param value 增加的值
     */
    public void hIncrement(@NonNull String key, @NonNull String field, int value) {
        redis.opsForHash().increment(key, field, value);
    }

    /**
     * 递增小数哈希表字段值
     * @param key 键
     * @param field 字段
     * @param value 增加的值
     */
    public void hIncrement(@NonNull String key, @NonNull String field, double value) {
        redis.opsForHash().increment(key, field, value);
    }

    /**
     * 删除哈希表字段
     * @param key 键
     * @param field 字段
     */
    public void hDelete(@NonNull String key, @NonNull String field) {
        redis.opsForHash().delete(key, field);
    }

    // ================ 集合相关操作 ================

    /**
     * 获取集合大小
     * @param key 键
     * @return 集合大小
     */
    public long sSize(@NonNull String key) {
        return Optional.ofNullable(redis.opsForSet().size(key)).orElse(0L);
    }

    /**
     * 判断元素是否是集合成员
     * @param key 键
     * @param value 值
     * @return 是否是集合成员
     */
    public boolean sIsMember(@NonNull String key, @NonNull String value) {
        return Boolean.TRUE.equals(redis.opsForSet().isMember(key, value));
    }

    /**
     * 获取集合所有元素
     * @param key 键
     * @return 元素集合
     */
    public Set<String> sMembers(@NonNull String key) {
        return Optional.ofNullable(redis.opsForSet().members(key)).orElse(Set.of());
    }

    /**
     * 向集合添加元素
     * @param key 键
     * @param value 值
     */
    public void sAdd(@NonNull String key, @NonNull String value) {
        redis.opsForSet().add(key, value);
    }

    /**
     * 向集合添加元素
     * @param key 键
     * @param values 值
     */
    public void sAdd(@NonNull String key, @NonNull String... values) {
        redis.opsForSet().add(key, values);
    }

    /**
     * 从集合移除元素
     * @param key 键
     * @param value 值
     */
    public void sRemove(@NonNull String key, @NonNull String value) {
        redis.opsForSet().remove(key, value);
    }

    /**
     * 获取两个集合的并集
     * @param key1 键 1
     * @param key2 键 2
     * @return 并集
     */
    public Set<String> sUnion(@NonNull String key1, @NonNull String key2) {
        return Optional.ofNullable(redis.opsForSet().union(key1, key2)).orElse(Set.of());
    }

    /**
     * 获取两个集合的交集
     * @param key1 键 1
     * @param key2 键 2
     * @return 交集
     */
    public Set<String> sIntersect(@NonNull String key1, @NonNull String key2) {
        return Optional.ofNullable(redis.opsForSet().intersect(key1, key2)).orElse(Set.of());
    }

    /**
     * 获取两个集合的差集
     * @param key1 键 1
     * @param key2 键 2
     * @return 差集
     */
    public Set<String> sDifference(@NonNull String key1, @NonNull String key2) {
        return Optional.ofNullable(redis.opsForSet().difference(key1, key2)).orElse(Set.of());
    }

    // ================ 有序集合相关操作 ================

    /**
     * 获取有序集合大小
     * @param key 键
     * @return 集合大小
     */
    public long zSize(@NonNull String key) {
        return Optional.ofNullable(redis.opsForZSet().size(key)).orElse(0L);
    }

    /**
     * 获取有序集合元素的排名（从小到大）
     * @param key 键
     * @param value 值
     * @return 排名
     */
    public Optional<Long> zRank(@NonNull String key, @NonNull String value) {
        return Optional.ofNullable(redis.opsForZSet().rank(key, value));
    }

    /**
     * 获取有序集合元素的排名（从大到小）
     * @param key 键
     * @param value 值
     * @return 排名
     */
    public Optional<Long> zReverseRank(@NonNull String key, @NonNull String value) {
        return Optional.ofNullable(redis.opsForZSet().reverseRank(key, value));
    }

    /**
     * 获取有序集合元素的分数
     * @param key 键
     * @param value 值
     * @return 分数
     */
    public Optional<Double> zGet(@NonNull String key, @NonNull String value) {
        return Optional.ofNullable(redis.opsForZSet().score(key, value));
    }

    /**
     * 获取整数有序集合元素的分数
     * @param key 键
     * @param value 值
     * @return 分数，若键不存在返回 0
     */
    public int zGetInt(@NonNull String key, @NonNull String value) {
        try {
            return zGet(key, value).map(Double::intValue).orElse(0);
        } catch (Exception e) {
            log.error("从 Redis 有序集合读取数值异常, 键: {}, 值: {}", key, value, e);
            return 0;
        }
    }

    /**
     * 获取有序集合指定范围内的元素（从小到大）
     * @param key 键
     * @return 元素集合
     */
    public Set<String> zRange(@NonNull String key) {
        return zRange(key, 0, -1);
    }

    /**
     * 获取有序集合指定范围内的元素（从小到大）
     * @param key 键
     * @param start 开始位置
     * @param end 结束位置
     * @return 元素集合
     */
    public Set<String> zRange(@NonNull String key, long start, long end) {
        return Optional.ofNullable(redis.opsForZSet().range(key, start, end)).orElse(Set.of());
    }

    /**
     * 获取有序集合指定范围内的元素（从大到小）
     * @param key 键
     * @return 元素集合
     */
    public Set<String> zReverseRange(@NonNull String key) {
        return zReverseRange(key, 0, -1);
    }

    /**
     * 获取有序集合指定范围内的元素（从大到小）
     * @param key 键
     * @param start 开始位置
     * @param end 结束位置
     * @return 元素集合
     */
    public Set<String> zReverseRange(@NonNull String key, long start, long end) {
        return Optional.ofNullable(redis.opsForZSet().reverseRange(key, start, end)).orElse(Set.of());
    }

    /**
     * 获取整数有序集合指定范围内的键值对（从小到大）
     * @param key 键
     * @return 键值对集合
     */
    public Set<Pair<String, Integer>> zRangeWithScoresInt(@NonNull String key) {
        return zRangeWithScoresInt(key, 0, -1);
    }

    /**
     * 获取整数有序集合指定范围内的键值对（从小到大）
     * @param key 键
     * @param start 开始位置
     * @param end 结束位置
     * @return 键值对集合
     */
    public Set<Pair<String, Integer>> zRangeWithScoresInt(@NonNull String key, long start, long end) {
        return Optional.ofNullable(redis.opsForZSet().rangeWithScores(key, start, end))
                .map(
                        tuples -> tuples.stream()
                                .filter(tuple -> tuple.getValue() != null && tuple.getScore() != null)
                                .map(tuple -> Pair.of(tuple.getValue(), tuple.getScore().intValue()))
                                .collect(Collectors.toCollection(LinkedHashSet::new))
                ).orElse(new LinkedHashSet<>());
    }

    /**
     * 获取整数有序集合指定范围内的键值对（从大到小）
     * @param key 键
     * @return 键值对集合
     */
    public Set<Pair<String, Integer>> zReverseRangeWithScoresInt(@NonNull String key) {
        return zReverseRangeWithScoresInt(key, 0, -1);
    }

    /**
     * 获取整数有序集合指定范围内的键值对（从大到小）
     * @param key 键
     * @param start 开始位置
     * @param end 结束位置
     * @return 键值对集合
     */
    public Set<Pair<String, Integer>> zReverseRangeWithScoresInt(@NonNull String key, long start, long end) {
        return Optional.ofNullable(redis.opsForZSet().reverseRangeWithScores(key, start, end))
                .map(
                        tuples -> tuples.stream()
                                .filter(tuple -> tuple.getValue() != null && tuple.getScore() != null)
                                .map(tuple -> Pair.of(tuple.getValue(), tuple.getScore().intValue()))
                                .collect(Collectors.toCollection(LinkedHashSet::new))
                ).orElse(new LinkedHashSet<>());
    }

    /**
     * 获取小数有序集合指定范围内的键值对（从小到大）
     * @param key 键
     * @return 键值对集合
     */
    public Set<Pair<String, Double>> zRangeWithScoresDouble(@NonNull String key) {
        return zRangeWithScoresDouble(key, 0, -1);
    }

    /**
     * 获取小数有序集合指定范围内的键值对（从小到大）
     * @param key 键
     * @param start 开始位置
     * @param end 结束位置
     * @return 键值对集合
     */
    public Set<Pair<String, Double>> zRangeWithScoresDouble(@NonNull String key, long start, long end) {
        return Optional.ofNullable(redis.opsForZSet().rangeWithScores(key, start, end))
                .map(
                        tuples -> tuples.stream()
                                .filter(tuple -> tuple.getValue() != null && tuple.getScore() != null)
                                .map(tuple -> Pair.of(tuple.getValue(), tuple.getScore()))
                                .collect(Collectors.toCollection(LinkedHashSet::new))
                ).orElse(new LinkedHashSet<>());
    }

    /**
     * 获取小数有序集合指定范围内的键值对（从大到小）
     * @param key 键
     * @return 键值对集合
     */
    public Set<Pair<String, Double>> zReverseRangeWithScoresDouble(@NonNull String key) {
        return zReverseRangeWithScoresDouble(key, 0, -1);
    }

    /**
     * 获取小数有序集合指定范围内的键值对（从大到小）
     * @param key 键
     * @param start 开始位置
     * @param end 结束位置
     * @return 键值对集合
     */
    public Set<Pair<String, Double>> zReverseRangeWithScoresDouble(@NonNull String key, long start, long end) {
        return Optional.ofNullable(redis.opsForZSet().reverseRangeWithScores(key, start, end))
                .map(
                        tuples -> tuples.stream()
                                .filter(tuple -> tuple.getValue() != null && tuple.getScore() != null)
                                .map(tuple -> Pair.of(tuple.getValue(), tuple.getScore()))
                                .collect(Collectors.toCollection(LinkedHashSet::new))
                ).orElse(new LinkedHashSet<>());
    }

    /**
     * 向有序集合添加元素
     * @param key 键
     * @param value 值
     * @param score 分数
     */
    public void zAdd(@NonNull String key, @NonNull String value, int score) {
        redis.opsForZSet().add(key, value, score);
    }

    /**
     * 向有序集合添加元素
     * @param key 键
     * @param value 值
     * @param score 分数
     */
    public void zAdd(@NonNull String key, @NonNull String value, double score) {
        redis.opsForZSet().add(key, value, score);
    }

    /**
     * 增加有序集合元素分数
     * @param key 键
     * @param value 值
     */
    public void zIncrement(@NonNull String key, @NonNull String value) {
        zIncrement(key, value, 1);
    }

    /**
     * 增加有序集合元素分数
     * @param key 键
     * @param value 值
     * @param delta 增加的分数
     */
    public void zIncrement(@NonNull String key, @NonNull String value, int delta) {
        redis.opsForZSet().incrementScore(key, value, delta);
    }

    /**
     * 增加有序集合元素分数
     * @param key 键
     * @param value 值
     * @param delta 增加的分数
     */
    public void zIncrement(@NonNull String key, @NonNull String value, double delta) {
        redis.opsForZSet().incrementScore(key, value, delta);
    }

    /**
     * 从有序集合移除元素
     * @param key 键
     * @param value 值
     */
    public void zRemove(@NonNull String key, @NonNull String value) {
        redis.opsForZSet().remove(key, value);
    }

    /**
     * 将两个有序集合的并集存储到目标键中
     * @param sourceKey 源键
     * @param destKey 目标键
     */
    public void zUnionStore(@NonNull String sourceKey, @NonNull String destKey) {
        redis.opsForZSet().unionAndStore(sourceKey, destKey, destKey);
    }

    /**
     * 将两个有序集合的并集存储到目标键中
     * @param key 源键 1
     * @param otherKey 源键 2
     * @param destKey 目标键
     */
    public void zUnionStore(@NonNull String key, @NonNull String otherKey, @NonNull String destKey) {
        redis.opsForZSet().unionAndStore(key, otherKey, destKey);
    }
}
