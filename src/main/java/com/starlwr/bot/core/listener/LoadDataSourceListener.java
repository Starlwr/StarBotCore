package com.starlwr.bot.core.listener;

import com.starlwr.bot.core.datasource.AbstractDataSource;
import jakarta.annotation.Resource;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * StarBot 应用就绪后加载数据源
 */
@Slf4j
@Order(0)
@Component
public class LoadDataSourceListener implements ApplicationListener<ApplicationReadyEvent> {
    @Resource
    private AbstractDataSource dataSource;

    @Override
    public void onApplicationEvent(@NonNull ApplicationReadyEvent event) {
        dataSource.load();
    }

    @Override
    public boolean supportsAsyncExecution() {
        return false;
    }
}
