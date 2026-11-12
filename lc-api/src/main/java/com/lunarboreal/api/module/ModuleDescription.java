package com.lunarboreal.api.module;

import java.util.List;

/**
 * 模块 jar 根目录 module.yml 的内容：
 * <pre>
 * id: economy            # 小写唯一 ID，同时作为语言/权限/web 命名空间
 * name: 经济系统
 * version: 1.0.0
 * main: com.lunarboreal.economy.EconomyModule
 * description: ...
 * authors: [LC]
 * depends: []            # 硬依赖模块 ID
 * softdepends: []
 * </pre>
 */
public record ModuleDescription(
        String id,
        String name,
        String version,
        String main,
        String description,
        List<String> authors,
        List<String> depends,
        List<String> softDepends
) {}
