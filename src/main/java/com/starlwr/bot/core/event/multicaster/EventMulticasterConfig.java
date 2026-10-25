package com.starlwr.bot.core.event.multicaster;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 事件广播器配置类
 */
@Configuration
public class EventMulticasterConfig {
    @Bean
    public InterruptibleEventMulticaster applicationEventMulticaster() {
        return new InterruptibleEventMulticaster();
    }
}
