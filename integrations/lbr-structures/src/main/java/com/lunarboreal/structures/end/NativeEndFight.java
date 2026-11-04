// Paper subclass port of YUNG Better End Island EnderDragonFightMixin (LGPL-3.0).
// Original source: third-party/yungs/end-island/upstream/
package com.lunarboreal.structures.end;

import com.google.common.collect.ContiguousSet;
import com.google.common.collect.DiscreteDomain;
import com.google.common.collect.Range;
import com.yungnickyoung.minecraft.betterendisland.BetterEndIslandCommon;
import com.yungnickyoung.minecraft.betterendisland.world.feature.BetterSpikeFeature;
import com.yungnickyoung.minecraft.betterendisland.world.BetterDragonRespawnStage;
import com.yungnickyoung.minecraft.betterendisland.world.IBetterDragonFight;
import com.yungnickyoung.minecraft.betterendisland.world.IEndSpike;
import com.yungnickyoung.minecraft.betterendisland.world.feature.BetterEndPodiumFeature;
import com.yungnickyoung.minecraft.betterendisland.world.feature.BetterEndSpawnPlatformFeature;
import com.yungnickyoung.minecraft.betterendisland.world.util.EndCrystalUtils;
import com.yungnickyoung.minecraft.betterendisland.world.util.EndSpikeUtils;
import com.yungnickyoung.minecraft.betterendisland.world.util.ExitPortalUtils;
import com.yungnickyoung.minecraft.betterendisland.world.util.WorldgenUtils;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Unit;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.pattern.BlockPattern;
import net.minecraft.world.level.dimension.end.DragonRespawnStage;
import net.minecraft.world.level.dimension.end.EnderDragonFight;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.EndPlatformFeature;
import net.minecraft.world.level.levelgen.feature.EndPodiumFeature;
import net.minecraft.world.level.levelgen.feature.EndSpikeFeature;
import net.minecraft.world.level.saveddata.SavedData;
import org.jspecify.annotations.Nullable;

import java.util.*;

public final class NativeEndFight extends EnderDragonFight implements IBetterDragonFight {
    private static final int MINIMUM_PORTAL_Y = 5;
    private static final int FALLBACK_PORTAL_Y = 65;






    private BetterDragonRespawnStage bei$dragonRespawnStage;
    private boolean bei$isFirstExitPortalSpawn = true;
    private boolean bei$hasDragonEverSpawned;
    private int bei$numTimesDragonKilled = 0;

    /**
     * Set up our state.
     */

    /**
     * Don't have the BossEvent be active from the start.
     */

    // Mostly vanilla logic.

    @Override public void tick() {
        if (!bei$hasDragonEverSpawned && bei$dragonRespawnStage == null && level.getGameTime() % 5 == 0) {
            for (var player : level.players()) {
                if (!player.isSpectator() && player.position().x() * player.position().x() + player.position().z() * player.position().z() < 625) {
                    doInitialDragonSpawn();
                    break;
                }
            }
        }
        tickBellSound();
        saveExtra();
        // As vanilla, except with the possibility that the dragon has not yet spawned.
        this.dragonEvent.setVisible(!read_dragonKilled() && this.bei$hasDragonEverSpawned);
        if (increment_ticksSinceLastPlayerScan() >= TIME_BETWEEN_PLAYER_SCANS) {
            this.updatePlayers();
            write_ticksSinceLastPlayerScan(0);
        }

        if (!this.dragonEvent.getPlayers().isEmpty()) {
            this.level.getChunkSource().addTicketWithRadius(TicketType.DRAGON, new ChunkPos(0, 0), ARENA_TICKET_LEVEL);
            boolean isArenaLoaded = this.isArenaLoaded();

            // Vanilla exits early here but we continue

            // Initial state scanning.
            // Only performed once, when the dimension is first loaded.
            // As vanilla, except running scanForInitialState rather than scanState.
            if (read_needsStateScanning() && isArenaLoaded) {
                this.scanForInitialState();
                write_needsStateScanning(false);
                this.setDirty();
            }

            // Update respawn stage if performing respawn
            // As vanilla, except using bei$dragonRespawnStage instead of respawnStage.
            if (this.bei$dragonRespawnStage != null) {
                List<EndCrystal> respawnCrystals = this.respawnCrystals.stream()
                        .map((e) -> e.getEntity(this.level, EndCrystal.class))
                        .filter(Objects::nonNull)
                        .toList();
                if (respawnCrystals.isEmpty() && isArenaLoaded) {
                    this.bei$dragonRespawnStage = null;
                    // Vanilla does abortRespawnSequence here.
                    this.tryRespawn();
                    return; //todo does it work fine without this return - I just added it now
                }

                this.bei$dragonRespawnStage.tick(this.level, this, respawnCrystals, next_respawnTime());
                this.setDirty();
            }

            if (!read_dragonKilled()) {
                // As vanilla, except with our arena loaded + has dragon ever spawned checks
                if ((read_dragonUUID() == null || increment_ticksSinceDragonSeen() >= MAX_TICKS_BEFORE_DRAGON_RESPAWN)
                        && isArenaLoaded && this.bei$hasDragonEverSpawned) {
                    this.findOrCreateDragon();
                    write_ticksSinceDragonSeen(0);
                }

                // As vanilla
                if (increment_ticksSinceCrystalsScanned() >= TIME_BETWEEN_CRYSTAL_SCANS && isArenaLoaded) {
                    this.updateCrystalCount();
                    write_ticksSinceCrystalsScanned(0);
                }
            }
        } else {
            // As vanilla
            this.level.getChunkSource().removeTicketWithRadius(TicketType.DRAGON, new ChunkPos(0, 0), ARENA_TICKET_LEVEL);
        }

    }

