package com.lunarboreal.permissions;

import com.lunarboreal.api.web.*;
import java.util.*;

/** Declarative permission administration; every mutation executes and persists on the main thread. */
final class PermissionWeb {
    private static final String VIEW="permissions.view", MANAGE="permissions.manage";
    static void register(PermissionsModule m) {
        m.webPermission(VIEW,"查看权限组、玩家与权限检查");m.webPermission(MANAGE,"管理权限组与玩家权限");
        m.webRoutes(r->{
            r.get("/groups",VIEW,c->page(c,c.sync(()->m.state().groups().values().stream().map(g->Map.<String,Object>of("name",g.name(),"weight",g.weight(),"parents",String.join(", ",new TreeSet<>(g.parents())),"permissions",grants(g.permissions()))).toList())));
            r.get("/users",VIEW,c->page(c,c.sync(()->m.state().users().values().stream().map(u->Map.<String,Object>of("uuid",u.uuid().toString(),"name",u.name(),"groups",members(u.groups()),"permissions",grants(u.permissions()))).toList())));
            r.get("/nodes",VIEW,c->page(c,c.sync(()->m.knownNodes().stream().map(n->Map.<String,Object>of("node",n)).toList())));
            r.get("/effective",VIEW,c->{var rows=c.sync(()->{
                String target=c.query("q","");if(target.isBlank())return List.<Map<String,Object>>of();
                UUID id;try{id=m.identify(target);}catch(IllegalArgumentException ex){throw HttpException.badRequest(ex.getMessage());}long now=System.currentTimeMillis();
                return PermissionResolver.effective(m.state(),id,now).entrySet().stream().sorted(Map.Entry.comparingByKey()).map(e->Map.<String,Object>of("uuid",id.toString(),"node",e.getKey(),"value",e.getValue())).toList();
            });slice(c,rows);});
            r.post("/check",VIEW,c->{var b=body(c,Set.of("user","node"));String result=c.sync(()->{
                try{return m.check(m.identify(text(b,"user")),PermissionState.node(text(b,"node").toLowerCase(Locale.ROOT))).toString();}
                catch(IllegalArgumentException e){throw HttpException.badRequest(e.getMessage());}
            });c.ok(result);});
            r.post("/mutate",MANAGE,c->{var b=body(c,Set.of("scope","target","action","key","value","duration"));
                c.sync(()->{try{PermissionCommands.apply(m,args(b),"web:"+c.user().username());return null;}catch(IllegalArgumentException e){throw HttpException.badRequest(e.getMessage());}});
                c.ok("权限设置已保存并更新");
            });
        });
        var groups=new WebPanel.Table("权限组","groups").rowKey("name").column("name","组名").column("weight","权重","number").column("parents","继承组").column("permissions","直接权限")
            .toolbarAction(new WebPanel.Action("create","创建权限组","mutate").permission(MANAGE)
                .field(hidden("scope","group")).field(new WebPanel.Field("target","组名","text").required(true)).field(hidden("action","create")))
            .rowAction(action("group","weight","设置权重").field(new WebPanel.Field("value","权重（整数）","number").required(true)))
            .rowAction(action("group","parent-add","添加继承组").field(key("父组")))
            .rowAction(action("group","parent-remove","移除继承组").field(key("父组")))
            .rowAction(permissionAction("group","permission-set","设置权限",true))
            .rowAction(permissionAction("group","permission-unset","移除权限",false))
            .rowAction(action("group","delete","删除权限组").style("danger").confirm("确认删除此权限组？默认组和仍被引用的组无法删除。"));
        var users=new WebPanel.Table("玩家直接记录","users").rowKey("uuid").column("name","名称").column("uuid","UUID").column("groups","直接加入组").column("permissions","直接权限")
            .toolbarAction(new WebPanel.Action("add-user-group","设置玩家权限组","mutate").permission(MANAGE).field(hidden("scope","user")).field(hidden("action","parent-add"))
                .field(new WebPanel.Field("target","UUID或已知玩家名称","text").required(true)).field(key("权限组")).field(duration()))
            .toolbarAction(new WebPanel.Action("set-user-permission","设置玩家权限","mutate").permission(MANAGE).field(hidden("scope","user")).field(hidden("action","permission-set"))
                .field(new WebPanel.Field("target","UUID或已知玩家名称","text").required(true)).field(key("权限节点")).field(value()).field(duration()))
            .rowAction(action("user","parent-add","加入权限组").field(key("权限组")).field(duration()))
            .rowAction(action("user","parent-remove","移出权限组").field(key("权限组")))
            .rowAction(permissionAction("user","permission-set","设置权限",true))
            .rowAction(permissionAction("user","permission-unset","移除权限",false));
        m.webPanel(WebPanel.of("permissions","权限管理").icon("shield-check").group("管理").permission(VIEW)
            .section(new WebPanel.Text("权限说明","默认组始终参与权限计算。玩家直接权限优先；组权限按节点匹配与权重计算。临时权限到期后不再生效。玩家列表展示已保存的直接记录；权限检查支持 UUID 或已知玩家名称。有效权限表的搜索框用于输入玩家 UUID 或已知名称。有效期留空或填写 0 表示永久，可填写 30s、15min、2h、7d。"))
            .section(groups).section(users)
            .section(new WebPanel.Table("已注册权限节点","nodes").rowKey("node").column("node","权限节点")
                .toolbarAction(new WebPanel.Action("check","检查玩家权限","check").permission(VIEW).field(new WebPanel.Field("user","UUID或已知玩家名称","text").required(true)).field(new WebPanel.Field("node","权限节点","text").required(true))))
            .section(new WebPanel.Table("指定玩家有效权限","effective").rowKey("node").column("uuid","UUID").column("node","节点").column("value","权限值","bool")
                .toolbarAction(new WebPanel.Action("effective-check","检查指定节点","check").permission(VIEW).field(new WebPanel.Field("user","UUID或已知玩家名称","text").required(true)).field(new WebPanel.Field("node","权限节点","text").required(true)))));
    }
    private static WebPanel.Action action(String scope,String action,String label){return new WebPanel.Action(scope+"-"+action,label,"mutate").permission(MANAGE).field(hidden("scope",scope)).field(hidden("action",action));}
    private static WebPanel.Action permissionAction(String scope,String op,String label,boolean set){var a=action(scope,op,label).field(key("权限节点"));if(set){a.field(value());a.field(duration());}return a;}
    // Select fields with one value keep operation metadata visible and immutable in the standard form.
    private static WebPanel.Field hidden(String key,String value){String label=switch(value){case "group"->"权限组";case "user"->"玩家";case "create"->"创建";case "delete"->"删除";case "weight"->"设置权重";case "parent-add"->"添加权限组";case "parent-remove"->"移除权限组";case "permission-set"->"设置权限";case "permission-unset"->"移除权限";default->value;};return new WebPanel.Field(key,key.equals("scope")?"对象类型":"操作","select").option(value,label).defaultValue(value).required(true);}
    private static WebPanel.Field key(String label){return new WebPanel.Field("key",label,"text").required(true);}
    private static WebPanel.Field value(){return new WebPanel.Field("value","权限值","select").option("true","允许").option("false","拒绝").defaultValue("true").required(true);}
    private static WebPanel.Field duration(){return new WebPanel.Field("duration","有效期","text").placeholder("0 / 30s / 15min / 2h / 7d");}
    private static String[] args(Map<String,Object>b){String scope=text(b,"scope"),target=optional(b,"target");
        if(target.isBlank() && b.get("row") instanceof Map<?,?> row){Object candidate=row.get(scope.equals("group")?"name":"uuid");if(candidate instanceof String s)target=s;}
        if(target.isBlank())throw new IllegalArgumentException("请填写操作对象");
        String op=text(b,"action");var out=new ArrayList<>(List.of(scope,target));
        switch(op){case "create","delete"->out.add(op);case "weight"->{out.add(op);String v=text(b,"value");out.add(v);}
            case "parent-add","parent-remove","permission-set","permission-unset"->{String[] parts=op.split("-");out.add(parts[0]);out.add(parts[1]);out.add(text(b,"key"));
                if(op.equals("permission-set"))out.add(text(b,"value"));if(op.equals("permission-set")||op.equals("parent-add")){String d=optional(b,"duration");if(!d.isBlank())out.add(d);}}
            default->throw new IllegalArgumentException("不支持的权限操作");}return out.toArray(String[]::new);
    }
    private static Map<String,Object> body(WebContext c,Set<String>allowed){var b=c.bodyMap();for(String k:b.keySet())if(!allowed.contains(k)&&!k.equals("row"))throw HttpException.badRequest("不支持的字段："+k);return b;}
    private static String optional(Map<String,Object>b,String key){Object v=b.get(key);if(v==null)return "";if(v instanceof String s)return s;if(v instanceof Number n && Double.isFinite(n.doubleValue())&&n.doubleValue()==n.longValue())return Long.toString(n.longValue());throw new IllegalArgumentException("字段类型无效："+key);}
    private static String text(Map<String,Object>b,String key){String s=optional(b,key);if(s.isBlank()||s.length()>200||s.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("字段无效："+key);return s;}
    private static String grants(Map<String,PermissionState.Grant> grants){long now=System.currentTimeMillis();return grants.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(e->e.getKey()+"="+e.getValue().value()+expiry(e.getValue().expiresAt(),now)).collect(java.util.stream.Collectors.joining(", "));}
    private static String members(Map<String,PermissionState.Membership>groups){long now=System.currentTimeMillis();return groups.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(e->e.getKey()+expiry(e.getValue().expiresAt(),now)).collect(java.util.stream.Collectors.joining(", "));}
    private static String expiry(long expires,long now){return expires==0?"（永久）":expires<=now?"（已过期）":"（到期："+java.time.Instant.ofEpochMilli(expires)+"）";}
    private static void page(WebContext c,List<Map<String,Object>> rows){String q=c.query("q","").toLowerCase(Locale.ROOT);var filtered=rows.stream().filter(row->row.values().stream().anyMatch(v->Objects.toString(v,"").toLowerCase(Locale.ROOT).contains(q))).sorted(Comparator.comparing(Object::toString)).toList();slice(c,filtered);}
    private static void slice(WebContext c,List<Map<String,Object>> rows){int size=Math.max(1,Math.min(100,c.queryInt("size",20)));long offset=(long)(Math.max(1,c.queryInt("page",1))-1)*size;int start=(int)Math.min(offset,rows.size());c.page(rows.size(),rows.subList(start,Math.min(start+size,rows.size())));}
}
