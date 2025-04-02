package com.starlwr.bot.common.event.live;

import com.starlwr.bot.common.enums.Platform;
import com.starlwr.bot.common.event.StarBotBaseEvent;
import com.starlwr.bot.common.model.LiveStreamerInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * StarBot 直播事件基类
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class StarBotBaseLiveEvent extends StarBotBaseEvent {
    /**
     * 直播平台
     */
    private String platform;

    /**
     * 主播信息
     */
    private LiveStreamerInfo source;

    public StarBotBaseLiveEvent(String platform, LiveStreamerInfo source) {
        this.platform = platform;
        this.source = source;
    }

    public StarBotBaseLiveEvent(String platform, LiveStreamerInfo source, Instant instant) {
        super(instant);
        this.platform = platform;
        this.source = source;
    }

    public StarBotBaseLiveEvent(Platform platform, LiveStreamerInfo source) {
        this.platform = platform.getName();
        this.source = source;
    }

    public StarBotBaseLiveEvent(Platform platform, LiveStreamerInfo source, Instant instant) {
        super(instant);
        this.platform = platform.getName();
        this.source = source;
    }
}
