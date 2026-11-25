package com.starlwr.bot.core;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.config.ConfigService;
import com.starlwr.bot.core.config.SpringPropertyBridge;
import com.starlwr.bot.core.config.security.ConfigPasswordUtil;
import com.starlwr.bot.core.config.service.ConfigMetaRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.logging.LogLevel;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * StarBotCore 启动类测试
 */
class StarBotCoreApplicationTest {
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
     * 独立配置中心测试进程
     */
    static class ConfigProcess {
        /**
         * 启动配置中心，关闭标准输入时退出
         * @param args 启动参数
         */
        public static void main(String[] args) {
            Thread shutdown = new Thread(() -> {
                try {
                    System.in.transferTo(OutputStream.nullOutputStream());
                } catch (IOException ignored) {
                }
                System.exit(0);
            });
            shutdown.setDaemon(true);
            shutdown.start();
            StarBotCoreApplication.main(args);
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        registry = new ConfigMetaRegistry(directory.resolve("plugins"));
        springPropertyBridge = new SpringPropertyBridge(registry);
    }

    /**
     * 未设置固定密码时生成不同的字母数字密码，且不写入配置文件
     */
    @Test
    void generatesFreshAlphanumericPasswordsWithoutSavingThem() {
        Path path = directory.resolve("config.json");
        ConfigService config = new ConfigService(path, registry, springPropertyBridge, false);
        try {
            String first = ReflectionTestUtils.invokeMethod(StarBotCoreApplication.class, "createConfigPassword", config);
            String second = ReflectionTestUtils.invokeMethod(StarBotCoreApplication.class, "createConfigPassword", config);
            assertThat(first).matches("[A-Za-z0-9]{32}").isNotEqualTo(second);
            assertThat(second).matches("[A-Za-z0-9]{32}");
            assertThat(path).doesNotExist();
        } finally {
            config.destroy();
        }
    }

    /**
     * 配置中心就绪前读取已设置的密码摘要，不输出弱密码警告
     */
    @Test
    void readsCustomPasswordWithoutWarningBeforeConfigCenterIsReady() throws Exception {
        Path path = directory.resolve("config.json");
        String encoded = ConfigPasswordUtil.encode("12345678");
        Files.writeString(path, "{\"starbot\":{\"core\":{\"config-center\":{\"password\":\"" + encoded + "\"}}}}");
        ConfigService config = new ConfigService(path, registry, springPropertyBridge, false);
        Logger logger = (Logger) LoggerFactory.getLogger(StarBotCoreApplication.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            String password = ReflectionTestUtils.invokeMethod(StarBotCoreApplication.class, "createConfigPassword", config);
            assertThat(password).isEqualTo(encoded);
            assertThat(appender.list).isEmpty();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
            config.destroy();
        }
    }

    /**
     * 点号和方括号写法均将完整包名绑定为日志级别 Map 的键
     */
    @Test
    void bindsPackageLogLevelsWithDotsAndBrackets() {
        for (String key : List.of("logging.level.com.example", "logging.level[com.example]")) {
            StandardEnvironment environment = new StandardEnvironment();
            environment.getPropertySources().addFirst(new MapPropertySource("configJson", Map.of(key, "DEBUG")));
            Map<String, LogLevel> levels = Binder.get(environment).bind("logging.level", Bindable.mapOf(String.class, LogLevel.class))
                    .orElseThrow(IllegalStateException::new);
            assertThat(levels).containsExactlyEntriesOf(Map.of("com.example", LogLevel.DEBUG));
        }
    }

