package com.starlwr.bot.core.config.validation;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.config.SpringPropertyBridge;
import com.starlwr.bot.core.config.service.ConfigMetaRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 配置校验服务测试
 */
class ConfigValidationServiceTest {
    @TempDir
    Path directory;

    /**
     * 未填写时使用一致的建议值，填写后根据各插件的规则校验
     */
    @Test
    void validatesSharedConstraintsUsingEffectiveSuggestionsAndKeepsSourceLocations() throws Exception {
        for (String name : List.of("first", "second")) {
            plugin(name, """
                    {"title":"%s","basePath":["starbot","%s"],"groups":[{"id":"main","title":"设置","target":"advanced","fields":[{
                     "key":"example.capacity","label":"容量","type":"integer","default":8,"suggestedValue":12,%s}]}]}
                    """.formatted(name, name, "first".equals(name) ? "\"minimum\":5" : "\"maximum\":10"));
        }
        ConfigMetaRegistry registry = new ConfigMetaRegistry(directory);
        ConfigValidationService validation = new ConfigValidationService(registry, new SpringPropertyBridge(registry));
        assertThat(registry.getAdvancedDefaults()).containsEntry("example.capacity", 12);
        assertThat(validation.validate(new JSONObject()).getJSONArray("errors")).hasSize(1);
        assertThat(validation.validate(JSONObject.of("advanced", JSONObject.of("example.capacity", 7))).getJSONArray("errors")).isEmpty();
        JSONObject issue = validation.validate(JSONObject.of("advanced", JSONObject.of("example.capacity", 12)))
                .getJSONArray("errors").getJSONObject(0);
        assertThat(issue.getString("tab")).isEqualTo("test:second");
        assertThat(issue.getJSONArray("path")).containsExactly("advanced", "example.capacity");
    }

    /**
     * 没有明确选择的建议冲突不阻止其他字段校验与逐项保存
     */
    @Test
    void defersUnselectedSuggestionConflictsAndValidatesExplicitChoices() throws Exception {
        for (String name : List.of("first", "second")) {
            plugin(name, """
                    {"title":"%s","basePath":["starbot","%s"],"groups":[{"id":"main","title":"设置","target":"advanced","fields":[{
                     "key":"example.capacity","label":"容量","type":"integer","default":8,"suggestedValue":%s,"minimum":1,"required":true}]}]}
                    """.formatted(name, name, "first".equals(name) ? 5 : 10));
        }
        ConfigMetaRegistry registry = new ConfigMetaRegistry(directory);
        ConfigValidationService validation = new ConfigValidationService(registry, new SpringPropertyBridge(registry));
        assertThat(validation.validate(new JSONObject()).getJSONArray("errors")).isEmpty();
        assertThat(validation.validate(JSONObject.of("advanced", JSONObject.of("example.capacity", 0))).getJSONArray("errors")).hasSize(2);
    }

    /**
     * 跨字段校验能读取一致的建议值，但不会将它写回配置文档
     */
    @Test
    void usesSuggestionsInDocumentConditionsWithoutMutatingTheDraft() throws Exception {
        plugin("conditions", """
                {"title":"Conditions","basePath":["starbot","conditions"],"groups":[{"id":"main","title":"设置","target":"advanced","fields":[
                 {"key":"example.enabled","type":"boolean","default":false,"suggestedValue":true},
                 {"key":"example.name","label":"名称","type":"string","suggestedValue":null,"requiredWhen":{
                   "scope":"document","path":["advanced","example.enabled"],"op":"=","value":true}}]}]}
                """);
        JSONObject draft = new JSONObject();
        ConfigMetaRegistry registry = new ConfigMetaRegistry(directory);
        ConfigValidationService validation = new ConfigValidationService(registry, new SpringPropertyBridge(registry));
        assertThat(validation.validate(draft).getJSONArray("errors")).hasSize(1);
        assertThat(draft).isEmpty();
        assertThat(validation.validate(JSONObject.of("advanced", JSONObject.of("example.enabled", false))).getJSONArray("errors")).isEmpty();
    }

