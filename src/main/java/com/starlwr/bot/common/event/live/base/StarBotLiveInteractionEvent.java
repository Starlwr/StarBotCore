package com.starlwr.bot.common.event.live.base;

import com.starlwr.bot.common.enums.LivePlatform;
import com.starlwr.bot.common.event.live.StarBotBaseLiveEvent;
import com.starlwr.bot.common.model.LiveStreamerInfo;
import com.starlwr.bot.common.model.UserInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * 直播间互动事件 (弹幕、表情、礼物、点赞等)
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class StarBotLiveInteractionEvent extends StarBotBaseLiveEvent {
    /**
     * 观众信息
     */
    private UserInfo sender;

    public StarBotLiveInteractionEvent(String platform, LiveStreamerInfo source, UserInfo sender) {
        super(platform, source);
        this.sender = sender;
    }

    public StarBotLiveInteractionEvent(String platform, LiveStreamerInfo source, UserInfo sender, Instant instant) {
        super(platform, source, instant);
        this.sender = sender;
    }

    public StarBotLiveInteractionEvent(LivePlatform platform, LiveStreamerInfo source, UserInfo sender) {
        super(platform, source);
        this.sender = sender;
    }

    public StarBotLiveInteractionEvent(LivePlatform platform, LiveStreamerInfo source, UserInfo sender, Instant instant) {
        super(platform, source, instant);
        this.sender = sender;
    }
}
