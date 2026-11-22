package com.starlwr.bot.core.config.validation;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONException;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.Strings;
import org.springframework.boot.convert.DurationStyle;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.ObjectReadContext;
import tools.jackson.core.json.JsonFactory;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 配置值工具类
 */
public final class ConfigValueUtil {
    /**
     * 字段未设置的标记，与显式 null 区分
     */
    public static final Object UNSET = new Object();

    /**
     * 配置原文的 JSON 解析器工厂
     */
    private static final JsonFactory JSON_FACTORY = new JsonFactory();

    private ConfigValueUtil() {
    }

    /**
     * 检查配置原文的 JSON 语法并读取根对象
     * @param raw 配置原文
     * @return 配置根对象
     */
    public static JSONObject parseDocument(String raw) {
        String content = Strings.CS.removeStart(Objects.toString(raw, ""), "\uFEFF");
        Object parsed;
        try (JsonParser parser = JSON_FACTORY.createParser(ObjectReadContext.empty(), content)) {
            parser.nextToken();
            parser.skipChildren();
            if (parser.nextToken() != null) {
                throw new JSONException("JSON 格式无效");
            }
            parsed = JSON.parse(content);
        } catch (JacksonException | JSONException | IllegalArgumentException e) {
            throw new JSONException("JSON 格式无效");
        }
        if (!(parsed instanceof JSONObject object)) {
            throw new IllegalArgumentException("配置文件根节点必须是 JSON 对象");
        }
        return object;
    }

    /**
     * 获取字段实际使用的默认值
     * @param field 字段元数据
     * @return 可直接采用的高级配置建议值或配置类的默认值，无可用值时返回 UNSET
     */
    public static Object getDefault(Map<String, Object> field) {
        boolean advanced = field.get("configPath") instanceof List<?> path && CollectionUtils.isNotEmpty(path) && "advanced".equals(path.get(0));
        return Boolean.TRUE.equals(field.get("suggestionConflict")) || Boolean.TRUE.equals(field.get("conflicted"))
                ? UNSET : field.getOrDefault("effectiveDefault", advanced ? UNSET : field.getOrDefault("default", UNSET));
    }

    /**
     * 给草稿中未设置的字段填上页面默认值
     * @param draft 要补充默认值的配置草稿
     * @param path 字段路径，每一段对应一个 JSON 键名
     * @param field 字段的配置元数据
     */
    public static void applyDefault(JSONObject draft, List<Object> path, JSONObject field) {
        boolean advanced = CollectionUtils.isNotEmpty(path) && "advanced".equals(path.get(0));
        applyField(draft, path, field, advanced, true);
    }

    /**
     * 将声明了转换规则的空字符串设为 null
     * @param document 配置文档
     * @param path 配置路径
     * @param field 字段元数据
     */
    public static void normalizeEmptyStrings(Map<String, Object> document, List<Object> path, JSONObject field) {
        applyField(document, path, field, false, false);
    }

    /**
     * 根据字段路径处理当前值，按需创建上级对象并补充默认值
     * @param draft 要补充默认值的配置对象
     * @param path 从该对象到字段的路径
     * @param field 字段的配置元数据
     * @param advanced 字段是否属于高级配置
     * @param fillDefaults 是否补充默认值
     */
    private static void applyField(Map<String, Object> draft, List<Object> path, JSONObject field, boolean advanced, boolean fillDefaults) {
        if (field.getBooleanValue("conflicted")) {
            return;
        }
        Map<String, Object> parent = draft;
        for (Object part : path.subList(0, path.size() - 1)) {
            String key = part.toString();
            if (!parent.containsKey(key)) {
                if (!fillDefaults) {
                    return;
                }
                parent.put(key, new JSONObject());
            }
            if (!(parent.get(key) instanceof JSONObject object)) {
                return;
            }
            parent = object;
        }
        String key = path.get(path.size() - 1).toString();
        Object value = resolveValue(parent.getOrDefault(key, UNSET), field, advanced, fillDefaults);
        if (value != UNSET) {
            parent.put(key, value);
        }
    }

