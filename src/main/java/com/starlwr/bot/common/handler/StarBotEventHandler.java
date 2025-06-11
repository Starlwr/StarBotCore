package com.starlwr.bot.common.handler;

import com.starlwr.bot.common.event.StarBotExternalBaseEvent;
import com.starlwr.bot.common.model.PushMessage;
import org.springframework.stereotype.Component;

/**
 * StarBot 事件处理器接口，推送配置中配置的事件处理器实现均应实现此接口，并使用 {@link Component} 等注解注册至 Spring 容器中
 */
public interface StarBotEventHandler {
    /**
     * 处理事件
     * @param baseEvent 事件
     * @param pushMessage 推送消息
     */
    void handle(StarBotExternalBaseEvent baseEvent, PushMessage pushMessage);
}
