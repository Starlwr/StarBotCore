package com.starlwr.bot.core.push.datasource;

import org.apache.commons.lang3.StringUtils;
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
    private final Map<String, DataSourceService> serviceMap = new HashMap<>();

    @Autowired
    public DataSourceServiceRegistry(List<DataSourceService> services) {
        for (DataSourceService service : services) {
            String platform = service.getPlatform();
            if (StringUtils.isBlank(platform)) {
                throw new IllegalArgumentException("数据源服务的直播平台名称不能为空: " + service.getClass().getName());
            }

            if (serviceMap.putIfAbsent(platform, service) != null) {
                throw new IllegalArgumentException("直播平台 " + platform + " 存在重复的数据源服务");
            }
        }
    }

    /**
     * 获取指定直播平台的数据源服务
     * @param platform 直播平台名称
     * @return 数据源服务
     */
    public Optional<DataSourceService> getDataSourceService(String platform) {
        return Optional.ofNullable(serviceMap.get(platform));
    }
}