    /**
     * 单条规则的条件不影响同字段的其他校验规则
     */
    @Test
    void appliesNamedConditionOnlyToItsOwnRule() throws Exception {
        plugin("proxy", """
                {"title":"Proxy","basePath":["starbot","proxy"],
                 "conditions":{"proxy-on":{"path":["enabled"],"op":"=","value":true}},
                 "groups":[{"id":"main","title":"设置","fields":[
                  {"key":"enabled","type":"boolean"},
                  {"key":"port","type":"integer","validators":[
                    {"rule":"port","message":"端口超出范围"},
                    {"rule":"minimum","value":1024,"when":"proxy-on","message":"代理端口过小"}
                  ]}
                 ]}]}
                """);
        ConfigMetaRegistry registry = new ConfigMetaRegistry(directory);
        ConfigValidationService validation = new ConfigValidationService(registry, new SpringPropertyBridge(registry));
        JSONObject proxy = JSONObject.of("enabled", false, "port", 443);
        JSONObject document = JSONObject.of("starbot", JSONObject.of("proxy", proxy));

        assertThat(validation.validate(document).getJSONArray("errors")).isEmpty();
        proxy.put("port", 70000);
        assertThat(validation.validate(document).getJSONArray("errors").toJavaList(JSONObject.class))
                .extracting(issue -> issue.getString("message")).containsExactly("端口超出范围");
        proxy.put("enabled", true);
        proxy.put("port", 443);
        assertThat(validation.validate(document).getJSONArray("errors").toJavaList(JSONObject.class))
                .extracting(issue -> issue.getString("message")).containsExactly("代理端口过小");
    }

    /**
     * 根据 Map 键的元数据校验键名
     */
    @Test
    void validatesMapKeysWithKeyMeta() throws Exception {
        ConfigMetaRegistry registry = new ConfigMetaRegistry(directory);
        ConfigValidationService validation = new ConfigValidationService(registry, new SpringPropertyBridge(registry));
        JSONObject owners = JSONObject.of(" ", List.of("123"));
        JSONObject document = JSONObject.of("starbot", JSONObject.of("core", JSONObject.of("command", JSONObject.of("owners", owners))));

        assertThat(validation.validate(document).getJSONArray("errors").toJavaList(JSONObject.class))
                .singleElement().satisfies(issue -> assertThat(issue.getJSONArray("path"))
                        .containsExactly("starbot", "core", "command", "owners", " "));

        owners.clear();
        owners.put("onebot", List.of("123"));
        assertThat(validation.validate(document).getJSONArray("errors")).isEmpty();
    }

    /**
     * 校验动态选项的当前键名和值，保留可空字段和未启用约束的字段
     */
    @Test
    void validatesEnforcedDynamicOptionsAgainstCurrentDraft() throws Exception {
        plugin("choices", """
                {"title":"Choices","basePath":["starbot","choices"],"groups":[{"id":"main","title":"设置","fields":[
                  {"key":"connection","label":"当前连接","type":"string","nullable":true,
                   "optionsFrom":{"path":["connections"],"source":"keys","enforce":true}},
                  {"key":"loose","type":"string","optionsFrom":{"path":["connections"],"source":"keys"}},
                  {"key":"status","type":"any","optionsFrom":{"scope":"document","path":["starbot","choices","catalog","0"],
                   "source":"values","enforce":true}},
                  {"key":"index","type":"string","optionsFrom":{"path":["catalog"],"source":"keys","enforce":true}},
                  {"key":"fixed","type":"string","options":[{"label":"固定","value":"fixed"}],
                   "optionsFrom":{"path":["connections"],"source":"keys","enforce":true}}
                ]}]}
                """);
        ConfigMetaRegistry registry = new ConfigMetaRegistry(directory);
        ConfigValidationService validation = new ConfigValidationService(registry, new SpringPropertyBridge(registry));
        JSONObject choices = JSONObject.of("connection", "local", "connections", JSONObject.of("local121", "localhost"),
                "loose", "local", "status", 0, "catalog", List.of(JSONObject.of("zero", 0, "disabled", false)),
                "index", "0", "fixed", "fixed");
        JSONObject document = JSONObject.of("starbot", JSONObject.of("choices", choices));

        assertThat(validation.validate(document).getJSONArray("errors")).singleElement().satisfies(issue -> {
            assertThat(((JSONObject) issue).getJSONArray("path")).containsExactly("starbot", "choices", "connection");
            assertThat(((JSONObject) issue).getString("message")).contains("当前选项已不可用");
        });
        choices.put("connection", "local121");
        assertThat(validation.validate(document).getJSONArray("errors")).isEmpty();

        choices.put("connection", null);
        choices.put("status", "0");
        choices.put("index", "00");
        assertThat(validation.validate(document).getJSONArray("errors")).hasSize(2);
        choices.put("status", false);
        choices.put("index", "0");
        assertThat(validation.validate(document).getJSONArray("errors")).isEmpty();

        choices.put("connection", "from-placeholder");
        choices.put("connections", "${CONNECTIONS}");
        assertThat(validation.validate(document).getJSONArray("errors")).singleElement().satisfies(issue ->
                assertThat(((JSONObject) issue).getJSONArray("path")).containsExactly("starbot", "choices", "connection"));
    }

