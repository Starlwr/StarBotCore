package com.starlwr.bot.core.config;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.config.service.ConfigMetaRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.core.ResolvableType;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring 属性桥接器测试
 */
class SpringPropertyBridgeTest {
    /**
     * 测试插件的临时目录
     */
    @TempDir
    Path directory;

    /**
     * 测试服务器配置
     */
    public record Server(Host a, String hostName) {
    }

    /**
     * 测试主机配置
     */
    public record Host(String b) {
    }

    /**
     * 验证数组中的对象、Map 和嵌套 Map 在有无配置元数据时均可绑定
     * @param withMeta 是否加载测试配置元数据
     */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void bindsArraysOfObjectsAndMaps(boolean withMeta) throws Exception {
        // 根据测试参数决定是否加载数组元素的类型声明
        SpringPropertyBridge bridge = withMeta ? bridge("""
                {"key":"example.objects","type":"array","suggestedValue":null,"items":{"type":"object","fields":[
                  {"key":"host-name","type":"string"},
                  {"key":"a","type":"object","fields":[{"key":"b","type":"string"}]}]}},
                {"key":"example.maps","type":"array","suggestedValue":null,"items":{"type":"map","values":{"type":"string"}}},
                {"key":"example.map-objects","type":"array","suggestedValue":null,"items":{"type":"map","values":{
                  "type":"object","fields":[{"key":"b","type":"string"}]}}},
                {"key":"example.nested-maps","type":"array","suggestedValue":null,"items":{"type":"map","values":{
                  "type":"map","values":{"type":"string"}}}}
                """) : new SpringPropertyBridge(new ConfigMetaRegistry(directory.resolve("plugins")));

        // 准备包含嵌套对象、普通键和带点号键的高级配置
        JSONObject advanced = JSON.parseObject("""
                {
                  "example.objects":[{"host-name":"primary","a":{"b":"localhost"}}],
                  "example.maps":[{"plain":"first","a.b":"second"}],
                  "example.map-objects":[{"plain":{"b":"first"},"a.b":{"b":"second"}}],
                  "example.nested-maps":[{"a":{"b":"first"},"a.b":{"c":"second","d.e":"third"}}]
                }
                """);

        // 检查展开后的 Spring 属性名和属性值
        Map<String, Object> properties = bridge.properties(advanced, advanced);
        assertThat(properties).containsEntry("example.objects[0].host-name", "primary")
                .containsEntry("example.objects[0].a.b", "localhost")
                .containsEntry(withMeta ? "example.maps[0][plain]" : "example.maps[0].plain", "first")
                .containsEntry("example.maps[0][a.b]", "second")
                .containsEntry(withMeta ? "example.map-objects[0][plain].b" : "example.map-objects[0].plain.b", "first")
                .containsEntry("example.map-objects[0][a.b].b", "second")
                .containsEntry(withMeta ? "example.nested-maps[0][a][b]" : "example.nested-maps[0].a.b", "first")
                .containsEntry(withMeta ? "example.nested-maps[0][a.b][c]" : "example.nested-maps[0][a.b].c", "second")
                .containsEntry("example.nested-maps[0][a.b][d.e]", "third");

        // 将展开后的属性绑定到 Java 类型，检查对象字段和 Map 键是否正确
        Binder binder = new Binder(new MapConfigurationPropertySource(properties));
        assertThat(binder.bind("example.objects", Server[].class).get())
                .containsExactly(new Server(new Host("localhost"), "primary"));
        ResolvableType string = ResolvableType.forClass(String.class);
        ResolvableType strings = ResolvableType.forClassWithGenerics(Map.class, string, string);
        assertThat(bindList(binder, "example.maps", strings)).isEqualTo(List.of(Map.of("plain", "first", "a.b", "second")));
        assertThat(bindList(binder, "example.map-objects", ResolvableType.forClassWithGenerics(Map.class, String.class, Host.class)))
                .isEqualTo(List.of(Map.of("plain", new Host("first"), "a.b", new Host("second"))));
        assertThat(bindList(binder, "example.nested-maps", ResolvableType.forClassWithGenerics(Map.class, string, strings)))
                .isEqualTo(List.of(Map.of("a", Map.of("b", "first"), "a.b", Map.of("c", "second", "d.e", "third"))));
    }

