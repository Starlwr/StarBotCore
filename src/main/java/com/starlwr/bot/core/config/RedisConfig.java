package com.starlwr.bot.core.config;

import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Redis 配置类
 */
@Profile("core")
@Configuration
@EnableAutoConfiguration(exclude = RedisAutoConfiguration.class)
public class RedisConfig {
}
