package com.starlwr.bot.core.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Redis 配置类
 */
@Profile("core")
@Slf4j
@Configuration
@EnableAutoConfiguration(exclude = RedisAutoConfiguration.class)
public class RedisConfig {
    @PostConstruct
    public void init() {
        log.info("当前配置为使用 core 模式启动, 不启用 Redis 服务, 如需修改, 请调整 spring.profiles.active 配置项");
    }
}
