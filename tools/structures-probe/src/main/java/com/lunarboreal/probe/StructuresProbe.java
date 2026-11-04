package com.lunarboreal.probe;

import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.BoundingBox;
import java.util.*;

/** Local acceptance helper. Mutations are restricted to worlds explicitly named structures-*. */
public final class StructuresProbe extends JavaPlugin implements CommandExecutor {
    private org.apache.logging.log4j.core.appender.AbstractAppender unsafeReads;
    @Override public void onEnable() {
        Objects.requireNonNull(getCommand("structuresprobe")).setExecutor(this);
        var first = new java.util.concurrent.atomic.AtomicBoolean();
        unsafeReads = new org.apache.logging.log4j.core.appender.AbstractAppender("StructuresProbeWorldgen", null,
                org.apache.logging.log4j.core.layout.PatternLayout.createDefaultLayout(), false,
                org.apache.logging.log4j.core.config.Property.EMPTY_ARRAY) {
            @Override public void append(org.apache.logging.log4j.core.LogEvent event) {
                if (event.getMessage().getFormattedMessage().contains("Detected unsafe terrain read") && first.compareAndSet(false,true)) {
                    var trace = new java.io.StringWriter();
                    new IllegalStateException("Acceptance probe: first unsafe terrain read").printStackTrace(new java.io.PrintWriter(trace));
                    try {
                        java.nio.file.Files.createDirectories(getDataFolder().toPath());
                        java.nio.file.Files.writeString(getDataFolder().toPath().resolve("unsafe-worldgen-stack.txt"), trace.toString());
                    } catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
                }
            }
        };
        unsafeReads.start();
        ((org.apache.logging.log4j.core.Logger)org.apache.logging.log4j.LogManager.getRootLogger()).addAppender(unsafeReads);
    }
    @Override public void onDisable() {
        if (unsafeReads != null) {
            ((org.apache.logging.log4j.core.Logger)org.apache.logging.log4j.LogManager.getRootLogger()).removeAppender(unsafeReads);
            unsafeReads.stop();
        }
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof ConsoleCommandSender)) { sender.sendMessage("Local test console only"); return true; }
        try {
            if (args.length < 2) throw new IllegalArgumentException("structuresprobe clear|inspect|end-reset <world-key> [x z radius]");
            World world = Bukkit.getWorld(NamespacedKey.fromString(args[1]));
            if (world == null || !(world.getName().startsWith("structures-") || world.getKey().getKey().startsWith("structures-")))
                throw new IllegalArgumentException("Not a structures-* test world");
            switch (args[0]) {
                case "clear" -> {
                    int x = Integer.parseInt(args[2]), z = Integer.parseInt(args[3]), radius = Integer.parseInt(args[4]);
                    if (radius < 1 || radius > 256) throw new IllegalArgumentException("Radius must be 1..256");
                    var bounds = new BoundingBox(x-radius, world.getMinHeight(), z-radius, x+radius+1d, world.getMaxHeight(), z+radius+1d);
                    Map<String, Integer> removed = new TreeMap<>();
                    for (var entity : world.getNearbyEntities(bounds)) {
                        if (entity instanceof org.bukkit.entity.Player) continue;
                        removed.merge(entity.getType().name(), 1, Integer::sum);
                        entity.remove();
                    }
                    world.save();
                    sender.sendMessage("STRUCTURES_PROBE cleared test entities " + removed);
                }
                case "inspect" -> {
                    int x = Integer.parseInt(args[2]), z = Integer.parseInt(args[3]);
                    if (!world.isChunkLoaded(x, z)) throw new IllegalArgumentException("Load the test chunk first");
                    var level = ((CraftWorld) world).getHandle();
                    var registry = level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.STRUCTURE);
                    level.getChunk(x, z).getAllStarts().forEach((type, start) -> {
                        if (start.isValid()) sender.sendMessage("STRUCTURES_PROBE " + registry.getKey(type) + " pieces=" + start.getPieces().size() + " bounds=" + start.getBoundingBox());
                    });
                }
                case "end-reset" -> {
                    var fight = ((CraftWorld) world).getHandle().getDragonFight();
                    if (fight == null || !fight.getClass().getName().equals("com.lunarboreal.structures.end.NativeEndFight"))
                        throw new IllegalArgumentException("Better End fight is not installed");
                    fight.skipArenaLoadedCheck();
                    fight.getClass().getMethod("reset", boolean.class).invoke(fight, false);
                    world.save();
                    sender.sendMessage("STRUCTURES_PROBE reset fresh test End island");
                }
                case "smoke" -> smoke(sender, world, args[2]);
                case "fingerprint" -> fingerprint(sender, world, Integer.parseInt(args[2]), Integer.parseInt(args[3]), Integer.parseInt(args[4]), args[5]);
                case "visit" -> {
                    var player = Bukkit.getPlayerExact("LunarCoreTest");
                    if (player == null) throw new IllegalStateException("Vanilla acceptance client is not connected");
                    if (player.isDead()) player.spigot().respawn();
                    Bukkit.getScheduler().runTaskLater(this, () -> {
                        var viewer = Bukkit.getPlayerExact("LunarCoreTest");
                        if (viewer == null) return;
                        viewer.setGameMode(GameMode.SPECTATOR);
                        var destination = new Location(world, Double.parseDouble(args[2]), Double.parseDouble(args[3]), Double.parseDouble(args[4]),
                                args.length > 5 ? Float.parseFloat(args[5]) : 0, args.length > 6 ? Float.parseFloat(args[6]) : 0);
                        viewer.teleportAsync(destination).thenAccept(ok -> sender.sendMessage("STRUCTURES_PROBE vanilla-client visit " + world.getKey() + " " + destination.toVector() + " success=" + ok));
                    }, 20);
                }
                case "gateway" -> {
                    var level = ((CraftWorld) world).getHandle();
                    var origin = new net.minecraft.core.BlockPos(Integer.parseInt(args[2]), Integer.parseInt(args[3]), Integer.parseInt(args[4]));
                    var gateway = (net.minecraft.world.level.block.entity.TheEndGatewayBlockEntity) level.getBlockEntity(origin);
                    if (gateway == null) throw new IllegalStateException("No gateway at test position");
                    var plugin = Bukkit.getPluginManager().getPlugin("LunarCoreStructures");
                    var type = plugin.getClass().getClassLoader().loadClass("com.lunarboreal.structures.end.PortedEndGateway");
                    var upgraded = (net.minecraft.world.level.block.entity.TheEndGatewayBlockEntity) type.getMethod("install",net.minecraft.server.level.ServerLevel.class,net.minecraft.world.level.block.entity.TheEndGatewayBlockEntity.class).invoke(null,level,gateway);
                    var destination=upgraded.getPortalPosition(level,origin);
                    if(destination==null || upgraded.exitPortal==null)throw new IllegalStateException("Gateway has no return link");
                    var remote=(net.minecraft.world.level.block.entity.TheEndGatewayBlockEntity)level.getBlockEntity(upgraded.exitPortal);
                    if(remote==null || !origin.equals(remote.exitPortal))throw new IllegalStateException("Gateway return link is incorrect");
                    var upgradedRemote=(net.minecraft.world.level.block.entity.TheEndGatewayBlockEntity)type.getMethod("install",net.minecraft.server.level.ServerLevel.class,net.minecraft.world.level.block.entity.TheEndGatewayBlockEntity.class).invoke(null,level,remote);
                    var returned=upgradedRemote.getPortalPosition(level,remote.getBlockPos());
                    world.save();
                    report("end-gateway",Map.of("outbound",destination.toString(),"return",returned.toString(),"remote",remote.getBlockPos().toString(),"build",Bukkit.getVersion()));
                    sender.sendMessage("STRUCTURES_PROBE PASS end-gateway " + destination + " return=" + returned);
                }
                case "end-cycle" -> endCycle(sender, world);
                case "end-resummon" -> {
                    var level=((CraftWorld)world).getHandle();var fight=level.getDragonFight();
                    var dragon=level.getDragons().stream().findFirst().orElse(null);
                    if (dragon != null) { fight.setDragonKilled(dragon);dragon.discard(); }
                    else if (!fight.hasPreviouslyKilledDragon()) throw new IllegalStateException("No defeated dragon to resummon");
                    var center=fight.exitPortalLocation.below(2);
                    for (var direction:net.minecraft.core.Direction.Plane.HORIZONTAL) {
                        net.minecraft.core.BlockPos pos=null;
                        for (int radius : new int[]{2,3}) {
                            var candidate=center.relative(direction,radius);
                            if (level.getBlockState(candidate.below()).is(net.minecraft.world.level.block.Blocks.BEDROCK)
                                    || level.getBlockState(candidate.below()).is(net.minecraft.world.level.block.Blocks.OBSIDIAN)) { pos=candidate;break; }
                        }
                        if (pos==null) throw new IllegalStateException("Portal rim has no crystal base");
                        var crystal=new net.minecraft.world.entity.boss.enderdragon.EndCrystal(level,pos.getX()+.5,pos.getY(),pos.getZ()+.5);
                        level.addFreshEntity(crystal);
                    }
                    if (!fight.tryRespawn()) throw new IllegalStateException("Vanilla four-crystal summoning was rejected");
                    runEndStages(sender,world,"end-resummon");
                }
                case "end-status" -> {
                    for(int x=-4;x<=4;x++)for(int z=-4;z<=4;z++)world.setChunkForceLoaded(x,z,true);
                    Bukkit.getScheduler().runTaskLater(this,()->{
                        try {
                            var level=((CraftWorld)world).getHandle();var fight=level.getDragonFight();
                            Object kills=fight.getClass().getMethod("getNumTimesDragonKilled").invoke(fight);
                            sender.sendMessage("STRUCTURES_PROBE end-status dragons="+level.getDragons().size()+" crystals="+fight.getSpikeCrystals().size()+" kills="+kills);
                        }catch(Exception e){getLogger().log(java.util.logging.Level.SEVERE,"End status failed",e);}
                    },20);
                }
                default -> throw new IllegalArgumentException("Unknown test command");
            }
        } catch (Exception error) { sender.sendMessage("STRUCTURES_PROBE error: " + error); getLogger().log(java.util.logging.Level.WARNING, "Probe failed", error); }
        return true;
    }

    private void smoke(CommandSender sender, World world, String id) {
        var level = ((CraftWorld) world).getHandle();
        var registry = level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.STRUCTURE);
        var key = net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.STRUCTURE, net.minecraft.resources.Identifier.parse(id));
        var target = registry.getOrThrow(key);
        var found = level.getChunkSource().getGenerator().findNearestMapStructure(level, net.minecraft.core.HolderSet.direct(target), net.minecraft.core.BlockPos.ZERO, 100, false);
        if (found == null) throw new IllegalStateException("No natural placement for " + id);
        var point = found.getFirst();
        world.getChunkAtAsync(point.getX() >> 4, point.getZ() >> 4).thenAccept(chunk -> Bukkit.getScheduler().runTask(this, () -> {
            try {
                var starts = level.structureManager().startsForStructure(chunk.getX(), chunk.getZ(), target.value());
                var start = starts.stream().filter(s -> s.isValid()).findFirst().orElseThrow();
                var box = start.getBoundingBox();
                var futures = new ArrayList<java.util.concurrent.CompletableFuture<org.bukkit.Chunk>>();
                for (int x=box.minX()>>4;x<=box.maxX()>>4;x++) for (int z=box.minZ()>>4;z<=box.maxZ()>>4;z++)
                    futures.add(world.getChunkAtAsync(x,z));
                java.util.concurrent.CompletableFuture.allOf(futures.toArray(java.util.concurrent.CompletableFuture[]::new))
                        .thenRun(() -> Bukkit.getScheduler().runTask(this, () -> {
                            try {
                                Map<String,Long> blocks = new TreeMap<>();
                                var pos = new net.minecraft.core.BlockPos.MutableBlockPos();
                                for (int x=box.minX();x<=box.maxX();x++) for (int z=box.minZ();z<=box.maxZ();z++)
                                    for (int y=Math.max(world.getMinHeight(),box.minY());y<=Math.min(world.getMaxHeight()-1,box.maxY());y++) {
                                        var state=level.getBlockState(pos.set(x,y,z));
                                        String block=net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                                        if (!block.startsWith("minecraft:")) throw new IllegalStateException("Non-vanilla block " + block);
                                        blocks.merge(block,1L,Long::sum);
                                    }
                                var entities = new ArrayList<Map<String,Object>>();
                                for (var future : futures) for (var tile : level.getChunk(future.join().getX(),future.join().getZ()).getBlockEntities().values())
                                    if (box.isInside(tile.getBlockPos())) {
                                        var tag=tile.saveWithFullMetadata(level.registryAccess());
                                        entities.add(Map.of("id",tag.getStringOr("id",""),"loot",tag.getStringOr("LootTable","")));
                                    }
                                world.save();
                                report(id,Map.of("structure",id,"pieces",start.getPieces().size(),"bounds",List.of(box.minX(),box.minY(),box.minZ(),box.maxX(),box.maxY(),box.maxZ()),
                                        "blockCounts",blocks,"blockEntities",entities,"build",Bukkit.getVersion()));
                                sender.sendMessage("STRUCTURES_PROBE PASS "+id+" pieces="+start.getPieces().size()+" tiles="+entities.size());
                            } catch(Exception error) { getLogger().log(java.util.logging.Level.SEVERE,"STRUCTURES_PROBE FAIL "+id,error); }
                        }));
            } catch(Exception error) { getLogger().log(java.util.logging.Level.SEVERE,"STRUCTURES_PROBE FAIL "+id,error); }
        }));
    }

    private void endCycle(CommandSender sender, World world) throws Exception {
        var level=((CraftWorld)world).getHandle();var fight=level.getDragonFight();
        if (fight==null||!fight.getClass().getName().endsWith("NativeEndFight")) throw new IllegalArgumentException("Not a ported test End");
        fight.skipArenaLoadedCheck();
        fight.getClass().getMethod("reset",boolean.class).invoke(fight,false);
        fight.getClass().getMethod("doInitialDragonSpawn").invoke(fight);
        runEndStages(sender,world,"end-cycle");
    }
    private void runEndStages(CommandSender sender,World world,String reportName) throws Exception {
        var level=((CraftWorld)world).getHandle();var fight=level.getDragonFight();
        var timer=net.minecraft.world.level.dimension.end.EnderDragonFight.class.getDeclaredField("respawnTime");timer.setAccessible(true);
        int ticks=0;
        while(ticks++<800) {
            Object stage=fight.getClass().getMethod("getDragonRespawnStage").invoke(fight);
            if(stage==null)break;
            var crystals=fight.respawnCrystals.stream().map(ref -> ref.getEntity(level,net.minecraft.world.entity.boss.enderdragon.EndCrystal.class)).filter(Objects::nonNull).toList();
            var method=stage.getClass().getMethod("tick",net.minecraft.server.level.ServerLevel.class,net.minecraft.world.level.dimension.end.EnderDragonFight.class,List.class,int.class);
            method.setAccessible(true);int t=timer.getInt(fight);timer.setInt(fight,t+1);method.invoke(stage,level,fight,crystals,t);fight.setDirty();
        }
        if(ticks>=800||level.getDragons().size()!=1)throw new IllegalStateException("Dragon cycle did not finish exactly once");
        if (fight.getSpikeCrystals().size()!=10 || fight.getSpikeCrystals().stream().anyMatch(c -> c.isPermanentlyInvulnerable() || c.getBeamTarget()!=null))
            throw new IllegalStateException("Spike crystals did not become vulnerable after summoning");
        world.save();report(reportName,Map.of("ticks",ticks,"dragons",level.getDragons().size(),"crystals",fight.getSpikeCrystals().size(),"build",Bukkit.getVersion()));
        sender.sendMessage("STRUCTURES_PROBE PASS "+reportName+" ticks="+ticks);
    }
    private void report(String id,Map<String,?> data) throws Exception {
        var directory=getDataFolder().toPath().resolve("reports");java.nio.file.Files.createDirectories(directory);
        java.nio.file.Files.writeString(directory.resolve(id.replace(':','_').replace('/','_')+".json"),new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(data));
    }

    private void fingerprint(CommandSender sender,World world,int x,int z,int radius,String name) {
        if(radius<0||radius>4)throw new IllegalArgumentException("Test radius must be 0..4 chunks");
        world.setGameRule(GameRule.RANDOM_TICK_SPEED,0);
        var futures=new ArrayList<java.util.concurrent.CompletableFuture<Chunk>>();
        java.util.concurrent.CompletableFuture<Void> chain=java.util.concurrent.CompletableFuture.completedFuture(null);
        for(int cx=x-radius;cx<=x+radius;cx++)for(int cz=z-radius;cz<=z+radius;cz++) {
            int chunkX=cx,chunkZ=cz;
            var future=chain.thenCompose(ignored->world.getChunkAtAsync(chunkX,chunkZ));
            futures.add(future);chain=future.thenAccept(ignored->{});
        }
        java.util.concurrent.CompletableFuture.allOf(futures.toArray(java.util.concurrent.CompletableFuture[]::new)).thenRun(()->Bukkit.getScheduler().runTask(this,()->{
            try {
                var digest=java.security.MessageDigest.getInstance("SHA-256");
                var biomes=java.security.MessageDigest.getInstance("SHA-256");
                var states=new IdentityHashMap<net.minecraft.world.level.block.state.BlockState,byte[]>();
                var level=((CraftWorld)world).getHandle();var starts=new TreeMap<String,Integer>();
                var registry=level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.STRUCTURE);
                long blocks=0;
                for(var future:futures){var chunk=future.join();var nativeChunk=level.getChunk(chunk.getX(),chunk.getZ());
                    for(int y=world.getMinHeight();y<world.getMaxHeight();y++)for(int dx=0;dx<16;dx++)for(int dz=0;dz<16;dz++){
                        var pos=new net.minecraft.core.BlockPos(chunk.getX()*16+dx,y,chunk.getZ()*16+dz);
                        digest.update(states.computeIfAbsent(nativeChunk.getBlockState(pos),state->(state.toString()+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8)));blocks++;
                        if((dx&3)==0&&(dz&3)==0&&(y&3)==0)biomes.update((level.getBiome(pos).unwrapKey().orElseThrow().identifier()+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }
                    nativeChunk.getAllStarts().forEach((type,start)->{if(start.isValid())starts.put(chunk.getX()+","+chunk.getZ()+":"+registry.getKey(type),start.getPieces().size());});
                }
                world.save();report(name,Map.of("blocks",blocks,"blocksSha256",HexFormat.of().formatHex(digest.digest()),"biomesSha256",HexFormat.of().formatHex(biomes.digest()),"starts",starts,"build",Bukkit.getVersion()));
                sender.sendMessage("STRUCTURES_PROBE PASS fingerprint "+name+" blocks="+blocks);
            }catch(Exception e){getLogger().log(java.util.logging.Level.SEVERE,"Fingerprint failed",e);}
        }));
    }
}
