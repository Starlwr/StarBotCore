package com.starlwr.bot.core.ai;

import com.starlwr.bot.core.config.StarBotCoreProperties;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.StructuredOutputValidationAdvisor;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * AI 服务
 */
@Service
public class StarBotAiService {
    private final String defaultModel;

    private final Map<String, ModelClient> clients;

    private final ThreadPoolTaskExecutor executor;

    private volatile boolean closing;

    /**
     * 已配置的 AI 模型客户端
     */
    @Getter
    @RequiredArgsConstructor
    private static final class ModelClient {
        /**
         * 聊天客户端
         */
        private final ChatClient client;

        /**
         * 流式等待超时时间
         */
        private final Duration timeout;

        /**
         * 结构化校验失败时最大自动修复次数
         */
        private final int structuredOutputMaxRepeatAttempts;
    }

    @Autowired
    public StarBotAiService(@Qualifier("aiThreadPool") ThreadPoolTaskExecutor executor, StarBotCoreProperties properties, AiChatModelFactory factory) {
        this.executor = executor;

        Map<String, ModelClient> modelClients = new LinkedHashMap<>();
        for (Map.Entry<String, AiModel> entry : properties.getAi().getModels().entrySet()) {
            AiModel config = entry.getValue();
            ChatClient client = ChatClient.builder(factory.create(entry.getKey(), config)).build();
            modelClients.put(entry.getKey(), new ModelClient(client, config.getTimeout(), config.getStructuredOutputMaxRepeatAttempts()));
        }

        String defaultModel = properties.getAi().getDefaultModel();
        if (StringUtils.isBlank(defaultModel) && modelClients.size() == 1) {
            defaultModel = modelClients.keySet().iterator().next();
        }

        if (MapUtils.isNotEmpty(modelClients)) {
            Assert.hasText(defaultModel, "配置多个 AI 模型时必须指定默认模型 default-model");
            Assert.isTrue(modelClients.containsKey(defaultModel), "默认 AI 模型 default-model 必须为已配置的模型");
        }

        this.defaultModel = defaultModel;
        this.clients = Collections.unmodifiableMap(modelClients);
    }

    /**
     * 停止接收新的请求
     */
    @Order(0)
    @EventListener(ContextClosedEvent.class)
    public void onContextClosedEvent() {
        closing = true;
    }

    /**
     * 获取所有模型配置名称
     * @return 配置名称集合
     */
    public Set<String> getModelNames() {
        return clients.keySet();
    }

    /**
     * 查找指定配置的客户端
     * @param name 已配置的模型名称
     * @return 模型客户端
     */
    private ModelClient getModelClient(String name) {
        Assert.state(MapUtils.isNotEmpty(clients), "AI 服务不可用，请先在 starbot.core.ai.models 中配置模型");
        Assert.state(!closing, "AI 服务已关闭");
        String modelName = StringUtils.defaultIfBlank(name, defaultModel);
        ModelClient client = clients.get(modelName);
        Assert.notNull(client, "AI 模型配置不存在: " + modelName);
        return client;
    }

    /**
     * 校验并返回提示词副本
     * @param prompt 提示词
     * @return 提示词副本
     */
    private Prompt copyPrompt(Prompt prompt) {
        Assert.notNull(prompt, "AI 提示词不能为空");
        Assert.notEmpty(prompt.getInstructions(), "AI 消息列表不能为空");
        Assert.noNullElements(prompt.getInstructions(), "AI 消息列表不能包含 null");
        return prompt.copy();
    }

    /**
     * 获取默认模型客户端
     * @return 默认模型客户端
     */
    public ChatClient getChatClient() {
        return getChatClient(null);
    }

    /**
     * 获取指定配置的模型客户端
     * @param name 已配置的模型名称
     * @return 指定配置的模型客户端
     */
    public ChatClient getChatClient(String name) {
        return getModelClient(name).getClient();
    }

    /**
     * 使用默认模型生成纯文本回复
     * @param message 用户消息
     * @return 回复文本
     */
    public String chat(String message) {
        return chat(new Prompt(message)).getResult().getOutput().getText();
    }

    /**
     * 同步调用默认模型
     * @param prompt 提示词，多轮对话需包含会话历史
     * @return 回复
     */
    public ChatResponse chat(Prompt prompt) {
        return chat(null, prompt);
    }

    /**
     * 同步调用指定模型
     * @param name 已配置的模型名称
     * @param prompt 提示词，多轮对话需包含会话历史
     * @return 回复
     */
    public ChatResponse chat(String name, Prompt prompt) {
        ChatResponse response = getChatClient(name).prompt(copyPrompt(prompt)).call().chatResponse();
        Assert.state(response != null && response.getResult() != null, "AI 模型未返回有效回复");
        return response;
    }

    /**
     * 异步调用默认模型
     * @param prompt 提示词，多轮对话需包含会话历史
     * @return 异步回复
     */
    public CompletableFuture<ChatResponse> chatAsync(Prompt prompt) {
        return chatAsync(null, prompt);
    }

