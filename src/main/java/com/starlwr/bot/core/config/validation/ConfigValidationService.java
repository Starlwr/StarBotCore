package com.starlwr.bot.core.config.validation;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.starlwr.bot.core.config.SpringPropertyBridge;
import com.starlwr.bot.core.config.security.ConfigPasswordUtil;
import com.starlwr.bot.core.config.service.ConfigMetaRegistry;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;

import java.util.*;

import static com.starlwr.bot.core.config.validation.ConfigValueUtil.*;

/**
 * 配置校验服务
 */
public class ConfigValidationService {
    /**
     * 配置元数据注册表
     */
    private final ConfigMetaRegistry registry;

    /**
     * Spring 属性桥接器
     */
    private final SpringPropertyBridge springPropertyBridge;

    /**
     * 映射到 Spring 的业务配置路径集合
     */
    private final Collection<List<String>> springPaths;

    public ConfigValidationService(ConfigMetaRegistry registry, SpringPropertyBridge springPropertyBridge) {
        this.registry = registry;
        this.springPropertyBridge = springPropertyBridge;
        springPaths = registry.getSpringBindings().values();
    }

    /**
     * 根据各模块的规则校验配置文档
     * @param document 待校验的配置对象
     * @return 校验错误及警告
     */
    public JSONObject validate(Object document) {
        return applyDefaultsAndValidate(JSON.parseObject(JSON.toJSONString(document, JSONWriter.Feature.WriteNulls)));
    }

    /**
     * 在当前配置中补充默认值并校验
     * @param effective 待补充默认值的配置草稿
     * @return 校验错误及警告
     */
    public JSONObject applyDefaultsAndValidate(JSONObject effective) {
        // 检查用户填写的重复属性，再补充页面默认值
        List<JSONObject> errors = new ArrayList<>();
        List<JSONObject> warnings = new ArrayList<>();
        Object accessPassword = getValue(effective, ConfigPasswordUtil.PASSWORD_PATH);
        for (SpringPropertyBridge.Duplicate duplicate : springPropertyBridge.duplicates(effective.get("advanced"))) {
            errors.add(JSONObject.of("path", new JSONArray(duplicate.getFirst()), "relatedPath", new JSONArray(duplicate.getSecond()),
                    "code", "duplicateSpringProperty", "message", duplicate.message()));
        }
        for (ConfigMetaRegistry.Declaration declaration : registry.getDeclarations()) {
            applyDefault(effective, declaration.getPath(), declaration.getField());
        }

        // 根据各模块的规则校验字段，记录错误所属的模块和分组
        for (ConfigMetaRegistry.Declaration declaration : registry.getDeclarations()) {
            Map<String, Object> defaults = getDefaults(declaration.getSiblings());
            List<Object> path = declaration.getPath();
            if ("core".equals(declaration.getTab()) && ConfigPasswordUtil.PASSWORD_PATH.equals(path)
                    && accessPassword != UNSET && !(accessPassword instanceof String)) {
                addIssue(errors, declaration, path, "访问密码必须为文本");
                continue;
            }
            visit(declaration.getField(), path, effective, getValue(effective, path.subList(0, path.size() - 1)), defaults, declaration, errors, warnings, 0);
        }

        // 根据路径、消息和来源去除重复的错误及警告
        for (List<JSONObject> issues : List.of(errors, warnings)) {
            Map<String, JSONObject> unique = new LinkedHashMap<>();
            for (JSONObject issue : issues) {
                String key = JSONObject.of("path", issue.get("path"), "message", issue.get("message"), "source", issue.get("source")).toJSONString();
                unique.putIfAbsent(key, issue);
            }
            issues.clear();
            issues.addAll(unique.values());
        }
        return JSONObject.of("errors", errors, "warnings", warnings);
    }

    /**
     * 获取同级字段的页面默认值
     * @param fields 同级字段的元数据列表
     * @return 字段名到默认值的映射
     */
    private static Map<String, Object> getDefaults(JSONArray fields) {
        Map<String, Object> defaults = new LinkedHashMap<>();
        for (Object item : fields) {
            JSONObject field = (JSONObject) item;
            if (getDefault(field) != UNSET) {
                defaults.put(field.getString("key"), normalizeString(getDefault(field), field.get("type")));
            }
        }
        return defaults;
    }

