package com.starlwr.bot.core.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * StarBotCore 线程池配置类
 */
@Slf4j
@Configuration
public class StarBotCoreThreadPoolConfig {
    private final StarBotCoreProperties properties;

    @Autowired
    public StarBotCoreThreadPoolConfig(StarBotCoreProperties properties) {
        this.properties = properties;
    }

    @Bean
    public ThreadPoolTaskExecutor networkThreadPool() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getNetworkThread().getCorePoolSize());
        executor.setMaxPoolSize(properties.getNetworkThread().getMaxPoolSize());
        executor.setQueueCapacity(properties.getNetworkThread().getQueueCapacity());
        executor.setKeepAliveSeconds(properties.getNetworkThread().getKeepAliveSeconds());
        executor.setThreadNamePrefix("network-thread-");
        executor.setRejectedExecutionHandler(new NetworkWithLogCallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    @Bean
    public ThreadPoolTaskExecutor commandThreadPool() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getCommandThread().getCorePoolSize());
        executor.setMaxPoolSize(properties.getCommandThread().getMaxPoolSize());
        executor.setQueueCapacity(properties.getCommandThread().getQueueCapacity());
        executor.setKeepAliveSeconds(properties.getCommandThread().getKeepAliveSeconds());
        executor.setThreadNamePrefix("command-thread-");
        executor.setRejectedExecutionHandler(new CommandWithLogAbortPolicy());
        executor.initialize();
        return executor;
    }

    @Bean
    public ThreadPoolTaskExecutor aiThreadPool() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getAiThread().getCorePoolSize());
        executor.setMaxPoolSize(properties.getAiThread().getMaxPoolSize());
        executor.setQueueCapacity(properties.getAiThread().getQueueCapacity());
        executor.setKeepAliveSeconds(properties.getAiThread().getKeepAliveSeconds());
        executor.setThreadNamePrefix("ai-thread-");
        executor.setRejectedExecutionHandler(new AiWithLogAbortPolicy());
        executor.initialize();
        return executor;
    }

    private static class NetworkWithLogCallerRunsPolicy implements RejectedExecutionHandler {
        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            if (executor.isShutdown()) {
                return;
            }
            log.warn("网络请求线程池资源已耗尽, 请考虑增加线程池大小!");
            r.run();
        }
    }

    private static class CommandWithLogAbortPolicy implements RejectedExecutionHandler {
        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            throw new RejectedExecutionException("命令线程池资源已耗尽");
        }
    }

    private static class AiWithLogAbortPolicy implements RejectedExecutionHandler {
        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            throw new RejectedExecutionException("AI 线程池资源已耗尽");
        }
    }
}
