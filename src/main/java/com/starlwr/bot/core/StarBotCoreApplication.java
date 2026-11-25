package com.starlwr.bot.core;

import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.config.ConfigGetterPostProcessor;
import com.starlwr.bot.core.config.ConfigService;
import com.starlwr.bot.core.config.SpringPropertyBridge;
import com.starlwr.bot.core.config.service.ConfigActionService;
import com.starlwr.bot.core.config.service.ConfigEditService;
import com.starlwr.bot.core.config.service.ConfigMetaRegistry;
import com.starlwr.bot.core.config.web.ConfigServer;
import com.starlwr.bot.core.config.web.ConfigWebEndpoint;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.convert.ConversionException;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.PlaceholderResolutionException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.BindException;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * StarBotCore 启动类
 */
@Slf4j
@EnableAsync
@EnableRetry
@EnableScheduling
@EnableAspectJAutoProxy(exposeProxy = true)
@SpringBootApplication
public class StarBotCoreApplication {
    /**
     * 根据启动参数和配置状态启动 StarBot 或独立配置中心
     * @param args 启动参数
     */
    public static void main(String[] args) {
        // 读取启动参数并加载配置元数据
        boolean editing = Arrays.asList(args).contains("--config");
        ConfigMetaRegistry registry;
        try {
            registry = new ConfigMetaRegistry(Path.of("plugins"));
        } catch (IOException e) {
            log.error(e.getMessage());
            System.exit(1);
            return;
        } catch (RuntimeException e) {
            log.error("初始化配置元数据注册表失败", e);
            System.exit(1);
            return;
        }

        // 根据配置元数据读取用户配置并注册文件监听
        ConfigService configService;
        SpringPropertyBridge springPropertyBridge;
        try {
            springPropertyBridge = new SpringPropertyBridge(registry);
            configService = new ConfigService(Path.of("data/config.json"), registry, springPropertyBridge,
                    editing || registry.hasConfigurationClassConflicts());
        } catch (UncheckedIOException e) {
            log.error(e.getMessage());
            System.exit(1);
            return;
        } catch (RuntimeException e) {
            log.error("初始化配置管理服务失败", e);
            System.exit(1);
            return;
        }

        // 启动失败时关闭已经注册的文件监听
        try {
            start(args, editing, configService, springPropertyBridge);
        } catch (RuntimeException | Error e) {
            configService.destroy();
            throw e;
        }
    }

