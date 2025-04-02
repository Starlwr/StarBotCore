package com.starlwr.bot.common.event.live.common;

import com.starlwr.bot.common.enums.Platform;
import com.starlwr.bot.common.event.live.base.StarBotLiveConnectionEvent;
import com.starlwr.bot.common.model.LiveStreamerInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * 连接断开事件
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class DisconnectedEvent extends StarBotLiveConnectionEvent {
    public DisconnectedEvent(String platform, LiveStreamerInfo source) {
        super(platform, source);
    }

    public DisconnectedEvent(String platform, LiveStreamerInfo source, Instant instant) {
        super(platform, source, instant);
    }

    public DisconnectedEvent(Platform platform, LiveStreamerInfo source) {
        super(platform, source);
    }

    public DisconnectedEvent(Platform platform, LiveStreamerInfo source, Instant instant) {
        super(platform, source, instant);
    }
}
