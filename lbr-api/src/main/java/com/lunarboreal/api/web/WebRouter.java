package com.lunarboreal.api.web;

/**
 * 路由构建器。path 相对于 /api/ext/{namespace}，路径参数写成 {name}，例如 "/accounts/{uuid}"。
 * permission 为 null 表示任何已登录用户可访问。
 */
public interface WebRouter {

    WebRouter get(String path, String permission, WebHandler handler);

    WebRouter post(String path, String permission, WebHandler handler);

    WebRouter put(String path, String permission, WebHandler handler);

    WebRouter delete(String path, String permission, WebHandler handler);
}
