package com.starlwr.bot.core.plugin;

import com.alibaba.fastjson2.JSON;
import lombok.Getter;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.AnnotatedGenericBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionReaderUtils;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * StarBot 插件加载器
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class StarBotPluginLoader implements EnvironmentAware, BeanDefinitionRegistryPostProcessor {
    private Environment environment;

    private final List<StarBotPlugin> plugins = new ArrayList<>();

    @Getter
    private final Map<StarBotPluginMeta, List<Dependency>> needDownloadDependencies = new HashMap<>();

    private final Map<String, ClassLoader> componentClassLoaders = new HashMap<>();

    private final Pattern jarPattern = Pattern.compile("^(.+)-([\\d.]+[\\w.-]*)\\.jar$");

    @Override
    public void setEnvironment(@NonNull Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanDefinitionRegistry(@NonNull BeanDefinitionRegistry registry) {
        List<File> pluginJars = scanJarFiles("plugins");

        List<File> libs = new ArrayList<>();
        libs.addAll(scanJarFiles("lib"));
        libs.addAll(scanJarFiles("plugins"));
        libs.addAll(scanJarFiles("plugins-lib"));

        Set<String> existsDependencies = new HashSet<>();
        existsDependencies.add("starbot-core");
        for (File lib : libs) {
            Matcher matcher = jarPattern.matcher(lib.getName());
            if (matcher.matches()) {
                existsDependencies.add(matcher.group(1));
            } else {
                log.error("无法解析的依赖: {}", lib.getName());
            }
        }

        log.info("开始注册 StarBot 插件");
        for (File jar : pluginJars) {
            try {
                URL jarUrl = jar.toURI().toURL();
                URLClassLoader pluginClassLoader = new URLClassLoader(new URL[] {jarUrl}, getClass().getClassLoader());
                try (JarFile jarFile = new JarFile(jar)) {
                    List<Dependency> missingDependencies = new ArrayList<>();

                    StarBotPlugin plugin = new StarBotPlugin();
                    plugin.setJarFile(jar);
                    plugin.setClassLoader(pluginClassLoader);

                    Enumeration<JarEntry> entries = jarFile.entries();
                    while (entries.hasMoreElements()) {
                        JarEntry entry = entries.nextElement();

                        if ("plugin.json".equals(entry.getName())) {
                            try (InputStream input = jarFile.getInputStream(entry)) {
                                String json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                                plugin.setMeta(JSON.parseObject(json, StarBotPluginMeta.class));
                            }
                        } else if ("dependency.json".equals(entry.getName())) {
                            try (InputStream input = jarFile.getInputStream(entry)) {
                                String json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                                List<Dependency> dependencies = JSON.parseArray(json, Dependency.class);
                                missingDependencies.addAll(dependencies.stream().filter(dependency -> !existsDependencies.contains(dependency.getArtifactId())).toList());
                            }
                        } else if (entry.getName().endsWith(".class")) {
                            if (!entry.getName().contains("META-INF") && !entry.getName().contains("module-info") && !entry.getName().contains("package-info") && !entry.getName().contains("$")) {
                                String className = entry.getName().replace('/', '.').replace(".class", "");
                                plugin.getComponentClassNames().add(className);
                            }
                        }
                    }

                    if (plugin.getMeta() != null) {
                        needDownloadDependencies.put(plugin.getMeta(), missingDependencies);
                        plugins.add(plugin);

                        StarBotPluginMeta meta = plugin.getMeta();
                        log.info("已注册插件 {} v{} --{}: {}", meta.getName(), meta.getVersion(), meta.getAuthor(), meta.getDescription());
                    }
                }
            } catch (Exception e) {
                log.error("插件 {} 注册异常", jar.getName(), e);
            }
        }

        if (needDownloadDependencies.values().stream().allMatch(List::isEmpty)) {
            log.info("开始加载 StarBot 插件");

            for (StarBotPlugin plugin : plugins) {
                for (String className : plugin.getComponentClassNames()) {
                    try {
                        Class<?> clazz = Class.forName(className, false, plugin.getClassLoader());
                        componentClassLoaders.put(clazz.getName(), plugin.getClassLoader());

                        if (!clazz.isInterface() && !Modifier.isAbstract(clazz.getModifiers()) && clazz.isAnnotationPresent(StarBotComponent.class)) {
                            log.debug("加载 StarBot 组件: {} - {}", plugin.getJarFile().getName(), clazz.getName());
                            plugin.getComponentClasses().add(clazz);
                        }
                    } catch (Exception e) {
                        log.error("加载 StarBot 组件 {} - {} 异常", plugin.getJarFile().getName(), className, e);
                    }
                }

                if (!plugin.getComponentClasses().isEmpty()) {
                    for (Class<?> clazz : plugin.getComponentClasses()) {
                        AnnotatedGenericBeanDefinition beanDefinition = new AnnotatedGenericBeanDefinition(clazz);
                        AnnotationConfigUtils.processCommonDefinitionAnnotations(beanDefinition);

                        AnnotatedTypeMetadata metadata = beanDefinition.getMetadata();
                        if (metadata.isAnnotated(Profile.class.getName())) {
                            Map<String, Object> attributes = metadata.getAnnotationAttributes(Profile.class.getName());
                            if (attributes != null) {
                                String[] profiles = (String[]) attributes.get("value");
                                if (!environment.acceptsProfiles(Profiles.of(profiles))) {
                                    log.debug("StarBot 组件: {} - {} 不匹配 @Profile 条件: {}, 不注册至 Spring 容器中", plugin.getJarFile().getName(), clazz.getName(), Arrays.toString(profiles));
                                    continue;
                                }
                            }
                        }
                        if (metadata.isAnnotated(Scope.class.getName())) {
                            Map<String, Object> attributes = metadata.getAnnotationAttributes(Scope.class.getName());
                            if (attributes != null) {
                                String scope = (String) attributes.get("value");
                                if ("prototype".equals(scope)) {
                                    beanDefinition.setScope(BeanDefinition.SCOPE_PROTOTYPE);
                                }
                            }
                        }

                        BeanDefinitionReaderUtils.registerBeanDefinition(new BeanDefinitionHolder(beanDefinition, new AnnotationBeanNameGenerator().generateBeanName(beanDefinition, registry)), registry);
                    }

                    StarBotPluginMeta meta = plugin.getMeta();
                    log.info("已加载插件 {} v{} --{}: {}", meta.getName(), meta.getVersion(), meta.getAuthor(), meta.getDescription());
                } else {
                    log.warn("插件 {} v{} --{}: {} 没有可加载的组件, 将跳过加载", plugin.getMeta().getName(), plugin.getMeta().getVersion(), plugin.getMeta().getAuthor(), plugin.getMeta().getDescription());
                }
            }
        } else {
            return;
        }

        AnnotationConfigUtils.registerAnnotationConfigProcessors(registry);
        new ConfigurationClassPostProcessor().postProcessBeanDefinitionRegistry(registry);

        plugins.removeIf(plugin -> plugin.getComponentClasses().isEmpty());
        if (plugins.isEmpty()) {
            log.info("没有需要加载的 StarBot 插件");
        } else {
            log.info("成功加载了 {} 个 StarBot 插件", plugins.size());
        }
    }

    @Override
    public void postProcessBeanFactory(@NonNull ConfigurableListableBeanFactory beanFactory) {
        StarBotClassLoader starBotClassLoader = new StarBotClassLoader(componentClassLoaders, beanFactory.getBeanClassLoader());
        beanFactory.setBeanClassLoader(starBotClassLoader);
        Thread.currentThread().setContextClassLoader(starBotClassLoader);
        BeanDefinitionRegistryPostProcessor.super.postProcessBeanFactory(beanFactory);
    }

    /**
     * 扫描目录中的依赖列表
     * @param path 目录
     * @return 依赖列表
     */
    private List<File> scanJarFiles(String path) {
        try {
            File pluginDir = new File(path);
            if (pluginDir.exists() && pluginDir.isDirectory()) {
                File[] jars = pluginDir.listFiles((dir, name) -> name.endsWith(".jar"));
                if (jars != null) {
                    return Arrays.asList(jars);
                }
            }
        } catch (Exception e) {
            log.error("扫描依赖包列表异常", e);
        }

        return new ArrayList<>();
    }
}
