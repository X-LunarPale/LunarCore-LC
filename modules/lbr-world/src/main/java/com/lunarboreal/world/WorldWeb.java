package com.lunarboreal.world;

import com.lunarboreal.api.web.*;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import java.nio.file.Files;
import java.util.*;

final class WorldWeb {
    private final WorldModule m;
    WorldWeb(WorldModule m) { this.m=m; }
    void register() {
        m.webPermission("world.view","查看世界"); m.webPermission("world.manage","创建、配置、卸载世界"); m.webPermission("world.teleport","传送在线玩家到世界");
        m.webRoutes(r -> r.get("/worlds","world.view",c -> c.json(sync(c,() -> list(c))))
                .get("/worlds/{name}/config","world.view",c -> c.json(sync(c,() -> m.require(c.pathParam("name")).map())))
                .post("/worlds/{name}/import","world.manage",c -> {String n=c.pathParam("name"),a=actor(c);sync(c,() -> {m.importLoaded(n,a);return null;});c.ok("已导入当前世界设置");})
                .post("/worlds","world.manage",c -> { var body=c.bodyMap(); String actor=actor(c); Object result=sync(c,() -> create(body,actor));c.status(202);c.json(result); })
                .post("/worlds/{name}/config","world.manage",c -> { var b=c.bodyMap();String n=c.pathParam("name"),a=actor(c);c.json(sync(c,() -> configure(n,b,a))); })
                .post("/worlds/{name}/load","world.manage",c -> { String n=c.pathParam("name"),a=actor(c);c.json(sync(c,() -> {m.queueLoad(m.require(n),a);return accepted(n);})); })
                .post("/worlds/{name}/unload","world.manage",c -> {String n=c.pathParam("name"),a=actor(c);sync(c,() -> {m.unload(n,a);return null;});c.ok("已卸载，目录保留");})
                .post("/worlds/{name}/teleport","world.teleport",c -> {String n=c.pathParam("name"),a=actor(c);String p=String.valueOf(c.bodyMap().getOrDefault("player",""));sync(c,() -> {teleport(n,p,a);return null;});c.ok("已传送");}));
        WebPanel.Action create = new WebPanel.Action("create","创建世界","worlds").permission("world.manage")
                .field(new WebPanel.Field("name","名称","text").required(true))
                .field(new WebPanel.Field("environment","环境","select").option("NORMAL","主世界").option("NETHER","下界").option("THE_END","末地").defaultValue("NORMAL"))
                .field(new WebPanel.Field("type","生成类型","select").option("NORMAL","普通").option("FLAT","超平坦").option("LARGE_BIOMES","大型生物群系").option("AMPLIFIED","放大化").defaultValue("NORMAL"))
                .field(new WebPanel.Field("seed","种子（整数）","text").defaultValue("0"));
        WebPanel.Action configure = settings(new WebPanel.Action("configure","配置","worlds/{name}/config").permission("world.manage"));
        m.webPanel(WebPanel.of("world","世界管理").icon("globe").group("服务器").permission("world.view")
                .section(new WebPanel.Text("说明","创建与加载异步排队，刷新列表查看状态。`allowCrossDimension=false` 时禁止从该世界进入其他维度。固定白天：时间1000，昼夜循环关闭。时间*仅对有世界时钟的维度生效；下界/末地不支持时钟，值仅保存。配置操作只修改填写的字段；出生点XYZ同时填写。"))
                .section(new WebPanel.Table("世界列表","worlds").rowKey("name").column("name","名称").column("environment","环境","tag")
                        .column("type","类型","tag").column("status","操作状态","tag").column("loaded","已加载","bool")
                        .column("pvp","PvP","bool").column("allowCrossDimension","跨维度","bool").column("difficulty","难度").column("time","时间*","number").column("daylightCycle","昼夜循环","bool").column("operationError","错误")
                        .column("managed","已托管","bool").toolbarAction(create).rowAction(configure)
                        .rowAction(new WebPanel.Action("import","导入已加载世界","worlds/{name}/import").permission("world.manage"))
                        .rowAction(new WebPanel.Action("load","加载","worlds/{name}/load").permission("world.manage"))
                        .rowAction(new WebPanel.Action("unload","安全卸载","worlds/{name}/unload").style("warning").confirm("保存并卸载世界，保留目录？").permission("world.manage"))
                        .rowAction(new WebPanel.Action("tp","传送玩家","worlds/{name}/teleport").permission("world.teleport").field(new WebPanel.Field("player","在线玩家","player").required(true)))));
    }
    private WebPanel.Action settings(WebPanel.Action a) {
        return a.field(new WebPanel.Field("pvp","PvP","select").option("true","启用").option("false","关闭"))
                .field(new WebPanel.Field("difficulty","难度","select").option("PEACEFUL","和平").option("EASY","简单").option("NORMAL","普通").option("HARD","困难"))
                .field(new WebPanel.Field("time","时间0~23999","number"))
                .field(new WebPanel.Field("daylightCycle","昼夜循环","select").option("true","启用").option("false","关闭"))
                .field(new WebPanel.Field("weather","天气","select").option("CLEAR","晴朗").option("RAIN","降雨").option("THUNDER","雷雨"))
                .field(new WebPanel.Field("autoSave","自动保存","select").option("true","启用").option("false","关闭"))
                .field(new WebPanel.Field("loadOnStartup","启动加载","select").option("true","启用").option("false","关闭"))
                .field(new WebPanel.Field("allowCrossDimension","允许进入其他维度","select").option("true","允许").option("false","禁止"))
                .field(new WebPanel.Field("spawnX","出生X","number")).field(new WebPanel.Field("spawnY","出生Y","number")).field(new WebPanel.Field("spawnZ","出生Z","number"));
    }
    private Map<String,Object> list(WebContext c) {
        List<Map<String,Object>> out=new ArrayList<>();String q=c.query("q","").toLowerCase(Locale.ROOT);
        m.worlds.forEach((n,o)->{if(!n.toLowerCase(Locale.ROOT).contains(q))return;Map<String,Object>x=o.map();x.put("loaded",Bukkit.getWorld(n)!=null);x.put("managed",true);x.put("status",m.states.getOrDefault(n,"registered"));x.put("operationError",m.errors.getOrDefault(n,""));out.add(x);});
        for(World world:Bukkit.getWorlds()) {
            if (world.getKey().getNamespace().equals("lunarcore_structure_stage")) continue;
            String n=world.getName();
            if(m.worlds.containsKey(n)||!n.toLowerCase(Locale.ROOT).contains(q))continue;
            Map<String,Object>x=m.snapshot(world).map();x.put("loaded",true);x.put("managed",false);x.put("status","unmanaged");x.put("operationError","");out.add(x);
        }
        int size=Math.max(1,Math.min(100,c.queryInt("size",20))),page=Math.max(1,c.queryInt("page",1));int start=(int)Math.min(out.size(),(long)(page-1)*size);
        return Map.of("total",out.size(),"items",out.subList(start,Math.min(out.size(),start+size)));
    }
    private Map<String,Object> create(Map<String,Object>b,String actor) throws Exception {
        String n=WorldOptions.validateName(String.valueOf(b.getOrDefault("name","")));
        if(m.worlds.keySet().stream().anyMatch(k -> k.equalsIgnoreCase(n)) || Bukkit.getWorld(n)!=null) throw new IllegalArgumentException("世界名已注册或已加载");
        if(Files.exists(WorldOptions.safePath(Bukkit.getWorldContainer().toPath(),n))) throw new IllegalArgumentException("拒绝覆盖现有未注册目录");
        m.rejectExistingDimension(n);
        WorldOptions o=WorldOptions.parse(n,b,null);m.validate(o);m.worlds.put(n,o);m.states.put(n,"registered");m.errors.put(n,"");
        try {m.persist();}catch(Exception e){m.worlds.remove(n);m.states.remove(n);m.errors.remove(n);throw e;}
        m.audit(actor,"world.create",n);m.queueLoad(o,actor);return accepted(n);
    }
    private Map<String,Object> configure(String n,Map<String,Object>b,String actor) throws Exception {
        WorldOptions old=m.require(n);if(Set.of("queued","running").contains(m.states.get(n)))throw new IllegalArgumentException("世界操作执行中");
        Map<String,Object> patch=new LinkedHashMap<>();b.forEach((k,v)->{if(v!=null&&!String.valueOf(v).isBlank()&&!k.equals("row"))patch.put(k,v);});
        for(String key:List.of("seed","environment","type","name"))if(patch.containsKey(key))throw new IllegalArgumentException("创建后不允许修改生成参数");
        WorldOptions o=WorldOptions.parse(n,patch,old);m.validate(o);World w=Bukkit.getWorld(n);
        if(w!=null && o.spawnY()!=null && (o.spawnY()<w.getMinHeight() || o.spawnY()>=w.getMaxHeight()))throw new IllegalArgumentException("出生Y越界");
        m.worlds.put(n,o);try{m.persist();if(w!=null)m.apply(w,o);}catch(Exception e){m.worlds.put(n,old);try{m.persist();if(w!=null)m.apply(w,old);}catch(Exception rollback){m.fail(n,rollback);}throw e;}
        m.audit(actor,"world.configure",n+" "+patch);return Map.of("ok",true,"message","配置已保存并应用");
    }
    private void teleport(String n,String player,String actor) {
        m.require(n);Player p=Bukkit.getPlayerExact(player);if(p==null)throw new IllegalArgumentException("玩家不在线");
        World w=Bukkit.getWorld(n);if(w==null)throw new IllegalArgumentException("世界未加载");
        if(!p.teleport(w.getSpawnLocation()))throw new IllegalArgumentException("传送被取消");m.audit(actor,"world.teleport",n+" player="+player);
    }
    private static Map<String,Object> accepted(String n){return Map.of("ok",true,"operationId",n,"status","queued","message","世界操作已排队，请刷新列表查看结果");}
    private static String actor(WebContext c){return "web:"+c.user().username();}
    private static <T>T sync(WebContext c,java.util.concurrent.Callable<T> action)throws Exception{
        try{return c.sync(action);}catch(Exception e){for(Throwable t=e;t!=null;t=t.getCause())if(t instanceof IllegalArgumentException)throw HttpException.badRequest(t.getMessage());throw e;}
    }
}
