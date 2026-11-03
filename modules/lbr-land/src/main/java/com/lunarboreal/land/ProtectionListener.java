package com.lunarboreal.land;

import com.lunarboreal.api.service.LandService.Flag;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Sheep;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Vehicle;
import org.bukkit.entity.Villager;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.projectiles.ProjectileSource;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 领地保护监听。所有查询走区块索引，O(1)~O(少量)。 */
final class ProtectionListener implements Listener {

    private static final Set<CreatureSpawnEvent.SpawnReason> NATURAL_SPAWNS = EnumSet.of(
            CreatureSpawnEvent.SpawnReason.NATURAL, CreatureSpawnEvent.SpawnReason.JOCKEY,
            CreatureSpawnEvent.SpawnReason.REINFORCEMENTS, CreatureSpawnEvent.SpawnReason.PATROL,
            CreatureSpawnEvent.SpawnReason.VILLAGE_INVASION, CreatureSpawnEvent.SpawnReason.RAID);

    /** 在方块上使用会改变世界的物品。 */
    private static final Set<Material> BUILD_ITEMS = EnumSet.of(Material.ARMOR_STAND, Material.END_CRYSTAL,
            Material.BONE_MEAL, Material.FLINT_AND_STEEL, Material.FIRE_CHARGE, Material.HONEYCOMB, Material.LEAD,
            Material.ITEM_FRAME, Material.GLOW_ITEM_FRAME, Material.PAINTING, Material.BRUSH, Material.WIND_CHARGE);

    private static final Set<Material> CONTAINER_BLOCKS = EnumSet.of(Material.LECTERN, Material.CHISELED_BOOKSHELF,
            Material.DECORATED_POT, Material.JUKEBOX, Material.FLOWER_POT, Material.COMPOSTER, Material.CAULDRON,
            Material.WATER_CAULDRON, Material.LAVA_CAULDRON, Material.POWDER_SNOW_CAULDRON, Material.CAMPFIRE,
            Material.SOUL_CAMPFIRE, Material.BEEHIVE, Material.BEE_NEST, Material.RESPAWN_ANCHOR);

    private final LandModule module;
    private final Map<UUID, Long> cooldown = new ConcurrentHashMap<>();

    ProtectionListener(LandModule module) {
        this.module = module;
    }

    private LandManager lm() {
        return module.manager();
    }

    /** 检查玩家在方块所在位置的权限，失败时（带冷却）提示。 */
    private boolean check(Player p, Block b, Flag flag) {
        Land land = lm().at(b);
        if (land == null || module.can(p, land, flag)) return true;
        deny(p, land, flag);
        return false;
    }

    private boolean check(Player p, Entity target, Flag flag) {
        Land land = lm().at(target.getLocation());
        if (land == null || module.can(p, land, flag)) return true;
        deny(p, land, flag);
        return false;
    }

    private void deny(Player p, Land land, Flag flag) {
        long now = System.currentTimeMillis();
        Long last = cooldown.get(p.getUniqueId());
        if (last != null && now - last < module.settings().denyCooldown) return;
        cooldown.put(p.getUniqueId(), now);
        module.msg(p, "protect.denied", "land", land.name, "owner", land.ownerName, "flag", flag.name());
    }

