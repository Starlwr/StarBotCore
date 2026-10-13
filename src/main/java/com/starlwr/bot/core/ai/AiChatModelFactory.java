package com.starlwr.bot.core.ai;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.MapUtils;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * AI 模型工厂
 */
@Slf4j
@Component
public class AiChatModelFactory {
    private final List<Runnable> closeHandlers = new ArrayList<>();

    /**
     * 根据配置创建模型
     * @param name 配置名称
     * @param config 模型配置
     * @return 模型
     */
    public ChatModel create(String name, AiModel config) {
        validate(name, config);
        return switch (config.getProvider()) {
            case OPENAI -> createOpenAiModel(config);
            case ANTHROPIC -> createAnthropicModel(config);
        };
    }

    /**
     * 检查模型配置
     * @param name 配置名称
     * @param config 模型配置
     */
    private void validate(String name, AiModel config) {
        Assert.hasText(name, "AI 模型配置名称不能为空");
        Assert.notNull(config, "AI 模型 " + name + " 的模型配置不能为空");

        String prefix = "AI 模型 " + name + " 模型配置的 ";
        Assert.notNull(config.getProvider(), prefix + "provider 不能为空");
        Assert.hasText(config.getBaseUrl(), prefix + "base-url 不能为空");
        Assert.hasText(config.getApiKey(), prefix + "api-key 不能为空");
        Assert.hasText(config.getModel(), prefix + "model 不能为空");
        Assert.isTrue(config.getMaxTokens() > 0, prefix + "max-tokens 必须大于 0");
        Assert.isTrue(config.getMaxRetries() >= 0, prefix + "max-retries 不能小于 0");
        Assert.isTrue(config.getStructuredOutputMaxRepeatAttempts() >= 0, prefix + "structured-output-max-repeat-attempts 不能小于 0");
        Assert.isTrue(config.getTimeout() != null && config.getTimeout().toMillis() > 0, prefix + "timeout 必须大于 0");

        try {
            URI uri = URI.create(config.getBaseUrl());
            Assert.isTrue(("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null,
                    prefix + "base-url 必须为有效的接口地址");
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(prefix + "base-url 必须为有效的接口地址");
        }
        Assert.isTrue(MapUtils.isEmpty(config.getExtraBody()) || config.getProvider() == AiProvider.OPENAI, prefix + "extra-body 仅支持 OPENAI 协议");
    }

    /**
     * 创建 OpenAI 兼容模型
     * @param config 模型配置
     * @return OpenAI 兼容模型
     */
    private ChatModel createOpenAiModel(AiModel config) {
        SpringAiOpenAiHttpClient http = SpringAiOpenAiHttpClient.builder().timeout(config.getTimeout()).build();
        closeHandlers.add(http::close);

        ClientOptions.Builder clientOptions = ClientOptions.builder()
                .httpClient(http)
                .baseUrl(config.getBaseUrl())
                .apiKey(config.getApiKey())
                .timeout(config.getTimeout())
                .maxRetries(config.getMaxRetries());
        if (MapUtils.isNotEmpty(config.getHeaders())) {
            config.getHeaders().forEach(clientOptions::putHeader);
        }

        OpenAIClient client = new OpenAIClientImpl(clientOptions.build());
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .timeout(config.getTimeout())
                .maxRetries(config.getMaxRetries())
                .model(config.getModel())
                .temperature(config.getTemperature())
                .maxTokens(config.getMaxTokens())
                .extraBody(config.getExtraBody() == null ? Map.of() : config.getExtraBody())
                .build();

        return OpenAiChatModel.builder().openAiClient(client).openAiClientAsync(client.async()).options(options).build();
    }

    /**
     * 创建 Anthropic 兼容模型
     * @param config 模型配置
     * @return Anthropic 兼容模型
     */
    private ChatModel createAnthropicModel(AiModel config) {
        AnthropicChatOptions options = AnthropicChatOptions.builder()
                .baseUrl(config.getBaseUrl())
                .apiKey(config.getApiKey())
                .timeout(config.getTimeout())
                .maxRetries(config.getMaxRetries())
                .customHeaders(config.getHeaders() == null ? Map.of() : config.getHeaders())
                .model(config.getModel())
                .temperature(config.getTemperature())
                .maxTokens(config.getMaxTokens())
                .build();

        AnthropicChatModel model = AnthropicChatModel.builder().options(options).build();
        closeHandlers.add(model.getAnthropicClient()::close);
        closeHandlers.add(model.getAnthropicClientAsync()::close);

        return model;
    }

    /**
     * 释放模型持有的 HTTP 连接和后台线程
     */
    @PreDestroy
    private void destroy() {
        for (Runnable closeHandler : closeHandlers) {
            try {
                closeHandler.run();
            } catch (Exception e) {
                log.error("关闭 AI 资源异常", e);
            }
        }

        closeHandlers.clear();
    }
}
