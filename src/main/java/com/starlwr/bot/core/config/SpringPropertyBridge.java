package com.starlwr.bot.core.config;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.starlwr.bot.core.config.service.ConfigMetaRegistry;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.starlwr.bot.core.config.validation.ConfigValueUtil.appendPath;

/**
 * Spring 属性桥接器
 */
public final class SpringPropertyBridge {
    /**
     * 匹配以点号开头或由方括号包住的属性路径段
     */
    private static final Pattern SEGMENT = Pattern.compile("\\.([^.\\[\\]]+)|\\[([^\\[\\]]+)]");

    /**
     * 匹配可以直接用点号连接的对象字段名
     */
    private static final Pattern SIMPLE_KEY = Pattern.compile("[a-z0-9-]+");

    /**
     * 匹配 Map 属性中省略方括号时允许的单个键名片段
     */
    private static final Pattern PLAIN_MAP_KEY = Pattern.compile("[A-Za-z0-9-]+");

    /**
     * 匹配列表路径中不带前导零的非负整数下标
     */
    private static final Pattern INDEX = Pattern.compile("0|[1-9][0-9]*");

    /**
     * 高级配置字段元数据映射
     */
    private final Map<String, JSONObject> fieldMetaByKey = new LinkedHashMap<>();

    /**
     * 对象、Map 和列表类型的高级配置键列表
     */
    private final List<String> containers;

    /**
     * Spring 属性声明路径映射
     */
    private final Map<String, List<String>> declaredPaths = new LinkedHashMap<>();

    public SpringPropertyBridge(ConfigMetaRegistry registry) {
        // 收集类型没有冲突的高级配置声明
        Map<String, List<String>> pathsByKey = new LinkedHashMap<>();
        for (ConfigMetaRegistry.Declaration declaration : registry.getDeclarations()) {
            List<Object> path = declaration.getPath();
            JSONObject field = declaration.getField();
            if ("advanced".equals(path.get(0)) && !field.getBooleanValue("conflicted")) {
                String key = field.getString("key");
                fieldMetaByKey.putIfAbsent(key, field);
                pathsByKey.putIfAbsent(key, path.stream().map(Object::toString).toList());
            }
        }
        // 记录展开配置时需要参考类型的键
        containers = fieldMetaByKey.keySet().stream().filter(key -> {
            JSONObject fieldMeta = fieldMetaByKey.get(key);
            return hasType(fieldMeta, "map") || hasType(fieldMeta, "array") || hasType(fieldMeta, "object");
        }).sorted(Comparator.comparingInt(String::length)).toList();

        // 仅记录可以唯一定位到配置路径的属性
        Map<String, Set<List<String>>> paths = new LinkedHashMap<>();
        pathsByKey.forEach((key, path) -> paths.computeIfAbsent(identity(key), ignored -> new HashSet<>()).add(path));
        paths.forEach((key, matches) -> {
            if (matches.size() == 1) {
                declaredPaths.put(key, matches.iterator().next());
            }
        });
    }

    /**
     * 重复 Spring 属性记录
     */
    @Getter
    @RequiredArgsConstructor
    @EqualsAndHashCode
    public static final class Duplicate {
        /**
         * 重复配置的 Spring 属性键
         */
        private final String key;

        /**
         * 第一处重复配置的路径段列表
         */
        private final List<Object> first;

        /**
         * 第二处重复配置的路径段列表
         */
        private final List<Object> second;

        /**
         * 生成包含两处 JSON 路径的重复配置提示
         * @return 包含重复属性及两处原始路径的错误消息
         */
        public String message() {
            return "Spring 属性 " + key + " 重复配置: " + JSON.toJSONString(first) + " 与 " + JSON.toJSONString(second);
        }
    }

    /**
     * Spring 属性重复配置异常
     */
    public static final class DuplicatePropertiesException extends IllegalArgumentException {
        private DuplicatePropertiesException(List<Duplicate> duplicates) {
            super(String.join(System.lineSeparator(), duplicates.stream().map(Duplicate::message).toList()));
        }
    }

