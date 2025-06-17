package com.starlwr.bot.core.event.live.common;

import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.event.live.base.StarBotLiveStatusChangeEvent;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * 开播事件
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class LiveOnEvent extends StarBotLiveStatusChangeEvent {
    public LiveOnEvent(String platform, LiveStreamerInfo source) {
        super(platform, source);
    }

    public LiveOnEvent(String platform, LiveStreamerInfo source, Instant instant) {
        super(platform, source, instant);
    }

    public LiveOnEvent(LivePlatform platform, LiveStreamerInfo source) {
        super(platform, source);
    }

    public LiveOnEvent(LivePlatform platform, LiveStreamerInfo source, Instant instant) {
        super(platform, source, instant);
    }
}
