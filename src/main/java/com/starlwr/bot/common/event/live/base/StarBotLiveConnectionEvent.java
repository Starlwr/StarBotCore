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
 * 直播间连接状态变更事件
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class StarBotLiveConnectionEvent extends StarBotBaseLiveEvent {
    public StarBotLiveConnectionEvent(String platform, LiveStreamerInfo source) {
        super(platform, source);
    }

    public StarBotLiveConnectionEvent(String platform, LiveStreamerInfo source, Instant instant) {
        super(platform, source, instant);
    }

    public StarBotLiveConnectionEvent(Platform platform, LiveStreamerInfo source) {
        super(platform, source);
    }

    public StarBotLiveConnectionEvent(Platform platform, LiveStreamerInfo source, Instant instant) {
        super(platform, source, instant);
    }
}
