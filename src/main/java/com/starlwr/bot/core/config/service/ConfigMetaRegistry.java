package com.starlwr.bot.core.config.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONException;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.starlwr.bot.core.config.validation.ConfigRules;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.function.Predicate;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import static com.starlwr.bot.core.config.validation.ConfigValueUtil.*;

/**
 * 配置元数据注册表
 */
@Slf4j
public class ConfigMetaRegistry {
    /**
     * 单个配置元数据文件的大小上限
     */
    private static final int MAX_META_BYTES = 8 * 1024 * 1024;

    /**
     * 配置字段类型集合
     */
    private static final Set<String> TYPES = Set.of("string", "integer", "number", "duration", "boolean", "object", "map", "array", "any", "null");

    /**
     * 系统生成的字段元数据属性集合
     */
    private static final Set<String> GENERATED_FIELD_KEYS = Set.of("configPath", "default", "effectiveDefault", "suggestions",
            "suggestionConflict", "typeConflict", "conflicted", "springBindingConflict", "declarationIssue", "mapEntries");

    /**
     * 插件 JAR 所在目录
     */
    @Getter
    private final Path plugins;

    /**
     * 模块配置元数据列表
     */
    private final List<JSONObject> meta = new ArrayList<>();

    /**
     * 配置元数据问题列表
     */
    private final List<JSONObject> diagnostics = new ArrayList<>();

    /**
     * 配置前缀与配置类名称映射
     */
    private final Map<String, String> configurationClasses = new LinkedHashMap<>();

    /**
     * 配置类声明冲突的前缀集合
     */
    private final Set<String> configurationClassConflicts = new LinkedHashSet<>();

    /**
     * 配置字段声明列表
     */
    private final List<Declaration> declarations = new ArrayList<>();

    /**
     * 主字段元数据映射
     */
    private final Map<List<Object>, Declaration> editors = new LinkedHashMap<>();

    /**
     * 配置字段声明映射
     */
    private final Map<List<Object>, List<Declaration>> entries = new LinkedHashMap<>();

    /**
     * 生效的 Spring 属性映射
     */
    private final Map<String, List<String>> springBindings = new LinkedHashMap<>();

    /**
     * 配置建议映射
     */
    private final Map<List<Object>, JSONArray> suggestionDefinitions = new LinkedHashMap<>();

    /**
     * 共享配置声明来源映射
     */
    private final Map<List<Object>, JSONArray> participants = new LinkedHashMap<>();

    /**
     * 建议值冲突路径集合
     */
    private final Set<List<Object>> suggestionConflicts = new LinkedHashSet<>();

    /**
     * 配置类型冲突路径集合
     */
    private final Set<List<Object>> typeConflicts = new LinkedHashSet<>();

    /**
     * 配置字段声明
     */
    @Getter
    @RequiredArgsConstructor
    public static class Declaration {
        /**
         * 字段元数据
         */
        private final JSONObject field;

        /**
         * 配置路径列表
         */
        private final List<Object> path;

        /**
         * 同组字段列表
         */
        private final JSONArray siblings;

        /**
         * 所在模块 ID
         */
        private final String tab;

        /**
         * 所在分组 ID
         */
        private final String group;

        /**
         * 声明来源
         */
        private final String source;

    }

