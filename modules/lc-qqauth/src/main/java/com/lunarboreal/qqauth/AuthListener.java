package com.lunarboreal.qqauth;

import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import net.kyori.adventure.text.Component;

import java.util.Locale;

final class AuthListener implements Listener {
    private final QQAuthModule module;
    AuthListener(QQAuthModule module) { this.module = module; }
    private boolean blocked(Player player) { return !module.authenticated(player); }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void prelogin(AsyncPlayerPreLoginEvent e) {
        if (e.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;
        if (!e.getName().matches("[A-Za-z0-9_]{3,16}")) { e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, module.componentMsg("login.invalid-name")); return; }
        if (!module.ready) { e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, module.componentMsg("login.not-ready")); return; }
        try {
            var existingName = module.bindings.findByName(e.getName());
            if (existingName.isPresent() && !existingName.get().uuid().equals(e.getUniqueId())) {
                var account = module.auth.account(e.getName());
                if (!module.nameBasedIdentity()) {
                    e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, module.componentMsg("login.identity-mismatch")); return;
                }
                if (account.isPresent() && account.get().locked()) {
                    e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, module.componentMsg("login.locked")); return;
                }
                if (account.isEmpty() || account.get().uuid().equals(e.getUniqueId())) {
                    var issued = module.bindings.issueIdentityRecovery(e.getUniqueId(), e.getName());
                    e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, module.componentMsg("login.identity-recovery",
                            "code", issued.code(), "player", e.getName(),
                            "group", module.config().getString("integration.group-id", ""),
                            "ttl", module.bindingSettings().bindTtl().toSeconds()));
                    return;
                }
                if (!account.get().uuid().equals(existingName.get().uuid())) {
                    e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, module.componentMsg("login.identity-mismatch")); return;
                }
            }
            boolean bound = module.bindings.find(e.getUniqueId()).isPresent()
                    || module.nameBasedIdentity() && existingName.isPresent();
            if (!bound && module.bindingRequired()) {
                var issued = module.bindings.issueBind(e.getUniqueId(), e.getName());
                e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, module.componentMsg("login.bind-required", "code", issued.code(), "player", e.getName(), "group", module.config().getString("integration.group-id", ""), "ttl", module.bindingSettings().bindTtl().toSeconds()));
            } else {
                var oldAccount = module.nameBasedIdentity() ? module.auth.account(e.getName()) : module.auth.account(e.getUniqueId());
                if (oldAccount.isPresent() && oldAccount.get().uuid().equals(e.getUniqueId()) && !oldAccount.get().name().equals(e.getName())) {
                    if (module.auth.renameByUuid(e.getUniqueId(), e.getName()) != com.lunarboreal.qqauth.auth.AuthService.Result.SUCCESS) {
                        e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, module.componentMsg("login.rename-conflict")); return;
                    }
                }
                if (bound) module.bindings.updateName(e.getUniqueId(), e.getName());
            }
        } catch (Exception ex) {
            module.logger().log(java.util.logging.Level.WARNING, "预登录身份检查失败", ex);
            e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, module.componentMsg("login.unavailable"));
        }
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void login(PlayerLoginEvent e) {
        if (e.getResult() != PlayerLoginEvent.Result.ALLOWED) return;
        if (!module.ready || !module.canEnter(e.getPlayer().getUniqueId(), e.getPlayer().getName()))
            e.disallow(PlayerLoginEvent.Result.KICK_OTHER, module.msg("login.changed"));
        else {
            var account = module.auth.account(e.getPlayer().getName());
            if (account.isPresent() && (account.get().locked()
                    || !module.nameBasedIdentity() && !account.get().uuid().equals(e.getPlayer().getUniqueId())))
                e.disallow(PlayerLoginEvent.Result.KICK_OTHER, module.msg("login.locked"));
        }
    }
    @EventHandler(priority = EventPriority.LOWEST)
    public void join(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        module.connections.put(p.getUniqueId(), p);
        module.joined.put(p.getUniqueId(), System.currentTimeMillis());
        module.granted.remove(p.getUniqueId());
        if (module.ready && !module.busy.contains(p.getUniqueId()) && module.auth.resumeSession(p.getUniqueId(), p.getName(), module.ip(p)) == com.lunarboreal.qqauth.auth.AuthService.Result.SUCCESS)
            module.granted.put(p.getUniqueId(), p);
        module.movement.update(p, !module.authenticated(p));
        p.sendMessage(module.componentMsg(module.authenticated(p) ? "join.session-restored"
                : module.bound.contains(p.getUniqueId()) ? "join.instructions" : "join.optional-instructions", "player", p.getName()));
        module.remindBinding(p);
    }
    @EventHandler public void quit(PlayerQuitEvent e) {
        module.movement.release(e.getPlayer());
        if (module.connections.remove(e.getPlayer().getUniqueId(), e.getPlayer())) {
            module.joined.remove(e.getPlayer().getUniqueId());
            module.granted.remove(e.getPlayer().getUniqueId(), e.getPlayer());
            module.reminderDays.remove(e.getPlayer().getUniqueId());
            if (module.auth != null) module.auth.disconnect(e.getPlayer().getUniqueId());
        }
    }
    @EventHandler(priority = EventPriority.LOWEST)
    public void command(PlayerCommandPreprocessEvent e) {
        String raw = e.getMessage().substring(1).stripLeading();
        String label = raw.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        String leaf = label.substring(label.lastIndexOf(':') + 1);
        // Best-effort redaction for later listeners only; earlier handlers may have seen the raw text.
        if (AuthCommands.SECRET_COMMANDS.contains(leaf)) {
            boolean previouslyCancelled = e.isCancelled();
            e.setCancelled(true); e.setMessage("/qqauth-redacted");
            if (previouslyCancelled) return;
            if (label.contains(":")) { e.getPlayer().sendMessage(module.componentMsg("command.namespaced")); return; }
            String[] pieces = raw.split("\\s+");
            AuthCommands.execute(module, e.getPlayer(), leaf, java.util.Arrays.copyOfRange(pieces, 1, pieces.length));
            return;
        }
        if (blocked(e.getPlayer())) {
            boolean permitted = !label.contains(":") && module.config().getStringList("auth.restrictions.allow-commands").contains(label);
            if (!permitted) { e.setCancelled(true); e.getPlayer().sendMessage(module.componentMsg("login.required")); }
        }
    }
    @EventHandler(priority = EventPriority.LOWEST)
    public void chat(AsyncChatEvent e) { if (blocked(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void move(PlayerMoveEvent e) {
        boolean blocked = blocked(e.getPlayer());
        module.movement.update(e.getPlayer(), blocked);
        if (blocked && e.hasChangedPosition() && e.getTo() != null) {
            var from = e.getFrom();
            var to = e.getTo();
            var anchor = from.clone();
            anchor.setYaw(to.getYaw()); anchor.setPitch(to.getPitch());
            if (to.getY() < from.getY()) {
                // Let gravity land the player, but discard any horizontal movement.
                anchor.setY(to.getY());
                e.setTo(anchor);
            } else {
                // Paper rolls a cancelled move back to from. Preserve requested look at that anchor.
                e.setFrom(anchor); e.setTo(anchor.clone()); e.setCancelled(true);
            }
        }
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void jump(com.destroystokyo.paper.event.player.PlayerJumpEvent e) {
        if (blocked(e.getPlayer())) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void sprint(PlayerToggleSprintEvent e) { if (blocked(e.getPlayer()) && e.isSprinting()) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void flight(PlayerToggleFlightEvent e) { if (blocked(e.getPlayer()) && e.isFlying()) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void glide(EntityToggleGlideEvent e) {
        if (e.getEntity() instanceof Player p && blocked(p) && e.isGliding()) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void velocity(PlayerVelocityEvent e) { if (blocked(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void teleport(PlayerTeleportEvent e) { if (blocked(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void interact(PlayerInteractEvent e) { if (blocked(e.getPlayer())) { e.setCancelled(true); e.setUseInteractedBlock(Event.Result.DENY); e.setUseItemInHand(Event.Result.DENY); } }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void interactEntity(PlayerInteractEntityEvent e) { if (blocked(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void breakBlock(BlockBreakEvent e) { if (blocked(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void placeBlock(BlockPlaceEvent e) { if (blocked(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void damage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && blocked(p)) e.setCancelled(true);
        if (e instanceof EntityDamageByEntityEvent by) {
            if (by.getDamager() instanceof Player p && blocked(p)) e.setCancelled(true);
            if (by.getDamager() instanceof org.bukkit.entity.Projectile projectile && projectile.getShooter() instanceof Player p && blocked(p)) e.setCancelled(true);
        }
    }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void inventory(InventoryClickEvent e) { if (e.getWhoClicked() instanceof Player p && blocked(p)) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void drag(InventoryDragEvent e) { if (e.getWhoClicked() instanceof Player p && blocked(p)) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void open(InventoryOpenEvent e) { if (e.getPlayer() instanceof Player p && blocked(p)) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void drop(PlayerDropItemEvent e) { if (blocked(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void pickup(EntityPickupItemEvent e) { if (e.getEntity() instanceof Player p && blocked(p)) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void swap(PlayerSwapHandItemsEvent e) { if (blocked(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void bucketEmpty(PlayerBucketEmptyEvent e) { if (blocked(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void bucketFill(PlayerBucketFillEvent e) { if (blocked(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void consume(PlayerItemConsumeEvent e) { if (blocked(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void editBook(PlayerEditBookEvent e) { if (blocked(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void editSign(SignChangeEvent e) { if (blocked(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void vehicle(VehicleEnterEvent e) { if (e.getEntered() instanceof Player p && blocked(p)) e.setCancelled(true); }
}
