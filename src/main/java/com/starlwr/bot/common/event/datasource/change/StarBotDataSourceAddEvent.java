package com.starlwr.bot.common.event.datasource.change;

import com.starlwr.bot.common.event.datasource.base.StarBotDataSourceChangeEvent;
import com.starlwr.bot.common.model.PushUser;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * 数据源推送用户新增事件
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
public class StarBotDataSourceAddEvent extends StarBotDataSourceChangeEvent {
    public StarBotDataSourceAddEvent(PushUser user) {
        super(user);
    }

    public StarBotDataSourceAddEvent(PushUser user, Instant instant) {
        super(user, instant);
    }
}
