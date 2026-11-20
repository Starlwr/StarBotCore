package com.starlwr.bot.core.config.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.starlwr.bot.core.config.SpringPropertyBridge;
import com.starlwr.bot.core.config.ConfigException;
import com.starlwr.bot.core.config.action.ConfigActionHandler;
import com.starlwr.bot.core.config.validation.ConfigRules;
import com.starlwr.bot.core.config.validation.ConfigValidationService;
import com.starlwr.bot.core.config.validation.ConfigValueUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 配置中心动作服务
 */
@Slf4j
public class ConfigActionService implements AutoCloseable {
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
     * 动作执行线程池
     */
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(0, 3, 60, TimeUnit.SECONDS,
            new SynchronousQueue<>(), runnable -> {
                Thread thread = new Thread(runnable, "starbot-config-action");
                thread.setDaemon(true);
                return thread;
            });

    /**
     * 动作任务映射
     */
    private final Map<List<String>, Task> tasks = new LinkedHashMap<>();

    /**
     * 动作处理器类加载器
     */
    private URLClassLoader classLoader;

    /**
     * 服务是否已关闭
     */
    private boolean closed;

    /**
     * 动作任务
     */
    private static class Task {
        /**
         * 任务 ID
         */
        private final String id = UUID.randomUUID().toString();

        /**
         * 动作执行完成后的页面响应
         */
        private final CompletableFuture<JSONObject> completion = new CompletableFuture<>();

        /**
         * 获取当前任务状态
         * @return 当前任务状态
         */
        private JSONObject getStatus() {
            return completion.getNow(JSONObject.of("id", id, "state", "running", "message", "正在执行, 请稍候"));
        }
    }

    public ConfigActionService(ConfigMetaRegistry registry, SpringPropertyBridge springPropertyBridge) {
        this.registry = registry;
        validation = new ConfigValidationService(registry, springPropertyBridge);
        this.springPropertyBridge = springPropertyBridge;
    }

    @Override
    public synchronized void close() throws IOException {
        closed = true;
        executor.shutdownNow();
        tasks.values().forEach(task -> task.completion.complete(JSONObject.of("id", task.id, "state", "failed", "message", "配置服务已停止")));
        tasks.clear();
        if (classLoader != null) {
            classLoader.close();
        }
    }

