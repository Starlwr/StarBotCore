package com.starlwr.bot.core.event.live.base;

import com.starlwr.bot.core.enums.LivePlatform;
import com.starlwr.bot.core.model.LiveStreamerInfo;
import com.starlwr.bot.core.model.UserInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * 直播间操作事件 (进房、关注、分享、点赞等)
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class StarBotLiveOperationEvent extends StarBotLiveUserEvent {
    public StarBotLiveOperationEvent(String platform, LiveStreamerInfo source, UserInfo sender) {
        super(platform, source, sender);
    }

    public StarBotLiveOperationEvent(String platform, LiveStreamerInfo source, UserInfo sender, Instant instant) {
        super(platform, source, sender, instant);
    }

    public StarBotLiveOperationEvent(LivePlatform platform, LiveStreamerInfo source, UserInfo sender) {
        super(platform, source, sender);
    }

    public StarBotLiveOperationEvent(LivePlatform platform, LiveStreamerInfo source, UserInfo sender, Instant instant) {
        super(platform, source, sender, instant);
    }
}