    /**
     * 校验字段及其子项，记录错误和警告
     * @param field 当前字段的配置元数据
     * @param path 当前字段的路径，保留键名中的点号和列表下标
     * @param document 已补充默认值的配置根对象
     * @param parent 当前字段所属对象
     * @param defaults 同级字段的默认值
     * @param declaration 声明该字段的模块、分组及元数据
     * @param errors 错误列表
     * @param warnings 警告列表
     * @param depth 当前嵌套层级
     */
    private void visit(JSONObject field, List<Object> path, Object document, Object parent, Object defaults,
                       ConfigMetaRegistry.Declaration declaration, List<JSONObject> errors, List<JSONObject> warnings, int depth) {
        // 限制递归深度，跳过元数据中已标记为有冲突的字段
        if (depth > 64) {
            addIssue(errors, declaration, path, "配置嵌套超过 64 层");
            return;
        }
        if (field.getBooleanValue("conflicted")) {
            return;
        }

        // 取得字段的填写值或默认值，判断是否需要校验
        Object raw = getValue(parent, List.of(path.get(path.size() - 1)));
        if (raw == UNSET && field.getBooleanValue("suggestionConflict")) {
            return;
        }
        Object value = normalizeString(raw == UNSET ? getDefault(field) : raw, field.get("type"));
        if (ConfigPasswordUtil.PASSWORD_PATH.equals(path) && "core".equals(declaration.getTab()) && ConfigPasswordUtil.isEncoded(value)) {
            return;
        }
        ConfigRules.Context context = new ConfigRules.Context(parent, value, document, defaults,
                path.subList(0, path.size() - 1), path, springPaths);
        if (field.containsKey("validateWhen") && Boolean.FALSE.equals(ConfigRules.evaluateCondition(field.getJSONObject("validateWhen"), context))) {
            return;
        }

        // 确认路径上的父节点仍为对象或列表
        for (int i = 1; i < path.size(); i++) {
            List<Object> ancestorPath = path.subList(0, i);
            Object ancestor = getValue(document, ancestorPath);
            if (ancestor == UNSET) {
                break;
            }
            if (!(ancestor instanceof Map<?, ?>) && !(ancestor instanceof List<?>)) {
                addIssue(errors, declaration, ancestorPath, "字段所属节点不是对象或列表");
                return;
            }
        }

        // 检查必填字段，再执行元数据中的校验规则
        String label = getString(field, "label", path.get(path.size() - 1).toString());
        boolean deferred = isSpringPlaceholder(value, path, springPaths);
        boolean required = field.getBooleanValue("required")
                || Boolean.TRUE.equals(ConfigRules.evaluateCondition(field.getJSONObject("requiredWhen"), context)) && field.containsKey("requiredWhen");
        if (required && (value == UNSET || value == null || "".equals(value))) {
            addIssue(errors, declaration, path, label + "需要填写");
        }
        JSONArray rules = field.getJSONArray("validators");
        if (rules != null) {
            for (Object item : rules) {
                JSONObject rule = (JSONObject) item;
                String name = rule.getString("rule");
                if (Set.of("required", "requiredWhen", "forbiddenWhen").contains(name)
                        || value != UNSET && value != null && !deferred) {
                    try {
                        if (!ConfigRules.check(value, rule, context)) {
                            addIssue(errors, declaration, path, getString(rule, "message", label + "未通过 " + name + " 校验"));
                        }
                    } catch (RuntimeException e) {
                        addIssue(errors, declaration, path, label + "无法执行 " + name + " 校验, 请检查值和规则参数");
                    }
                }
            }
        }

        // 跳过未填写的值，把 Spring 占位符留到启动时解析
        if (value == UNSET) {
            return;
        }
        if (deferred) {
            addIssue(warnings, declaration, path, "Spring 占位符按原文保存, 具体值需要在正常启动时验证");
            return;
        }

        // 检查字段能否填写 null，以及值的类型是否正确
        List<?> types = field.get("type") instanceof List<?> list ? list : List.of(field.get("type"));
        if (value == null) {
            if (!required && !field.getBooleanValue("nullable") && !types.contains("any") && !types.contains("null")) {
                addIssue(errors, declaration, path, label + "不允许为 null");
            }
            return;
        }
        if (types.stream().noneMatch(type -> switch (type.toString()) {
            case "any" -> true;
            case "string" -> value instanceof String;
            case "integer" -> value instanceof Number && toDecimal(value).stripTrailingZeros().scale() <= 0;
            case "number" -> value instanceof Number;
            case "duration" -> parseDuration(value) != null;
            case "boolean" -> value instanceof Boolean;
            case "object", "map" -> value instanceof Map<?, ?>;
            case "array" -> value instanceof List<?>;
            default -> false;
        })) {
            addIssue(errors, declaration, path, label + "的数据类型不正确");
            return;
        }

        // 检查空白文本、数值上下限、候选值和指定格式
        if (field.getBooleanValue("nonBlank") && (!(value instanceof String text) || StringUtils.isBlank(text))) {
            addIssue(errors, declaration, path, label + "不能为空白");
        }
        if (value instanceof Number) {
            if (field.containsKey("minimum") && !ConfigRules.compare(value, ">=", field.get("minimum"))) {
                addIssue(errors, declaration, path, label + "不能小于 " + field.get("minimum"));
            }
            if (field.containsKey("maximum") && !ConfigRules.compare(value, "<=", field.get("maximum"))) {
                addIssue(errors, declaration, path, label + "不能大于 " + field.get("maximum"));
            }
        }
        JSONArray options = field.getJSONArray("options");
        if (options != null && options.stream().noneMatch(item -> isEqual(((JSONObject) item).get("value"), value))) {
            addIssue(errors, declaration, path, "请选择有效的" + label);
        }
        JSONObject optionsFrom = field.getJSONObject("optionsFrom");
        if (options == null && optionsFrom != null && optionsFrom.getBooleanValue("enforce")
                && !matchesDynamicOption(value, optionsFrom, context)) {
            addIssue(errors, declaration, path, "\"" + label + "\" 的当前选项已不可用, 请重新选择");
        }
        if (field.containsKey("format") && !ConfigRules.isValidFormat(value, field.getString("format"))) {
            addIssue(errors, declaration, path, label + "格式不正确");
        }

        // 递归校验固定对象和列表元素
        if (types.contains("object") && value instanceof Map<?, ?>) {
            JSONArray fields = field.getJSONArray("fields");
            Map<String, Object> childDefaults = getDefaults(fields);
            for (Object child : fields) {
                JSONObject definition = (JSONObject) child;
                visit(definition, appendPath(path, definition.getString("key")), document, value, childDefaults, declaration, errors, warnings, depth + 1);
            }
        }
        if (types.contains("array") && value instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                visit(field.getJSONObject("items"), appendPath(path, i), document, value, Map.of(), declaration, errors, warnings, depth + 1);
            }
        }