    public ConfigMetaRegistry(Path plugins) throws IOException {
        // 加载 Core 内置的配置元数据
        this.plugins = plugins;
        JSONObject coreMeta = loadCoreMeta();
        meta.add(coreMeta);

        // 扫描插件 JAR，跳过缺失或无效的配置元数据
        Map<String, JSONObject> pluginMetaById = new TreeMap<>();
        if (Files.isDirectory(plugins)) {
            try (java.util.stream.Stream<Path> paths = Files.list(plugins)) {
                for (Path path : paths.filter(p -> p.getFileName().toString().endsWith(".jar")).sorted().toList()) {
                    String source = path.getFileName().toString();
                    try (JarFile jar = new JarFile(path.toFile())) {
                        JarEntry entry = jar.getJarEntry("config/config.json");
                        if (entry == null) {
                            continue;
                        }
                        JarEntry pluginInfoEntry = jar.getJarEntry("plugin.json");
                        if (pluginInfoEntry == null) {
                            addDiagnostic("error", List.of(), path.getFileName().toString(), "插件缺少 plugin.json, 已跳过加载配置元数据", false);
                            continue;
                        }

                        // 检查插件是否有有效且唯一的标识
                        JSONObject pluginInfo = readJson(jar.getInputStream(pluginInfoEntry), pluginInfoEntry.getName());
                        if (pluginInfo == null || !(pluginInfo.get("groupId") instanceof String groupId) || StringUtils.isBlank(groupId)
                                || !(pluginInfo.get("artifactId") instanceof String artifactId) || StringUtils.isBlank(artifactId)) {
                            addDiagnostic("error", List.of(), path.getFileName().toString(), "插件标识不完整, 已跳过加载配置元数据", false);
                            continue;
                        }
                        String id = groupId + ":" + artifactId;
                        if (pluginMetaById.containsKey(id)) {
                            addDiagnostic("error", List.of(), path.getFileName().toString(), "插件标识与其他插件重复, 已跳过加载配置元数据", false);
                            continue;
                        }

                        // 校验配置元数据并记录所属插件
                        String pluginName = getString(pluginInfo, "name", id);
                        source = pluginName + " (" + source + ")";
                        JSONObject moduleMeta = readJson(jar.getInputStream(entry), entry.getName());
                        validateMeta(moduleMeta);
                        moduleMeta.put("id", id);
                        moduleMeta.put("source", pluginName);
                        moduleMeta.put("sourceFile", path.getFileName().toString());
                        pluginMetaById.put(id, moduleMeta);
                    } catch (IllegalArgumentException e) {
                        addDiagnostic("error", List.of(), source, "已跳过加载配置元数据: " + e.getMessage(), true);
                    } catch (IOException e) {
                        addDiagnostic("error", List.of(), source, "无法读取插件 JAR 中的配置元数据, 已跳过加载", true);
                    } catch (RuntimeException e) {
                        log.warn("加载插件 {} 的配置元数据时发生异常", source, e);
                        addDiagnostic("error", List.of(), source, "加载配置元数据时发生异常, 请查看 StarBot 控制台", true);
                    }
                }
            } catch (IOException | UncheckedIOException | SecurityException e) {
                addDiagnostic("warning", List.of(), plugins.toString(), "插件目录无法读取, 已跳过加载配置元数据", false);
            }
        }

        // 根据配置前缀排列插件的配置元数据，再添加自定义配置页
        List<JSONObject> pluginMetaList = new ArrayList<>(pluginMetaById.values());
        pluginMetaList.sort((a, b) -> {
            JSONArray aPath = a.getJSONArray("basePath");
            JSONArray bPath = b.getJSONArray("basePath");
            for (int i = 0; i < Math.min(aPath.size(), bPath.size()); i++) {
                int result = aPath.getString(i).compareTo(bPath.getString(i));
                if (result != 0) {
                    return result;
                }
            }
            int result = Integer.compare(aPath.size(), bPath.size());
            return result == 0 ? a.getString("id").compareTo(b.getString("id")) : result;
        });
        meta.addAll(pluginMetaList);
        meta.add(JSONObject.of("id", "custom", "title", "自定义", "description", "添加和编辑其他页面中未列出的配置项",
                "basePath", new JSONArray(), "groups", new JSONArray()));

        registerConfigurationClasses();

        // 先登记容器，再登记通过方括号引用容器条目的字段
        registerFields(false);
        registerFields(true);
        Set<List<Object>> conflictingSpringPaths = registerSpringBindings();

        // 合并插件追加的下拉选项，将结果更新到各个页面
        List<JSONObject> contributors = new ArrayList<>(meta);
        for (JSONObject contributor : contributors) {
            JSONArray additions = contributor.getJSONArray("optionContributions");
            if (additions == null) {
                continue;
            }
            for (Object item : additions) {
                JSONObject addition = (JSONObject) item;
                List<Object> path = new ArrayList<>(addition.getJSONArray("path"));
                Declaration target = editors.get(path);
                if (target == null) {
                    addDiagnostic("warning", path, getString(contributor, "source", contributor.getString("title")), "选项扩展的目标字段不存在", false);
                    continue;
                }
                JSONArray options = target.field.getJSONArray("options");
                if (options == null) {
                    options = new JSONArray();
                    target.field.put("options", options);
                }
                for (Object option : addition.getJSONArray("options")) {
                    JSONObject choice = (JSONObject) option;
                    if (options.stream().noneMatch(existing -> isEqual(((JSONObject) existing).get("value"), choice.get("value")))) {
                        options.add(JSON.parseObject(JSON.toJSONString(choice, JSONWriter.Feature.WriteNulls)));
                    }
                }
                for (Declaration declaration : declarations) {
                    if (declaration.path.equals(path)) {
                        declaration.field.put("options", JSON.parseArray(JSON.toJSONString(options, JSONWriter.Feature.WriteNulls)));
                    }
                }
            }
        }

        // 汇总插件对类型、取值范围和建议值的不同要求
        for (Map.Entry<List<Object>, List<Declaration>> entry : entries.entrySet()) {
            List<Object> path = entry.getKey();
            List<JSONObject> fields = entry.getValue().stream().map(Declaration::getField).toList();
            boolean typeConflict = fields.stream().anyMatch(field -> field.getBooleanValue("typeConflict"));
            if (fields.size() > 1 && !participants.containsKey(path)) {
                addDeclarationDiagnostic("业务配置路径重复声明", "这些配置项使用了同一个配置路径. 请由一个模块负责声明, 或为不同配置项使用各自的路径",
                        entry.getValue().stream().map(ConfigMetaRegistry::diagnosticSource).toList(), true);
            } else if (!typeConflict) {
                checkConstraints(entry.getValue());
            }

            // 标记元数据声明错误，仅在字段类型不一致且声明无误时允许用户选择配置值
            JSONArray suggestions = suggestionDefinitions.get(path);
            boolean springBindingConflict = conflictingSpringPaths.contains(path);
            JSONObject issue = diagnostics.stream().filter(item -> item.getBooleanValue("blocking") && affectsPath(item, path)).findFirst().orElse(null);
            if (issue != null) {
                fields.forEach(field -> {
                    field.put("conflicted", true);
                    if (springBindingConflict) {
                        field.put("springBindingConflict", true);
                    } else if (issue.containsKey("title")) {
                        field.put("declarationIssue", issue.getString("title"));
                    }
                    field.remove("typeConflict");
                });
                continue;
            }
            if (typeConflict) {
                typeConflicts.add(path);
                fields.forEach(field -> {
                    field.put("conflicted", true);
                    field.put("typeConflict", true);
                    field.put("suggestionConflict", true);
                });
                continue;
            }

            // 比较各插件的建议值，建议值一致时作为默认值
            if (CollectionUtils.isEmpty(suggestions)) {
                continue;
            }
            Object first = suggestions.getJSONObject(0).get("value");
            if (suggestions.stream().anyMatch(item -> !isEqual(first, ((JSONObject) item).get("value")))) {
                suggestionConflicts.add(path);
                fields.forEach(field -> field.put("suggestionConflict", true));
            } else if (first != null) {
                fields.forEach(field -> field.put("effectiveDefault", first));
            }
        }

        // 同一配置在各插件的页面上使用同一份建议清单
        for (Map.Entry<List<Object>, List<Declaration>> entry : entries.entrySet()) {
            JSONArray candidates = suggestionDefinitions.get(entry.getKey());
            if (candidates != null) {
                entry.getValue().forEach(declaration -> declaration.field.put("suggestions", candidates));
            }
        }

        // 父对象有类型冲突时，用户只需选择整个对象
        Set<List<Object>> typePaths = new LinkedHashSet<>(typeConflicts);
        typeConflicts.removeIf(path -> typePaths.stream().anyMatch(parent -> parent.size() < path.size()
                && parent.equals(path.subList(0, parent.size()))));
        suggestionConflicts.removeIf(path -> typeConflicts.stream().anyMatch(parent -> parent.size() < path.size()
                && parent.equals(path.subList(0, parent.size()))));
    }

    /**
     * 获取没有冲突且可直接使用的高级配置建议值
     * @return 建议值一致的高级配置默认值
     */
    public Map<String, Object> getAdvancedDefaults() {
        JSONObject defaults = new JSONObject();
        for (Declaration declaration : editors.values()) {
            if (declaration.path.size() < 2 || !"advanced".equals(declaration.path.get(0))
                    || declaration.field.getBooleanValue("conflicted") || hasSelectionConflict(declaration.path) || !declaration.field.containsKey("effectiveDefault")) {
                continue;
            }
            JSONObject parent = defaults;
            for (int index = 1; index < declaration.path.size() - 1; index++) {
                String key = declaration.path.get(index).toString();
                if (!(parent.get(key) instanceof JSONObject)) {
                    parent.put(key, new JSONObject());
                }
                parent = parent.getJSONObject(key);
            }
            parent.put(declaration.path.get(declaration.path.size() - 1).toString(),
                    JSON.parse(JSON.toJSONString(declaration.field.get("effectiveDefault"), JSONWriter.Feature.WriteNulls)));
        }
        return defaults;
    }

    /**
     * 获取各业务配置前缀对应的配置类名称
     * @return 配置前缀到 DTO 完整类名的映射
     */
    public Map<String, String> getConfigurationClasses() {
        return Collections.unmodifiableMap(configurationClasses);
    }

    /**
     * 检查是否存在同一个配置前缀声明了不同配置类
     * @return 是否存在配置类声明冲突
     */
    public boolean hasConfigurationClassConflicts() {
        return CollectionUtils.isNotEmpty(configurationClassConflicts);
    }

    /**
     * 检查指定前缀是否声明了不同配置类
     * @param prefix 配置前缀
     * @return 是否存在配置类声明冲突
     */
    public boolean hasConfigurationClassConflict(String prefix) {
        return configurationClassConflicts.contains(prefix);
    }

    /**
     * 记录无法加载配置类的问题
     * @param name DTO 完整类名
     */
    public void configurationError(String name) {
        addDiagnostic("warning", List.of(), name, "配置 DTO 暂时无法加载, 页面仍可编辑配置", false);
    }

