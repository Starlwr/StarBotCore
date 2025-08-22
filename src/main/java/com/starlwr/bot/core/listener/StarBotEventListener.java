package com.starlwr.bot.core.listener;

import com.starlwr.bot.core.event.StarBotBaseEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(name = "starbot.core.log.event-log", havingValue = "true")
public class StarBotEventListener {
    private static final Logger eventLogger = LoggerFactory.getLogger("EventLogger");

    @EventListener
    public void handleEvent(StarBotBaseEvent event) {
        eventLogger.debug("[{}] {}", event.getClass().getSimpleName(), event);
    }
}