    /**
     * 处理字段和集合条目的空字符串及默认值
     * @param value 当前配置值，未填写时为 UNSET
     * @param field 字段的配置元数据
     * @param advanced 是否为高级配置
     * @param fillDefaults 是否补充默认值
     * @return 已补充默认值的配置值
     */
    private static Object resolveValue(Object value, JSONObject field, boolean advanced, boolean fillDefaults) {
        // 各插件建议值不同时，留空等用户选择
        if (value == UNSET && field.getBooleanValue("suggestionConflict")) {
            return UNSET;
        }

        // 补充未设置字段的默认值
        if (fillDefaults && (value == UNSET || "".equals(value) && field.getJSONObject("ui") != null
                && field.getJSONObject("ui").getBooleanValue("emptyAsDefault"))) {
            Object defaults = advanced && !field.containsKey("effectiveDefault") ? UNSET : getDefault(field);
            value = defaults != UNSET ? JSON.parse(JSON.toJSONString(defaults, JSONWriter.Feature.WriteNulls))
                    : "object".equals(field.getString("type")) ? new JSONObject() : UNSET;
        }

        if ("".equals(value) && field.getBooleanValue("emptyAsNull")) {
            return null;
        }

        // 根据字段类型转换当前值，再补充对象、Map 或列表内部的默认值
        if (fillDefaults) {
            value = normalizeString(value, field.get("type"));
        }
        if (value instanceof JSONObject object) {
            if (field.getJSONArray("fields") != null) {
                for (Object item : field.getJSONArray("fields")) {
                    JSONObject child = (JSONObject) item;
                    applyField(object, List.of(child.getString("key")), child, advanced, fillDefaults);
                }
            } else if (field.getJSONObject("values") != null) {
                JSONObject definition = field.getJSONObject("values");
                JSONObject mapEntries = field.getJSONObject("mapEntries");
                object.replaceAll((key, item) -> mapEntries != null && mapEntries.containsKey(key) ? item : resolveValue(item, definition, advanced, fillDefaults));
            }
        } else if (value instanceof JSONArray array && field.getJSONObject("items") != null) {
            JSONObject definition = field.getJSONObject("items");
            array.replaceAll(item -> resolveValue(item, definition, advanced, fillDefaults));
        }
        return value;
    }

    /**
     * 读取对象中的文本属性
     * @param value 待读取的对象
     * @param key 属性名
     * @param fallback 默认文本
     * @return 属性的文本值，属性缺失或类型不符时返回默认文本
     */
    public static String getString(Map<?, ?> value, String key, String fallback) {
        return value.get(key) instanceof String text ? text : fallback;
    }

    /**
     * 字段接受文本但不接受当前数字时，将数字转成文本
     * @param value 配置值
     * @param type 字段类型或类型列表
     * @return 转换后的值，其他类型保持原样
     */
    public static Object normalizeString(Object value, Object type) {
        if (!(value instanceof Number)) {
            return value;
        }
        List<?> types = type instanceof List<?> list ? list : Collections.singletonList(type);
        if (!types.contains("string") || types.contains("any") || types.contains("number")
                || types.contains("integer") && toDecimal(value).stripTrailingZeros().scale() <= 0) {
            return value;
        }
        return value.toString();
    }