    /**
     * 展开后的 Spring 属性
     */
    @Getter
    @RequiredArgsConstructor
    private static class Property {
        /**
         * Spring 属性键
         */
        private final String name;

        /**
         * Spring 属性值
         */
        private final Object value;

        /**
         * Spring 属性原始配置路径段列表
         */
        private final List<Object> path;

    }

    /**
     * Spring 属性路径段
     */
    @Getter
    @RequiredArgsConstructor
    private static class Segment {
        /**
         * 路径段名称
         */
        private final String key;

        /**
         * 路径段是否写在方括号中
         */
        private final boolean bracketed;

    }

    /**
     * 查找用户填写的重复 Spring 属性
     * @param advanced 高级配置原文解析结果
     * @return 重复属性及其在 JSON 中的两处位置
     */
    public List<Duplicate> duplicates(Object advanced) {
        return duplicates(expand(advanced));
    }

    /**
     * 从启动配置中排除重复的属性，保留同一对象中其他可用的属性
     * @param advanced 启动配置中的高级配置副本
     * @param duplicates 用户填写的重复属性列表
     */
    public void excludeDuplicates(Map<String, Object> advanced, List<Duplicate> duplicates) {
        Set<String> duplicateKeys = new HashSet<>();
        Set<String> affectedEntries = new HashSet<>();
        for (Duplicate duplicate : duplicates) {
            duplicateKeys.add(duplicate.getKey());
            affectedEntries.add((String) duplicate.getFirst().get(1));
            affectedEntries.add((String) duplicate.getSecond().get(1));
        }

        List<Property> properties = expand(advanced);
        affectedEntries.forEach(advanced::remove);
        for (Property property : properties) {
            if (affectedEntries.contains((String) property.getPath().get(1)) && !duplicateKeys.contains(identity(property.getName()))) {
                advanced.put(property.getName(), property.getValue());
            }
        }
    }

    /**
     * 将高级属性整理到元数据声明的配置路径，存在重复属性时保留原写法
     * @param document 待整理的配置对象
     */
    public void normalizeAdvanced(Map<String, Object> document) {
        if (!(document.get("advanced") instanceof Map<?, ?> advanced)) {
            return;
        }
        List<Property> properties = expand(advanced);
        if (CollectionUtils.isNotEmpty(duplicates(properties))) {
            return;
        }

        JSONObject normalized = new JSONObject();
        advanced.forEach((key, value) -> normalized.put(key.toString(), value));
        for (Property property : properties) {
            List<Object> path = property.getPath();
            List<String> declared = declaredPaths.get(identity(property.getName()));
            if (declared != null && !path.equals(declared) && path.stream().allMatch(String.class::isInstance)) {
                moveProperty(normalized, property, declared);
            }
        }
        document.put("advanced", normalized);
    }

    /**
     * 查询属性在元数据中声明的配置路径
     * @param key 待查找的 Spring 属性键
     * @return 元数据中的配置路径，无法唯一匹配时保留完整键
     */
    public List<String> declaredPath(String key) {
        return declaredPaths.getOrDefault(identity(key), List.of("advanced", key));
    }

    /**
     * 将高级配置展开为 Spring 属性，优先采用用户填写的值，配置值明确设为 null 时，跳过当前属性及其默认值
     * @param effective 补充默认值后的高级配置
     * @param explicit 文件或草稿明确填写的高级配置
     * @return 展开后的 Spring 属性映射
     */
    public Map<String, Object> properties(Object effective, Object explicit) {
        // 先检查用户填写的属性是否重复，再补充未填写的值
        List<Property> overrides = expand(explicit);
        requireNoDuplicates(duplicates(overrides));
        Map<String, Property> properties = new LinkedHashMap<>();
        overrides.forEach(property -> properties.put(identity(property.getName()), property));
        expand(effective).forEach(property -> properties.putIfAbsent(identity(property.getName()), property));

        // null 表示取消设置该属性，无需传给 Spring
        Map<String, Object> result = new LinkedHashMap<>();
        properties.values().forEach(property -> {
            if (property.getValue() != null) {
                result.put(property.getName(), property.getValue());
            }
        });
        return result;
    }

