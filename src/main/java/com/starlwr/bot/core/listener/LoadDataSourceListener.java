package com.starlwr.bot.core.listener;

import com.starlwr.bot.core.datasource.AbstractDataSource;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * StarBot 应用就绪后加载数据源
 */
@Slf4j
@Order(0)
@Component
public class LoadDataSourceListener {
    @Resource
    private AbstractDataSource dataSource;

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationEvent() {
        dataSource.load();
    }
}
