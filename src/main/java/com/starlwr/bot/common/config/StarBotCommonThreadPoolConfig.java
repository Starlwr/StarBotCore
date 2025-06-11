package com.starlwr.bot.common.config;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * StarBotCommon 线程池配置类
 */
@Slf4j
@Configuration
public class StarBotCommonThreadPoolConfig {
    @Resource
    private StarBotCommonProperties properties;

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

    private static class NetworkWithLogCallerRunsPolicy implements RejectedExecutionHandler {
        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            log.warn("网络请求线程池资源已耗尽, 请考虑增加线程池大小!");
            r.run();
        }
    }

    @Bean
    public ThreadPoolTaskExecutor eventHandlerThreadPool() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getEventHandlerThread().getCorePoolSize());
        executor.setMaxPoolSize(properties.getEventHandlerThread().getMaxPoolSize());
        executor.setQueueCapacity(properties.getEventHandlerThread().getQueueCapacity());
        executor.setKeepAliveSeconds(properties.getEventHandlerThread().getKeepAliveSeconds());
        executor.setThreadNamePrefix("handler-thread-");
        executor.setRejectedExecutionHandler(new EventHandlerWithLogCallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    private static class EventHandlerWithLogCallerRunsPolicy implements RejectedExecutionHandler {
        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            log.warn("事件处理线程池资源已耗尽, 请考虑增加线程池大小!");
            r.run();
        }
    }
}
