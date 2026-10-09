package com.starlwr.bot.core.service;

import com.alibaba.fastjson2.JSON;
import com.starlwr.bot.core.command.StarBotCommand;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import com.starlwr.bot.core.enums.PushTargetType;
import com.starlwr.bot.core.model.CommandContext;
import com.starlwr.bot.core.model.CommandMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

/**
 * StarBot 命令服务
 */
@Slf4j
@Service
public class StarBotCommandService {
    private final ThreadPoolTaskExecutor executor;

    private final StarBotCoreProperties properties;

    private final StarBotCommandRegistry registry;

    private final StarBotCommandPolicy policy;

    private volatile boolean running = true;

    @Autowired
    public StarBotCommandService(@Qualifier("commandThreadPool") ThreadPoolTaskExecutor executor, StarBotCoreProperties properties, StarBotCommandRegistry registry, StarBotCommandPolicy policy) {
        this.executor = executor;
        this.properties = properties;
        this.registry = registry;
        this.policy = policy;
    }

    /**
     * 停止接收新命令
     */
    @Order(0)
    @EventListener(ContextClosedEvent.class)
    public void close() {
        running = false;
        executor.shutdown();
    }

    /**
     * 提交消息，各消息平台接收到消息后，均应调用此方法将消息提交到命令服务
     * @param messageSupplier 命令消息工厂
     * @return 异步执行结果
     */
    public CompletableFuture<Void> dispatch(Supplier<Optional<CommandMessage>> messageSupplier) {
        CompletableFuture<Void> completion = new CompletableFuture<>();

        if (!running) {
            RejectedExecutionException e = new RejectedExecutionException("命令服务已关闭");
            completion.completeExceptionally(e);
            return completion;
        }

        try {
            executor.execute(() -> execute(messageSupplier, completion));
        } catch (Exception e) {
            log.error("向命令线程池提交任务失败", e);
            completion.completeExceptionally(e);
        }

        return completion;
    }

    /**
     * 构建命令消息并处理
     * @param messageSupplier 命令消息工厂
     * @param completion 异步执行结果
     */
    private void execute(Supplier<Optional<CommandMessage>> messageSupplier, CompletableFuture<Void> completion) {
        Optional<CommandMessage> optionalMessage;

        try {
            optionalMessage = messageSupplier.get();
        } catch (Exception e) {
            log.error("构建命令消息异常", e);
            completion.completeExceptionally(e);
            return;
        }

        if (optionalMessage.isEmpty()) {
            completion.complete(null);
            return;
        }
        CommandMessage message = optionalMessage.get();

        try {
            process(message);
            completion.complete(null);
        } catch (Exception e) {
            log.error("处理消息失败: {}", JSON.toJSONString(message), e);
            completion.completeExceptionally(e);
        }
    }

    /**
     * 根据命令触发规则执行所有匹配命令
     * @param message 命令消息
     */
    private void process(CommandMessage message) {
        // 判断是否提及机器人
        if (properties.getCommand().isMention() && message.getType() == PushTargetType.GROUP && !message.isMentioned() && !message.isReplyToBot()) {
            return;
        }

        // 检查命令前缀并尝试创建命令上下文
        Optional<CommandContext> optionalContext = CommandContext.tryCreate(message, properties.getCommand().getPrefix());
        if (optionalContext.isEmpty()) {
            return;
        }
        CommandContext context = optionalContext.get();

        // 查找所有匹配的命令
        List<StarBotCommand> matched = new ArrayList<>();
        for (StarBotCommand command : registry.getCommands()) {
            try {
                if (command.isTrigger(context)) {
                    matched.add(command);
                }
            } catch (Exception e) {
                log.error("命令 {} ({}) 匹配异常, 消息: {}", command.getName(), command.getId(), JSON.toJSONString(message), e);
            }
        }

        // 按命令策略执行所有匹配的命令
        for (StarBotCommand command : matched) {
            try {
                StarBotCommandPolicy.Decision decision = policy.evaluate(command, context);
                if (decision.isDisabled()) {
                    if (decision.isGlobalDisabled()) {
                        log.info("[{}] {} 命令 {} ({}) 未执行: {}", context.getType().getStr(), context.getTargetId(), command.getName(), command.getId(), "命令已全局禁用");
                    } else if (decision.isGroupDisabled()) {
                        log.info("[{}] {} 命令 {} ({}) 未执行: {}", context.getType().getStr(), context.getTargetId(), command.getName(), command.getId(), "命令已在当前会话禁用");
                        context.reply("此命令已被禁用~");
                    }
                    continue;
                }

                if (!decision.isAllowed()) {
                    if (!decision.isScopeAllowed()) {
                        log.info("[{}] {} 命令 {} ({}) 未执行: {}", context.getType().getStr(), context.getTargetId(), command.getName(), command.getId(), "命令不适用于当前会话类型");
                        context.reply("此命令不适用于当前会话类型~");
                    } else if (!decision.isPermissionAllowed()) {
                        log.info("[{}] {} 命令 {} ({}) 未执行: {}", context.getType().getStr(), context.getTargetId(), command.getName(), command.getId(), "权限不足");
                        context.reply("您没有执行此命令的权限~");
                    }
                    continue;
                }

                command.execute(context);
                log.info("[{}] {} 命令 {} ({}) 执行成功", context.getType().getStr(), context.getTargetId(), command.getName(), command.getId());
            } catch (Exception e) {
                log.error("命令 {} ({}) 执行异常, 消息: {}", command.getName(), command.getId(), JSON.toJSONString(message), e);
            }
        }
    }
}