    /**
     * 获取可以实时更新的业务配置路径
     * @return 可实时更新的配置路径，每一段对应一个 JSON 键名
     */
    public List<List<String>> getRuntimePaths() {
        List<List<String>> paths = new ArrayList<>();
        for (Declaration declaration : editors.values()) {
            if (CollectionUtils.isNotEmpty(declaration.path) && "starbot".equals(declaration.path.get(0))
                    && !"object".equals(declaration.field.getString("type"))
                    && !declaration.field.getBooleanValue("restartRequired") && !declaration.field.containsKey("springKey")) {
                paths.add(declaration.path.stream().map(Object::toString).toList());
            }
        }
        return paths;
    }

    /**
     * 获取需要整体保存的 Map 配置路径
     * @return 配置路径
     */
    public Set<List<String>> getMapPaths() {
        Set<List<String>> paths = new HashSet<>();
        for (Declaration declaration : editors.values()) {
            if ("map".equals(declaration.field.get("type"))
                    && !declaration.field.getBooleanValue("conflicted") && !declaration.field.getBooleanValue("typeConflict")) {
                paths.add(declaration.path.stream().map(Object::toString).toList());
            }
        }
        return Set.copyOf(paths);
    }

    /**
     * 获取没有重复映射的 Spring 属性对应的业务配置路径
     * @return Spring 属性键到业务配置路径的映射
     */
    public Map<String, List<String>> getSpringBindings() {
        return Collections.unmodifiableMap(springBindings);
    }

    /**
     * 获取供页面使用的配置元数据
     * @return 根据页面显示顺序排列的模块配置元数据列表
     */
    public List<JSONObject> getMeta() {
        return Collections.unmodifiableList(meta);
    }

    /**
     * 获取读取或合并配置元数据时发现的问题
     * @return 元数据错误及警告
     */
    public List<JSONObject> getDiagnostics() {
        return Collections.unmodifiableList(diagnostics);
    }

    /**
     * 获取各模块分别声明的字段
     * @return 配置声明
     */
    public List<Declaration> getDeclarations() {
        return Collections.unmodifiableList(declarations);
    }

    /**
     * 检查是否存在配置元数据错误
     * @return 是否存在元数据声明错误
     */
    public boolean hasMetaErrors() {
        return diagnostics.stream().anyMatch(item -> item.getBooleanValue("blocking"));
    }

    /**
     * 列出建议值或类型不一致的高级配置，并标记用户是否已提供值
     * @param document 文件或页面草稿
     * @return 各插件的建议及原因，以及用户已填写的值
     */
    public JSONArray getConflicts(Object document) {
        // 收集建议值不同或类型不同的配置
        JSONArray conflicts = new JSONArray();
        Set<List<Object>> paths = new LinkedHashSet<>(suggestionConflicts);
        paths.addAll(typeConflicts);
        for (List<Object> path : paths) {
            // 类型冲突使用通用编辑器，密码字段继续遮挡显示
            JSONObject field = editors.get(path).field;
            boolean typeConflict = typeConflicts.contains(path);
            if (typeConflict) {
                JSONObject editor = JSONObject.of("key", field.getString("key"), "label", getString(field, "label", field.getString("key")),
                        "type", "any", "configPath", new JSONArray(path), "restartRequired", true,
                        "suggestionConflict", true, "typeConflict", true, "suggestions", suggestionDefinitions.getOrDefault(path, new JSONArray()));
                if (entries.get(path).stream().map(Declaration::getField)
                        .anyMatch(item -> item.getJSONObject("ui") != null && "password".equals(item.getJSONObject("ui").getString("widget")))) {
                    editor.put("ui", JSONObject.of("widget", "password"));
                }
                field = editor;
            }

            // 检查用户是否已填写值，明确填写的 null 也算已选择
            Object value = getValue(document, path);
            JSONObject conflict = JSONObject.of("path", new JSONArray(path), "label", getString(field, "label", field.getString("key")),
                    "definitions", suggestionDefinitions.getOrDefault(path, new JSONArray()), "participants", participants.get(path),
                    "typeConflict", typeConflict, "resolved", value != UNSET, "field", field);
            if (value != UNSET) {
                conflict.put("value", value);
            }
            conflicts.add(conflict);
        }
        return conflicts;
    }

    /**
     * 检查是否存在建议值或类型不一致且未填写的高级配置
     * @param document 文件或草稿
     * @return 是否还有需要用户选值或自行填写的配置
     */
    public boolean hasUnresolvedConflicts(Object document) {
        return hasUnresolvedConflicts(document, path -> true);
    }

    /**
     * 检查指定范围内是否存在建议值或类型不一致且未填写的高级配置
     * @param document 文件或草稿
     * @param pathFilter 判断当前配置路径是否需要检查
     * @return 指定范围内是否还有需要用户选值或自行填写的配置
     */
    public boolean hasUnresolvedConflicts(Object document, Predicate<List<Object>> pathFilter) {
        return Stream.concat(suggestionConflicts.stream(), typeConflicts.stream())
                .anyMatch(path -> pathFilter.test(path) && getValue(document, path) == UNSET);
    }

    /**
     * 检查指定配置及其父子项的建议值或类型是否一致
     * @param path 当前配置路径
     * @return 是否涉及需要用户选值的配置，无论是否已选择
     */
    public boolean hasSelectionConflict(List<?> path) {
        return Stream.concat(suggestionConflicts.stream(), typeConflicts.stream()).anyMatch(conflict ->
                path.size() <= conflict.size() && path.equals(conflict.subList(0, path.size()))
                        || conflict.size() < path.size() && conflict.equals(path.subList(0, conflict.size())));
    }

    /**
     * 根据配置前缀登记配置类，记录同一前缀中的不同配置类
     */
    private void registerConfigurationClasses() {
        // 根据配置前缀收集声明了配置类的模块
        Map<String, List<JSONObject>> modulesByPrefix = new LinkedHashMap<>();
        for (JSONObject module : meta) {
            if (module.containsKey("configurationClass")) {
                String prefix = StringUtils.join(module.getJSONArray("basePath"), '.');
                modulesByPrefix.computeIfAbsent(prefix, ignored -> new ArrayList<>()).add(module);
            }
        }

        // 同一前缀只登记一种配置类，并列出冲突的全部来源
        modulesByPrefix.forEach((prefix, modules) -> {
            String className = modules.get(0).getString("configurationClass");
            if (modules.stream().allMatch(module -> className.equals(module.getString("configurationClass")))) {
                configurationClasses.put(prefix, className);
                return;
            }
            configurationClassConflicts.add(prefix);
            List<JSONObject> sources = modules.stream().map(module -> JSONObject.of(
                    "source", module.getString("title") + " (" + module.getString("id") + ")",
                    "path", module.getJSONArray("basePath"),
                    "detail", "配置类 " + module.getString("configurationClass"))).toList();
            addDeclarationDiagnostic("同一配置前缀声明了不同配置类",
                    "同一个配置前缀只能使用一个配置类. 请将下列模块的配置类统一, 或为不同配置类使用各自的配置前缀",
                    sources, true);
            log.warn("配置前缀 {} 声明了不同配置类: {}", prefix,
                    sources.stream().map(source -> source.getString("source") + " - " + source.getString("detail")).toList());
        });
    }

