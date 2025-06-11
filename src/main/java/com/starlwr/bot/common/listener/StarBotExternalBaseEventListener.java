package com.starlwr.bot.common.listener;

import com.starlwr.bot.common.datasource.AbstractDataSource;
import com.starlwr.bot.common.event.StarBotExternalBaseEvent;
import com.starlwr.bot.common.handler.StarBotEventHandler;
import com.starlwr.bot.common.model.PushMessage;
import com.starlwr.bot.common.model.PushTarget;
import com.starlwr.bot.common.model.PushUser;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * StarBot 外部事件监听器
 */
@Slf4j
@Component
public class StarBotExternalBaseEventListener {
    @Resource
    private ApplicationContext applicationContext;

    @Resource
    private AbstractDataSource dataSource;

    private final Map<String, StarBotEventHandler> cache = new HashMap<>();

    @PostConstruct
    public void init() {
        for (StarBotEventHandler handler : applicationContext.getBeansOfType(StarBotEventHandler.class).values()) {
            cache.put(handler.getClass().getName(), handler);
        }
    }

    @Async("eventHandlerThreadPool")
    @EventListener
    public void handleEvent(StarBotExternalBaseEvent event) {
        log.debug("接收到事件 {}: {}", event.getClass().getSimpleName(), event);

        Optional<PushUser> optionalUser = dataSource.getUser(event.getPlatform(), event.getSource().getUid());
        if (optionalUser.isEmpty()) {
            return;
        }

        String eventClass = event.getClass().getName();

        PushUser user = optionalUser.get();
        for (PushTarget target : user.getTargets()) {
            for (PushMessage message : target.getMessages()) {
                if (eventClass.equals(message.getEvent())) {
                    StarBotEventHandler handler = cache.get(message.getHandler());

                    if (handler == null) {
                        log.error("不存在的事件处理器: {}, 请检查推送配置是否正确", message.getHandler());
                        continue;
                    }

                    try {
                        handler.handle(event, message);
                    } catch (Exception e) {
                        log.error("事件处理器 {} 处理事件 {} 异常", message.getHandler(), eventClass, e);
                    }
                }
            }
        }
    }
}