    /**
     * 执行动作
     * @param request 页面请求，包含模块 ID、分组 ID、动作 ID 和页面提交的配置
     * @return 同步执行结果或后台任务状态
     */
    public JSONObject execute(JSONObject request) {
        // 查找元数据中声明的模块、分组和动作
        String module = request.getString("module"), groupId = request.getString("group"), actionId = request.getString("action");
        JSONObject moduleMeta = registry.getMeta().stream().filter(item -> item.getString("id").equals(module)).findFirst()
                .orElseThrow(() -> new ConfigException(404, "配置动作不存在"));
        JSONObject group = moduleMeta.getJSONArray("groups").stream().map(item -> (JSONObject) item)
                .filter(item -> item.getString("id").equals(groupId)).findFirst()
                .orElseThrow(() -> new ConfigException(404, "配置动作不存在"));
        JSONArray actions = group.getJSONArray("actions");
        JSONObject action = CollectionUtils.isEmpty(actions) ? null : actions.stream().map(item -> (JSONObject) item)
                .filter(item -> item.getString("id").equals(actionId)).findFirst().orElse(null);
        if (action == null) {
            throw new ConfigException(404, "配置动作不存在");
        }

        // 根据动作元数据中的参数从页面提交的配置中筛选当前动作所需的参数
        if (!(request.get("configuration") instanceof JSONObject configuration)) {
            throw new ConfigException(422, "请提供当前页面的配置");
        }
        JSONObject parameters = (JSONObject) action.getOrDefault("parameters", new JSONObject());
        JSONObject normalized = new JSONObject(configuration);
        springPropertyBridge.normalizeAdvanced(normalized);
        JSONObject draft = selectConfiguration(normalized, parameters);

        // 拒绝使用声明本身有错误的字段，例如同一个必填字段没有各插件都接受的取值
        if (registry.getDeclarations().stream().anyMatch(item -> item.getField().getBooleanValue("conflicted") && !item.getField().getBooleanValue("typeConflict") && includesPath(parameters, item.getPath()))) {
            throw new ConfigException(422, "此操作所需的配置元数据不兼容, 请调整相关插件后再试");
        }

        // 同一高级配置的建议值或类型不一致时，需要手动解决冲突
        if (registry.hasUnresolvedConflicts(draft, path -> includesPath(parameters, path))) {
            throw new ConfigException(422, "此操作需要的参数配置存在冲突, 请先选择配置值");
        }

        // 检查业务配置和高级配置是否都是对象
        for (String area : List.of("starbot", "advanced")) {
            if (draft.containsKey(area) && !(draft.get(area) instanceof JSONObject)) {
                throw new ConfigException(422, area + " 必须是对象");
            }
        }

        // 在筛选后的配置中补充默认值并校验
        List<JSONObject> errors = validation.applyDefaultsAndValidate(draft).getJSONArray("errors").stream().map(item -> (JSONObject) item)
                .filter(item -> includesPath(parameters, item.getJSONArray("path"))).toList();
        if (CollectionUtils.isNotEmpty(errors)) {
            throw new ConfigException(422, "请先修正此操作需要的配置后再执行", JSONObject.of("errors", errors));
        }

        // 检查动作的可见条件
        JSONArray groupPath = group.getJSONArray("configPath");
        Object parent = ConfigValueUtil.getValue(draft, groupPath);
        if (action.containsKey("visibleWhen") && !Boolean.TRUE.equals(ConfigRules.evaluateCondition(action.getJSONObject("visibleWhen"),
                new ConfigRules.Context(parent, parent, draft, Map.of(), groupPath, groupPath, registry.getSpringBindings().values())))) {
            throw new ConfigException(422, "请先满足此操作的启用条件");
        }

        // 组装动作参数
        JSONObject arguments = new JSONObject();
        parameters.forEach((name, item) -> {
            JSONObject selector = (JSONObject) item;
            Object value = selectValue(draft, selector);
            if (selector.getBooleanValue("springProperties")) {
                JSONObject properties = new JSONObject(springPropertyBridge.properties(value, selectValue(normalized, selector)));
                properties.replaceAll((key, property) -> copyArgumentValue(property));
                value = properties;
            } else {
                value = copyArgumentValue(value);
            }
            arguments.put(name, value == ConfigValueUtil.UNSET ? null : value);
        });

        // 执行动作
        Task task;
        synchronized (this) {
            if (closed) {
                throw new ConfigException(503, "配置服务正在关闭, 请稍后重试");
            }
            List<String> key = List.of(module, groupId, actionId);
            task = tasks.get(key);
            if (task == null || task.completion.isDone()) {
                task = new Task();
                Task submitted = task;
                try {
                    executor.execute(() -> run(submitted, action, arguments, module, groupId));
                } catch (RejectedExecutionException e) {
                    throw new ConfigException(429, "正在执行的操作较多, 请稍后再试");
                }
                tasks.put(key, task);
            }
        }

        return action.getBooleanValue("synchronous") ? task.completion.join() : task.getStatus();
    }

    /**
     * 根据任务 ID 查询任务状态
     * @param id 任务 ID
     * @return 任务状态
     */
    public synchronized JSONObject getStatus(String id) {
        return tasks.values().stream()
                .filter(task -> task.id.equals(id)).findFirst().map(Task::getStatus)
                .orElseThrow(() -> new ConfigException(404, "执行记录已失效, 请重新操作"));
    }

    /**
     * 从页面提交的配置中筛选当前动作所需的参数
     * @param source 页面提交的配置
     * @param parameters 动作元数据中声明的参数
     * @return 筛选后的参数
     */
    private static JSONObject selectConfiguration(JSONObject source, JSONObject parameters) {
        JSONObject result = new JSONObject();
        for (Object item : parameters.values()) {
            JSONObject selector = (JSONObject) item;
            Object value = selectValue(source, selector);
            if (value == ConfigValueUtil.UNSET) {
                continue;
            }

            Object copy = copyArgumentValue(value);
            List<?> path = parameterPath(selector);
            JSONObject parent = result;
            for (int index = 0; index < path.size() - 1; index++) {
                String key = (String) path.get(index);
                if (!parent.containsKey(key)) {
                    parent.put(key, new JSONObject());
                }
                parent = parent.getJSONObject(key);
            }
            String key = (String) path.get(path.size() - 1);
            if (parent.get(key) instanceof JSONObject existing && copy instanceof JSONObject additional) {
                existing.putAll(additional);
            } else {
                parent.put(key, copy);
            }
        }
        return result;
    }

