package com.starlwr.bot.common.event.dynamic;

import com.starlwr.bot.common.enums.LivePlatform;
import com.starlwr.bot.common.event.StarBotExternalBaseEvent;
import com.starlwr.bot.common.model.LiveStreamerInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * StarBot 动态事件基类
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class StarBotBaseDynamicEvent extends StarBotExternalBaseEvent {
    public StarBotBaseDynamicEvent(String platform, LiveStreamerInfo source) {
        super(platform, source);
    }

    public StarBotBaseDynamicEvent(String platform, LiveStreamerInfo source, Instant instant) {
        super(platform, source, instant);
    }

    public StarBotBaseDynamicEvent(LivePlatform platform, LiveStreamerInfo source) {
        super(platform, source);
    }

    public StarBotBaseDynamicEvent(LivePlatform platform, LiveStreamerInfo source, Instant instant) {
        super(platform, source, instant);
    }
}