    /**
     * 验证不同写法指向同一属性时能报告两处位置，并区分带点号的 Map 键和嵌套层级
     */
    @Test
    void findsAliasesWithoutCollapsingLiteralDots() throws Exception {
        SpringPropertyBridge bridge = bridge("""
                {"key":"example.levels","type":"map","suggestedValue":null,"values":{"type":"string"}},
                {"key":"example.nested","type":"array","suggestedValue":null,"items":{"type":"map","values":{"type":"map","values":{"type":"string"}}}}
                """);
        JSONObject advanced = JSON.parseObject("""
                {
                  "example.levels":{"a.b":"DEBUG"},
                  "example.levels.a.b":"INFO",
                  "example.nested":[{"a":{"b":"first"},"a.b":{"c":"second"}}],
                  "example.nested[0].a.b":"third"
                }
                """);
        assertThat(bridge.duplicates(advanced)).containsExactly(
                new SpringPropertyBridge.Duplicate("example.levels[a.b]", List.of("advanced", "example.levels", "a.b"),
                        List.of("advanced", "example.levels.a.b")),
                new SpringPropertyBridge.Duplicate("example.nested[0][a][b]", List.of("advanced", "example.nested", 0, "a", "b"),
                        List.of("advanced", "example.nested[0].a.b")));
    }

    /**
     * 验证用户填写的完整键覆盖同一 Map 条目的建议值，保留其他条目及用户填写的键名
     */
    @Test
    void explicitAliasesOverrideSuggestedValues() throws Exception {
        SpringPropertyBridge bridge = bridge("""
                {"key":"example.levels","type":"map","suggestedValue":null,"values":{"type":"string"}}
                """);
        JSONObject effective = JSONObject.of("example.levels", JSONObject.of("a.b", "DEBUG", "other", "TRACE"), "example.levels.a.b", "INFO");
        JSONObject explicit = JSONObject.of("example.levels.a.b", "INFO");
        assertThat(bridge.properties(effective, explicit)).containsExactlyInAnyOrderEntriesOf(
                Map.of("example.levels.a.b", "INFO", "example.levels[other]", "TRACE"));
    }

    /**
     * 根据指定的元素类型将 Spring 属性绑定为列表
     * @param binder Spring 属性绑定器
     * @param key 列表对应的 Spring 属性键
     * @param elementType 列表元素类型
     * @return 绑定后的列表
     */
    private static List<?> bindList(Binder binder, String key, ResolvableType elementType) {
        return (List<?>) binder.bind(key, Bindable.of(ResolvableType.forClassWithGenerics(List.class, elementType))).get();
    }

    /**
     * 根据高级配置字段声明生成测试插件并创建 Spring 属性桥接器
     * @param fields 高级配置字段声明的 JSON 片段
     * @return 已加载测试配置元数据的 Spring 属性桥接器
     */
    private SpringPropertyBridge bridge(String fields) throws Exception {
        // 将插件信息和配置元数据写入测试插件 JAR
        Path plugins = Files.createDirectories(directory.resolve("plugins"));
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(plugins.resolve("spring-properties.jar")))) {
            entry(jar, "plugin.json", """
                    {"groupId":"test","artifactId":"spring-properties","name":"Spring 属性测试"}
                    """);
            entry(jar, "config/config.json", """
                    {"title":"Spring 属性测试","basePath":["starbot","spring-properties"],"groups":[
                      {"id":"spring","title":"Spring 属性","target":"advanced","fields":[%s]}]}
                    """.formatted(fields));
        }

        // 确认配置元数据加载成功，再创建桥接器
        ConfigMetaRegistry registry = new ConfigMetaRegistry(plugins);
        assertThat(registry.hasMetaErrors()).isFalse();
        return new SpringPropertyBridge(registry);
    }

    /**
     * 将文本写入测试插件 JAR 中的指定条目
     * @param jar 测试插件 JAR 输出流
     * @param path 条目在 JAR 中的路径
     * @param content 条目的文本内容
     */
    private static void entry(JarOutputStream jar, String path, String content) throws Exception {
        jar.putNextEntry(new JarEntry(path));
        jar.write(content.getBytes(StandardCharsets.UTF_8));
        jar.closeEntry();
    }
}