    @Override
    public void reset(boolean forcePortalPosReset) {
        // Kill dragon if exists
        List<? extends EnderDragon> dragons = this.level.getDragons();
        dragons.forEach(EnderDragon::discard);
        this.dragonEvent.setProgress(0);
        this.dragonEvent.setVisible(false);

        // Get portal pos
        if (this.exitPortalLocation == null || this.exitPortalLocation.getY() < MINIMUM_PORTAL_Y || forcePortalPosReset) {
            BetterEndIslandCommon.LOGGER.info("Tried to reset, but need to find the portal first.");
            if (this.exitPortalLocation == null) {
                BetterEndIslandCommon.LOGGER.info("Portal location is currently null.");
            } else if (this.exitPortalLocation.getY() < MINIMUM_PORTAL_Y) {
                BetterEndIslandCommon.LOGGER.info("Portal location is currently too low: {}", this.exitPortalLocation.getY());
            } else {
                BetterEndIslandCommon.LOGGER.info("Forcing portal position reset...");
            }
            this.findExitPortal();
            if (this.exitPortalLocation == null || this.exitPortalLocation.getY() < MINIMUM_PORTAL_Y || forcePortalPosReset) { // If still null after finding portal, we find it ourselves
                if (this.exitPortalLocation == null) {
                    BetterEndIslandCommon.LOGGER.info("Portal location is still null. Placing manually...");
                } else if (this.exitPortalLocation.getY() < MINIMUM_PORTAL_Y) {
                    BetterEndIslandCommon.LOGGER.info("Portal location is still too low: {}. Placing manually...", this.exitPortalLocation.getY());
                }
                this.exitPortalLocation = new BlockPos(0, WorldgenUtils.getSurfacePosAt(this.level, 0, 0), 0);
                while (this.level.getBlockState(this.exitPortalLocation).is(Blocks.BEDROCK) && this.exitPortalLocation.getY() > this.level.getSeaLevel()) {
                    this.exitPortalLocation = this.exitPortalLocation.below();
                }
                if (this.exitPortalLocation.getY() < MINIMUM_PORTAL_Y) {
                    BetterEndIslandCommon.LOGGER.info("Portal was still placed too low! Force placing at y={}...", FALLBACK_PORTAL_Y);
                    this.exitPortalLocation = new BlockPos(this.exitPortalLocation.getX(), FALLBACK_PORTAL_Y, this.exitPortalLocation.getZ());
                }
            }
        }

        // Reset vars to initial state
        write_dragonUUID(null);
        write_dragonKilled(false);
        this.hasPreviouslyKilledDragon = false;
        this.bei$isFirstExitPortalSpawn = false;
        this.bei$hasDragonEverSpawned = false;
        this.bei$numTimesDragonKilled = 0;
        this.bei$dragonRespawnStage = null;
        this.respawnStage = null;
        write_respawnTime(0);
        write_needsStateScanning(true);
        write_ticksSinceLastPlayerScan(0);
        write_ticksSinceDragonSeen(0);
        write_aliveCrystals(0);
        write_ticksSinceCrystalsScanned(0);

        // Get rid of summoning crystals
        this.respawnCrystals.stream()
                .map((e) -> e.getEntity(this.level, EndCrystal.class))
                .filter(Objects::nonNull)
                .forEach(EndCrystal::discard);
        this.respawnCrystals = List.of();
        List<EndCrystal> remainingSummoningCrystals = EndCrystalUtils.checkForBEIRespawnCrystals(this.level, this.exitPortalLocation.above(1));
        remainingSummoningCrystals.forEach(EndCrystal::discard);
        remainingSummoningCrystals = EndCrystalUtils.checkForVanillaRespawnCrystals(this.level, this, this.exitPortalLocation);
        remainingSummoningCrystals.forEach(EndCrystal::discard);

        // Get rid of spike crystals
        List<EndSpikeFeature.EndSpike> allSpikes = BetterSpikeFeature.getSpikesForLevel(this.level);
        for (EndSpikeFeature.EndSpike spike : allSpikes) {
            for (EndCrystal crystal : this.level.getEntitiesOfClass(EndCrystal.class, spike.getTopBoundingBox())) {
                crystal.discard();
            }
        }

        // Reset tower to initial state w/ summoning crystals
        if (BetterEndIslandCommon.CONFIG.spawnCentralTowerInitially) {
            // Spawn the central tower
            BetterEndPodiumFeature endPodiumFeature = new BetterEndPodiumFeature(true, false, false);
            BlockPos spawnPos = this.exitPortalLocation.below(MINIMUM_PORTAL_Y);
            endPodiumFeature.place(this.level, this.level.getChunkSource().getGenerator(), RandomSource.create(), spawnPos);
        } else {
            // Spawn the vanilla podium
            EndPodiumFeature endPodiumFeature = new EndPodiumFeature(false);
            if (endPodiumFeature.place(this.level, this.level.getChunkSource().getGenerator(), RandomSource.create(), this.exitPortalLocation)) {
                int $$2 = Mth.positiveCeilDiv(4, 16);
                this.level.getChunkSource().chunkMap.waitForLightBeforeSending(ChunkPos.containing(this.exitPortalLocation), $$2);
            }
        }

        // Get rid of vanilla spikes in case they're there
        EndSpikeUtils.removeVanillaPillars(this.level);

        // Reset spikes to initial state
        EndSpikeUtils.resetSpikes(this.level, allSpikes);

        // Reset spawn platform to initial state
        BlockPos platformPos = ServerLevel.END_SPAWN_POINT.below();
        if (BetterEndIslandCommon.CONFIG.useVanillaSpawnPlatform) {
            EndPlatformFeature.createEndPlatform(this.level, platformPos, false);
        } else {
            BetterEndSpawnPlatformFeature.place(this.level, platformPos, false);
        }

        // Remove all gateways
        for (int i = 0; i < GATEWAY_COUNT; i++) {
            int x = Mth.floor(GATEWAY_DISTANCE * Math.cos(2.0D * (-Math.PI + 0.15707963267948966D * (double) i)));
            int z = Mth.floor(GATEWAY_DISTANCE * Math.sin(2.0D * (-Math.PI + 0.15707963267948966D * (double) i)));
            BlockPos gatePos = new BlockPos(x, 75, z);
            BlockPos.betweenClosed(gatePos.offset(-1, -4, -1), gatePos.offset(1, 4, 1)).forEach(pos -> {
                this.level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            });
        }

        // Reset gateways var
        this.gateways.clear();
        this.gateways.addAll(ContiguousSet.create(Range.closedOpen(0, 20), DiscreteDomain.integers()));
        Util.shuffle(this.gateways, RandomSource.create(this.level.getSeed()));
    }

