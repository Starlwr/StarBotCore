package com.starlwr.bot.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 推送平台
 */
@Getter
@AllArgsConstructor
public enum PushPlatform {
    QQ("QQ"),
    QQOfficial("QQOfficial"),
    WeChat("WeChat"),
    Telegram("Telegram"),
    SMS("SMS");

    private final String name;
}