    private static Player playerOf(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile proj) {
            ProjectileSource src = proj.getShooter();
            if (src instanceof Player p) return p;
        }
        if (damager instanceof TNTPrimed tnt && tnt.getSource() instanceof Player p) return p;
        if (damager instanceof AreaEffectCloud cloud && cloud.getSource() instanceof Player p) return p;
        return null;
    }

    @EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent e) {
        cooldown.remove(e.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------ 方块

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (!check(e.getPlayer(), e.getBlockPlaced(), Flag.BUILD)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (!check(e.getPlayer(), e.getBlock(), Flag.BREAK)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent e) {
        if (!check(e.getPlayer(), e.getBlock(), Flag.BUILD)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent e) {
        if (!check(e.getPlayer(), e.getBlockClicked(), Flag.BREAK)) e.setCancelled(true);
    }

    // ------------------------------------------------------------ 交互

    @SuppressWarnings("deprecation") // Material#isInteractable：Bukkit 通用，Paper 标记过时但仍可用
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        if (e.useInteractedBlock() == Event.Result.DENY && e.useItemInHand() == Event.Result.DENY) return;
        Block b = e.getClickedBlock();
        if (b == null) return;
        Player p = e.getPlayer();
        if (e.getAction() == Action.RIGHT_CLICK_BLOCK && e.getItem() != null
                && isBuildItem(e.getItem().getType(), false)
                && !check(p, b.getRelative(e.getBlockFace()), Flag.BUILD)) {
            e.setCancelled(true);
            return;
        }
        Land land = lm().at(b);
        if (land == null) return;
        Material t = b.getType();

        if (e.getAction() == Action.PHYSICAL) {
            Flag f = t == Material.FARMLAND || t == Material.TURTLE_EGG || t == Material.SNIFFER_EGG ? Flag.BREAK : Flag.INTERACT;
            if (!module.can(p, land, f)) {
                e.setUseInteractedBlock(Event.Result.DENY);
                e.setCancelled(true);
            }
            return;
        }
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        ItemStack item = e.getItem();
        boolean holding = item != null && !item.getType().isAir();
        boolean interactsBlock = !(p.isSneaking() && holding) && t.isInteractable();

        if (interactsBlock) {
            Flag f = blockFlag(b, t);
            if (f != null && !module.can(p, land, f)) {
                deny(p, land, f);
                e.setCancelled(true);
                return;
            }
        }
        if (holding && isBuildItem(item.getType(), interactsBlock)) {
            Block target = b.getRelative(e.getBlockFace());
            if (!check(p, b, Flag.BUILD) || !check(p, target, Flag.BUILD)) e.setCancelled(true);
        }
    }

    /** 方块交互对应的 flag；null 表示无需保护（如末影箱、工作台）。 */
    private static Flag blockFlag(Block b, Material t) {
        if (Tag.DOORS.isTagged(t) || Tag.TRAPDOORS.isTagged(t) || Tag.FENCE_GATES.isTagged(t)) return Flag.USE_DOOR;
        if (Tag.BUTTONS.isTagged(t) || t == Material.LEVER) return Flag.INTERACT;
        if (t == Material.ENDER_CHEST || t == Material.CRAFTING_TABLE || t == Material.ENCHANTING_TABLE
                || t == Material.CARTOGRAPHY_TABLE || t == Material.STONECUTTER || t == Material.LOOM
                || t == Material.SMITHING_TABLE || t == Material.GRINDSTONE) return null;
        if (CONTAINER_BLOCKS.contains(t) || Tag.ANVIL.isTagged(t) || Tag.SHULKER_BOXES.isTagged(t)) return Flag.CONTAINER;
        BlockState state = b.getState();
        if (state instanceof InventoryHolder) return Flag.CONTAINER;
        return Flag.INTERACT;
    }

    private static boolean isBuildItem(Material m, boolean interactsBlock) {
        if (BUILD_ITEMS.contains(m)) return true;
        String n = m.name();
        if (n.endsWith("_SPAWN_EGG") || n.endsWith("_BOAT") || n.endsWith("_RAFT") || n.endsWith("MINECART")) return true;
        // 斧/锹/锄修改方块（去皮、压路、耕地），仅在不与方块本身交互时
        return !interactsBlock && (n.endsWith("_AXE") || n.endsWith("_SHOVEL") || n.endsWith("_HOE"));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        Entity en = e.getRightClicked();
        Flag f;
        if (en instanceof ItemFrame || en instanceof ArmorStand || en instanceof InventoryHolder) f = Flag.CONTAINER;
        else if (en instanceof Hanging) f = Flag.BUILD;
        else if (en instanceof Player || en instanceof Enemy) return;
        else f = Flag.INTERACT;
        if (!check(e.getPlayer(), en, f)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent e) {
        if (!check(e.getPlayer(), e.getRightClicked(), Flag.CONTAINER)) e.setCancelled(true);
    }

    // ------------------------------------------------------------ 实体伤害

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        Entity victim = e.getEntity();
        Player attacker = playerOf(e.getDamager());
        Land land = lm().at(victim.getLocation());

        if (victim instanceof Player pv) {
            if (attacker == null || attacker == pv) return;
            Land aLand = lm().at(attacker.getLocation());
            if ((land != null && !land.globalFlag(Flag.PVP)) || (aLand != null && !aLand.globalFlag(Flag.PVP))) {
                e.setCancelled(true);
                deny(attacker, land != null ? land : aLand, Flag.PVP);
            }
            return;
        }
        if (land == null) return;
        boolean structural = victim instanceof Hanging || victim instanceof ArmorStand || victim instanceof EnderCrystal
                || victim instanceof Vehicle;
        if (structural) {
            if (attacker != null) {
                if (!module.can(attacker, land, Flag.BREAK)) {
                    e.setCancelled(true);
                    deny(attacker, land, Flag.BREAK);
                }
            } else if (isExplosion(e.getCause())) {
                if (!land.globalFlag(Flag.EXPLOSION)) e.setCancelled(true);
            } else if (module.settings().protectMobGrief) {
                e.setCancelled(true);
            }
            return;
        }
        if (victim instanceof Enemy) return;
        if (victim instanceof LivingEntity) {
            if (attacker != null) {
                if (!module.can(attacker, land, Flag.ANIMAL_DAMAGE)) {
                    e.setCancelled(true);
                    deny(attacker, land, Flag.ANIMAL_DAMAGE);
                }
            } else if (isExplosion(e.getCause()) && !land.globalFlag(Flag.EXPLOSION)) {
                e.setCancelled(true);
            }
        }
    }

    private static boolean isExplosion(EntityDamageEvent.DamageCause c) {
        return c == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION || c == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onVehicleDestroy(VehicleDestroyEvent e) {
        Player p = e.getAttacker() == null ? null : playerOf(e.getAttacker());
        if (p != null && !check(p, e.getVehicle(), Flag.BREAK)) e.setCancelled(true);
    }

    // ------------------------------------------------------------ 悬挂物（画、展示框）

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakEvent e) {
        Land land = lm().at(e.getEntity().getLocation());
        if (land == null) return;
        if (e instanceof HangingBreakByEntityEvent be && be.getRemover() != null) {
            Player p = playerOf(be.getRemover());
            if (p != null) {
                if (!module.can(p, land, Flag.BREAK)) {
                    e.setCancelled(true);
                    deny(p, land, Flag.BREAK);
                }
                return;
            }
            if (module.settings().protectMobGrief && e.getCause() == HangingBreakEvent.RemoveCause.ENTITY) {
                e.setCancelled(true);
                return;
            }
        }
        if (e.getCause() == HangingBreakEvent.RemoveCause.EXPLOSION && !land.globalFlag(Flag.EXPLOSION)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent e) {
        if (e.getPlayer() != null && !check(e.getPlayer(), e.getEntity(), Flag.BUILD)) e.setCancelled(true);
    }

    // ------------------------------------------------------------ 爆炸

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        filterExplosion(e.blockList());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        filterExplosion(e.blockList());
    }

    private void filterExplosion(List<Block> blocks) {
        if (blocks.isEmpty()) return;
        blocks.removeIf(b -> {
            Land l = lm().at(b);
            return l != null && !l.globalFlag(Flag.EXPLOSION);
        });
    }

    // ------------------------------------------------------------ 火

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent e) {
        Material src = e.getSource().getType();
        if (src != Material.FIRE && src != Material.SOUL_FIRE) return;
        Land l = lm().at(e.getBlock());
        if (l != null && !l.globalFlag(Flag.FIRE_SPREAD)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) {
        Land l = lm().at(e.getBlock());
        if (l != null && !l.globalFlag(Flag.FIRE_SPREAD)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent e) {
        Land l = lm().at(e.getBlock());
        if (l == null) return;
        Player p = e.getPlayer();
        if (p != null) {
            if (!module.can(p, l, Flag.BUILD)) {
                e.setCancelled(true);
                deny(p, l, Flag.BUILD);
            }
            return;
        }
        if (!l.globalFlag(Flag.FIRE_SPREAD)) e.setCancelled(true);
    }

    // ------------------------------------------------------------ 液体 / 活塞

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent e) {
        if (!module.settings().protectLiquid) return;
        Block to = e.getToBlock();
        Land toLand = lm().at(to);
        if (toLand == null) return;
        Land fromLand = lm().at(e.getBlock());
        if (fromLand != toLand) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSponge(org.bukkit.event.block.SpongeAbsorbEvent e) {
        if (!module.settings().protectLiquid) return;
        Land source = lm().at(e.getBlock());
        // SpongeAbsorbEvent has no reliable player actor (also fires on neighbour updates).
        // Keep only states in the sponge's region (same land or both wilderness).
        // Protect both directions across every XYZ border, including land -> wilderness.
        e.getBlocks().removeIf(state -> LandGeometry.spongeProtected(source, lm().at(state.getLocation())));
    }

    /** 领地外发射器向领地内投放（液体、火、方块等）。 */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDispense(org.bukkit.event.block.BlockDispenseEvent e) {
        if (!(e.getBlock().getBlockData() instanceof org.bukkit.block.data.Directional d)) return;
        Land target = lm().at(e.getBlock().getRelative(d.getFacing()));
        if (target != null && lm().at(e.getBlock()) != target) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        if (!module.settings().protectPistons) return;
        if (crossesBorder(e.getBlock(), e.getBlocks(), e.getDirection())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        if (!module.settings().protectPistons) return;
        if (crossesBorder(e.getBlock(), e.getBlocks(), e.getDirection())) e.setCancelled(true);
    }

    /** 被推/拉的方块（原位置与目标位置）必须与活塞处于同一领地（或同为野外）。 */
    private boolean crossesBorder(Block piston, List<Block> blocks, BlockFace dir) {
        Land pl = lm().at(piston);
        if (lm().at(piston.getRelative(dir)) != pl) return true;
        BlockFace back = dir.getOppositeFace();
        for (Block b : blocks) {
            // 推出与拉回方向不同实现间语义略有差异，两侧都检查（保守）
            if (lm().at(b) != pl || lm().at(b.getRelative(dir)) != pl || lm().at(b.getRelative(back)) != pl) return true;
        }
        return false;
    }

    // ------------------------------------------------------------ 生物

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent e) {
        if (!(e.getEntity() instanceof Enemy)) return;
        if (!NATURAL_SPAWNS.contains(e.getSpawnReason())) return;
        Land l = lm().at(e.getLocation());
        if (l != null && !l.globalFlag(Flag.MOB_SPAWN)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChangeBlock(EntityChangeBlockEvent e) {
        Entity en = e.getEntity();
        if (en instanceof Player p) {
            if (!check(p, e.getBlock(), Flag.BREAK)) e.setCancelled(true);
            return;
        }
        if (!module.settings().protectMobGrief) return;
        if (en instanceof FallingBlock || en instanceof Villager || en instanceof Sheep) return;
        Land l = lm().at(e.getBlock());
        if (l == null) return;
        Player shooter = playerOf(en);
        if (shooter != null) {
            if (!module.can(shooter, l, Flag.BREAK)) e.setCancelled(true);
            return;
        }
        e.setCancelled(true);
    }
}
