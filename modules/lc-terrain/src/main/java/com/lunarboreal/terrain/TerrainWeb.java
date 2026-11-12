package com.lunarboreal.terrain;

import com.lunarboreal.api.web.HttpException;
import com.lunarboreal.api.web.WebContext;
import com.lunarboreal.api.web.WebPanel;

import java.util.Map;
import java.util.concurrent.Callable;

final class TerrainWeb {
    private final TerrainModule module;

    TerrainWeb(TerrainModule module) {
        this.module = module;
    }

    void register() {
        module.webPermission("terrain.view", "查看宏伟地形世界");
        module.webPermission("terrain.manage", "创建、加载、卸载和设置原生地形世界出生点");
        module.webPermission("terrain.teleport", "传送在线玩家到地形世界");
        module.webRoutes(r -> r
                .get("/status", "terrain.view", c -> c.json(sync(c, module::status)))
                .post("/height-ranges", "terrain.manage", c -> {
                    Map<String, Object> body = c.bodyMap();
                    sync(c, () -> { module.addHeightRange(body, actor(c)); return null; });
                    c.ok("高度范围已保存，完整重启服务器后可用");
                })
                .post("/worlds", "terrain.manage", c -> {
                    Map<String, Object> body = c.bodyMap();
                    sync(c, () -> { module.create(body, actor(c)); return null; });
                    accepted(c, String.valueOf(body.get("name")), "世界创建已排队");
                })
                .post("/worlds/{name}/load", "terrain.manage", c -> {
                    String name = c.pathParam("name");
                    sync(c, () -> { module.queueLoad(name, actor(c)); return null; });
                    accepted(c, name, "世界加载已排队");
                })
                .post("/worlds/{name}/unload", "terrain.manage", c -> {
                    String name = c.pathParam("name");
                    sync(c, () -> { module.unload(name, actor(c)); return null; });
                    c.ok("地形世界已保存并卸载");
                })
                .post("/worlds/{name}/spawn", "terrain.manage", c -> {
                    String name = c.pathParam("name");
                    Map<String, Object> body = c.bodyMap();
                    sync(c, () -> { module.setSpawn(name, body, actor(c)); return null; });
                    c.ok("地形世界出生点已保存");
                })
                .post("/worlds/{name}/teleport", "terrain.teleport", c -> {
                    Object player = c.bodyMap().get("player");
                    if (!(player instanceof String name)) throw HttpException.badRequest("请填写在线玩家名称");
                    sync(c, () -> { module.teleport(c.pathParam("name"), name, actor(c)); return null; });
                    accepted(c, c.pathParam("name"), "传送请求已提交，请核对玩家位置");
                }));
        module.webPanel(WebPanel.of("tectonic", "Tectonic 宏伟地形").icon("mountain").group("服务器")
                .permission("terrain.view")
                .section(new WebPanel.Stats("原生生成器", "status"))
                .section(new WebPanel.Text("生成范围", "为独立新世界固定生成参数，使用原生地形生成器。")));
    }

    private static void accepted(WebContext context, String name, String message) {
        context.status(202);
        context.json(Map.of("ok", true, "message", message, "name", name));
    }

    private static String actor(WebContext context) {
        return "web:" + context.user().username();
    }

    private static <T> T sync(WebContext context, Callable<T> task) throws Exception {
        try {
            return context.sync(task);
        } catch (Exception e) {
            for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                if (cause instanceof IllegalArgumentException) throw HttpException.badRequest(cause.getMessage());
            }
            throw e;
        }
    }
}
