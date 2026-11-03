package com.lunarboreal.essentials.feature;

import com.lunarboreal.essentials.EssentialsModule;
import com.lunarboreal.essentials.data.UserData;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class EssListener implements Listener {

    private static final LegacyComponentSerializer AMP = LegacyComponentSerializer.legacyAmpersand();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private final EssentialsModule m;
    private final Map<UUID, Long> powertoolCooldown = new ConcurrentHashMap<>();

    public EssListener(EssentialsModule m) {
        this.m = m;
        if (m.api().isPaper()) {
            try {
                Class.forName("io.papermc.paper.event.player.AsyncChatEvent");
                m.registerListener(new PaperChatListener(m));
            } catch (ClassNotFoundException ignored) {
                // 非 Paper：仅通过移动/命令/交互判断活动
            }
        }
    }

    // ---------------- 数据加载 / 加入 / 退出 ----------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent e) {
        if (e.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;
        m.users().loadBlocking(e.getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        UUID u = p.getUniqueId();
        if (!m.users().isLoaded(u)) {
            // 预登录时未加载（极少见），异步补载后恢复
            m.runAsync(() -> {
                m.users().unload(u);
                m.users().loadBlocking(u);
                m.runTask(() -> { if (p.isOnline()) afterJoin(p); });
            });
        } else {
            afterJoin(p);
        }
    }

    private void afterJoin(Player p) {
        m.restoreState(p, true);
        if (m.config().getBoolean("kits.first-join-enabled", true) && !p.hasPlayedBefore()) {
            m.kits().giveFirstJoin(p);
        } else if (!p.hasPlayedBefore()) {
            UserData d = m.data(p);
            d.firstKitsGiven = true;
            d.markDirty();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoinMessage(PlayerJoinEvent e) {
        if (m.vanish().isVanished(e.getPlayer().getUniqueId()) && m.config().getBoolean("vanish.hide-join-quit", true)) {
            e.joinMessage(null);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuitMessage(PlayerQuitEvent e) {
        if (m.vanish().isVanished(e.getPlayer().getUniqueId()) && m.config().getBoolean("vanish.hide-join-quit", true)) {
            e.quitMessage(null);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        m.afk().quit(p);
        m.vanish().quit(p);
        Guis.READONLY_VIEWERS.remove(p.getUniqueId());
        powertoolCooldown.remove(p.getUniqueId());
        m.users().saveAsync(p.getUniqueId(), p.getName(), true);
    }

    // ---------------- AFK 活动 ----------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        var f = e.getFrom();
        var t = e.getTo();
        boolean rotated = f.getYaw() != t.getYaw() || f.getPitch() != t.getPitch();
        boolean moved = f.getBlockX() != t.getBlockX() || f.getBlockZ() != t.getBlockZ();
        if (rotated || (moved && !m.config().getBoolean("afk.rotation-only", false))) {
            m.afk().activity(e.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        String msg = e.getMessage().toLowerCase();
        if (msg.startsWith("/afk") || msg.startsWith("/lc:afk") || msg.startsWith("/lbr:afk") || msg.startsWith("/away")) return;
        m.afk().activity(e.getPlayer());
    }

    // ---------------- 上帝模式 ----------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (!m.users().isLoaded(p.getUniqueId())) return;
        boolean god = m.data(p).god;
        boolean afkGod = m.afk().isAfk(p.getUniqueId()) && m.config().getBoolean("afk.invulnerable", false);
        if (god || afkGod) {
            if (e.getCause() == EntityDamageEvent.DamageCause.VOID && !m.config().getBoolean("god.protect-void", false)) return;
            e.setCancelled(true);
            p.setFireTicks(0);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFood(FoodLevelChangeEvent e) {
        if (e.getEntity() instanceof Player p && m.users().isLoaded(p.getUniqueId()) && m.data(p).god
                && e.getFoodLevel() < p.getFoodLevel()) {
            e.setCancelled(true);
        }
    }

    // ---------------- 飞行保持 ----------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameMode(PlayerGameModeChangeEvent e) {
        Player p = e.getPlayer();
        if (e.getNewGameMode() == GameMode.SURVIVAL || e.getNewGameMode() == GameMode.ADVENTURE) {
            if (m.users().isLoaded(p.getUniqueId()) && m.data(p).fly && p.hasPermission("lbr.essentials.fly")) {
                m.runTask(() -> { if (p.isOnline()) p.setAllowFlight(true); });
            }
        }
    }

    // ---------------- 隐身 ----------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p && m.vanish().isVanished(p.getUniqueId())
                && !m.config().getBoolean("vanish.allow-pickup", false)) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTarget(EntityTargetLivingEntityEvent e) {
        if (e.getTarget() instanceof Player p && (m.vanish().isVanished(p.getUniqueId())
                || (m.users().isLoaded(p.getUniqueId()) && m.data(p).god && m.config().getBoolean("god.no-mob-target", false)))) {
            e.setCancelled(true);
        }
    }

    // ---------------- 交互：AFK / 隐身踩压力板 / 强力工具 ----------------

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        if (e.getAction() == Action.PHYSICAL) {
            if (m.vanish().isVanished(p.getUniqueId())) e.setCancelled(true);
            return;
        }
        m.afk().activity(p);
        if (e.getHand() != EquipmentSlot.HAND || !m.config().getBoolean("powertool.enabled", true)) return;
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) return;
        if (!m.users().isLoaded(p.getUniqueId())) return;
        List<String> cmds = m.data(p).powertools.get(hand.getType().name());
        if (cmds == null || cmds.isEmpty() || !p.hasPermission("lbr.essentials.powertool")) return;
        long now = System.currentTimeMillis();
        Long last = powertoolCooldown.get(p.getUniqueId());
        if (last != null && now - last < 150) return;
        powertoolCooldown.put(p.getUniqueId(), now);
        e.setCancelled(true);
        for (String c : cmds) {
            String line = c.replace("{player}", p.getName());
            if (line.startsWith("c:")) p.chat(line.substring(2));
            else p.performCommand(line.startsWith("/") ? line.substring(1) : line);
        }
    }

    // ---------------- GUI ----------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        m.afk().activity(p);
        var top = e.getView().getTopInventory();
        if (top.getHolder() instanceof RecipeGui.Holder rh) {
            e.setCancelled(true);
            if (e.getClickedInventory() == top) RecipeGui.click(m, p, rh, e.getSlot());
            return;
        }
        if (top.getHolder() instanceof Guis.KitHolder h) {
            e.setCancelled(true);
            if (!h.list || e.getClickedInventory() != top) return;
            String kitName = h.slots.get(e.getSlot());
            if (kitName == null) return;
            KitManager.Kit k = m.kits().get(kitName);
            if (k == null) return;
            if (e.isRightClick()) {
                m.runTask(() -> Guis.openKitPreview(m, p, k));
            } else {
                m.runTask(() -> {
                    p.closeInventory();
                    p.performCommand("kit " + k.name);
                });
            }
            return;
        }
        if (Guis.READONLY_VIEWERS.contains(p.getUniqueId())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Guis.KitHolder
                || e.getView().getTopInventory().getHolder() instanceof RecipeGui.Holder
                || Guis.READONLY_VIEWERS.contains(e.getWhoClicked().getUniqueId())) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        Guis.READONLY_VIEWERS.remove(e.getPlayer().getUniqueId());
        if (e.getInventory().getHolder() instanceof Guis.DisposalHolder) {
            e.getInventory().clear();
        }
    }

    // ---------------- 彩色告示牌 / 铁砧 ----------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSign(SignChangeEvent e) {
        if (!m.config().getBoolean("color.signs", true)) return;
        Player p = e.getPlayer();
        if (!p.hasPermission("lbr.essentials.signs.color")) return;
        for (int i = 0; i < e.lines().size(); i++) {
            Component line = e.line(i);
            if (line == null) continue;
            String text = PLAIN.serialize(line);
            if (text.indexOf('&') < 0) continue;
            e.line(i, AMP.deserialize(filterCodes(p, text, "signs")));
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onAnvil(PrepareAnvilEvent e) {
        if (!m.config().getBoolean("color.anvil", true)) return;
        ItemStack result = e.getResult();
        if (result == null || result.getType().isAir()) return;
        String rename = e.getView().getRenameText();
        if (rename == null || rename.indexOf('&') < 0) return;
        if (!(e.getView().getPlayer() instanceof Player p) || !p.hasPermission("lbr.essentials.anvil.color")) return;
        ItemStack copy = result.clone();
        ItemMeta meta = copy.getItemMeta();
        meta.displayName(AMP.deserialize(filterCodes(p, rename, "anvil"))
                .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
        copy.setItemMeta(meta);
        e.setResult(copy);
    }

    /** 去除无权限使用的 &k（混乱）与格式代码。 */
    static String filterCodes(Player p, String text, String kind) {
        boolean format = p.hasPermission("lbr.essentials." + kind + ".format");
        boolean magic = p.hasPermission("lbr.essentials." + kind + ".magic");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '&' && i + 1 < text.length()) {
                char code = Character.toLowerCase(text.charAt(i + 1));
                if (code == 'k' && !magic) { i++; continue; }
                if ("lmno".indexOf(code) >= 0 && !format) { i++; continue; }
            }
            sb.append(ch);
        }
        return sb.toString();
    }
}
