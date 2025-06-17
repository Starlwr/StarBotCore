package com.starlwr.bot.core.datasource;

import com.starlwr.bot.core.service.DataSourceService;
import com.starlwr.bot.core.service.DataSourceServiceInterface;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 数据源服务注册表
 */
@Component
public class DataSourceServiceRegistry {
    private final Map<String, DataSourceServiceInterface> serviceMap = new HashMap<>();

    @Autowired
    public DataSourceServiceRegistry(List<DataSourceServiceInterface> services) {
        for (DataSourceServiceInterface service : services) {
            DataSourceService annotation = service.getClass().getAnnotation(DataSourceService.class);
            if (annotation != null) {
                serviceMap.put(annotation.name(), service);
            }
        }
    }

    public Optional<DataSourceServiceInterface> getDataSourceService(String platform) {
        return Optional.ofNullable(serviceMap.get(platform));
    }
}
