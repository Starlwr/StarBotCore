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
 * 下播事件
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class LiveOffEvent extends StarBotLiveStatusChangeEvent {
    public LiveOffEvent(String platform, LiveStreamerInfo source) {
        super(platform, source);
    }

    public LiveOffEvent(String platform, LiveStreamerInfo source, Instant instant) {
        super(platform, source, instant);
    }

    public LiveOffEvent(Platform platform, LiveStreamerInfo source) {
        super(platform, source);
    }

    public LiveOffEvent(Platform platform, LiveStreamerInfo source, Instant instant) {
        super(platform, source, instant);
    }
}
