package com.starlwr.bot.core.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.config.security.ConfigPasswordUtil;
import com.starlwr.bot.core.config.service.ConfigMetaRegistry;
import com.starlwr.bot.core.event.config.StarBotConfigChangedEvent;
import com.starlwr.bot.core.event.listener.StarBotEventListener;
import com.starlwr.bot.core.message.Sender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.awt.Color;
import java.awt.Font;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/**
 * 配置服务测试
 */
class ConfigServiceTest {
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

    @BeforeEach
    void setUp() throws IOException {
        registry = new ConfigMetaRegistry(directory.resolve("plugins"));
        springPropertyBridge = new SpringPropertyBridge(registry);
    }

    /**
     * 没有配置文件时使用默认值，不新建文件，也不标记为待重启
     */
    @Test
    void readsDtoDefaultsWithoutCreatingFile() {
        Path path = directory.resolve("missing/config.json");
        try (Service service = new Service(path, new ConfigService(path, registry, springPropertyBridge, false))) {
            assertThat(service.config.get("starbot.core.datasource.type")).isEqualTo("json");
            assertThat(service.config.get("starbot.core.command.prefix")).isEqualTo("");
            assertThat(service.config.get("advanced.server.port", Integer.class)).isEqualTo(7827);
            assertThat(service.config.get("starbot.core.mail.enabled", Boolean.class)).isFalse();
            assertThat(service.config.hasPendingChanges()).isFalse();
            assertThat(path).doesNotExist();
        }
    }

    /**
     * 页面用配置类的初值作为默认值
     */
    @Test
    void derivesMetaDefaultsAndCollectionTemplatesFromDtos() throws Exception {
        Path path = directory.resolve("config.json");
        String raw = "{\"starbot\":{\"core\":{\"command\":{\"prefix\":\"custom\"}}}}";
        Files.writeString(path, raw);
        try (Service service = new Service(path, new ConfigService(path, registry, springPropertyBridge, false))) {
            JSONObject prefix = service.config.getRegistry().getDeclarations().stream()
                    .filter(item -> item.getPath().equals(List.of("starbot", "core", "command", "prefix"))).findFirst().orElseThrow().getField();
            assertThat(prefix.get("default")).isEqualTo("");
            assertThat(service.config.get("starbot.core.command.prefix")).isEqualTo("custom");
            JSONObject models = service.config.getRegistry().getDeclarations().stream()
                    .filter(item -> item.getPath().equals(List.of("starbot", "core", "ai", "models"))).findFirst().orElseThrow().getField();
            JSONObject maxTokens = models.getJSONObject("values").getJSONArray("fields").stream().map(item -> (JSONObject) item)
                    .filter(item -> "max-tokens".equals(item.getString("key"))).findFirst().orElseThrow();
            assertThat(maxTokens.get("default")).isEqualTo(128);
            JSONObject copyright = service.config.getRegistry().getDeclarations().stream()
                    .filter(item -> item.getPath().equals(List.of("starbot", "core", "paint", "extra-copyrights"))).findFirst().orElseThrow().getField();
            JSONObject size = copyright.getJSONObject("items").getJSONArray("fields").stream().map(item -> (JSONObject) item)
                    .filter(item -> "size".equals(item.getString("key"))).findFirst().orElseThrow();
            assertThat(size.get("default")).isEqualTo(0);
            JSONObject maven = service.config.getRegistry().getDeclarations().stream()
                    .filter(item -> item.getPath().equals(List.of("starbot", "core", "plugin", "maven-base-urls"))).findFirst().orElseThrow().getField();
            assertThat(maven.getJSONArray("default")).containsExactly("https://maven.aliyun.com/repository/public", "https://repo1.maven.org/maven2");
            assertThat(Files.readString(path)).isEqualTo(raw);
        }
    }

