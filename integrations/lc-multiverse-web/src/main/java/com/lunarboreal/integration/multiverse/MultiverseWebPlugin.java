package com.lunarboreal.integration.multiverse;

import com.lunarboreal.api.LC;
import com.lunarboreal.api.web.*;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.*;

public final class MultiverseWebPlugin extends JavaPlugin implements org.bukkit.event.Listener {
    private static final String NS="multiverse", VIEW="multiverse.view", EDIT="multiverse.configure", LIFE="multiverse.lifecycle", TP="multiverse.teleport";
    private static final Set<String> CONFIG_KEYS=Set.of("alias","pvp","allowWeather","autoLoad");
    private boolean active;
    @Override public void onEnable() {
        active=true;
        Bukkit.getPluginManager().registerEvents(this,this);
        var web=LC.get().web();
        web.registerPermission(VIEW,"查看Multiverse世界",this);
        web.registerPermission(EDIT,"修改Multiverse世界白名单配置",this);
        web.registerPermission(LIFE,"加载/卸载空的非主世界",this);
        web.registerPermission(TP,"传送在线玩家到Multiverse世界出生点",this);
        web.routes(NS,this,r->r.get("/status",VIEW,c->c.json(c.sync(this::status)))
                .get("/worlds",VIEW,this::worlds).post("/configure",EDIT,this::configure)
                .post("/lifecycle",LIFE,this::lifecycle).post("/teleport",TP,this::teleport));
        WebPanel.Table table=new WebPanel.Table("Multiverse世界","worlds").rowKey("name").searchable(true)
                .column("name","世界").column("loaded","已加载","bool").column("players","在线人数","number")
                .column("alias","别名").column("pvp","PVP","bool").column("allowWeather","允许天气","bool").column("autoLoad","自动加载","bool")
                .rowAction(new WebPanel.Action("configure","修改配置","configure").permission(EDIT)
                        .field(new WebPanel.Field("name","世界名","text").required(true))
                        .field(new WebPanel.Field("property","属性","select").option("alias","别名").option("pvp","PVP").option("allowWeather","允许天气").option("autoLoad","自动加载").required(true))
                        .field(new WebPanel.Field("value","值（布尔填true/false）","text").required(true)))
                .rowAction(new WebPanel.Action("lifecycle","加载/卸载","lifecycle").permission(LIFE).confirm("确认操作 {name}？有玩家或主世界不允许卸载。")
                        .field(new WebPanel.Field("name","世界名","text").required(true))
                        .field(new WebPanel.Field("operation","操作","select").option("load","加载").option("unload","保存并卸载").required(true)))
                .rowAction(new WebPanel.Action("teleport","传送玩家","teleport").permission(TP).confirm("将指定在线玩家传送到 {name} 的出生点？")
                        .field(new WebPanel.Field("name","世界名","text").required(true))
                        .field(new WebPanel.Field("player","在线玩家精确名称","text").required(true)));
        web.registerPanel(NS,WebPanel.of("worlds","多世界管理").icon("globe").group("管理").permission(VIEW)
                .section(new WebPanel.Text("管理范围","工作区会按当前可用的 Multiverse 公开 API 展示世界状态，并提供别名、PVP、天气和自动加载配置。加载/卸载受主世界、默认世界和在线玩家保护；传送使用 Multiverse 安全传送器，并按独立权限执行。"))
                .section(new WebPanel.Stats("桥接状态","status").refresh(10)).section(table),this);
    }
    @Override public void onDisable(){active=false;if(LC.isLoaded())LC.get().web().unregisterAll(this);}
    @org.bukkit.event.EventHandler
    public void vendorEnabled(org.bukkit.event.server.PluginEnableEvent event) {
        if(event.getPlugin().getName().equals("Multiverse-Core")) getLogger().info("Multiverse-Core启用；后续请求将重新检查公开API");
    }
    @org.bukkit.event.EventHandler
    public void vendorDisabled(org.bukkit.event.server.PluginDisableEvent event) {
        if(event.getPlugin().getName().equals("Multiverse-Core")) getLogger().warning("Multiverse-Core停用；保留健康面板，拒绝所有世界操作");
    }
    private MvApi mv() throws Exception {
        if(!active)throw HttpException.badRequest("桥接已停用");
        Plugin p=Bukkit.getPluginManager().getPlugin("Multiverse-Core");
        if(p==null||!p.isEnabled())throw HttpException.badRequest("Multiverse-Core未启用");
        return new MvApi(p);
    }
    private List<Map<String,Object>> status(){
        String detail;
        try{mv();detail="Multiverse公开API可用";}catch(Exception e){detail=e.getMessage();}
        return List.of(Map.of("label","Multiverse桥接","value",String.valueOf(detail),"icon","globe"));
    }
    private void worlds(WebContext c)throws Exception{
        String query=c.query("q");
        var rows=c.sync(()->{
            MvApi m=mv();List<Map<String,Object>> out=new ArrayList<>();
            for(Object w:(Collection<?>)MvApi.call(m.manager,"getWorlds")){
                String name=(String)MvApi.call(w,"getName");World bw=Bukkit.getWorld(name);
                Map<String,Object> row=worldRow(w,bw==null?0:bw.getPlayers().size());
                String alias=(String)row.get("alias");
                if(query==null || query.isBlank() || name.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT)) || alias.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT)))
                    out.add(row);
            }return out;
        });
        int size=Math.max(1,Math.min(100,c.queryInt("size",20)));
        int from=(int)Math.min(rows.size(),(Math.max(1L,c.queryInt("page",1))-1)*size);
        c.page(rows.size(),rows.subList(from,Math.min(rows.size(),from+size)));
    }
    static Map<String,Object> worldRow(Object world,int players)throws Exception{
        Map<String,Object> row=new LinkedHashMap<>();
        row.put("name",MvApi.call(world,"getName"));
        row.put("loaded",MvApi.call(world,"isLoaded"));
        row.put("players",players);
        row.putAll(readConfig(world));
        return row;
    }
    static Map<String,Object> body(WebContext c,String... keys){
        Map<String,Object> b=c.bodyMap();Set<String> allowed=new HashSet<>(List.of(keys));allowed.add("row");
        if(b==null||!allowed.containsAll(b.keySet())||(b.containsKey("row")&&!(b.get("row") instanceof Map)))throw HttpException.badRequest("未知请求字段");
        return b;
    }
    static String text(Map<String,Object>b,String key){
        if(!(b.get(key) instanceof String s)||s.isBlank()||s.length()>128||s.chars().anyMatch(Character::isISOControl))throw HttpException.badRequest("无效字段: "+key);
        return s;
    }
    static boolean bool(Object raw){
        if(raw instanceof Boolean b)return b;
        if("true".equals(raw))return true;
        if("false".equals(raw))return false;
        throw HttpException.badRequest("布尔值仅允许true/false");
    }
    static Map<String,Object> configChanges(Object raw){
        if(!(raw instanceof Map<?,?> input)||input.isEmpty())throw HttpException.badRequest("请提供至少一项世界配置");
        Map<String,Object> values=new LinkedHashMap<>();
        for(var entry:input.entrySet()){
            if(!(entry.getKey() instanceof String key)||!CONFIG_KEYS.contains(key))throw HttpException.badRequest("属性不在白名单");
            Object value=entry.getValue();
            if(key.equals("alias"))values.put(key,alias(value));
            else if(value instanceof Boolean)values.put(key,value);
            else throw HttpException.badRequest(key+"必须为布尔值");
        }
        return values;
    }
    static Map<String,Object> expectedConfig(Object raw){
        Map<String,Object> values=configChanges(raw);
        if(!values.keySet().equals(CONFIG_KEYS))throw HttpException.badRequest("预期配置字段不完整");
        return values;
    }
    static Object legacyConfigValue(String key,Object raw){
        if(!CONFIG_KEYS.contains(key))throw HttpException.badRequest("属性不在白名单");
        return key.equals("alias")?alias(raw):bool(raw);
    }
    private static String alias(Object raw){
        if(!(raw instanceof String value)||value.length()>128||value.chars().anyMatch(Character::isISOControl))
            throw HttpException.badRequest("别名最多128个字符且不能包含控制字符");
        return value;
    }
    static String configGetter(String key){
        return switch(key){case "alias"->"getAlias";case "pvp"->"getPvp";case "allowWeather"->"isAllowWeather";case "autoLoad"->"isAutoLoad";default->throw HttpException.badRequest("属性不在白名单");};
    }
    private static String setter(String key){
        return switch(key){case "alias"->"setAlias";case "pvp"->"setPvp";case "allowWeather"->"setAllowWeather";case "autoLoad"->"setAutoLoad";default->throw HttpException.badRequest("属性不在白名单");};
    }
    private static Map<String,Object> readConfig(Object world)throws Exception{
        Map<String,Object> values=new LinkedHashMap<>();
        for(String key:List.of("alias","pvp","allowWeather","autoLoad")){
            Object value=MvApi.call(world,configGetter(key));
            values.put(key,key.equals("alias")?Objects.toString(value,""):value);
        }
        return values;
    }
    private static void applyConfig(MvApi api,Object world,Map<String,Object> changes)throws Exception{
        Map<String,Object> before=readConfig(world);
        List<String> attempted=new ArrayList<>();
        try{
            for(var entry:changes.entrySet()){
                attempted.add(entry.getKey());
                MvApi.success(MvApi.call(world,setter(entry.getKey()),entry.getValue()));
            }
            MvApi.success(MvApi.call(api.manager,"saveWorldsConfig"));
        }catch(Exception failure){
            Collections.reverse(attempted);
            for(String key:attempted){
                try{MvApi.success(MvApi.call(world,setter(key),before.get(key)));}
                catch(Exception rollback){failure.addSuppressed(rollback);}
            }
            try{MvApi.success(MvApi.call(api.manager,"saveWorldsConfig"));}
            catch(Exception rollback){failure.addSuppressed(rollback);}
            throw failure;
        }
    }
    private void configure(WebContext c)throws Exception{
        var b=body(c,"name","property","value","values","expected");
        String name=text(b,"name");
        Map<String,Object> changes;
        if(b.containsKey("values")){
            if(b.containsKey("property")||b.containsKey("value"))throw HttpException.badRequest("请使用批量配置字段");
            changes=configChanges(b.get("values"));
        }else{
            String property=text(b,"property");
            Map<String,Object> single=new LinkedHashMap<>();single.put(property,legacyConfigValue(property,b.get("value")));
            changes=configChanges(single);
        }
        Map<String,Object> expected=b.containsKey("expected")?expectedConfig(b.get("expected")):null;
        c.sync(()->{
            MvApi m=mv();Object w=m.world(name);
            if(expected!=null&&!readConfig(w).equals(expected))throw HttpException.badRequest("世界配置已被其他操作修改，请重新加载后再保存");
            applyConfig(m,w,changes);
            audit(c,"configure",name+" "+changes);return null;
        });
        c.ok("世界配置已保存");
    }
    private void lifecycle(WebContext c)throws Exception{
        var b=body(c,"name","operation");String name=text(b,"name"),op=text(b,"operation");
        if(!List.of("load","unload").contains(op))throw HttpException.badRequest("未知操作");
        c.sync(()->{MvApi m=mv();Object w=m.world(name);
            if(op.equals("load")){MvApi.success(MvApi.call(m.manager,"loadWorld",name));}
            else{
                World bw=Bukkit.getWorld(name);
                Object defaultOption=MvApi.call(m.manager,"getDefaultWorld");
                Object defaultWorld=MvApi.call(defaultOption,"getOrNull");
                boolean primary=!Bukkit.getWorlds().isEmpty()&&Bukkit.getWorlds().getFirst().getName().equals(name);
                if(primary||(defaultWorld!=null&&name.equals(MvApi.call(defaultWorld,"getName"))))throw HttpException.badRequest("不能卸载主世界或MV默认世界");
                if(bw==null||!bw.getPlayers().isEmpty())throw HttpException.badRequest("只允许卸载已加载且无玩家的世界");
                Object loaded=MvApi.call(MvApi.call(w,"asLoadedWorld"),"get");
                Class<?> type=m.loader.loadClass("org.mvplugins.multiverse.core.world.options.UnloadWorldOptions");
                Class<?> loadedType=m.loader.loadClass("org.mvplugins.multiverse.core.world.LoadedMultiverseWorld");
                Object options=type.getMethod("world",loadedType).invoke(null,loaded);
                MvApi.call(options,"saveBukkitWorld",true);MvApi.call(options,"unloadBukkitWorld",true);
                MvApi.success(MvApi.call(m.manager,"unloadWorld",options));
            }audit(c,op,name);return null;});c.ok("世界"+op+"完成");
    }
    private void teleport(WebContext c)throws Exception{
        var b=body(c,"name","player");String name=text(b,"name"),player=text(b,"player");
        c.sync(()->{MvApi m=mv();Object w=m.world(name);Player p=Bukkit.getPlayerExact(player);
            if(p==null||!Boolean.TRUE.equals(MvApi.call(w,"isLoaded")))throw HttpException.badRequest("玩家须在线且目标世界已加载");
            Location spawn=(Location)MvApi.call(w,"getSpawnLocation");
            if(spawn==null||spawn.getWorld()==null)throw HttpException.badRequest("没有有效出生点");
            Object action=MvApi.call(MvApi.call(m.api,"getSafetyTeleporter"),"to",spawn.clone());
            MvApi.call(action,"checkSafety",true);MvApi.call(action,"by",p);
            Object attempt=MvApi.call(action,"teleport",p);
            String actor=actor(c),detail=p.getUniqueId()+" -> "+name;
            audit(c,"teleport-submitted",detail);
            MvApi.call(attempt,"onSuccess",(Runnable)()->LC.get().audit().log(NS,actor,"teleport-success",detail));
            MvApi.call(attempt,"onFailure",(Runnable)()->LC.get().audit().log(NS,actor,"teleport-failed",detail));
            return null;});c.ok("传送已提交MV安全检查，请查看实际位置与审计最终结果；未承诺已到达");
    }
    private static String actor(WebContext c){return "web:"+(c.user()==null?"?":c.user().username());}
    private void audit(WebContext c,String action,String detail){LC.get().audit().log(NS,actor(c),action,detail);}
}