    /**
     * 读取 Core 内置的配置元数据
     * @return Core 的配置元数据
     */
    private static JSONObject loadCoreMeta() throws IOException {
        String classPath = ConfigMetaRegistry.class.getName().replace('.', '/') + ".class";
        URL location = Objects.requireNonNull(ConfigMetaRegistry.class.getResource("ConfigMetaRegistry.class"));
        String owner = location.toExternalForm();
        String name = "config/config.json";
        try (InputStream input = new URL(owner.substring(0, owner.length() - classPath.length()) + name).openStream()) {
            JSONObject coreMeta = readJson(input, name);
            validateMeta(coreMeta);
            coreMeta.put("id", "core");
            return coreMeta;
        } catch (IOException e) {
            throw new IOException("无法读取 Core 内置配置元数据 " + name + ", 请检查 Core 安装包", e);
        } catch (IllegalArgumentException e) {
            throw new IOException("Core 内置配置元数据 " + name + " 无效, 请检查 Core 安装包: " + e.getMessage(), e);
        }
    }

    /**
     * 根据配置路径登记各字段及其所在页面
     * @param bracketed 是否登记带方括号的高级字段
     */
    private void registerFields(boolean bracketed) {
        for (JSONObject moduleMeta : meta) {
            for (Object item : moduleMeta.getJSONArray("groups")) {
                JSONObject group = (JSONObject) item;
                List<Object> path = new ArrayList<>(moduleMeta.getJSONArray("basePath"));
                path.addAll(group.getJSONArray("path"));
                boolean advanced = "advanced".equals(group.getString("target"));
                group.put("configPath", advanced ? JSONArray.of("advanced") : new JSONArray(path));
                for (Object itemField : group.getJSONArray("fields")) {
                    JSONObject field = (JSONObject) itemField;
                    boolean shared = "advanced".equals(getString(field, "target", advanced ? "advanced" : "starbot"));
                    if ((shared && field.getString("key").contains("[")) == bracketed) {
                        register(field, shared ? List.of("advanced") : path, group.getJSONArray("fields"), moduleMeta.getString("id"),
                                group.getString("id"), getString(moduleMeta, "source", moduleMeta.getString("title")), shared, true);
                    }
                }
            }
        }
    }

    /**
     * 登记 Spring 属性的业务映射，记录同一个属性对应的所有冲突声明
     * @return 存在 Spring 属性映射冲突的配置路径集合
     */
    private Set<List<Object>> registerSpringBindings() {
        // 根据 Spring 属性名归集业务字段和直接声明的高级配置项
        Map<String, List<Declaration>> bindings = new LinkedHashMap<>();
        for (Declaration declaration : editors.values()) {
            String key = declaration.field.getString("springKey");
            if (key == null && declaration.path.size() == 2 && "advanced".equals(declaration.path.get(0))) {
                key = declaration.path.get(1).toString();
            }
            if (key != null) {
                bindings.computeIfAbsent(key, ignored -> new ArrayList<>()).add(declaration);
            }
        }

        // 只登记唯一的业务映射，每个重复属性记录一条诊断
        Set<List<Object>> conflicts = new HashSet<>();
        for (Map.Entry<String, List<Declaration>> entry : bindings.entrySet()) {
            String key = entry.getKey();
            List<Declaration> sources = entry.getValue();
            Declaration first = sources.get(0);
            if (sources.size() == 1) {
                if (first.field.containsKey("springKey")) {
                    springBindings.put(key, first.path.stream().map(Object::toString).toList());
                }
                continue;
            }

            JSONArray related = new JSONArray();
            for (Declaration source : sources) {
                conflicts.add(source.path);
                related.add(JSONObject.of("source", source.source, "path", source.path));
            }
            diagnostics.add(JSONObject.of("severity", "error", "path", first.path, "source", first.source, "blocking", true,
                    "message", "不同配置路径映射到相同 Spring 属性: " + key, "springKey", key, "sources", related));
            log.warn("Spring 属性 {} 对应多个配置路径, 已跳过该属性的业务映射: {}", key,
                    sources.stream().map(source -> source.source + " (" + StringUtils.join(source.path, '.') + ")").toList());
        }
        return conflicts;
    }

    /**
     * 判断诊断是否涉及当前配置路径
     * @param issue 配置元数据诊断
     * @param path 配置路径
     * @return 是否涉及当前路径
     */
    private static boolean affectsPath(JSONObject issue, List<Object> path) {
        JSONArray sources = issue.getJSONArray("sources");
        return issue.getJSONArray("path").equals(path) || sources != null
                && sources.stream().anyMatch(source -> ((JSONObject) source).getJSONArray("path").equals(path));
    }

    /**
     * 记录配置元数据问题
     * @param severity 诊断级别
     * @param path 配置路径
     * @param source 声明来源
     * @param message 诊断消息
     * @param blocking 是否为影响对应配置声明的错误
     */
    private void addDiagnostic(String severity, List<?> path, String source, String message, boolean blocking) {
        diagnostics.add(JSONObject.of("severity", severity, "path", path, "source", source, "message", message, "blocking", blocking));
    }

    /**
     * 记录涉及多个配置字段的声明问题
     * @param title 问题标题
     * @param message 问题说明
     * @param sources 涉及的字段及其来源
     * @param blocking 是否为影响对应配置声明的错误
     */
    private void addDeclarationDiagnostic(String title, String message, List<JSONObject> sources, boolean blocking) {
        JSONObject first = sources.get(0);
        diagnostics.add(JSONObject.of("severity", blocking ? "error" : "warning", "path", first.get("path"),
                "source", first.get("source"), "title", title, "message", message, "blocking", blocking, "sources", sources));
    }

    /**
     * 取出诊断中需要展示的字段名称、类型和配置路径
     * @param declaration 配置字段声明
     * @return 字段及其来源
     */
    private static JSONObject diagnosticSource(Declaration declaration) {
        return JSONObject.of("source", declaration.source, "path", declaration.path,
                "label", declaration.field.get("label"), "type", declaration.field.get("type"));
    }

    /**
     * 从输入流读取配置元数据
     * @param input JSON 输入流
     * @param name JSON 文件名
     * @return 配置元数据 JSON 对象
     */
    private static JSONObject readJson(InputStream input, String name) throws IOException {
        try (input) {
            byte[] bytes = input.readNBytes(MAX_META_BYTES + 1);
            require(bytes.length <= MAX_META_BYTES, "配置元数据超过 8 MiB");
            try {
                return JSON.parseObject(new String(bytes, StandardCharsets.UTF_8));
            } catch (JSONException | IllegalArgumentException e) {
                // 解析异常可能包含原文，提示中只保留文件名和错误类型
                throw new IllegalArgumentException(name + " 中的 JSON 格式无效");
            }
        }
    }

