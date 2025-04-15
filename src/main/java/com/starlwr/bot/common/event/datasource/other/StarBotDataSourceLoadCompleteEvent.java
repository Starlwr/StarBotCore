package com.starlwr.bot.common.event.datasource.other;

import com.starlwr.bot.common.event.datasource.StarBotBaseDataSourceEvent;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * 数据源加载完毕事件
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class StarBotDataSourceLoadCompleteEvent extends StarBotBaseDataSourceEvent {
    public StarBotDataSourceLoadCompleteEvent(Instant instant) {
        super(instant);
    }
}
