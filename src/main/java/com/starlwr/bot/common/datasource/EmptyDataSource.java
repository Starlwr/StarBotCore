package com.starlwr.bot.common.datasource;

import com.starlwr.bot.common.event.datasource.other.StarBotDataSourceLoadCompleteEvent;
import lombok.extern.slf4j.Slf4j;
import org.reflections.Reflections;
import org.reflections.scanners.Scanners;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 空数据源
 */
@Slf4j
public class EmptyDataSource extends AbstractDataSource {
    /**
     * 加载数据源，读取完毕后需调用 add 方法将推送用户添加至数据源中
     * PushUser 仅须填充 uid, platform, enabled, targets 字段
     * PushTarget 仅须填充 user, platform, type, num, enabled, messages 字段
     * PushMessage 仅须填充 target, event, handler, params, enabled 字段
     */
    @Override
    public void load() {
        Reflections reflections = new Reflections("com.starlwr.bot", Scanners.TypesAnnotated);
        Set<Class<?>> classes = reflections.getTypesAnnotatedWith(DataSource.class);
        List<String> names = classes.stream()
                .filter(AbstractDataSource.class::isAssignableFrom)
                .map(clazz -> clazz.getAnnotation(DataSource.class))
                .filter(Objects::nonNull)
                .map(DataSource::name)
                .collect(Collectors.toList());

        log.warn("未选用任何数据源, 将以空数据源启动, 请配置 spring.profiles.active 以选用数据源");
        log.warn("当前可用的数据源实现: {}", names);

        eventPublisher.publishEvent(new StarBotDataSourceLoadCompleteEvent(Instant.now()));
    }
}
