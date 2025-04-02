package com.starlwr.bot.common.event.live.base;

import com.starlwr.bot.common.enums.Platform;
import com.starlwr.bot.common.event.live.StarBotBaseLiveEvent;
import com.starlwr.bot.common.model.LiveStreamerInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * 直播状态变更事件
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class StarBotLiveStatusChangeEvent extends StarBotBaseLiveEvent {
    public StarBotLiveStatusChangeEvent(String platform, LiveStreamerInfo source) {
        super(platform, source);
    }

    public StarBotLiveStatusChangeEvent(String platform, LiveStreamerInfo source, Instant instant) {
        super(platform, source, instant);
    }

    public StarBotLiveStatusChangeEvent(Platform platform, LiveStreamerInfo source) {
        super(platform, source);
    }

    public StarBotLiveStatusChangeEvent(Platform platform, LiveStreamerInfo source, Instant instant) {
        super(platform, source, instant);
    }
}