    /**
     * 校验模块的配置元数据
     * @param moduleMeta 模块配置元数据
     */
    private static void validateMeta(JSONObject moduleMeta) {
        // 检查模块名称、业务前缀和配置类声明
        require(moduleMeta != null, "配置元数据必须是 JSON 对象");
        moduleMeta.keySet().removeAll(Set.of("id", "source", "sourceFile"));
        requireText(moduleMeta, "title");
        validatePath(moduleMeta.get("basePath"));
        if (moduleMeta.containsKey("configurationClass")) {
            requireText(moduleMeta, "configurationClass");
        }
        if (moduleMeta.containsKey("optionContributions")) {
            require(moduleMeta.get("optionContributions") instanceof JSONArray, "optionContributions 必须是数组");
            for (Object item : moduleMeta.getJSONArray("optionContributions")) {
                JSONObject addition = requireObject(item);
                validatePath(addition.get("path"));
                require(addition.get("options") instanceof JSONArray, "选项扩展必须提供 options 数组");
                for (Object choice : addition.getJSONArray("options")) {
                    require(requireObject(choice).containsKey("value"), "选项缺少 value");
                }
            }
        }

        // 检查可通过名称引用的条件
        JSONObject conditions = moduleMeta.containsKey("conditions") ? requireObject(moduleMeta.get("conditions")) : new JSONObject();
        for (String name : conditions.keySet()) {
            require(StringUtils.isNotBlank(name), "条件名称不能为空");
            validateCondition(requireObject(conditions.get(name)), 0);
        }

        // 检查分组标识和配置区域，将分组的重启设置应用到字段
        require(moduleMeta.get("groups") instanceof JSONArray, "groups 必须是数组");
        JSONArray groups = moduleMeta.getJSONArray("groups");
        Map<String, Integer> groupIndexes = new HashMap<>();
        for (int index = 0; index < groups.size(); index++) {
            JSONObject group = requireObject(groups.get(index));
            group.remove("configPath");
            requireText(group, "id");
            requireText(group, "title");
            String id = group.getString("id");
            Integer previous = groupIndexes.putIfAbsent(id, index);
            if (previous != null) {
                throw new IllegalArgumentException("第 %d 组「%s」和第 %d 组「%s」使用了相同的分组 ID「%s」. 请为两个分组设置不同的 ID"
                        .formatted(previous + 1, groups.getJSONObject(previous).getString("title"), index + 1, group.getString("title"), id));
            }
            require(!group.containsKey("restartRequired") || group.get("restartRequired") instanceof Boolean, "restartRequired 必须是布尔值");
            require(!group.containsKey("target") || Set.of("starbot", "advanced").contains(group.getString("target")), "未知配置 target");
            group.putIfAbsent("path", new JSONArray());
            group.putIfAbsent("fields", new JSONArray());
            validatePath(group.get("path"));
            require(group.get("fields") instanceof JSONArray, "fields 必须是数组");
            for (Object itemField : group.getJSONArray("fields")) {
                JSONObject field = requireObject(itemField);
                require(!field.containsKey("target") || Set.of("starbot", "advanced").contains(field.getString("target")), "未知配置 target");
                boolean advanced = "advanced".equals(getString(field, "target", getString(group, "target", "starbot")));
                if (advanced) {
                    require(field.containsKey("suggestedValue"), "高级配置 " + field.getString("key") + " 必须声明 suggestedValue");
                    require(!field.containsKey("suggestedReason") || field.get("suggestedReason") == null || field.get("suggestedReason") instanceof String, "suggestedReason 必须是文本");
                }
                prepareField(field, group.getBooleanValue("restartRequired"), advanced, 0);
            }
            expandConditions(group, conditions, 0);
            validateFields(group.get("fields"), 0);
            validateActions(group);
        }
    }

    /**
     * 登记字段及其页面位置，并继续登记对象的子字段
     * @param field 字段元数据
     * @param parent 父配置路径
     * @param siblings 同组字段
     * @param tab 模块标识
     * @param group 分组标识
     * @param source 声明来源
     * @param shared 是否为共享高级配置
     * @param validate 是否登记根校验声明
     */
    private void register(JSONObject field, List<Object> parent, JSONArray siblings, String tab, String group, String source, boolean shared, boolean validate) {
        // 确定字段路径，将交给 Spring 的配置标为重启生效
        List<Object> path = shared && parent.size() == 1 ? advancedPath(field) : List.copyOf(appendPath(parent, field.getString("key")));
        field.put("configPath", new JSONArray(path));
        if (field.containsKey("springKey") || shared) {
            field.put("restartRequired", true);
        }

        // 登记字段所在页面、校验规则和插件建议
        Declaration declaration = new Declaration(field, path, siblings, tab, group, source);
        if (validate) {
            declarations.add(declaration);
        }
        entries.computeIfAbsent(path, ignored -> new ArrayList<>()).add(declaration);
        if (shared) {
            participants.computeIfAbsent(path, ignored -> new JSONArray()).add(JSONObject.of("source", source, "module", tab, "type", field.get("type")));
        }
        if (shared && field.containsKey("suggestedValue")) {
            suggestionDefinitions.computeIfAbsent(path, ignored -> new JSONArray()).add(JSONObject.of("source", source, "module", tab, "type", field.get("type"),
                    "value", JSON.parse(JSON.toJSONString(normalizeString(field.get("suggestedValue"), field.get("type")), JSONWriter.Feature.WriteNulls)),
                    "reason", StringUtils.defaultIfBlank(getString(field, "suggestedReason", ""), "插件未说明原因")));
        }

        // 检查重复字段能否合并，以及父子路径是否冲突
        Declaration previous = editors.putIfAbsent(path, declaration);
        if (previous != null) {
            if (shared && !isCompatible(previous.field, field)) {
                field.put("typeConflict", true);
                previous.field.put("typeConflict", true);
            }
        } else {
            for (Map.Entry<List<Object>, Declaration> entry : editors.entrySet()) {
                List<Object> existing = entry.getKey();
                if (existing.equals(path)) {
                    continue;
                }
                boolean existingParent = existing.size() < path.size() && existing.equals(path.subList(0, existing.size()));
                boolean currentParent = path.size() < existing.size() && path.equals(existing.subList(0, path.size()));
                JSONObject existingField = entry.getValue().field;
                if (existingParent && !allowsChild(existingField, path.get(existing.size()))
                        || currentParent && !allowsChild(field, existing.get(path.size()))) {
                    Declaration parentField = existingParent ? entry.getValue() : declaration;
                    Declaration childField = existingParent ? declaration : entry.getValue();
                    addDeclarationDiagnostic("配置路径的父项与子项冲突",
                            "第一个配置项已经声明了整个值, 第二个配置项又在它下面声明了子项. 请将子项移到其他路径, 或把父项声明为包含该子项的对象",
                            List.of(diagnosticSource(parentField), diagnosticSource(childField)), true);
                }
            }
        }

        // 登记对象的子字段，校验时从整个对象开始
        if ("object".equals(field.getString("type"))) {
            for (Object child : field.getJSONArray("fields")) {
                register((JSONObject) child, path, field.getJSONArray("fields"), tab, group, source, shared, false);
            }
        }
    }

