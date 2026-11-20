package com.starlwr.bot.core.config.validation;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.Strings;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.springframework.boot.convert.DurationStyle;

import java.net.URI;
import java.time.DateTimeException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.starlwr.bot.core.config.validation.ConfigValueUtil.*;

/**
 * 配置条件与校验规则
 */
public final class ConfigRules {
    /**
     * 条件比较符集合
     */
    public static final Set<String> OPERATORS = Set.of("=", "!=", ">", ">=", "<", "<=", "in", "!in",
            "contains", "!contains", "hasKey", "!hasKey", "exists", "!exists", "empty", "!empty",
            "startsWith", "!startsWith", "endsWith", "!endsWith", "matches", "!matches", "between", "!between");

    /**
     * 校验规则名称集合
     */
    public static final Set<String> RULES = Set.of("required", "nonBlank", "minimum", "maximum", "range", "multipleOf",
            "exclusiveMinimum", "exclusiveMaximum", "notEmpty", "trimmed", "lowercase", "uppercase", "integer", "finite",
            "minLength", "maxLength", "length", "pattern", "enum", "format", "port", "contains", "minContains", "maxContains",
            "minItems", "maxItems", "minKeys", "maxKeys", "uniqueItems", "uniqueItemField", "requiredKeys", "allowedKeys",
            "keyPattern", "keyInMap", "valueInField", "uniqueAcross", "dependentRequired", "equalTo", "notEqualTo", "compare",
            "positiveDuration", "durationRange", "nonEmptyOnlyWhen", "requiredWhen", "forbiddenWhen", "atLeastOne",
            "exactlyOne", "allOrNone", "mutuallyExclusive");

    /**
     * 配置值格式名称集合
     */
    public static final Set<String> FORMATS = Set.of("duration", "color", "http-base-url", "http-url", "listen-address", "email", "password",
            "url", "uuid", "ipv4", "hostname", "json");

    /**
     * 密码的半角字符格式
     */
    private static final Pattern PASSWORD_CHARACTERS = Pattern.compile("[!-~]*");

    /**
     * HTTP 接口地址格式，允许路径和查询参数
     */
    private static final Pattern HTTP_URL = Pattern.compile(
            "https?://(\\[[0-9a-f:.]+]|[a-z0-9.-]+)(?::([0-9]{1,5}))?"
                    + "(?:/[-a-z0-9._~!$&'()*+,;=:@/%]*)?(?:\\?[-a-z0-9._~!$&'()*+,;=:@/%?\\[\\]]*)?",
            Pattern.CASE_INSENSITIVE);

    /**
     * HTTP 接口地址中不完整的百分号转义格式
     */
    private static final Pattern INVALID_URL_ESCAPE = Pattern.compile("%(?![0-9a-fA-F]{2})");

    /**
     * 自行判断条件的校验规则名称集合
     */
    private static final Set<String> CONDITIONAL_RULES = Set.of("requiredWhen", "forbiddenWhen", "nonEmptyOnlyWhen");

    private ConfigRules() {
    }

    /**
     * 条件上下文
     */
    @Getter
    @RequiredArgsConstructor
    public static class Context {
        /**
         * 当前字段所属对象
         */
        private final Object parent;

        /**
         * 当前字段值
         */
        private final Object current;

        /**
         * 配置根对象
         */
        private final Object document;

        /**
         * 同级字段的默认值映射
         */
        private final Object defaults;

        /**
         * 父节点的配置路径
         */
        private final List<?> parentPath;

        /**
         * 当前字段的配置路径
         */
        private final List<?> currentPath;

        /**
         * 映射到 Spring 的业务配置路径集合
         */
        private final Collection<List<String>> springPaths;

        /**
         * 根据作用域名称读取条件要比较的配置
         * @param scope 作用域名称
         * @return 父对象、当前值或配置根对象
         */
        public Object scope(String scope) {
            return switch (scope) {
                case "parent" -> parent;
                case "current" -> current;
                case "document" -> document;
                default -> throw new IllegalArgumentException("未知条件作用域: " + scope);
            };
        }

