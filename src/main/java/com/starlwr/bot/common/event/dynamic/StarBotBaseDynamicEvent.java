package com.starlwr.bot.common.event.dynamic;

import com.starlwr.bot.common.enums.LivePlatform;
import com.starlwr.bot.common.event.StarBotBaseEvent;
import com.starlwr.bot.common.model.LiveStreamerInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * StarBot 动态事件基类
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class StarBotBaseDynamicEvent extends StarBotBaseEvent {
    /**
     * 直播平台
     */
    private String platform;

    /**
     * 主播信息
     */
    private LiveStreamerInfo source;

    public StarBotBaseDynamicEvent(String platform, LiveStreamerInfo source) {
        this.platform = platform;
        this.source = source;
    }

    public StarBotBaseDynamicEvent(String platform, LiveStreamerInfo source, Instant instant) {
        super(instant);
        this.platform = platform;
        this.source = source;
    }

    public StarBotBaseDynamicEvent(LivePlatform platform, LiveStreamerInfo source) {
        this.platform = platform.getName();
        this.source = source;
    }

    public StarBotBaseDynamicEvent(LivePlatform platform, LiveStreamerInfo source, Instant instant) {
        super(instant);
        this.platform = platform.getName();
        this.source = source;
    }
}