    /**
     * 判断动作元数据中声明的参数配置路径是否匹配指定配置路径
     * @param parameters 动作元数据中声明的参数
     * @param path 待检查的配置路径
     * @return 是否匹配
     */
    private static boolean includesPath(JSONObject parameters, List<?> path) {
        for (Object item : parameters.values()) {
            JSONObject selector = (JSONObject) item;
            List<?> selected = parameterPath(selector);
            int length = Math.min(selected.size(), path.size());
            if (selected.subList(0, length).equals(path.subList(0, length))
                    && (path.size() <= selected.size() || matchesParameterKey(selector, path.get(selected.size()).toString()))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 深拷贝动作参数中的对象和列表
     * @param value 动作参数
     * @return 对象或列表的副本，其他值直接返回
     */
    private static Object copyArgumentValue(Object value) {
        return value instanceof Map<?, ?> || value instanceof List<?>
                ? JSON.parse(JSON.toJSONString(value, JSONWriter.Feature.WriteNulls)) : value;
    }

    /**
     * 调用动作处理器
     * @param task 动作任务
     * @param action 动作元数据
     * @param arguments 动作参数
     * @param module 模块 ID
     * @param group 分组 ID
     */
    private void run(Task task, JSONObject action, JSONObject arguments, String module, String group) {
        try {
            Class<?> type = Class.forName(action.getString("handler"), true, getClassLoader());
            ConfigActionHandler handler = type.asSubclass(ConfigActionHandler.class).getConstructor().newInstance();
            String message = handler.execute(arguments);
            task.completion.complete(JSONObject.of("id", task.id, "state", "succeeded", "message",
                    StringUtils.isBlank(message) ? "执行成功" : message));
        } catch (Exception | LinkageError e) {
            log.error("配置动作执行失败: {}/{}/{}", module, group, action.getString("id"), e);
            Throwable failure = e instanceof InvocationTargetException invocation && invocation.getCause() != null ? invocation.getCause() : e;
            String message = "执行出错, 详情请查看 StarBot 控制台: " + failure.getClass().getName()
                    + (StringUtils.isBlank(failure.getMessage()) ? "" : ": " + failure.getMessage());
            task.completion.complete(JSONObject.of("id", task.id, "state", "failed", "message", message));
        }
    }

    /**
     * 根据动作参数元数据中的路径从页面提交的配置中读取配置值
     * @param source 页面提交的配置
     * @param selector 动作参数元数据
     * @return 从页面提交的配置中读取到的配置值，不存在时返回 UNSET
     */
    private static Object selectValue(JSONObject source, JSONObject selector) {
        // 根据配置路径取值，区分未填写和显式空值
        Object value = source;
        for (Object part : parameterPath(selector)) {
            if (!(value instanceof Map<?, ?> map)) {
                throw new ConfigException(422, "配置路径的上级需要是对象, 请检查相关配置");
            }
            value = map.containsKey(part) ? map.get(part) : ConfigValueUtil.UNSET;
            if (value == ConfigValueUtil.UNSET) {
                return ConfigValueUtil.UNSET;
            }
        }

        // 根据完整键名或键名前缀筛选字段
        if (selector.containsKey("keys") || selector.containsKey("keyPrefix")) {
            if (!(value instanceof Map<?, ?> map)) {
                throw new ConfigException(422, "按键名筛选的配置需要是对象");
            }
            JSONObject selected = new JSONObject();
            map.forEach((key, item) -> {
                if (matchesParameterKey(selector, key.toString())) {
                    selected.put(key.toString(), item);
                }
            });
            return selected;
        }
        return value;
    }

    /**
     * 获取动作参数的配置路径
     * @param selector 动作参数元数据
     * @return 动作参数的配置路径
     */
    private static List<?> parameterPath(JSONObject selector) {
        return selector.getBooleanValue("springProperties") ? List.of("advanced") : selector.getJSONArray("path");
    }

    /**
     * 判断键名是否符合动作参数的筛选条件
     * @param selector 动作参数元数据
     * @param key 配置键名
     * @return 键名是否符合动作参数的筛选条件
     */
    private static boolean matchesParameterKey(JSONObject selector, String key) {
        if (selector.containsKey("keys")) {
            return selector.getJSONArray("keys").contains(key);
        }

        String prefix = selector.getString("keyPrefix");
        return prefix == null || key.startsWith(prefix);
    }

    /**
     * 获取动作处理器类加载器
     * @return 动作处理器类加载器
     */
    private synchronized ClassLoader getClassLoader() throws IOException {
        if (closed) {
            throw new IllegalStateException("配置服务已关闭");
        }

        if (classLoader == null) {
            List<URL> urls = new ArrayList<>();
            for (Path directory : List.of(registry.getPlugins(), Path.of("lib"), Path.of("plugins-lib"))) {
                if (Files.isDirectory(directory)) {
                    try (java.util.stream.Stream<Path> files = Files.list(directory)) {
                        for (Path jar : files.filter(file -> file.toString().endsWith(".jar")).sorted().toList()) {
                            urls.add(jar.toUri().toURL());
                        }
                    }
                }
            }
            classLoader = new URLClassLoader(urls.toArray(URL[]::new), getClass().getClassLoader());
        }

        return classLoader;
    }
}