    /**
     * 将高级字段中的方括号键解析为已声明的 Map 条目路径
     * @param field 字段元数据
     * @return 字段的配置路径
     */
    private List<Object> advancedPath(JSONObject field) {
        String key = field.getString("key");
        int bracket = key.indexOf('[');
        if (bracket > 0 && bracket + 2 < key.length() && key.indexOf(']') == key.length() - 1
                && key.indexOf('[', bracket + 1) < 0) {
            String mapKey = key.substring(0, bracket);
            List<Object> path = List.of("advanced", mapKey);
            Declaration parent = editors.get(path);
            if (parent != null && "map".equals(parent.field.getString("type")) && !parent.field.getBooleanValue("typeConflict")) {
                String entryKey = key.substring(bracket + 1, key.length() - 1);
                for (Declaration declaration : entries.get(path)) {
                    JSONObject mapEntries = (JSONObject) declaration.field.computeIfAbsent("mapEntries", ignored -> new JSONObject());
                    mapEntries.putIfAbsent(entryKey, field);
                }
                return List.of("advanced", mapKey, entryKey);
            }
        }
        return List.of("advanced", key);
    }

    /**
     * 判断父字段是否允许单独声明指定子字段
     * @param field 父字段的配置元数据
     * @param child 子字段的键名
     * @return 是否允许子字段声明
     */
    private static boolean allowsChild(JSONObject field, Object child) {
        JSONObject mapEntries = field.getJSONObject("mapEntries");
        return "object".equals(field.getString("type")) || mapEntries != null && mapEntries.containsKey(child);
    }

    /**
     * 检查多个插件的选项或数值范围是否有共同取值
     * @param definitions 各模块对同一配置项的声明
     */
    private void checkConstraints(List<Declaration> definitions) {
        // 收集各插件始终要求的选项和数值范围
        List<Object> allowed = null;
        Object minimum = null;
        Object maximum = null;
        boolean required = false;
        List<JSONObject> sources = new ArrayList<>();
        for (Declaration declaration : definitions) {
            JSONObject definition = declaration.field;
            if (definition.containsKey("validateWhen")) {
                continue;
            }
            boolean fieldRequired = definition.getBooleanValue("required");
            JSONArray rules = definition.getJSONArray("validators");
            List<JSONObject> constraints = new ArrayList<>();
            if (rules != null) {
                for (Object rule : rules) {
                    if (!((JSONObject) rule).containsKey("when")) {
                        constraints.add((JSONObject) rule);
                    }
                }
            }
            if (definition.containsKey("minimum")) {
                constraints.add(JSONObject.of("rule", "minimum", "value", definition.get("minimum")));
            }
            if (definition.containsKey("maximum")) {
                constraints.add(JSONObject.of("rule", "maximum", "value", definition.get("maximum")));
            }
            if (definition.containsKey("options")) {
                JSONArray values = new JSONArray();
                definition.getJSONArray("options").forEach(item -> values.add(((JSONObject) item).get("value")));
                constraints.add(JSONObject.of("rule", "enum", "values", values));
            }

            // 找出各插件共同允许的选项和数值范围
            List<String> limits = new ArrayList<>();
            for (JSONObject constraint : constraints) {
                String rule = constraint.getString("rule");
                fieldRequired |= "required".equals(rule);
                if ("enum".equals(rule)) {
                    JSONArray values = constraint.getJSONArray("values");
                    limits.add("仅允许 " + JSON.toJSONString(values, JSONWriter.Feature.WriteNulls));
                    if (allowed == null) {
                        allowed = new ArrayList<>(values);
                    } else {
                        allowed.removeIf(value -> values.stream().noneMatch(other -> isEqual(value, other)));
                    }
                }
                if ("minimum".equals(rule)) {
                    limits.add("最小值为 " + constraint.get("value"));
                    if (minimum == null || ConfigRules.compare(constraint.get("value"), ">", minimum)) {
                        minimum = constraint.get("value");
                    }
                }
                if ("maximum".equals(rule)) {
                    limits.add("最大值为 " + constraint.get("value"));
                    if (maximum == null || ConfigRules.compare(constraint.get("value"), "<", maximum)) {
                        maximum = constraint.get("value");
                    }
                }
            }
            required |= fieldRequired;
            if (fieldRequired) {
                limits.add("必填");
            }
            if (CollectionUtils.isNotEmpty(limits)) {
                JSONObject source = diagnosticSource(declaration);
                source.put("detail", String.join(", ", limits));
                sources.add(source);
            }
        }

        // 取值范围没有交集时，根据字段是否必填记录错误或警告
        if (allowed != null && CollectionUtils.isEmpty(allowed) || minimum != null && maximum != null && ConfigRules.compare(minimum, ">", maximum)) {
            addDeclarationDiagnostic("配置项的取值要求互不兼容",
                    "没有一个值能同时满足下面列出的要求. " + (required ? "该配置项为必填, 请联系对应插件作者调整取值要求" : "该配置项可以留空"),
                    sources, required);
        }
    }