    // Much like vanilla's scanState.
    private void scanForInitialState() {
        // As vanilla, except using our own spawnPortal.
        BetterEndIslandCommon.LOGGER.info("Scanning for legacy world dragon fight...");
        boolean hasActiveExitPortal = this.hasActiveExitPortal();
        if (hasActiveExitPortal) {
            BetterEndIslandCommon.LOGGER.info("Found that the dragon has been killed in this world already.");
            this.hasPreviouslyKilledDragon = true;
        } else {
            BetterEndIslandCommon.LOGGER.info("Found that the dragon has not yet been killed in this world.");
            this.hasPreviouslyKilledDragon = false;
            if (this.findExitPortal() == null) {
                ExitPortalUtils.spawnPortal(this, this.level, false, false);
            }
        }

        // As vanilla
        List<? extends EnderDragon> dragons = this.level.getDragons();
        if (dragons.isEmpty()) {
            write_dragonKilled(true);
        } else {
            EnderDragon dragon = dragons.getFirst();
            write_dragonUUID(dragon.getUUID());
            BetterEndIslandCommon.LOGGER.info("Found that there's a dragon still alive ({})", dragon);
            write_dragonKilled(false);
            if (!hasActiveExitPortal) {
                BetterEndIslandCommon.LOGGER.info("But we didn't have a portal, so let's remove the dragon.");
                dragon.discard();
                write_dragonUUID(null);
            }
        }

        if (!this.hasPreviouslyKilledDragon && read_dragonKilled()) {
            write_dragonKilled(false);
        }

        this.setDirty();
    }

