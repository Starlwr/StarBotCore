package com.starlwr.bot.core.service;

import com.starlwr.bot.core.util.RedisUtil;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Redis 服务类
 */
@Profile("!core")
@Slf4j
@Service
public class RedisService {
    @Resource
    private ApplicationContext context;

    @Resource(name = "stringRedisTemplate")
    private StringRedisTemplate redis;

    private final Map<String, RedisUtil> redisMap = new HashMap<>();

    @Autowired
    public RedisService(List<RedisUtil> redisUtils) {
        for (RedisUtil redis : redisUtils) {
            redisMap.put(redis.getPlatform(), redis);
        }
    }

    @PostConstruct
    public void init() {
        ping();
    }

    /**
     * 尝试连接到 Redis
     */
    public void ping() {
        log.info("开始连接 Redis 数据库");
        try {
            RedisConnectionFactory factory = redis.getConnectionFactory();
            if (factory == null) {
                throw new RuntimeException("获取 Redis 连接工厂失败");
            }

            factory.getConnection().ping();
            log.info("成功连接 Redis 数据库");
        } catch (Exception e) {
            log.error("连接 Redis 数据库失败, 请检查是否启动了 Redis 服务或配置文件中的连接参数是否正确", e);
            SpringApplication.exit(context);
            System.exit(0);
        }
    }

    /**
     * 获取指定直播平台的 Redis 实例
     * @param platform 直播平台名称
     * @return Redis 实例
     */
    public Optional<RedisUtil> getRedis(String platform) {
        return Optional.ofNullable(redisMap.get(platform));
    }
}
