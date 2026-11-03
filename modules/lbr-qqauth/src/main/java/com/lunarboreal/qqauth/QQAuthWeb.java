package com.lunarboreal.qqauth;

import com.lunarboreal.api.web.*;
import com.lunarboreal.qqauth.auth.AuthService;
import java.util.*;

final class QQAuthWeb {
    static void register(QQAuthModule m) {
        QQAuthConfig configuration = new QQAuthConfig(m);
        m.webPermission("qqauth.view",m.msg("web.permission.view"));
        m.webPermission("qqauth.manage",m.msg("web.permission.manage"));
        m.webRoutes(r -> {
            r.get("/config", "qqauth.manage", c -> c.json(configuration.read()));
            r.post("/config", "qqauth.manage", c -> {
                c.json(configuration.save(c.bodyMap()));
                m.audit(c.user().username(), "web-config", "保存 QQAuth 配置，等待重载");
            });
            r.post("/messages/preview", "qqauth.manage", c -> {
                Object value = c.bodyMap().get("text");
                if (!(value instanceof String text) || text.length() > 4096) throw HttpException.badRequest("提示消息无效");
                var preview = new org.bukkit.configuration.file.YamlConfiguration();
                preview.set("messages.preview", text);
                Messages messages = new Messages(preview);
                String formatted = messages.formatted("preview", "player", "Steve", "code", "123456",
                        "group", "示例群", "ttl", "600", "command", "验证", "status", "成功", "count", "3", "ready", "true", "error", "示例错误");
                c.json(Map.of("plain", Messages.plain(formatted), "formatted", formatted));
            });
            r.get("/stats","qqauth.view",c -> c.json(List.of(Map.of("label",m.msg("web.stats.module-status"),"value",m.msg(m.ready?"web.status.ready":"web.status.not-ready")),
                    Map.of("label",m.msg("web.stats.account-count"),"value",m.auth==null?0:m.auth.accounts().size()))));
            r.get("/accounts","qqauth.view",c -> {
                if(!m.ready) { c.status(503); c.json(Map.of("error",m.msg("web.error.not-ready"))); return; }
                String q=c.query("q","").toLowerCase(Locale.ROOT);
                var all=m.auth.accounts().stream().filter(a -> a.name().toLowerCase(Locale.ROOT).contains(q)).sorted(Comparator.comparing(a -> a.name())).toList();
                int size=Math.max(1,Math.min(100,c.queryInt("size",20))),page=Math.max(1,c.queryInt("page",1));
                int from=(int)Math.min(all.size(),(long)(page-1)*size);
                var rows=all.subList(from,Math.min(all.size(),from+size)).stream().map(a -> Map.of("uuid",a.uuid().toString(),"name",a.name(),"locked",a.locked(),"authenticated",m.auth.isAuthenticated(a.uuid()),"createdAt",a.createdAt())).toList();
                c.page(all.size(),rows);
            });
            r.post("/accounts/{name}/{action}","qqauth.manage",c -> {
                if(!m.ready) { c.status(503); c.json(Map.of("error",m.msg("web.error.not-ready"))); return; }
                String name=c.pathParam("name"),action=c.pathParam("action");
                var account=m.auth.account(name).orElse(null);
                if(account==null) { c.status(404); c.json(Map.of("error",m.msg("web.error.account-not-found"))); return; }
                var body=c.bodyMap();
                AuthService.Result result=AuthService.Result.SUCCESS;
                switch(action) {
                    case "lock" -> result=m.auth.setLocked(name,true);
                    case "unlock" -> result=m.auth.setLocked(name,false);
                    case "delete" -> result=m.auth.deleteAccount(name);
                    case "reset" -> {
                        Object password=body.get("password");
                        if(!(password instanceof String value)) { c.status(400); c.json(Map.of("error",m.msg("web.error.password-required"))); return; }
                        try { result=m.work(() -> m.auth.adminReset(name,value)); }
                        catch (java.util.concurrent.RejectedExecutionException ex) { c.status(429); c.json(Map.of("error",m.msg("web.error.queue-full"))); return; }
                    }
                    case "forceunbind" -> {
                        var unbind=m.bindings.forceUnbind(name);
                        if(unbind.status()!=com.lunarboreal.qqauth.binding.BindingService.Status.SUCCESS) { c.status(409); c.json(Map.of("error",m.msg("web.error.operation-failed","status",m.bindingStatus(unbind.status())))); return; }
                        m.bound.remove(account.uuid());
                    }
                    default -> { c.status(400); c.json(Map.of("error",m.msg("web.error.unknown-action"))); return; }
                }
                if(result!=AuthService.Result.SUCCESS) { c.status(409); c.json(Map.of("error",m.msg("web.error.operation-failed","status",m.authStatus(result)))); return; }
                m.bindings.cancel(account.uuid());
                m.kick(account.uuid(),m.msg("kick.web-updated"));
                m.audit(c.user().username(),"web-"+action,name);
                c.ok(m.msg("web.operation-complete"));
            });
        });
        var table=new WebPanel.Table(m.msg("web.table.title"),"accounts").rowKey("uuid")
                .column("name",m.msg("web.column.name")).column("uuid",m.msg("web.column.uuid")).column("locked",m.msg("web.column.locked"),"bool")
                .column("authenticated",m.msg("web.column.authenticated"),"bool").column("createdAt",m.msg("web.column.created-at"),"datetime");
        for(String action:List.of("lock","unlock","delete","forceunbind"))
            table.rowAction(new WebPanel.Action(action,m.msg("web.action."+action),"accounts/{name}/"+action)
                    .permission("qqauth.manage").confirm(m.msg("web.confirm.action")));
        table.rowAction(new WebPanel.Action("reset",m.msg("web.action.reset"),"accounts/{name}/reset").permission("qqauth.manage")
                .field(new WebPanel.Field("password",m.msg("web.field.new-password"),"password").required(true)).confirm(m.msg("web.confirm.reset")));
        m.webPanel(WebPanel.of("accounts",m.msg("web.panel.title")).icon("shield").permission("qqauth.view")
                .section(new WebPanel.Stats(m.msg("web.section.auth-status"),"stats").refresh(10)).section(table)
                .section(new WebPanel.Text(m.msg("web.section.configuration"),m.msg("web.configuration-description"))));
    }
}
