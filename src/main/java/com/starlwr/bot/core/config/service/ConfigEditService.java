package com.starlwr.bot.core.config.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.starlwr.bot.core.config.ConfigService;
import com.starlwr.bot.core.config.SpringPropertyBridge;
import com.starlwr.bot.core.config.security.ConfigPasswordUtil;
import com.starlwr.bot.core.config.ConfigException;
import com.starlwr.bot.core.config.validation.ConfigValidationService;
import com.starlwr.bot.core.config.validation.ConfigValueUtil;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.apache.commons.collections4.CollectionUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 配置中心编辑服务
 */
public class ConfigEditService {
    /**
     * 允许编辑的配置原文最大字节数
     */
    private static final int MAX_BYTES = 16 * 1024 * 1024;

    /**
     * 配置管理服务
     */
    private final ConfigService configService;

    /**
     * 配置元数据注册表
     */
    private final ConfigMetaRegistry registry;

    /**
     * 配置校验服务
     */
    private final ConfigValidationService validation;

    /**
     * Spring 属性桥接器
     */
    private final SpringPropertyBridge springPropertyBridge;

    /**
     * 自定义配置改名记录列表
     */
    private List<CustomRename> savedRenames = List.of();

    /**
     * 保存改名记录时的配置原文
     */
    private String renameDocument;

    /**
     * 自定义配置改名记录
     */
    @Getter
    @RequiredArgsConstructor
    private static class CustomRename {
        /**
         * 改名前的配置路径列表
         */
        private final List<?> from;

        /**
         * 改名后的配置路径列表
         */
        private final List<?> to;

        /**
         * 生成返回给页面的改名记录
         * @return 包含改名前后路径的 JSON 对象
         */
        private JSONObject toJson() {
            return JSONObject.of("from", from, "to", to);
        }
    }

    public ConfigEditService(ConfigService configService, SpringPropertyBridge springPropertyBridge) {
        this.configService = configService;
        registry = configService.getRegistry();
        validation = new ConfigValidationService(registry, springPropertyBridge);
        this.springPropertyBridge = springPropertyBridge;
    }

    /**
     * 读取完整配置
     * @return 包含配置原文、校验结果、生效状态、改名记录等的完整配置
     */
    public synchronized JSONObject read() throws IOException {
        boolean exists = Files.exists(configService.getPath());
        JSONObject document = buildDocument(readRaw(exists), exists);
        document.put("customRenames", savedRenames.stream().map(CustomRename::toJson).toList());
        return document;
    }

    /**
     * 生成 JSON 预览
     * @param request 页面提交的 JSON 原文或表单修改
     * @return 预览草稿及校验结果
     */
    public JSONObject preview(JSONObject request) throws IOException {
        String original;
        synchronized (this) {
            original = readRaw(Files.exists(configService.getPath()));
        }

        // 检查修改后的 JSON 结构，整理后再校验字段并组装预览结果
        String raw = applyChanges(original, request);
        raw = configService.normalizeForPreview(parse(raw));
        JSONObject draft = buildDocument(raw, true);
        return JSONObject.of("json", raw, "draft", draft, "errors", draft.get("errors"), "warnings", draft.get("warnings"));
    }

    /**
     * 校验并保存页面修改
     * @param request 页面提交的 JSON 原文或表单修改
     * @return 保存后的配置、文件是否有变化及是否需要重启
     */
    public synchronized JSONObject save(JSONObject request) throws IOException {
        AtomicReference<String> previousRaw = new AtomicReference<>();
        boolean changed = configService.update(original -> {
            previousRaw.set(original);
            String raw = applyChanges(original, request);
            validateBeforeSave(raw);
            return raw;
        });
        String raw = Files.readString(configService.getPath(), StandardCharsets.UTF_8);
        JSONObject document = buildDocument(raw, true);
        savedRenames = retainRenames(request, previousRaw.get(), document);
        renameDocument = CollectionUtils.isEmpty(savedRenames) ? null : raw;
        document.put("customRenames", savedRenames.stream().map(CustomRename::toJson).toList());
        List<?> pendingPaths = (List<?>) document.get("pendingPaths");
        return JSONObject.of("document", document, "unchanged", !changed, "restartRequired", CollectionUtils.isNotEmpty(pendingPaths),
                "errors", new JSONArray(), "warnings", new JSONArray());
    }