        /**
         * 判断引用的配置值是否需要等 Spring 解析
         * @param value 引用的配置值
         * @param scope 相对路径起点
         * @param path 引用的相对路径
         * @return 是否暂缓校验
         */
        public boolean defersPlaceholder(Object value, String scope, List<?> path) {
            List<?> base = switch (scope) {
                case "parent" -> parentPath;
                case "current" -> currentPath;
                default -> List.of();
            };
            List<Object> fullPath = new ArrayList<>(base);
            fullPath.addAll(path);
            return isSpringPlaceholder(value, fullPath, springPaths);
        }
    }

    /**
     * 根据条件指定的比较符比较两个配置值
     * @param a 左值
     * @param op 比较符
     * @param b 右值
     * @return 比较结果
     */
    public static boolean compare(Object a, String op, Object b) {
        return switch (op) {
            // 比较相等时保留原类型，比较数字大小时使用十进制数值
            case "=" -> isEqual(a, b);
            case "!=" -> !isEqual(a, b);
            case ">", ">=", "<", "<=" -> {
                if (!(a instanceof Number) || !(b instanceof Number)) {
                    yield false;
                }
                int result = toDecimal(a).compareTo(toDecimal(b));
                yield switch (op) {
                    case ">" -> result > 0;
                    case ">=" -> result >= 0;
                    case "<" -> result < 0;
                    default -> result <= 0;
                };
            }

            // 处理列表成员、文本包含及对象键判断
            case "in" -> b instanceof List<?> list && list.stream().anyMatch(item -> isEqual(a, item));
            case "!in" -> b instanceof List<?> && !compare(a, "in", b);
            case "contains" -> a instanceof String text && b instanceof String part ? text.contains(part)
                    : a instanceof List<?> list && list.stream().anyMatch(item -> isEqual(item, b));
            case "!contains" -> !compare(a, "contains", b);
            case "hasKey" -> a instanceof Map<?, ?> map && map.containsKey(b);
            case "!hasKey" -> !compare(a, "hasKey", b);

            // 区分未设置、空值和已设置的配置
            case "exists" -> a != UNSET;
            case "!exists" -> a == UNSET;
            case "empty" -> isEmpty(a);
            case "!empty" -> !isEmpty(a);

            // 执行文本前后缀、受限正则和范围比较
            case "startsWith" -> a instanceof String text && b instanceof String part && text.startsWith(part);
            case "endsWith" -> a instanceof String text && b instanceof String part && text.endsWith(part);
            case "!startsWith" -> !compare(a, "startsWith", b);
            case "!endsWith" -> !compare(a, "endsWith", b);
            case "matches" -> a instanceof String text && b instanceof String regex && matches(text, regex);
            case "!matches" -> !compare(a, "matches", b);
            case "between" -> b instanceof List<?> range && range.size() == 2
                    && compare(a, ">=", range.get(0)) && compare(a, "<=", range.get(1));
            case "!between" -> !compare(a, "between", b);
            default -> throw new IllegalArgumentException("未知比较符: " + op);
        };
    }

    /**
     * 判断条件是否成立，遇到未解析的占位符时暂不判断
     * @param condition 元数据中声明的条件
     * @param context 当前上下文
     * @return 成立、不成立或 null
     */
    public static Boolean evaluateCondition(JSONObject condition, Context context) {
        // 未声明条件时默认成立
        if (condition == null) {
            return true;
        }

        // 逐项判断组合条件，遇到未解析的占位符时保留未知结果
        if (condition.containsKey("all") || condition.containsKey("any")) {
            boolean all = condition.containsKey("all");
            boolean unknown = false;
            for (Object item : condition.getJSONArray(all ? "all" : "any")) {
                Boolean result = evaluateCondition((JSONObject) item, context);
                if (Boolean.valueOf(!all).equals(result)) {
                    return !all;
                }
                unknown |= result == null;
            }
            return unknown ? null : all;
        }

        // 对子条件取反，未知结果仍保持未知
        if (condition.containsKey("not")) {
            Boolean result = evaluateCondition(condition.getJSONObject("not"), context);
            return result == null ? null : !result;
        }

        // 读取条件引用的值，父对象中未设置时尝试同级默认值
        String scope = getString(condition, "scope", "parent");
        List<?> path = condition.containsKey("path") ? condition.getJSONArray("path") : List.of();
        Object value = getValue(context.scope(scope), path);
        if (value == UNSET && "parent".equals(scope)) {
            value = getValue(context.defaults, path);
        }
        if (context.defersPlaceholder(value, scope, path)) {
            return null;
        }

        // 条件要求比较长度时，先计算文本或集合的长度
        if ("size".equals(condition.getString("measure"))) {
            int length = getSize(value);
            value = length < 0 ? UNSET : length;
        }
        return compare(value, condition.getString("op"), condition.get("value"));
    }

