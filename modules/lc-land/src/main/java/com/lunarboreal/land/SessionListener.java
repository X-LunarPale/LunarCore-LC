package com.lunarboreal.land;

import com.lunarboreal.api.service.LandService.Flag;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 选区工具、进出领地提示、进入/传送限制。 */
final class SessionListener implements Listener {

    private static final net.kyori.adventure.text.minimessage.MiniMessage SAFE_MM =
            net.kyori.adventure.text.minimessage.MiniMessage.builder()
                    .tags(net.kyori.adventure.text.minimessage.tag.resolver.TagResolver.resolver(
                            net.kyori.adventure.text.minimessage.tag.standard.StandardTags.color(),
                            net.kyori.adventure.text.minimessage.tag.standard.StandardTags.decorations(),
                            net.kyori.adventure.text.minimessage.tag.standard.StandardTags.gradient(),
                            net.kyori.adventure.text.minimessage.tag.standard.StandardTags.rainbow(),
                            net.kyori.adventure.text.minimessage.tag.standard.StandardTags.reset()))
                    .build();

    private final LandModule module;
    /** 玩家当前所在领地（用于进出提示）。 */
    private final Map<UUID, Land> current = new ConcurrentHashMap<>();
    private final Map<UUID, Long> borderCooldown = new ConcurrentHashMap<>();
    private final Map<UUID, Long> denyCooldown = new ConcurrentHashMap<>();

    SessionListener(LandModule module) {
        this.module = module;
    }

