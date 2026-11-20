package com.starlwr.bot.core.config.web;

import com.starlwr.bot.core.config.ConfigService;
import com.starlwr.bot.core.config.SpringPropertyBridge;
import com.starlwr.bot.core.config.service.ConfigActionService;
import com.starlwr.bot.core.config.service.ConfigEditService;
import com.starlwr.bot.core.config.service.ConfigMetaRegistry;
import jakarta.servlet.ServletInputStream;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 配置中心控制器测试
 */
class ConfigControllerTest {
    @TempDir
    Path directory;

    /**
     * 配置元数据注册表
     */
    private ConfigMetaRegistry registry;

    /**
     * Spring 属性桥接器
     */
    private SpringPropertyBridge springPropertyBridge;

    /**
     * 准备配置元数据注册表和 Spring 属性桥接器
     */
    @BeforeEach
    void setUp() throws IOException {
        registry = new ConfigMetaRegistry(directory.resolve("plugins"));
        springPropertyBridge = new SpringPropertyBridge(registry);
    }

    /**
     * 仅提供配置请求处理器时注册控制器，关闭配置中心不创建入口
     */
    @Test
    void registersControllerOnlyWhenEndpointExists() {
        ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(ConfigController.class);
        runner.run(context -> assertThat(context).doesNotHaveBean(ConfigController.class));
        ConfigService config = new ConfigService(directory.resolve("config.json"), registry, springPropertyBridge, false);
        try {
            runner.withBean(ConfigWebEndpoint.class, () -> createEndpoint(config))
                    .run(context -> assertThat(context).hasSingleBean(ConfigController.class));
        } finally {
            config.destroy();
        }
    }

    /**
     * Servlet 入口在鉴权前不会调用请求的 getInputStream
     */
    @Test
    void rejectsUnauthorizedRequestWithoutOpeningBody() throws Exception {
        ConfigService config = new ConfigService(directory.resolve("config.json"), registry, springPropertyBridge, false);
        ConfigWebEndpoint endpoint = createEndpoint(config);
        try {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/config/api/save") {
                /**
                 * 检测是否读取了未通过鉴权的请求正文
                 * @return 请求输入流
                 */
                @NotNull
                @Override
                public ServletInputStream getInputStream() {
                    throw new AssertionError("鉴权前打开了请求正文");
                }
            };
            MockHttpServletResponse response = new MockHttpServletResponse();
            new ConfigController(endpoint).handle(request, response);
            assertThat(response.getStatus()).isEqualTo(401);
        } finally {
            endpoint.destroy();
            config.destroy();
        }
    }

    /**
     * 根据配置管理服务创建测试使用的配置请求处理器
     * @param config 配置管理服务
     * @return 配置请求处理器
     */
    private ConfigWebEndpoint createEndpoint(ConfigService config) {
        return new ConfigWebEndpoint(registry, new ConfigEditService(config, springPropertyBridge),
                new ConfigActionService(registry, springPropertyBridge), "test-password", false, true, "");
    }
}
