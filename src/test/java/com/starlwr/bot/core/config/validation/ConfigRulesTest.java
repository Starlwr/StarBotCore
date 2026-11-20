package com.starlwr.bot.core.config.validation;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static com.starlwr.bot.core.config.validation.ConfigValueUtil.UNSET;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 配置条件与校验规则测试
 */
class ConfigRulesTest {
    /**
     * 读取前后端共用的配置校验用例
     * @return 校验用例及上下文
     */
    private static JSONObject cases() throws IOException {
        try (var input = ConfigRulesTest.class.getResourceAsStream("/config-center/rules-cases.json")) {
            assertNotNull(input);
            return JSON.parseObject(input.readAllBytes());
        }
    }

    /**
     * 根据共享用例逐项验证配置校验规则
     * @return 规则测试用例
     */
    @TestFactory
    Stream<DynamicTest> sharedRuleCases() throws IOException {
        var cases = cases();
        var context = cases.getJSONObject("context");
        var ctx = new ConfigRules.Context(context.get("parent"), context.get("current"), context.get("document"),
                Map.of(), List.of(), List.of(), List.of());
        return cases.getJSONArray("rules").stream().map(item -> (JSONObject) item).map(item -> DynamicTest.dynamicTest(item.getString("name"),
                () -> assertEquals(item.getBooleanValue("expected"), ConfigRules.check(item.getOrDefault("value", UNSET), item.getJSONObject("rule"), ctx))));
    }

    /**
     * 根据共享用例逐项验证严格类型比较符
     * @return 比较符测试用例
     */
    @TestFactory
    Stream<DynamicTest> sharedOperatorCases() throws IOException {
        return cases().getJSONArray("operators").stream().map(item -> (JSONObject) item).map(item -> DynamicTest.dynamicTest("比较 " + item.getString("op"),
                () -> assertEquals(item.getBooleanValue("expected"), ConfigRules.compare(item.getOrDefault("left", UNSET), item.getString("op"), item.get("right")))));
    }

    /**
     * 根据共享用例逐项验证配置值格式
     * @return 格式测试用例
     */
    @TestFactory
    Stream<DynamicTest> sharedFormatCases() throws IOException {
        return cases().getJSONArray("formats").stream().map(item -> (JSONObject) item).map(item -> DynamicTest.dynamicTest("格式 " + item.getString("format") + " " + item.get("value"),
                () -> assertEquals(item.getBooleanValue("expected"), ConfigRules.isValidFormat(item.get("value"), item.getString("format")))));
    }

    /**
     * 共享用例覆盖规则目录中的全部规则和比较符
     */
    @Test
    void catalogHasEveryImplementedRuleAndOperator() throws IOException {
        var rules = new HashSet<String>();
        var operators = new HashSet<String>();
        cases().getJSONArray("rules").forEach(item -> rules.add(((JSONObject) item).getJSONObject("rule").getString("rule")));
        cases().getJSONArray("operators").forEach(item -> operators.add(((JSONObject) item).getString("op")));
        assertEquals(ConfigRules.RULES, rules);
        assertEquals(ConfigRules.OPERATORS, operators);
    }

    /**
     * 根据共享用例验证占位符的完整性
     * @return 占位符测试用例
     */
    @TestFactory
    Stream<DynamicTest> sharedPlaceholderCases() throws IOException {
        return cases().getJSONArray("placeholders").stream().map(item -> (JSONObject) item)
                .map(item -> DynamicTest.dynamicTest("占位符 " + item.get("value"),
                        () -> assertEquals(item.getBooleanValue("expected"), ConfigValueUtil.containsPlaceholder(item.get("value")))));
    }

    /**
     * 仅当条件引用了映射到 Spring 的占位符时结果未知，默认值只补充同级字段
     */
    @Test
    void placeholdersStayUnknownAndDefaultsAreScopedToParent() {
        var condition = JSONObject.of("scope", "parent", "path", List.of("port"), "op", ">", "value", 1);
        var parent = Map.of("port", "${PORT}");
        var path = List.of("starbot", "example");
        var bound = List.of(List.of("starbot", "example", "port"));
        assertNull(ConfigRules.evaluateCondition(condition, new ConfigRules.Context(parent, UNSET, Map.of(), Map.of(), path, List.of(), bound)));
        assertEquals(Boolean.FALSE, ConfigRules.evaluateCondition(condition, new ConfigRules.Context(parent, UNSET, Map.of(), Map.of(), path, List.of(), List.of())));
        assertEquals(Boolean.FALSE, ConfigRules.evaluateCondition(condition, new ConfigRules.Context(Map.of("port", "${PORT"), UNSET, Map.of(), Map.of(),
                path, List.of(), bound)));
        var fallback = new ConfigRules.Context(Map.of(), UNSET, Map.of(), Map.of("port", 7827), path, List.of(), List.of());
        assertEquals(Boolean.TRUE, ConfigRules.evaluateCondition(condition, fallback));
        condition.put("scope", "document");
        assertEquals(Boolean.FALSE, ConfigRules.evaluateCondition(condition, fallback));
    }

    /**
     * 拒绝越界颜色值及协议不符、包含凭据、查询参数或片段的 API 地址
     */
    @Test
    void formatsRejectInvalidColorsAndUnsafeBaseUrls() {
        assertFalse(ConfigRules.isValidFormat("256,0,0", "color"));
        assertTrue(ConfigRules.isValidFormat("255,0,0", "color"));
        for (String url : Set.of("https://user@example.com", "https://example.com?q=1", "https://example.com#x", "file:/tmp/test")) {
            assertFalse(ConfigRules.isValidFormat(url, "http-base-url"));
        }
        assertTrue(ConfigRules.isValidFormat("https://example.com/v1", "http-base-url"));
    }

    /**
     * 拒绝未知规则、未知比较符和高风险正则表达式
     */
    @Test
    void unknownRulesAndDangerousPatternsAreRejected() {
        var ctx = new ConfigRules.Context(Map.of(), 1, Map.of(), Map.of(), List.of(), List.of(), List.of());
        assertThrows(IllegalArgumentException.class, () -> ConfigRules.check(1, JSONObject.of("rule", "runJava"), ctx));
        assertThrows(IllegalArgumentException.class, () -> ConfigRules.compare(1, "unknown", 2));
        assertThrows(IllegalArgumentException.class, () -> ConfigRules.validatePattern("(a+)+$"));
        assertThrows(IllegalArgumentException.class, () -> ConfigRules.validatePattern("(?=a)"));
    }
}