    @Override public void onCrystalDestroyed(EndCrystal crystal, DamageSource damageSource) {
        /*
         As vanilla, except checking the BetterDragonRespawnStage.
         Also the vanilla version of this if statement has 'this.respawnCrystals.contains(crystal)',
         which I think is a bug as that's always false.
        */
        if (this.bei$dragonRespawnStage != null && this.respawnCrystals.stream().anyMatch(ref -> ref.matches(crystal))) {
            // Vanilla has 'abortRespawnSequence' here.
            BetterEndIslandCommon.LOGGER.info("Aborting dragon respawn sequence");
            this.bei$dragonRespawnStage = null;
            write_respawnTime(0);
            this.resetSpikeCrystals();
        } else {
            // As vanilla
            this.updateCrystalCount();
            Entity dragonEntity = this.level.getEntity(read_dragonUUID());
            if (dragonEntity instanceof EnderDragon enderDragon) {
                enderDragon.onCrystalDestroyed(this.level, crystal, crystal.blockPosition(), damageSource);
            }
        }

    }

    /**
     * Replacement of vanilla logic.
     */

    @Override public boolean tryRespawn() {
        if (read_dragonKilled() && this.bei$dragonRespawnStage == null) {
            this.spawnDragon(false);
        }

            return this.bei$dragonRespawnStage != null;
    }

    /**
     * Same as tryRespawn, but triggered by the player getting close enough.
     */
    @Override
    public void doInitialDragonSpawn() {
        BetterEndIslandCommon.LOGGER.info("Starting initial dragon fight!");
        this.spawnDragon(true);
    }

    /**
     * Some of this is as vanilla 'tryRespawn'.
     */
    private void spawnDragon(boolean isInitialSpawn) {
        // As vanilla 'tryRespawn', but using our 'spawnPortal' instead of vanilla 'spawnExitPortal'
        BlockPos portalPos = this.exitPortalLocation;
        if (portalPos == null) {
            BetterEndIslandCommon.LOGGER.info("Tried to respawn, but need to find the portal first.");
            BlockPattern.BlockPatternMatch portalPatternMatch = this.findExitPortal();
            if (portalPatternMatch == null) {
                BetterEndIslandCommon.LOGGER.info("Couldn't find a portal, so we made one.");
                ExitPortalUtils.spawnPortal(this, this.level, false, false);
                ExitPortalUtils.spawnPortal(this, this.level, true, true); // Place open, active bottom after spawning tower
            } else {
                BetterEndIslandCommon.LOGGER.info("Found the exit portal & saved its location for next time.");
            }

            portalPos = this.exitPortalLocation;
        }

        // Check for all 4 summoning crystals
        List<EndCrystal> allCrystals = EndCrystalUtils.checkForBEIRespawnCrystals(this.level, portalPos.above(1));
        if (allCrystals.size() != 4) {
            allCrystals = EndCrystalUtils.checkForVanillaRespawnCrystals(this.level, this, portalPos);
            if (allCrystals.size() != 4) {
                if (isInitialSpawn) {
                    BetterEndIslandCommon.LOGGER.warn("Unable to find all 4 summoning crystals. This shouldn't happen!");
                }
                return;
            }
        }

        if (isInitialSpawn) {
            BetterEndIslandCommon.LOGGER.info("Found all crystals, starting initial dragon spawn.");
        } else {
            BetterEndIslandCommon.LOGGER.info("Found all crystals, respawning dragon.");
        }
        this.respawnDragon(allCrystals);
    }