    /**
     * 执行单条校验规则
     * @param value 当前值
     * @param rule 元数据中声明的校验规则
     * @param context 当前上下文
     * @return 是否通过
     */
    public static boolean check(Object value, JSONObject rule, Context context) {
        // 普通规则只在 when 条件明确成立时执行
        String name = rule.getString("rule");
        if (rule.containsKey("when") && !CONDITIONAL_RULES.contains(name)
                && !Boolean.TRUE.equals(evaluateCondition(rule.getJSONObject("when"), context))) {
            return true;
        }

        // 读取规则引用的字段，暂时跳过未解析的占位符
        String scope = getString(rule, "scope", "current");
        List<?> rulePath = rule.containsKey("path") ? rule.getJSONArray("path") : List.of();
        Object rawTarget = getValue(context.scope(scope), rulePath);
        Object target = rawTarget == UNSET && "parent".equals(scope) ? getValue(context.defaults, rulePath) : rawTarget;
        if (context.defersPlaceholder(target, scope, rulePath)) {
            return true;
        }
        return switch (name) {
            // 校验必填及非空要求
            case "required" -> value != UNSET && value != null && !"".equals(value);
            case "nonBlank" -> value instanceof String text && StringUtils.isNotBlank(text);
            case "notEmpty" -> !isEmpty(value);

            // 校验数值范围、倍数及整数要求
            case "minimum" -> compare(value, ">=", rule.get("value"));
            case "maximum" -> compare(value, "<=", rule.get("value"));
            case "exclusiveMinimum" -> compare(value, ">", rule.get("value"));
            case "exclusiveMaximum" -> compare(value, "<", rule.get("value"));
            case "range" -> compare(value, ">=", rule.get("min")) && compare(value, "<=", rule.get("max"));
            case "multipleOf" -> value instanceof Number && rule.get("value") instanceof Number
                    && toDecimal(rule.get("value")).signum() > 0
                    && toDecimal(value).remainder(toDecimal(rule.get("value"))).signum() == 0;
            case "integer" -> value instanceof Number && toDecimal(value).stripTrailingZeros().scale() <= 0;
            case "finite" -> value instanceof Number && Double.isFinite(((Number) value).doubleValue());

            // 检查文本格式、长度和匹配结果
            case "trimmed" -> value instanceof String text && text.equals(text.strip());
            case "lowercase" -> value instanceof String text && text.equals(text.toLowerCase(Locale.ROOT));
            case "uppercase" -> value instanceof String text && text.equals(text.toUpperCase(Locale.ROOT));
            case "minLength" -> value instanceof String && compare(getSize(value), ">=", rule.get("value"));
            case "maxLength" -> value instanceof String && compare(getSize(value), "<=", rule.get("value"));
            case "length" -> value instanceof String && isEqual(getSize(value), rule.get("value"));
            case "pattern" -> value instanceof String text && matches(text, rule.getString("value"));

            // 检查候选值、指定格式和端口范围
            case "enum" -> rule.getJSONArray("values").stream().anyMatch(item -> isEqual(value, item));
            case "format" -> isValidFormat(value, rule.getString("value"));
            case "port" -> value instanceof Number && toDecimal(value).stripTrailingZeros().scale() <= 0
                    && compare(value, ">=", rule.getBooleanValue("allowZero") ? 0 : 1) && compare(value, "<=", 65535);

            // 校验集合中的匹配数量、大小及唯一性
            case "contains", "minContains", "maxContains" -> {
                if (!(value instanceof List<?> list)) {
                    yield false;
                }
                long count = list.stream().filter(item -> isEqual(item, rule.get("value"))).count();
                yield "contains".equals(name) ? count > 0 : compare(count, "minContains".equals(name) ? ">=" : "<=", rule.get("count"));
            }
            case "minItems", "maxItems", "minKeys", "maxKeys" -> (name.endsWith("Items") ? value instanceof List<?> : value instanceof Map<?, ?>)
                    && compare(getSize(value), name.startsWith("min") ? ">=" : "<=", rule.get("value"));
            case "uniqueItems" -> value instanceof List<?> list && hasUniqueItems(list);
            case "uniqueItemField" -> {
                if (!(value instanceof List<?> list)) {
                    yield false;
                }
                List<Object> fields = new ArrayList<>();
                for (Object item : list) {
                    Object field = getValue(item, rulePath);
                    if (field != UNSET) {
                        fields.add(field);
                    }
                }
                yield hasUniqueItems(fields);
            }

            // 检查对象中必需的键、允许的键和键名格式
            case "requiredKeys" -> value instanceof Map<?, ?> map && rule.getJSONArray("keys").stream().allMatch(map::containsKey);
            case "allowedKeys" -> value instanceof Map<?, ?> map && rule.getJSONArray("keys").containsAll(map.keySet());
            case "keyPattern" -> value instanceof Map<?, ?> map && map.keySet().stream().allMatch(key -> matches(key.toString(), rule.getString("value")));

            // 检查字段引用、字段间比较和取值是否重复
            case "keyInMap" -> rule.getBooleanValue("allowUnset") && isEmpty(value) || target instanceof Map<?, ?> map && map.containsKey(value);
            case "valueInField" -> target instanceof List<?> list && list.stream().anyMatch(item -> isEqual(value, item));
            case "equalTo" -> isEqual(value, target);
            case "notEqualTo" -> !isEqual(value, target);
            case "compare" -> compare(value, rule.getString("op"), target);
            case "uniqueAcross" -> {
                List<?> values = value instanceof List<?> list ? list : List.of(value);
                Iterable<?> others = target instanceof List<?> list ? list : target instanceof Map<?, ?> map ? map.values() : List.of();
                boolean valid = true;
                for (Object other : others) {
                    Object compared = rule.containsKey("itemPath") ? getValue(other, rule.getJSONArray("itemPath")) : other;
                    valid &= values.stream().noneMatch(item -> isEqual(item, compared));
                }
                yield valid;
            }

            // 检查指定字段填写后，关联字段是否也已填写
            case "dependentRequired" -> {
                boolean valid = true;
                for (Object item : rule.getJSONArray("dependencies")) {
                    JSONObject dependency = (JSONObject) item;
                    if (!isEmpty(getValue(value, dependency.getJSONArray("when")))) {
                        valid &= dependency.getJSONArray("require").stream().noneMatch(p -> isEmpty(getValue(value, (List<?>) p)));
                    }
                }
                yield valid;
            }

            // 校验时长范围及条件下的字段取值
            case "positiveDuration" -> {
                Duration duration = parseDuration(value);
                yield duration != null && duration.compareTo(Duration.ZERO) > 0;
            }
            case "durationRange" -> {
                Duration duration = parseDuration(value);
                Duration minimum = parseDuration(rule.get("min"));
                Duration maximum = parseDuration(rule.get("max"));
                yield duration != null && minimum != null && maximum != null
                        && duration.compareTo(minimum) >= 0 && duration.compareTo(maximum) <= 0;
            }
            case "nonEmptyOnlyWhen" -> isEmpty(getValue(value, rulePath)) || !Boolean.FALSE.equals(evaluateCondition(rule.getJSONObject("when"),
                    new Context(value, value, context.document, Map.of(), context.currentPath, context.currentPath, context.springPaths)));
            case "requiredWhen" -> !Boolean.TRUE.equals(evaluateCondition(rule.getJSONObject("when"), context)) || !isEmpty(value);
            case "forbiddenWhen" -> !Boolean.TRUE.equals(evaluateCondition(rule.getJSONObject("when"), context)) || isEmpty(value);

            // 根据已填写的字段数量检查是否存在互斥项或遗漏
            case "atLeastOne", "exactlyOne", "allOrNone", "mutuallyExclusive" -> {
                long count = rule.getJSONArray("paths").stream().filter(p -> !isEmpty(getValue(value, (List<?>) p))).count();
                yield switch (name) {
                    case "atLeastOne" -> count > 0;
                    case "exactlyOne" -> count == 1;
                    case "allOrNone" -> count == 0 || count == rule.getJSONArray("paths").size();
                    default -> count <= 1;
                };
            }
            default -> throw new IllegalArgumentException("未知校验规则: " + name);
        };
    }