    /**
     * 异步调用指定模型
     * @param name 已配置的模型名称
     * @param prompt 提示词，多轮对话需包含会话历史
     * @return 异步回复
     */
    public CompletableFuture<ChatResponse> chatAsync(String name, Prompt prompt) {
        try {
            Prompt snapshot = copyPrompt(prompt);
            getModelClient(name);
            return CompletableFuture.supplyAsync(() -> chat(name, snapshot), executor);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * 使用默认模型生成结构化回复
     * @param prompt 提示词，多轮对话需包含会话历史
     * @param type 结构化类型
     * @return 结构化回复
     */
    public <T> T entity(Prompt prompt, Class<T> type) {
        return entity(null, prompt, ParameterizedTypeReference.forType(type));
    }

    /**
     * 使用指定模型生成结构化回复
     * @param name 已配置的模型名称
     * @param prompt 提示词，多轮对话需包含会话历史
     * @param type 结构化类型
     * @return 结构化回复
     */
    public <T> T entity(String name, Prompt prompt, Class<T> type) {
        return entity(name, prompt, ParameterizedTypeReference.forType(type));
    }

    /**
     * 使用默认模型生成结构化回复
     * @param prompt 提示词，多轮对话需包含会话历史
     * @param type 结构化类型，支持 List 等泛型
     * @return 结构化回复
     */
    public <T> T entity(Prompt prompt, ParameterizedTypeReference<T> type) {
        return entity(null, prompt, type);
    }

    /**
     * 使用指定模型生成结构化回复
     * @param name 已配置的模型名称
     * @param prompt 提示词，多轮对话需包含会话历史
     * @param type 结构化类型，支持 List 等泛型
     * @return 结构化回复
     */
    public <T> T entity(String name, Prompt prompt, ParameterizedTypeReference<T> type) {
        Assert.notNull(type, "AI 结构化回复类型不能为空");
        ModelClient modelClient = getModelClient(name);
        StructuredOutputValidationAdvisor validator = StructuredOutputValidationAdvisor.builder().outputType(type).maxRepeatAttempts(modelClient.getStructuredOutputMaxRepeatAttempts()).build();
        T result = modelClient.getClient().prompt(copyPrompt(prompt)).advisors(validator).call().entity(type);
        Assert.state(result != null, "AI 未返回有效的结构化回复");
        return result;
    }

    /**
     * 使用默认模型异步生成结构化回复
     * @param prompt 提示词，多轮对话需包含会话历史
     * @param type 结构化类型
     * @return 异步结构化回复
     */
    public <T> CompletableFuture<T> entityAsync(Prompt prompt, Class<T> type) {
        return entityAsync(null, prompt, ParameterizedTypeReference.forType(type));
    }

    /**
     * 使用指定模型异步生成结构化回复
     * @param name 已配置的模型名称
     * @param prompt 提示词，多轮对话需包含会话历史
     * @param type 结构化类型
     * @return 异步结构化回复
     */
    public <T> CompletableFuture<T> entityAsync(String name, Prompt prompt, Class<T> type) {
        return entityAsync(name, prompt, ParameterizedTypeReference.forType(type));
    }

    /**
     * 使用默认模型异步生成结构化回复
     * @param prompt 提示词，多轮对话需包含会话历史
     * @param type 结构化类型，支持 List 等泛型
     * @return 异步结构化回复
     */
    public <T> CompletableFuture<T> entityAsync(Prompt prompt, ParameterizedTypeReference<T> type) {
        return entityAsync(null, prompt, type);
    }

    /**
     * 使用指定模型异步生成结构化回复
     * @param name 已配置的模型名称
     * @param prompt 提示词，多轮对话需包含会话历史
     * @param type 结构化类型，支持 List 等泛型
     * @return 异步结构化回复
     */
    public <T> CompletableFuture<T> entityAsync(String name, Prompt prompt, ParameterizedTypeReference<T> type) {
        try {
            Prompt snapshot = copyPrompt(prompt);
            Assert.notNull(type, "AI 结构化回复类型不能为空");
            getModelClient(name);
            return CompletableFuture.supplyAsync(() -> entity(name, snapshot, type), executor);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * 流式调用默认模型
     * @param prompt 提示词，多轮对话需包含会话历史
     * @return 回复数据流
     */
    public Flux<ChatResponse> stream(Prompt prompt) {
        return stream(null, prompt);
    }

    /**
     * 流式调用指定模型
     * @param name 已配置的模型名称
     * @param prompt 提示词，多轮对话需包含会话历史
     * @return 回复数据流
     */
    public Flux<ChatResponse> stream(String name, Prompt prompt) {
        return Flux.defer(() -> {
            ModelClient modelClient = getModelClient(name);
            return modelClient.getClient().prompt(copyPrompt(prompt)).stream().chatResponse().timeout(modelClient.getTimeout());
        });
    }
}