    /**
     * 修改读取到的集合或配置对象不会影响配置，getter 仍能读到之后生效的新值
     */
    @Test
    @SuppressWarnings("unchecked")
    void returnsIndependentCopiesAndKeepsTypedGetterCalls() {
        try (Service service = service("{}")) {
            StarBotCoreProperties properties = createPropertiesProxy(service.config);
            List<String> source = new ArrayList<>(List.of("123"));
            service.config.set("starbot.core.command.owners", Map.of("qq", source));
            source.add("456");
            Map<String, List<String>> owners = (Map<String, List<String>>) service.config.get("starbot.core.command.owners");
            owners.get("qq").clear();
            StarBotCoreProperties.Command command = Objects.requireNonNull(properties.getCommand());
            assertThat(command.getOwners().get("qq")).containsExactly("123");
            command.setPrefix("outside");
            assertThat(Objects.requireNonNull(properties.getCommand()).getPrefix()).isEmpty();
            service.config.set("starbot.core.command.prefix", "!");
            assertThat(Objects.requireNonNull(properties.getCommand()).getPrefix()).isEqualTo("!");
            assertThat(command.getPrefix()).isEqualTo("outside");
            assertThat(service.config.get("starbot.core.paint.fonts", List.class)).isEmpty();
        }
    }

    /**
     * 读取指定配置分组时，只转换当前 getter 对应的字段
     */
    @Test
    void convertsOnlyTheRequestedGetterField() {
        try (Service service = service("""
                {"starbot":{"core":{
                    "command":{"prefix":"!"},
                    "config-center":{"enabled":false},
                    "log":{"console":"invalid-level"}
                }}}
                """)) {
            StarBotCoreProperties properties = createPropertiesProxy(service.config);

            assertThat(Objects.requireNonNull(properties.getCommand()).getPrefix()).isEqualTo("!");
            assertThat(properties.getConfigCenter().isEnabled()).isFalse();
            assertThatThrownBy(properties::getLog).isInstanceOf(IllegalArgumentException.class);
        }
    }

    /**
     * 从调试分组读取两个开关，网络日志立即生效，事件日志在启动时启用
     */
    @Test
    void readsDebugSettingsFromTheirOwnGroup() {
        try (Service service = service("""
                {"starbot":{"core":{"debug":{"event-debug":true,"network-debug":true}}}}
                """)) {
            StarBotCoreProperties properties = createPropertiesProxy(service.config);
            StarBotCoreProperties.Debug debug = Objects.requireNonNull(properties.getDebug());
            assertThat(debug.isEventDebug()).isTrue();
            assertThat(debug.isNetworkDebug()).isTrue();
            new ApplicationContextRunner()
                    .withInitializer(context -> context.getEnvironment().getPropertySources()
                            .addFirst(new MapPropertySource("configJson", service.config.getSpringProperties())))
                    .withUserConfiguration(StarBotEventListener.class)
                    .run(context -> assertThat(context).hasSingleBean(StarBotEventListener.class));

            service.config.set("starbot.core.debug.network-debug", false);
            service.config.set("starbot.core.debug.event-debug", false);
            debug = Objects.requireNonNull(properties.getDebug());
            assertThat(debug.isNetworkDebug()).isFalse();
            assertThat(debug.isEventDebug()).isTrue();
            assertThat(service.config.getPendingPaths()).containsExactly(List.of("starbot", "core", "debug", "event-debug"));
        }
    }

    /**
     * 读取列表时按 getter 声明的元素类型生成独立的配置副本
     */
    @Test
    void preservesGenericGetterElementTypesAndCopies() {
        try (Service service = service("""
                {"starbot":{"core":{"sender":[{"name":"onebot","url":"http://localhost/send"}]}}}
                """)) {
            StarBotCoreProperties properties = createPropertiesProxy(service.config);

            List<Sender> senders = Objects.requireNonNull(properties.getSender());
            assertThat(senders).singleElement().satisfies(sender -> {
                assertThat(sender.getName()).isEqualTo("onebot");
                assertThat(sender.getToken()).isEmpty();
            });
            senders.get(0).setName("outside");
            senders.clear();

            assertThat(properties.getSender()).extracting(Sender::getName).containsExactly("onebot");
        }
    }

