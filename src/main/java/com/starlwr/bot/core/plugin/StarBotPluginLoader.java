package com.starlwr.bot.core.plugin;

import com.alibaba.fastjson2.JSON;
import lombok.Getter;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.AnnotatedGenericBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionReaderUtils;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.context.annotation.AnnotationBeanNameGenerator;
import org.springframework.context.annotation.AnnotationConfigUtils;
import org.springframework.context.annotation.ConfigurationClassPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
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
public class StarBotPluginLoader implements BeanDefinitionRegistryPostProcessor {
    private final Map<StarBotPluginMeta, List<Class<?>>> pluginComponents = new HashMap<>();

    @Getter
    private final Map<StarBotPluginMeta, List<Dependency>> needDownloadDependencies = new HashMap<>();

    private final Pattern jarPattern = Pattern.compile("^(.+)-([\\d.]+[\\w.-]*)\\.jar$");

    @Override
    public void postProcessBeanDefinitionRegistry(@NonNull BeanDefinitionRegistry registry) {
        log.info("开始注册 StarBot 插件");

        List<File> pluginJars = scanJarFiles("plugins");

        List<File> libs = new ArrayList<>();
        libs.addAll(scanJarFiles("lib"));
        libs.addAll(scanJarFiles("plugins-lib"));

        Set<String> existsDependencies = new HashSet<>();
        for (File lib : libs) {
            Matcher matcher = jarPattern.matcher(lib.getName());
            if (matcher.matches()) {
                existsDependencies.add(matcher.group(1));
            } else {
                log.error("无法解析的依赖: {}", lib.getName());
            }
        }

        for (File jar : pluginJars) {
            try {
                URL jarUrl = jar.toURI().toURL();
                URLClassLoader pluginClassLoader = new URLClassLoader(new URL[] {jarUrl}, getClass().getClassLoader());
                try (JarFile jarFile = new JarFile(jar)) {
                    boolean isPlugin = false;
                    StarBotPluginMeta meta = null;
                    List<Dependency> missingDependencies = new ArrayList<>();
                    List<Class<?>> componentClasses = new ArrayList<>();

                    Enumeration<JarEntry> entries = jarFile.entries();
                    while (entries.hasMoreElements()) {
                        JarEntry entry = entries.nextElement();

                        if ("plugin.json".equals(entry.getName())) {
                            try (InputStream input = jarFile.getInputStream(entry)) {
                                String json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                                isPlugin = true;
                                meta = JSON.parseObject(json, StarBotPluginMeta.class);
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
                                Class<?> clazz = Class.forName(className, false, pluginClassLoader);

                                if (!clazz.isInterface() && !Modifier.isAbstract(clazz.getModifiers()) && clazz.isAnnotationPresent(StarBotComponent.class)) {
                                    log.debug("注册 StarBot 组件: {} - {}", jar.getName(), clazz.getName());
                                    componentClasses.add(clazz);
                                }
                            }
                        }
                    }

                    if (isPlugin && !componentClasses.isEmpty()) {
                        for (Class<?> clazz : componentClasses) {
                            AnnotatedGenericBeanDefinition beanDefinition = new AnnotatedGenericBeanDefinition(clazz);
                            AnnotationConfigUtils.processCommonDefinitionAnnotations(beanDefinition);
                            BeanDefinitionReaderUtils.registerBeanDefinition(new BeanDefinitionHolder(beanDefinition, new AnnotationBeanNameGenerator().generateBeanName(beanDefinition, registry)), registry);
                        }

                        pluginComponents.put(meta, componentClasses);
                        needDownloadDependencies.put(meta, missingDependencies);
                        log.info("已注册插件 {} v{} --{}: {}", meta.getName(), meta.getVersion(), meta.getAuthor(), meta.getDescription());
                    }
                }
            } catch (Exception e) {
                log.error("插件 {} 注册异常", jar.getName(), e);
            }
        }

        AnnotationConfigUtils.registerAnnotationConfigProcessors(registry);
        new ConfigurationClassPostProcessor().postProcessBeanDefinitionRegistry(registry);

        if (pluginComponents.isEmpty()) {
            log.info("没有需要加载的 StarBot 插件");
        } else {
            log.info("注册了 {} 个 StarBot 插件", pluginComponents.size());
        }
    }

    @Override
    public void postProcessBeanFactory(@NonNull ConfigurableListableBeanFactory beanFactory) {
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
