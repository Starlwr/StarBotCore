package com.starlwr.bot.common.config;

import com.starlwr.bot.common.datasource.AbstractDataSource;
import com.starlwr.bot.common.datasource.EmptyDataSource;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * 数据源配置类
 */
@Profile("!mysql")
@Configuration
@EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
public class DataSourceConfig {
    @Bean
    @ConditionalOnMissingBean(AbstractDataSource.class)
    public AbstractDataSource emptyDataSource(ApplicationEventPublisher publisher) {
        EmptyDataSource dataSource = new EmptyDataSource();
        dataSource.setEventPublisher(publisher);
        return dataSource;
    }
}
