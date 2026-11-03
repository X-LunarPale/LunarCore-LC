package com.lunarboreal.api.web;

import java.util.Set;

public interface WebUser {

    String username();

    String role();

    /** 角色拥有的权限节点，"*" 表示全部；支持 "economy.*" 通配。 */
    Set<String> permissions();

    boolean hasPermission(String node);
}
