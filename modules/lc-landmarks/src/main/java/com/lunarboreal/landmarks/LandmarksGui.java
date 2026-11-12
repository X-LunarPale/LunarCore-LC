package com.lunarboreal.landmarks;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

final class LandmarksGui implements Listener {
    private final LandmarksModule module;
    private static final class Menu implements InventoryHolder {
        final UUID viewer;
        final boolean mine;
        final int page;
        final Map<Integer, Runnable> actions = new HashMap<>();
        final Map<Long, Long> displayed = new HashMap<>();
        Inventory inventory;
        boolean consumed;
        Menu(UUID viewer, boolean mine, int page) { this.viewer = viewer; this.mine = mine; this.page = page; }
        @Override public Inventory getInventory() { return inventory; }
    }
    LandmarksGui(LandmarksModule module) { this.module = module; }
    private Menu menu(Player player, boolean mine, int page, String key, Object... values) {
        Menu menu = new Menu(player.getUniqueId(), mine, page);
        menu.inventory = Bukkit.createInventory(menu, 54, module.component(key, values));
        return menu;
    }
    private void button(Menu menu, int slot, Material material, Component name, List<Component> lore, Runnable action) {
        ItemStack item = new ItemStack(material);
        var meta = item.getItemMeta();
        meta.displayName(name);
        meta.lore(lore);
        item.setItemMeta(meta);
        menu.inventory.setItem(slot, item);
        if (action != null) menu.actions.put(slot, action);
    }
    void open(Player player, boolean mine, int requestedPage) {
        module.requireReady();
        if (!player.hasPermission("lc.landmarks.use")) return;
        List<Landmark> entries = module.entries(player, mine);
        int pages = Math.max(1, (entries.size() + 44) / 45);
        int page = Math.clamp(requestedPage, 0, pages - 1);
        Menu menu = menu(player, mine, page, mine ? "gui.mine" : "gui.public", "page", page + 1, "pages", pages);
        for (int i = page * 45; i < Math.min(entries.size(), (page + 1) * 45); i++) {
            Landmark entry = entries.get(i);
            var lore = new ArrayList<Component>();
            lore.add(module.component("gui.owner", "owner", entry.ownerName()));
            if (mine) lore.add(module.component("gui.status", "status", status(entry)));
            for (String line : wrap(entry.description(), 36)) lore.add(Component.text(line, NamedTextColor.GRAY));
            menu.displayed.put(entry.id(), entry.revision());
            button(menu, i - page * 45, mine && entry.status() != Landmark.Status.APPROVED ? Material.WRITABLE_BOOK : Material.BOOK,
                    Component.text(entry.title(), NamedTextColor.AQUA), lore, () -> detail(player, entry.id(), mine, page));
        }
        if (entries.isEmpty()) button(menu, 22, Material.PAPER, module.component(mine ? "gui.empty-mine" : "gui.empty"),
                List.of(), null);
        if (page > 0) button(menu, 45, Material.ARROW, module.component("gui.previous"), List.of(),
                () -> open(player, mine, page - 1));
        button(menu, 47, Material.WRITABLE_BOOK, module.component("gui.mine-tab"), List.of(), () -> open(player, true, 0));
        button(menu, 49, Material.BOOKSHELF, module.component("gui.public-tab"), List.of(), () -> open(player, false, 0));
        button(menu, 51, Material.PAPER, module.component("gui.help"), List.of(), () -> module.msg(player, "help"));
        if (page + 1 < pages) button(menu, 53, Material.ARROW, module.component("gui.next"), List.of(),
                () -> open(player, mine, page + 1));
        player.openInventory(menu.inventory);
    }
    private Component status(Landmark entry) {
        return module.component("status." + entry.status().name().toLowerCase(Locale.ROOT));
    }
    private void detail(Player player, long id, boolean mine, int page) {
        Landmark entry = module.book().find(id);
        if (mine ? !entry.owner().equals(player.getUniqueId()) : !module.visibleTo(player, entry)) {
            open(player, mine, page);
            return;
        }
        Menu menu = menu(player, mine, page, "gui.detail", "id", id);
        menu.displayed.put(id, entry.revision());
        var lore = new ArrayList<Component>();
        lore.add(module.component("gui.owner", "owner", entry.ownerName()));
        lore.add(module.component("gui.status", "status", status(entry)));
        lore.add(module.component("gui.world", "world", entry.world()));
        lore.add(module.component("gui.coordinates", "x", entry.x(), "y", entry.y(), "z", entry.z()));
        lore.add(module.component("gui.claim", "id", entry.landId()));
        for (String line : wrap(entry.description(), 36)) lore.add(Component.text(line, NamedTextColor.GRAY));
        if (mine && !entry.reviewNote().isEmpty()) {
            lore.add(module.component("gui.review-note"));
            for (String line : wrap(entry.reviewNote(), 36)) lore.add(Component.text(line, NamedTextColor.YELLOW));
        }
        button(menu, 13, Material.MAP, Component.text(entry.title(), NamedTextColor.AQUA), lore, null);
        if (mine) {
            button(menu, 30, Material.WRITABLE_BOOK, module.component("gui.edit"), List.of(),
                    () -> module.msg(player, "edit-hint", "id", id));
            if (entry.status() != Landmark.Status.WITHDRAWN)
                button(menu, 32, Material.BARRIER, module.component("gui.withdraw"), List.of(),
                        () -> confirmWithdraw(player, entry, page));
        }
        button(menu, 49, Material.ARROW, module.component("gui.back"), List.of(), () -> open(player, mine, page));
        player.openInventory(menu.inventory);
    }
    private void confirmWithdraw(Player player, Landmark entry, int page) {
        Menu menu = menu(player, true, page, "gui.confirm-withdraw");
        menu.displayed.put(entry.id(), entry.revision());
        button(menu, 20, Material.LIME_CONCRETE, module.component("gui.confirm"), List.of(),
                () -> module.withdraw(player, entry.id(), entry.revision()));
        button(menu, 24, Material.RED_CONCRETE, module.component("gui.cancel"), List.of(),
                () -> detail(player, entry.id(), true, page));
        player.openInventory(menu.inventory);
    }
    @EventHandler public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Menu menu)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !menu.viewer.equals(player.getUniqueId())
                || !module.ready() || !player.hasPermission("lc.landmarks.use")
                || event.getClick() != ClickType.LEFT || event.getRawSlot() < 0 || event.getRawSlot() >= 54
                || menu.consumed) return;
        Runnable action = menu.actions.get(event.getRawSlot());
        if (action == null) return;
        menu.consumed = true;
        module.later(() -> {
            if (!player.isOnline() || player.getOpenInventory().getTopInventory().getHolder() != menu) return;
            player.closeInventory();
            if (!module.ready() || !player.hasPermission("lc.landmarks.use")) return;
            try {
                if (!valid(player, menu)) { open(player, menu.mine, menu.page); return; }
                action.run();
            } catch (RuntimeException error) { module.report(player, error); }
        });
    }
    @EventHandler public void drag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Menu) event.setCancelled(true);
    }
    private boolean valid(Player player, Menu menu) {
        for (var displayed : menu.displayed.entrySet()) {
            Landmark current;
            try { current = module.book().find(displayed.getKey()); }
            catch (RuntimeException gone) { return false; }
            if (current.revision() != displayed.getValue()
                    || (menu.mine ? !current.owner().equals(player.getUniqueId()) : !module.visibleTo(player, current)))
                return false;
        }
        return true;
    }
    void refreshInvalid() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!(player.getOpenInventory().getTopInventory().getHolder() instanceof Menu menu)) continue;
            if (!module.ready() || !player.hasPermission("lc.landmarks.use")) player.closeInventory();
            else if (!valid(player, menu)) {
                player.closeInventory();
                open(player, menu.mine, menu.page);
            }
        }
    }
    void closeAll() {
        for (Player player : Bukkit.getOnlinePlayers())
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof Menu) player.closeInventory();
    }
    private static List<String> wrap(String value, int width) {
        int[] points = value.codePoints().toArray();
        var lines = new ArrayList<String>();
        for (int i = 0; i < points.length; i += width)
            lines.add(new String(points, i, Math.min(width, points.length - i)));
        return lines;
    }
}