    /**
     * 校验待保存的配置，校验失败时附带页面所需的错误详情
     * @param raw 待保存的配置原文
     */
    private void validateBeforeSave(String raw) {
        try {
            JSONObject data = parseEditorData(raw);
            JSONObject validationResult = validation.validate(data);
            if (CollectionUtils.isNotEmpty(validationResult.getJSONArray("errors"))) {
                JSONObject details = createDocumentResponse(raw, true);
                addDocumentData(details, data);
                details.putAll(validationResult);
                throw new ConfigException(422, "配置校验未通过", details);
            }
        } catch (ConfigException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ConfigException(422, "JSON 格式错误, 请在原文中修正");
        }
    }

    /**
     * 读取配置原文，文件内容变化时清除改名记录
     * @param exists 配置文件是否存在
     * @return 配置原文
     */
    private String readRaw(boolean exists) throws IOException {
        String raw = exists ? Files.readString(configService.getPath(), StandardCharsets.UTF_8) : "{}";
        if (renameDocument != null && !raw.equals(renameDocument)) {
            savedRenames = List.of();
            renameDocument = null;
        }
        return raw;
    }

    /**
     * 构建页面所需的配置数据及校验结果
     * @param raw 配置原文
     * @param exists 文件是否存在
     * @return 页面所需的配置数据及校验结果
     */
    private JSONObject buildDocument(String raw, boolean exists) {
        JSONObject result = createDocumentResponse(raw, exists);
        try {
            JSONObject data = parseEditorData(raw);
            addDocumentData(result, data);
            result.put("accessPassword", JSONObject.of(
                    "stored", ConfigPasswordUtil.isEncoded(ConfigValueUtil.getValue(data, ConfigPasswordUtil.PASSWORD_PATH)),
                    "active", ConfigPasswordUtil.isEncoded(ConfigValueUtil.getValue(result.get("activeData"), ConfigPasswordUtil.PASSWORD_PATH))));
            result.putAll(validation.validate(data));
        } catch (RuntimeException e) {
            if (!result.containsKey("conflicts")) {
                result.put("conflicts", registry.getConflicts(new JSONObject()));
            }
            result.put("error", e instanceof ConfigException ? e.getMessage() : "JSON 格式错误, 请在原文中修正");
        }
        return result;
    }

    /**
     * 解析编辑用的配置并统一高级配置键名
     * @param raw 配置原文
     * @return 解析后的配置对象
     */
    private JSONObject parseEditorData(String raw) {
        JSONObject data = parse(raw);
        springPropertyBridge.normalizeAdvanced(data);
        return data;
    }

    /**
     * 组装配置原文和当前生效状态
     * @param raw 配置原文
     * @param exists 文件是否存在
     * @return 配置页面的基础响应
     */
    private JSONObject createDocumentResponse(String raw, boolean exists) {
        JSONObject response = JSONObject.of("file", configService.getPath().toString(), "exists", exists,
                "raw", raw, "index", 0, "count", 1, "data", new JSONObject(), "inherited", new JSONObject(),
                "errors", new JSONArray(), "warnings", new JSONArray(), "pendingPaths", configService.getPendingPaths(),
                "activeData", configService.getActiveValues(),
                "metaErrors", registry.hasMetaErrors());
        if (configService.getStartupFormatError() != null) {
            response.put("rawRecovery", true);
        }
        return response;
    }

    /**
     * 补充表单数据、配置冲突和整数精度检查结果
     * @param response 配置页面响应
     * @param data 解析后的配置对象
     */
    private void addDocumentData(JSONObject response, JSONObject data) {
        response.put("data", data);
        response.put("conflicts", registry.getConflicts(data));
        response.put("unsafeNumbers", ConfigValueUtil.hasUnsafeInteger(data));
    }

