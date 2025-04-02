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
 * 直播间信息更新事件
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class StarBotLiveInfoUpdateEvent extends StarBotBaseLiveEvent {
    public StarBotLiveInfoUpdateEvent(String platform, LiveStreamerInfo source) {
        super(platform, source);
    }

    public StarBotLiveInfoUpdateEvent(String platform, LiveStreamerInfo source, Instant instant) {
        super(platform, source, instant);
    }

    public StarBotLiveInfoUpdateEvent(Platform platform, LiveStreamerInfo source) {
        super(platform, source);
    }

    public StarBotLiveInfoUpdateEvent(Platform platform, LiveStreamerInfo source, Instant instant) {
        super(platform, source, instant);
    }
}