    /**
     * 需要重启的配置和未知配置保存后不会立即生效
     */
    @Test
    void keepsRestartAndUnknownChangesOutOfRuntimeAndEvents() {
        try (Service service = service("{}")) {
            List<StarBotConfigChangedEvent> events = new ArrayList<>();
            service.config.setApplicationEventPublisher(event -> events.add((StarBotConfigChangedEvent) event));
            service.config.set("advanced.server.port", 9100);
            service.config.set("starbot.core.network.connect-timeout", 20);
            service.config.set("starbot.other.unknown", "pending");
            assertThat(service.config.get("advanced.server.port", Integer.class)).isEqualTo(7827);
            assertThat(service.config.get("starbot.core.network.connect-timeout", Integer.class)).isEqualTo(10);
            assertThat(service.config.get("starbot.other.unknown")).isNull();
            assertThat(events).isEmpty();
            assertThat(service.config.hasPendingChanges()).isTrue();
            JSONObject stored = JSON.parseObject(read(service.path));
            assertThat(stored.getJSONObject("advanced").getIntValue("server.port")).isEqualTo(9100);
            try (Service restarted = new Service(service.path, new ConfigService(service.path, registry, springPropertyBridge, false))) {
                assertThat(restarted.config.get("advanced.server.port", Integer.class)).isEqualTo(9100);
                assertThat(restarted.config.get("starbot.other.unknown")).isEqualTo("pending");
            }
        }
    }

    /**
     * 只有生效值变化才发布事件，恢复默认后从文件移除该项
     */
    @Test
    void publishesOnlyAppliedDifferencesAndRestoresDtoDefaults() {
        try (Service service = service("{}")) {
            List<StarBotConfigChangedEvent> events = new ArrayList<>();
            service.config.setApplicationEventPublisher(event -> events.add((StarBotConfigChangedEvent) event));
            service.config.set("starbot.core.command.prefix", "!");
            service.config.set("starbot.core.command.prefix", "!");
            assertThat(events).hasSize(1);
            assertThat(events.get(0).isChanged("starbot.core.command")).isTrue();
            assertThat(events.get(0).isChanged("starbot.core.commander")).isFalse();
            assertThat(events.get(0).getOldValue("starbot.core.command.prefix", String.class)).isEmpty();
            assertThat(events.get(0).getNewValue("starbot.core.command.prefix", String.class)).isEqualTo("!");
            service.config.set("starbot.core.command.prefix", "");
            assertThat(service.config.get("starbot.core.command.prefix")).isEqualTo("");
            assertThat(read(service.path)).doesNotContain("prefix");
            assertThat(events).hasSize(2);
            assertThat(events.get(0).getNewValue("starbot.core.command.prefix", String.class)).isEqualTo("!");
        }
    }

    /**
     * 一次保存只发布一个事件，其中只包含实际生效的配置
     */
    @Test
    void publishesOneEventAfterApplyingAllLiveChanges() {
        try (Service service = service("{}")) {
            List<StarBotConfigChangedEvent> events = new ArrayList<>();
            service.config.setApplicationEventPublisher(published -> {
                assertThat(service.config.get("starbot.core.command.prefix")).isEqualTo("!");
                assertThat(service.config.get("starbot.core.command.owners.qq", List.class)).isEqualTo(List.of("123"));
                events.add((StarBotConfigChangedEvent) published);
            });
            service.config.update(raw -> """
                    {"starbot":{"core":{"command":{"prefix":"!","owners":{"qq":["123"]}}},
                     "unknown":{"enabled":true}},"advanced":{"server.port":9100}}
                    """);
            assertThat(events).hasSize(1);
            StarBotConfigChangedEvent event = events.get(0);
            assertThat(event.getKeys()).containsExactlyInAnyOrder("starbot.core.command.prefix", "starbot.core.command.owners.qq");
            assertThat(event.getOldValue("starbot.core.command.prefix", String.class)).isEmpty();
            assertThat(event.getNewValue("starbot.core.command.prefix", String.class)).isEqualTo("!");
            assertThat(event.getNewValue("starbot.core.command.owners.qq", List.class)).isEqualTo(List.of("123"));
            assertThat(event.isAnyChanged("advanced", "starbot.unknown")).isFalse();
            assertThat(service.config.get("advanced.server.port", Integer.class)).isEqualTo(7827);
            assertThat(service.config.update(raw -> raw)).isFalse();
            assertThat(events).hasSize(1);
        }
    }