        // 检查 Map 中每个条目的键和值
        if (types.contains("map") && value instanceof Map<?, ?> map) {
            JSONObject mapEntries = field.getJSONObject("mapEntries");
            for (Object key : map.keySet()) {
                if (mapEntries != null && mapEntries.containsKey(key)) {
                    continue;
                }
                JSONObject keyMeta = field.getJSONObject("keyMeta");
                if (keyMeta != null) {
                    List<Object> keyPath = List.of("key");
                    List<JSONObject> keyErrors = new ArrayList<>();
                    List<JSONObject> keyWarnings = new ArrayList<>();
                    Map<String, Object> keyDocument = Map.of("key", key);
                    visit(keyMeta, keyPath, keyDocument, keyDocument, Map.of(), declaration, keyErrors, keyWarnings, depth + 1);
                    for (JSONObject issue : keyErrors) {
                        issue.put("path", appendPath(path, key));
                    }
                    for (JSONObject issue : keyWarnings) {
                        issue.put("path", appendPath(path, key));
                    }
                    errors.addAll(keyErrors);
                    warnings.addAll(keyWarnings);
                }
                visit(field.getJSONObject("values"), appendPath(path, key), document, value, Map.of(), declaration, errors, warnings, depth + 1);
            }
        }
    }

    /**
     * 记录校验错误及其字段、分组和来源
     * @param errors 错误列表
     * @param declaration 用于定位字段所在模块和分组的声明
     * @param path 配置路径
     * @param message 错误消息
     */
    private static void addIssue(List<JSONObject> errors, ConfigMetaRegistry.Declaration declaration, List<?> path, String message) {
        errors.add(JSONObject.of("path", path, "message", message, "tab", declaration.getTab(), "group", declaration.getGroup(), "source", declaration.getSource()));
    }

    /**
     * 检查当前值是否仍在其他配置生成的选项中
     * @param value 当前配置值
     * @param optionsFrom 动态选项元数据
     * @param context 当前校验上下文
     * @return 是否匹配选项，选项来源包含占位符时暂时通过校验
     */
    private static boolean matchesDynamicOption(Object value, JSONObject optionsFrom, ConfigRules.Context context) {
        // 根据作用域和路径读取候选值来源
        String scope = getString(optionsFrom, "scope", "parent");
        JSONArray path = optionsFrom.getJSONArray("path");
        Object source = context.scope(scope);
        for (Object part : path) {
            if (source instanceof List<?> list) {
                int index = optionIndex(part, list.size());
                source = index < 0 ? UNSET : list.get(index);
            } else {
                source = getValue(source, List.of(part));
            }
        }
        if (context.defersPlaceholder(source, scope, path)) {
            return true;
        }

        // 根据选项来源匹配对象的键或值、列表的下标或元素
        boolean useKeys = "keys".equals(optionsFrom.getString("source"));
        if (source instanceof Map<?, ?> map) {
            return useKeys ? map.containsKey(value) : map.values().stream().anyMatch(candidate -> isEqual(candidate, value));
        }
        if (source instanceof List<?> list) {
            return useKeys ? optionIndex(value, list.size()) >= 0 : list.stream().anyMatch(candidate -> isEqual(candidate, value));
        }
        return false;
    }

    /**
     * 将列表选项的键名转换为有效下标
     * @param value 待检查的键名
     * @param size 列表长度
     * @return 列表下标，键名无效时返回 -1
     */
    private static int optionIndex(Object value, int size) {
        if (!(value instanceof String key)) {
            return -1;
        }
        int index = NumberUtils.toInt(key, -1);
        return index >= 0 && index < size && key.equals(Integer.toString(index)) ? index : -1;
    }
}
