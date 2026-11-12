package com.lunarboreal.api.web;

import java.util.Map;
import java.util.concurrent.Callable;

public interface WebContext {

    String method();

    String path();

    String pathParam(String name);

    /** 查询参数，不存在返回 null。 */
    String query(String name);

    default String query(String name, String def) {
        String v = query(name);
        return v == null || v.isEmpty() ? def : v;
    }

    default int queryInt(String name, int def) {
        try { return Integer.parseInt(query(name, String.valueOf(def))); } catch (NumberFormatException e) { return def; }
    }

    String header(String name);

    String ip();

    String body();

    /** 使用 Gson 反序列化请求体。 */
    <T> T bodyAs(Class<T> type);

    /** 请求体 JSON 对象，数字为 Double。 */
    Map<String, Object> bodyMap();

    WebUser user();

    void status(int code);

    /** 以 JSON 输出（Gson）。 */
    void json(Object value);

    /** 输出 {"ok":true,"message":message}。 */
    default void ok(String message) {
        json(Map.of("ok", true, "message", message == null ? "" : message));
    }

    /** 输出分页数据 {"total":..,"items":[..]}，供 TableSection 使用。 */
    default void page(long total, java.util.List<?> items) {
        json(Map.of("total", total, "items", items));
    }

    /** 在服务器主线程执行并等待结果（超时 10 秒）。 */
    <T> T sync(Callable<T> task) throws Exception;
}
