package com.starlwr.bot.core.event.datasource.change;

import com.starlwr.bot.core.event.datasource.base.StarBotDataSourceChangeEvent;
import com.starlwr.bot.core.model.PushUser;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * 数据源推送用户更新事件
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class StarBotDataSourceUpdateEvent extends StarBotDataSourceChangeEvent {
    public StarBotDataSourceUpdateEvent(PushUser user) {
        super(user);
    }

    public StarBotDataSourceUpdateEvent(PushUser user, Instant instant) {
        super(user, instant);
    }
}