    /**
     * 将时长文本或毫秒数转换为 Duration
     * @param value 时长文本或毫秒数
     * @return 时长，格式或范围无效时返回 null
     */
    public static Duration parseDuration(Object value) {
        try {
            if (value instanceof Number number) {
                return Duration.ofMillis(toDecimal(number).longValueExact());
            }
            return value instanceof String text ? DurationStyle.detectAndParse(text) : null;
        } catch (ArithmeticException | DateTimeException | IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 根据路径读取值，区分未设置和明确设为 null
     * @param value 起始值
     * @param path 路径分段
     * @return 配置值或 UNSET
     */
    public static Object getValue(Object value, List<?> path) {
        Object current = value;
        for (Object part : path) {
            if (current instanceof Map<?, ?> map && map.containsKey(part)) {
                current = map.get(part);
            } else if (current instanceof List<?> list && part instanceof Number number
                    && number.intValue() >= 0 && number.intValue() < list.size()) {
                current = list.get(number.intValue());
            } else {
                return UNSET;
            }
        }
        return current;
    }

    /**
     * 根据 JSON 类型比较两个配置值
     * @param left 左值
     * @param right 右值
     * @return 是否相等
     */
    public static boolean isEqual(Object left, Object right) {
        if (left instanceof Number && right instanceof Number) {
            return toDecimal(left).compareTo(toDecimal(right)) == 0;
        }
        if (left instanceof List<?> a && right instanceof List<?> b) {
            if (a.size() != b.size()) {
                return false;
            }
            for (int i = 0; i < a.size(); i++) {
                if (!isEqual(a.get(i), b.get(i))) {
                    return false;
                }
            }
            return true;
        }
        if (left instanceof Map<?, ?> a && right instanceof Map<?, ?> b) {
            return a.keySet().equals(b.keySet()) && a.keySet().stream().allMatch(key -> isEqual(a.get(key), b.get(key)));
        }
        return Objects.equals(left, right);
    }

    /**
     * 判断值是否为空，false 和 0 不为空
     * @param value 配置值
     * @return 是否为空
     */
    public static boolean isEmpty(Object value) {
        return value == UNSET || value == null || "".equals(value)
                || value instanceof List<?> list && CollectionUtils.isEmpty(list)
                || value instanceof Map<?, ?> map && MapUtils.isEmpty(map);
    }

    /**
     * 获取文本字符数或集合条目数
     * @param value 配置值
     * @return 长度，不适用时返回 -1
     */
    public static int getSize(Object value) {
        if (value instanceof String text) {
            return text.codePointCount(0, text.length());
        }
        if (value instanceof List<?> list) {
            return list.size();
        }
        return value instanceof Map<?, ?> map ? map.size() : -1;
    }

    /**
     * 判断字符串是否包含完整的 Spring 占位符
     * @param value 配置值
     * @return 是否包含未转义且闭合的占位符
     */
    public static boolean containsPlaceholder(Object value) {
        if (!(value instanceof String text)) {
            return false;
        }

        int depth = 0;
        boolean found = false;
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (text.startsWith("${", index) && (index == 0 || text.charAt(index - 1) != '\\')) {
                int key = index + 2;
                if (key == text.length() || "}: \t\n\r\f\u000B".indexOf(text.charAt(key)) >= 0) {
                    return false;
                }
                depth++;
                index++;
            } else if (depth > 0 && character == '{') {
                depth++;
            } else if (depth > 0 && character == '}') {
                found |= --depth == 0;
            }
        }
        return found && depth == 0;
    }

    /**
     * 判断当前配置值是否交给 Spring 解析
     * @param value 当前配置值
     * @param path 当前配置路径
     * @param springPaths 业务配置中映射到 Spring 的路径集合
     * @return 是否需要等 Spring 启动后再校验
     */
    public static boolean isSpringPlaceholder(Object value, List<?> path, Collection<List<String>> springPaths) {
        return CollectionUtils.isNotEmpty(path) && containsPlaceholder(value) && ("advanced".equals(path.get(0))
                || springPaths.stream().anyMatch(binding -> binding.size() <= path.size() && binding.equals(path.subList(0, binding.size()))));
    }

    /**
     * 追加路径分段
     * @param path 原路径
     * @param part 新分段
     * @return 新路径
     */
    public static List<Object> appendPath(List<?> path, Object part) {
        List<Object> result = new ArrayList<>(path);
        result.add(part);
        return result;
    }

    /**
     * 将数字转成精确十进制表示
     * @param value 数字
     * @return 十进制数
     */
    public static BigDecimal toDecimal(Object value) {
        return new BigDecimal(value.toString());
    }

    /**
     * 判断值中是否包含浏览器不能精确表示的整数
     * @param value 配置值
     * @return 是否需要原文编辑
     */
    public static boolean hasUnsafeInteger(Object value) {
        if (value instanceof Number) {
            BigDecimal number = toDecimal(value);
            return number.stripTrailingZeros().scale() <= 0 && number.abs().compareTo(new BigDecimal("9007199254740991")) > 0;
        }
        if (value instanceof Map<?, ?> map) {
            return map.values().stream().anyMatch(ConfigValueUtil::hasUnsafeInteger);
        }
        return value instanceof List<?> list && list.stream().anyMatch(ConfigValueUtil::hasUnsafeInteger);
    }
}