    /**
     * Replacement of vanilla logic. Vanilla builds the portal here, we don't need to.
     */

    @Override public boolean respawnDragon(List<EndCrystal> crystals) {
        if ((read_dragonKilled() || !this.bei$hasDragonEverSpawned) && this.bei$dragonRespawnStage == null) {
            this.bei$dragonRespawnStage = BetterDragonRespawnStage.START;
            // As vanilla
            write_respawnTime(0);
            this.respawnCrystals = crystals.stream().map(EntityReference::of).toList();
            this.setDirty();
        }

            return this.bei$dragonRespawnStage != null;
    }

    @Override public void resetSpikeCrystals() {
        for (EndCrystal crystal : getSpikeCrystals()) {
            crystal.setPermanentlyInvulnerable(false);
            crystal.setBeamTarget(null);
        }
        // Reset beam targets for summoning crystals. This is necessary for BEI's crystals because unlike vanilla,
        // the crystals aren't close enough to destroy each other when one is destroyed.
        this.respawnCrystals.stream()
                .map((e) -> e.getEntity(this.level, EndCrystal.class))
                .filter(Objects::nonNull)
                .forEach(crystal -> {
                    crystal.setPermanentlyInvulnerable(false);
                    crystal.setBeamTarget(null);
                });
    }

    @Override public void setDragonKilled(EnderDragon dragon) {
        // As vanilla
        if (dragon.getUUID().equals(read_dragonUUID())) {
            this.dragonEvent.setProgress(0.0F);
            this.dragonEvent.setVisible(false);

            // Departure from vanilla
            // Special case - killing the dragon for the first time with initial tower disabled but tower on resummoning enabled
            if (this.bei$numTimesDragonKilled == 0
                    && !BetterEndIslandCommon.CONFIG.spawnCentralTowerInitially
                    && BetterEndIslandCommon.CONFIG.spawnCentralTowerOnResummon) {
                ExitPortalUtils.spawnPortal(this, this.level, false, false);
            }
            ExitPortalUtils.spawnPortal(this, this.level, true, true);

            this.level.explode(null, this.exitPortalLocation.getX(), this.exitPortalLocation.getY(), this.exitPortalLocation.getZ(), 6.0F, Level.ExplosionInteraction.NONE);

            // As vanilla
            this.spawnNewGateway();
            // As vanilla except for our config checks
            if (!this.hasPreviouslyKilledDragon || BetterEndIslandCommon.moreDragonEggs || BetterEndIslandCommon.CONFIG.resummonedDragonDropsEgg) {
                // Vanilla puts it on the top block by heightmap at this.origin, we put it in a slightly different place
                this.level.setBlockAndUpdate(
                        this.exitPortalLocation.above(),
                        Blocks.DRAGON_EGG.defaultBlockState());
            }

            // Departure from vanilla
            // Turn bedrock on spikes into obsidian
            int topY = BetterEndIslandCommon.betterEnd ? 70 : 60;
            List<EndSpikeFeature.EndSpike> spikes = BetterSpikeFeature.getSpikesForLevel(level);
            spikes.forEach(spike -> {
                int crystalY = topY + ((IEndSpike) spike).getCrystalYOffset();
                this.level.setBlock(new BlockPos(spike.getCenterX(), crystalY - 1, spike.getCenterZ()), Blocks.OBSIDIAN.defaultBlockState(), 3);
            });

            // As vanilla
            this.hasPreviouslyKilledDragon = true;
            write_dragonKilled(true);
            this.bei$numTimesDragonKilled++;
            this.setDirty();
        }

    }

    @Override
    public void advanceRespawnStage(BetterDragonRespawnStage nextStage) {
        if (this.bei$dragonRespawnStage == null) {
            throw new IllegalStateException("Better Dragon respawn isn't in progress, can't skip ahead in the respawn process.");
        }
        write_respawnTime(0);
        nextStage.onStart(this.level, this);
        this.setDirty();
    }

