package com.starlwr.bot.common.event.live.common;

import com.starlwr.bot.common.enums.Platform;
import com.starlwr.bot.common.event.live.base.StarBotLiveStatusChangeEvent;
import com.starlwr.bot.common.model.LiveStreamerInfo;
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

    public LiveOnEvent(Platform platform, LiveStreamerInfo source) {
        super(platform, source);
    }

    public LiveOnEvent(Platform platform, LiveStreamerInfo source, Instant instant) {
        super(platform, source, instant);
    }
}