    /**
     * 检查配置值是否符合指定格式
     * @param value 待检查值
     * @param format 格式名称
     * @return 是否符合格式
     */
    public static boolean isValidFormat(Object value, String format) {
        try {
            String text = String.valueOf(value);
            return switch (format) {
                // 校验时长和颜色格式
                case "duration" -> parseDuration(value) != null;
                case "color" -> value instanceof String && (text.matches("#?[0-9a-fA-F]{6}")
                        || text.matches("#[0-9a-fA-F]+") && Long.parseLong(text.substring(1), 16) <= Integer.MAX_VALUE
                        || text.matches("(?i)white|black|red|green|blue|yellow|orange|pink|gray|light_gray|dark_gray|cyan|magenta")
                        || text.matches("\\d{1,3}\\s*,\\s*\\d{1,3}\\s*,\\s*\\d{1,3}")
                        && java.util.Arrays.stream(text.split("\\s*,\\s*")).allMatch(part -> Integer.parseInt(part) <= 255));

                // 检查 HTTP 地址的协议、主机，以及是否有额外参数
                case "http-base-url" -> value instanceof String && !text.contains("?") && isHttpUrl(text);
                case "http-url" -> value instanceof String && isHttpUrl(text);
                case "listen-address" -> value instanceof String && isListenAddress(text);

                // 检查常见地址、标识和 JSON 文本格式
                case "url" -> value instanceof String && URI.create(text).isAbsolute();
                case "email" -> value instanceof String && text.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+");
                case "password" -> value instanceof String && PASSWORD_CHARACTERS.matcher(text).matches();
                case "uuid" -> value instanceof String && text.matches("(?i)[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}");
                case "ipv4" -> value instanceof String && text.split("\\.", -1).length == 4
                        && java.util.Arrays.stream(text.split("\\.", -1)).allMatch(part -> part.matches("\\d{1,3}") && Integer.parseInt(part) <= 255);
                case "hostname" -> value instanceof String && text.length() <= 253
                        && java.util.Arrays.stream(text.split("\\.", -1)).allMatch(part -> part.matches("(?i)[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"));
                case "json" -> value instanceof String && JSON.isValid(text);
                default -> false;
            };
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * 检查 HTTP 接口地址的写法
     * @param text 接口地址
     * @return 接口地址是否有效
     */
    private static boolean isHttpUrl(String text) {
        Matcher address = HTTP_URL.matcher(text);
        if (!address.matches() || INVALID_URL_ESCAPE.matcher(text).find()) {
            return false;
        }
        String port = address.group(2);
        if (port != null && (Integer.parseInt(port) == 0 || Integer.parseInt(port) > 65535)) {
            return false;
        }

        return isHostAddress(address.group(1));
    }

    /**
     * 检查监听地址，主机名需要带域名后缀，localhost 除外
     * @param text 监听地址
     * @return 地址格式是否有效
     */
    private static boolean isListenAddress(String text) {
        if (!text.contains(":")) {
            String hostname = Strings.CS.removeEnd(text, ".");
            return ("localhost".equalsIgnoreCase(hostname) || hostname.contains(".")) && isHostAddress(text);
        }
        String address = text.startsWith("[") && text.endsWith("]") ? text.substring(1, text.length() - 1) : text;
        int zone = address.indexOf('%');
        if (zone >= 0) {
            if (!address.substring(zone + 1).matches("[a-zA-Z0-9_.-]+")) {
                return false;
            }
            address = address.substring(0, zone);
        }
        return isHostAddress("[" + address + "]");
    }

    /**
     * 检查主机名、IPv4 地址或方括号中的 IPv6 地址
     * @param host 主机地址
     * @return 主机地址格式是否有效
     */
    private static boolean isHostAddress(String host) {
        if (host.matches("\\[[0-9a-fA-F:.]+]")) {
            int ipv4Start = host.lastIndexOf(':') + 1;
            return (!host.contains(".") || isIpv4Address(host.substring(ipv4Start, host.length() - 1)))
                    && URI.create("http://" + host).getHost() != null;
        }
        if (host.matches("[0-9.]+")) {
            return isIpv4Address(host);
        }
        String hostname = Strings.CS.removeEnd(host, ".");
        int lastDot = hostname.lastIndexOf('.');
        return isValidFormat(hostname, "hostname") && (lastDot < 0 || Character.isLetter(hostname.charAt(lastDot + 1)));
    }

    /**
     * 检查 IPv4 地址是否为标准四段写法
     * @param host IPv4 地址
     * @return IPv4 地址是否有效
     */
    private static boolean isIpv4Address(String host) {
        return host.matches("(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}")
                && isValidFormat(host, "ipv4");
    }

    /**
     * 检查正则表达式的写法和匹配复杂度
     * @param regex 正则
     */
    public static void validatePattern(String regex) {
        compilePattern(regex);
    }

    /**
     * 在限制范围内用正则匹配文本
     * @param value 待匹配文本
     * @param regex 正则表达式
     * @return 是否存在匹配
     */
    private static boolean matches(String value, String regex) {
        Pattern pattern = compilePattern(regex);
        if (value.length() > 4096) {
            return false;
        }
        return pattern.matcher(new BoundedText(value, new int[]{100000})).find();
    }

    /**
     * 检查正则表达式的限制并编译
     * @param regex 正则
     * @return 编译后的正则表达式
     */
    private static Pattern compilePattern(String regex) {
        if (regex == null || regex.length() > 256 || regex.contains("(?") || regex.matches(".*\\\\[1-9].*")
                || regex.matches(".*\\)[*+?{].*")) {
            throw new IllegalArgumentException("正则最多 256 字符, 不支持环视, 反向引用或对分组重复量化");
        }
        return Pattern.compile(regex);
    }

    /**
     * 检查列表中是否存在重复元素
     * @param list 待检查列表
     * @return 是否没有重复元素
     */
    private static boolean hasUniqueItems(List<?> list) {
        for (int i = 0; i < list.size(); i++) {
            for (int j = 0; j < i; j++) {
                if (isEqual(list.get(i), list.get(j))) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 将时长文本或毫秒数转换为 Duration
     * @param value 时长文本或毫秒数
     * @return 时长，格式或范围无效时返回 null
     */
    private static Duration parseDuration(Object value) {
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
     * 限长读取的正则匹配文本
     */
    private static final class BoundedText implements CharSequence {
        /**
         * 待匹配文本
         */
        private final String text;

        /**
         * 共享的剩余字符访问次数
         */
        private final int[] budget;

        private BoundedText(String text, int[] budget) {
            this.text = text;
            this.budget = budget;
        }

        /**
         * 获取正则输入文本长度
         * @return 字符数
         */
        @Override
        public int length() {
            return text.length();
        }

        /**
         * 读取一个字符并扣减可用次数
         * @param index 字符下标
         * @return 对应字符
         */
        @Override
        public char charAt(int index) {
            if (--budget[0] < 0) {
                throw new IllegalArgumentException("正则校验超过计算限制");
            }
            return text.charAt(index);
        }

        /**
         * 截取一段文本，继续共用剩余读取次数
         * @param start 起始下标
         * @param end 结束下标，不包含该位置
         * @return 子序列
         */
        @NotNull
        @Override
        public CharSequence subSequence(int start, int end) {
            return new BoundedText(text.substring(start, end), budget);
        }

        /**
         * 获取字符串
         * @return 字符串
         */
        @NotNull
        @Override
        public String toString() {
            return super.toString();
        }
    }
}
