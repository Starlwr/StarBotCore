package com.starlwr.bot.core.config;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONException;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.starlwr.bot.core.config.security.ConfigPasswordUtil;
import com.starlwr.bot.core.config.service.ConfigMetaRegistry;
import com.starlwr.bot.core.config.validation.ConfigValueUtil;
import com.starlwr.bot.core.event.config.StarBotConfigChangedEvent;
import com.starlwr.bot.core.util.FileChangeWatcher;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationEventPublisherAware;
import org.springframework.context.event.EventListener;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.util.ReflectionUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.UnaryOperator;

/**
 * 配置管理服务
 */
@Slf4j
public class ConfigService implements ApplicationEventPublisherAware {
    /**
     * 字段未设置时的标记，区别于明确填写的 null
     */
    private static final Object ABSENT = new Object();

    /**
     * 配置文件的绝对路径
     */
    @Getter
    private final Path path;

    /**
     * 配置元数据注册表
     */
    @Getter
    private final ConfigMetaRegistry registry;

    /**
     * Map 配置路径集合
     */
    private final Set<List<String>> mapPaths;

    /**
     * 配置分组路径集合
     */
    private final Set<List<Object>> sectionPaths;

    /**
     * 配置文件写入和重载锁
     */
    private final Object lock = new Object();

    /**
     * 配置默认值映射
     */
    private final Map<String, Object> defaults = new LinkedHashMap<>();

    /**
     * Spring 属性桥接器
     */
    private final SpringPropertyBridge springPropertyBridge;

    /**
     * 配置前缀与配置类映射
     */
    private final Map<String, Class<?>> types = new LinkedHashMap<>();

    /**
     * Spring 启动配置值映射
     */
    private final Map<String, Object> springValues = new LinkedHashMap<>();

    /**
     * 是否已读取 Spring 的启动配置
     */
    private boolean springInitialized;

    /**
     * 插件配置类加载器
     */
    private final URLClassLoader configurationClassLoader;

    /**
     * 当前生效配置映射
     */
    private volatile Map<String, Object> values = Map.of();

    /**
     * 文件配置映射
     */
    private Map<String, Object> document;

    /**
     * 已生效的文件配置映射
     */
    private Map<String, Object> activeDocument;

    /**
     * Spring 事件发布器
     */
    private ApplicationEventPublisher publisher;

    /**
     * 文件变更监听器
     */
    private FileChangeWatcher fileWatcher;

    /**
     * 启动时发现的重复 Spring 属性列表
     */
    @Getter
    private final List<SpringPropertyBridge.Duplicate> startupDuplicates;

    /**
     * 读取文件时是否清空了无效的配置中心访问密码
     */
    @Getter
    private volatile boolean passwordReset;

