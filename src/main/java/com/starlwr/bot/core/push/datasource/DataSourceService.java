package com.starlwr.bot.core.push.datasource;

import com.starlwr.bot.core.event.model.LivePlatform;
import com.starlwr.bot.core.push.model.PushUser;

import java.util.List;

/**
 * 数据源服务接口，各直播平台实现均应实现此接口，用于获取各平台中的推送用户信息
 */
public interface DataSourceService {
    /**
     * 直播平台名称，请优先从 {@link LivePlatform} 中获取，若不存在可使用自定义字符串
     * @return 直播平台名称
     */
    String getPlatform();

    /**
     * 补全推送用户信息
     * @param user 推送用户
     */
    void completePushUser(PushUser user);

    /**
     * 批量补全推送用户信息
     * @param users 推送用户列表
     */
    default void completePushUsers(List<PushUser> users) {
        for (PushUser user : users) {
            completePushUser(user);
        }
    }
}