    /**
     * 配置元数据不符合要求时抛出异常
     * @param valid 约束是否成立
     * @param message 约束不成立时的错误消息
     */
    private static void require(boolean valid, String message) {
        if (!valid) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * 检查指定属性是否是长度合适的非空文本
     * @param value 配置元数据对象
     * @param key 属性名
     */
    private static void requireText(JSONObject value, String key) {
        require(value.get(key) instanceof String text && StringUtils.isNotBlank(text) && text.length() <= 256, key + " 必须是非空文本且不超过 256 字符");
    }

    /**
     * 检查配置路径的各段名称和长度
     * @param value 元数据中的路径段列表
     */
    private static void validatePath(Object value) {
        require(value instanceof List<?> list && list.size() <= 32 && list.stream().allMatch(item -> item instanceof String text && !text.isEmpty()),
                "路径必须是最多 32 段的字符串数组");
    }

    /**
     * 检查并返回 JSON 对象
     * @param value 元数据节点
     * @return JSON 对象
     */
    private static JSONObject requireObject(Object value) {
        require(value instanceof JSONObject, "配置元数据节点必须是 JSON 对象");
        return (JSONObject) value;
    }

    /**
     * 检查条件的来源、比较方式和嵌套结构
     * @param condition 元数据中声明的条件
     * @param depth 当前嵌套层级
     */
    private static void validateCondition(JSONObject condition, int depth) {
        require(depth <= 32, "条件嵌套超过 32 层");
        for (String key : List.of("all", "any")) {
            if (condition.containsKey(key)) {
                require(condition.get(key) instanceof JSONArray, "组合条件必须是数组");
                for (Object item : condition.getJSONArray(key)) {
                    validateCondition(requireObject(item), depth + 1);
                }
                return;
            }
        }
        if (condition.containsKey("not")) {
            validateCondition(requireObject(condition.get("not")), depth + 1);
            return;
        }
        require(!condition.containsKey("scope") || Set.of("parent", "current", "document").contains(condition.getString("scope")), "未知作用域");
        validatePath(condition.get("path"));
        require(ConfigRules.OPERATORS.contains(condition.getString("op")), "未知条件比较符");
        if (Set.of("matches", "!matches").contains(condition.getString("op"))) {
            ConfigRules.validatePattern(condition.getString("value"));
        }
    }

    /**
     * 清理系统生成的属性并确定字段修改后是否需要重启
     * @param field 字段元数据
     * @param inherited 分组或父字段的重启要求
     * @param forced 父对象或框架桥接是否强制重启
     * @param depth 嵌套层级
     */
    private static void prepareField(JSONObject field, boolean inherited, boolean forced, int depth) {
        require(depth <= 32, "配置元数据嵌套超过 32 层");
        field.keySet().removeAll(GENERATED_FIELD_KEYS);
        require(!field.containsKey("restartRequired") || field.get("restartRequired") instanceof Boolean, "restartRequired 必须是布尔值");
        boolean restart = forced || field.containsKey("springKey") || (field.containsKey("restartRequired") ? field.getBooleanValue("restartRequired") : inherited);
        field.put("restartRequired", restart);
        JSONArray fields = field.getJSONArray("fields");
        if (fields != null) {
            for (Object item : fields) {
                JSONObject child = requireObject(item);
                require(!child.containsKey("target"), "嵌套字段不能切换配置 target");
                prepareField(child, restart, restart, depth + 1);
            }
        }
        for (String key : List.of("items", "values", "keyMeta")) {
            if (field.get(key) instanceof JSONObject child) {
                require(!child.containsKey("target"), "嵌套字段不能切换配置 target");
                prepareField(child, restart, restart, depth + 1);
            }
        }
    }

    /**
     * 将校验规则引用的条件名称替换为条件内容
     * @param value 元数据节点
     * @param conditions 模块命名条件
     * @param depth 遍历深度
     */
    private static void expandConditions(Object value, JSONObject conditions, int depth) {
        require(depth <= 96, "配置元数据嵌套过深");
        if (value instanceof JSONObject object) {
            for (String key : new ArrayList<>(object.keySet())) {
                Object item = object.get(key);
                if (Set.of("default", "suggestedValue", "suggestedReason", "options", "ui").contains(key)) {
                    continue;
                }
                if (Set.of("visibleWhen", "validateWhen", "requiredWhen", "when").contains(key) && item instanceof String name) {
                    require(conditions.containsKey(name), "命名条件不存在: " + name);
                    object.put(key, JSON.parseObject(conditions.getJSONObject(name).toJSONString()));
                } else {
                    expandConditions(item, conditions, depth + 1);
                }
            }
        } else if (value instanceof JSONArray array) {
            for (Object item : array) {
                expandConditions(item, conditions, depth + 1);
            }
        }
    }

    /**
     * 校验字段列表及其嵌套层级
     * @param value 字段元数据列表
     * @param depth 当前嵌套层级
     */
    private static void validateFields(Object value, int depth) {
        require(value instanceof JSONArray, "fields 必须是数组");
        Set<String> keys = new HashSet<>();
        for (Object item : (JSONArray) value) {
            JSONObject field = requireObject(item);
            requireText(field, "key");
            require(keys.add(field.getString("key")), "同一对象内字段 key 重复");
            validateField(field, depth + 1);
        }
    }

    /**
     * 检查分组内的按钮配置
     * @param group 配置分组
     */
    private static void validateActions(JSONObject group) {
        // 检查动作列表以及同组动作的唯一标识
        if (!group.containsKey("actions")) {
            return;
        }
        require(group.get("actions") instanceof JSONArray, "actions 必须是数组");
        Set<String> actionIds = new HashSet<>();
        for (Object item : group.getJSONArray("actions")) {
            JSONObject action = requireObject(item);
            requireText(action, "id");
            require(actionIds.add(action.getString("id")), "同一配置分组中动作 id 重复");
            requireText(action, "label");

            // 检查处理器声明，只能填写类名或 Spring Bean 名称
            boolean bean = action.containsKey("handlerBean");
            require(bean != action.containsKey("handler"), "动作必须且只能声明 handler 或 handlerBean");
            requireText(action, bean ? "handlerBean" : "handler");

            // 检查动作的执行方式和确认提示
            require(!action.containsKey("synchronous") || action.get("synchronous") instanceof Boolean, "synchronous 必须是布尔值");
            if (action.containsKey("confirmation")) {
                requireText(action, "confirmation");
            }

            // 检查各动作参数的读取路径和 Spring 属性转换设置
            if (action.containsKey("parameters")) {
                JSONObject parameters = requireObject(action.get("parameters"));
                for (String name : parameters.keySet()) {
                    require(StringUtils.isNotBlank(name) && name.length() <= 256, "动作参数名必须是非空文本且不超过 256 字符");
                    JSONObject selector = requireObject(parameters.get(name));
                    if (selector.containsKey("springProperties")) {
                        require(Boolean.TRUE.equals(selector.get("springProperties")), "springProperties 必须为 true");
                        require(!selector.containsKey("path"), "springProperties 与 path 只能声明一个");
                    } else {
                        require(selector.containsKey("path"), "动作参数必须声明 path 或 springProperties");
                        validatePath(selector.get("path"));
                        require(CollectionUtils.isNotEmpty(selector.getJSONArray("path")), "动作参数不能选择整个配置文档");
                    }
                    if (selector.containsKey("keyPrefix")) {
                        requireText(selector, "keyPrefix");
                    }
                    if (selector.containsKey("keys")) {
                        require(selector.getBooleanValue("springProperties"), "keys 只支持 Spring 属性参数");
                        require(!selector.containsKey("keyPrefix"), "keys 与 keyPrefix 只能声明一个");
                        require(selector.get("keys") instanceof JSONArray keys && CollectionUtils.isNotEmpty(keys)
                                        && keys.stream().allMatch(key -> key instanceof String text && StringUtils.isNotBlank(text))
                                        && new HashSet<>(keys).size() == keys.size(),
                                "keys 必须是非空且无重复的配置键名数组");
                    }
                }
            }

            // 检查动作的显示及执行条件
            if (action.containsKey("visibleWhen")) {
                validateCondition(requireObject(action.get("visibleWhen")), 0);
            }
        }
    }

    /**
     * 判断两个共享字段的类型和子结构是否兼容
     * @param a 已有字段元数据
     * @param b 新字段元数据
     * @return 是否兼容
     */
    private static boolean isCompatible(JSONObject a, JSONObject b) {
        List<?> left = a.get("type") instanceof List<?> list ? list : List.of(a.get("type"));
        List<?> right = b.get("type") instanceof List<?> list ? list : List.of(b.get("type"));
        if (!new HashSet<>(left).equals(new HashSet<>(right))) {
            return false;
        }
        if (left.contains("array") && !isCompatible(a.getJSONObject("items"), b.getJSONObject("items"))
                || left.contains("map") && !isCompatible(a.getJSONObject("values"), b.getJSONObject("values"))) {
            return false;
        }
        if (left.contains("object")) {
            JSONArray af = a.getJSONArray("fields");
            JSONArray bf = b.getJSONArray("fields");
            return af.size() == bf.size() && af.stream().allMatch(item -> bf.stream().anyMatch(other -> {
                JSONObject x = (JSONObject) item;
                JSONObject y = (JSONObject) other;
                return isEqual(x.get("key"), y.get("key")) && isCompatible(x, y);
            }));
        }
        return true;
    }

    /**
     * 检查字段类型、子字段、选项和校验规则
     * @param field 字段元数据
     * @param depth 当前嵌套层级
     */
    private static void validateField(JSONObject field, int depth) {
        // 检查字段层数、重启设置和 Spring 属性键
        require(depth <= 32, "配置元数据嵌套超过 32 层");
        require(!field.containsKey("restartRequired") || field.get("restartRequired") instanceof Boolean, "restartRequired 必须是布尔值");
        if (field.containsKey("springKey")) {
            requireText(field, "springKey");
        }

        // 校验字段类型，并递归检查对象和集合的子结构
        List<?> types = field.get("type") instanceof List<?> list ? list : Collections.singletonList(field.get("type"));
        require(CollectionUtils.isNotEmpty(types) && types.stream().allMatch(type -> type instanceof String name && TYPES.contains(name)), "未知字段类型");
        if (field.get("ui") instanceof JSONObject ui) {
            for (String key : List.of("confirmOnEnable", "confirmOnDisable")) {
                if (ui.containsKey(key)) {
                    require("boolean".equals(field.get("type")), key + " 只支持 boolean 字段");
                    requireText(ui, key);
                }
            }
        }
        if (field.containsKey("emptyAsNull")) {
            require(field.get("emptyAsNull") instanceof Boolean, "emptyAsNull 必须是布尔值");
            if (field.getBooleanValue("emptyAsNull")) {
                require(types.contains("string") && field.getBooleanValue("nullable"), "emptyAsNull 只支持允许为空值的文本字段");
                JSONObject ui = field.getJSONObject("ui");
                require(ui == null || !ui.getBooleanValue("emptyAsDefault"), "emptyAsNull 和 emptyAsDefault 不能同时启用");
            }
        }
        if (types.contains("object")) {
            validateFields(field.get("fields"), depth);
        }
        if (types.contains("array")) {
            validateField(requireObject(field.get("items")), depth + 1);
        }
        if (types.contains("map")) {
            validateField(requireObject(field.get("values")), depth + 1);
            if (field.containsKey("keyMeta")) {
                JSONObject keyMeta = field.getJSONObject("keyMeta");
                if (!keyMeta.containsKey("type")) {
                    keyMeta.put("type", "string");
                }
                validateField(keyMeta, depth + 1);
            }
        }

        // 检查显示、必填和校验条件
        for (String key : List.of("visibleWhen", "requiredWhen", "validateWhen")) {
            if (field.containsKey(key)) {
                validateCondition(requireObject(field.get(key)), depth + 1);
            }
        }

        // 检查动态选项来源和固定选项清单
        if (field.containsKey("optionsFrom")) {
            JSONObject options = requireObject(field.get("optionsFrom"));
            require(!options.containsKey("scope") || Set.of("parent", "current", "document").contains(options.getString("scope")), "未知作用域");
            validatePath(options.get("path"));
            require(Set.of("keys", "values").contains(options.getString("source")), "动态选项 source 只支持 keys 或 values");
            require(!options.containsKey("enforce") || options.get("enforce") instanceof Boolean, "动态选项 enforce 必须是布尔值");
        }
        if (field.containsKey("options")) {
            require(field.get("options") instanceof JSONArray, "options 必须是数组");
            for (Object option : field.getJSONArray("options")) {
                require(requireObject(option).containsKey("value"), "选项缺少 value");
            }
        }

        // 检查格式名称和数值上下限
        if (field.containsKey("format")) {
            require(ConfigRules.FORMATS.contains(field.getString("format")), "未知 format");
        }
        for (String key : List.of("minimum", "maximum")) {
            require(!field.containsKey(key) || field.get(key) instanceof Number, key + " 必须是数字");
        }

        // 检查每条规则的参数、取值来源和正则表达式
        if (field.containsKey("validators")) {
            require(field.get("validators") instanceof JSONArray, "validators 必须是数组");
            for (Object item : field.getJSONArray("validators")) {
                JSONObject rule = requireObject(item);
                require(ConfigRules.RULES.contains(rule.getString("rule")), "未知校验规则");
                validateRule(rule);
                require(!rule.containsKey("scope") || Set.of("parent", "current", "document").contains(rule.getString("scope")), "未知作用域");
                if (rule.containsKey("path")) {
                    validatePath(rule.get("path"));
                }
                if (rule.containsKey("when")) {
                    validateCondition(requireObject(rule.get("when")), depth + 1);
                }
                if (Set.of("pattern", "keyPattern").contains(rule.getString("rule"))) {
                    ConfigRules.validatePattern(rule.getString("value"));
                }
                if ("compare".equals(rule.getString("rule"))) {
                    require(ConfigRules.OPERATORS.contains(rule.getString("op")), "未知校验比较符");
                }
            }
        }
    }

    /**
     * 检查规则参数是否齐全、类型是否正确
     * @param rule 元数据中声明的校验规则
     */
    private static void validateRule(JSONObject rule) {
        // 检查数值范围和文本匹配规则所需的参数
        String name = rule.getString("rule");
        if (Set.of("minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "multipleOf", "minLength", "maxLength", "length", "minItems", "maxItems", "minKeys", "maxKeys").contains(name)) {
            require(rule.get("value") instanceof Number, "数值规则缺少数字 value");
        }
        if ("range".equals(name)) {
            require(rule.get("min") instanceof Number && rule.get("max") instanceof Number, "range 需要数字 min 和 max");
        }
        if (Set.of("pattern", "keyPattern", "format").contains(name)) {
            require(rule.get("value") instanceof String, name + " 需要文本 value");
        }
        if ("format".equals(name)) {
            require(ConfigRules.FORMATS.contains(rule.getString("value")), "未知 format 规则");
        }

        // 检查选项、对象键和多个字段联动时使用的路径
        if ("enum".equals(name)) {
            require(rule.get("values") instanceof JSONArray, "enum 需要 values 数组");
        }
        if (Set.of("requiredKeys", "allowedKeys").contains(name)) {
            validatePath(rule.get("keys"));
        }
        if (Set.of("atLeastOne", "exactlyOne", "allOrNone", "mutuallyExclusive").contains(name)) {
            require(rule.get("paths") instanceof JSONArray, name + " 需要 paths 数组");
            for (Object item : rule.getJSONArray("paths")) {
                validatePath(item);
            }
        }

        // 检查集合元素及匹配次数规则
        if (Set.of("contains", "minContains", "maxContains").contains(name)) {
            require(rule.containsKey("value"), name + " 需要 value");
        }
        if (Set.of("minContains", "maxContains").contains(name)) {
            require(rule.get("count") instanceof Number, name + " 需要 count");
        }

        // 检查条件规则和时长范围
        if (Set.of("requiredWhen", "forbiddenWhen", "nonEmptyOnlyWhen").contains(name)) {
            require(rule.get("when") instanceof JSONObject, name + " 需要 when 条件");
        }
        if ("durationRange".equals(name)) {
            Duration minimum = parseDuration(rule.get("min"));
            Duration maximum = parseDuration(rule.get("max"));
            require(minimum != null && maximum != null && minimum.compareTo(maximum) <= 0, "durationRange 需要合法的 min 和 max，且 min 不得大于 max");
        }

        // 检查哪些字段会触发校验，以及触发后哪些字段必填
        if ("dependentRequired".equals(name)) {
            require(rule.get("dependencies") instanceof JSONArray, "dependentRequired 需要 dependencies 数组");
            for (Object item : rule.getJSONArray("dependencies")) {
                JSONObject dependency = requireObject(item);
                validatePath(dependency.get("when"));
                require(dependency.get("require") instanceof JSONArray, "依赖规则 require 必须是路径数组");
                for (Object required : dependency.getJSONArray("require")) {
                    validatePath(required);
                }
            }
        }
    }
}