    @Override
    public void tickBellSound() {
        if (!BetterEndIslandCommon.CONFIG.playBellSound) return;

        if (!this.bei$hasDragonEverSpawned || this.bei$dragonRespawnStage != null) {
            long gameTime = this.level.getGameTime();
            int soundY = this.exitPortalLocation == null ? 80 : this.exitPortalLocation.getY() + 15;

            if (gameTime % 100 == 0) {
                // Play bell sound every 4 seconds
                this.level.playSound(null, new BlockPos(0, soundY, 0), SoundEvents.BELL_BLOCK, SoundSource.NEUTRAL, 24.0f, 0.5f);

                // When close to center, play a higher pitch resonance sound
                this.level.playSound(null, new BlockPos(0, soundY, 0), SoundEvents.BELL_RESONATE, SoundSource.NEUTRAL, 4.0f, 0.9f);
            }

            // Play low pitch resonance sound every 3 bell rings
            if (gameTime % 300 == 0) {
                this.level.playSound(null, new BlockPos(0, 80, 0), SoundEvents.BELL_RESONATE, SoundSource.NEUTRAL, 24.0f, 0.8f);
            }
        }
    }

    @Override
    public BetterDragonRespawnStage getDragonRespawnStage() {
        return this.bei$dragonRespawnStage;
    }

    @Override
    public boolean isFirstExitPortalSpawn() {
        return bei$isFirstExitPortalSpawn;
    }

    @Override
    public boolean hasDragonEverSpawned() {
        return bei$hasDragonEverSpawned;
    }

    @Override
    public int getNumTimesDragonKilled() {
        return bei$numTimesDragonKilled;
    }

    @Override
    public void setDragonRespawnStage(BetterDragonRespawnStage stage) {
        this.bei$dragonRespawnStage = stage;
    }

    @Override
    public void setIsFirstExitPortalSpawn(boolean bl) {
        this.bei$isFirstExitPortalSpawn = bl;
    }

    @Override
    public void setHasDragonEverSpawned(boolean bl) {
        this.bei$hasDragonEverSpawned = bl;
    }

    @Override
    public void setNumTimesDragonKilled(int i) {
        this.bei$numTimesDragonKilled = i;
    }

