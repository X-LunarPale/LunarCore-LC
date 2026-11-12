package com.lunarboreal.permissions;

import com.lunarboreal.api.web.*;
import java.util.*;

/** Declarative administration backed by the official LuckPerms API. */
final class PermissionWeb {
    private static final String VIEW="permissions.view", MANAGE="permissions.manage";
    static void register(PermissionsModule m) {
        m.webPermission(VIEW,"查看权限组、玩家与权限检查");m.webPermission(MANAGE,"管理权限组与玩家权限");
        m.webRoutes(r->{
            r.get("/status",VIEW,c->c.json(List.of(stat("权限后端","LuckPerms"),stat("连接状态",m.connection()),stat("版本",m.backendVersion()),stat("旧数据导入",m.migration()))));
            r.get("/groups",VIEW,c->page(c,m.bridge().groups()));
            r.get("/users",VIEW,c->{
                var result=m.bridge().users(c.query("q",""),Math.max(1,c.queryInt("page",1)),Math.clamp(c.queryInt("size",20),1,100));
                c.page(result.total(),result.items());
            });
            r.get("/nodes",VIEW,c->page(c,c.sync(()->m.knownNodes().stream().map(n->Map.<String,Object>of("node",n)).toList())));
            r.get("/effective",VIEW,c->{
                String target=c.query("q","");if(target.isBlank()){c.page(0,List.of());return;}
                var bridge=m.bridge();slice(c,bridge.effective(bridge.identify(target)));
            });
            r.post("/check",VIEW,c->{
                var b=body(c,Set.of("user","node"));var bridge=m.bridge();
                var result=bridge.check(bridge.identify(text(b,"user")),text(b,"node"));
                c.ok(text(b,"user")+" · "+result.get("node")+"："+result.get("result"));
            });
            r.post("/mutate",MANAGE,c->{var b=body(c,Set.of("scope","target","action","key","value","duration"));
                mutate(m,c,b,text(b,"scope"),text(b,"action"));
            });
            r.post("/mutate/{scope}/{action}",MANAGE,c->mutate(m,c,body(c,Set.of("target","key","value","duration")),c.pathParam("scope"),c.pathParam("action")));
        });
        var groups=new WebPanel.Table("权限组","groups").rowKey("name").column("name","组名").column("weight","权重","number").column("parents","继承组").column("permissions","直接权限")
            .toolbarAction(action("group","create","创建权限组").field(new WebPanel.Field("target","组名","text").required(true)))
            .rowAction(action("group","weight","设置权重").field(new WebPanel.Field("value","权重（整数）","number").required(true)))
            .rowAction(action("group","parent-add","添加继承组").field(key("父组")).field(duration()))
            .rowAction(action("group","parent-remove","移除继承组").field(key("父组")))
            .rowAction(permissionAction("group","permission-set","设置权限",true))
            .rowAction(permissionAction("group","permission-unset","移除权限",false))
            .rowAction(action("group","delete","删除权限组").style("danger").confirm("确认删除此权限组？默认组和仍被引用的组无法删除。"));
        var users=new WebPanel.Table("玩家权限","users").rowKey("uuid").column("name","名称").column("uuid","UUID").column("primaryGroup","主权限组").column("groups","直接加入组").column("permissions","直接权限")
            .toolbarAction(action("user","parent-add","设置玩家权限组")
                .field(new WebPanel.Field("target","UUID或已知玩家名称","text").required(true)).field(key("权限组")).field(duration()))
            .toolbarAction(action("user","permission-set","设置玩家权限")
                .field(new WebPanel.Field("target","UUID或已知玩家名称","text").required(true)).field(key("权限节点")).field(value()).field(duration()))
            .rowAction(action("user","parent-add","加入权限组").field(key("权限组")).field(duration()))
            .rowAction(action("user","parent-remove","移出权限组").field(key("权限组")))
            .rowAction(permissionAction("user","permission-set","设置权限",true))
            .rowAction(permissionAction("user","permission-unset","移除权限",false));
        m.webPanel(WebPanel.of("permissions","权限管理").icon("shield-check").group("管理").permission(VIEW)
            .section(new WebPanel.Stats("LuckPerms","status").refresh(15))
            .section(groups).section(users)
            .section(new WebPanel.Table("已注册权限节点","nodes").rowKey("node").column("node","权限节点")
                .toolbarAction(new WebPanel.Action("check","检查玩家权限","check").permission(VIEW).field(new WebPanel.Field("user","UUID或已知玩家名称","text").required(true)).field(new WebPanel.Field("node","权限节点","text").required(true))))
            .section(new WebPanel.Table("指定玩家有效权限","effective").rowKey("node").column("uuid","UUID").column("node","节点").column("value","权限值","bool")
                .toolbarAction(new WebPanel.Action("effective-check","检查指定节点","check").permission(VIEW).field(new WebPanel.Field("user","UUID或已知玩家名称","text").required(true)).field(new WebPanel.Field("node","权限节点","text").required(true)))));
    }
    private static WebPanel.Action action(String scope,String action,String label){return new WebPanel.Action(scope+"-"+action,label,"mutate/"+scope+"/"+action).permission(MANAGE);}
    private static WebPanel.Action permissionAction(String scope,String op,String label,boolean set){var a=action(scope,op,label).field(key("权限节点"));if(set){a.field(value());a.field(duration());}return a;}
    private static WebPanel.Field key(String label){return new WebPanel.Field("key",label,"text").required(true);}
    private static WebPanel.Field value(){return new WebPanel.Field("value","权限值","select").option("true","允许").option("false","拒绝").defaultValue("true").required(true);}
    private static WebPanel.Field duration(){return new WebPanel.Field("duration","有效期","text").placeholder("0 / 30s / 15min / 2h / 7d");}
    private static void mutate(PermissionsModule m,WebContext c,Map<String,Object>b,String scope,String action){
        String target=optional(b,"target");
        if(target.isBlank() && b.get("row") instanceof Map<?,?> row){Object candidate=row.get(scope.equals("group")?"name":"uuid");if(candidate instanceof String s)target=s;}
        if(target.isBlank())throw new IllegalArgumentException("请填写操作对象");
        m.bridge().mutate(scope,target,action,optional(b,"key"),optional(b,"value"),optional(b,"duration"));
        m.audit("web:"+c.user().username(),"permissions",scope+" "+target+" "+action+" "+optional(b,"key"));
        c.ok("LuckPerms 权限已保存");
    }
    private static Map<String,Object> body(WebContext c,Set<String>allowed){var b=c.bodyMap();for(String k:b.keySet())if(!allowed.contains(k)&&!k.equals("row"))throw HttpException.badRequest("不支持的字段："+k);return b;}
    private static String optional(Map<String,Object>b,String key){Object v=b.get(key);if(v==null)return "";if(v instanceof String s)return s;if(v instanceof Number n && Double.isFinite(n.doubleValue())&&n.doubleValue()==n.longValue())return Long.toString(n.longValue());throw new IllegalArgumentException("字段类型无效："+key);}
    private static String text(Map<String,Object>b,String key){String s=optional(b,key);if(s.isBlank()||s.length()>200||s.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("字段无效："+key);return s;}
    private static Map<String,Object> stat(String label,String value){return Map.of("label",label,"labelKey",label,"value",value,"icon","shield-check");}
    private static void page(WebContext c,List<Map<String,Object>> rows){String q=c.query("q","").toLowerCase(Locale.ROOT);var filtered=rows.stream().filter(row->row.values().stream().anyMatch(v->Objects.toString(v,"").toLowerCase(Locale.ROOT).contains(q))).sorted(Comparator.comparing(Object::toString)).toList();slice(c,filtered);}
    private static void slice(WebContext c,List<Map<String,Object>> rows){int size=Math.max(1,Math.min(100,c.queryInt("size",20)));long offset=(long)(Math.max(1,c.queryInt("page",1))-1)*size;int start=(int)Math.min(offset,rows.size());c.page(rows.size(),rows.subList(start,Math.min(start+size,rows.size())));}
}