    /**
     * Map 中新增和删除值为 null 的键仍属于有效变更，事件保留对应键名
     */
    @Test
    void publishesMapKeyPresenceChangesWithNullValues() {
        try (Service service = service("{}")) {
            List<StarBotConfigChangedEvent> events = new ArrayList<>();
            service.config.setApplicationEventPublisher(event -> events.add((StarBotConfigChangedEvent) event));
            Map<String, Object> owners = new LinkedHashMap<>();
            owners.put("qq", null);
            service.config.set("starbot.core.command.owners", owners);
            assertThat(events).hasSize(1);
            assertThat(events.get(0).getKeys()).containsExactly("starbot.core.command.owners.qq");
            assertThat(events.get(0).getOldValue("starbot.core.command.owners.qq")).isNull();
            assertThat(events.get(0).getNewValue("starbot.core.command.owners.qq")).isNull();
            service.config.set("starbot.core.command.owners", Map.of());
            assertThat(events).hasSize(2);
            assertThat(events.get(1).isChanged("starbot.core.command.owners.qq")).isTrue();
        }
    }

    /**
     * 保留显式 null、空对象和包含点号的 Map 键，并补充缺失的 DTO 默认字段
     */
    @Test
    void preservesNullEmptyAndLiteralMapKeys() {
        try (Service service = service("""
                {"starbot":{"core":{"ai":{"models":{"main.v1":{"provider":"OPENAI"}}}}},
                 "advanced":{"server.port":9000}}
                """)) {
            assertThat(service.config.get("starbot.core.ai.models.main.v1.max-tokens", Integer.class)).isEqualTo(128);
            assertThat(service.config.get("starbot.core.ai", StarBotCoreProperties.Ai.class).getModels().get("main.v1").getTimeout()).isEqualTo(30);
            service.config.set("starbot.core.command.owners", Map.of());
            service.config.set("starbot.core.command.prefix", null);
            assertThat(service.config.get("starbot.core.command.prefix")).isNull();
            assertThat(read(service.path)).contains("\"prefix\":null");
            assertThat(service.config.get("advanced.server.port", Integer.class)).isEqualTo(9000);
        }
    }

