package com.starlwr.bot.core.config;

import lombok.Getter;

/**
 * 配置接口异常
 */
@Getter
public class ConfigException extends RuntimeException {
    /**
     * 接口返回的 HTTP 状态码
     */
    private final int status;

    /**
     * 页面定位问题时使用的校验结果或冲突详情
     */
    private final Object details;

    public ConfigException(int status, String message) {
        this(status, message, null);
    }

    public ConfigException(int status, String message, Object details) {
        super(message);
        this.status = status;
        this.details = details;
    }
}