    /**
     * 根据原文或表单操作生成配置草稿
     * @param original 编辑前的配置原文
     * @param request 页面提交的 JSON 原文或表单修改
     * @return 修改后的配置原文
     */
    private String applyChanges(String original, JSONObject request) {
        // 原文编辑直接使用提交的文本，表单编辑从原配置开始
        if (request.containsKey("raw")) {
            return request.getString("raw");
        }
        JSONObject data = parseEditorData(request.containsKey("baseRaw") ? request.getString("baseRaw") : original);

        // 依次处理表单中的设置、删除和改名操作
        Object changes = request.get("changes");
        if (changes != null) {
            if (!(changes instanceof List<?> operations)) {
                throw new ConfigException(422, "表单修改必须是操作列表");
            }
            for (Object item : operations) {
                if (!(item instanceof JSONObject change)) {
                    throw new ConfigException(422, "表单修改中的每项操作必须是对象");
                }
                List<?> path = change.getJSONArray("path");
                checkPath(path);
                String operation = change.getString("op");
                if ("move".equals(operation)) {
                    List<?> from = change.getJSONArray("from");
                    checkPath(from);
                    if (!from.subList(0, from.size() - 1).equals(path.subList(0, path.size() - 1))) {
                        throw new ConfigException(422, "仅支持在同一对象内重命名字段");
                    }
                    Object parent = getParent(data, from, false);
                    if (!(parent instanceof Map<?, ?> map) || !map.containsKey(from.get(from.size() - 1))
                            || map.containsKey(path.get(path.size() - 1))) {
                        throw new ConfigException(422, "重命名的原字段不存在或目标字段已存在");
                    }
                    Object value = map.get(from.get(from.size() - 1));
                    assign(parent, from.get(from.size() - 1), null, true);
                    assign(parent, path.get(path.size() - 1), value, false);
                } else if ("set".equals(operation) || "unset".equals(operation)) {
                    Object parent = getParent(data, path, "set".equals(operation));
                    if (parent != null) {
                        assign(parent, path.get(path.size() - 1), change.get("value"), "unset".equals(operation));
                    }
                } else {
                    throw new ConfigException(422, "不支持的编辑操作");
                }
            }
        }

        // 生成新的 JSON 原文，保留明确填写的 null
        return JSON.toJSONString(data, JSONWriter.Feature.PrettyFormat, JSONWriter.Feature.WriteNulls);
    }

    /**
     * 保留自定义键改名记录
     * @param request 页面保存请求
     * @param original 保存前的配置原文
     * @param document 保存后的表单数据及当前生效配置
     * @return 可用于恢复当前生效值的改名记录列表
     */
    private List<CustomRename> retainRenames(JSONObject request, String original, JSONObject document) {
        // 仅在文件内容未发生变化时保留上次保存的改名记录
        List<CustomRename> previous = original.equals(renameDocument) ? savedRenames : List.of();
        JSONArray supplied = request.getJSONArray("customRenames");
        if ((supplied == null ? CollectionUtils.isEmpty(previous) : CollectionUtils.isEmpty(supplied)) || document.containsKey("error")) {
            return List.of();
        }
        JSONObject before;
        try {
            before = parse(original);
        } catch (RuntimeException e) {
            return List.of();
        }

        // 核对旧键仍有生效值，且保存后的配置只包含新键
        Object after = document.get("data"), active = document.get("activeData");
        List<CustomRename> result = new ArrayList<>();
        Set<List<?>> usedFrom = new HashSet<>(), usedTo = new HashSet<>();
        for (CustomRename rename : supplied == null ? previous : parseRenames(supplied)) {
            if (!exists(active, rename.getFrom()) || exists(after, rename.getFrom()) || !exists(after, rename.getTo())) {
                continue;
            }

            // 保留目标未变的记录，或根据保存前的配置确认本次改名
            if (supplied == null) {
                if (!ConfigValueUtil.isEqual(ConfigValueUtil.getValue(before, rename.getTo()), ConfigValueUtil.getValue(after, rename.getTo()))) {
                    continue;
                }
            } else {
                CustomRename earlier = previous.stream().filter(item -> item.getFrom().equals(rename.getFrom())).findFirst().orElse(null);
                List<?> sourcePath = earlier == null ? rename.getFrom() : earlier.getTo();
                if (!sourcePath.equals(rename.getTo()) && (!exists(before, sourcePath) || exists(before, rename.getTo()))) {
                    continue;
                }
            }

            // 每个旧键和新键只能各出现一次
            if (!usedFrom.contains(rename.getFrom()) && !usedTo.contains(rename.getTo())) {
                usedFrom.add(rename.getFrom());
                usedTo.add(rename.getTo());
                result.add(rename);
            }
        }
        return List.copyOf(result);
    }

