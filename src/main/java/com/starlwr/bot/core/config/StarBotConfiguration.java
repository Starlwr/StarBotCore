package com.starlwr.bot.core.config;

import org.springframework.stereotype.Component;

import java.lang.annotation.*;

/**
 * StarBot 配置类注解
 */
@Documented
@Component
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface StarBotConfiguration {
    /**
     * 配置类在 JSON 中的路径前缀
     * @return 配置前缀
     */
    String prefix();
}
