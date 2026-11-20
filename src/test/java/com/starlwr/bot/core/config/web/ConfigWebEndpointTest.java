package com.starlwr.bot.core.config.web;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.config.ConfigService;
import com.starlwr.bot.core.config.SpringPropertyBridge;
import com.starlwr.bot.core.config.security.ConfigPasswordUtil;
import com.starlwr.bot.core.config.service.ConfigActionService;
import com.starlwr.bot.core.config.service.ConfigEditService;
import com.starlwr.bot.core.config.service.ConfigMetaRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * 配置中心请求处理器测试
 */
@ExtendWith(OutputCaptureExtension.class)
class ConfigWebEndpointTest {
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

    private final Logger endpointLogger = (Logger) LoggerFactory.getLogger(ConfigWebEndpoint.class);

    private final ListAppender<ILoggingEvent> accessLogs = new ListAppender<>();

    /**
     * 准备配置元数据注册表、Spring 属性桥接器和日志记录器
     */
    @BeforeEach
    void setUp() throws IOException {
        registry = new ConfigMetaRegistry(directory.resolve("plugins"));
        springPropertyBridge = new SpringPropertyBridge(registry);
        accessLogs.start();
        endpointLogger.addAppender(accessLogs);
    }

    /**
     * 移除测试使用的日志记录器
     */
    @AfterEach
    void tearDown() {
        endpointLogger.detachAppender(accessLogs);
        accessLogs.stop();
    }

    /**
     * 输出配置中心地址和弱密码警告，启动和访问登录页时均不输出固定密码
     * @param output 捕获的控制台输出
     */
    @Test
    void doesNotPrintFixedPassword(CapturedOutput output) throws IOException {
        ConfigService config = new ConfigService(directory.resolve("config.json"), registry, springPropertyBridge, false);
        String token = "12345678";
        ConfigWebEndpoint endpoint = createEndpoint(config, token, false, true, "");
        try {
            endpoint.printAccessInfo("0.0.0.0", 7827);
            assertThat(endpoint.handle("GET", "/config/", null, null, "localhost", () -> new ByteArrayInputStream(new byte[0])).getStatus()).isEqualTo(200);
            assertThat(accessLogs.list).extracting(ILoggingEvent::getLevel, ILoggingEvent::getFormattedMessage)
                    .containsExactly(tuple(Level.INFO, "配置中心: http://127.0.0.1:7827/config"),
                            tuple(Level.WARN, "配置中心访问密码强度较低, 建议使用更复杂的密码"));
            assertThat(output.getOut()).doesNotContain(token, "配置中心访问密码:");
        } finally {
            endpoint.destroy();
            config.destroy();
        }
    }

    /**
     * Spring 应用就绪后输出实际端口和随机密码
     * @param output 捕获的控制台输出
     */
    @Test
    void printsAccessInfoWhenApplicationIsReady(CapturedOutput output) {
        ConfigService config = new ConfigService(directory.resolve("config.json"), registry, springPropertyBridge, false);
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            String token = "GeneratedPasswordAtStartup";
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("testServer",
                    Map.of("server.address", "::", "server.port", 0, "local.server.port", 39827)));
            context.registerBean(ConfigWebEndpoint.class, () -> createEndpoint(config, token, true, true, ""));
            context.refresh();
            assertThat(accessLogs.list).isEmpty();
            assertThat(output.getOut()).doesNotContain(token);

