package com.starlwr.bot.core.ai;

import lombok.Getter;
import lombok.Setter;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI 模型配置
 */
@Getter
@Setter
public class AiModel {
    /**
     * 接口协议
     */
    private AiProvider provider;

    /**
     * API 接口地址
     */
    private String baseUrl;

    /**
     * API Key
     */
    private String apiKey;

    /**
     * 服务端模型名称
     */
    private String model;

    /**
     * 采样温度，留空时使用服务端默认值
     */
    private Double temperature;

    /**
     * 最大输出 token 数
     */
    private int maxTokens = 128;

    /**
     * 网络请求超时时间
     */
    private Duration timeout = Duration.ofSeconds(30);

    /**
     * 网络请求最大重试次数，不包含首次请求
     */
    private int maxRetries = 0;

    /**
     * 结构化校验失败时最大自动修复次数，不包含首次请求
     */
    private int structuredOutputMaxRepeatAttempts = 3;

    /**
     * 自定义请求头，可用于兼容接口的额外认证
     */
    private Map<String, String> headers = new LinkedHashMap<>();

    /**
     * 额外请求体参数，仅 OPENAI 协议生效，可用于第三方模型的专有参数
     */
    private Map<String, Object> extraBody = new LinkedHashMap<>();
}
