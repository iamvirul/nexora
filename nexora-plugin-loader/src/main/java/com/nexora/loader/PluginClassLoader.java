package com.nexora.loader;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.Set;

/**
 * Child-first ClassLoader for plugin isolation.
 *
 * Classes in API_PACKAGES always delegate to the parent — this ensures type identity
 * across plugin boundaries (e.g., two plugins can both implement Capability and the
 * engine sees them as the same interface).
 *
 * All other classes resolve from the plugin JAR first; only if not found does it
 * fall back to the parent. This allows each plugin to bundle its own dependency
 * versions without conflicting with other plugins or the engine.
 */
public final class PluginClassLoader extends URLClassLoader {

    private static final Set<String> API_PACKAGES =
            Set.of("com.nexora.spi.", "com.nexora.core.", "org.slf4j.", "java.", "javax.", "sun.", "jdk.");

    public PluginClassLoader(URL[] pluginJars, ClassLoader parent) {
        super(pluginJars, parent);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (isApiClass(name)) {
            return super.loadClass(name, resolve);
        }
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded != null) {
                if (resolve) resolveClass(loaded);
                return loaded;
            }
            try {
                Class<?> found = findClass(name);
                if (resolve) resolveClass(found);
                return found;
            } catch (ClassNotFoundException e) {
                return super.loadClass(name, resolve);
            }
        }
    }

    private static boolean isApiClass(String className) {
        for (String pkg : API_PACKAGES) {
            if (className.startsWith(pkg)) return true;
        }
        return false;
    }
}