    /**
     * 根据启动配置启动 StarBot 或独立配置中心
     * @param args 启动参数
     * @param editing 是否以独立配置模式启动
     * @param configService 配置管理服务
     * @param springPropertyBridge Spring 属性桥接器
     */
    private static void start(String[] args, boolean editing, ConfigService configService, SpringPropertyBridge springPropertyBridge) {
        ConfigMetaRegistry registry = configService.getRegistry();
        boolean configEnabled = !Boolean.FALSE.equals(configService.get("starbot.core.config-center.enabled"));
        String formatError = configService.getStartupFormatError();
        boolean invalidDocument = !editing && formatError != null;
        List<JSONObject> errors = editing || invalidDocument ? List.of() : configService.getStartupValidationErrors().stream()
                .filter(error -> !"duplicateSpringProperty".equals(error.getString("code"))).toList();
        boolean invalid = invalidDocument || CollectionUtils.isNotEmpty(errors);
        boolean conflicts = configService.hasUnresolvedConflicts();
        boolean classConflicts = registry.hasConfigurationClassConflicts();
        List<SpringPropertyBridge.Duplicate> duplicates = configService.getStartupDuplicates();
        boolean hasDuplicates = CollectionUtils.isNotEmpty(duplicates);
        if (hasDuplicates) {
            duplicates.forEach(duplicate -> log.warn(duplicate.message()));
        }

        if (invalid || conflicts || hasDuplicates || classConflicts) {
            List<String> reasons = new ArrayList<>();
            if (invalid) {
                reasons.add(invalidDocument ? "配置文件格式错误（" + formatError + "）" : "配置项校验错误");
            }
            if (conflicts) {
                reasons.add("未解决的配置冲突");
            }
            if (hasDuplicates) {
                reasons.add("重复的 Spring 属性");
            }
            if (classConflicts) {
                reasons.add("配置类声明冲突");
            }
            String reason = "存在" + String.join("和", reasons);

            if (!configEnabled) {
                configService.destroy();
                String files = classConflicts
                        ? invalid || conflicts || hasDuplicates ? "插件配置元数据和 data/config.json" : "插件配置元数据"
                        : "data/config.json";
                log.error("{}, 且配置中心已关闭, 请修正 {} 后重启 StarBot", reason, files);
                System.exit(1);
                return;
            }

            if (!editing) {
                log.warn("{}, 已切换至独立配置中心, {}后重启 StarBot", reason,
                        classConflicts ? "请查看配置声明问题并处理" : "请处理");
            }
            editing = true;
        }

        if (editing && !configEnabled) {
            configService.destroy();
            log.error("配置中心已关闭, 无法使用 --config. 请在 data/config.json 中将 starbot.core.config-center.enabled 改为 true 后重试");
            System.exit(1);
            return;
        }

        // 获取本次启动使用的 Spring 属性
        Map<String, Object> properties = configService.getSpringProperties();

        // 准备登录密码、登录提示和网页接口
        String configuredPassword = configService.get("starbot.core.config-center.password", String.class);
        boolean randomPassword = configEnabled && StringUtils.isBlank(configuredPassword);
        String token = configEnabled ? createConfigPassword(configService) : null;
        String loginNotice = randomPassword ? (configService.isPasswordReset()
                ? "访问密码已重置, 请使用 StarBot 控制台中的随机密码登录"
                : "当前使用随机访问密码, 请查看 StarBot 控制台中的登录密码") : "";
        ConfigWebEndpoint endpoint = configEnabled ? new ConfigWebEndpoint(registry,
                new ConfigEditService(configService, springPropertyBridge),
                new ConfigActionService(registry, springPropertyBridge), token, randomPassword, !editing, loginNotice) : null;

        // 独立模式只启动配置 HTTP 服务和文件监听
        if (editing) {
            startConfigServer(args, properties, configService, endpoint, invalidDocument, errors);
            return;
        }

        // 将正常运行时需要的配置服务注册到 Spring
        SpringApplication application = new SpringApplication(StarBotCoreApplication.class);
        application.setDefaultProperties(properties);
        application.addInitializers(context -> {
            configService.initializeSpring(context.getEnvironment());
            GenericApplicationContext applicationContext = (GenericApplicationContext) context;
            applicationContext.registerBean("configService", ConfigService.class, () -> configService);
            applicationContext.registerBean("configGetterPostProcessor", ConfigGetterPostProcessor.class, () -> new ConfigGetterPostProcessor(configService));
            applicationContext.registerBean("configMetaRegistry", ConfigMetaRegistry.class, () -> registry);
            if (endpoint != null) {
                applicationContext.registerBean("configWebEndpoint", ConfigWebEndpoint.class, () -> endpoint);
            }
        });

        application.run(args);
    }