    /**
     * 仅对 Spring 配置及其子项暂缓校验，纯业务字段和不完整的占位符照常校验
     */
    @Test
    void validatesPlaceholdersAccordingToSpringBindings() throws Exception {
        plugin("references", """
                {"title":"References","basePath":["starbot","references"],"groups":[{"id":"main","title":"设置","fields":[
                  {"key":"port","type":"integer","springKey":"example.port","minimum":1},
                  {"key":"local-port","type":"integer","restartRequired":true},
                  {"key":"local-email","type":"string","format":"email"},
                  {"key":"mapped","type":"map","springKey":"example.mapped","values":{"type":"integer"}},
                  {"key":"options","type":"map","springKey":"example.options","values":{"type":"string"}},
                  {"key":"selected","type":"string","optionsFrom":{"path":["options"],"source":"keys","enforce":true}}
                ]}]}
                """);
        ConfigMetaRegistry registry = new ConfigMetaRegistry(directory);
        ConfigValidationService validation = new ConfigValidationService(registry, new SpringPropertyBridge(registry));
        JSONObject business = JSONObject.of("port", "${PORT:7827}", "local-port", "${PORT}", "local-email", "${EMAIL}",
                "mapped", JSONObject.of("nested", "${NESTED:1}"), "options", "${OPTIONS}", "selected", "any");
        JSONObject document = JSONObject.of("starbot", JSONObject.of("references", business),
                "advanced", JSONObject.of("server.port", "${SERVER_PORT:7827}"));

        JSONObject result = validation.validate(document);
        assertThat(result.getJSONArray("errors").toJavaList(JSONObject.class)).extracting(issue -> issue.getJSONArray("path").toJavaList(String.class))
                .containsExactlyInAnyOrder(List.of("starbot", "references", "local-port"), List.of("starbot", "references", "local-email"));
        assertThat(result.getJSONArray("warnings").toJavaList(JSONObject.class)).extracting(issue -> issue.getJSONArray("path").toJavaList(String.class))
                .containsExactlyInAnyOrder(List.of("advanced", "server.port"), List.of("starbot", "references", "port"),
                        List.of("starbot", "references", "mapped", "nested"), List.of("starbot", "references", "options"));

        business.put("port", "${PORT");
        business.put("options", "${OPTIONS");
        document.getJSONObject("advanced").put("server.port", "${SERVER_PORT");
        result = validation.validate(document);
        assertThat(result.getJSONArray("errors").toJavaList(JSONObject.class)).extracting(issue -> issue.getJSONArray("path").toJavaList(String.class))
                .contains(List.of("advanced", "server.port"), List.of("starbot", "references", "port"),
                        List.of("starbot", "references", "options"), List.of("starbot", "references", "selected"));
    }

    /**
     * 根据共享用例检查联合类型中的 null 和 Map 键的格式及候选值
     */
    @Test
    void validatesNullableUnionAndMapKeyRules() throws Exception {
        JSONObject fixture;
        try (var input = getClass().getResourceAsStream("/config-center/rules-cases.json")) {
            assertThat(input).isNotNull();
            fixture = JSON.parseObject(input.readAllBytes()).getJSONObject("fieldParity");
        }
        plugin("field-parity", fixture.getJSONObject("meta").toJSONString());
        ConfigMetaRegistry registry = new ConfigMetaRegistry(directory);
        ConfigValidationService validation = new ConfigValidationService(registry, new SpringPropertyBridge(registry));
        JSONObject document = JSONObject.of("starbot", JSONObject.of("field-parity", fixture.getJSONObject("values")));
        assertThat(validation.validate(document).getJSONArray("errors").toJavaList(JSONObject.class))
                .extracting(issue -> issue.getJSONArray("path")).containsExactlyElementsOf(
                        fixture.getJSONArray("errorPaths").toJavaList(JSONArray.class));
    }

    /**
     * 创建包含元数据的测试插件
     * @param name 插件标识
     * @param meta 配置元数据
     */
    private void plugin(String name, String meta) throws Exception {
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(directory.resolve(name + ".jar")))) {
            for (String resource : List.of("plugin.json", "config/config.json")) {
                jar.putNextEntry(new JarEntry(resource));
                String content = "plugin.json".equals(resource)
                        ? "{\"groupId\":\"test\",\"artifactId\":\"" + name + "\",\"name\":\"" + name + "\"}" : meta;
                jar.write(content.getBytes(StandardCharsets.UTF_8));
                jar.closeEntry();
            }
        }
    }
}
