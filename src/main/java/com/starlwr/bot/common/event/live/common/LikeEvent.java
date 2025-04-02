package com.starlwr.bot.common.event.live.common;

import com.starlwr.bot.common.enums.Platform;
import com.starlwr.bot.common.event.live.base.StarBotLiveInteractionEvent;
import com.starlwr.bot.common.model.LiveStreamerInfo;
import com.starlwr.bot.common.model.UserInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * 点赞事件
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class LikeEvent extends StarBotLiveInteractionEvent {
    public LikeEvent(String platform, LiveStreamerInfo source, UserInfo sender) {
        super(platform, source, sender);
    }

    public LikeEvent(String platform, LiveStreamerInfo source, UserInfo sender, Instant instant) {
        super(platform, source, sender, instant);
    }

    public LikeEvent(Platform platform, LiveStreamerInfo source, UserInfo sender) {
        super(platform, source, sender);
    }

    public LikeEvent(Platform platform, LiveStreamerInfo source, UserInfo sender, Instant instant) {
        super(platform, source, sender, instant);
    }
}
