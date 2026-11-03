package com.lunarboreal.vote;

import com.lunarboreal.api.module.LbrModule;
import com.lunarboreal.api.command.LbrCommand;
import com.lunarboreal.api.web.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.command.CommandSender;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.potion.*;
import net.kyori.adventure.text.Component;
import java.util.*;
import java.io.IOException;

public final class VoteModule extends LbrModule implements org.bukkit.event.Listener {
    private static final Map<String,VoteProposal> EFFECTS=Map.of("speed",VoteProposal.speed,"jump",VoteProposal.jump,"slow",VoteProposal.slow);
    private VoteStore store; private volatile VoteStore.Snapshot state;
    private final Map<UUID,Set<PotionEffectType>> owned=new HashMap<>();
    private boolean healthy, changingEffects; private long nextAutomatic;
    @Override public void onEnable(){
        store=new VoteStore(dataFolder().toPath().resolve("state.properties"));
        try{state=store.load();if(state.vote()!=null||!state.effect().isEmpty())commit(null,"",0,"restart-cancelled",state.cooldownUntil());}
        catch(IOException e){throw new IllegalStateException("不能安全读取/恢复投票状态",e);}
        healthy=true;nextAutomatic=System.currentTimeMillis()+interval()*1000L;
        defaultPermissions("lbr.vote.use");registerPermission("lbr.vote.start",PermissionDefault.OP,"发起白名单玩法投票");registerPermission("lbr.vote.admin",PermissionDefault.OP,"取消投票和停止效果");
        registerCommand(new VoteCommand());registerListener(this);registerWeb();runTaskTimer(this::tick,20,20);
    }
    @Override public void onReload(){stop("reload-cancelled");super.onReload();healthy=true;nextAutomatic=System.currentTimeMillis()+interval()*1000L;}
    @Override public void onDisable(){try{stop("disable-cancelled");}finally{healthy=false;clearEffects();}}
    private long interval(){return Math.clamp(config().getLong("automatic.interval-seconds",900),300,86400);}
    private boolean available(String id){return EFFECTS.containsKey(id)&&config().getBoolean("effects."+id+".enabled",false);}
    private void requireHealthy(){if(!healthy)throw new IllegalStateException("投票模块写盘失败或正在关闭，请管理员修复后重载");}
    private int duration(int value,String key,int low,int high){int max=Math.clamp(config().getInt(key,high),low,high);if(value<low||value>max)throw new IllegalArgumentException("时长范围 "+low+"–"+max+" 秒");return value;}
    private double ratio(String key,double fallback){double n=config().getDouble(key,fallback);if(!Double.isFinite(n)||n<=0||n>1)throw new IllegalArgumentException(key+"须在(0,1]");return n;}
    private void commit(VoteState vote,String effect,long until,String history,long cooldown)throws IOException{
        List<String> entries=new ArrayList<>(state==null?List.of():state.history());if(history!=null){entries.add(0,System.currentTimeMillis()+" "+history);if(entries.size()>50)entries.removeLast();}
        VoteStore.Snapshot next=new VoteStore.Snapshot(vote,cooldown,effect,until,entries);store.save(next);state=next;
    }
    private void persist(VoteState vote,String effect,long until,String history,long cooldown){try{commit(vote,effect,until,history,cooldown);}catch(IOException ex){healthy=false;clearEffects();logger().log(java.util.logging.Level.SEVERE,"投票写盘失败，停止效果并禁止新操作",ex);throw new IllegalStateException("投票未保存，操作失败",ex);}}
    private void start(String id,int seconds,int effectSeconds,String actor){
        requireHealthy();if(!config().getBoolean("enabled",true))throw new IllegalArgumentException("玩法投票已关闭");
        if(!available(id))throw new IllegalArgumentException("未知或关闭的效果；仅允许 "+EFFECTS.keySet());
        long now=System.currentTimeMillis();if(state.vote()!=null||!state.effect().isEmpty())throw new IllegalArgumentException("已有投票或效果，不能并发");
        if(now<state.cooldownUntil())throw new IllegalArgumentException("全服冷却剩余 "+((state.cooldownUntil()-now+999)/1000)+" 秒");
        duration(seconds,"max-vote-seconds",30,300);duration(effectSeconds,"max-effect-seconds",15,300);
        Set<UUID> electorate=new HashSet<>();for(Player p:Bukkit.getOnlinePlayers())if(p.hasPermission("lbr.vote.use")&&eligible(p))electorate.add(p.getUniqueId());
        int min=Math.clamp(config().getInt("minimum-online",3),2,200);if(electorate.size()<min||electorate.size()>200)throw new IllegalArgumentException("需 "+min+"–200 名有投票权的在线生存/冒险玩家");
        VoteState vote=new VoteState(UUID.randomUUID(),id,now+seconds*1000L,effectSeconds,electorate,Map.of(),ratio("quorum",.5),ratio("approval",.6));
        persist(vote,"",0,"started "+id+" by "+actor,now+Math.clamp(config().getInt("cooldown-seconds",300),60,86400)*1000L);
        Bukkit.broadcast(api().messages().parse("<dark_gray>[</dark_gray><blue><bold>玩法投票</bold></blue><dark_gray>]</dark_gray> "+id+"（"+description(id)+"），投票"+seconds+"秒，通过后"+effectSeconds+"秒。/vote yes 或 /vote no；需参与"+Math.ceil(vote.electorate().size()*vote.quorum())+"人，赞成比例"+vote.approval()+"且赞成多于反对。"));audit(actor,"start",id);
    }
    private String description(String id){VoteProposal proposal=EFFECTS.get(id);return proposal==null?"无":proposal.description();}
    private void cast(Player player,boolean yes){requireHealthy();if(!eligible(player))throw new IllegalArgumentException("仅配置世界中的生存/冒险玩家可投票");VoteState vote=state.vote();if(vote==null)throw new IllegalArgumentException("没有正在进行的投票");VoteState next=vote.cast(player.getUniqueId(),yes,System.currentTimeMillis());persist(next,"",0,null,state.cooldownUntil());audit(player.getUniqueId().toString(),"ballot",vote.id()+" "+yes);}
    private boolean eligible(Player p){return (p.getGameMode()==GameMode.SURVIVAL||p.getGameMode()==GameMode.ADVENTURE)&&config().getStringList("worlds").stream().anyMatch(w->w.equals("*")||w.equals(p.getWorld().getName()));}
    private boolean ours(PotionEffect e){return e!=null&&e.getAmplifier()==0&&e.isAmbient()&&!e.hasParticles()&&!e.hasIcon()&&e.getDuration()>=0&&e.getDuration()<=60;}
    private void clearPlayer(Player p) {
        Set<PotionEffectType> types=owned.remove(p.getUniqueId());if(types==null)return;
        boolean prior=changingEffects;changingEffects=true;
        try{for(PotionEffectType type:types)if(ours(p.getPotionEffect(type)))p.removePotionEffect(type);}
        finally{changingEffects=prior;}
    }
    private void clearEffects(){for(UUID id:List.copyOf(owned.keySet())){Player p=Bukkit.getPlayer(id);if(p!=null)clearPlayer(p);}owned.clear();}
    @org.bukkit.event.EventHandler(priority=org.bukkit.event.EventPriority.MONITOR,ignoreCancelled=true)
    public void externalEffect(org.bukkit.event.entity.EntityPotionEffectEvent event){
        if(!changingEffects&&event.getEntity() instanceof Player p){
            Set<PotionEffectType> types=owned.get(p.getUniqueId());
            // Relinquish only the externally changed type; the next tick removes the sibling
            // instead of granting a benefit without its cost. Never remove the external effect.
            if(types!=null)types.remove(event.getModifiedType());
        }
    }
    @org.bukkit.event.EventHandler public void quit(org.bukkit.event.player.PlayerQuitEvent event){clearPlayer(event.getPlayer());}
    @org.bukkit.event.EventHandler public void changedWorld(org.bukkit.event.player.PlayerChangedWorldEvent event){if(!eligible(event.getPlayer()))clearPlayer(event.getPlayer());}
    @org.bukkit.event.EventHandler(priority=org.bukkit.event.EventPriority.MONITOR,ignoreCancelled=true)
    public void changedMode(org.bukkit.event.player.PlayerGameModeChangeEvent event){if(event.getNewGameMode()!=GameMode.SURVIVAL&&event.getNewGameMode()!=GameMode.ADVENTURE)clearPlayer(event.getPlayer());}
    private void apply(){
        VoteProposal proposal=EFFECTS.get(state.effect());if(proposal==null)return;
        List<PotionEffectType> types=proposal.effects.stream().map(name->Objects.requireNonNull(PotionEffectType.getByName(name))).toList();
        for(Player p:Bukkit.getOnlinePlayers()){
            if(!eligible(p)){clearPlayer(p);continue;}
            Set<PotionEffectType> mine=owned.computeIfAbsent(p.getUniqueId(),id->new HashSet<>());
            mine.removeIf(type->!ours(p.getPotionEffect(type)));
            Set<String> existing=new HashSet<>(),leased=new HashSet<>();
            for(int i=0;i<types.size();i++){if(p.hasPotionEffect(types.get(i)))existing.add(proposal.effects.get(i));if(mine.contains(types.get(i)))leased.add(proposal.effects.get(i));}
            // If either slot belongs to someone else, skip the entire pair and clear ours.
            if(!proposal.canLease(existing,leased)){clearPlayer(p);continue;}
            boolean success=true;changingEffects=true;
            try{
                // Cost first; benefit only if the cost was accepted. Roll back either half
                // when another plugin cancels the addition or changes the resulting effect.
                for(int i=types.size()-1;i>=0;i--){PotionEffectType type=types.get(i);
                    if(!p.addPotionEffect(new PotionEffect(type,60,0,true,false,false))||!ours(p.getPotionEffect(type))){success=false;break;}
                    mine.add(type);
                }
            }finally{changingEffects=false;}
            if(!success)clearPlayer(p);
        }
        owned.keySet().removeIf(id->Bukkit.getPlayer(id)==null);
    }
    private void stop(String reason){if(state==null)return;try{if(state.vote()!=null||!state.effect().isEmpty())persist(null,"",0,reason,state.cooldownUntil());}finally{clearEffects();}}
    private void tick(){if(!healthy)return;try{
        long now=System.currentTimeMillis();if(!config().getBoolean("enabled",true)){stop("config-disabled");return;}
        VoteState v=state.vote();if(v!=null&&now>=v.deadline()){
            boolean pass=v.passed()&&available(v.effect());persist(null,pass?v.effect():"",pass?now+v.effectSeconds()*1000L:0,"result "+v.id()+" yes="+v.yes()+" no="+(v.ballots().size()-v.yes())+" passed="+pass,state.cooldownUntil());
            Bukkit.broadcast(api().messages().parse("<dark_gray>[</dark_gray><blue><bold>玩法投票</bold></blue><dark_gray>]</dark_gray> "+v.effect()+"（"+description(v.effect())+"） "+(pass?"通过":"未通过")+"，支持"+v.yes()+" / 不支持"+(v.ballots().size()-v.yes())));
        }
        if(!state.effect().isEmpty()){if(now>=state.effectUntil()||!available(state.effect()))stop("effect-ended");else apply();}
        if(config().getBoolean("automatic.enabled",false)&&now>=nextAutomatic){nextAutomatic=now+interval()*1000L;if(state.vote()==null&&state.effect().isEmpty()&&now>=state.cooldownUntil()){
            List<String> pool=config().getStringList("automatic.pool").stream().filter(this::available).toList();if(!pool.isEmpty())start(pool.get(new Random().nextInt(pool.size())),config().getInt("vote-seconds",60),config().getInt("effect-seconds",60),"automatic");}}
    }catch(IllegalArgumentException ex){logger().warning("投票配置/自动提案未执行: "+ex.getMessage());}catch(RuntimeException ex){logger().log(java.util.logging.Level.SEVERE,"投票运行已停止",ex);healthy=false;clearEffects();}}
    private Map<String,Object> status(){VoteState v=state.vote();Map<String,Object> out=new LinkedHashMap<>();out.put("healthy",healthy);String current=v==null?state.effect():v.effect();VoteProposal proposal=EFFECTS.get(current);out.put("benefit",proposal==null?"无":proposal.benefit);out.put("drawback",proposal==null?"无":proposal.drawback);out.put("effect",state.effect());out.put("effectUntil",state.effectUntil());out.put("cooldownUntil",state.cooldownUntil());out.put("proposal",v==null?"无":v.effect());out.put("deadline",v==null?0:v.deadline());out.put("yes",v==null?0:v.yes());out.put("no",v==null?0:v.ballots().size()-v.yes());out.put("electorate",v==null?0:v.electorate().size());out.put("quorum",v==null?0:v.quorum());out.put("approval",v==null?0:v.approval());return out;}
    private final class VoteCommand extends LbrCommand {
        VoteCommand(){super("vote","lbr.vote.use","lbrvote");description("全服玩法支持/不支持投票");usage("/vote yes|no|status|start <speed|jump|slow> [投票秒] [效果秒]|cancel|stop");}
        @Override public void execute(CommandSender sender,String label,String[] args){try{
            String action=args.length==0?"status":args[0].toLowerCase(Locale.ROOT);
            switch(action){
                case "status"->{if(args.length>1)throw new IllegalArgumentException("/vote status");sender.sendMessage(Component.text(status().toString()));for(VoteProposal proposal:VoteProposal.values())sender.sendMessage(Component.text(proposal.name()+"（"+description(proposal.name())+"），配置启用="+available(proposal.name())));}
                case "yes","no"->{if(args.length!=1||!(sender instanceof Player p))throw new IllegalArgumentException("需玩家使用 /vote yes 或 no");cast(p,action.equals("yes"));sender.sendMessage(Component.text("已保存你的"+(action.equals("yes")?"支持":"不支持")+"票"));}
                case "start"->{if(!sender.hasPermission("lbr.vote.start"))throw new IllegalArgumentException("需要 lbr.vote.start");if(args.length<2||args.length>4)throw new IllegalArgumentException(usage());start(args[1],args.length>2?Integer.parseInt(args[2]):config().getInt("vote-seconds",60),args.length>3?Integer.parseInt(args[3]):config().getInt("effect-seconds",60),sender.getName());}
                case "cancel","stop"->{if(!sender.hasPermission("lbr.vote.admin")||args.length!=1)throw new IllegalArgumentException("需要管理权限");requireHealthy();stop("admin-"+action);audit(sender.getName(),action,"vote/effect stopped");sender.sendMessage(Component.text("投票/效果已停止"));}
                default->throw new IllegalArgumentException("/vote yes|no|status|start <speed|jump|slow> [投票秒] [效果秒]|cancel|stop");
            }
        }catch(RuntimeException ex){sender.sendMessage(Component.text("投票操作失败: "+ex.getMessage()));}}
        @Override public List<String> tabComplete(CommandSender sender,String alias,String[] args){if(args.length==1)return filter(List.of("yes","no","status","start","cancel","stop"),args[0]);if(args.length==2&&args[0].equals("start"))return filter(EFFECTS.keySet().stream().sorted().toList(),args[1]);return List.of();}
    }
    static Map<String,Object> validateStartBody(Map<String,Object> body){
        if(body==null||body.keySet().stream().anyMatch(k->!Set.of("effect","voteSeconds","effectSeconds","row").contains(k))
                ||(body.containsKey("row")&&(!(body.get("row") instanceof Map<?,?> row)||!row.isEmpty()))
                ||!(body.get("effect") instanceof String))
            throw new IllegalArgumentException("仅允许effect、voteSeconds、effectSeconds及空row");
        return body;
    }
    private int number(Map<String,Object> body,String key,int fallback){Object n=body.get(key);if(n==null)return fallback;if(!(n instanceof Number value)||!Double.isFinite(value.doubleValue())||value.doubleValue()!=Math.rint(value.doubleValue())||value.doubleValue()<1||value.doubleValue()>300)throw new IllegalArgumentException(key+"须为整数秒");return value.intValue();}
    private void registerWeb(){
        webPermission("vote.view","查看全服玩法投票");webPermission("vote.manage","发起白名单投票、取消和停止效果");
        webRoutes(r->r.get("/status","vote.view",c->c.json(c.sync(this::status)))
            .get("/effects","vote.view",c->{var rows=c.sync(()->EFFECTS.keySet().stream().sorted().map(id->Map.of("id",id,"description",description(id),"benefit",EFFECTS.get(id).benefit,"drawback",EFFECTS.get(id).drawback,"enabled",available(id))).toList());c.page(rows.size(),rows);})
            .get("/current","vote.view",c->c.page(1,List.of(c.sync(this::status))))
            .get("/history","vote.view",c->{List<String> h=state.history();c.page(h.size(),h.stream().map(s->Map.of("event",s)).toList());})
            .post("/start","vote.manage",c->{try{Map<String,Object>b=validateStartBody(c.bodyMap());String id=(String)b.get("effect");c.sync(()->{start(id,number(b,"voteSeconds",config().getInt("vote-seconds",60)),number(b,"effectSeconds",config().getInt("effect-seconds",60)),"web:"+c.user().username());return null;});c.ok("投票已发起");}catch(IllegalArgumentException ex){throw HttpException.badRequest(ex.getMessage());}})
            .post("/stop","vote.manage",c->{if(c.body()!=null&&!c.body().isBlank()&&!c.body().equals("{}"))throw HttpException.badRequest("无需参数");c.sync(()->{requireHealthy();stop("web-stopped");audit("web:"+c.user().username(),"stop","vote/effect stopped");return null;});c.ok("已停止");}));
        webPanel(WebPanel.of("vote","全服玩法投票").icon("vote").group("玩法").order(60).permission("vote.view")
            .section(new WebPanel.Table("当前投票与效果（截止为Unix毫秒）","current").column("healthy","运行正常").column("proposal","提案").column("benefit","收益").column("drawback","代价").column("yes","支持").column("no","不支持").column("electorate","选民人数").column("deadline","截止时间").column("effect","进行中效果").column("effectUntil","效果到期"))
            .section(new WebPanel.Table("可投票效果（无任意命令）","effects").rowKey("id").column("id","ID").column("benefit","收益（同时施加）").column("drawback","代价（同时施加）").column("enabled","配置开启")
                .toolbarAction(new WebPanel.Action("start","发起玩法投票","start").permission("vote.manage")
                    .field(new WebPanel.Field("effect","效果","select").option("speed","速度I＋饥饿I：快跑但更饿").option("jump","跳跃I＋虚弱I：跳高但近战弱").option("slow","急迫I＋缓慢I：挖快但走慢").required(true))
                    .field(new WebPanel.Field("voteSeconds","投票秒数30–300","number").required(true))
                    .field(new WebPanel.Field("effectSeconds","效果秒数15–300","number").required(true)))
                .toolbarAction(new WebPanel.Action("stop","停止投票/效果","stop").permission("vote.manage")))
            .section(new WebPanel.Table("最近50条记录","history").column("event","记录")));
    }
}