    /**
     * 支持颜色、字体和时长转换，拒绝将 null 转为基本类型
     */
    @Test
    void copiesColorFontAndPrimitiveNullConversion() {
        Color color = ConfigService.convert("#123456", Color.class);
        Font font = ConfigService.convert(Map.of("name", "Dialog", "size", 24, "style", Font.BOLD), Font.class);
        assertThat(color).isEqualTo(new Color(0x123456));
        assertThat(font.getSize()).isEqualTo(24);
        assertThat(ConfigService.convert("7s", Duration.class)).isEqualTo(Duration.ofSeconds(7));
        assertThatThrownBy(() -> ConfigService.convert(null, int.class)).isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 重复填写监听地址和端口时，启动属性使用 Core 建议值，文件保留两种写法
     */
    @Test
    void usesCoreSuggestionsForDuplicateListenerProperties() {
        String raw = """
                {"advanced":{"server":{"address":"192.0.2.10","port":9001},"server.address":"192.0.2.11","server.port":9002}}
                """;
        try (Service service = service(raw)) {
            assertThat(service.config.getStartupDuplicates()).extracting(SpringPropertyBridge.Duplicate::getKey)
                    .containsExactlyInAnyOrder("server.address", "server.port");
            assertThat(service.config.getSpringProperties()).containsEntry("server.address", "0.0.0.0").containsEntry("server.port", 7827);
            assertThat(read(service.path)).isEqualTo(raw);
        }
    }

    /**
     * 独立邮件字段保存在 Map 中，重复写法报告两个原始位置
     */
    @Test
    void normalizesDeclaredMailKeysAndExcludesDuplicateStartupValues() {
        try (Service service = service("{\"advanced\":{\"spring.mail.properties[mail.smtp.auth]\":true,\"spring.mail.properties[mail.vendor_option]\":\"custom\"}}")) {
            assertThat(service.config.getSpringProperties()).containsEntry("spring.mail.properties[mail.smtp.auth]", true);
            assertThat(service.config.get("advanced.spring.mail.properties.mail.smtp.auth")).isEqualTo(true);
            service.config.set("advanced.spring.mail.properties[mail.smtp.auth]", false);
            assertThat(JSON.parseObject(read(service.path)).getJSONObject("advanced"))
                    .containsOnlyKeys("spring.mail.properties", "spring.mail.properties[mail.vendor_option]")
                    .containsEntry("spring.mail.properties", JSONObject.of("mail.smtp.auth", false))
                    .containsEntry("spring.mail.properties[mail.vendor_option]", "custom");
            service.config.set("advanced.spring.mail.properties.mail.smtp.auth", true);
            assertThat(JSON.parseObject(read(service.path)).getJSONObject("advanced").getJSONObject("spring.mail.properties"))
                    .containsEntry("mail.smtp.auth", true);
        }
        List<JSONObject> forms = List.of(JSONObject.of("spring.mail.properties.mail.smtp.auth", true),
                JSONObject.of("spring.mail.properties[mail.smtp.auth]", true),
                JSONObject.of("spring.mail.properties", JSONObject.of("mail.smtp.auth", true)));
        List<List<Object>> paths = List.of(List.of("advanced", "spring.mail.properties.mail.smtp.auth"),
                List.of("advanced", "spring.mail.properties[mail.smtp.auth]"), List.of("advanced", "spring.mail.properties", "mail.smtp.auth"));
        for (int index = 0; index < forms.size(); index++) {
            int next = (index + 1) % forms.size();
            JSONObject advanced = new JSONObject(forms.get(index));
            advanced.putAll(forms.get(next));
            String raw = JSONObject.of("advanced", advanced).toJSONString();
            List<Object> firstPath = paths.get(index), secondPath = paths.get(next);
            try (Service service = service(raw)) {
                assertThat(service.config.getStartupDuplicates()).singleElement().satisfies(duplicate -> {
                    assertThat(duplicate.getFirst()).isEqualTo(firstPath);
                    assertThat(duplicate.getSecond()).isEqualTo(secondPath);
                });
                assertThat(service.config.getSpringProperties()).doesNotContainKey("spring.mail.properties.mail.smtp.auth")
                        .containsEntry("spring.mail.properties[mail.smtp.auth]", false);
                assertThatThrownBy(() -> service.config.update(ignored -> raw)).isInstanceOf(SpringPropertyBridge.DuplicatePropertiesException.class)
                        .hasMessageContaining(JSON.toJSONString(firstPath)).hasMessageContaining(JSON.toJSONString(secondPath));
                assertThat(read(service.path)).isEqualTo(raw);
            }
        }
    }

    /**
     * 配置服务记住启动时从 Spring 读到的值，Spring 后续变化不会更新它
     */
    @Test
    void capturesOnlyMappedSpringOverridesOnceAndKeepsThemFrozen() {
        try (Service service = service("{}")) {
            StandardEnvironment environment = new StandardEnvironment();
            environment.getPropertySources().addFirst(new MapPropertySource("overrides", Map.of(
                    "starbot.core.mail.enabled", true, "starbot.core.command.prefix", "external")));
            service.config.initializeSpring(environment);
            assertThat(service.config.get("starbot.core.mail.enabled", Boolean.class)).isTrue();
            assertThat(service.config.get("starbot.core.command.prefix")).isEqualTo("");
            assertThat(service.config.hasPendingChanges()).isFalse();
            service.config.set("starbot.core.command.prefix", "!");
            assertThat(service.config.get("starbot.core.mail.enabled", Boolean.class)).isTrue();
            assertThat(service.config.get("starbot.core.command.prefix")).isEqualTo("!");
            environment.getPropertySources().addFirst(new MapPropertySource("later", Map.of("starbot.core.mail.enabled", false)));
            service.config.initializeSpring(environment);
            assertThat(service.config.get("starbot.core.mail.enabled", Boolean.class)).isTrue();
            assertThat(read(service.path)).doesNotContain("enabled");
            service.config.set("starbot.core.mail.enabled", true);
            assertThat(service.config.hasPendingChanges()).isTrue();
        }
    }

    /**
     * Spring 解析高级属性和映射字段中的占位符，纯业务文本保留原文
     */
    @Test
    void springResolvesOnlyBridgedPlaceholders() {
        try (Service service = service("""
                {"advanced":{"server.port":"${STAR_BOT_TEST_PORT:7827}"},
                 "starbot":{"core":{"mail":{"enabled":"${STAR_BOT_TEST_MAIL:true}"},"command":{"prefix":"${STAR_BOT_TEST_PREFIX}"}}}}
                """)) {
            StandardEnvironment environment = new StandardEnvironment();
            environment.getPropertySources().addLast(new MapPropertySource("configJson", service.config.getSpringProperties()));
            assertThat(environment.getProperty("server.port", Integer.class)).isEqualTo(7827);
            environment.getPropertySources().addFirst(new MapPropertySource("deployment", Map.of(
                    "STAR_BOT_TEST_PORT", "9001", "STAR_BOT_TEST_PREFIX", "!")));
            assertThat(environment.getProperty("server.port", Integer.class)).isEqualTo(9001);
            service.config.initializeSpring(environment);
            assertThat(service.config.get("starbot.core.mail.enabled", Boolean.class)).isTrue();
            assertThat(service.config.get("starbot.core.command.prefix", String.class)).isEqualTo("${STAR_BOT_TEST_PREFIX}");
        }
    }

    /**
     * 直接调用配置服务读写时不执行表单的业务规则
     */
    @Test
    void doesNotRunBusinessValidationOnReadOrSet() {
        try (Service service = service("{\"starbot\":{\"core\":{\"datasource\":{\"type\":\"future-backend\"}}}}")) {
            assertThat(service.config.get("starbot.core.datasource.type")).isEqualTo("future-backend");
            service.config.set("starbot.core.live.reconnect-interval", -5);
            assertThat(service.config.get("starbot.core.live.reconnect-interval", Integer.class)).isEqualTo(-5);
        }
    }

    /**
     * 启动监听线程前的文件变更会重新读取，JSON 无效时保留上次读到的有效配置
     */
    @Test
    void watchesExternalChangesAndKeepsLastRuntimeValueOnBrokenJson() throws Exception {
        Path path = directory.resolve("config.json");
        Files.writeString(path, "{}");
        Logger logger = (Logger) LoggerFactory.getLogger(ConfigService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.list = new CopyOnWriteArrayList<>();
        appender.start();
        logger.addAppender(appender);
        try (Service service = new Service(path, new ConfigService(path, registry, springPropertyBridge, false))) {
            Duration timeout = Duration.ofSeconds(6);
            String key = "starbot.core.command.prefix";
            Files.writeString(path, "{\"starbot\":{\"core\":{\"command\":{\"prefix\":\"?\"}}}}");
            assertThat(service.config.get(key)).isEqualTo("");
            service.config.startWatching();
            await().atMost(timeout).untilAsserted(() -> assertThat(service.config.get(key)).isEqualTo("?"));

            Files.writeString(path, "{");
            await().atMost(timeout).untilAsserted(() -> assertThat(appender.list).anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage()).startsWith("配置文件未能重载").endsWith(path.toString());
            }));
            assertThat(service.config.get(key)).isEqualTo("?");

            Files.writeString(path, "{}");
            await().atMost(timeout).untilAsserted(() -> assertThat(service.config.get(key)).isEqualTo(""));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    /**
     * 固定密码只保存摘要，重启后使用新密码
     */
    @Test
    void storesPasswordHashAndKeepsCurrentValueUntilRestart() {
        try (Service service = service("{}")) {
            service.config.set("starbot.core.config-center.password", "A7!CustomPassword#29");
            String encoded = JSON.parseObject(read(service.path)).getJSONObject("starbot").getJSONObject("core")
                    .getJSONObject("config-center").getString("password");
            assertThat(read(service.path)).doesNotContain("A7!CustomPassword#29");
            assertThat(ConfigPasswordUtil.matches("A7!CustomPassword#29", encoded)).isTrue();
            assertThat(service.config.get("starbot.core.config-center.password")).isEqualTo("");
            try (Service restarted = new Service(service.path, new ConfigService(service.path, registry, springPropertyBridge, false))) {
                assertThat(restarted.config.get("starbot.core.config-center.password")).isEqualTo(encoded);
                assertThat(restarted.config.isPasswordReset()).isFalse();
            }
        }
    }

    /**
     * 配置文件中存在明文密码时，仅清空密码并保留其他设置
     */
    @Test
    void clearsInvalidPasswordWithoutChangingOtherSettings() {
        try (Service service = service("{\"starbot\":{\"core\":{\"config-center\":{\"enabled\":false,\"password\":\"accidental-plaintext\"}}},\"advanced\":{\"server.port\":9100}}")) {
            assertThat(service.config.isPasswordReset()).isTrue();
            assertThat(service.config.get("starbot.core.config-center.password")).isEqualTo("");
            assertThat(service.config.get("starbot.core.config-center.enabled")).isEqualTo(false);
            Map<?, ?> advanced = service.config.get("advanced", Map.class);
            assertThat(advanced).isNotNull();
            assertThat(advanced.get("server.port")).isEqualTo(9100);
            assertThat(read(service.path)).doesNotContain("accidental-plaintext");
        }
    }

    /**
     * 读到有效的密码摘要时保留原文件，也不提示密码已重置
     */
    @Test
    void preservesStoredHashWithoutRewritingFile() {
        String encoded = ConfigPasswordUtil.encode("A7!StoredPassword#29");
        String raw = "{\"starbot\":{\"core\":{\"config-center\":{\"password\":\"" + encoded + "\"}}}}";
        try (Service service = service(raw)) {
            assertThat(read(service.path)).isEqualTo(raw);
            assertThat(service.config.isPasswordReset()).isFalse();
            assertThat(service.config.get("starbot.core.config-center.password")).isEqualTo(encoded);
        }
    }

    /**
     * 冲突项明确选择空文本时，保存及重新启动均保留该选择
     */
    @Test
    void preservesEmptyConflictSelectionAcrossSavesAndRestarts() throws Exception {
        Path plugins = Files.createDirectories(directory.resolve("plugins"));
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(plugins.resolve("address.jar")))) {
            for (Map.Entry<String, String> entry : Map.of(
                    "plugin.json", "{\"groupId\":\"test\",\"artifactId\":\"address\",\"name\":\"Address\"}",
                    "config/config.json", """
                            {"title":"Address","basePath":["starbot","address"],"groups":[{"id":"server","title":"地址","target":"advanced",
                             "fields":[{"key":"server.address","type":"integer","suggestedValue":7}]}]}
                            """).entrySet()) {
                jar.putNextEntry(new JarEntry(entry.getKey()));
                jar.write(entry.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                jar.closeEntry();
            }
        }
        Path path = directory.resolve("config.json");
        Files.writeString(path, "{\"advanced\":{\"server.address\":\"\"}}");
        for (int startup = 0; startup < 2; startup++) {
            ConfigMetaRegistry registry = new ConfigMetaRegistry(plugins);
            SpringPropertyBridge springPropertyBridge = new SpringPropertyBridge(registry);
            try (Service service = new Service(path, new ConfigService(path, registry, springPropertyBridge, false))) {
                assertThat(service.config.hasUnresolvedConflicts()).isFalse();
                assertThat(service.config.get("advanced.server.address")).isEqualTo("");
                service.config.set("advanced.server.address", "");
                assertThat(service.config.hasUnresolvedConflicts()).isFalse();
                assertThat(JSON.parseObject(read(path)).getJSONObject("advanced").get("server.address")).isEqualTo("");
            }
        }
    }

