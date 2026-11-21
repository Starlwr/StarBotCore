package com.starlwr.bot.core.config.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.starlwr.bot.core.config.ConfigService;
import com.starlwr.bot.core.config.SpringPropertyBridge;
import com.starlwr.bot.core.config.ConfigException;
import com.starlwr.bot.core.config.StarBotConfiguration;
import com.starlwr.bot.core.config.security.ConfigPasswordUtil;
import com.starlwr.bot.core.event.config.StarBotConfigChangedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.*;

/**
 * 配置中心编辑服务测试
 */
class ConfigEditServiceTest {
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
     * 验证时长配置可预览、保存并按 Java 时长读取
     */
    @Test
    void savesDurationFieldsAndAppliesDurationRules() throws Exception {
        Path plugins = Files.createDirectories(registry.getPlugins());
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(plugins.resolve("duration.jar")))) {
            entry(jar, "plugin.json", "{\"groupId\":\"test\",\"artifactId\":\"duration\"}");
            entry(jar, "config/config.json", """
                    {"title":"Duration","basePath":["starbot","duration"],"configurationClass":"%s",
                     "groups":[{"id":"timing","title":"Timing","fields":[
                      {"key":"timeout","type":"duration","validators":[{"rule":"positiveDuration"},
                       {"rule":"durationRange","min":"1ns","max":"2m"}]},
                      {"key":"delay","type":"duration"},
                      {"key":"optional-delay","type":"duration","nullable":true}
                    ]}]}
                    """.formatted(DurationDefaults.class.getName()));
        }
        ConfigMetaRegistry pluginRegistry = new ConfigMetaRegistry(plugins);
        SpringPropertyBridge bridge = new SpringPropertyBridge(pluginRegistry);
        Path path = directory.resolve("config.json");
        Files.writeString(path, """
                {"starbot":{"duration":{"timeout":1000.0,"delay":"PT-0.000000001S"}}}
                """);
        ConfigService config = new ConfigService(path, pluginRegistry, bridge, false);
        try {
            ConfigEditService editService = new ConfigEditService(config, bridge);
            assertThat(pluginRegistry.getDiagnostics()).isEmpty();
            assertThat(editService.read().getJSONArray("errors")).isEmpty();
            assertThat(config.get("starbot.duration", JSONObject.class)).containsEntry("optional-delay", null);
            assertThat(config.get("starbot.duration.timeout", Duration.class)).isEqualTo(Duration.ofSeconds(1));
            assertThat(config.get("starbot.duration.delay", Duration.class)).isEqualTo(Duration.ofNanos(-1));

            List<String> fieldPath = List.of("starbot", "duration", "timeout");
            for (Object invalid : List.of("0s", "121s", "P1DT", 0.5)) {
                JSONObject request = JSONObject.of("changes", List.of(JSONObject.of("op", "set", "path", fieldPath, "value", invalid)));
                assertThat(editService.preview(request).getJSONArray("errors")).isNotEmpty();
            }
            JSONObject request = JSONObject.of("changes", List.of(JSONObject.of("op", "set", "path", fieldPath, "value", "PT2M")));
            assertThat(editService.preview(request).getJSONArray("errors")).isEmpty();
            assertThat(editService.save(request).getJSONArray("errors")).isEmpty();
            assertThat(config.get("starbot.duration.timeout", Duration.class)).isEqualTo(Duration.ofMinutes(2));
        } finally {
            config.destroy();
        }
    }

    /**
     * 时长测试配置
     */
    @StarBotConfiguration(prefix = "starbot.duration")
    @lombok.Getter
    public static class DurationDefaults {
        /**
         * 附加延迟
         */
        private final Duration optionalDelay = null;
    }

    /**
     * 根据共享用例检查访问密码的预览、保存和程序写入
     */
    @Test
    void validatesNewPasswordBeforeHashingInEveryWritePath() throws IOException {
        JSONArray cases;
        try (var input = getClass().getResourceAsStream("/config-center/rules-cases.json")) {
            assertThat(input).isNotNull();
            cases = JSON.parseObject(input.readAllBytes()).getJSONArray("accessPasswords");
        }
        Path path = directory.resolve("config.json");
        Files.writeString(path, "{}");
        ConfigService config = new ConfigService(path, registry, springPropertyBridge, false);
        try {
            ConfigEditService edit = new ConfigEditService(config, springPropertyBridge);
            for (Object item : cases) {
                JSONObject sample = (JSONObject) item;
                Object value = sample.get("value");
                String message = sample.getString("message");
                String before = Files.readString(path);
                JSONObject draft = JSONObject.of("starbot", JSONObject.of("core",
                        JSONObject.of("config-center", JSONObject.of("password", value))));
                JSONObject request = JSONObject.of("raw", JSON.toJSONString(draft, JSONWriter.Feature.WriteNulls));
                if (message != null) {
                    assertThatThrownBy(() -> edit.preview(request)).isInstanceOf(SecurityException.class).hasMessage(message);
                    ConfigException failure = catchThrowableOfType(ConfigException.class, () -> edit.save(request));
                    assertThat(failure.getStatus()).isEqualTo(422);
                    assertThat(((JSONObject) failure.getDetails()).getJSONArray("errors").getJSONObject(0)).containsEntry("message", message);
                    assertThatThrownBy(() -> config.set("starbot.core.config-center.password", value))
                            .isInstanceOf(SecurityException.class).hasMessage(message);
                    assertThat(Files.readString(path)).isEqualTo(before);
                    continue;
                }
                assertThat(edit.preview(request).getJSONArray("errors")).isEmpty();
                assertThat(edit.save(request).getJSONArray("errors")).isEmpty();
                config.set("starbot.core.config-center.password", value);
                assertThat(edit.read().getJSONArray("errors")).isEmpty();
            }
        } finally {
            config.destroy();
        }
    }

    /**
     * 拒绝无法登录的密码，保存后继续用原密码验证，已有摘要不影响其他设置
     */
    @Test
    void rejectsUnsupportedPasswordCharactersAndKeepsStoredHashValid() throws IOException {
        Path path = directory.resolve("config.json");
        Files.writeString(path, "{}");
        ConfigService config = new ConfigService(path, registry, springPropertyBridge, false);
        try {
            ConfigEditService editService = new ConfigEditService(config, springPropertyBridge);
            List<String> passwordPath = List.of("starbot", "core", "config-center", "password");
            JSONObject invalid = JSONObject.of("changes", List.of(JSONObject.of("op", "set", "path", passwordPath, "value", "Aa1!密码abc")));
            ConfigException failure = catchThrowableOfType(ConfigException.class, () -> editService.save(invalid));
            assertThat(failure.getStatus()).isEqualTo(422);
            assertThat(((JSONObject) failure.getDetails()).getJSONArray("errors").getJSONObject(0))
                    .containsEntry("message", "访问密码只能使用英文字母、数字和半角符号");
            assertThatThrownBy(() -> config.set("starbot.core.config-center.password", "Aa1!密码abc"))
                    .isInstanceOf(SecurityException.class);
            assertThat(Files.readString(path)).isEqualTo("{}");

            String password = "Aa1!NewPassword#";
            JSONObject valid = JSONObject.of("changes", List.of(JSONObject.of("op", "set", "path", passwordPath, "value", password)));
            JSONObject saved = editService.save(valid).getJSONObject("document");
            assertThat(saved.getJSONArray("errors")).isEmpty();
            assertThat(saved.getJSONObject("accessPassword")).containsEntry("stored", true).containsEntry("active", false);
            String encoded = saved.getJSONObject("data").getJSONObject("starbot").getJSONObject("core")
                    .getJSONObject("config-center").getString("password");
            assertThat(ConfigPasswordUtil.matches(password, encoded)).isTrue();
            assertThat(Files.readString(path)).doesNotContain(password);
            editService.save(JSONObject.of("changes", List.of(JSONObject.of("op", "set", "path", List.of("advanced", "server.port"), "value", 9000))));
            assertThat(editService.read().getJSONArray("errors")).isEmpty();
            assertThat(editService.read().getJSONObject("accessPassword")).containsEntry("stored", true).containsEntry("active", false);
            assertThat(Files.readString(path)).contains(encoded);
        } finally {
            config.destroy();
        }
    }

    /**
     * 插件密码字段仍按元数据校验，只有配置中心能识别访问密码摘要
     */
    @Test
    void appliesPluginPasswordValidationEvenWhenMetadataDeclaresHashed() throws Exception {
        Path plugins = Files.createDirectories(directory.resolve("plugins"));
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(plugins.resolve("token.jar")))) {
            entry(jar, "plugin.json", "{\"groupId\":\"test\",\"artifactId\":\"token\"}");
            entry(jar, "config/config.json", """
                    {"title":"Plugin","basePath":["starbot","plugin"],"groups":[{"id":"auth","title":"Auth","fields":[
                      {"key":"token","type":"string","format":"password","ui":{"widget":"password","hashed":true},
                       "validators":[{"rule":"maxLength","value":32}]}
                    ]}]}
                    """);
        }
        ConfigMetaRegistry pluginRegistry = new ConfigMetaRegistry(plugins);
        SpringPropertyBridge bridge = new SpringPropertyBridge(pluginRegistry);
        Path path = directory.resolve("config.json");
        Files.writeString(path, "{}");
        ConfigService config = new ConfigService(path, pluginRegistry, bridge, false);
        try {
            ConfigEditService editService = new ConfigEditService(config, bridge);
            String encoded = ConfigPasswordUtil.encode("Aa1!NewPassword#");
            JSONObject document = JSONObject.of("starbot", JSONObject.of(
                    "core", JSONObject.of("config-center", JSONObject.of("password", encoded)),
                    "plugin", JSONObject.of("token", encoded)));
            ConfigException failure = catchThrowableOfType(ConfigException.class,
                    () -> editService.save(JSONObject.of("raw", document.toJSONString())));
            assertThat(failure.getStatus()).isEqualTo(422);
            JSONArray errors = ((JSONObject) failure.getDetails()).getJSONArray("errors");
            assertThat(errors).hasSize(1);
            assertThat(errors.getJSONObject(0).getJSONArray("path")).containsExactly("starbot", "plugin", "token");
            assertThat(Files.readString(path)).isEqualTo("{}");
        } finally {
            config.destroy();
        }
    }

    /**
     * 改名并保存后仍能找到原键，连续改名也能恢复原来的生效值
     */
    @Test
    void retainsCustomRenameAcrossSaveAndReadWithoutWritingItToConfig() throws Exception {
        Path path = directory.resolve("config.json");
        Files.writeString(path, """
                {"advanced":{"example.old":{"name":"live"},"example.unrelated":{"name":"live"}}}
                """);
        ConfigService config = new ConfigService(path, registry, springPropertyBridge, false);
        List<String> old = List.of("advanced", "example.old");
        List<String> middle = List.of("advanced", "example.middle");
        List<String> renamed = List.of("advanced", "example.renamed");
        try {
            ConfigEditService editService = new ConfigEditService(config, springPropertyBridge);
            editService.save(JSONObject.of("changes", List.of(JSONObject.of("op", "move", "from", old, "path", middle)),
                    "customRenames", List.of(JSONObject.of("from", old, "to", middle))));
            assertThat(editService.read().getJSONArray("customRenames"))
                    .containsExactly(JSONObject.of("from", old, "to", middle));

            editService.save(JSONObject.of("raw", """
                    {"advanced":{"example.renamed":{"name":"draft"},"example.unrelated":{"name":"live"}}}
                    """, "customRenames", List.of(JSONObject.of("from", old, "to", renamed))));
            assertThat(editService.read().getJSONArray("customRenames"))
                    .containsExactly(JSONObject.of("from", old, "to", renamed));
            assertThat(Files.readString(path)).doesNotContain("customRenames", "example.old");

            Files.writeString(path, """
                    {"advanced":{"example.renamed":{"name":"external"},"example.unrelated":{"name":"live"}}}
                    """);
            assertThat(editService.read().getJSONArray("customRenames")).isEmpty();
        } finally {
            config.destroy();
        }
    }

    /**
     * 删除旧键时，已存在的其他配置项不会被视为改名目标
     */
    @Test
    void rejectsRenameLinkToAnAlreadyExistingIndependentKey() throws Exception {
        Path path = directory.resolve("config.json");
        Files.writeString(path, """
                {"advanced":{"example.old":"same","example.other":"same"}}
                """);
        ConfigService config = new ConfigService(path, registry, springPropertyBridge, false);
        try {
            ConfigEditService editService = new ConfigEditService(config, springPropertyBridge);
            List<String> old = List.of("advanced", "example.old");
            List<String> other = List.of("advanced", "example.other");
            editService.save(JSONObject.of("changes", List.of(JSONObject.of("op", "unset", "path", old)),
                    "customRenames", List.of(JSONObject.of("from", old, "to", other))));
            assertThat(editService.read().getJSONArray("customRenames")).isEmpty();
            assertThat(editService.read().getJSONObject("data").getJSONObject("advanced")).containsEntry("example.other", "same");
        } finally {
            config.destroy();
        }
    }

    /**
     * 预览和保存都指出配置重复的两处位置，原文件保持不变
     */
    @Test
    void rejectsDuplicateSpringKeysWithBothJsonPaths() throws Exception {
        Path path = directory.resolve("config.json");
        Files.writeString(path, "{}");
        ConfigService config = new ConfigService(path, registry, springPropertyBridge, false);
        String draft = """
                {"advanced":{"example.server":{"host-name":"localhost"},"example.server.host-name":"backup"}}
                """;
        try {
            ConfigEditService editService = new ConfigEditService(config, springPropertyBridge);
            JSONObject preview = editService.preview(JSONObject.of("baseRaw", draft, "changes", List.of(
                    JSONObject.of("op", "set", "path", List.of("advanced", "server.address"), "value", "0.0.0.0"))));
            assertThat(preview.getJSONArray("errors")).hasSize(1);
            assertThat(JSON.parseObject(preview.getString("json")).getJSONObject("advanced"))
                    .containsOnlyKeys("example.server", "example.server.host-name");
            ConfigException failure = catchThrowableOfType(ConfigException.class, () -> editService.save(JSONObject.of("raw", draft)));
            assertThat(failure.getStatus()).isEqualTo(422);
            JSONObject details = (JSONObject) failure.getDetails();
            assertThat(details).containsOnlyKeys("file", "exists", "raw", "index", "count", "data", "inherited", "errors", "warnings",
                    "pendingPaths", "activeData", "metaErrors", "conflicts", "unsafeNumbers");
            assertThat(details).containsEntry("raw", draft);
            JSONObject error = details.getJSONArray("errors").getJSONObject(0);
            assertThat(error.getJSONArray("path")).containsExactly("advanced", "example.server", "host-name");
            assertThat(error.getJSONArray("relatedPath")).containsExactly("advanced", "example.server.host-name");
            assertThat(error.getString("message")).contains("example.server.host-name", "[\"advanced\",\"example.server\",\"host-name\"]",
                    "[\"advanced\",\"example.server.host-name\"]");
            assertThat(Files.readString(path)).isEqualTo("{}");
        } finally {
            config.destroy();
        }
    }

    /**
     * 表单和保存文件都使用元数据声明的键，其他字段保留原来的层级
     */
    @Test
    void normalizesDeclaredPropertiesWithoutMovingUnknownSiblings() throws Exception {
        Path path = directory.resolve("config.json");
        Files.writeString(path, """
                {"advanced":{"server":{"port":8100,"extra":"keep"},
                             "spring.mail.properties":{"mail":{"smtp":{"auth":false}},"mail.custom":"keep"}}}
                """);
        ConfigService config = new ConfigService(path, registry, springPropertyBridge, false);
        try {
            ConfigEditService editService = new ConfigEditService(config, springPropertyBridge);
            JSONObject document = editService.read();
            assertThat(document.getJSONObject("data").getJSONObject("advanced"))
                    .containsEntry("server.port", 8100)
                    .containsEntry("server", JSONObject.of("extra", "keep"))
                    .containsEntry("spring.mail.properties", JSONObject.of("mail.custom", "keep", "mail.smtp.auth", false));
            assertThat(config.getSpringProperties()).containsEntry("server.port", 8100)
                    .containsEntry("spring.mail.properties[mail.smtp.auth]", false);
            JSONObject saved = editService.save(JSONObject.of("changes", List.of(
                    JSONObject.of("op", "set", "path", List.of("advanced", "server.port"), "value", 8200))));
            assertThat(saved.getJSONArray("errors")).isEmpty();
            assertThat(JSON.parseObject(Files.readString(path)).getJSONObject("advanced"))
                    .containsEntry("server.port", 8200)
                    .containsEntry("server", JSONObject.of("extra", "keep"))
                    .containsEntry("spring.mail.properties", JSONObject.of("mail.custom", "keep", "mail.smtp.auth", false));
        } finally {
            config.destroy();
        }
    }

    /**
     * 预览保留参与重复的那一项，同时清理没有重复的建议值
     */
    @Test
    void preservesDuplicatePathEvenIfItsValueMatchesTheSuggestion() throws Exception {
        ConfigService config = new ConfigService(directory.resolve("config.json"), registry, springPropertyBridge, false);
        try {
            ConfigEditService editService = new ConfigEditService(config, springPropertyBridge);
            JSONObject preview = editService.preview(JSONObject.of("raw", """
                    {"advanced":{"server":{"address":"127.0.0.1"},"server.address":"0.0.0.0","server.port":7827}}
                    """));
            assertThat(preview.getJSONArray("errors")).hasSize(1);
            assertThat(JSON.parseObject(preview.getString("json")).getJSONObject("advanced"))
                    .containsOnlyKeys("server", "server.address")
                    .containsEntry("server.address", "0.0.0.0");
        } finally {
            config.destroy();
        }
    }

    /**
     * 网页一次保存多项实时配置时只发布一个事件，重复保存不再发布
     */
    @Test
    void publishesOneEventForOneWebSave() throws Exception {
        ConfigService config = new ConfigService(directory.resolve("config.json"), registry, springPropertyBridge, false);
        try {
            List<StarBotConfigChangedEvent> events = new ArrayList<>();
            config.setApplicationEventPublisher(event -> events.add((StarBotConfigChangedEvent) event));
            ConfigEditService editService = new ConfigEditService(config, springPropertyBridge);
            JSONObject request = JSONObject.of("raw", """
                    {"starbot":{"core":{"command":{"prefix":"!","owners":{"qq":["123"]}}}},
                     "advanced":{"server.port":9000}}
                    """);
            editService.save(request);
            assertThat(events).hasSize(1);
            assertThat(events.get(0).getKeys()).containsExactlyInAnyOrder(
                    "starbot.core.command.prefix", "starbot.core.command.owners.qq");
            assertThat(events.get(0).isChanged("advanced.server.port")).isFalse();
            assertThat(editService.save(request).getBooleanValue("unchanged")).isTrue();
            assertThat(events).hasSize(1);
        } finally {
            config.destroy();
        }
    }

    /**
     * 关闭邮件时跳过参数校验并保留已填值，重新开启后恢复校验
     */
    @Test
    void skipsInactiveMailValidationAndKeepsParametersForReenabling() throws Exception {
        ConfigService config = new ConfigService(directory.resolve("config.json"), registry, springPropertyBridge, false);
        try {
            ConfigEditService editService = new ConfigEditService(config, springPropertyBridge);
            String disabled = """
                    {"starbot":{"core":{"mail":{"enabled":false}}},
                     "advanced":{"spring.mail.port":"-","spring.mail.properties.mail.smtp.auth":true}}
                    """;
            assertThat(editService.preview(JSONObject.of("raw", disabled)).getJSONArray("errors")).isEmpty();
            editService.save(JSONObject.of("raw", disabled));
            assertThat(editService.read().getJSONObject("data").getJSONObject("advanced")).containsEntry("spring.mail.port", "-");
            String enabled = disabled.replace("\"enabled\":false", "\"enabled\":true");
            assertThat(editService.preview(JSONObject.of("raw", enabled)).getJSONArray("errors")).isNotEmpty();
            assertThatThrownBy(() -> editService.save(JSONObject.of("raw", enabled))).isInstanceOf(ConfigException.class);
            assertThat(config.get("starbot.core.mail.enabled", Boolean.class)).isFalse();
        } finally {
            config.destroy();
        }
    }

    /**
     * 高级配置使用插件建议值，恢复建议值后删除文件中的覆盖并取消待重启标记
     */
    @Test
    void usesPluginMetaDefaultsForAdvancedAndPersistsOnlyOverrides() throws Exception {
        Path plugins = Files.createDirectories(directory.resolve("plugins"));
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(plugins.resolve("defaults.jar")))) {
            entry(jar, "plugin.json", "{\"groupId\":\"test\",\"artifactId\":\"defaults\",\"name\":\"Defaults\"}");
            entry(jar, "config/config.json", """
                    {"title":"Defaults","basePath":["starbot","defaults"],"groups":[{"target":"advanced",
                      "id":"native","title":"原生参数","fields":[
                        {"key":"example.capacity","type":"integer","suggestedValue":42},
                        {"key":"example.ratio","type":"number","suggestedValue":1.0},
                        {"key":"example.enabled","type":"boolean","suggestedValue":false},
                        {"key":"example.sources","type":"array","items":{"type":"string"},"suggestedValue":["primary"]},
                        {"key":"example.settings","suggestedValue":{"label":"main"},"type":"object","fields":[{"key":"label","type":"string"}]},
                        {"key":"example.bind-address","type":"string","suggestedValue":"127.0.0.1"}
                      ]}]}
                    """);
        }
        ConfigMetaRegistry registry = new ConfigMetaRegistry(plugins);
        SpringPropertyBridge springPropertyBridge = new SpringPropertyBridge(registry);
        Path path = directory.resolve("config.json");
        ConfigService config = new ConfigService(path, registry, springPropertyBridge, false);
        try {
            assertThat(registry.hasMetaErrors()).isFalse();
            assertThat(config.get("advanced.example.capacity", Integer.class)).isEqualTo(42);
            assertThat(config.get("advanced.example.enabled", Boolean.class)).isFalse();
            assertThat(config.get("advanced.example.sources", List.class)).isEqualTo(List.of("primary"));
            assertThat(config.get("advanced.example.settings", JSONObject.class)).containsEntry("label", "main");
            assertThat(config.get("advanced.example.bind-address")).isEqualTo("127.0.0.1");
            assertThat(path).doesNotExist();
            ConfigEditService editService = new ConfigEditService(config, springPropertyBridge);
            editService.save(JSONObject.of("raw", "{\"advanced\":{\"example.capacity\":70}}"));
            assertThat(JSON.parseObject(Files.readString(path)).getJSONObject("advanced"))
                    .containsOnlyKeys("example.capacity").containsEntry("example.capacity", 70);
            assertThat(config.get("advanced.example.capacity", Integer.class)).isEqualTo(42);
            editService.save(JSONObject.of("raw", "{\"advanced\":{\"example.capacity\":42,\"example.ratio\":1}}"));
            assertThat(JSON.parseObject(Files.readString(path))).isEmpty();
            assertThat(config.hasPendingChanges()).isFalse();
        } finally {
            config.destroy();
        }
    }

    /**
     * 启用邮件时校验收件和发件邮箱，密码只在开启 SMTP 身份认证时必填
     */
    @Test
    void requiresMailUsernameAndConditionallyRequiresPassword() throws Exception {
        ConfigService config = new ConfigService(directory.resolve("config.json"), registry, springPropertyBridge, false);
        try {
            ConfigEditService editService = new ConfigEditService(config, springPropertyBridge);
            JSONObject advanced = JSONObject.of("spring.mail.host", "smtp.example.com");
            JSONObject mail = JSONObject.of("enabled", true);
            JSONObject configuration = JSONObject.of("starbot", JSONObject.of("core", JSONObject.of("mail", mail)), "advanced", advanced);
            JSONObject request = JSONObject.of("raw", configuration.toJSONString());
            assertThat(editService.preview(request).getJSONArray("errors").toJavaList(JSONObject.class))
                    .extracting(issue -> issue.getString("message")).containsExactly("邮箱用户名需要填写");
            assertThatThrownBy(() -> editService.save(request)).isInstanceOf(ConfigException.class);

            advanced.put("spring.mail.username", "");
            assertThat(editService.preview(JSONObject.of("raw", configuration.toJSONString())).getJSONArray("errors").toJavaList(JSONObject.class))
                    .extracting(issue -> issue.getString("message")).containsExactly("邮箱用户名需要填写");
            advanced.put("spring.mail.username", "sender");
            assertThat(editService.preview(JSONObject.of("raw", configuration.toJSONString())).getJSONArray("errors").toJavaList(JSONObject.class))
                    .extracting(issue -> issue.getString("message")).containsExactly("邮箱用户名格式不正确");
            advanced.put("spring.mail.username", "sender@example.com");
            assertThat(editService.preview(JSONObject.of("raw", configuration.toJSONString())).getJSONArray("errors")).isEmpty();

            for (String invalid : List.of(" ", "recipient")) {
                mail.put("default-to", invalid);
                assertThat(editService.preview(JSONObject.of("raw", configuration.toJSONString())).getJSONArray("errors").toJavaList(JSONObject.class))
                        .extracting(issue -> issue.getString("message")).containsExactly("默认收件邮箱格式不正确");
            }
            for (String optional : List.of("", "recipient@example.com")) {
                mail.put("default-to", optional);
                assertThat(editService.preview(JSONObject.of("raw", configuration.toJSONString())).getJSONArray("errors")).isEmpty();
            }

            advanced.put("spring.mail.properties", JSONObject.of("mail.smtp.auth", true));
            JSONObject authenticated = JSONObject.of("raw", configuration.toJSONString());
            assertThat(editService.preview(authenticated).getJSONArray("errors").toJavaList(JSONObject.class))
                    .extracting(issue -> issue.getString("message")).containsExactly("密码或授权码需要填写");
            assertThatThrownBy(() -> editService.save(authenticated)).isInstanceOf(ConfigException.class);

            mail.put("enabled", false);
            advanced.remove("spring.mail.username");
            assertThat(editService.preview(JSONObject.of("raw", configuration.toJSONString())).getJSONArray("errors")).isEmpty();
            assertThat(directory.resolve("config.json")).doesNotExist();
        } finally {
            config.destroy();
        }
    }

    /**
     * 恢复默认后预览和保存的 JSON 一致，内容相同时不重复写文件
     */
    @Test
    void previewsRestoredDefaultsAsTheSameDocumentThatWillBeSaved() throws Exception {
        Path path = directory.resolve("config.json");
        Files.writeString(path, "{}");
        ConfigService config = new ConfigService(path, registry, springPropertyBridge, false);
        try {
            ConfigEditService editService = new ConfigEditService(config, springPropertyBridge);
            JSONObject edited = editService.preview(JSONObject.of("changes", List.of(
                    JSONObject.of("op", "set", "path", List.of("starbot", "core", "command", "prefix"), "value", "!"))));
            assertThat(edited).containsOnlyKeys("json", "draft", "errors", "warnings");
            JSONObject restored = editService.preview(JSONObject.of("baseRaw", edited.getString("json"), "changes", List.of(
                    JSONObject.of("op", "set", "path", List.of("starbot", "core", "command", "prefix"), "value", ""),
                    JSONObject.of("op", "set", "path", List.of("advanced", "server.port"), "value", 7827))));
            assertThat(JSON.parseObject(edited.getString("json")).getJSONObject("starbot")
                    .getJSONObject("core").getJSONObject("command").getString("prefix")).isEqualTo("!");
            assertThat(JSON.parseObject(restored.getString("json"))).isEmpty();
            assertThat(restored.getJSONObject("draft").getJSONObject("data")).isEmpty();
            assertThat(restored.getJSONObject("draft").getString("raw")).isEqualTo(restored.getString("json"));
            assertThat(config.get("starbot.core.command.prefix")).isEqualTo("");
            assertThat(Files.readString(path)).isEqualTo("{}");
            assertThat(editService.save(JSONObject.of("raw", restored.getString("json"))).getBooleanValue("unchanged")).isTrue();
            assertThat(Files.readString(path)).isEqualTo("{}");
        } finally {
            config.destroy();
        }
    }

    /**
     * 预览、保存和启动读取时把清空的默认收件邮箱当作 null
     */
    @Test
    void clearsDefaultMailRecipientWithEmptyString() throws Exception {
        Path path = directory.resolve("config.json");
        String raw = """
                {"starbot":{"core":{"mail":{"default-to":"","enabled":false},"command":{"prefix":"!"}}}}
                """;
        Files.writeString(path, raw);
        ConfigService config = new ConfigService(path, registry, springPropertyBridge, false);
        try {
            ConfigEditService editService = new ConfigEditService(config, springPropertyBridge);
            assertThat(config.get("starbot.core.mail.default-to")).isNull();
            editService.save(JSONObject.of("raw", raw.replace("\"default-to\":\"\"", "\"default-to\":\"recipient@example.com\"")));
            assertThat(config.get("starbot.core.mail.default-to")).isEqualTo("recipient@example.com");
            JSONObject request = JSONObject.of("raw", raw);
            assertThat(JSON.parseObject(editService.preview(request).getString("json"))
                    .getJSONObject("starbot").getJSONObject("core")).doesNotContainKey("mail");
            editService.save(request);
            assertThat(JSON.parseObject(Files.readString(path)).getJSONObject("starbot").getJSONObject("core"))
                    .doesNotContainKey("mail").containsKey("command");
            assertThat(config.get("starbot.core.mail.default-to")).isNull();
        } finally {
            config.destroy();
        }
    }

    /**
     * 预览和保存拒绝格式不正确的表单操作，配置文件保持原样
     */
    @Test
    void rejectsMalformedFormOperations() throws Exception {
        Path path = directory.resolve("config.json");
        Files.writeString(path, "{}");
        ConfigService config = new ConfigService(path, registry, springPropertyBridge, false);
        try {
            ConfigEditService editService = new ConfigEditService(config, springPropertyBridge);
            for (Object changes : List.of(new JSONObject(), List.of("bad"))) {
                JSONObject request = JSONObject.of("changes", changes);
                ConfigException previewError = catchThrowableOfType(ConfigException.class, () -> editService.preview(request));
                ConfigException saveError = catchThrowableOfType(ConfigException.class, () -> editService.save(request));
                assertThat(previewError.getStatus()).isEqualTo(422);
                assertThat(saveError.getStatus()).isEqualTo(422);
            }
            assertThat(Files.readString(path)).isEqualTo("{}");
        } finally {
            config.destroy();
        }
    }

    /**
     * 预览清理默认覆盖时保留显式 null、false 和未知空列表，且不提前写入文件
     */
    @Test
    void previewKeepsExplicitNullAndUnknownValuesWhileClearingDefaults() throws Exception {
        ConfigService config = new ConfigService(directory.resolve("config.json"), registry, springPropertyBridge, false);
        try {
            ConfigEditService editService = new ConfigEditService(config, springPropertyBridge);
            JSONObject preview = editService.preview(JSONObject.of("raw", """
                    {"starbot":{"core":{"command":{"prefix":null},"mail":{"enabled":false}},
                     "unknown":{"enabled":false,"empty":[]}},"advanced":{"server.port":7827,"example.enabled":false}}
                    """));
            JSONObject data = JSON.parseObject(preview.getString("json"));
            JSONObject core = data.getJSONObject("starbot").getJSONObject("core");
            assertThat(core.getJSONObject("command")).containsEntry("prefix", null);
            assertThat(core).doesNotContainKey("mail");
            assertThat(data.getJSONObject("starbot").getJSONObject("unknown")).containsEntry("enabled", false);
            assertThat(data.getJSONObject("starbot").getJSONObject("unknown").getJSONArray("empty")).isEmpty();
            assertThat(data.getJSONObject("advanced")).containsOnlyKeys("example.enabled");
            assertThat(preview.getJSONObject("draft").getJSONObject("data")).isEqualTo(data);
            assertThat(directory.resolve("config.json")).doesNotExist();
        } finally {
            config.destroy();
        }
    }

    /**
     * 保存时检查配置，可即时生效的配置立即更新，其余配置保存后标记为待重启
     */
    @Test
    void validatesWebSaveAndKeepsRestartValuesInFile() throws Exception {
        ConfigService config = new ConfigService(directory.resolve("config.json"), registry, springPropertyBridge, false);
        try {
            ConfigEditService editService = new ConfigEditService(config, springPropertyBridge);
            assertThatThrownBy(() -> editService.save(JSONObject.of("raw", "{\"advanced\":{\"server.port\":-1}}")))
                    .isInstanceOf(ConfigException.class);
            assertThat(directory.resolve("config.json")).doesNotExist();
            JSONObject result = editService.save(JSONObject.of("raw", "{\"advanced\":{\"server.port\":9000},\"starbot\":{\"core\":{\"command\":{\"prefix\":\"!\"}}}}"));
            assertThat(result).containsOnlyKeys("document", "unchanged", "restartRequired", "errors", "warnings");
            assertThat(config.get("advanced.server.port", Integer.class)).isEqualTo(7827);
            assertThat(config.get("starbot.core.command.prefix")).isEqualTo("!");
            assertThat(result.getBooleanValue("restartRequired")).isTrue();
            assertThat(editService.read().getJSONObject("data").getJSONObject("advanced").getIntValue("server.port")).isEqualTo(9000);
        } finally {
            config.destroy();
        }
    }

    /**
     * 允许通过原文修复损坏的 JSON，并保留未知配置、字面键名和空列表
     */
    @Test
    void repairsRawJsonAndRetainsUnknownLiteralKeys() throws Exception {
        Path path = directory.resolve("config.json");
        Files.writeString(path, "{");
        ConfigService config = new ConfigService(path, registry, springPropertyBridge, true);
        try {
            ConfigEditService editService = new ConfigEditService(config, springPropertyBridge);
            assertThat(editService.read()).containsKeys("error", "conflicts");
            ConfigException previewError = catchThrowableOfType(ConfigException.class,
                    () -> editService.preview(JSONObject.of("raw", "{")));
            assertThat(previewError.getStatus()).isEqualTo(422);
            assertThat(previewError).hasMessage("JSON 格式错误, 请在原文中修正");
            editService.save(JSONObject.of("raw", "{\"advanced\":{\"example.map[key.v1]\":false},\"starbot\":{\"unknown\":{\"empty\":[]}}}"));
            JSONObject data = editService.read().getJSONObject("data");
            assertThat(data.getJSONObject("advanced")).containsEntry("example.map[key.v1]", false);
            assertThat(data.getJSONObject("starbot").getJSONObject("unknown").getJSONArray("empty")).isEmpty();
        } finally {
            config.destroy();
        }
    }

    /**
     * 仅在邮件开启时要求填写有效的 SMTP 主机，保存后的开关等待重启生效
     */
    @Test
    void requiresMailHostOnlyWhenEnabled() throws Exception {
        ConfigService config = new ConfigService(directory.resolve("config.json"), registry, springPropertyBridge, false);
        try {
            ConfigEditService editService = new ConfigEditService(config, springPropertyBridge);
            String mail = """
                    {"starbot":{"core":{"mail":{"enabled":%s}}},
                     "advanced":{"spring.mail.host":"%s","spring.mail.username":"sender@example.com"}}
                    """;
            JSONObject disabled = editService.preview(JSONObject.of("raw", mail.formatted(false, "smtp.example.com:465")));
            assertThat(disabled.getJSONArray("errors")).isEmpty();
            assertThatThrownBy(() -> editService.save(JSONObject.of("raw", mail.formatted(true, ""))))
                    .isInstanceOfSatisfying(ConfigException.class, error ->
                            assertThat(error.getDetails().toString()).contains("SMTP 主机需要填写"));
            for (String host : List.of("c", "mail-relay", "smtp.example.com:465")) {
                assertThatThrownBy(() -> editService.save(JSONObject.of("raw", mail.formatted(true, host))))
                        .isInstanceOfSatisfying(ConfigException.class, error ->
                                assertThat(error.getDetails().toString()).contains("SMTP 主机格式不正确"));
            }
            for (String host : List.of("localhost", "192.0.2.1", "2001:db8::1")) {
                JSONObject preview = editService.preview(JSONObject.of("raw", mail.formatted(true, host)));
                assertThat(preview.getJSONArray("errors")).isEmpty();
            }
            editService.save(JSONObject.of("raw", mail.formatted(true, "smtp.example.com")));
            assertThat(config.get("starbot.core.mail.enabled", Boolean.class)).isFalse();
            editService.save(JSONObject.of("raw", "{}"));
            assertThat(JSON.parseObject(Files.readString(config.getPath()))).doesNotContainKey("advanced");
        } finally {
            config.destroy();
        }
    }

    /**
     * 移除监听地址和服务端口的覆盖值后恢复建议值，并清除相关的待重启变更
     */
    @Test
    void removingServerOverridesRestoresSuggestionsAndCancelsPendingChanges() throws Exception {
        Path path = directory.resolve("config.json");
        Files.writeString(path, "{}");
        ConfigService config = new ConfigService(path, registry, springPropertyBridge, false);
        try {
            ConfigEditService editService = new ConfigEditService(config, springPropertyBridge);
            assertThat(config.get("advanced.server.address")).isEqualTo("0.0.0.0");
            assertThat(config.get("advanced.server.port")).isEqualTo(7827);
            assertThat(config.getPendingPaths()).isEmpty();
            editService.save(JSONObject.of("raw", "{\"advanced\":{\"server.address\":\"127.0.0.1\",\"server.port\":0}}"));
            JSONObject document = editService.read();
            assertThat(document.getJSONArray("pendingPaths"))
                    .containsExactlyInAnyOrder(List.of("advanced", "server.address"), List.of("advanced", "server.port"));
            assertThat(document.getJSONObject("activeData").getJSONObject("advanced"))
                    .containsEntry("server.address", "0.0.0.0").containsEntry("server.port", 7827);
            JSONObject restore = JSONObject.of("changes", List.of(
                    JSONObject.of("op", "unset", "path", List.of("advanced", "server.address")),
                    JSONObject.of("op", "unset", "path", List.of("advanced", "server.port"))));
            editService.save(restore);
            assertThat(editService.read().getJSONArray("pendingPaths")).isEmpty();
            assertThat(JSON.parseObject(Files.readString(path))).doesNotContainKey("advanced");
        } finally {
            config.destroy();
        }
    }

    /**
     * 清空 SMTP 端口后恢复建议值，并移除已保存的端口覆盖值
     */
    @Test
    void clearingSmtpPortRestoresItsSuggestion() throws Exception {
        ConfigService config = new ConfigService(directory.resolve("config.json"), registry, springPropertyBridge, false);
        try {
            ConfigEditService editService = new ConfigEditService(config, springPropertyBridge);
            String raw = """
                    {"starbot":{"core":{"mail":{"enabled":true}}},
                     "advanced":{"spring.mail.host":"smtp.example.com","spring.mail.username":"sender@example.com","spring.mail.port":%s}}
                    """;
            editService.save(JSONObject.of("raw", raw.formatted("587")));
            List<String> portPath = List.of("advanced", "spring.mail.port");
            assertThat(config.getPendingPaths()).contains(portPath);

            JSONObject request = JSONObject.of("changes", List.of(JSONObject.of("op", "unset", "path", portPath)));
            JSONObject preview = editService.preview(request);
            assertThat(preview.getJSONArray("errors")).isEmpty();
            assertThat(JSON.parseObject(preview.getString("json")).getJSONObject("advanced"))
                    .containsOnlyKeys("spring.mail.host", "spring.mail.username");
            editService.save(request);
            assertThat(Files.readString(config.getPath())).isEqualTo(preview.getString("json"));
            assertThat(config.getPendingPaths()).doesNotContain(portPath);
            assertThat(config.getSpringProperties()).containsEntry("spring.mail.port", 25);
        } finally {
            config.destroy();
        }
    }

    /**
     * AI 模型的请求超时按秒填写，只接受 HTTP 客户端支持的正整数
     */
    @Test
    void validatesAiTimeoutInSeconds() throws Exception {
        ConfigService config = new ConfigService(directory.resolve("config.json"), registry, springPropertyBridge, false);
        try {
            ConfigEditService editService = new ConfigEditService(config, springPropertyBridge);
            String raw = """
                    {"starbot":{"core":{"ai":{"models":{"demo":{"provider":"OPENAI","base-url":"https://api.example.com",
                    "api-key":"demo-token","model":"demo","timeout":%s}}}}}}
                    """;
            List<String> timeoutPath = List.of("starbot", "core", "ai", "models", "demo", "timeout");
            assertThat(editService.preview(JSONObject.of("raw", raw.formatted(7))).getJSONArray("errors")).isEmpty();
            for (Object invalid : List.of(0, 2147484, 1.5, "\"30s\"")) {
                assertThat(editService.preview(JSONObject.of("raw", raw.formatted(invalid))).getJSONArray("errors").toJavaList(JSONObject.class))
                        .extracting(issue -> issue.getJSONArray("path").toJavaList(String.class)).containsExactly(timeoutPath);
            }
            JSONObject saved = editService.save(JSONObject.of("raw", raw.formatted(7)));
            assertThat(saved.getJSONObject("document").getJSONObject("data").getJSONObject("starbot").getJSONObject("core")
                    .getJSONObject("ai").getJSONObject("models").getJSONObject("demo")).containsEntry("timeout", 7);
        } finally {
            config.destroy();
        }
    }

    /**
     * 仅开启自动下载时校验 Maven 仓库数量和地址格式
     */
    @Test
    void validatesMavenRepositoriesForAutomaticDownloads() throws Exception {
        ConfigService config = new ConfigService(directory.resolve("config.json"), registry, springPropertyBridge, false);
        try {
            ConfigEditService editService = new ConfigEditService(config, springPropertyBridge);
            String raw = """
                    {"starbot":{"core":{"plugin":{%s"maven-base-urls":%s}}}}
                    """;
            assertThat(editService.preview(JSONObject.of("raw", raw.formatted("", "[]"))).getJSONArray("errors").toJavaList(JSONObject.class))
                    .extracting(issue -> issue.getString("message")).containsExactly("开启自动下载插件依赖时至少填写一个 Maven 仓库地址");
            assertThat(editService.preview(JSONObject.of("raw", raw.formatted("\"auto-download-dependency\":false,", "[]")))
                    .getJSONArray("errors")).isEmpty();
            JSONObject disabled = JSONObject.of("raw", raw.formatted("\"auto-download-dependency\":false,", "[\" \"]"));
            assertThat(editService.preview(disabled).getJSONArray("errors")).isEmpty();
            assertThat(editService.save(disabled).getJSONObject("document").getJSONObject("data")
                    .getJSONObject("starbot").getJSONObject("core").getJSONObject("plugin").getJSONArray("maven-base-urls"))
                    .containsExactly(" ");
            assertThat(editService.preview(JSONObject.of("raw", raw.formatted("", "[\" \"]"))).getJSONArray("errors").toJavaList(JSONObject.class))
                    .extracting(issue -> issue.getString("message")).containsExactly("Maven 仓库地址格式不正确");
            assertThat(editService.preview(JSONObject.of("raw", raw.formatted("", "[\"https://repo1.maven.org/maven2\"]")))
                    .getJSONArray("errors")).isEmpty();
        } finally {
            config.destroy();
        }
    }

    /**
     * 推送接口允许带查询参数的 HTTP 地址，保存时拒绝不完整的地址
     */
    @Test
    void validatesSenderEndpointWithQueryParameters() throws Exception {
        ConfigService config = new ConfigService(directory.resolve("config.json"), registry, springPropertyBridge, false);
        try {
            ConfigEditService editService = new ConfigEditService(config, springPropertyBridge);
            JSONObject sender = JSONObject.of("name", "test", "url", "https://example.com/send?channel=alerts&tag=a%20b");
            JSONObject configuration = JSONObject.of("starbot", JSONObject.of("core", JSONObject.of("sender", List.of(sender))));
            JSONObject valid = JSONObject.of("raw", configuration.toJSONString());
            assertThat(editService.preview(valid).getJSONArray("errors")).isEmpty();
            editService.save(valid);
            assertThat(Files.readString(config.getPath())).contains(sender.getString("url"));

            sender.put("url", "https:///send?channel=alerts");
            JSONObject invalid = JSONObject.of("raw", configuration.toJSONString());
            assertThat(editService.preview(invalid).getJSONArray("errors").toJavaList(JSONObject.class))
                    .extracting(issue -> issue.getString("message")).containsExactly("推送接口地址格式不正确");
            assertThatThrownBy(() -> editService.save(invalid)).isInstanceOf(ConfigException.class);
            assertThat(Files.readString(config.getPath())).contains("https://example.com/send?channel=alerts&tag=a%20b");
        } finally {
            config.destroy();
        }
    }

    /**
     * 拒绝空白地址和正在使用的空白文件路径，必填项的空值只报告一次
     */
    @Test
    void rejectsBlankAddressesAndPathsUsedByEnabledFeatures() throws Exception {
        ConfigService config = new ConfigService(directory.resolve("config.json"), registry, springPropertyBridge, false);
        try {
            ConfigEditService editService = new ConfigEditService(config, springPropertyBridge);
            String raw = """
                    {"advanced":{"server.address":" ","spring.mail.host":" ","spring.mail.username":" "},"starbot":{"core":{
                      "mail":{"enabled":true},"command":{"disabled-command-path":" "},
                      "datasource":{"json-path":" "},"live":{"live-data-path":" "},"sender":[{"name":"test","url":null}]
                    }}}
                    """;
            assertThat(editService.preview(JSONObject.of("raw", raw)).getJSONArray("errors").toJavaList(JSONObject.class))
                    .extracting(issue -> issue.getString("message"))
                    .containsExactlyInAnyOrder("监听地址格式不正确", "SMTP 主机格式不正确", "邮箱用户名格式不正确", "命令禁用配置文件路径不能为空白",
                            "使用 JSON 数据源时请填写 JSON 文件路径", "开启直播数据保存时请填写直播数据文件路径", "推送接口地址需要填写");
            String disabled = """
                    {"advanced":{"spring.mail.host":" "},
                     "starbot":{"core":{"live":{"save-live-data":false,"live-data-path":" "}}}}
                    """;
            JSONObject preview = editService.preview(JSONObject.of("raw", disabled));
            assertThat(preview.getJSONArray("errors")).isEmpty();
            JSONObject blankAdvanced = JSONObject.of("raw", """
                    {"advanced":{"server.address":"","server.port":"","spring.mail.host":"smtp.example.com",
                                 "spring.mail.username":"sender@example.com","spring.mail.protocol":"","spring.mail.default-encoding":""},
                     "starbot":{"core":{"mail":{"enabled":true}}}}
                    """);
            assertThat(editService.preview(blankAdvanced).getJSONArray("errors").toJavaList(JSONObject.class))
                    .extracting(issue -> issue.getString("message"))
                    .containsExactlyInAnyOrder("监听地址格式不正确", "StarBot 服务端口的数据类型不正确", "邮件协议不能为空白", "邮件编码不能为空白");
            assertThatThrownBy(() -> editService.save(blankAdvanced)).isInstanceOf(ConfigException.class);
        } finally {
            config.destroy();
        }
    }

    /**
     * 监听地址拒绝单段主机名，支持 IPv6 和 Spring 占位符
     */
    @Test
    void validatesServerListenAddress() throws Exception {
        ConfigService config = new ConfigService(directory.resolve("config.json"), registry, springPropertyBridge, false);
        try {
            ConfigEditService editService = new ConfigEditService(config, springPropertyBridge);
            JSONObject advanced = JSONObject.of("server.address", "::1");
            JSONObject configuration = JSONObject.of("advanced", advanced);
            JSONObject valid = JSONObject.of("raw", configuration.toJSONString());
            assertThat(editService.preview(valid).getJSONArray("errors")).isEmpty();
            editService.save(valid);

            advanced.put("server.address", "test");
            JSONObject invalid = JSONObject.of("raw", configuration.toJSONString());
            assertThat(editService.preview(invalid).getJSONArray("errors").toJavaList(JSONObject.class))
                    .extracting(issue -> issue.getString("message")).containsExactly("监听地址格式不正确");
            assertThatThrownBy(() -> editService.save(invalid)).isInstanceOf(ConfigException.class);
            assertThat(Files.readString(config.getPath())).contains("::1");

            advanced.put("server.address", "${STARBOT_ADDRESS:127.0.0.1}");
            JSONObject placeholder = editService.preview(JSONObject.of("raw", configuration.toJSONString()));
            assertThat(placeholder.getJSONArray("errors")).isEmpty();
            assertThat(placeholder.getJSONArray("warnings")).hasSize(1);
        } finally {
            config.destroy();
        }
    }

    /**
     * 画布扩展高度必须大于零，自动保存间隔仅在保存直播数据时校验
     */
    @Test
    void validatesCanvasExpansionAndEnabledLiveDataSaving() throws Exception {
        ConfigService config = new ConfigService(directory.resolve("config.json"), registry, springPropertyBridge, false);
        try {
            ConfigEditService editService = new ConfigEditService(config, springPropertyBridge);
            String raw = """
                    {"starbot":{"core":{
                      "paint":{"auto-expand-height":%d},
                      "live":{"save-live-data":%s,"auto-save-live-data-interval":0}
                    }}}
                    """;
            assertThat(editService.preview(JSONObject.of("raw", raw.formatted(0, true))).getJSONArray("errors").toJavaList(JSONObject.class))
                    .extracting(issue -> issue.getJSONArray("path").stream().map(Object::toString).toList())
                    .containsExactlyInAnyOrder(List.of("starbot", "core", "paint", "auto-expand-height"),
                            List.of("starbot", "core", "live", "auto-save-live-data-interval"));
            assertThat(editService.preview(JSONObject.of("raw", raw.formatted(1, false))).getJSONArray("errors")).isEmpty();
        } finally {
            config.destroy();
        }
    }

    /**
     * 仅已保存的重启项进入待生效列表，恢复当前值或重新启动后清除标记
     */
    @Test
    void reportsOnlySavedPendingPathsAndClearsThemWhenRestoredOrRestarted() throws Exception {
        Path path = directory.resolve("config.json");
        ConfigService config = new ConfigService(path, registry, springPropertyBridge, false);
        try {
            ConfigEditService editService = new ConfigEditService(config, springPropertyBridge);
            assertThat(editService.read().getJSONArray("pendingPaths")).isEmpty();
            editService.preview(JSONObject.of("raw", "{\"advanced\":{\"server.port\":9000}}"));
            assertThat(editService.read().getJSONArray("pendingPaths")).isEmpty();
            JSONObject saved = editService.save(JSONObject.of("raw", """
                    {"advanced":{"server.port":9000},"starbot":{"core":{"command":{"prefix":"!"}}}}
                    """));
            assertThat(saved.getJSONObject("document").getJSONArray("pendingPaths"))
                    .containsExactly(List.of("advanced", "server.port"));
            assertThat(saved.getBooleanValue("restartRequired")).isTrue();
            assertThat(config.get("advanced.server.port", Integer.class)).isEqualTo(7827);
            assertThat(config.get("starbot.core.command.prefix")).isEqualTo("!");
            assertThat(editService.read().getJSONArray("pendingPaths")).containsExactly(List.of("advanced", "server.port"));
            JSONObject restored = editService.save(JSONObject.of("raw", "{\"advanced\":{\"server.port\":7827}}"));
            assertThat(restored.getBooleanValue("restartRequired")).isFalse();
            assertThat(restored.getJSONObject("document").getJSONArray("pendingPaths")).isEmpty();
            assertThat(editService.read().getJSONArray("pendingPaths")).isEmpty();
            editService.save(JSONObject.of("raw", "{\"advanced\":{\"server.port\":9100}}"));
        } finally {
            config.destroy();
        }
        ConfigService restarted = new ConfigService(path, registry, springPropertyBridge, false);
        try {
            assertThat(restarted.get("advanced.server.port", Integer.class)).isEqualTo(9100);
            assertThat(new ConfigEditService(restarted, springPropertyBridge).read().getJSONArray("pendingPaths")).isEmpty();
        } finally {
            restarted.destroy();
        }
    }

    /**
     * 删除日志覆盖并恢复 Maven 默认仓库时，预览和保存均移除空分组
     */
    @Test
    void removesEmptySectionsAfterDeletingOverrides() throws Exception {
        Path path = directory.resolve("config.json");
        ConfigService config = new ConfigService(path, registry, springPropertyBridge, false);
        try {
            ConfigEditService editService = new ConfigEditService(config, springPropertyBridge);
            editService.save(JSONObject.of("raw", """
                    {"advanced":{"logging.level":{"com.example":"DEBUG"}},
                     "starbot":{"core":{"plugin":{"maven-base-urls":["https://repo1.maven.org/maven2"]}}}}
                    """));

            JSONObject changes = JSONObject.of("changes", List.of(
                    JSONObject.of("op", "unset", "path", List.of("advanced", "logging.level")),
                    JSONObject.of("op", "set", "path", List.of("starbot", "core", "plugin", "maven-base-urls"), "value",
                            List.of("https://maven.aliyun.com/repository/public", "https://repo1.maven.org/maven2"))));
            assertThat(JSON.parseObject(editService.preview(changes).getString("json"))).isEmpty();
            assertThat(JSON.parseObject(editService.preview(JSONObject.of("raw", """
                    {"advanced":{},"starbot":{"core":{"plugin":{}}}}
                    """)).getString("json"))).isEmpty();
            JSONObject saved = editService.save(changes);
            assertThat(saved.getBooleanValue("restartRequired")).isFalse();
            assertThat(editService.read().getJSONObject("data")).isEmpty();
            assertThat(JSON.parseObject(Files.readString(path))).isEmpty();
        } finally {
            config.destroy();
        }
    }

    /**
     * 清理空分组时保留用来覆盖默认值的空列表和明确填写的自定义对象
     */
    @Test
    void retainsExplicitEmptyValuesAndOtherSettingsWhenRemovingEmptySections() throws Exception {
        ConfigService config = new ConfigService(directory.resolve("config.json"), registry, springPropertyBridge, false);
        try {
            ConfigEditService editService = new ConfigEditService(config, springPropertyBridge);
            JSONObject request = JSONObject.of("raw", """
                    {"advanced":{},"starbot":{"core":{"log":{},"plugin":{"auto-download-dependency":false,"maven-base-urls":[]}},
                     "custom":{"empty":{}}}}
                    """);
            JSONObject expected = JSON.parseObject("""
                    {"starbot":{"core":{"plugin":{"auto-download-dependency":false,"maven-base-urls":[]}},"custom":{"empty":{}}}}
                    """);
            assertThat(JSON.parseObject(editService.preview(request).getString("json"))).isEqualTo(expected);
            assertThat(editService.save(request).getJSONObject("document").getJSONObject("data")).isEqualTo(expected);
        } finally {
            config.destroy();
        }
    }

    /**
     * 待重启配置保留键名中的点号，并区分清空对象、设为 null 和删除
     */
    @Test
    void preservesLiteralKeysAndExplicitEmptyOrNullPendingValues() throws Exception {
        ConfigService config = new ConfigService(directory.resolve("config.json"), registry, springPropertyBridge, false);
        try {
            ConfigEditService editService = new ConfigEditService(config, springPropertyBridge);
            editService.save(JSONObject.of("raw", """
                    {"advanced":{"logging.level":{"com.example":"DEBUG"}},
                     "starbot":{"custom":{"empty":{},"nullable":null}}}
                    """));
            assertThat(config.getPendingPaths()).containsExactlyInAnyOrder(
                    List.of("advanced", "logging.level", "com.example"),
                    List.of("starbot", "custom", "empty"), List.of("starbot", "custom", "nullable"));
            editService.save(JSONObject.of("raw", "{}"));
            assertThat(config.getPendingPaths()).isEmpty();
        } finally {
            config.destroy();
        }
    }

    /**
     * 建议冲突可逐项保存，等于某个建议值的明确选择不会被默认值精简删除
     */
    @Test
    void savesSuggestionChoicesIncrementallyAndKeepsExplicitValuesAcrossRestart() throws Exception {
        Path plugins = Files.createDirectories(directory.resolve("plugins"));
        for (String name : List.of("first", "second")) {
            try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(plugins.resolve(name + ".jar")))) {
                entry(jar, "plugin.json", "{\"groupId\":\"test\",\"artifactId\":\"" + name + "\",\"name\":\"" + name + "\"}");
                entry(jar, "config/config.json", """
                        {"title":"%s","basePath":["starbot","%s"],"groups":[{"id":"main","title":"设置","target":"advanced","fields":[
                          {"key":"example.enabled","type":"boolean","suggestedValue":%s},
                          {"key":"example.interval","type":"integer","suggestedValue":%s,"minimum":1,"maximum":100}]}]}
                        """.formatted(name, name, "first".equals(name), "first".equals(name) ? 10 : 20));
            }
        }
        ConfigMetaRegistry registry = new ConfigMetaRegistry(plugins);
        SpringPropertyBridge springPropertyBridge = new SpringPropertyBridge(registry);
        Path path = directory.resolve("config.json");
        ConfigService config = new ConfigService(path, registry, springPropertyBridge, false);
        try {
            ConfigEditService editService = new ConfigEditService(config, springPropertyBridge);
            assertThat(config.hasUnresolvedConflicts()).isTrue();
            assertThat(config.get("advanced.example.enabled")).isNull();
            editService.save(JSONObject.of("raw", "{\"advanced\":{\"example.enabled\":false}}"));
            assertThat(config.hasUnresolvedConflicts()).isTrue();
            assertThat(Files.readString(path)).contains("example.enabled");
            editService.save(JSONObject.of("raw", "{\"advanced\":{\"example.enabled\":false,\"example.interval\":20}}"));
            assertThat(config.hasUnresolvedConflicts()).isFalse();
            assertThat(config.get("advanced.example.enabled")).isNull();
            assertThat(config.hasPendingChanges()).isTrue();
            assertThatThrownBy(() -> editService.save(JSONObject.of("raw", "{\"advanced\":{\"example.interval\":-1}}")))
                    .isInstanceOf(ConfigException.class);
            ConfigService restarted = new ConfigService(path, registry, springPropertyBridge, false);
            try {
                assertThat(restarted.get("advanced.example.enabled")).isEqualTo(false);
                assertThat(restarted.get("advanced.example.interval", Integer.class)).isEqualTo(20);
                editService.save(JSONObject.of("raw", "{}"));
                assertThat(config.hasUnresolvedConflicts()).isTrue();
                assertThat(restarted.get("advanced.example.interval", Integer.class)).isEqualTo(20);
            } finally {
                restarted.destroy();
            }
        } finally {
            config.destroy();
        }
    }

    /**
     * 类型冲突时根据用户所选的 JSON 类型保存，不应用单个插件的限制
     */
    @Test
    void savesTypeConflictChoicesAsOriginalJsonValuesAndStillValidatesOtherFields() throws Exception {
        Path plugins = Files.createDirectories(directory.resolve("plugins"));
        for (String type : List.of("integer", "string")) {
            try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(plugins.resolve(type + ".jar")))) {
                entry(jar, "plugin.json", "{\"groupId\":\"test\",\"artifactId\":\"" + type + "\",\"name\":\"" + type + "\"}");
                entry(jar, "config/config.json", """
                        {"title":"%s","basePath":["starbot","%s"],"groups":[{"id":"main","title":"设置","target":"advanced",
                         "fields":[{"key":"example.mixed","type":"%s","suggestedValue":null}]}]}
                        """.formatted(type, type, type));
            }
        }
        Path path = directory.resolve("config.json");
        ConfigMetaRegistry registry = new ConfigMetaRegistry(plugins);
        SpringPropertyBridge springPropertyBridge = new SpringPropertyBridge(registry);
        ConfigService config = new ConfigService(path, registry, springPropertyBridge, false);
        try {
            ConfigEditService editService = new ConfigEditService(config, springPropertyBridge);
            for (String value : List.of("0", "false", "\"\"", "null", "{\"items\":[1]}", "[]")) {
                editService.save(JSONObject.of("raw", "{\"advanced\":{\"example.mixed\":" + value + "}}"));
                JSONObject stored = com.alibaba.fastjson2.JSON.parseObject(Files.readString(path)).getJSONObject("advanced");
                assertThat(stored).containsKey("example.mixed");
                assertThat(stored.get("example.mixed")).isEqualTo(com.alibaba.fastjson2.JSON.parse(value));
                assertThat(editService.read().getJSONArray("conflicts").getJSONObject(0).getBooleanValue("resolved")).isTrue();
            }
            assertThatThrownBy(() -> editService.save(JSONObject.of("raw", "{\"advanced\":{\"example.mixed\":0,\"server.port\":-1}}")))
                    .isInstanceOf(ConfigException.class);
            assertThatThrownBy(() -> editService.save(JSONObject.of("raw", "not-json"))).isInstanceOf(ConfigException.class);
        } finally {
            config.destroy();
        }
    }

    /**
     * 向测试插件 JAR 写入 UTF-8 文本资源
     * @param jar JAR 输出流
     * @param name 资源路径
     * @param content 资源内容
     */
    private static void entry(JarOutputStream jar, String name, String content) throws Exception {
        jar.putNextEntry(new JarEntry(name));
        jar.write(content.getBytes(StandardCharsets.UTF_8));
        jar.closeEntry();
    }
}
