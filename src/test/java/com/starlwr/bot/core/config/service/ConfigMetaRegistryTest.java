package com.starlwr.bot.core.config.service;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.config.ConfigService;
import com.starlwr.bot.core.config.SpringPropertyBridge;
import com.starlwr.bot.core.config.StarBotCoreProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 配置元数据注册表测试
 */
class ConfigMetaRegistryTest {
    @TempDir
    Path directory;

    /**
     * 多个模块可以为同一前缀声明同一个配置类
     */
    @Test
    void sharesConfigurationClassForSamePrefix() throws Exception {
        plugin("shared-prefix", """
                {"title":"Shared prefix","basePath":["starbot","core"],"configurationClass":"%s","groups":[]}
                """.formatted(StarBotCoreProperties.class.getName()));
        ConfigMetaRegistry registry = new ConfigMetaRegistry(directory.resolve("plugins"));
        assertThat(registry.getConfigurationClasses()).containsEntry("starbot.core", StarBotCoreProperties.class.getName());
        assertThat(registry.hasConfigurationClassConflicts()).isFalse();
        assertThat(registry.hasMetaErrors()).isFalse();
    }

    /**
     * 同一前缀声明不同配置类时保留双方元数据，跳过该前缀的默认值
     */
    @Test
    void reportsDifferentConfigurationClassesForSamePrefix() throws Exception {
        for (String module : List.of("a", "b")) {
            plugin(module, """
                    {"title":"Module %s","basePath":["starbot","demo"],"configurationClass":"example.Properties_%s",
                     "groups":[{"id":"settings","title":"Settings","fields":[{"key":"field-%s","type":"string"}]}]}
                    """.formatted(module, module, module));
        }
        ConfigMetaRegistry registry = new ConfigMetaRegistry(directory.resolve("plugins"));
        assertThat(registry.getConfigurationClasses()).doesNotContainKey("starbot.demo")
                .containsEntry("starbot.core", StarBotCoreProperties.class.getName());
        assertThat(registry.hasConfigurationClassConflicts()).isTrue();
        assertThat(registry.hasMetaErrors()).isTrue();
        assertThat(registry.getMeta().stream().map(module -> module.getString("id"))).contains("test:a", "test:b");
        assertThat(registry.getDiagnostics()).singleElement().satisfies(issue -> {
            assertThat(issue.getString("title")).isEqualTo("同一配置前缀声明了不同配置类");
            assertThat(issue.getJSONArray("sources").toJSONString()).contains(
                    "Module a (test:a)", "Module b (test:b)", "[\"starbot\",\"demo\"]",
                    "example.Properties_a", "example.Properties_b");
        });

        Path file = directory.resolve("config.json");
        Files.writeString(file, """
                {"starbot":{"demo":{"field-a":"explicit"}}}
                """);
        for (boolean editing : List.of(false, true)) {
            ConfigService config = new ConfigService(file, registry, new SpringPropertyBridge(registry), editing);
            try {
                config.registerDefaults("starbot.demo", Map.of("field-a", "source-a", "field-b", "source-b"));
                assertThat(config.get("starbot.demo.field-a")).isEqualTo("explicit");
                assertThat(config.get("starbot.demo.field-b")).isNull();
            } finally {
                config.destroy();
            }
        }
    }

    /**
     * 加载达到 8 MiB 的配置元数据，跳过超出上限的文件
     */
    @Test
    void enforcesEightMebibyteMetaLimit() throws Exception {
        String meta = """
                {"title":"Metadata limit","basePath":["starbot","limit"],"groups":[
                  {"id":"main","title":"Settings","fields":[{"key":"enabled","type":"boolean"}]}
                ]}
                """;
        String atLimit = meta + " ".repeat(8 * 1024 * 1024 - meta.length());
        plugin("at-limit", atLimit);
        plugin("over-limit", atLimit + " ");

        ConfigMetaRegistry registry = new ConfigMetaRegistry(directory.resolve("plugins"));
        assertThat(registry.getMeta()).anySatisfy(moduleMeta -> assertThat(moduleMeta.getString("id")).isEqualTo("test:at-limit"));
        assertThat(registry.getMeta()).noneMatch(moduleMeta -> "test:over-limit".equals(moduleMeta.getString("id")));
        assertThat(registry.getDiagnostics()).anySatisfy(issue -> assertThat(issue.getString("message")).contains("配置元数据超过 8 MiB"));
    }

