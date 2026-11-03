package com.lunarboreal.core.web;

import com.lunarboreal.api.web.HttpException;
import com.lunarboreal.api.web.WebContext;
import com.lunarboreal.api.web.WebUser;
import io.javalin.http.Context;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class WebContextImpl implements WebContext {

    private final Context ctx;
    private final Plugin plugin;
    private final String path;
    private final Map<String, String> params;
    private final WebUserImpl user;
    private final String ip;
    private String body;
    private Map<String, Object> bodyMap;

    WebContextImpl(Context ctx, Plugin plugin, String path, Map<String, String> params, WebUserImpl user, String ip) {
        this.ctx = ctx;
        this.plugin = plugin;
        this.path = path;
        this.params = params;
        this.user = user;
        this.ip = ip;
    }

    @Override public String method() { return ctx.method().name(); }

    @Override public String path() { return path; }

    @Override public String pathParam(String name) { return params.get(name); }

    @Override public String query(String name) { return ctx.queryParam(name); }

    @Override public String header(String name) { return ctx.header(name); }

    @Override public String ip() { return ip; }

    @Override
    public String body() {
        if (body == null) body = ctx.body();
        return body;
    }

    @Override
    public <T> T bodyAs(Class<T> type) {
        try {
            return Json.GSON.fromJson(body(), type);
        } catch (RuntimeException e) {
            throw HttpException.badRequest("请求体格式错误：" + e.getMessage());
        }
    }

    @Override
    public Map<String, Object> bodyMap() {
        if (bodyMap == null) bodyMap = Json.map(body());
        return bodyMap;
    }

    @Override public WebUser user() { return user; }

    WebUserImpl userImpl() { return user; }

    @Override public void status(int code) { ctx.status(code); }

    @Override
    public void json(Object value) {
        ctx.header("Cache-Control", "no-store").contentType("application/json; charset=utf-8").result(Json.GSON.toJson(value));
    }

    void image(byte[] bytes, String contentType) {
        ctx.contentType(contentType).header("Cache-Control", "no-cache").result(bytes);
    }

    @Override
    public <T> T sync(Callable<T> task) throws Exception {
        return runSync(plugin, task);
    }

    static <T> T runSync(Plugin plugin, Callable<T> task) throws Exception {
        if (Bukkit.isPrimaryThread()) return task.call();
        Future<T> f = Bukkit.getScheduler().callSyncMethod(plugin, task);
        try {
            return f.get(10, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            f.cancel(false);
            throw new HttpException(504, "服务器主线程响应超时");
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof Exception ex) throw ex;
            throw new RuntimeException(c);
        }
    }

    /** 必填字符串字段。 */
    String required(String key) {
        String v = Json.str(bodyMap(), key);
        if (v == null || v.isBlank()) throw HttpException.badRequest("缺少参数：" + key);
        return v;
    }

    String optional(String key) {
        return Json.str(bodyMap(), key);
    }

    /** 读取 body 字段，若没有则从 row 中读取（WebPanel Action 的请求体结构为 {row:{..}, 字段..}）。 */
    @SuppressWarnings("unchecked")
    String fieldOrRow(String key) {
        String v = Json.str(bodyMap(), key);
        if (v != null) return v;
        Object row = bodyMap().get("row");
        if (row instanceof Map<?, ?> m) return Json.str((Map<String, Object>) m, key);
        return null;
    }
}