    // ------------------------------------------------------------ 选区工具

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSelect(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || e.getClickedBlock() == null) return;
        Player p = e.getPlayer();
        if (e.getItem() == null || e.getItem().getType() != module.settings().tool) return;
        if (!p.hasPermission("lc.land.create")) return;
        boolean left;
        if (e.getAction() == Action.LEFT_CLICK_BLOCK) left = true;
        else if (e.getAction() == Action.RIGHT_CLICK_BLOCK) left = false;
        else return;
        e.setCancelled(true);
        setPos(p, e.getClickedBlock().getLocation(), left ? 0 : 1);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onToolBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        if (p.getGameMode() == GameMode.CREATIVE && p.getInventory().getItemInMainHand().getType() == module.settings().tool
                && p.hasPermission("lc.land.create")) e.setCancelled(true);
    }

    void setPos(Player p, Location loc, int index) {
        Location[] sel = module.selections.computeIfAbsent(p.getUniqueId(), k -> new Location[2]);
        Location block = new Location(loc.getWorld(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        // 不同世界的旧点作废
        Location other = sel[1 - index];
        if (other != null && other.getWorld() != block.getWorld()) sel[1 - index] = null;
        sel[index] = block;
        module.msg(p, index == 0 ? "select.pos1" : "select.pos2", "x", block.getBlockX(), "y", block.getBlockY(), "z", block.getBlockZ());
        if (sel[0] != null && sel[1] != null) {
            int minX = Math.min(sel[0].getBlockX(), sel[1].getBlockX()), maxX = Math.max(sel[0].getBlockX(), sel[1].getBlockX());
            int minZ = Math.min(sel[0].getBlockZ(), sel[1].getBlockZ()), maxZ = Math.max(sel[0].getBlockZ(), sel[1].getBlockZ());
            long area = (long) (maxX - minX + 1) * (maxZ - minZ + 1);
            String world = block.getWorld().getName();
            int minY = Math.min(sel[0].getBlockY(), sel[1].getBlockY()), maxY = Math.max(sel[0].getBlockY(), sel[1].getBlockY());
            module.msg(p, "select.done", "width", maxX - minX + 1, "length", maxZ - minZ + 1, "area", area,
                    "cost", module.fmt(module.cost(world, area, minY, maxY)));
            module.visualizer().show(p, world, minX, minY, minZ, maxX, maxY, maxZ, module.settings().selectionShowSeconds, Visualizer.SELECTION);
        }
    }

    // ------------------------------------------------------------ 进出

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (e instanceof PlayerTeleportEvent || !e.hasChangedBlock()) return;
        Player p = e.getPlayer();
        Land to = module.manager().at(e.getTo());
        Land from = current.get(p.getUniqueId());
        if (to == from) return;
        if (to != null && !module.can(p, to, Flag.ENTER)) {
            // 已身处该领地（例如登录时就在里面）时不阻止离开/内部移动
            Land fromActual = module.manager().at(e.getFrom());
            if (fromActual != to) {
                e.setCancelled(true);
                deny(p, "protect.enter", to);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMoveDone(PlayerMoveEvent e) {
        if (e instanceof PlayerTeleportEvent || !e.hasChangedBlock()) return;
        Land from = current.get(e.getPlayer().getUniqueId());
        Land to = module.manager().at(e.getTo());
        if (from != to) changeLand(e.getPlayer(), from, to);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        Player p = e.getPlayer();
        Land to = module.manager().at(e.getTo());
        Land from = module.manager().at(e.getFrom());
        if (to == null || to == from) return;
        if (module.teleportBypass.remove(p.getUniqueId())) return;
        if (!module.can(p, to, Flag.TELEPORT) || !module.can(p, to, Flag.ENTER)) {
            e.setCancelled(true);
            deny(p, "protect.teleport", to);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleportDone(PlayerTeleportEvent e) {
        Player p = e.getPlayer();
        Land to = module.manager().at(e.getTo());
        Land from = current.get(p.getUniqueId());
        if (to != from) changeLand(p, from, to);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Land l = module.manager().at(e.getPlayer().getLocation());
        if (l != null) current.put(e.getPlayer().getUniqueId(), l);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID u = e.getPlayer().getUniqueId();
        current.remove(u);
        denyCooldown.remove(u);
        borderCooldown.remove(u);
        module.bypass.remove(u);
        module.bypassDisabled.remove(u);
        module.selections.remove(u);
        module.teleportBypass.remove(u);
        module.visualizer().hide(u);
    }

    private void changeLand(Player p, Land from, Land to) {
        if (to == null) current.remove(p.getUniqueId());
        else current.put(p.getUniqueId(), to);
        if (from != null && module.manager().byName(from.name) == from) {
            show(p, from, from.leaveMessage, "notice.leave");
        }
        if (to != null) show(p, to, to.enterMessage, "notice.enter");
        long now = System.currentTimeMillis();
        Settings s = module.settings();
        if (s.transitionParticles && now - borderCooldown.getOrDefault(p.getUniqueId(), 0L) >= s.transitionCooldown) {
            borderCooldown.put(p.getUniqueId(), now);
            module.visualizer().hide(p.getUniqueId());
            if (from != null) module.visualizer().add(p, from, s.transitionSeconds);
            if (to != null) module.visualizer().add(p, to, s.transitionSeconds);
        }
    }

    private void show(Player p, Land land, String custom, String defKey) {
        Component c;
        if (custom != null && !custom.isEmpty()) {
            // 玩家可控文本：只允许颜色/装饰类标签，防止 click/hover/insert 等注入
            String text = custom.replace("{land}", SAFE_MM.escapeTags(land.name))
                    .replace("{owner}", SAFE_MM.escapeTags(String.valueOf(land.ownerName)))
                    .replace("{player}", p.getName());
            try {
                c = SAFE_MM.deserialize(text);
            } catch (Exception ex) {
                c = Component.text(custom);
            }
        } else if (module.settings().showDefaultMessages) {
            c = module.api().messages().component("land." + defKey, "land", land.name, "owner", land.ownerName);
        } else return;
        switch (module.settings().messageDisplay) {
            case "title" -> p.showTitle(Title.title(Component.empty(), c,
                    Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(1500), Duration.ofMillis(400))));
            case "chat" -> p.sendMessage(c);
            default -> p.sendActionBar(c);
        }
    }

    private void deny(Player p, String key, Land land) {
        long now = System.currentTimeMillis();
        Long last = denyCooldown.get(p.getUniqueId());
        if (last != null && now - last < module.settings().denyCooldown) return;
        denyCooldown.put(p.getUniqueId(), now);
        module.msg(p, key, "land", land.name);
    }
}