    /**
     * 检查同一个 Spring 属性是否被用户填写了多次
     * @param explicit 文件或草稿明确填写的高级配置
     */
    public void requireUnique(Object explicit) {
        requireNoDuplicates(duplicates(explicit));
    }

    /**
     * 根据 springKey 展开业务配置并写入属性映射
     * @param key 元数据声明的 springKey
     * @param value 业务配置的当前生效值
     * @param target 接收值不为 null 的属性的映射
     */
    public static void addBinding(String key, Object value, Map<String, Object> target) {
        List<Property> properties = new ArrayList<>();
        flatten(key, value, null, List.of(), properties);
        properties.forEach(property -> {
            if (property.getValue() != null) {
                target.put(property.getName(), property.getValue());
            }
        });
    }

    /**
     * 检查字段是否声明了指定类型
     * @param fieldMeta 字段元数据
     * @param type 待检查的类型名称
     * @return 是否包含该类型
     */
    private static boolean hasType(JSONObject fieldMeta, String type) {
        return fieldMeta != null && (fieldMeta.get("type") instanceof List<?> types ? types.contains(type) : type.equals(fieldMeta.get("type")));
    }

    /**
     * 把指向同一属性的键转成相同写法，供比较时使用
     * @param key 待比较的 Spring 属性键
     * @return 用于判断是否为同一属性的键名
     */
    private String identity(String key) {
        for (String root : containers) {
            if (key.startsWith(root + ".") || key.startsWith(root + "[")) {
                List<Segment> segments = segments(key.substring(root.length()));
                if (CollectionUtils.isNotEmpty(segments)) {
                    JSONObject fieldMeta = fieldMetaByKey.get(root);
                    String suffix = normalize(segments, 0, fieldMeta);
                    if (suffix != null) {
                        return root + suffix;
                    }
                    if (hasType(fieldMeta, "map") && isScalar(fieldMeta.getJSONObject("values")) && segments.get(0).isBracketed()
                            && segments.size() > 1 && segments.subList(1, segments.size()).stream().noneMatch(Segment::isBracketed)) {
                        String candidate = root + "[" + String.join(".", segments.stream().map(Segment::getKey).toList()) + "]";
                        if (declaredPaths.containsKey(candidate)) {
                            return candidate;
                        }
                    }
                }
            }
        }
        return key;
    }

    /**
     * 比较展开后的属性，记录同一个属性的两处配置位置
     * @param properties 带原始 JSON 路径的属性
     * @return 重复属性及其位置
     */
    private List<Duplicate> duplicates(List<Property> properties) {
        Map<String, Property> seen = new LinkedHashMap<>();
        List<Duplicate> duplicates = new ArrayList<>();
        for (Property property : properties) {
            String key = identity(property.getName());
            Property previous = seen.putIfAbsent(key, property);
            if (previous != null) {
                duplicates.add(new Duplicate(key, previous.getPath(), property.getPath()));
            }
        }
        return duplicates;
    }

    /**
     * 展开高级配置并记录各属性在 JSON 中的位置
     * @param advanced 高级配置对象
     * @return 展开后的属性及原始路径
     */
    private List<Property> expand(Object advanced) {
        List<Property> properties = new ArrayList<>();
        if (advanced instanceof Map<?, ?> map) {
            map.forEach((name, value) -> {
                String key = name.toString();
                flatten(key, value, fieldMetaByKey.get(key), List.of("advanced", key), properties);
            });
        }
        return properties;
    }

