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
        executor.setCorePoolSize(properties.getThread().getCorePoolSize());
        executor.setMaxPoolSize(properties.getThread().getMaxPoolSize());
        executor.setQueueCapacity(properties.getThread().getQueueCapacity());
        executor.setKeepAliveSeconds(properties.getThread().getKeepAliveSeconds());
        executor.setThreadNamePrefix("network-thread-");
        executor.setRejectedExecutionHandler(new WithLogCallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    private static class WithLogCallerRunsPolicy implements RejectedExecutionHandler {
        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            log.warn("网络请求线程池资源已耗尽, 请考虑增加线程池大小!");
            r.run();
        }
    }
}
