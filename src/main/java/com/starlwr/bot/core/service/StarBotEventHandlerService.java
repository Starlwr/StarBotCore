package com.starlwr.bot.core.service;

import com.starlwr.bot.core.handler.DefaultHandlerForEvent;
import com.starlwr.bot.core.handler.StarBotEventHandler;
import jakarta.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * StarBot 事件处理器服务
 */
@Slf4j
@Service
public class StarBotEventHandlerService {
    @Resource
    private ApplicationContext applicationContext;

    private final Map<String, StarBotEventHandler> cache = new HashMap<>();

    private final Map<String, StarBotEventHandler> defaultHandlers = new HashMap<>();

    @PostConstruct
    public void init() {
        for (StarBotEventHandler handler : applicationContext.getBeansOfType(StarBotEventHandler.class).values()) {
            cache.put(handler.getClass().getName(), handler);

            if (handler.getClass().isAnnotationPresent(DefaultHandlerForEvent.class)) {
                DefaultHandlerForEvent annotation = handler.getClass().getAnnotation(DefaultHandlerForEvent.class);
                if (!defaultHandlers.containsKey(annotation.event())) {
                    defaultHandlers.put(annotation.event(), handler);
                }
            }
        }
    }

    /**
     * 获取事件处理器，优先使用配置的事件处理器，若未配置，返回事件的默认处理器
     * @param eventClass 事件全类名
     * @param handlerClass 处理器全类名
     * @return 事件处理器
     */
    public Optional<StarBotEventHandler> getHandler(@NonNull String eventClass, @Nullable String handlerClass) {
        if (handlerClass != null) {
            return Optional.ofNullable(cache.get(handlerClass));
        }

        if (defaultHandlers.containsKey(eventClass)) {
            return Optional.of(defaultHandlers.get(eventClass));
        }

        return Optional.empty();
    }
}