    /**
     * 将属性移动到已声明的高级字段或 Map 条目
     * @param advanced 当前高级配置
     * @param property 带原始路径的属性
     * @param target 声明的配置路径
     */
    private static void moveProperty(JSONObject advanced, Property property, List<String> target) {
        String root = target.get(1);
        if (target.size() == 2) {
            if (!advanced.containsKey(root)) {
                removePath(advanced, property.getPath(), 1);
                advanced.put(root, property.getValue());
            }
            return;
        }
        Object existing = advanced.get(root);
        if (advanced.containsKey(root) && !(existing instanceof Map<?, ?>)) {
            return;
        }
        String key = target.get(2);
        if (existing instanceof Map<?, ?> entries && entries.containsKey(key)) {
            return;
        }
        removePath(advanced, property.getPath(), 1);
        JSONObject map = new JSONObject();
        if (advanced.get(root) instanceof Map<?, ?> remaining) {
            remaining.forEach((name, value) -> map.put(name.toString(), value));
        }
        map.put(key, property.getValue());
        advanced.put(root, map);
    }

    /**
     * 删除字段的旧位置，并清理因此变空的父级
     * @param parent 当前层的配置对象
     * @param path 从 advanced 开始的原始 JSON 路径，保留键名中的点号
     * @param index 当前处理的路径段下标
     */
    private static void removePath(JSONObject parent, List<Object> path, int index) {
        String key = (String) path.get(index);
        if (index == path.size() - 1) {
            parent.remove(key);
        } else if (parent.get(key) instanceof Map<?, ?> child) {
            JSONObject remaining = new JSONObject();
            child.forEach((name, value) -> remaining.put(name.toString(), value));
            removePath(remaining, path, index + 1);
            if (MapUtils.isEmpty(remaining)) {
                parent.remove(key);
            } else {
                parent.put(key, remaining);
            }
        }
    }

    /**
     * 发现重复属性时报告冲突
     * @param duplicates 重复属性及其位置
     */
    private static void requireNoDuplicates(List<Duplicate> duplicates) {
        if (CollectionUtils.isNotEmpty(duplicates)) {
            throw new DuplicatePropertiesException(duplicates);
        }
    }

    /**
     * 将对象字段、Map 键和列表下标展开成 Spring 属性名
     * @param key 当前的 Spring 属性路径
     * @param value 当前配置值
     * @param fieldMeta 当前节点的元数据，可以为空
     * @param path 当前节点的原始 JSON 路径
     * @param target 接收展开后的属性
     */
    private static void flatten(String key, Object value, JSONObject fieldMeta, List<Object> path, List<Property> target) {
        if (value instanceof Map<?, ?> map && MapUtils.isNotEmpty(map)) {
            boolean isMap = hasType(fieldMeta, "map") && !hasType(fieldMeta, "object");
            map.forEach((name, item) -> {
                String child = name.toString();
                JSONObject definition = isMap ? mapValue(fieldMeta, child) : field(fieldMeta, child);
                String suffix = isMap || !SIMPLE_KEY.matcher(child).matches() ? "[" + child + "]" : "." + child;
                flatten(key + suffix, item, definition, appendPath(path, child), target);
            });
        } else if (value instanceof List<?> list && CollectionUtils.isNotEmpty(list)) {
            JSONObject items = hasType(fieldMeta, "array") ? fieldMeta.getJSONObject("items") : null;
            for (int index = 0; index < list.size(); index++) {
                flatten(key + "[" + index + "]", list.get(index), items, appendPath(path, index), target);
            }
        } else {
            target.add(new Property(key, value, List.copyOf(path)));
        }
    }

