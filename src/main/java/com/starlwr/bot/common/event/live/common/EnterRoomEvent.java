package com.starlwr.bot.common.event.live.common;

import com.starlwr.bot.common.enums.LivePlatform;
import com.starlwr.bot.common.event.live.base.StarBotLiveOperationEvent;
import com.starlwr.bot.common.model.LiveStreamerInfo;
import com.starlwr.bot.common.model.UserInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * 进入房间事件
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class EnterRoomEvent extends StarBotLiveOperationEvent {
    public EnterRoomEvent(String platform, LiveStreamerInfo source, UserInfo sender) {
        super(platform, source, sender);
    }

    public EnterRoomEvent(String platform, LiveStreamerInfo source, UserInfo sender, Instant instant) {
        super(platform, source, sender, instant);
    }

    public EnterRoomEvent(LivePlatform platform, LiveStreamerInfo source, UserInfo sender) {
        super(platform, source, sender);
    }

    public EnterRoomEvent(LivePlatform platform, LiveStreamerInfo source, UserInfo sender, Instant instant) {
        super(platform, source, sender, instant);
    }
}
