package com.starlwr.bot.core.plugin;

import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * StarBot 类加载器
 */
@Slf4j
public class StarBotClassLoader extends ClassLoader {
    private final Map<String, ClassLoader> delegates;

    public StarBotClassLoader(Map<String, ClassLoader> delegates, ClassLoader parent) {
        super(parent);
        this.delegates = delegates;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        ClassLoader delegate = delegates.get(name);

        if (delegate == null && name.contains("$$SpringCGLIB$$")) {
            for (Map.Entry<String, ClassLoader> entry : delegates.entrySet()) {
                String packagePrefix = entry.getKey();
                if (name.startsWith(packagePrefix)) {
                    delegate = entry.getValue();
                    break;
                }
            }
        }

        if (delegate != null) {
            try {
                Class<?> clazz = delegate.loadClass(name);
                if (resolve) {
                    resolveClass(clazz);
                }
                return clazz;
            } catch (ClassNotFoundException ignored) {
            }
        }

        return super.loadClass(name, resolve);
    }
}
