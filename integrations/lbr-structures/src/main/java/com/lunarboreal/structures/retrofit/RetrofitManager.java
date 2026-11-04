package com.lunarboreal.structures.retrofit;

import com.lunarboreal.api.LC;
import com.lunarboreal.api.service.LandService;
import com.lunarboreal.structures.config.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.bukkit.*;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.event.world.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Serialized background replay with main-thread world access and a write-ahead rollback journal. */
public final class RetrofitManager implements Listener, AutoCloseable {
    private static final NamespacedKey ENTITY_JOB = new NamespacedKey("lunarcorestructures", "retrofit_job");
    private final JavaPlugin plugin;
    private final StructuresConfig active;
    private final Path backups;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> { var t = new Thread(r, "LC-structure-replay"); t.setDaemon(true); return t; });
    private final ConcurrentHashMap<UUID, Job> jobs = new ConcurrentHashMap<>();
    private final Set<UUID> maintenance = ConcurrentHashMap.newKeySet();
    private final List<JobJournal> history;
    private final AtomicBoolean closing = new AtomicBoolean();

    public RetrofitManager(JavaPlugin plugin, StructuresConfig active) throws java.io.IOException {
        this.plugin = plugin;
        this.active = active;
        backups = plugin.getDataFolder().toPath().resolve("structure-backups");
        history = new CopyOnWriteArrayList<>(JobJournal.load(backups));
    }

    private static final class Job {
        final UUID world;
        final String key, name;
        final boolean mustRecover;
        final AtomicBoolean cancel = new AtomicBoolean();
        volatile String phase = "queued", error = "";
        volatile long scanned, candidates, completed, skipped;
        final ConcurrentLinkedDeque<Map<String, Object>> details = new ConcurrentLinkedDeque<>();
        Job(World world, boolean mustRecover) { this.world = world.getUID(); this.key = world.getKey().toString(); this.name = world.getName(); this.mustRecover = mustRecover; }
        void detail(String candidate, String reason) {
            details.addLast(Map.of("candidate", candidate, "reason", reason));
            while (details.size() > 200) details.pollFirst();
        }
        Map<String, Object> map() { return Map.of("id", world.toString(), "world", key, "name", name, "phase", phase,
                "scanned", scanned, "candidates", candidates, "completed", completed, "skipped", skipped, "error", error,
                "details", List.copyOf(details)); }
    }

    public List<Map<String, Object>> status() { return jobs.values().stream().sorted(Comparator.comparing(j -> j.key)).map(Job::map).toList(); }
    public boolean busy() { return !maintenance.isEmpty(); }
    public void cancel(String id) {
        Job job = jobs.get(UUID.fromString(id));
        if (job == null) throw new IllegalArgumentException("找不到结构处理任务");
        job.cancel.set(true);
        synchronized (job) {
            if (job.phase.equals("queued") && !job.mustRecover) { job.phase = "cancelled"; maintenance.remove(job.world); }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void initializing(WorldInitEvent event) {
        World world = event.getWorld();
        if (StagingWorlds.internal(world.getKey().toString())) return;
        var policy = active.forWorld(world.getKey().toString());
        boolean recovery = history.stream().anyMatch(j -> j.world().equals(world.getUID().toString()) && needsRecovery(j));
        if (recovery || policy.overwriteExisting() && policy.anyEnabled()) {
            var job = new Job(world, recovery);
            jobs.put(world.getUID(), job);
            if (recovery || !restoreCompletedReport(job, policy)) maintenance.add(world.getUID());
        }
    }

    @EventHandler public void loaded(WorldLoadEvent event) {
        Job job = jobs.get(event.getWorld().getUID());
        if (job != null && job.phase.equals("queued") && !closing.get()) worker.execute(() -> run(job, event.getWorld()));
    }

    private boolean restoreCompletedReport(Job job, GenerationPolicy policy) {
        Path file = backups.resolve("report-" + job.world + ".json");
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 1024 * 1024) return false;
            var report = StructuresConfig.object(new com.google.gson.Gson().fromJson(Files.readString(file), Object.class));
            if (!job.world.toString().equals(report.get("id")) || !job.key.equals(report.get("world"))
                    || StructuresConfig.integer(report.get("revision")) != active.revision()
                    || !GenerationPolicy.parse(StructuresConfig.object(report.get("policy"))).equals(policy)
                    || !Set.of("completed", "cancelled").contains(report.get("phase"))) return false;
            job.scanned = StructuresConfig.integer(report.get("scanned"));
            job.candidates = StructuresConfig.integer(report.get("candidates"));
            job.completed = StructuresConfig.integer(report.get("completed"));
            job.skipped = StructuresConfig.integer(report.get("skipped"));
            if (report.get("details") instanceof List<?> details)
                for (Object detail : details) {
                    var row = StructuresConfig.object(detail);
                    if (row.get("candidate") instanceof String candidate && row.get("reason") instanceof String reason) job.detail(candidate, reason);
                }
            job.phase = (String) report.get("phase");
            return true;
        } catch (Exception invalidReport) { return false; } // Reports are advisory; recovery journals remain authoritative.
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void login(PlayerLoginEvent event) {
        if (maintenance.contains(event.getPlayer().getWorld().getUID()) || StagingWorlds.internal(event.getPlayer().getWorld().getKey().toString()))
            event.disallow(PlayerLoginEvent.Result.KICK_OTHER, "世界正在安全更新结构，请稍后重试 / World structure maintenance in progress");
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void preLogin(AsyncPlayerPreLoginEvent event) {
        // Login's final saved dimension is selected after PlayerLoginEvent. Keep new
        // sessions out until recovery finishes rather than guessing their destination.
        if (!maintenance.isEmpty()) event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                "服务器正在安全更新结构，请稍后重试 / Structure maintenance in progress");
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void respawn(PlayerRespawnEvent event) {
        if (!maintenance.contains(event.getRespawnLocation().getWorld().getUID()) && !StagingWorlds.internal(event.getRespawnLocation().getWorld().getKey().toString())) return;
        World safe = Bukkit.getWorlds().stream().filter(w -> !maintenance.contains(w.getUID()) && !StagingWorlds.internal(w.getKey().toString())).findFirst().orElse(null);
        if (safe != null) event.setRespawnLocation(safe.getSpawnLocation());
        else event.getPlayer().kick(net.kyori.adventure.text.Component.text("Structure maintenance in progress"));
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void teleport(PlayerTeleportEvent event) {
        if (event.getTo() != null && (maintenance.contains(event.getTo().getWorld().getUID()) || StagingWorlds.internal(event.getTo().getWorld().getKey().toString()))) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("目标世界正在安全更新结构，请稍后重试 / World structure maintenance in progress");
        }
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void unloading(WorldUnloadEvent event) { if (maintenance.contains(event.getWorld().getUID())) event.setCancelled(true); }

    private static boolean needsRecovery(JobJournal journal) {
        return Set.of("APPLYING", "ROLLING_BACK", "RECOVERY_REQUIRED").contains(journal.status());
    }

    private void run(Job job, World world) {
        synchronized (job) { if (job.phase.equals("cancelled") && !job.mustRecover) return; job.phase = "recovering"; }
        try {
            for (var journal : history) {
                if (journal.world().equals(job.world.toString()) && needsRecovery(journal)) recover(job, world, journal);
            }
            var policy = active.forWorld(job.key);
            if (!policy.overwriteExisting() || !policy.anyEnabled() || job.cancel.get()) return;
            sync(() -> { if (!world.getPlayers().isEmpty()) throw new IllegalStateException("世界仍有玩家，拒绝覆盖"); world.save(); return null; });
            job.phase = "scanning";
            Path region = sync(() -> dimensionPath(world).resolve("region"));
            var candidates = RegionScanner.scan(region, world.getUID(), policy, () -> job.cancel.get() || closing.get(), p -> {
                job.scanned = p.chunks(); job.candidates = p.candidates();
            });
            var completed = new HashSet<String>();
            history.stream().filter(j -> j.status().equals("COMMITTED")).forEach(j -> completed.add(j.candidate()));
            for (var candidate : candidates) {
                if (job.cancel.get() || closing.get()) break;
                if (completed.contains(candidate.id())) continue;
                try { replace(job, world, candidate); job.completed++; }
                catch (Skip skip) { job.skipped++; job.detail(candidate.id(), skip.getMessage()); }
            }
            if (policy.enabled(StructureFamily.END_ISLAND) && sync(() -> {
                var fight = ((CraftWorld) world).getHandle().getDragonFight();
                return fight != null && !(fight instanceof com.lunarboreal.structures.end.NativeEndFight);
            })) {
                job.candidates++;
                job.skipped++;
                job.detail("end_island", "末地岛需验证完整龙战历史；已有岛屿未自动覆盖");
            }
            job.phase = job.cancel.get() ? "cancelled" : "completed";
        } catch (Throwable failure) {
            job.error = message(failure);
            if (!job.phase.equals("recovery_required")) job.phase = "failed";
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Structure retrofit failed for " + job.key, failure);
        } finally {
            if (!job.phase.equals("recovery_required")) maintenance.remove(job.world);
            if (job.phase.equals("recovering")) job.phase = job.cancel.get() ? "cancelled" : "completed";
            try {
                var report = new LinkedHashMap<String, Object>(job.map());
                report.put("revision", active.revision());
                report.put("policy", active.forWorld(job.key).map());
                var json = new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(report);
                ConfigStore.atomicWrite(backups.resolve("report-" + job.world + ".json"), json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch (Exception e) { plugin.getLogger().warning("Cannot save retrofit report: " + e.getMessage()); }
        }
    }

    private void replace(Job job, World world, RetrofitCandidate candidate) throws Exception {
        StagingWorlds.Pair pair = null;
        JobJournal journal = null;
        var pinned = new ArrayList<ChunkPos>();
        try {
            job.phase = "replaying";
            try { pair = sync(() -> StagingWorlds.create(world, candidate)); }
            catch (Exception e) { throw new Skip("无法重放生成来源：" + message(e)); }
            var bounds = BoundingBox.encapsulatingBoxes(List.of(candidate.bounds(), pair.replacementStart().getBoundingBox())).orElseThrow();
            var chunks = chunks(bounds, world);
            var scene = pair;
            for (ChunkPos pos : chunks) {
                if (job.cancel.get()) throw new Skip("任务已取消");
                if (!sync(() -> world.isChunkGenerated(pos.x(), pos.z()))) throw new Skip("扩展范围包含尚未生成的区块");
                load(scene.baseline(), pos, true); load(scene.replacement(), pos, true); load(world, pos, false);
                sync(() -> { world.addPluginChunkTicket(pos.x(), pos.z(), plugin); return null; });
                pinned.add(pos);
            }
            job.phase = "checking";
            sync(() -> { requireProtectedAreaClear(world, bounds); return null; });
            var before = new ArrayList<ChunkImage>();
            var after = new ArrayList<ChunkImage>();
            for (ChunkPos pos : chunks) {
                ChunkImage current = sync(() -> capture(world, pos));
                ChunkImage expected = sync(() -> capture(scene.baseline(), pos));
                String mismatch = current.difference(expected, bounds);
                if (mismatch != null) throw new Skip("与原版重放不一致，已保留原样；" + mismatch);
                before.add(current);
                after.add(sync(() -> capture(scene.replacement(), pos)));
            }
            if (candidate.dungeonOrigin() != null) {
                var origin = candidate.dungeonOrigin();
                var room = before.stream().filter(i -> i.position.equals(candidate.start())).findFirst().orElseThrow();
                if (!room.block(origin.getX(), origin.getY(), origin.getZ()).is(net.minecraft.world.level.block.Blocks.SPAWNER))
                    throw new Skip("未找到可验证的原版小地牢");
            }
            var entities = sync(() -> captureEntities(scene.replacement(), pairBounds(scene), bounds));
            String replacementId = sync(() -> ((CraftWorld) world).getHandle().registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.STRUCTURE)
                    .getKey(scene.replacementStart().getStructure()).toString());
            Set<String> replaced = Set.of(candidate.vanillaId(), replacementId);
            if (job.cancel.get()) throw new Skip("任务已取消");
            job.phase = "backing_up";
            journal = JobJournal.prepare(backups, world.getUID(), job.key, active.revision(), candidate, bounds, replaced, before, after, entities);
            history.add(journal);
            // Recheck after IO; an administrator or another plugin may have changed the world while backing up.
            sync(() -> { requireProtectedAreaClear(world, bounds); return null; });
            for (int i = 0; i < before.size(); i++) {
                ChunkImage original = before.get(i);
                if (!original.sameContents(sync(() -> capture(world, original.position)), bounds)) throw new Skip("备份期间世界发生变化，已取消覆盖");
            }
            if (job.cancel.get()) throw new Skip("任务已取消");
            journal.status("APPLYING");
            job.phase = "applying";
            for (var image : after) sync(() -> image.apply(((CraftWorld) world).getHandle(), bounds, replaced));
            var operation = journal;
            sync(() -> { spawnEntities(world, entities, operation.id); world.save(); return null; });
            journal.status("COMMITTED");
            try { LC.get().audit().log("structures", "system", "retrofit", job.key + " " + candidate.id() + " backup=" + journal.id); }
            catch (Exception auditError) { plugin.getLogger().warning("Committed retrofit " + journal.id + "; audit log unavailable: " + auditError.getMessage()); }
        } catch (Exception failure) {
            if (journal != null && needsRecovery(journal)) {
                try { recover(job, world, journal); }
                catch (Exception rollback) { failure.addSuppressed(rollback); job.phase = "recovery_required"; throw failure; }
            }
            throw failure;
        } finally {
            if (!closing.get()) sync(() -> { pinned.forEach(pos -> world.removePluginChunkTicket(pos.x(), pos.z(), plugin)); return null; });
            if (pair != null && !closing.get()) {
                var cleanup = pair;
                sync(() -> { StagingWorlds.unload(cleanup); return null; });
                StagingWorlds.delete(cleanup);
            }
        }
    }

    private void recover(Job job, World world, JobJournal journal) throws Exception {
        job.phase = "recovering";
        try {
            List<CompoundTag> images = journal.beforeImages(); // Verify every checksum before restoring any chunk.
            BoundingBox bounds = journal.bounds();
            Set<String> structureIds = journal.structureIds();
            journal.status("ROLLING_BACK");
            for (var image : images) {
                var pos = new ChunkPos(image.getIntOr("xPos", Integer.MIN_VALUE), image.getIntOr("zPos", Integer.MIN_VALUE));
                load(world, pos, false);
                sync(() -> {
                    for (var entity : world.getChunkAt(pos.x(), pos.z()).getEntities())
                        if (journal.id.equals(entity.getPersistentDataContainer().get(ENTITY_JOB, PersistentDataType.STRING))) {
                            entity.getPersistentDataContainer().set(new NamespacedKey("lunarcorestructures", "rollback_removal"), PersistentDataType.BYTE, (byte) 1);
                            entity.remove();
                        }
                    new ChunkImage(((CraftWorld) world).getHandle(), image).apply(((CraftWorld) world).getHandle(), bounds, structureIds);
                    return null;
                });
            }
            sync(() -> { world.save(); return null; });
            journal.status("ROLLED_BACK");
        } catch (Exception e) {
            job.phase = "recovery_required";
            try { journal.status("RECOVERY_REQUIRED"); } catch (Exception stateError) { e.addSuppressed(stateError); }
            throw e;
        }
    }

    private static BoundingBox pairBounds(StagingWorlds.Pair pair) { return pair.replacementStart().getBoundingBox(); }
    private static List<CompoundTag> captureEntities(World staging, BoundingBox structure, BoundingBox operation) {
        var level = ((CraftWorld) staging).getHandle();
        var result = new ArrayList<CompoundTag>();
        for (var entity : staging.getNearbyEntities(box(structure))) {
            if (entity instanceof org.bukkit.entity.Player) throw new IllegalStateException("Player entered staging world");
            var output = net.minecraft.world.level.storage.TagValueOutput.createWithContext(net.minecraft.util.ProblemReporter.DISCARDING, level.registryAccess());
            if (((CraftEntity) entity).getHandle().save(output)) {
                var tag = output.buildResult(); tag.remove("UUID"); result.add(tag);
            }
        }
        return List.copyOf(result);
    }
    private static void spawnEntities(World world, List<CompoundTag> tags, String operation) {
        var level = ((CraftWorld) world).getHandle();
        for (var original : tags) {
            var tag = original.copy();
            markEntityTree(tag, operation);
            var input = net.minecraft.world.level.storage.TagValueInput.create(net.minecraft.util.ProblemReporter.DISCARDING, level.registryAccess(), tag);
            var entity = net.minecraft.world.entity.EntityType.loadEntityRecursive(input, level, net.minecraft.world.entity.EntitySpawnReason.STRUCTURE,
                    net.minecraft.world.entity.EntityProcessor.NOP);
            if (entity == null || !level.tryAddFreshEntityWithPassengers(entity)) throw new IllegalStateException("Cannot restore generated structure entity");
        }
    }

    static void markEntityTree(CompoundTag tag, String operation) {
        tag.remove("UUID");
        var pdc = tag.getCompoundOrEmpty("BukkitValues").copy();
        pdc.putString(ENTITY_JOB.toString(), operation);
        tag.put("BukkitValues", pdc);
        for (var passenger : tag.getListOrEmpty("Passengers"))
            if (passenger instanceof CompoundTag child) markEntityTree(child, operation);
    }

    private static void requireProtectedAreaClear(World world, BoundingBox bounds) throws Skip {
        if (!world.getPlayers().isEmpty()) throw new Skip("世界仍有玩家");
        var land = LC.get().services().get(LandService.class);
        if (land.isPresent()) {
            var claims = land.get().intersecting(world.getName(), bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(), bounds.maxZ());
            if (claims.isEmpty()) throw new Skip("领地保护尚未就绪或不支持范围查询");
            if (!claims.get().isEmpty()) throw new Skip("结构或扩展范围与领地相交");
        } else if (LC.get().modules().discovered().stream().anyMatch(d -> d.id().equals("land"))) {
            throw new Skip("领地模块已安装但保护数据不可用");
        }
        if (!world.getNearbyEntities(box(bounds)).isEmpty()) throw new Skip("范围内存在实体，无法确认未被玩家改动");
    }
    private static org.bukkit.util.BoundingBox box(BoundingBox b) {
        return new org.bukkit.util.BoundingBox(b.minX(), b.minY(), b.minZ(), b.maxX()+1d, b.maxY()+1d, b.maxZ()+1d);
    }
    private static List<ChunkPos> chunks(BoundingBox bounds, World world) throws Skip {
        long count = ((long)(bounds.maxX() >> 4) - (bounds.minX() >> 4) + 1) * ((long)(bounds.maxZ() >> 4) - (bounds.minZ() >> 4) + 1);
        if (count <= 0 || count > 256 || bounds.minY() < world.getMinHeight() || bounds.maxY() >= world.getMaxHeight())
            throw new Skip("结构超出单次安全处理范围或世界高度");
        var result = new ArrayList<ChunkPos>();
        for (int x = bounds.minX() >> 4; x <= bounds.maxX() >> 4; x++) for (int z = bounds.minZ() >> 4; z <= bounds.maxZ() >> 4; z++) result.add(new ChunkPos(x,z));
        return result;
    }
    private static Path dimensionPath(World world) {
        var level = ((CraftWorld) world).getHandle();
        return level.getServer().storageSource.getDimensionPath(level.dimension());
    }
    private static ChunkImage capture(World world, ChunkPos pos) {
        var level = ((CraftWorld) world).getHandle();
        return ChunkImage.capture(level, level.getChunk(pos.x(), pos.z()));
    }
    private void load(World world, ChunkPos pos, boolean generate) throws Exception {
        var future = sync(() -> world.getChunkAtAsync(pos.x(), pos.z(), generate));
        if (future.get() == null) throw new Skip("所需区块不存在");
    }
    private <T> T sync(Callable<T> task) throws Exception {
        if (closing.get()) throw new InterruptedException("Structure plugin is stopping");
        try { return Bukkit.getScheduler().callSyncMethod(plugin, task).get(); }
        catch (ExecutionException e) {
            if (e.getCause() instanceof Exception cause) throw cause;
            throw e;
        }
    }
    private static String message(Throwable t) {
        while (t.getCause() != null && (t instanceof ExecutionException || t instanceof CompletionException)) t = t.getCause();
        return Objects.toString(t.getMessage(), t.getClass().getSimpleName());
    }
    private static final class Skip extends Exception { Skip(String reason) { super(reason); } }

    @Override public void close() {
        closing.set(true);
        jobs.values().forEach(j -> j.cancel.set(true));
        worker.shutdownNow();
        if (jobs.values().stream().anyMatch(j -> j.phase.equals("applying") || j.phase.equals("recovery_required"))) Bukkit.shutdown();
    }
}