    /**
     * 检查原文大小和根对象结构，并解析配置文档
     * @param raw 配置原文
     * @return 配置根对象
     */
    private static JSONObject parse(String raw) {
        if (raw == null || raw.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new ConfigException(422, "配置文件不能为空或超过 16 MiB");
        }
        JSONObject object;
        try {
            object = ConfigValueUtil.parseDocument(raw);
        } catch (IllegalArgumentException e) {
            throw new ConfigException(422, e.getMessage());
        } catch (RuntimeException e) {
            throw new ConfigException(422, "JSON 格式错误, 请在原文中修正");
        }
        for (String key : List.of("starbot", "advanced")) {
            if (object.containsKey(key) && !(object.get(key) instanceof Map<?, ?>)) {
                throw new ConfigException(422, key + " 必须是对象");
            }
        }
        return object;
    }

    /**
     * 检查编辑路径是否由有效的键名和下标组成
     * @param path 编辑路径
     */
    private static void checkPath(List<?> path) {
        if (CollectionUtils.isEmpty(path) || path.size() > 64
                || path.stream().anyMatch(part -> !(part instanceof String) && !(part instanceof Integer))) {
            throw new ConfigException(422, "配置路径必须由键名或列表下标组成");
        }
    }

    /**
     * 查找编辑路径的父节点，并根据路径创建缺失的对象或列表
     * @param data 配置根对象
     * @param path 编辑路径
     * @param create 是否创建缺失节点
     * @return 父节点，未创建且路径缺失时返回 null
     */
    private static Object getParent(Object data, List<?> path, boolean create) {
        Object current = data;
        for (int index = 0; index < path.size() - 1; index++) {
            Object key = path.get(index);
            Object child = ConfigValueUtil.getValue(current, List.of(key));
            if (child == ConfigValueUtil.UNSET) {
                if (!create) {
                    return null;
                }
                child = path.get(index + 1) instanceof Integer ? new JSONArray() : new JSONObject();
                assign(current, key, child, false);
            }
            if (!(child instanceof Map<?, ?>) && !(child instanceof List<?>)) {
                throw new ConfigException(422, "字段所属节点不是对象或列表");
            }
            current = child;
        }
        return current;
    }

    /**
     * 写入或删除对象字段和列表元素
     * @param parent 所属对象或列表
     * @param key 字段名或列表下标
     * @param value 新值
     * @param remove 是否删除
     */
    @SuppressWarnings("unchecked")
    private static void assign(Object parent, Object key, Object value, boolean remove) {
        if (parent instanceof Map<?, ?> map && key instanceof String) {
            if (remove) {
                map.remove(key);
            } else {
                ((Map<Object, Object>) map).put(key, value);
            }
        } else if (parent instanceof List<?> list && key instanceof Integer index && index >= 0 && index <= list.size()) {
            List<Object> values = (List<Object>) list;
            if (remove && index < values.size()) {
                values.remove((int) index);
            } else if (!remove && index == values.size()) {
                values.add(value);
            } else if (!remove) {
                values.set(index, value);
            }
        } else {
            throw new ConfigException(422, "配置路径与当前节点类型不匹配");
        }
    }

    /**
     * 从保存请求中读取同一层级内的改名记录
     * @param supplied 页面提交的改名记录
     * @return 路径格式有效的改名记录
     */
    private static List<CustomRename> parseRenames(JSONArray supplied) {
        List<CustomRename> result = new ArrayList<>();
        for (Object item : supplied) {
            if (!(item instanceof JSONObject rename) || !(rename.get("from") instanceof List<?> from)
                    || !(rename.get("to") instanceof List<?> to) || from.size() < 2 || from.size() > 64 || from.size() != to.size()
                    || from.stream().anyMatch(part -> !(part instanceof String)) || to.stream().anyMatch(part -> !(part instanceof String))
                    || !List.of("advanced", "starbot").contains(from.get(0)) || from.equals(to)
                    || !from.subList(0, from.size() - 1).equals(to.subList(0, to.size() - 1))) {
                continue;
            }
            result.add(new CustomRename(List.copyOf(from), List.copyOf(to)));
        }
        return result;
    }

    /**
     * 检查指定路径是否存在，值为 null 也算存在
     * @param document 配置文档
     * @param path 待查找的配置路径
     * @return 指定路径是否存在
     */
    private static boolean exists(Object document, List<?> path) {
        return ConfigValueUtil.getValue(document, path) != ConfigValueUtil.UNSET;
    }
}
