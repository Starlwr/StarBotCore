package com.starlwr.bot.core.event.live.common;

import com.starlwr.bot.core.event.live.base.StarBotLiveMessageEvent;
import com.starlwr.bot.core.event.model.EmojiInfo;
import com.starlwr.bot.core.event.model.LivePlatform;
import com.starlwr.bot.core.event.model.LiveStreamerInfo;
import com.starlwr.bot.core.event.model.UserInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * 表情事件
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class EmojiEvent extends StarBotLiveMessageEvent {
    /**
     * 表情信息
     */
    private EmojiInfo emoji;

    public EmojiEvent(String platform, LiveStreamerInfo source, UserInfo sender, EmojiInfo emoji) {
        super(platform, source, sender);
        this.emoji = emoji;
    }

    public EmojiEvent(String platform, LiveStreamerInfo source, UserInfo sender, EmojiInfo emoji, Instant instant) {
        super(platform, source, sender, instant);
        this.emoji = emoji;
    }

    public EmojiEvent(LivePlatform platform, LiveStreamerInfo source, UserInfo sender, EmojiInfo emoji) {
        super(platform, source, sender);
        this.emoji = emoji;
    }

    public EmojiEvent(LivePlatform platform, LiveStreamerInfo source, UserInfo sender, EmojiInfo emoji, Instant instant) {
        super(platform, source, sender, instant);
        this.emoji = emoji;
    }
}