    /**
     * 根据文件名区分插件描述与配置元数据的 JSON 错误
     */
    @Test
    void identifiesInvalidPluginJsonWithoutDisplayingItsContents() throws Exception {
        Path plugins = Files.createDirectories(directory.resolve("plugins"));
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(plugins.resolve("bad-descriptor.jar")))) {
            entry(jar, "plugin.json", "{\"secret\":\"DoNotDisplayThisValue\",\"broken\":");
            entry(jar, "config/config.json", "{}");
        }
        plugin("bad-config", "{\"secret\":\"DoNotDisplayThisValue\",\"broken\":");

        ConfigMetaRegistry registry = new ConfigMetaRegistry(plugins);
        assertThat(registry.getDiagnostics()).anySatisfy(issue -> {
            assertThat(issue.getString("source")).isEqualTo("bad-descriptor.jar");
            assertThat(issue.getString("message")).contains("plugin.json 中的 JSON 格式无效").doesNotContain("DoNotDisplayThisValue");
        }).anySatisfy(issue -> {
            assertThat(issue.getString("source")).contains("bad-config.jar");
            assertThat(issue.getString("message")).contains("config/config.json 中的 JSON 格式无效").doesNotContain("DoNotDisplayThisValue");
        });
    }

    /**
     * Core 邮件分组合并业务字段和高级字段，各字段保留独立的实际配置路径
     */
    @Test
    void combinesBusinessAndAdvancedMailFieldsInCoreWithTheirOwnPaths() throws Exception {
        ConfigMetaRegistry registry = new ConfigMetaRegistry(directory.resolve("plugins"));
        JSONObject core = registry.getMeta().stream().filter(moduleMeta -> "core".equals(moduleMeta.getString("id"))).findFirst().orElseThrow();
        JSONObject mail = core.getJSONArray("groups").stream().map(group -> (JSONObject) group)
                .filter(group -> "mail".equals(group.getString("id"))).findFirst().orElseThrow();
        JSONObject enabled = mail.getJSONArray("fields").stream().map(item -> (JSONObject) item)
                .filter(field -> "enabled".equals(field.getString("key"))).findFirst().orElseThrow();
        assertThat(enabled.getJSONArray("configPath")).containsExactly("starbot", "core", "mail", "enabled");
        assertThat(registry.getDeclarations()).anySatisfy(declaration -> {
            assertThat(declaration.getPath()).containsExactly("advanced", "spring.mail.host");
            assertThat(declaration.getTab()).isEqualTo("core");
            assertThat(declaration.getGroup()).isEqualTo("mail");
        });
        assertThat(registry.getAdvancedDefaults())
                .containsEntry("server.port", 7827)
                .containsEntry("spring.mail.protocol", "smtp")
                .containsEntry("spring.mail.default-encoding", "UTF-8")
                .doesNotContainKeys("spring.mail.host", "spring.mail.username", "spring.mail.password");
    }

    /**
     * 高级字段必须声明建议值，建议为 null 时不生成生效属性
     */
    @Test
    void requiresSuggestionAndAcceptsExplicitNull() throws Exception {
        String meta = """
                {"title":"Example","basePath":["starbot","example"],"groups":[{"id":"main","title":"高级配置","target":"advanced",
                  "fields":[{"key":"example.host","type":"string"%s}]}]}
                """;
        plugin("missing", meta.formatted(""));
        plugin("unset", meta.formatted(",\"suggestedValue\":null"));

        ConfigMetaRegistry registry = new ConfigMetaRegistry(directory.resolve("plugins"));
        assertThat(registry.getDiagnostics()).anySatisfy(diagnostic -> assertThat(diagnostic.getString("message"))
                .contains("example.host", "必须声明 suggestedValue"));
        assertThat(registry.getMeta()).anyMatch(module -> "test:unset".equals(module.getString("id")))
                .noneMatch(module -> "test:missing".equals(module.getString("id")));
        assertThat(registry.getAdvancedDefaults()).doesNotContainKey("example.host");
        assertThat(registry.hasUnresolvedConflicts(new JSONObject())).isFalse();
    }

    /**
     * 多个插件可以编辑同一个高级配置，用户明确选值后冲突标记消失
     */
    @Test
    void keepsSharedEditorsAndResolvesSuggestionConflictsUsingExplicitValues() throws Exception {
        for (String name : List.of("first", "second")) {
            plugin(name, """
                    {"title":"%s","basePath":["starbot","%s"],"groups":[{
                      "id":"native","title":"高级设置","target":"advanced","fields":[{
                        "key":"example.enabled","label":"开关","type":"boolean","suggestedValue":%s,
                        "suggestedReason":"%s选择这个建议值"}]}]}
                    """.formatted(name, name, "first".equals(name), name));
        }
        ConfigMetaRegistry registry = new ConfigMetaRegistry(directory.resolve("plugins"));
        assertThat(registry.hasMetaErrors()).isFalse();
        assertThat(registry.getAdvancedDefaults()).doesNotContainKey("example.enabled");
        JSONObject emptyDocument = new JSONObject();
        List<Object> conflictPath = List.of("advanced", "example.enabled");
        assertThat(registry.hasUnresolvedConflicts(emptyDocument)).isTrue();
        assertThat(registry.hasUnresolvedConflicts(emptyDocument, conflictPath::equals)).isTrue();
        assertThat(registry.hasUnresolvedConflicts(emptyDocument, path -> path.equals(List.of("advanced", "server.port")))).isFalse();
        JSONObject nullSelection = JSONObject.of("advanced", JSONObject.of("example.enabled", null));
        assertThat(registry.hasUnresolvedConflicts(nullSelection, conflictPath::equals)).isFalse();
        JSONObject document = JSONObject.of("advanced", JSONObject.of("example.enabled", false));
        assertThat(registry.hasUnresolvedConflicts(document)).isFalse();
        assertThat(registry.getConflicts(document).getJSONObject(0).getBooleanValue("resolved")).isTrue();
        assertThat(registry.getConflicts(document).getJSONObject(0).getJSONArray("definitions")).hasSize(2);
        for (String name : List.of("first", "second")) {
            JSONObject moduleMeta = registry.getMeta().stream().filter(item -> ("test:" + name).equals(item.getString("id"))).findFirst().orElseThrow();
            JSONObject field = moduleMeta.getJSONArray("groups").getJSONObject(0).getJSONArray("fields").getJSONObject(0);
            assertThat(field.getJSONArray("configPath")).containsExactly("advanced", "example.enabled");
            assertThat(field.getBooleanValue("suggestionConflict")).isTrue();
            assertThat(field.getJSONArray("suggestions")).hasSize(2);
        }
    }

    /**
     * 根据元数据顺序展示分组并展开复用条件，字段根据分组或 Spring 属性决定是否需要重启
     */
    @Test
    void expandsNamedConditionsAndInheritsRestartModeWithoutChangingGroupOrder() throws Exception {
        plugin("mixed", """
                {"title":"Mixed","basePath":["starbot","mixed"],"conditions":{
                  "enabled":{"scope":"document","path":["starbot","mixed","enabled"],"op":"=","value":true}},
                 "groups":[{"id":"first","title":"混合设置","restartRequired":true,"fields":[
                  {"key":"enabled","type":"boolean","restartRequired":false},
                  {"key":"text","type":"string","visibleWhen":"enabled","validateWhen":"enabled"},
                  {"key":"example.port","target":"advanced","type":"integer","suggestedValue":null,"restartRequired":false}]},
                  {"id":"second","title":"按钮","actions":[{"id":"test","label":"测试","handler":"example.Test","visibleWhen":"enabled"}]}]}
                """);
        ConfigMetaRegistry registry = new ConfigMetaRegistry(directory.resolve("plugins"));
        JSONObject moduleMeta = registry.getMeta().stream().filter(item -> "test:mixed".equals(item.getString("id"))).findFirst().orElseThrow();
        assertThat(moduleMeta.getJSONArray("groups")).extracting(item -> ((JSONObject) item).getString("id")).containsExactly("first", "second");
        JSONObject group = moduleMeta.getJSONArray("groups").getJSONObject(0);
        assertThat(group.getJSONArray("fields").getJSONObject(0).getBooleanValue("restartRequired")).isFalse();
        assertThat(group.getJSONArray("fields").getJSONObject(1).getBooleanValue("restartRequired")).isTrue();
        assertThat(group.getJSONArray("fields").getJSONObject(1).getJSONObject("visibleWhen").getString("scope")).isEqualTo("document");
        assertThat(group.getJSONArray("fields").getJSONObject(2).getBooleanValue("restartRequired")).isTrue();
        assertThat(group.getJSONArray("fields").getJSONObject(2).getJSONArray("configPath")).containsExactly("advanced", "example.port");
        assertThat(registry.hasMetaErrors()).isFalse();
    }

    /**
     * 未知命名条件不会进入表单
     */
    @Test
    void rejectsUnknownNamedConditions() throws Exception {
        plugin("condition", """
                {"title":"Condition","basePath":["starbot","condition"],"groups":[{"id":"main","title":"设置",
                  "fields":[{"key":"enabled","type":"boolean","visibleWhen":"missing"}]}]}
                """);
        ConfigMetaRegistry registry = new ConfigMetaRegistry(directory.resolve("plugins"));
        assertThat(registry.hasMetaErrors()).isTrue();
        assertThat(registry.getDiagnostics()).anySatisfy(item -> assertThat(item.getString("message")).contains("命名条件不存在"));
    }

    /**
     * 字段类型缺失或包含 null 时报告未知字段类型
     */
    @Test
    void reportsMissingAndNullFieldTypes() throws Exception {
        Map<String, String> types = Map.of("missing", "", "null-type", ",\"type\":null", "null-in-list", ",\"type\":[\"string\",null]");
        for (Map.Entry<String, String> type : types.entrySet()) {
            plugin(type.getKey(), """
                    {"title":"%s","basePath":["starbot","%s"],"groups":[{"id":"main","title":"设置","fields":[
                      {"key":"value"%s}]}]}
                    """.formatted(type.getKey(), type.getKey(), type.getValue()));
        }
        ConfigMetaRegistry registry = new ConfigMetaRegistry(directory.resolve("plugins"));
        assertThat(registry.hasMetaErrors()).isTrue();
        assertThat(registry.getDiagnostics()).hasSize(types.size())
                .allSatisfy(issue -> assertThat(issue.getString("message")).contains("未知字段类型"));
    }

    /**
     * 共用高级配置的相同建议正常合并，重复的业务映射只记录一条诊断并标记所有字段
     */
    @Test
    void sharesSuggestionsAndDetectsDuplicateSpringBindings() throws Exception {
        for (String name : List.of("first", "second", "third")) {
            plugin(name, """
                    {"title":"%s","basePath":["starbot","%s"],"groups":[{"id":"main","title":"设置","fields":[
                      {"key":"enabled","type":"boolean","springKey":"example.enabled"},
                      {"key":"example.port","target":"advanced","type":"integer","suggestedValue":1234}]}]}
                    """.formatted(name, name));
        }
        ConfigMetaRegistry registry = new ConfigMetaRegistry(directory.resolve("plugins"));
        assertThat(registry.getAdvancedDefaults()).containsEntry("example.port", 1234);
        assertThat(registry.getConflicts(new JSONObject())).isEmpty();
        assertThat(registry.getDeclarations().stream().filter(item -> item.getPath().equals(List.of("advanced", "example.port"))))
                .allSatisfy(item -> assertThat(item.getField().get("effectiveDefault")).isEqualTo(1234));
        assertThat(registry.hasMetaErrors()).isTrue();
        assertThat(registry.getDiagnostics()).singleElement().satisfies(issue -> {
            assertThat(issue.getString("springKey")).isEqualTo("example.enabled");
            assertThat(issue.getJSONArray("sources")).containsExactly(
                    JSONObject.of("source", "first", "path", List.of("starbot", "first", "enabled")),
                    JSONObject.of("source", "second", "path", List.of("starbot", "second", "enabled")),
                    JSONObject.of("source", "third", "path", List.of("starbot", "third", "enabled")));
        });
        assertThat(registry.getDeclarations().stream().filter(item -> item.getField().containsKey("springKey")
                && "example.enabled".equals(item.getField().getString("springKey"))))
                .hasSize(3).allSatisfy(item -> {
                    assertThat(item.getField().getBooleanValue("conflicted")).isTrue();
                    assertThat(item.getField().getBooleanValue("springBindingConflict")).isTrue();
                });
        assertThat(registry.getSpringBindings()).doesNotContainKey("example.enabled");
    }

    /**
     * 启动时跳过重复的业务映射，继续使用用户明确填写的高级配置和唯一的业务映射
     */
    @Test
    void excludesConflictingBusinessMappingsFromSpringProperties() throws Exception {
        for (String name : List.of("first", "second")) {
            plugin(name, """
                    {"title":"%s","basePath":["starbot","%s"],"groups":[{"id":"main","title":"设置","fields":[
                      {"key":"enabled","type":"boolean","springKey":"example.enabled"},
                      {"key":"mode","type":"string","springKey":"example.mode"},
                      {"key":"label","type":"string","springKey":"example.%s.label"}]}]}
                    """.formatted(name, name, name));
        }
        ConfigMetaRegistry registry = new ConfigMetaRegistry(directory.resolve("plugins"));
        SpringPropertyBridge bridge = new SpringPropertyBridge(registry);
        Path path = Files.writeString(directory.resolve("config.json"), """
                {"starbot":{"first":{"enabled":true,"mode":"alpha","label":"first"},"second":{"enabled":false,"mode":"beta"}},
                 "advanced":{"example.mode":"manual"}}
                """);
        ConfigService config = new ConfigService(path, registry, bridge, false);
        try {
            assertThat(config.getSpringProperties()).doesNotContainKey("example.enabled")
                    .containsEntry("example.mode", "manual").containsEntry("example.first.label", "first");
            assertThat(config.get("starbot.first.enabled")).isEqualTo(true);
            assertThat(config.get("starbot.second.enabled")).isEqualTo(false);
            assertThat(config.hasUnresolvedConflicts()).isFalse();
        } finally {
            config.destroy();
        }
    }

    /**
     * 声明问题包含重复的分组、涉及的配置路径和各模块的取值要求
     */
    @Test
    void reportsDetailsForMetadataDeclarationConflicts() throws Exception {
        plugin("a", """
                {"title":"A","basePath":["starbot","demo"],"groups":[
                  {"id":"main","title":"常规","fields":[{"key":"switch","type":"boolean"},{"key":"connection","type":"string","label":"连接地址"}]},
                  {"id":"limits","title":"限制","target":"advanced","fields":[
                    {"key":"example.mode","type":"string","suggestedValue":null,"required":true,"options":[{"value":"alpha"}]},
                    {"key":"example.size","type":"integer","suggestedValue":null,"minimum":10}]}]}
                """);
        plugin("b", """
                {"title":"B","basePath":["starbot","demo"],"groups":[
                  {"id":"main","title":"常规","fields":[{"key":"switch","type":"boolean"}]},
                  {"id":"network","title":"网络","path":["connection"],"fields":[{"key":"port","type":"integer","label":"连接端口"}]},
                  {"id":"limits","title":"限制","target":"advanced","fields":[
                    {"key":"example.mode","type":"string","suggestedValue":null,"options":[{"value":"beta"}]},
                    {"key":"example.size","type":"integer","suggestedValue":null,"maximum":5}]}]}
                """);
        plugin("c", """
                {"title":"C","basePath":["starbot","demo"],"groups":[
                  {"id":"main","title":"常规"},{"id":"main","title":"网络"}]}
                """);
        ConfigMetaRegistry registry = new ConfigMetaRegistry(directory.resolve("plugins"));
        List<JSONObject> issues = registry.getDiagnostics();
        assertThat(issues).hasSize(5).anySatisfy(issue -> {
            assertThat(issue.getString("source")).isEqualTo("c (c.jar)");
            assertThat(issue.getString("message")).contains("第 1 组「常规」", "第 2 组「网络」", "分组 ID「main」");
        }).anySatisfy(issue -> {
            assertThat(issue.getString("title")).isEqualTo("业务配置路径重复声明");
            assertThat(issue.getJSONArray("sources")).hasSize(2).allSatisfy(source ->
                    assertThat(((JSONObject) source).getJSONArray("path")).containsExactly("starbot", "demo", "switch"));
        }).anySatisfy(issue -> {
            assertThat(issue.getString("title")).isEqualTo("配置路径的父项与子项冲突");
            JSONArray sources = issue.getJSONArray("sources");
            assertThat(sources.getJSONObject(0).getString("label")).isEqualTo("连接地址");
            assertThat(sources.getJSONObject(1).getString("label")).isEqualTo("连接端口");
        }).anySatisfy(issue -> {
            assertThat(issue.getJSONArray("path")).containsExactly("advanced", "example.mode");
            assertThat(issue.getBooleanValue("blocking")).isTrue();
            assertThat(issue.getJSONArray("sources").getJSONObject(0).getString("detail")).contains("alpha", "必填");
            assertThat(issue.getJSONArray("sources").getJSONObject(1).getString("detail")).contains("beta");
        }).anySatisfy(issue -> {
            assertThat(issue.getJSONArray("path")).containsExactly("advanced", "example.size");
            assertThat(issue.getBooleanValue("blocking")).isFalse();
            assertThat(issue.getJSONArray("sources").getJSONObject(0).getString("detail")).contains("最小值为 10");
            assertThat(issue.getJSONArray("sources").getJSONObject(1).getString("detail")).contains("最大值为 5");
        });
        assertThat(registry.getDeclarations().stream().filter(item -> item.getPath().contains("connection"))).hasSize(2)
                .allSatisfy(item -> assertThat(item.getField().getString("declarationIssue")).isEqualTo("配置路径的父项与子项冲突"));
    }

    /**
     * 相同建议自动合并，未填写原因时保留模块标识和友好提示
     */
    @Test
    void sharesMatchingSuggestionsAndKeepsModuleIdentities() throws Exception {
        for (String name : List.of("first", "second")) {
            plugin(name, """
                    {"title":"%s","basePath":["starbot","%s"],"groups":[{"id":"main","title":"设置","target":"advanced",
                     "fields":[{"key":"example.port","type":"integer","suggestedValue":9000}]}]}
                    """.formatted(name, name));
        }
        ConfigMetaRegistry registry = new ConfigMetaRegistry(directory.resolve("plugins"));
        assertThat(registry.hasMetaErrors()).isFalse();
        assertThat(registry.getAdvancedDefaults()).containsEntry("example.port", 9000);
        JSONObject field = registry.getDeclarations().stream().filter(item -> item.getPath().equals(List.of("advanced", "example.port"))).findFirst().orElseThrow().getField();
        assertThat(field.getJSONArray("suggestions")).extracting(item -> ((JSONObject) item).getString("module")).containsExactly("test:first", "test:second");
        assertThat(field.getJSONArray("suggestions").getJSONObject(0).getString("reason")).isEqualTo("插件未说明原因");
        assertThat(registry.hasUnresolvedConflicts(new JSONObject())).isFalse();
    }

    /**
     * 插件追加的下拉选项去重后同时参与表单显示和保存校验
     */
    @Test
    void mergesPluginDropdownOptionsIntoMetaAndValidation() throws Exception {
        Path plugins = Files.createDirectories(directory.resolve("plugins"));
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(plugins.resolve("mysql.jar")))) {
            entry(jar, "plugin.json", "{\"groupId\":\"test\",\"artifactId\":\"mysql\",\"name\":\"MySQL\"}");
            entry(jar, "config/config.json", """
                    {"title":"MySQL","basePath":["starbot","mysql"],"groups":[],
                     "optionContributions":[{"path":["starbot","core","datasource","type"],
                     "options":[{"label":"MySQL","value":"mysql"},{"label":"重复","value":"mysql"}]}]}
                    """);
        }
        ConfigMetaRegistry registry = new ConfigMetaRegistry(plugins);
        SpringPropertyBridge springPropertyBridge = new SpringPropertyBridge(registry);
        ConfigService config = new ConfigService(directory.resolve("config.json"), registry, springPropertyBridge, false);
        try {
            ConfigEditService editService = new ConfigEditService(config, springPropertyBridge);
            editService.save(JSONObject.of("raw", "{\"starbot\":{\"core\":{\"datasource\":{\"type\":\"mysql\"}}}}"));
            ConfigMetaRegistry.Declaration declaration = registry.getDeclarations().stream()
                    .filter(item -> item.getPath().equals(List.of("starbot", "core", "datasource", "type"))).findFirst().orElseThrow();
            assertThat(declaration.getField().getJSONArray("options")).hasSize(2);
            assertThat(config.get("starbot.core.datasource.type")).isEqualTo("json");
        } finally {
            config.destroy();
        }
    }

    /**
     * 动作执行方式必须使用布尔值，非法元数据不进入表单
     */
    @Test
    void rejectsInvalidActionExecutionMode() throws Exception {
        Path plugins = Files.createDirectories(directory.resolve("plugins"));
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(plugins.resolve("actions.jar")))) {
            entry(jar, "plugin.json", "{\"groupId\":\"test\",\"artifactId\":\"actions\",\"name\":\"Actions\"}");
            entry(jar, "config/config.json", """
                    {"title":"Actions","basePath":["starbot","actions"],
                     "groups":[{"id":"main","title":"设置","path":[],"fields":[],
                       "actions":[{"id":"run","label":"执行","handler":"example.Action","synchronous":"false"}]}]}
                    """);
        }
        ConfigMetaRegistry registry = new ConfigMetaRegistry(plugins);
        assertThat(registry.getMeta()).noneMatch(moduleMeta -> "test:actions".equals(moduleMeta.getString("id")));
        assertThat(registry.getDiagnostics()).anySatisfy(issue -> assertThat(issue.getString("message")).contains("synchronous 必须是布尔值"));
    }

    /**
     * 校验动作参数的配置来源和二次确认提示
     */
    @Test
    void rejectsInvalidActionParametersAndConfirmation() throws Exception {
        Path plugins = Files.createDirectories(directory.resolve("plugins"));
        Map<String, String> invalidActions = Map.of(
                "parameters", "\"parameters\":{\"all\":{\"path\":[]}}",
                "confirmation", "\"confirmation\":{}",
                "both-sources", "\"parameters\":{\"smtp\":{\"path\":[\"advanced\"],\"springProperties\":true}}",
                "false-spring-properties", "\"parameters\":{\"smtp\":{\"springProperties\":false}}",
                "missing-source", "\"parameters\":{\"smtp\":{\"keyPrefix\":\"spring.mail.\"}}",
                "path-with-keys", "\"parameters\":{\"smtp\":{\"path\":[\"advanced\"],\"keys\":[\"spring.mail.host\"]}}",
                "prefix-with-keys", "\"parameters\":{\"smtp\":{\"springProperties\":true,\"keyPrefix\":\"spring.mail.\",\"keys\":[\"spring.mail.host\"]}}",
                "empty-keys", "\"parameters\":{\"smtp\":{\"springProperties\":true,\"keys\":[]}}",
                "duplicate-keys", "\"parameters\":{\"smtp\":{\"springProperties\":true,\"keys\":[\"spring.mail.host\",\"spring.mail.host\"]}}");
        for (Map.Entry<String, String> invalid : invalidActions.entrySet()) {
            String name = invalid.getKey();
            try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(plugins.resolve(name + ".jar")))) {
                entry(jar, "plugin.json", "{\"groupId\":\"test\",\"artifactId\":\"" + name + "\",\"name\":\"Actions\"}");
                entry(jar, "config/config.json", """
                        {"title":"Actions","basePath":["starbot","%s"],
                         "groups":[{"id":"main","title":"设置","path":[],"fields":[],
                           "actions":[{"id":"run","label":"执行","handler":"example.Action",%s}]}]}
                    """.formatted(name, invalid.getValue()));
            }
        }
        ConfigMetaRegistry registry = new ConfigMetaRegistry(plugins);
        assertThat(registry.getMeta()).noneMatch(moduleMeta -> invalidActions.keySet().stream().anyMatch(name -> ("test:" + name).equals(moduleMeta.getString("id"))));
        assertThat(registry.getDiagnostics()).anySatisfy(issue -> assertThat(issue.getString("message")).contains("不能选择整个配置文档"));
        assertThat(registry.getDiagnostics()).anySatisfy(issue -> assertThat(issue.getString("message")).contains("confirmation 必须是非空文本"));
        assertThat(registry.getDiagnostics()).anySatisfy(issue -> assertThat(issue.getString("message")).contains("springProperties 与 path 只能声明一个"));
        assertThat(registry.getDiagnostics()).anySatisfy(issue -> assertThat(issue.getString("message")).contains("springProperties 必须为 true"));
        assertThat(registry.getDiagnostics()).anySatisfy(issue -> assertThat(issue.getString("message")).contains("动作参数必须声明 path 或 springProperties"));
        assertThat(registry.getDiagnostics()).anySatisfy(issue -> assertThat(issue.getString("message")).contains("keys 只支持 Spring 属性参数"));
        assertThat(registry.getDiagnostics()).anySatisfy(issue -> assertThat(issue.getString("message")).contains("keys 与 keyPrefix 只能声明一个"));
        assertThat(registry.getDiagnostics().stream().filter(issue -> issue.getString("message").contains("keys 必须是非空且无重复的配置键名数组")))
                .hasSize(2);
    }

    /**
     * 类型不一致时保留各来源及其声明类型，未选择配置值时进入配置中心
     */
    @Test
    void requiresExplicitSelectionForTypeConflicts() throws Exception {
        plugin("integer", """
                {"title":"Integer","basePath":["starbot","integer"],"groups":[{"id":"main","title":"设置","target":"advanced",
                 "fields":[{"key":"example.mixed","type":"integer","suggestedValue":7}]}]}
                """);
        plugin("text", """
                {"title":"Text","basePath":["starbot","text"],"groups":[{"id":"main","title":"设置","target":"advanced",
                 "fields":[{"key":"example.mixed","type":"string","suggestedValue":"seven"}]}]}
                """);
        ConfigMetaRegistry registry = new ConfigMetaRegistry(directory.resolve("plugins"));
        assertThat(registry.hasMetaErrors()).isFalse();
        assertThat(registry.hasUnresolvedConflicts(new JSONObject())).isTrue();
        assertThat(registry.getAdvancedDefaults()).doesNotContainKey("example.mixed");
        JSONObject conflict = registry.getConflicts(new JSONObject()).getJSONObject(0);
        assertThat(conflict.getBooleanValue("typeConflict")).isTrue();
        assertThat(conflict.getJSONObject("field").getString("type")).isEqualTo("any");
        assertThat(conflict.getJSONArray("participants")).extracting(item -> ((JSONObject) item).getString("module"))
                .containsExactly("test:integer", "test:text");
        assertThat(conflict.getJSONArray("definitions")).extracting(item -> ((JSONObject) item).get("value")).containsExactly(7, "seven");
        assertThat(registry.hasSelectionConflict(List.of("advanced", "example.mixed", "child"))).isTrue();
        assertThat(registry.getConflicts(JSONObject.of("advanced", JSONObject.of("example.mixed", false))).getJSONObject(0).getBooleanValue("resolved")).isTrue();
        for (Object value : new Object[]{7, "seven", false, "", null, new JSONObject(), new JSONArray()}) {
            assertThat(registry.hasUnresolvedConflicts(JSONObject.of("advanced", JSONObject.of("example.mixed", value)))).isFalse();
        }
    }

    /**
     * 类型有冲突且建议均为不设置时，仍显示来源并允许用户填写
     */
    @Test
    void exposesTypeConflictsWithNullSuggestionsForCustomInput() throws Exception {
        for (String type : List.of("integer", "string")) {
            plugin(type, """
                    {"title":"%s","basePath":["starbot","%s"],"groups":[{"id":"main","title":"设置","target":"advanced",
                     "fields":[{"key":"example.mixed","type":"%s","suggestedValue":null}]}]}
                    """.formatted(type, type, type));
        }
        ConfigMetaRegistry registry = new ConfigMetaRegistry(directory.resolve("plugins"));
        JSONObject conflict = registry.getConflicts(new JSONObject()).getJSONObject(0);
        assertThat(registry.hasUnresolvedConflicts(new JSONObject())).isTrue();
        assertThat(conflict.getJSONArray("definitions").toJavaList(JSONObject.class)).hasSize(2)
                .allSatisfy(definition -> assertThat(definition).containsEntry("value", null));
        assertThat(conflict.getJSONArray("participants")).hasSize(2);
        assertThat(conflict.getJSONObject("field").getString("type")).isEqualTo("any");
        assertThat(registry.hasUnresolvedConflicts(JSONObject.of("advanced", JSONObject.of("example.mixed", "custom")))).isFalse();
    }

    /**
     * 父对象的类型有冲突时只让用户选择整个对象，不再单独列出子项冲突
     */
    @Test
    void resolvesNestedTypeConflictsThroughParentSelection() throws Exception {
        for (String type : List.of("integer", "string")) {
            String value = "integer".equals(type) ? "7" : "\"seven\"";
            plugin(type, """
                    {"title":"%s","basePath":["starbot","%s"],"groups":[{"id":"main","title":"设置","target":"advanced",
                     "fields":[{"key":"example.object","type":"object","suggestedValue":{"value":%s},
                        "fields":[{"key":"value","type":"%s","suggestedValue":%s}]},
                       {"key":"example.enabled","type":"boolean","suggestedValue":%s}]}]}
                    """.formatted(type, type, value, type, value, "integer".equals(type)));
        }
        ConfigMetaRegistry registry = new ConfigMetaRegistry(directory.resolve("plugins"));
        JSONArray conflicts = registry.getConflicts(new JSONObject());
        assertThat(conflicts).hasSize(2);
        assertThat(conflicts).allSatisfy(item -> assertThat(((JSONObject) item).getJSONArray("path")).hasSize(2));
        assertThat(registry.getAdvancedDefaults()).doesNotContainKeys("example.object", "example.enabled");
        for (Object value : new Object[]{"custom", null, new JSONObject(), JSONObject.of("value", 7)}) {
            JSONObject document = JSONObject.of("advanced", JSONObject.of("example.object", value));
            assertThat(registry.hasUnresolvedConflicts(document)).isTrue();
            document.getJSONObject("advanced").put("example.enabled", false);
            assertThat(registry.hasUnresolvedConflicts(document)).isFalse();
            assertThat(registry.getConflicts(document)).allSatisfy(item ->
                    assertThat(((JSONObject) item).getBooleanValue("resolved")).isTrue());
        }
    }

    /**
     * 创建带配置元数据的测试插件
     * @param name 插件标识
     * @param meta 配置元数据
     */
    private void plugin(String name, String meta) throws Exception {
        Path plugins = Files.createDirectories(directory.resolve("plugins"));
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(plugins.resolve(name + ".jar")))) {
            entry(jar, "plugin.json", "{\"groupId\":\"test\",\"artifactId\":\"" + name + "\",\"name\":\"" + name + "\"}");
            entry(jar, "config/config.json", meta);
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