    public NativeEndFight(EnderDragonFight original, EndFightData data) {
        super(read(original, "needsStateScanning"), read(original, "dragonKilled"), original.hasPreviouslyKilledDragon,
                Optional.ofNullable(original.respawnStage), read(original, "respawnTime"), Optional.ofNullable(original.dragonUUID()),
                Optional.ofNullable(original.exitPortalLocation), new ArrayList<>(original.gateways), new ArrayList<>(original.respawnCrystals));
        this.extra = data;
        this.bei$isFirstExitPortalSpawn = data.firstPortal;
        this.bei$hasDragonEverSpawned = data.everSpawned;
        this.bei$numTimesDragonKilled = data.kills;
        this.bei$dragonRespawnStage = data.stage.isEmpty() ? null : BetterDragonRespawnStage.byName(data.stage);
        if (this.bei$dragonRespawnStage == null && (int) read(original, "respawnTime") != 0) this.bei$dragonRespawnStage = BetterDragonRespawnStage.START;
    }
    private final EndFightData extra;
    private static final int MAX_TICKS_BEFORE_DRAGON_RESPAWN = 1200;
    private static final int TIME_BETWEEN_CRYSTAL_SCANS = 100;
    private static final int GATEWAY_DISTANCE = 96;
    @SuppressWarnings("unchecked")
    private static <T> T read(Object target, String field) {
        try { return (T) com.lunarboreal.structures.nms.PaperAccess.field(EnderDragonFight.class, field).get(target); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }
    private Object callVanilla(String name) {
        try {
            var method = EnderDragonFight.class.getDeclaredMethod(name);
            method.setAccessible(true);
            return method.invoke(this);
        } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }
    private void findOrCreateDragon() { callVanilla("findOrCreateDragon"); }
    private void updateCrystalCount() { write_aliveCrystals(getSpikeCrystals().size()); }
    @Override public List<EndCrystal> getSpikeCrystals() {
        return BetterSpikeFeature.getSpikesForLevel(level).stream()
                .flatMap(spike -> level.getEntitiesOfClass(EndCrystal.class, spike.getTopBoundingBox()).stream())
                .distinct().toList();
    }
    private void updatePlayers() { callVanilla("updatePlayers"); }
    private boolean isArenaLoaded() { return (boolean) callVanilla("isArenaLoaded"); }
    private boolean hasActiveExitPortal() { return (boolean) callVanilla("hasActiveExitPortal"); }
    @Override public boolean tryRespawn(BlockPos pos) { return tryRespawn(); }
    @Override public void spawnExitPortal(boolean active) { ExitPortalUtils.spawnPortal(this, level, active, false); }
    @Override public void spawnNewGateway(BlockPos position) {
        com.yungnickyoung.minecraft.betterendisland.world.feature.BetterEndGatewayFeature.place(level, position, level.getRandom(),
                net.minecraft.world.level.levelgen.feature.EndGatewayFeature.delayedExitSearch());
    }
    public void saveExtra() {
        if (extra == null) return;
        String stage = bei$dragonRespawnStage == null ? "" : bei$dragonRespawnStage.getSerializedName();
        boolean changed = extra.firstPortal != bei$isFirstExitPortalSpawn || extra.everSpawned != bei$hasDragonEverSpawned
                || extra.kills != bei$numTimesDragonKilled || !extra.stage.equals(stage);
        extra.firstPortal = bei$isFirstExitPortalSpawn;
        extra.everSpawned = bei$hasDragonEverSpawned;
        extra.kills = bei$numTimesDragonKilled;
        extra.stage = stage;
        if (changed) extra.setDirty();
    }
    @Override public void setDirty() { saveExtra(); super.setDirty(); }
    private boolean read_dragonKilled() { return read(this, "dragonKilled"); }
    private void write_dragonKilled(boolean value) { com.lunarboreal.structures.nms.PaperAccess.write(this, "dragonKilled", value); }
    private int read_ticksSinceLastPlayerScan() { return read(this, "ticksSinceLastPlayerScan"); }
    private void write_ticksSinceLastPlayerScan(int value) { com.lunarboreal.structures.nms.PaperAccess.write(this, "ticksSinceLastPlayerScan", value); }
    private int increment_ticksSinceLastPlayerScan() { int v=read_ticksSinceLastPlayerScan()+1; write_ticksSinceLastPlayerScan(v); return v; }
    private int next_ticksSinceLastPlayerScan() { int v=read_ticksSinceLastPlayerScan(); write_ticksSinceLastPlayerScan(v+1); return v; }
    private boolean read_needsStateScanning() { return read(this, "needsStateScanning"); }
    private void write_needsStateScanning(boolean value) { com.lunarboreal.structures.nms.PaperAccess.write(this, "needsStateScanning", value); }
    private int read_respawnTime() { return read(this, "respawnTime"); }
    private void write_respawnTime(int value) { com.lunarboreal.structures.nms.PaperAccess.write(this, "respawnTime", value); }
    private int increment_respawnTime() { int v=read_respawnTime()+1; write_respawnTime(v); return v; }
    private int next_respawnTime() { int v=read_respawnTime(); write_respawnTime(v+1); return v; }
    private int read_ticksSinceDragonSeen() { return read(this, "ticksSinceDragonSeen"); }
    private void write_ticksSinceDragonSeen(int value) { com.lunarboreal.structures.nms.PaperAccess.write(this, "ticksSinceDragonSeen", value); }
    private int increment_ticksSinceDragonSeen() { int v=read_ticksSinceDragonSeen()+1; write_ticksSinceDragonSeen(v); return v; }
    private int next_ticksSinceDragonSeen() { int v=read_ticksSinceDragonSeen(); write_ticksSinceDragonSeen(v+1); return v; }
    private int read_ticksSinceCrystalsScanned() { return read(this, "ticksSinceCrystalsScanned"); }
    private void write_ticksSinceCrystalsScanned(int value) { com.lunarboreal.structures.nms.PaperAccess.write(this, "ticksSinceCrystalsScanned", value); }
    private int increment_ticksSinceCrystalsScanned() { int v=read_ticksSinceCrystalsScanned()+1; write_ticksSinceCrystalsScanned(v); return v; }
    private int next_ticksSinceCrystalsScanned() { int v=read_ticksSinceCrystalsScanned(); write_ticksSinceCrystalsScanned(v+1); return v; }
    private int read_aliveCrystals() { return read(this, "aliveCrystals"); }
    private void write_aliveCrystals(int value) { com.lunarboreal.structures.nms.PaperAccess.write(this, "aliveCrystals", value); }
    private int increment_aliveCrystals() { int v=read_aliveCrystals()+1; write_aliveCrystals(v); return v; }
    private int next_aliveCrystals() { int v=read_aliveCrystals(); write_aliveCrystals(v+1); return v; }
    private UUID read_dragonUUID() { return read(this, "dragonUUID"); }
    private void write_dragonUUID(UUID value) { com.lunarboreal.structures.nms.PaperAccess.write(this, "dragonUUID", value); }
}
