package com.starlwr.bot.common.event;

import lombok.Getter;
import lombok.Setter;
import org.springframework.context.ApplicationEvent;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

/**
 * StarBot 事件基类
 */
@Getter
@Setter
public class StarBotBaseEvent extends ApplicationEvent {
    public StarBotBaseEvent() {
        super(new Object());
    }

    public StarBotBaseEvent(Instant instant) {
        super(new Object(), Clock.fixed(instant, ZoneId.systemDefault()));
    }

    @Override
    public String toString() {
        return "StarBotBaseEvent(" + "timestamp=" + getTimestamp() + ")";
    }
}
