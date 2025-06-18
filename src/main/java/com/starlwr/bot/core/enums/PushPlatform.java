package com.starlwr.bot.core.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 推送平台
 */
@Getter
@AllArgsConstructor
public enum PushPlatform {
    QQ_NAPCAT("qq-napcat"),
    QQ_OVERFLOW("qq-overflow"),
    QQ_OFFICIAL("qq-official"),
    WE_CHAT("wechat"),
    TELEGRAM("telegram"),
    SMS("sms");

    private final String name;
}
