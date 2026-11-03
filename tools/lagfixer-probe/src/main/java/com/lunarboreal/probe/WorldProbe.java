package com.lunarboreal.probe;

import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.function.Consumer;

/** Destructive isolated-world diagnostic. Never install or invoke on a production world. */
public final class WorldProbe implements Listener {
    private final JavaPlugin plugin;
    private final World world;
    private final Consumer<String> log;
    private final Consumer<Boolean> done;
    private final Map<Block, BlockState> original = new LinkedHashMap<>();
    private final Set<Chunk> tickets = new HashSet<>();
    private final List<Entity> entities = new ArrayList<>();
    private final List<Block> leaves = new ArrayList<>();
    private Block protectedLeaf;
    private Hopper physicalHopper;
    private Chest physicalChest;
    private int oldRandomTick;
    private boolean hopperChunkWasForced;
    private boolean finished;
    private int decayEvents;
    private int syntheticRedstone;
    private final int y;

    private WorldProbe(JavaPlugin p, World w, Consumer<String> l, Consumer<Boolean> d) {
        plugin=p; world=w; log=l; done=d; y=w.getMaxHeight()-30;
    }
    public static void run(JavaPlugin plugin, World world, Consumer<String> log, Consumer<Boolean> done) {
        WorldProbe probe=new WorldProbe(plugin,world,log,done);
        try { probe.start(); } catch(Throwable e) { probe.fail(e); }
    }
    private void require(boolean ok,String text) {
        if(!ok) throw new IllegalStateException(text);
        log.accept("ASSERT "+text);
    }
    private Block block(int dx,int dy,int dz,Material material) {
        Block b=world.getBlockAt(100+dx,y+dy,100+dz);
        if(!tickets.contains(b.getChunk())) { b.getChunk().addPluginChunkTicket(plugin); tickets.add(b.getChunk()); }
        original.putIfAbsent(b,b.getState());
        b.setType(material,false);
        return b;
    }
    private void emit(Event e) { Bukkit.getPluginManager().callEvent(e); }
    private void start() {
        Integer random=world.getGameRuleValue(GameRule.RANDOM_TICK_SPEED);
        oldRandomTick=random==null?3:random;
        world.setGameRule(GameRule.RANDOM_TICK_SPEED,0);
        Bukkit.getPluginManager().registerEvents(this,plugin);
        log.accept("BEGIN actual runtime listeners; injected Bukkit events plus engine hopper/leaf blocks; arena=100,"+y+",100");
        redstone(); explosion(); hopperEvents(); prepareLeaves(); preparePhysicalHopper();
        later(1,()-> {
            long removed=leaves.stream().filter(b->b.getType().isAir()).count();
            require(removed<=32,"leaf per-tick budget: removed after first tick="+removed+" <=32");
            require(protectedLeaf.getType()==Material.OAK_LEAVES,"cancelled leaf remains after first tick");
        });
        later(10,()-> {
            long removed=leaves.stream().filter(b->b.getType().isAir()).count();
            require(removed==leaves.size()-1,"engine leaf breakNaturally removed all eligible leaves="+removed);
            require(protectedLeaf.getType()==Material.OAK_LEAVES,"protection LeavesDecayEvent cancellation retains actual leaf block");
            require(decayEvents>=leaves.size(),"runtime emitted cancellable per-leaf events="+decayEvents);
        });
        later(42,()-> {
            int transferred=Arrays.stream(physicalChest.getInventory().getContents()).filter(Objects::nonNull)
                    .filter(i->i.getType()==Material.COBBLESTONE).mapToInt(ItemStack::getAmount).sum();
            require(transferred>=1 && transferred<=3,"engine hopper/chest inventory transfer over42 ticks="+transferred+" (cooldown20)");
            int remaining=Arrays.stream(physicalHopper.getInventory().getContents()).filter(Objects::nonNull).mapToInt(ItemStack::getAmount).sum();
            require(transferred+remaining==16,"physical hopper conservation: source="+remaining+" target="+transferred);
            log.accept("PASS world behavior probe; injected redstone events="+syntheticRedstone);
            finish(true);
        });
    }
    private void redstone() {
        Block wire=block(0,0,0,Material.REDSTONE_WIRE);
        int blocked=0;
        for(int i=0;i<5;i++) {
            BlockRedstoneEvent e=new BlockRedstoneEvent(wire,0,15); emit(e); syntheticRedstone++;
            if(e.getNewCurrent()==0) blocked++;
        }
        require(blocked>=3,"redstone runtime budget blocks >=3 of5 injected real-block events, blocked="+blocked);
        require(wire.getType()==Material.REDSTONE_WIRE,"redstone throttling retains actual wire block");
        Block piston=block(1,0,0,Material.PISTON);
        List<Block> pushed=List.of(block(2,0,0,Material.STONE),block(3,0,0,Material.STONE),block(4,0,0,Material.STONE));
        BlockPistonExtendEvent event=new BlockPistonExtendEvent(piston,pushed,BlockFace.EAST);emit(event);
        require(event.isCancelled(),"piston budget cancels three-block extension at limit2");
        require(pushed.stream().allMatch(b->b.getType()==Material.STONE),"cancelled piston retains all three actual stone blocks");
        BlockPistonExtendEvent already=new BlockPistonExtendEvent(piston,pushed,BlockFace.EAST);already.setCancelled(true);emit(already);
        require(already.isCancelled(),"previous protection cancellation retained");
    }
    private void explosion() {
        TNTPrimed tnt=world.spawn(new Location(world,110,y+4,110),TNTPrimed.class);tnt.setFuseTicks(12000);entities.add(tnt);
        ExplosionPrimeEvent prime=new ExplosionPrimeEvent(tnt,12,false);emit(prime);
        require(!prime.isCancelled() && prime.getRadius()<=2,"actual TNT entity prime radius capped="+prime.getRadius());
        Block victim=block(10,0,10,Material.STONE);
        EntityExplodeEvent first=new EntityExplodeEvent(tnt,tnt.getLocation(),new ArrayList<>(List.of(victim)),1f,ExplosionResult.DESTROY);emit(first);
        require(!first.isCancelled() && first.getYield()<=.5f,"first TNT explode event permitted with capped drop yield="+first.getYield());
        EntityExplodeEvent chain=new EntityExplodeEvent(tnt,tnt.getLocation(),new ArrayList<>(List.of(victim)),1f,ExplosionResult.DESTROY);emit(chain);
        require(chain.isCancelled(),"nearby second TNT explode event cancelled by chain budget1");
        require(victim.getType()==Material.STONE,"event processing does not itself destroy actual victim stone");
        tnt.remove();
    }
    private void hopperEvents() {
        Hopper h=(Hopper)block(5,0,0,Material.HOPPER).getState();
        Chest chest=(Chest)block(5,-1,0,Material.CHEST).getState();
        h.getInventory().clear();chest.getInventory().clear();h.getInventory().addItem(new ItemStack(Material.DIRT,4));
        InventoryMoveItemEvent first=new InventoryMoveItemEvent(h.getInventory(),new ItemStack(Material.DIRT),chest.getInventory(),true);emit(first);
        require(!first.isCancelled(),"first real-hopper inventory event permitted");
        InventoryMoveItemEvent second=new InventoryMoveItemEvent(h.getInventory(),new ItemStack(Material.DIRT),chest.getInventory(),true);emit(second);
        require(second.isCancelled(),"same-tick real-hopper second inventory event throttled");
        require(h.getInventory().contains(Material.DIRT,4)&&chest.getInventory().isEmpty(),"listeners conserve actual inventory on injected events");
        h.getInventory().clear();
    }
    private void prepareLeaves() {
        for(int i=0;i<71;i++) {
            Block b=block(i%10,5,i/10+8,Material.OAK_LEAVES);
            Leaves data=(Leaves)b.getBlockData();data.setPersistent(false);data.setDistance(7);b.setBlockData(data,false);leaves.add(b);
        }
        protectedLeaf=leaves.get(11);
        // Event only seeds the real production queue; subsequent block deletion is production behavior.
        emit(new LeavesDecayEvent(leaves.getFirst()));
    }
    private void preparePhysicalHopper() {
        physicalHopper=(Hopper)block(18,0,18,Material.HOPPER).getState();
        physicalChest=(Chest)block(18,-1,18,Material.CHEST).getState();
        physicalHopper.getInventory().clear();physicalChest.getInventory().clear();
        physicalHopper.getInventory().addItem(new ItemStack(Material.COBBLESTONE,16));
        // A plugin chunk ticket prevents unloading but not ticking when no players are online.
        // This isolated probe also requests ticking; cleanup removes the request again.
        hopperChunkWasForced=physicalHopper.getChunk().isForceLoaded();
        physicalHopper.getChunk().setForceLoaded(true);
    }
    @EventHandler(priority=EventPriority.HIGHEST)
    public void protect(LeavesDecayEvent e) {
        if(leaves.contains(e.getBlock())) decayEvents++;
        if(e.getBlock().equals(protectedLeaf)) e.setCancelled(true);
    }
    private void later(long ticks,Runnable action) {
        Bukkit.getScheduler().runTaskLater(plugin,()-> {
            if(finished)return;
            try { action.run(); } catch(Throwable e) { fail(e); }
        },ticks);
    }
    private void fail(Throwable e) { log.accept("FAIL "+e); finish(false); }
    private void finish(boolean ok) {
        if(finished)return; finished=true;
        HandlerList.unregisterAll(this);
        entities.forEach(Entity::remove);
        // Remove only drops generated inside this diagnostic arena at build height.
        for(Entity e:world.getNearbyEntities(new Location(world,110,y+4,110),20,12,20)) if(e instanceof Item)e.remove();
        for(BlockState state:original.values()) state.update(true,false);
        world.setGameRule(GameRule.RANDOM_TICK_SPEED,oldRandomTick);
        if(physicalHopper!=null) physicalHopper.getChunk().setForceLoaded(hopperChunkWasForced);
        tickets.forEach(chunk->chunk.removePluginChunkTicket(plugin));
        done.accept(ok);
    }
}
