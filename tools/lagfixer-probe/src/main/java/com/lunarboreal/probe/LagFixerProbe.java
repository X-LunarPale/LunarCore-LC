package com.lunarboreal.probe;

import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;
import java.lang.reflect.Method;
import java.nio.file.*;
import java.util.*;

/** Isolated-server diagnostic only; never install on a production server. No NMS compile dependency. */
public final class LagFixerProbe extends JavaPlugin implements Listener {
    private final Map<String,UUID> ids=new LinkedHashMap<>();
    private final Map<UUID,List<String>> originalGoals=new LinkedHashMap<>();
    private final Set<UUID> priorEntities=new HashSet<>();
    private int births;
    private Location arena;
    private Path log;
    @Override public void onEnable(){log=getDataFolder().toPath().resolve("probe.log");getServer().getPluginManager().registerEvents(this,this);write("READY commands=seed,optimized,restored,breed,bred,world,cleanup");}
    private void write(String text){String line="LAGFIXER_PROBE "+text;getLogger().info(line);try{Files.createDirectories(log.getParent());Files.writeString(log,line+System.lineSeparator(),StandardOpenOption.CREATE,StandardOpenOption.APPEND);}catch(Exception e){getLogger().severe("Cannot append probe log: "+e);}}
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args){
        if(!(sender instanceof ConsoleCommandSender)){sender.sendMessage("Console only");return true;}
        try{switch(args.length==0?"":args[0]){
            case "world"->WorldProbe.run(this,Bukkit.getWorlds().getFirst(),line->write("WORLD "+line),ok->write(ok?"PASS world probe":"FAIL world probe"));
            case "seed"->seed();
            case "optimized"->optimized();
            case "restored"->restored();
            case "breed"->breed();
            case "bred"->require(births>0,"birth event observed births="+births);
            case "cleanup"->cleanup();
            default->write("USAGE lagfixerprobe seed|optimized|restored|breed|bred|world|cleanup");
        }}catch(Throwable e){write("FAIL "+e.getClass().getSimpleName()+" "+e.getMessage());e.printStackTrace();}return true;
    }
    private void seed()throws Exception{
        if(!ids.isEmpty())throw new IllegalStateException("cleanup before seed");
        World w=Bukkit.getWorlds().getFirst();arena=w.getSpawnLocation().clone().add(32,0,32);arena.setY(w.getHighestBlockYAt(arena)+2);
        w.getChunkAt(arena).addPluginChunkTicket(this);
        for(int x=-5;x<=5;x++)for(int z=-5;z<=5;z++)w.getBlockAt(arena.getBlockX()+x,arena.getBlockY()-1,arena.getBlockZ()+z).setType(Material.STONE);
        for(Entity e:w.getEntities())priorEntities.add(e.getUniqueId());
        add("cow1",w.spawn(arena.clone().add(-2,0,0),Cow.class));add("cow2",w.spawn(arena.clone().add(2,0,0),Cow.class));
        add("minecart",w.spawn(arena.clone().add(0,0,3),org.bukkit.entity.Minecart.class));
        for(String name:List.of("cow1","cow2"))originalGoals.put(entity(name).getUniqueId(),goals(entity(name)));
        write("SEED ids="+ids+" goals="+originalGoals+" arena="+arena.getBlockX()+","+arena.getBlockY()+","+arena.getBlockZ());
        require(!className(entity("minecart")).contains("lagfixer"),"baseline minecart vanilla");
    }
    private void add(String name,Entity e){ids.put(name,e.getUniqueId());}
    private Entity entity(String name){UUID id=ids.get(name);if(id==null)throw new IllegalStateException("seed first");Entity e=Bukkit.getEntity(id);if(e==null)throw new IllegalStateException(name+" disappeared UUID="+id);return e;}
    private Object handle(Entity e)throws Exception{return e.getClass().getMethod("getHandle").invoke(e);}
    private String className(Entity e)throws Exception{return handle(e).getClass().getName();}
    private List<String> goals(Entity e)throws Exception{
        Object mob=handle(e);Object selector=mob.getClass().getMethod("getGoalSelector").invoke(mob);
        Collection<?> goals=(Collection<?>)selector.getClass().getMethod("getAvailableGoals").invoke(selector);
        List<String> result=new ArrayList<>();for(Object wrapped:goals){Object goal=wrapped.getClass().getMethod("getGoal").invoke(wrapped);result.add(wrapped.getClass().getMethod("getPriority").invoke(wrapped)+":"+goal.getClass().getName());}Collections.sort(result);return result;
    }
    private void optimized()throws Exception{
        require(!ids.isEmpty(),"seed exists");
        Entity cart=entity("minecart");require(className(cart).contains("VehicleOptimization$ReducedMinecart"),"ordinary minecart uses reduced NMS subclass "+className(cart));
        unique(cart.getWorld());
        for(String name:List.of("cow1","cow2")){List<String> g=goals(entity(name));require(g.stream().anyMatch(s->s.contains("ReducedBreedGoal")),name+" breed goal replaced "+g);require(g.stream().anyMatch(s->s.contains("ReducedTemptGoal")),name+" tempt goal replaced");}
        write("PASS optimized UUIDs="+ids);
    }
    private void restored()throws Exception{
        Entity cart=entity("minecart");require(className(cart).equals("net.minecraft.world.entity.vehicle.minecart.Minecart"),"minecart restored to vanilla "+className(cart));unique(cart.getWorld());
        require(cart.getScheduler().execute(this,()->write("PASS restored scheduler task executed"),()->write("FAIL restored scheduler retired"),1),"restored entity scheduler accepts task");
        for(String name:List.of("cow1","cow2")){Entity cow=entity(name);require(goals(cow).equals(originalGoals.get(cow.getUniqueId())),name+" original goals/priorities restored");}
        write("PASS restored UUIDs="+ids);
    }
    private void unique(World world){for(var entry:ids.entrySet()){long count=world.getEntities().stream().filter(e->e.getUniqueId().equals(entry.getValue())).count();require(count==1,"UUID unique "+entry.getKey()+" count="+count);}}
    private void breed()throws Exception{
        births=0;
        for(String name:List.of("cow1","cow2")){
            Cow cow=(Cow)entity(name);cow.teleport(arena.clone().add(name.equals("cow1")?-1:1,0,0));cow.setAdult();cow.setBreed(true);cow.setLoveModeTicks(600);
            Object mob=handle(cow);
            write("BREED_DIAG "+name+" ai="+cow.hasAI()+" aware="+cow.isAware()+" age="+cow.getAge()+" love="+cow.getLoveModeTicks()+" ticks="+cow.getTicksLived()+" position="+cow.getLocation()+" players="+cow.getWorld().getPlayers().size()+" goals="+goals(cow));
            for(String field:List.of("activatedTick","defaultActivationState"))try{write("BREED_ACTIVATION "+name+" "+field+"="+mob.getClass().getField(field).get(mob));}catch(ReflectiveOperationException ignored){}
        }
        // Plugin chunk tickets guarantee loaded chunks, not Paper entity activation without players.
        // Activate only the two probe cows for 200 ticks, then restore their previous activation marker.
        Map<Object,Long> activation=new IdentityHashMap<>();
        for(String name:List.of("cow1","cow2")){Object mob=handle(entity(name));var field=mob.getClass().getField("activatedTick");activation.put(mob,field.getLong(mob));field.setLong(mob,Long.MAX_VALUE);}
        Bukkit.getScheduler().runTaskLater(this,()->{
            for(var entry:activation.entrySet())try{entry.getKey().getClass().getField("activatedTick").setLong(entry.getKey(),entry.getValue());}catch(ReflectiveOperationException ex){write("FAIL activation restore "+ex);}
            for(String name:List.of("cow1","cow2"))try{Cow cow=(Cow)entity(name);write("BREED_AFTER "+name+" love="+cow.getLoveModeTicks()+" age="+cow.getAge()+" ticks="+cow.getTicksLived()+" position="+cow.getLocation());}catch(Exception ex){write("FAIL breed diagnostics "+ex);}
        },200);
        write("BREED_STARTED probe cows activation forced for 200 ticks; wait >=40ticks then bred");
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void born(EntityBreedEvent e){if(ids.containsValue(e.getFather().getUniqueId())&&ids.containsValue(e.getMother().getUniqueId())){births++;write("BIRTH child="+e.getEntity().getUniqueId()+" parents="+e.getFather().getUniqueId()+","+e.getMother().getUniqueId());}}
    @EventHandler(priority=EventPriority.MONITOR)
    public void target(org.bukkit.event.entity.EntityTargetLivingEntityEvent e){if(ids.containsValue(e.getEntity().getUniqueId()))write("TARGET uuid="+e.getEntity().getUniqueId()+" reason="+e.getReason()+" cancelled="+e.isCancelled()+" target="+(e.getTarget()==null?null:e.getTarget().getUniqueId()));}
    @EventHandler(priority=EventPriority.MONITOR)
    public void breedAttempt(EntityBreedEvent e){if(ids.containsValue(e.getFather().getUniqueId()))write("BREED_EVENT cancelled="+e.isCancelled());}
    private void require(boolean value,String message){if(!value)throw new IllegalStateException(message);write("ASSERT "+message);}
    private void cleanup(){if(arena!=null){for(Entity e:arena.getWorld().getNearbyEntities(arena,16,16,16))if(!priorEntities.contains(e.getUniqueId())&&!(e instanceof Player))e.remove();arena.getChunk().removePluginChunkTicket(this);}ids.clear();originalGoals.clear();priorEntities.clear();write("CLEANUP entities removed; stone platform retained in isolated world");}
}
