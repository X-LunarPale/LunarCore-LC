package com.lunarboreal.api.web;

@FunctionalInterface
public interface WebHandler {
    /** 在 HTTP 工作线程执行（非主线程）。访问 Bukkit API 请用 {@link WebContext#sync}。抛出 {@link HttpException} 返回对应状态码。 */
    void handle(WebContext ctx) throws Exception;
}