    /**
     * 桥接嵌套高级配置时保留包名和类名中的点号及大小写
     */
    @Test
    void preservesLiteralMapKeysWhenBridgingAdvancedLoggingConfiguration() throws Exception {
        Path file = directory.resolve("config.json");
        Files.writeString(file, """
                {"advanced":{"logging.level":{"com.example":"DEBUG","com.example.DemoService":"TRACE"}}}
                """);
        ConfigService config = new ConfigService(file, registry, springPropertyBridge, false);
        Map<String, Object> properties;
        try {
            properties = config.getSpringProperties();
        } finally {
            config.destroy();
        }
        assertThat(properties).containsEntry("logging.level[com.example]", "DEBUG")
                .containsEntry("logging.level[com.example.DemoService]", "TRACE");
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("configJson", properties));
        Map<String, LogLevel> levels = Binder.get(environment).bind("logging.level", Bindable.mapOf(String.class, LogLevel.class))
                .orElseThrow(IllegalStateException::new);
        assertThat(levels).containsExactlyInAnyOrderEntriesOf(Map.of("com.example", LogLevel.DEBUG,
                "com.example.DemoService", LogLevel.TRACE));
    }

    /**
     * 独立配置中心的启动属性无效时提示具体原因，配置原文中的密码不进入输出
     * @param raw 配置文件原文
     * @param message 应输出的错误提示
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            {"advanced":{"server.port":"DoNotLogThisPassword"}} | 监听端口必须是 0 到 65535 之间的整数
            {"advanced":{"server.port":65536}} | 监听端口必须是 0 到 65535 之间的整数
            {"advanced":{"server.port":7827.5}} | 监听端口必须是 0 到 65535 之间的整数
            {"advanced":{"server.port":"${DoNotLogThisPassword}"}} | 配置中心启动属性存在无法解析的占位符
            {"advanced":{"starbot.core.mail.enabled":"DoNotLogThisPassword"}} | 配置中心启动属性的值无法转换
            """)
    void reportsInvalidStartupConfigurationWithoutLoggingValues(String raw, String message) throws Exception {
        Path data = Files.createDirectories(directory.resolve("data"));
        Files.writeString(data.resolve("config.json"), raw);
        List<String> lines = runFailedStartup(true);
        assertThat(lines).anyMatch(line -> line.contains("ERROR") && line.contains(message))
                .noneMatch(line -> line.contains("DoNotLogThisPassword") || line.stripLeading().startsWith("at "));
    }

    /**
     * 自动切换或因配置中心关闭而退出时，只输出一条包含实际原因的提示
     * @param editing 是否手动指定独立配置模式
     * @param configEnabled 是否启用配置中心
     * @param conflicts 是否存在未解决的配置冲突
     * @param duplicates 是否存在重复的 Spring 属性
     */
    @ParameterizedTest
    @CsvSource({"false, true, true, false", "false, true, true, true", "true, true, true, true",
            "false, false, true, false", "false, false, false, true", "false, false, true, true"})
    void reportsActualReasonsWhenConfigurationBlocksNormalStartup(boolean editing, boolean configEnabled, boolean conflicts,
                                                                  boolean duplicates) throws Exception {
        // 准备两个给同一配置项提供不同建议值的测试插件
        Path plugins = Files.createDirectories(directory.resolve("plugins"));
        for (int value : List.of(1, 2)) {
            String name = "conflict-" + value;
            try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(plugins.resolve(name + ".jar")))) {
                Map<String, String> resources = Map.of(
                        "plugin.json", JSONObject.of("groupId", "test", "artifactId", name, "name", name).toJSONString(),
                        "config/config.json", """
                                {"title":"%s","basePath":["starbot","%s"],"groups":[{"id":"settings","title":"设置","target":"advanced",
                                 "fields":[{"key":"example.conflict","type":"integer","suggestedValue":%d}]}]}
                                """.formatted(name, name, value));
                for (Map.Entry<String, String> resource : resources.entrySet()) {
                    jar.putNextEntry(new JarEntry(resource.getKey()));
                    jar.write(resource.getValue().getBytes(StandardCharsets.UTF_8));
                    jar.closeEntry();
                }
            }
        }

        // 使用无效端口确认进入独立模式，再检查是否输出自动切换警告
        Path data = Files.createDirectories(directory.resolve("data"));
        JSONObject advanced = duplicates
                ? JSONObject.of("example.server", JSONObject.of("host-name", "localhost"), "example.server.host-name", "backup")
                : new JSONObject();
        if (!conflicts) {
            advanced.put("example.conflict", 1);
        }
        Files.writeString(data.resolve("config.json"), """
                {"starbot":{"core":{"config-center":{"enabled":%s}}},"advanced":%s}
                """.formatted(configEnabled, advanced));
        List<String> lines = runFailedStartup(editing, "--server.port=invalid");
        if (configEnabled) {
            assertThat(lines).anyMatch(line -> line.contains("监听端口必须是"));
        }
        if (duplicates) {
            assertThat(lines).anyMatch(line -> line.contains("example.server.host-name") && line.contains("重复配置"));
        }
        String outcome = configEnabled ? "已切换至独立配置中心" : "且配置中心已关闭";
        List<String> summaries = lines.stream().filter(line -> line.contains(outcome)).toList();
        if (!editing || !configEnabled) {
            assertThat(summaries).singleElement().satisfies(summary -> {
                assertThat(summary).contains(configEnabled ? "WARN" : "ERROR", "重启 StarBot");
                assertThat(summary.contains("未解决的配置冲突")).isEqualTo(conflicts);
                assertThat(summary.contains("重复的 Spring 属性")).isEqualTo(duplicates);
            });
        } else {
            assertThat(summaries).isEmpty();
        }
    }

    /**
     * 端口建议存在冲突时，独立配置中心要求明确指定端口
     */
    @Test
    void rejectsUnresolvedPortConflictInIndependentMode() throws Exception {
        Path plugins = Files.createDirectories(directory.resolve("plugins"));
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(plugins.resolve("port-conflict.jar")))) {
            jar.putNextEntry(new JarEntry("plugin.json"));
            jar.write("""
                    {"groupId":"test","artifactId":"port-conflict","name":"端口冲突测试"}
                    """.getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
            jar.putNextEntry(new JarEntry("config/config.json"));
            jar.write("""
                    {"title":"端口冲突测试","basePath":["starbot","port-conflict"],"groups":[{
                      "id":"settings","title":"设置","target":"advanced",
                      "fields":[{"key":"server.port","type":"integer","suggestedValue":9091}]
                    }]}
                    """.getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        assertThat(runFailedStartup(true)).anyMatch(line -> line.contains("监听端口存在配置冲突"))
                .noneMatch(line -> line.contains("配置中心: http"));
    }

    /**
     * 监听端口已被占用时，独立配置中心报告绑定失败并退出
     */
    @Test
    void reportsOccupiedPortInIndependentMode() throws Exception {
        try (ServerSocket occupied = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            List<String> lines = runFailedStartup(true, "--server.address=127.0.0.1", "--server.port=" + occupied.getLocalPort());
            assertThat(lines).anyMatch(line -> line.contains("无法绑定配置中心监听地址"))
                    .noneMatch(line -> line.contains("配置中心: http"));
        }
    }

    /**
     * 配置文件格式错误时自动进入独立配置中心的原文修复模式
     * @param raw 配置文件原文
     * @param message 应输出的格式错误提示
     * @param editorMessage 原文编辑器中的错误提示
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            {"password":"DoNotLogThisPassword","broken": | JSON 格式无效 | JSON 格式错误
            {"advanced":{"server.port":0,}} | JSON 格式无效 | JSON 格式错误
            [] | 配置文件根节点必须是 JSON 对象 | 配置文件根节点必须是 JSON 对象
            """)
    void opensIndependentEditorForInvalidJson(String raw, String message, String editorMessage) throws Exception {
        Path config = Files.createDirectories(directory.resolve("data")).resolve("config.json");
        Files.writeString(config, raw);
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        String java = ProcessHandle.current().info().command().orElseThrow();
        Process process = new ProcessBuilder(java, "-Dfile.encoding=UTF-8", "-cp", classpath, ConfigProcess.class.getName(), "--server.port=0")
                .directory(directory.toFile()).redirectErrorStream(true).start();
        try (BufferedReader output = process.inputReader(StandardCharsets.UTF_8)) {
            try {
                // 从控制台获取恢复入口和本次启动的随机密码
                List<String> startupMessages = new ArrayList<>();
                String address = awaitEditorAddress(output, startupMessages);
                String password = awaitStartupValue(output, startupMessages, Pattern.compile("配置中心访问密码: ([A-Za-z0-9]{32})"));
                assertThat(startupMessages).anyMatch(line -> line.contains("WARN") && line.contains("配置文件格式错误")
                                && line.contains(message) && line.contains("已切换至独立配置中心"))
                        .noneMatch(line -> line.contains("DoNotLogThisPassword") || line.contains("Started StarBotCoreApplication")
                                || line.stripLeading().startsWith("at "));

                // 读取损坏的原文，再通过配置中心保存修正后的配置
                HttpClient client = HttpClient.newHttpClient();
                HttpResponse<String> document = client.send(HttpRequest.newBuilder(URI.create(address + "/api/document"))
                        .timeout(Duration.ofSeconds(5)).header("Authorization", "Bearer " + password).build(), HttpResponse.BodyHandlers.ofString());
                assertThat(document.statusCode()).isEqualTo(200);
                JSONObject body = JSON.parseObject(document.body());
                assertThat(body.getBooleanValue("rawRecovery")).isTrue();
                assertThat(body.getString("raw")).isEqualTo(raw);
                assertThat(body.getString("error")).contains(editorMessage);
                assertThat(Files.readString(config)).isEqualTo(raw);

                String corrected = "{\"advanced\":{\"server.port\":0}}";
                HttpResponse<String> saved = client.send(HttpRequest.newBuilder(URI.create(address + "/api/save")).timeout(Duration.ofSeconds(5))
                        .header("Authorization", "Bearer " + password).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(JSONObject.of("raw", corrected).toJSONString())).build(),
                        HttpResponse.BodyHandlers.ofString());
                assertThat(saved.statusCode()).isEqualTo(200);
                assertThat(JSON.parseObject(saved.body()).getJSONObject("document").getBooleanValue("rawRecovery")).isTrue();
                assertThat(JSON.parseObject(Files.readString(config))).isEqualTo(JSON.parseObject(corrected));
            } finally {
                process.getOutputStream().close();
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(10, TimeUnit.SECONDS);
                }
            }
        }
        assertThat(process.exitValue()).isZero();
    }

    /**
     * 配置校验失败时自动启动独立配置中心，页面可以读取原文及错误字段的路径
     * @param core Core 配置片段
     * @param advanced 高级配置片段
     * @param path 预期的错误字段路径
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            "network":{"connect-timeout":601} | "server.address":"127.0.0.1" | ["starbot","core","network","connect-timeout"]
            "network":[] | "server.address":"127.0.0.1" | ["starbot","core","network"]
            "network-thread":{"core-pool-size":8,"max-pool-size":1} | "server.address":"127.0.0.1" | ["starbot","core","network-thread","core-pool-size"]
            "config-center":{"enabled":"invalid"} | "server.address":"127.0.0.1" | ["starbot","core","config-center","enabled"]
            "cache":{"type":"redis"} | "server.address":"127.0.0.1" | ["starbot","core","cache","type"]
            "network":{} | "server.address":"invalid/address","server.port":65536 | ["advanced","server.address"]
            "network":{} | "server":{"address":"127.0.0.1","port":65536} | ["advanced","server.port"]
            """)
    void opensIndependentEditorForInvalidConfiguration(String core, String advanced, String path) throws Exception {
        Path config = Files.createDirectories(directory.resolve("data")).resolve("config.json");
        String password = "TestConfigPage-2026!";
        JSONObject fields = JSON.parseObject("{" + core + "}");
        fields.computeIfAbsent("config-center", key -> new JSONObject());
        fields.getJSONObject("config-center").put("password", ConfigPasswordUtil.encode(password));
        String raw = """
                {"starbot":{"core":%s},"advanced":{%s}}
                """.formatted(fields, advanced);
        Files.writeString(config, raw);
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        String java = ProcessHandle.current().info().command().orElseThrow();
        Process process = new ProcessBuilder(java, "-Dfile.encoding=UTF-8", "-cp", classpath, ConfigProcess.class.getName(), "--server.port=0")
                .directory(directory.toFile()).redirectErrorStream(true).start();
        try (BufferedReader output = process.inputReader(StandardCharsets.UTF_8)) {
            try {
                List<String> startupMessages = new ArrayList<>();
                String address = awaitEditorAddress(output, startupMessages);
                assertThat(startupMessages).anyMatch(line -> line.contains("配置项校验错误") && line.contains("已切换至独立配置中心"));
                HttpResponse<String> document = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(address + "/api/document"))
                        .timeout(Duration.ofSeconds(5)).header("Authorization", "Bearer " + password).build(), HttpResponse.BodyHandlers.ofString());
                assertThat(document.statusCode()).isEqualTo(200);
                JSONObject body = JSON.parseObject(document.body());
                assertThat(body.getString("raw")).isEqualTo(raw);
                assertThat(body.getJSONArray("errors").toJSONString()).contains(path);
                assertThat(body).doesNotContainKey("rawRecovery");
                assertThat(Files.readString(config)).isEqualTo(raw);
            } finally {
                process.getOutputStream().close();
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(10, TimeUnit.SECONDS);
                }
            }
        }
        assertThat(process.exitValue()).isZero();
    }

    /**
     * 配置校验失败且配置中心已关闭时，启动进程报错退出
     */
    @Test
    void exitsForInvalidConfigurationWhenEditorIsDisabled() throws Exception {
        Path config = Files.createDirectories(directory.resolve("data")).resolve("config.json");
        Files.writeString(config, """
                {"starbot":{"core":{"config-center":{"enabled":false},"network":{"connect-timeout":601}}}}
                """);
        assertThat(runFailedStartup(false)).anyMatch(line -> line.contains("配置项校验错误") && line.contains("配置中心已关闭"))
                .noneMatch(line -> line.contains("配置中心: http"));
    }

    /**
     * 重复属性可在独立配置中心修正，保存时仍检查两处重复位置
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void opensIndependentEditorForDuplicateSpringProperties(boolean explicitConfigMode) throws Exception {
        Path data = Files.createDirectories(directory.resolve("data"));
        Path config = data.resolve("config.json");
        String password = "TestConfigPage-2026!";
        String raw = """
                {"starbot":{"core":{"config-center":{"password":"%s"}}},
                 "advanced":{"server":{"port":9001,"address":"192.0.2.10"},"server.address":"192.0.2.11","server.port":9002,
                             "example.server":{"host-name":"localhost"},"example.server.host-name":"backup"}}
                """.formatted(ConfigPasswordUtil.encode(password));
        Files.writeString(config, raw);
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        String java = ProcessHandle.current().info().command().orElseThrow();
        List<String> command = new ArrayList<>(List.of(java, "-Dfile.encoding=UTF-8", "-cp", classpath, ConfigProcess.class.getName(),
                "--server.address=127.0.0.1", "--server.port=0"));
        if (explicitConfigMode) {
            command.add("--config");
        }
        Process process = new ProcessBuilder(command)
                .directory(directory.toFile()).redirectErrorStream(true).start();
        try (var output = process.inputReader(StandardCharsets.UTF_8)) {
            try {
                // 根据启动输出访问独立配置中心，确认原文和重复位置均可读取
                String address = awaitEditorAddress(output, new ArrayList<>());
                assertThat(URI.create(address).getPort()).isPositive().isNotEqualTo(7827);
                HttpClient client = HttpClient.newHttpClient();
                HttpResponse<String> document = client.send(HttpRequest.newBuilder(URI.create(address + "/api/document"))
                        .timeout(Duration.ofSeconds(5)).header("Authorization", "Bearer " + password).build(),
                        HttpResponse.BodyHandlers.ofString());
                assertThat(document.statusCode()).isEqualTo(200);
                JSONObject body = JSON.parseObject(document.body());
                assertThat(body.getString("raw")).isEqualTo(raw);
                assertThat(body.getJSONArray("errors").toJSONString()).contains(
                        "[\"advanced\",\"example.server\",\"host-name\"]", "[\"advanced\",\"example.server.host-name\"]",
                        "[\"advanced\",\"server\",\"address\"]", "[\"advanced\",\"server.address\"]",
                        "[\"advanced\",\"server\",\"port\"]", "[\"advanced\",\"server.port\"]");

                // 重复属性仍无法保存，删除其中一处后可以保存
                URI save = URI.create(address + "/api/save");
                for (String draft : List.of(raw, "{\"advanced\":{\"example.server.host-name\":\"backup\"}}")) {
                    HttpResponse<String> saved = client.send(HttpRequest.newBuilder(save).timeout(Duration.ofSeconds(5))
                            .header("Authorization", "Bearer " + password).header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(JSONObject.of("raw", draft).toJSONString())).build(),
                            HttpResponse.BodyHandlers.ofString());
                    if (draft.equals(raw)) {
                        assertThat(saved.statusCode()).isEqualTo(422);
                        assertThat(Files.readString(config)).isEqualTo(raw);
                    } else {
                        assertThat(saved.statusCode()).isEqualTo(200);
                        assertThat(Files.readString(config)).contains("backup").doesNotContain("localhost");
                    }
                }
            } finally {
                process.getOutputStream().close();
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(10, TimeUnit.SECONDS);
                }
            }
        }
        assertThat(process.exitValue()).isZero();
    }

    /**
     * 等待配置中心输出访问地址，记录此前的启动日志
     * @param output 启动进程的输出
     * @param messages 接收访问地址之前的日志
     * @return 配置中心访问地址
     */
    private String awaitEditorAddress(BufferedReader output, List<String> messages) throws Exception {
        return awaitStartupValue(output, messages, Pattern.compile("配置中心: (http://127\\.0\\.0\\.1:\\d+/config)"));
    }

    /**
     * 等待启动日志中的指定值
     * @param output 启动进程的输出
     * @param messages 接收匹配前的日志
     * @param pattern 用第一个捕获组提取启动信息的表达式
     * @return 匹配到的值
     */
    private String awaitStartupValue(BufferedReader output, List<String> messages, Pattern pattern) throws Exception {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String line;
                while ((line = output.readLine()) != null) {
                    Matcher matcher = pattern.matcher(line);
                    if (matcher.find()) {
                        return matcher.group(1);
                    }
                    messages.add(line);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            throw new AssertionError(String.join(System.lineSeparator(), messages));
        }).get(20, TimeUnit.SECONDS);
    }

    /**
     * 启动会以错误退出的测试进程并读取输出
     * @param editing 是否以独立配置模式启动
     * @param args 附加启动参数
     * @return 进程输出列表
     */
    private List<String> runFailedStartup(boolean editing, String... args) throws Exception {
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        String java = ProcessHandle.current().info().command().orElseThrow();
        List<String> command = new ArrayList<>(List.of(java, "-Dfile.encoding=UTF-8", "-cp", classpath, StarBotCoreApplication.class.getName()));
        if (editing) {
            command.add("--config");
        }
        command.addAll(List.of(args));
        Path output = directory.resolve("startup-output.log");
        Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true)
                .redirectOutput(output.toFile()).start();
        try {
            assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).isEqualTo(1);
            return Files.readAllLines(output);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
        }
    }
}