    /**
     * 显式 Map 保留已选条目，改名或删除的默认条目在重启后不会重新出现
     */
    @Test
    void usesSavedMapMembershipAfterRestart() throws Exception {
        Path plugins = Files.createDirectories(directory.resolve("plugins"));
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(plugins.resolve("map-test.jar")))) {
            for (Map.Entry<String, String> entry : Map.of(
                    "plugin.json", "{\"groupId\":\"test\",\"artifactId\":\"map-test\",\"name\":\"Map test\"}",
                    "config/config.json", """
                            {"title":"Map test","basePath":["starbot","map-test"],"configurationClass":"%s",
                             "groups":[{"id":"main","title":"连接设置","fields":[
                               {"key":"connections","type":"map","values":{"type":"string"},"restartRequired":true}
                             ]}]}
                            """.formatted(MapTestProperties.class.getName())).entrySet()) {
                jar.putNextEntry(new JarEntry(entry.getKey()));
                jar.write(entry.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                jar.closeEntry();
            }
        }
        Path path = directory.resolve("config.json");
        ConfigMetaRegistry registry = new ConfigMetaRegistry(plugins);
        SpringPropertyBridge springPropertyBridge = new SpringPropertyBridge(registry);
        String key = "starbot.map-test.connections";
        Map<String, String> renamed = Map.of("renamed", "primary", "backup", "secondary");
        try (Service service = new Service(path, new ConfigService(path, registry, springPropertyBridge, false))) {
            assertThat(service.config.get(key)).isEqualTo(Map.of("local", "primary", "backup", "secondary"));
            service.config.set(key + ".extra", "extra");
            assertThat(JSON.parseObject(read(path)).getJSONObject("starbot").getJSONObject("map-test").getJSONObject("connections"))
                    .containsKeys("local", "backup", "extra");
            service.config.set(key, renamed);
            assertThat(JSON.parseObject(read(path)).getJSONObject("starbot").getJSONObject("map-test").get("connections"))
                    .isEqualTo(renamed);
            assertThat(service.config.getPendingPaths()).contains(List.of("starbot", "map-test", "connections", "local"));
        }
        try (Service restarted = new Service(path, new ConfigService(path, registry, springPropertyBridge, false))) {
            assertThat(restarted.config.get(key)).isEqualTo(renamed);
            assertThat(restarted.config.get(key + ".local")).isNull();
            restarted.config.set(key, Map.of());
        }
        try (Service restarted = new Service(path, new ConfigService(path, registry, springPropertyBridge, false))) {
            assertThat(restarted.config.get(key)).isEqualTo(Map.of());
            assertThat(restarted.config.get(key + ".backup")).isNull();
        }
    }

    /**
     * Map 测试配置类
     */
    @StarBotConfiguration(prefix = "starbot.map-test")
    @lombok.Getter
    public static class MapTestProperties {
        /**
         * 测试连接配置映射
         */
        private final Map<String, String> connections = Map.of("local", "primary", "backup", "secondary");
    }

    /**
     * 创建 Core 配置代理并检查是否创建成功
     * @param config 配置管理服务
     * @return Core 配置代理
     */
    private static StarBotCoreProperties createPropertiesProxy(ConfigService config) {
        Object proxy = new ConfigGetterPostProcessor(config).postProcessAfterInitialization(new StarBotCoreProperties(), "starBotCoreProperties");
        return (StarBotCoreProperties) Objects.requireNonNull(proxy, "未创建 Core 配置代理");
    }

    /**
     * 写入配置原文并创建测试配置服务
     * @param raw 配置原文
     * @return 配置文件及服务
     */
    private Service service(String raw) {
        try {
            Path path = directory.resolve("config.json");
            Files.writeString(path, raw);
            return new Service(path, new ConfigService(path, registry, springPropertyBridge, false));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 读取测试配置文件内容
     * @param path 配置文件路径
     * @return 配置原文
     */
    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 测试配置服务
     * @param path 配置文件路径
     * @param config 配置管理服务
     */
    private record Service(Path path, ConfigService config) implements AutoCloseable {
        /**
         * 关闭配置服务并释放文件监听资源
         */
        @Override
        public void close() {
            config.destroy();
        }
    }
}
