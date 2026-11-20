package com.starlwr.bot.core.config;

import lombok.NonNull;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.util.ClassUtils;

import java.beans.PropertyDescriptor;
import java.lang.reflect.Method;

/**
 * StarBot 配置类 Getter 后置处理器
 */
public class ConfigGetterPostProcessor implements BeanPostProcessor {
    /**
     * 配置管理服务
     */
    private final ConfigService configService;

    public ConfigGetterPostProcessor(ConfigService configService) {
        this.configService = configService;
    }

    @Override
    public Object postProcessAfterInitialization(@NonNull Object bean, @NonNull String name) {
        Class<?> type = ClassUtils.getUserClass(bean);
        StarBotConfiguration configuration = type.getAnnotation(StarBotConfiguration.class);
        if (configuration == null) {
            return bean;
        }
        String prefix = configuration.prefix();
        configService.registerDefaults(prefix, BeanUtils.instantiateClass(type));

        ProxyFactory factory = new ProxyFactory(bean);
        factory.setProxyTargetClass(true);
        factory.addAdvice((MethodInterceptor) invocation -> {
            Method method = invocation.getMethod();
            PropertyDescriptor property = BeanUtils.findPropertyForMethod(method, type);
            if (property == null || !method.equals(property.getReadMethod()) || "class".equals(property.getName())) {
                return invocation.proceed();
            }
            String key = prefix + "." + ConfigService.toConfigKey(property.getName());
            return configService.get(key, method.getGenericReturnType());
        });
        return factory.getProxy(type.getClassLoader());
    }
}