    /**
     * 拆分属性路径，方括号内的内容保留为一段
     * @param suffix 去掉根属性名后的路径
     * @return 完整解析的路径段，存在无法解析的字符时返回空列表
     */
    private static List<Segment> segments(String suffix) {
        List<Segment> segments = new ArrayList<>();
        Matcher matcher = SEGMENT.matcher(suffix);
        int end = 0;
        while (matcher.find() && matcher.start() == end) {
            boolean bracketed = matcher.group(2) != null;
            segments.add(new Segment(matcher.group(bracketed ? 2 : 1), bracketed));
            end = matcher.end();
        }
        return end == suffix.length() ? segments : List.of();
    }

    /**
     * 根据元数据统一后续路径中的点号和方括号写法
     * @param segments 属性后缀的分段结果
     * @param index 当前处理的路径段下标
     * @param fieldMeta 当前节点的元数据
     * @return 统一后的属性后缀，路径不符合元数据时返回 null
     */
    private static String normalize(List<Segment> segments, int index, JSONObject fieldMeta) {
        if (index == segments.size()) {
            return "";
        }
        Segment segment = segments.get(index);
        String prefix;
        JSONObject child;
        if (hasType(fieldMeta, "array") && segment.isBracketed() && INDEX.matcher(segment.getKey()).matches()) {
            prefix = "[" + segment.getKey() + "]";
            child = fieldMeta.getJSONObject("items");
        } else if (hasType(fieldMeta, "map") && !hasType(fieldMeta, "object")) {
            child = fieldMeta.getJSONObject("values");
            if (!segment.isBracketed() && !PLAIN_MAP_KEY.matcher(segment.getKey()).matches()) {
                return null;
            }
            if (!segment.isBracketed() && isScalar(child)) {
                List<Segment> remaining = segments.subList(index, segments.size());
                if (remaining.stream().allMatch(part -> !part.isBracketed() && PLAIN_MAP_KEY.matcher(part.getKey()).matches())) {
                    return "[" + String.join(".", remaining.stream().map(Segment::getKey).toList()) + "]";
                }
            }
            prefix = "[" + segment.getKey() + "]";
            child = mapValue(fieldMeta, segment.getKey());
        } else if (!segment.isBracketed() && (child = field(fieldMeta, segment.getKey())) != null) {
            prefix = "." + segment.getKey();
        } else {
            return null;
        }
        String tail = normalize(segments, index + 1, child);
        return tail == null ? null : prefix + tail;
    }

    /**
     * 判断 Map 是否声明为存放文本、布尔值或数字的映射
     * @param fieldMeta Map 值的元数据
     * @return 是否可以将剩余的点号路径视为一个完整 Map 键
     */
    private static boolean isScalar(JSONObject fieldMeta) {
        return (hasType(fieldMeta, "string") || hasType(fieldMeta, "boolean")
                || hasType(fieldMeta, "integer") || hasType(fieldMeta, "number"))
                && !hasType(fieldMeta, "any") && !hasType(fieldMeta, "array") && !hasType(fieldMeta, "object") && !hasType(fieldMeta, "map");
    }

    /**
     * 获取 Map 条目的元数据，优先使用该键的独立字段声明
     * @param fieldMeta Map 字段元数据
     * @param key Map 条目的键名
     * @return 条目对应的字段元数据
     */
    private static JSONObject mapValue(JSONObject fieldMeta, String key) {
        JSONObject entries = fieldMeta.getJSONObject("mapEntries");
        return entries != null && entries.containsKey(key) ? entries.getJSONObject(key) : fieldMeta.getJSONObject("values");
    }

    /**
     * 根据原键名查找对象的子字段元数据
     * @param fieldMeta 当前节点的元数据
     * @param key 子字段键名
     * @return 子字段元数据，未找到时返回 null
     */
    private static JSONObject field(JSONObject fieldMeta, String key) {
        if (hasType(fieldMeta, "object")) {
            JSONArray fields = fieldMeta.getJSONArray("fields");
            for (Object item : fields) {
                JSONObject field = (JSONObject) item;
                if (key.equals(field.getString("key"))) {
                    return field;
                }
            }
        }
        return null;
    }
}
