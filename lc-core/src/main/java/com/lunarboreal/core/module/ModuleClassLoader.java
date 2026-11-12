package com.lunarboreal.core.module;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * 模块类加载器：父加载器为 core 插件类加载器（可见 lc-api、Paper API 与 libraries），
 * 模块之间互不可见。类为父优先；资源为子优先（避免 core jar 中的 config.yml、lang/*.yml 遮蔽模块资源）。
 */
public final class ModuleClassLoader extends URLClassLoader {

    static {
        ClassLoader.registerAsParallelCapable();
    }

    private final String moduleId;

    public ModuleClassLoader(String moduleId, URL jar, ClassLoader parent) {
        super("LC-Module-" + moduleId, new URL[]{jar}, parent);
        this.moduleId = moduleId;
    }

    public String moduleId() {
        return moduleId;
    }

    @Override
    public URL getResource(String name) {
        URL url = findResource(name);
        return url != null ? url : super.getResource(name);
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        List<URL> out = new ArrayList<>(Collections.list(findResources(name)));
        ClassLoader parent = getParent();
        if (parent != null) out.addAll(Collections.list(parent.getResources(name)));
        return Collections.enumeration(out);
    }

    @Override
    public InputStream getResourceAsStream(String name) {
        URL url = getResource(name);
        if (url == null) return null;
        try {
            var conn = url.openConnection();
            // 禁用 jar 缓存，避免热重载后读到旧内容 / Windows 下文件被锁
            conn.setUseCaches(false);
            return conn.getInputStream();
        } catch (IOException e) {
            return null;
        }
    }
}
