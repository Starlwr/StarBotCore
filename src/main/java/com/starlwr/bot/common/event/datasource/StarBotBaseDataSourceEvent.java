package com.starlwr.bot.common.event.datasource;

import com.starlwr.bot.common.event.StarBotBaseEvent;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * StarBot 数据源事件基类
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class StarBotBaseDataSourceEvent extends StarBotBaseEvent {
    public StarBotBaseDataSourceEvent(Instant instant) {
        super(instant);
    }
}
