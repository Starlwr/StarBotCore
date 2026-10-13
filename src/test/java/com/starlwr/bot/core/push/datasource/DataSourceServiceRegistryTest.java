package com.starlwr.bot.core.push.datasource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 数据源服务注册表测试
 */
class DataSourceServiceRegistryTest {
    /**
     * 验证 Spring 自动发现接口实现，并支持代理对象及自定义平台名称
     */
    @Test
    void discoversServicesByInterfaceIncludingProxiesAndCustomPlatforms() {
        DataSourceService bilibili = mock(DataSourceService.class);
        when(bilibili.getPlatform()).thenReturn("bilibili");
        ProxyFactory factory = new ProxyFactory(bilibili);
        factory.setInterfaces(DataSourceService.class);
        DataSourceService proxy = (DataSourceService) factory.getProxy();
        DataSourceService custom = mock(DataSourceService.class);
        when(custom.getPlatform()).thenReturn("custom-platform");

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean("bilibiliDataSourceService", DataSourceService.class, () -> proxy);
            context.registerBean("customDataSourceService", DataSourceService.class, () -> custom);
            context.register(DataSourceServiceRegistry.class);
            context.refresh();

            DataSourceServiceRegistry registry = context.getBean(DataSourceServiceRegistry.class);
            assertSame(proxy, registry.getDataSourceService("bilibili").orElseThrow());
            assertSame(custom, registry.getDataSourceService("custom-platform").orElseThrow());
            assertTrue(registry.getDataSourceService("unknown-platform").isEmpty());
        }
    }

    /**
     * 验证空平台名称不能注册
     */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void rejectsBlankPlatformNames(String platform) {
        DataSourceService service = mock(DataSourceService.class);
        when(service.getPlatform()).thenReturn(platform);

        assertThrows(IllegalArgumentException.class, () -> new DataSourceServiceRegistry(List.of(service)));
    }

    /**
     * 验证同一平台的多个服务不能相互覆盖
     */
    @Test
    void rejectsDuplicatePlatforms() {
        DataSourceService first = mock(DataSourceService.class);
        when(first.getPlatform()).thenReturn("bilibili");
        DataSourceService second = mock(DataSourceService.class);
        when(second.getPlatform()).thenReturn("bilibili");

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new DataSourceServiceRegistry(List.of(first, second)));

        assertTrue(exception.getMessage().contains("bilibili"));
    }
}