            context.publishEvent(new ApplicationReadyEvent(new SpringApplication(), new String[0], context, Duration.ZERO));
            assertThat(accessLogs.list).extracting(ILoggingEvent::getLevel, ILoggingEvent::getFormattedMessage)
                    .containsExactly(tuple(Level.INFO, "配置中心: http://127.0.0.1:39827/config"));
            assertThat(output.getOut()).contains("配置中心访问密码: " + token);
        } finally {
            config.destroy();
        }
    }

    /**
     * 仅打开登录页时再次输出随机密码，重定向、静态资源和接口请求不重复输出
     * @param output 捕获的控制台输出
     */
    @Test
    void printsRandomPasswordOnlyWhenOpeningLoginPage(CapturedOutput output) {
        ConfigService config = new ConfigService(directory.resolve("config.json"), registry, springPropertyBridge, false);
        try {
            String token = "GeneratedPasswordForPage";
            ConfigWebEndpoint endpoint = createEndpoint(config, token, true, true, "");
            endpoint.handle("GET", "/config", null, null, "localhost", () -> new ByteArrayInputStream(new byte[0]));
            endpoint.handle("GET", "/config/assets/app.js", null, null, "localhost", () -> new ByteArrayInputStream(new byte[0]));
            endpoint.handle("GET", "/config/api/bootstrap", "Bearer " + token, null, "localhost", () -> new ByteArrayInputStream(new byte[0]));
            assertThat(output).doesNotContain(token);
            assertThat(endpoint.handle("GET", "/config/", null, null, "localhost", () -> new ByteArrayInputStream(new byte[0])).getStatus()).isEqualTo(200);
            assertThat(output).contains("配置中心访问密码: " + token);
        } finally {
            config.destroy();
        }
    }

    /**
     * 配置页面与接口共用 /config 路径，拒绝未授权、跨域及越界资源请求
     */
    @Test
    void sharesAuthenticatedApiAndResourcesUnderConfigPath() {
        ConfigService config = new ConfigService(directory.resolve("config.json"), registry, springPropertyBridge, false);
        try {
            ConfigWebEndpoint endpoint = createEndpoint(config, "secret-token", false, true, "");
            assertThat(endpoint.handle("GET", "/config", null, null, "localhost", () -> new ByteArrayInputStream(new byte[0])).getStatus()).isEqualTo(302);
            ConfigWebEndpoint.Response page = endpoint.handle("GET", "/config/", null, null, "localhost", () -> new ByteArrayInputStream(new byte[0]));
            assertThat(page.getStatus()).isEqualTo(200);
            assertThat(new String(page.getBody(), StandardCharsets.UTF_8)).contains("编辑 JSON", "assets/app.js");
            assertThat(endpoint.handle("GET", "/config/api/bootstrap", null, null, "localhost", () -> new ByteArrayInputStream(new byte[0])).getStatus()).isEqualTo(401);
            assertThat(endpoint.handle("POST", "/config/api/action", null, null, "localhost", () -> new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8))).getStatus()).isEqualTo(401);
            assertThat(endpoint.handle("GET", "/config/api/action-status/unknown", null, null, "localhost", () -> new ByteArrayInputStream(new byte[0])).getStatus()).isEqualTo(401);
            assertThat(endpoint.handle("GET", "/config/api/bootstrap", "Bearer secret-token", "http://other", "localhost", () -> new ByteArrayInputStream(new byte[0])).getStatus()).isEqualTo(403);
            ConfigWebEndpoint.Response bootstrap = endpoint.handle("GET", "/config/api/bootstrap", "Bearer secret-token", null, "localhost",
                    () -> new ByteArrayInputStream(new byte[0]));
            assertThat(bootstrap.getStatus()).isEqualTo(200);
            JSONObject payload = JSONObject.parseObject(new String(bootstrap.getBody(), StandardCharsets.UTF_8));
            assertThat(payload).containsOnlyKeys("meta", "diagnostics", "springPaths", "accessPasswordPath", "document");
            assertThat(payload.getJSONArray("accessPasswordPath")).containsExactly("starbot", "core", "config-center", "password");
            assertThat(payload.getJSONArray("springPaths")).contains(List.of("starbot", "core", "mail", "enabled"));
            assertThat(payload.getJSONArray("meta").getJSONObject(0).getString("id")).isEqualTo("core");
            assertThat(payload.getJSONObject("document")).containsEntry("metaErrors", false);
            assertThat(endpoint.handle("POST", "/config/api/action", "Bearer secret-token", "http://other", "localhost", () -> new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8))).getStatus()).isEqualTo(403);
            assertThat(endpoint.handle("POST", "/config/api/action", "Bearer secret-token", null, "localhost", () -> new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8))).getStatus()).isEqualTo(404);
            assertThat(endpoint.handle("GET", "/config/api/action-status/unknown", "Bearer secret-token", null, "localhost", () -> new ByteArrayInputStream(new byte[0])).getStatus()).isEqualTo(404);
            assertThat(endpoint.handle("GET", "/config/assets/../config.json", null, null, "localhost", () -> new ByteArrayInputStream(new byte[0])).getStatus()).isEqualTo(404);
        } finally {
            config.destroy();
        }
    }

    /**
     * 通过验证且来源合法的 POST 请求才会读取请求体
     */
    @Test
    void authenticatesBeforeOpeningRequestBodyAndRejectsHashAsPassword() throws Exception {
        ConfigService config = new ConfigService(directory.resolve("config.json"), registry, springPropertyBridge, false);
        String encoded = ConfigPasswordUtil.encode("A7!ActualPassword#29");
        ConfigWebEndpoint endpoint = createEndpoint(config, encoded, false, true, "");
        try {
            ConfigWebEndpoint.RequestBody unreadable = () -> { throw new AssertionError("未授权请求读取了正文"); };
            assertThat(endpoint.handle("POST", "/config/api/save", null, null, "localhost", unreadable).getStatus()).isEqualTo(401);
            assertThat(endpoint.handle("POST", "/config/api/save", "Bearer wrong", null, "localhost", unreadable).getStatus()).isEqualTo(401);
            assertThat(endpoint.handle("POST", "/config/api/save", "Bearer " + encoded, null, "localhost", unreadable).getStatus()).isEqualTo(401);
            assertThat(endpoint.handle("POST", "/config/api/save", "Bearer A7!ActualPassword#29", "http://other", "localhost", unreadable).getStatus()).isEqualTo(403);
            assertThat(endpoint.handle("GET", "/config/api/bootstrap", "Bearer A7!ActualPassword#29", null, "localhost", unreadable).getStatus()).isEqualTo(200);
            AtomicInteger opened = new AtomicInteger();
            ByteArrayInputStream content = spy(new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)));
            ConfigWebEndpoint.Response result = endpoint.handle("POST", "/config/api/action", "Bearer A7!ActualPassword#29", null, "localhost", () -> {
                opened.incrementAndGet();
                return content;
            });
            assertThat(result.getStatus()).isEqualTo(404);
            assertThat(opened).hasValue(1);
            verify(content).close();
            ByteArrayInputStream oversized = spy(new ByteArrayInputStream(new byte[ConfigWebEndpoint.MAX_BYTES + 1]));
            assertThat(endpoint.handle("POST", "/config/api/save", "Bearer A7!ActualPassword#29", null, "localhost",
                    () -> oversized).getStatus()).isEqualTo(413);
            verify(oversized).close();
        } finally {
            endpoint.destroy();
            config.destroy();
        }
    }

    /**
     * 允许预览 UTF-8 字节数超过 2 MiB 的配置原文
     */
    @Test
    void previewsConfigurationLargerThanTwoMebibytes() throws Exception {
        ConfigService config = new ConfigService(directory.resolve("config.json"), registry, springPropertyBridge, false);
        String token = "A7!ActualPassword#29";
        ConfigWebEndpoint endpoint = createEndpoint(config, token, false, true, "");
        try {
            String raw = JSONObject.of("advanced", JSONObject.of("example.large-text", "中".repeat(800_000))).toJSONString();
            byte[] content = JSONObject.of("raw", raw).toJSONString().getBytes(StandardCharsets.UTF_8);
            ConfigWebEndpoint.Response response = endpoint.handle("POST", "/config/api/preview", "Bearer " + token, null, "localhost",
                    () -> new ByteArrayInputStream(content));
            assertThat(response.getStatus()).isEqualTo(200);
        } finally {
            endpoint.destroy();
            config.destroy();
        }
    }

    /**
     * 独立模式启动时输出随机密码，登录页只显示密码重置提示
     * @param output 捕获的控制台输出
     */
    @Test
    void displaysResetNoticeWithoutPrintingPasswordAgain(CapturedOutput output) throws Exception {
        ConfigService config = new ConfigService(directory.resolve("config.json"), registry, springPropertyBridge, false);
        String token = "GeneratedOnlyInConsole";
        ConfigWebEndpoint endpoint = createEndpoint(config, token, true, false,
                "访问密码已重置, 请使用 StarBot 控制台中的随机密码登录");
        try {
            endpoint.printAccessInfo("2001:db8::1", 7827);
            String startupOutput = output.getOut();
            assertThat(accessLogs.list).extracting(ILoggingEvent::getFormattedMessage)
                    .containsExactly("配置中心: http://[2001:db8::1]:7827/config");
            assertThat(startupOutput).contains("配置中心访问密码: " + token);
            ConfigWebEndpoint.Response result = endpoint.handle("GET", "/config/", null, null, "localhost", () -> { throw new AssertionError(); });
            String html = new String(result.getBody(), StandardCharsets.UTF_8);
            assertThat(html).contains("访问密码已重置", "id=\"starbot-password-notice\"")
                    .doesNotContain("id=\"starbot-password-notice\" hidden", token);
            assertThat(output.getOut()).isEqualTo(startupOutput);
        } finally {
            endpoint.destroy();
            config.destroy();
        }
    }

    /**
     * 固定密码隐藏登录提示，随机密码通过提示框说明获取位置
     */
    @Test
    void showsPasswordNoticeOnlyForRandomCredentials() throws Exception {
        for (String notice : List.of("", "当前使用随机访问密码, 请查看 StarBot 控制台中的登录密码")) {
            ConfigService config = new ConfigService(directory.resolve("config.json"), registry, springPropertyBridge, false);
            ConfigWebEndpoint endpoint = createEndpoint(config, "OnlyInConsole", !notice.isEmpty(), false, notice);
            try {
                String html = new String(endpoint.handle("GET", "/config/", null, null, "localhost", () -> { throw new AssertionError(); }).getBody(), StandardCharsets.UTF_8);
                assertThat(html).doesNotContain("{{password-notice}}");
                if (notice.isEmpty()) {
                    assertThat(html).contains("id=\"starbot-password-notice\" hidden");
                } else {
                    assertThat(html).contains(notice, "starbot-login-help starbot-password-notice").doesNotContain("id=\"starbot-password-notice\" hidden");
                }
            } finally {
                endpoint.destroy();
                config.destroy();
            }
        }
    }

    /**
     * 创建用于测试的配置中心请求处理器
     * @param config 配置管理服务
     * @param token 配置中心访问密码
     * @param randomPassword 是否为随机密码
     * @param printPasswordOnPage 是否在打开登录页时向控制台输出随机密码
     * @param loginNotice 登录页提示
     * @return 配置中心请求处理器
     */
    private ConfigWebEndpoint createEndpoint(ConfigService config, String token, boolean randomPassword,
                                             boolean printPasswordOnPage, String loginNotice) {
        return new ConfigWebEndpoint(registry, new ConfigEditService(config, springPropertyBridge),
                new ConfigActionService(registry, springPropertyBridge), token, randomPassword, printPasswordOnPage, loginNotice);
    }
}
