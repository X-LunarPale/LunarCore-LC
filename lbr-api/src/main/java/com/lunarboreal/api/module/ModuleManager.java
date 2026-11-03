package com.lunarboreal.api.module;

import java.util.Collection;
import java.util.Optional;

public interface ModuleManager {

    Collection<LbrModule> loaded();

    Optional<LbrModule> get(String id);

    boolean isEnabled(String id);

    /** 启用（如未加载会先从 modules/ 加载）。 */
    boolean enable(String id);

    /** 禁用并卸载类加载器；会级联禁用依赖它的模块。 */
    boolean disable(String id);

    boolean reload(String id);

    /** 扫描 modules/ 目录发现的所有模块描述（包括未启用的）。 */
    Collection<ModuleDescription> discovered();
}
