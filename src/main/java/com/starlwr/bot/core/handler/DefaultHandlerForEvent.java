package com.starlwr.bot.core.handler;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 默认事件处理器注解，添加在事件处理器类上后，若指定的事件未配置事件处理器，默认使用当前处理器处理该事件
 */
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface DefaultHandlerForEvent {
    /**
     * 事件全类名
     */
    String event();
}