    public ConfigService(Path path, ConfigMetaRegistry registry, SpringPropertyBridge springPropertyBridge, boolean editing) {
        // 准备配置路径和插件配置类加载器
        this.path = path.toAbsolutePath().normalize();
        this.registry = registry;
        mapPaths = registry.getMapPaths();
        Set<List<Object>> fieldPaths = new HashSet<>();
        Set<List<Object>> sections = new HashSet<>();
        for (ConfigMetaRegistry.Declaration declaration : registry.getDeclarations()) {
            List<Object> fieldPath = declaration.getPath();
            fieldPaths.add(fieldPath);
            for (int length = 1; length < fieldPath.size(); length++) {
                sections.add(fieldPath.subList(0, length));
            }
        }
        sections.removeAll(fieldPaths);
        sections.add(List.of("starbot"));
        sections.add(List.of("advanced"));
        sectionPaths = Set.copyOf(sections);
        this.springPropertyBridge = springPropertyBridge;
        configurationClassLoader = createConfigurationClassLoader();

        try {
            // 在首次读取前注册监听，独立模式可以打开损坏的原文
            Files.createDirectories(this.path.getParent());
            fileWatcher = new FileChangeWatcher("config", this.path);
            try {
                document = Files.exists(this.path) ? readDocument(false) : new LinkedHashMap<>();
            } catch (IOException e) {
                if (!editing) {
                    throw new UncheckedIOException("无法读取配置文件 " + this.path + ": " + e.getMessage(), e);
                }
                document = new LinkedHashMap<>();
            }

            // 保留文件中的重复配置供页面修正，只将没有重复的属性用于启动
            startupDuplicates = List.copyOf(springPropertyBridge.duplicates(document.get("advanced")));
            activeDocument = cast(ConfigValueConverter.toTree(document));
            if (CollectionUtils.isNotEmpty(startupDuplicates)) {
                springPropertyBridge.excludeDuplicates(cast(activeDocument.get("advanced")), startupDuplicates);
            }

            // 加载 Core 和插件的字段初值，以及高级配置建议
            registerDefaults("advanced", registry.getAdvancedDefaults());
            for (Map.Entry<String, String> entry : registry.getConfigurationClasses().entrySet()) {
                try {
                    Class<?> type = Class.forName(entry.getValue(), false, configurationClassLoader);
                    StarBotConfiguration configuration = type.getAnnotation(StarBotConfiguration.class);
                    if (configuration == null || !configuration.prefix().equals(entry.getKey())) {
                        registry.configurationError(entry.getValue());
                        continue;
                    }
                    registerDefaults(entry.getKey(), type.getDeclaredConstructor().newInstance());
                } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
                    registry.configurationError(entry.getValue());
                }
            }

            // 补充页面字段和新建集合条目的默认值
            initializeMetaDefaults();
        } catch (IOException e) {
            closeResources();
            throw new UncheckedIOException("无法监听配置文件 " + this.path + ": " + e.getMessage(), e);
        } catch (RuntimeException | Error e) {
            closeResources();
            throw e;
        }
    }

    @Override
    public void setApplicationEventPublisher(@NonNull ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    @Order(0)
    @EventListener(ApplicationReadyEvent.class)
    public void startWatching() {
        synchronized (lock) {
            if (fileWatcher != null) {
                fileWatcher.start(this::reloadAfterExternalChange);
            }
        }
    }

    @PreDestroy
    public void destroy() {
        synchronized (lock) {
            closeResources();
        }
    }

    /**
     * 登记配置类的字段初值
     * @param key 配置前缀
     * @param value 默认 DTO
     */
    public void registerDefaults(String key, Object value) {
        checkKey(key);
        Objects.requireNonNull(value, "默认配置不能为空");
        if (registry.hasConfigurationClassConflict(key)) {
            return;
        }
        synchronized (lock) {
            if (types.containsKey(key)) {
                types.put(key, value.getClass());
                return;
            }
            put(defaults, parsePath(key, defaults), ConfigValueConverter.toTree(value));
            types.put(key, value.getClass());
            values = resolve(activeDocument);
        }
    }

    /**
     * 获取启动时要交给 Spring 的属性
     * @return 展开后的 Spring 属性映射
     */
    public Map<String, Object> getSpringProperties() {
        synchronized (lock) {
            Map<String, Object> properties = new LinkedHashMap<>();
            registry.getSpringBindings().forEach((key, path) -> SpringPropertyBridge.addBinding(key, get(String.join(".", path)), properties));
            properties.putAll(springPropertyBridge.properties(values.get("advanced"), activeDocument.get("advanced")));
            return properties;
        }
    }

    /**
     * 检查配置文件中是否存在建议值或类型不一致且未填写的高级配置
     * @return 是否还有需要用户选值或自行填写的配置
     */
    public boolean hasUnresolvedConflicts() {
        synchronized (lock) {
            return registry.hasUnresolvedConflicts(document);
        }
    }

    /**
     * 根据配置键读取当前生效值，返回独立副本
     * @param key 完整配置键
     * @return 当前配置值，未设置时返回 null
     */
    public Object get(String key) {
        return get(key, Object.class);
    }

    /**
     * 根据配置键读取当前生效值，返回指定类型的副本
     * @param key 完整配置键
     * @param type 目标类型
     * @param <T> 目标值类型
     * @return 配置副本，未设置时返回 null
     */
    public <T> T get(String key, Class<T> type) {
        return convert(findEffectiveValue(key), type);
    }

    /**
     * 根据配置键和完整类型读取当前生效值的副本
     * @param key 完整配置键
     * @param type 目标类型，可包含集合元素的类型
     * @return 配置副本，未设置时返回 null
     */
    public Object get(String key, Type type) {
        return ConfigValueConverter.convert(findEffectiveValue(key), type);
    }

    /**
     * 记录 Spring 启动时实际采用的配置值
     * @param environment 启动环境
     */
    public void initializeSpring(Environment environment) {
        synchronized (lock) {
            if (springInitialized) {
                return;
            }
            for (Map.Entry<String, List<String>> entry : registry.getSpringBindings().entrySet()) {
                Object configured = find(values, entry.getValue());
                if (configured instanceof Map<?, ?> || configured instanceof List<?>) {
                    continue;
                }
                Class<?> type = configured == null || configured == ABSENT ? String.class : configured.getClass();
                Object value = environment.getProperty(entry.getKey(), type);
                if (value != null) {
                    put(springValues, entry.getValue(), ConfigValueConverter.toTree(value));
                }
            }
            springInitialized = true;
            values = resolve(activeDocument);
        }
    }

    /**
     * 判断是否有配置等待重启生效
     * @return 是否存在待重启变更
     */
    public boolean hasPendingChanges() {
        synchronized (lock) {
            return !ConfigValueUtil.isEqual(merge(List.of(), defaults, activeDocument), merge(List.of(), defaults, document));
        }
    }

    /**
     * 获取待重启的配置路径
     * @return 待重启配置的路径，键名中的点号保留在同一段
     */
    public List<List<String>> getPendingPaths() {
        synchronized (lock) {
            List<List<String>> paths = new ArrayList<>();
            collectPendingPaths(List.of(), merge(List.of(), defaults, activeDocument), merge(List.of(), defaults, document), paths);
            return List.copyOf(paths);
        }
    }

    /**
     * 获取当前生效配置的独立副本
     * @return 当前配置
     */
    public Map<String, Object> getActiveValues() {
        return cast(ConfigValueConverter.toTree(values));
    }

    /**
     * 保存一项配置并重新读取文件
     * @param key 完整配置键
     * @param value 配置值，允许显式 null
     */
    public void set(String key, Object value) {
        checkKey(key);
        Object copied = ConfigValueConverter.toTree(value);
        update(raw -> {
            Map<String, Object> updated = parseDocument(raw);
            springPropertyBridge.normalizeAdvanced(updated);
            List<String> parts = parsePath(key, updated);
            for (List<String> mapPath : mapPaths) {
                if (parts.size() > mapPath.size() && parts.subList(0, mapPath.size()).equals(mapPath)
                        && find(updated, mapPath) == ABSENT && findDefault(mapPath) instanceof Map<?, ?> defaultMap) {
                    put(updated, mapPath, defaultMap);
                }
            }
            put(updated, parts, copied);
            return JSON.toJSONString(updated, JSONWriter.Feature.WriteNulls);
        });
    }

    /**
     * 更新配置文件并重新加载发生变化的内容
     * @param edit 配置原文编辑函数
     * @return 是否实际写入文件
     */
    public boolean update(UnaryOperator<String> edit) {
        // 加锁后应用编辑，省略可以恢复的默认值
        boolean changed;
        synchronized (lock) {
            try {
                String original = Files.exists(path) ? Files.readString(path, StandardCharsets.UTF_8) : "{}";
                String formatted = normalize(edit.apply(original));
                Map<String, Object> normalized = parseDocument(formatted);

                // 对比编辑前后的内容，有变化时才写入文件
                Map<String, Object> previous;
                try {
                    previous = JSON.parseObject(original);
                } catch (RuntimeException e) {
                    previous = null;
                }
                changed = !normalized.equals(previous) || !Files.exists(path);
                if (changed) {
                    Files.createDirectories(path.getParent());
                    Files.writeString(path, formatted, StandardCharsets.UTF_8);
                }
            } catch (IOException e) {
                throw new IllegalStateException("无法保存配置文件 " + path + ", 请检查文件权限", e);
            }
        }

        // 写入后重读文件，更新配置并发布变更
        reload();
        return changed;
    }

    /**
     * 移除配置原文中与默认值相同的设置
     * @param raw 配置原文
     * @return 规范化后的配置原文
     */
    public String normalize(String raw) {
        return normalize(parseDocument(raw), false);
    }

    /**
     * 预览整理后的配置原文，保留重复项供用户修改
     * @param parsed 已解析的配置草稿，可在整理时修改
     * @return 规范化后的预览原文
     */
    public String normalizeForPreview(Map<String, Object> parsed) {
        return normalize(parsed, true);
    }

    /**
     * 把配置值转成指定类型的副本
     * @param value 原配置值
     * @param type 目标类型
     * @param <T> 目标值类型
     * @return 转换后的独立副本
     */
    public static <T> T convert(Object value, Class<T> type) {
        return ConfigValueConverter.convert(value, type);
    }

    /**
     * 将 Java 属性名转成小写短横线格式的配置键
     * @param name Java 属性名
     * @return 配置键
     */
    public static String toConfigKey(String name) {
        return ConfigValueConverter.toConfigKey(name);
    }

    /**
     * 根据插件和依赖目录创建配置类加载器
     * @return 插件配置类加载器
     */
    private URLClassLoader createConfigurationClassLoader() {
        List<URL> urls = new ArrayList<>();
        for (Path directory : List.of(registry.getPlugins(), Path.of("lib"), Path.of("plugins-lib"))) {
            if (Files.isDirectory(directory)) {
                try (java.util.stream.Stream<Path> files = Files.list(directory)) {
                    for (Path jar : files.filter(file -> file.toString().endsWith(".jar")).sorted().toList()) {
                        urls.add(jar.toUri().toURL());
                    }
                } catch (IOException e) {
                    throw new IllegalStateException("无法读取配置类目录 " + directory, e);
                }
            }
        }
        return new URLClassLoader(urls.toArray(URL[]::new), getClass().getClassLoader());
    }

    /**
     * 关闭文件监听和插件配置类加载器
     */
    private void closeResources() {
        if (fileWatcher != null) {
            fileWatcher.close();
            fileWatcher = null;
        }
        try {
            configurationClassLoader.close();
        } catch (IOException e) {
            log.warn("关闭配置类加载器失败");
        }
    }

    /**
     * 重新读取外部修改的配置，读取失败时稍后重试
     */
    private void reloadAfterExternalChange() {
        for (int attempt = 1; attempt <= 3 && !Thread.currentThread().isInterrupted(); attempt++) {
            try {
                reload();
                return;
            } catch (RuntimeException e) {
                if (attempt == 3) {
                    log.warn("配置文件未能重载, 继续使用上一次有效配置, 请检查文件权限, JSON 格式和配置类型: {}", path);
                    return;
                }
                try {
                    Thread.sleep(500);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /**
     * 重读配置文件，更新实时项并发布变更事件
     */
    private void reload() {
        // 重新读取配置文件，读取失败时保留上次生效的配置
        StarBotConfigChangedEvent event;
        synchronized (lock) {
            Map<String, Object> loaded;
            try {
                loaded = readDocument(true);
            } catch (IOException | RuntimeException e) {
                throw new IllegalStateException("无法重载配置文件 " + path + ", 请检查文件权限, JSON 格式和配置类型");
            }
            if (loaded.equals(document)) {
                return;
            }

            // 只更新允许实时生效的字段，重启项保留启动值
            Map<String, Object> updated = cast(ConfigValueConverter.toTree(activeDocument));
            for (List<String> runtimePath : registry.getRuntimePaths()) {
                put(updated, runtimePath, find(loaded, runtimePath));
            }
            Map<String, Object> resolved = resolve(updated);

            // 对比新旧生效值，再更新内存中的配置
            Map<String, StarBotConfigChangedEvent.Change> changes = new LinkedHashMap<>();
            collectChanges("", values, resolved, changes);
            activeDocument = updated;
            document = loaded;
            values = resolved;
            event = MapUtils.isEmpty(changes) ? null : new StarBotConfigChangedEvent(changes);
        }

        // 释放锁后统一通知本次生效的变化
        if (publisher != null && event != null) {
            publisher.publishEvent(event);
        }
    }

    /**
     * 读取配置文件，处理密码重置和清空后恢复默认的字段
     * @param requireUnique 是否拒绝包含重复 Spring 属性的文件
     * @return 文件覆盖值
     */
    private Map<String, Object> readDocument(boolean requireUnique) throws IOException {
        String raw = Files.readString(path, StandardCharsets.UTF_8);
        Map<String, Object> loaded;
        try {
            loaded = parseDocument(raw);
        } catch (JSONException | IllegalArgumentException e) {
            throw new IOException(e.getMessage(), e);
        }
        if (requireUnique) {
            springPropertyBridge.requireUnique(loaded.get("advanced"));
        }
        springPropertyBridge.normalizeAdvanced(loaded);
        normalizeEmptyStrings(loaded);

        // 清除无效的密码摘要，记录密码重置状态
        Object password = ConfigValueUtil.getValue(loaded, ConfigPasswordUtil.PASSWORD_PATH);
        if (password != ConfigValueUtil.UNSET && password != null
                    && (!(password instanceof String text) || StringUtils.isNotBlank(text) && !ConfigPasswordUtil.isEncoded(text))) {
            Map<String, Object> original = parseDocument(raw);
            put(original, ConfigPasswordUtil.PASSWORD_PATH, "");
            try {
                Files.writeString(path, JSON.toJSONString(original, JSONWriter.Feature.PrettyFormat, JSONWriter.Feature.WriteNulls), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException("无法清空无效的配置中心访问密码, 请检查 " + path + " 的权限", e);
            }
            put(loaded, ConfigPasswordUtil.PASSWORD_PATH, "");
            passwordReset = true;
            log.warn("配置中心访问密码格式无效, 已清空. 未设置固定密码时, 启动会生成随机密码");
        }

        // 处理清空即恢复默认的字段，保留冲突项的明确选择
        for (ConfigMetaRegistry.Declaration declaration : registry.getDeclarations()) {
            if (!registry.hasSelectionConflict(declaration.getPath()) && declaration.getField().getJSONObject("ui") != null
                    && declaration.getField().getJSONObject("ui").getBooleanValue("emptyAsDefault")
                    && "".equals(ConfigValueUtil.getValue(loaded, declaration.getPath()))) {
                put(loaded, declaration.getPath().stream().map(Object::toString).toList(), ABSENT);
            }
        }
        return loaded;
    }

    /**
     * 根据配置类的字段初值设置页面默认值
     */
    private void initializeMetaDefaults() {
        // 找到业务字段所属的配置类
        for (ConfigMetaRegistry.Declaration declaration : registry.getDeclarations()) {
            List<String> path = declaration.getPath().stream().map(Object::toString).toList();
            if (CollectionUtils.isEmpty(path) || !"starbot".equals(path.get(0))) {
                continue;
            }
            for (Map.Entry<String, Class<?>> entry : types.entrySet()) {
                List<String> prefix = Arrays.asList(entry.getKey().split("\\."));
                if (path.size() <= prefix.size() || !path.subList(0, prefix.size()).equals(prefix)) {
                    continue;
                }

                // 沿路径找到字段类型和初始值
                ResolvableType type = ResolvableType.forClass(entry.getValue());
                Object value = createDefaultInstance(entry.getValue());
                for (String part : path.subList(prefix.size(), path.size())) {
                    String name = java.util.regex.Pattern.compile("-([a-z])").matcher(part)
                            .replaceAll(match -> match.group(1).toUpperCase(Locale.ROOT));
                    Field field = ReflectionUtils.findField(type.resolve(Object.class), name);
                    if (field == null) {
                        type = ResolvableType.NONE;
                        break;
                    }
                    ReflectionUtils.makeAccessible(field);
                    value = value == null || value == ABSENT ? ABSENT : ReflectionUtils.getField(field, value);
                    type = ResolvableType.forField(field, type);
                }

                // 将 DTO 字段的初值补到字段元数据中
                if (type != ResolvableType.NONE) {
                    populateMetaDefaults(declaration.getField(), type, value, 0);
                }
                break;
            }
        }
    }

    /**
     * 补充对象字段、Map 条目和列表元素的默认值
     * @param fieldMeta 字段元数据
     * @param type DTO 字段类型
     * @param value DTO 初值或缺失标记
     * @param depth 当前嵌套深度
     */
    private static void populateMetaDefaults(JSONObject fieldMeta, ResolvableType type, Object value, int depth) {
        // 检查嵌套层数并复制字段初值
        if (depth > 32) {
            return;
        }
        if (value != ABSENT) {
            fieldMeta.put("default", ConfigValueConverter.toTree(value));
        }

        // 补充对象子字段的默认值
        Class<?> raw = type.resolve(Object.class);
        if (fieldMeta.getJSONArray("fields") != null) {
            Object bean = value != null && value != ABSENT ? value : createDefaultInstance(raw);
            for (Object item : fieldMeta.getJSONArray("fields")) {
                JSONObject child = (JSONObject) item;
                String name = java.util.regex.Pattern.compile("-([a-z])").matcher(child.getString("key"))
                        .replaceAll(match -> match.group(1).toUpperCase(Locale.ROOT));
                Field field = ReflectionUtils.findField(raw, name);
                if (field != null) {
                    ReflectionUtils.makeAccessible(field);
                    populateMetaDefaults(child, ResolvableType.forField(field, type), bean == null ? ABSENT : ReflectionUtils.getField(field, bean), depth + 1);
                }
            }
        }

        // 为 Map 和列表的新条目准备默认内容
        if (fieldMeta.getJSONObject("values") != null && Map.class.isAssignableFrom(raw)) {
            populateMetaTemplate(fieldMeta.getJSONObject("values"), type.getGeneric(1), depth + 1);
        }
        if (fieldMeta.getJSONObject("items") != null && (Collection.class.isAssignableFrom(raw) || raw.isArray())) {
            populateMetaTemplate(fieldMeta.getJSONObject("items"), raw.isArray() ? type.getComponentType() : type.getGeneric(0), depth + 1);
        }
    }

    /**
     * 创建添加集合条目时使用的默认对象
     * @param fieldMeta 集合元素的元数据
     * @param type 元素类型
     * @param depth 当前嵌套深度
     */
    private static void populateMetaTemplate(JSONObject fieldMeta, ResolvableType type, int depth) {
        Class<?> raw = type.resolve(Object.class);
        Object value = raw == Object.class || raw.isPrimitive() || raw.isEnum() || raw.isInterface()
                || Modifier.isAbstract(raw.getModifiers()) || raw.getName().startsWith("java.") ? ABSENT : createDefaultInstance(raw);
        populateMetaDefaults(fieldMeta, type, value == null ? ABSENT : value, depth);
    }

    /**
     * 查找内存中的生效配置，未设置时查找默认值
     * @param key 完整配置键
     * @return 配置值，未找到时返回 null
     */
    private Object findEffectiveValue(String key) {
        checkKey(key);
        Map<String, Object> snapshot = values;
        List<String> parts = parsePath(key, snapshot);
        Object value = find(snapshot, parts);
        if (value == ABSENT && mapPaths.stream().noneMatch(path -> parts.size() > path.size()
                && parts.subList(0, path.size()).equals(path) && !hasMapEntry(snapshot, path, parts.get(path.size())))) {
            value = findDefault(parts);
        }
        return value == ABSENT ? null : value;
    }

    /**
     * 统一高级配置的键名，处理访问密码并移除可省略的默认值
     * @param parsed 已解析的配置对象
     * @param preview 是否为页面预览，预览时保留重复属性供用户修改
     * @return 整理后的配置原文
     */
    private String normalize(Map<String, Object> parsed, boolean preview) {
        synchronized (lock) {
            Set<List<String>> preserved = new HashSet<>();
            if (preview) {
                for (SpringPropertyBridge.Duplicate duplicate : springPropertyBridge.duplicates(parsed.get("advanced"))) {
                    for (List<Object> source : List.of(duplicate.getFirst(), duplicate.getSecond())) {
                        List<String> path = source.stream().map(Object::toString).toList();
                        for (int length = 1; length <= path.size(); length++) {
                            preserved.add(path.subList(0, length));
                        }
                    }
                }
            } else {
                springPropertyBridge.requireUnique(parsed.get("advanced"));
            }
            springPropertyBridge.normalizeAdvanced(parsed);
            normalizeEmptyStrings(parsed);
            protectPassword(parsed);
            return JSON.toJSONString(prune(List.of(), parsed, preserved), JSONWriter.Feature.PrettyFormat, JSONWriter.Feature.WriteNulls);
        }
    }

    /**
     * 将配置文档中指定字段的空字符串转成 null
     * @param document 配置文档
     */
    private void normalizeEmptyStrings(Map<String, Object> document) {
        for (ConfigMetaRegistry.Declaration declaration : registry.getDeclarations()) {
            ConfigValueUtil.normalizeEmptyStrings(document, declaration.getPath(), declaration.getField());
        }
    }

    /**
     * 保存前把明文访问密码转成哈希
     * @param document 配置文档
     */
    private static void protectPassword(Map<String, Object> document) {
        Object value = ConfigValueUtil.getValue(document, ConfigPasswordUtil.PASSWORD_PATH);
        if (value != ConfigValueUtil.UNSET && !(value instanceof String)) {
            throw new SecurityException("访问密码必须为文本");
        }
        if (value instanceof String password) {
            put(document, ConfigPasswordUtil.PASSWORD_PATH, ConfigPasswordUtil.encode(password));
        }
    }

    /**
     * 移除配置树中可以省略的默认值
     * @param path 正在检查的配置项路径
     * @param value 文件覆盖值
     * @param preserved 预览中需要保留的冲突路径
     * @return 待保存值，无需保存时返回 ABSENT
     */
    private Object prune(List<String> path, Object value, Set<List<String>> preserved) {
        // 将指定字段的空文本视为恢复默认，保留用户对冲突项的选择
        boolean canPrune = !preserved.contains(path) && !registry.hasSelectionConflict(path);
        if (canPrune && "".equals(value) && registry.getDeclarations().stream().anyMatch(declaration -> declaration.getPath().equals(path)
                && declaration.getField().getJSONObject("ui") != null
                && declaration.getField().getJSONObject("ui").getBooleanValue("emptyAsDefault"))) {
            return ABSENT;
        }

        // 跳过与字段初值或统一建议相同的值
        Object defaultValue = findDefault(path);
        if (CollectionUtils.isNotEmpty(path) && canPrune && ConfigValueUtil.isEqual(value, defaultValue)) {
            return ABSENT;
        }

        // 逐项清理子字段，移除空分组，保留明确设置的空对象
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            boolean retainEntries = mapPaths.contains(path);
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                List<String> child = new ArrayList<>(path);
                child.add(entry.getKey().toString());
                Object pruned = prune(child, entry.getValue(), preserved);
                if (pruned != ABSENT) {
                    result.put(entry.getKey().toString(), pruned);
                } else if (retainEntries) {
                    result.put(entry.getKey().toString(), entry.getValue());
                }
            }
            if (MapUtils.isEmpty(result) && (canPrune && sectionPaths.contains(path)
                    || CollectionUtils.isNotEmpty(path) && MapUtils.isNotEmpty(map) && defaultValue instanceof Map<?, ?>)) {
                return ABSENT;
            }
            return result;
        }
        return value;
    }

    /**
     * 查找指定路径的默认值
     * @param path 要查找默认值的配置路径
     * @return 默认值，未声明时返回 ABSENT
     */
    private Object findDefault(List<String> path) {
        // 先查启动时登记的默认值
        Object found = find(defaults, path);
        if (found != ABSENT) {
            return found;
        }

        // 未找到默认值时，读取对应配置类的条目初值
        for (Map.Entry<String, Class<?>> entry : types.entrySet()) {
            List<String> prefix = Arrays.asList(entry.getKey().split("\\."));
            if (path.size() <= prefix.size() || !path.subList(0, prefix.size()).equals(prefix)) {
                continue;
            }
            ResolvableType type = ResolvableType.forClass(entry.getValue());
            Object bean = createDefaultInstance(entry.getValue());
            for (int index = prefix.size(); index < path.size(); index++) {
                // 遇到 Map 键时查看条目类型，其余路径逐段查找 Java 字段
                if (Map.class.isAssignableFrom(type.resolve(Object.class))) {
                    type = type.getGeneric(1);
                    Class<?> target = type.resolve(Object.class);
                    if (index == path.size() - 1 || target == Object.class || target.isEnum() || target.isPrimitive() || target.getName().startsWith("java.")
                            || target.isInterface() || Modifier.isAbstract(target.getModifiers()) || target.isArray()) {
                        return ABSENT;
                    }
                    bean = createDefaultInstance(target);
                } else {
                    StringBuilder name = new StringBuilder();
                    boolean upper = false;
                    for (char character : path.get(index).toCharArray()) {
                        if (character == '-') {
                            upper = true;
                        } else {
                            name.append(upper ? Character.toUpperCase(character) : character);
                            upper = false;
                        }
                    }
                    Field field = ReflectionUtils.findField(type.resolve(Object.class), name.toString());
                    if (field == null || bean == null) {
                        return ABSENT;
                    }
                    ReflectionUtils.makeAccessible(field);
                    bean = ReflectionUtils.getField(field, bean);
                    type = ResolvableType.forField(field, type);
                }
            }
            return ConfigValueConverter.toTree(bean);
        }
        return ABSENT;
    }

    /**
     * 创建带字段初值的配置对象
     * @param type DTO 类型
     * @return 默认实例，无法创建时返回 null
     */
    private static Object createDefaultInstance(Class<?> type) {
        try {
            return type.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    /**
     * 合并默认值、文件配置和 Spring 的启动配置
     * @param raw 已生效的文件覆盖值
     * @return 当前生效配置
     */
    private Map<String, Object> resolve(Map<String, Object> raw) {
        return cast(merge(List.of(), merge(List.of(), defaults, raw), springValues));
    }

    /**
     * 合并配置对象，声明为 Map 的字段以覆盖值中的键为准
     * @param path 正在合并的配置项路径
     * @param base 合并前的配置值
     * @param override 用于覆盖的配置值
     * @return 合并后的值
     */
    private Object merge(List<String> path, Object base, Object override) {
        if (base instanceof Map<?, ?> baseMap && override instanceof Map<?, ?> overrideMap) {
            Map<String, Object> result = new LinkedHashMap<>();
            if (!mapPaths.contains(path)) {
                baseMap.forEach((key, value) -> result.put(key.toString(), value));
            }
            overrideMap.forEach((key, value) -> {
                String name = key.toString();
                List<String> child = new ArrayList<>(path);
                child.add(name);
                result.put(name, merge(child, baseMap.get(key), value));
            });
            return result;
        }
        return override;
    }

    /**
     * 找出已保存值和生效值不同的配置路径
     * @param path 正在比较的配置项路径
     * @param before 当前生效值
     * @param after 文件中的值
     * @param paths 待重启路径列表
     */
    private static void collectPendingPaths(List<String> path, Object before, Object after, List<List<String>> paths) {
        if (ConfigValueUtil.isEqual(before, after)) {
            return;
        }
        if ((before instanceof Map<?, ?> || before == ABSENT) && (after instanceof Map<?, ?> || after == ABSENT)) {
            Map<?, ?> oldMap = before instanceof Map<?, ?> map ? map : Map.of();
            Map<?, ?> newMap = after instanceof Map<?, ?> map ? map : Map.of();
            Set<Object> keys = new LinkedHashSet<>(oldMap.keySet());
            keys.addAll(newMap.keySet());
            if (CollectionUtils.isNotEmpty(keys)) {
                for (Object key : keys) {
                    List<String> child = new ArrayList<>(path);
                    child.add(key.toString());
                    collectPendingPaths(child, oldMap.containsKey(key) ? oldMap.get(key) : ABSENT,
                            newMap.containsKey(key) ? newMap.get(key) : ABSENT, paths);
                }
                return;
            }
        }
        paths.add(List.copyOf(path));
    }

    /**
     * 比较重载前后的配置，记录实际生效的变化
     * @param key 当前完整配置键
     * @param before 重载前的生效值
     * @param after 重载后的生效值
     * @param changes 本次变更清单
     */
    private static void collectChanges(String key, Object before, Object after, Map<String, StarBotConfigChangedEvent.Change> changes) {
        if (Objects.equals(before, after)) {
            return;
        }
        if (before instanceof Map<?, ?> oldMap && after instanceof Map<?, ?> newMap) {
            Set<Object> keys = new LinkedHashSet<>(oldMap.keySet());
            keys.addAll(newMap.keySet());
            for (Object child : keys) {
                String childKey = key.isEmpty() ? child.toString() : key + "." + child;
                if (!oldMap.containsKey(child) || !newMap.containsKey(child)) {
                    changes.put(childKey, new StarBotConfigChangedEvent.Change(oldMap.get(child), newMap.get(child)));
                } else {
                    collectChanges(childKey, oldMap.get(child), newMap.get(child), changes);
                }
            }
        } else {
            changes.put(key, new StarBotConfigChangedEvent.Change(before, after));
        }
    }

    /**
     * 检查配置键及各段名称是否为空
     * @param key 完整配置键
     */
    private static void checkKey(String key) {
        if (StringUtils.isBlank(key) || key.startsWith(".") || key.endsWith(".") || key.contains("..")) {
            throw new IllegalArgumentException("配置键不能为空或包含空路径");
        }
    }

    /**
     * 把配置键拆成查找路径，保留已有的完整键名
     * @param key 完整配置键
     * @param root 配置根对象
     * @return 保留完整键名的配置路径
     */
    private List<String> parsePath(String key, Map<String, Object> root) {
        // 根据元数据定位高级字段或 Map 条目
        if (key.startsWith("advanced.")) {
            return springPropertyBridge.declaredPath(key.substring("advanced.".length()));
        }

        // 业务配置优先匹配已有的完整键名，再根据点号拆分其余部分
        List<String> result = new ArrayList<>();
        Object current = root;
        String remaining = key;
        while (!remaining.isEmpty()) {
            int split = remaining.indexOf('.');
            if (current instanceof Map<?, ?> map) {
                if (map.containsKey(remaining)) {
                    result.add(remaining);
                    break;
                }
                for (int index = remaining.lastIndexOf('.'); index > 0; index = remaining.lastIndexOf('.', index - 1)) {
                    if (map.containsKey(remaining.substring(0, index))) {
                        split = index;
                        break;
                    }
                }
            }
            String part = split < 0 ? remaining : remaining.substring(0, split);
            result.add(part);
            current = current instanceof Map<?, ?> map ? map.get(part) : null;
            remaining = split < 0 ? "" : remaining.substring(split + 1);
        }
        return result;
    }

    /**
     * 判断 Map 条目是否可以继续查找默认值
     * @param snapshot 当前生效配置
     * @param path Map 所在的配置路径
     * @param key 要查找的条目键名
     * @return Map 路径不存在或 Map 包含该键时返回 true
     */
    private static boolean hasMapEntry(Object snapshot, List<String> path, String key) {
        Object value = find(snapshot, path);
        return value == ABSENT || value instanceof Map<?, ?> map && map.containsKey(key);
    }

    /**
     * 根据路径中各段的完整键名读取对象字段
     * @param value 起始对象
     * @param path 要读取的路径，每一段对应一个完整键名
     * @return 配置值，路径不存在时返回 ABSENT
     */
    private static Object find(Object value, List<String> path) {
        Object current = value;
        for (String part : path) {
            if (!(current instanceof Map<?, ?> map) || !map.containsKey(part)) {
                return ABSENT;
            }
            current = map.get(part);
        }
        return current;
    }

    /**
     * 设置指定路径的值，传入缺失标记时删除字段
     * @param target 配置对象
     * @param path 要写入或删除的路径，每一段对应一个完整键名
     * @param value 新值或 ABSENT
     */
    private static void put(Map<String, Object> target, List<String> path, Object value) {
        Map<String, Object> current = target;
        for (int index = 0; index < path.size() - 1; index++) {
            String key = path.get(index);
            Map<String, Object> child = current.get(key) instanceof Map<?, ?> map ? new LinkedHashMap<>(cast(map)) : new LinkedHashMap<>();
            current.put(key, child);
            current = child;
        }
        if (value == ABSENT) {
            current.remove(path.get(path.size() - 1));
        } else {
            current.put(path.get(path.size() - 1), value);
        }
    }

    /**
     * 解析配置原文并取得根对象
     * @param raw 配置原文
     * @return 配置对象
     */
    private static Map<String, Object> parseDocument(String raw) {
        Object parsed;
        try {
            parsed = JSON.parse(raw);
        } catch (JSONException | IllegalArgumentException e) {
            // 解析异常可能包含配置原文，仅保留固定的格式提示
            throw new JSONException("JSON 格式无效");
        }
        if (!(parsed instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("配置文件根节点必须是 JSON 对象");
        }
        return cast(parsed);
    }

    /**
     * 将已解析的配置对象转为字符串键的映射类型
     * @param value 配置对象
     * @return 字符串键的配置对象
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object value) {
        return (Map<String, Object>) value;
    }
}