    /**
     * 启动独立配置中心
     * @param args 启动参数
     * @param properties 启动属性映射
     * @param configService 配置管理服务
     * @param endpoint 配置中心请求处理器
     * @param invalidDocument 是否因配置文件格式错误自动切换
     * @param errors 自动切换至独立配置中心时发现的校验错误
     */
    private static void startConfigServer(String[] args, Map<String, Object> properties, ConfigService configService,
                                          ConfigWebEndpoint endpoint, boolean invalidDocument, List<JSONObject> errors) {
        String message;
        try {
            // 加载启动属性，检查仍未解决的监听地址和端口冲突
            boolean invalidAddress = invalidDocument || hasInvalidListenerProperty(errors, "server.address");
            boolean invalidPort = invalidDocument || hasInvalidListenerProperty(errors, "server.port");
            if (invalidAddress) {
                properties.remove("server.address");
            }
            if (invalidPort) {
                properties.remove("server.port");
            }
            StandardEnvironment environment = new StandardEnvironment();
            environment.getPropertySources().addFirst(new SimpleCommandLinePropertySource(args));
            environment.getPropertySources().addLast(new MapPropertySource("configJson", properties));
            ConfigMetaRegistry registry = configService.getRegistry();
            String host = environment.getProperty("server.address");
            if (host == null && !invalidAddress && registry.hasSelectionConflict(List.of("advanced", "server.address"))) {
                message = "监听地址存在配置冲突, 请在 data/config.json 中设置 server.address 或使用 --server.address 指定";
            } else if (!environment.containsProperty("server.port") && !invalidPort && registry.hasSelectionConflict(List.of("advanced", "server.port"))) {
                message = "监听端口存在配置冲突, 请在 data/config.json 中设置 server.port 或使用 --server.port 指定";
            } else {
                // 非整数和超出范围的端口使用同一提示
                Integer port;
                try {
                    port = environment.getConversionService().convert(environment.getProperty("server.port", "7827"), Integer.class);
                } catch (ConversionException e) {
                    port = null;
                }
                if (port == null || port < 0 || port > 65535) {
                    message = "监听端口必须是 0 到 65535 之间的整数, 请检查 server.port";
                } else {
                    // 初始化生效配置，绑定端口并登记退出时要关闭的服务
                    if (!invalidDocument && errors.isEmpty()) {
                        configService.initializeSpring(environment);
                    }
                    String address = host != null ? host : invalidAddress ? "127.0.0.1" : "0.0.0.0";
                    ConfigServer server = new ConfigServer(address, port, endpoint);
                    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                        try {
                            server.stop();
                        } finally {
                            configService.destroy();
                        }
                    }, "config-shutdown"));
                    configService.startWatching();
                    server.start();
                    endpoint.printAccessInfo(address, server.getPort());
                    return;
                }
            }
        } catch (UnknownHostException e) {
            message = "无法解析配置中心监听地址, 请检查 server.address";
        } catch (BindException e) {
            message = "无法绑定配置中心监听地址, 请检查 server.port 是否已被占用";
        } catch (PlaceholderResolutionException e) {
            message = "配置中心启动属性存在无法解析的占位符, 请检查配置引用及其默认值";
        } catch (ConversionException e) {
            message = "配置中心启动属性的值无法转换, 请检查与 Spring 关联的配置项类型";
        } catch (IOException | RuntimeException e) {
            log.error("启动独立配置中心失败", e);
            message = null;
        }
        configService.destroy();
        if (message != null) {
            log.error(message);
        }
        System.exit(1);
    }

    /**
     * 判断监听属性或其上级节点是否存在校验错误
     * @param errors 配置校验错误
     * @param property Spring 监听属性名
     * @return 监听属性是否无效
     */
    private static boolean hasInvalidListenerProperty(List<JSONObject> errors, String property) {
        List<String> path = List.of("advanced", property);
        return errors.stream().anyMatch(error -> {
            List<?> invalidPath = error.getJSONArray("path");
            return invalidPath.size() <= path.size() && path.subList(0, invalidPath.size()).equals(invalidPath);
        });
    }

    /**
     * 获取配置中心的登录凭据
     * @param configService 配置服务
     * @return 固定密码哈希或随机密码
     */
    private static String createConfigPassword(ConfigService configService) {
        String configured = configService.get("starbot.core.config-center.password", String.class);
        if (StringUtils.isNotBlank(configured)) {
            return configured;
        }
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        SecureRandom random = new SecureRandom();
        StringBuilder password = new StringBuilder(32);
        for (int index = 0; index < 32; index++) {
            password.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return password.toString();
    }
}
