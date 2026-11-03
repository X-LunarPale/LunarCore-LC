package com.lunarboreal.teleport;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 地标箱子 GUI：分页，最后一行为翻页栏，点击图标传送。 */
public final class WarpGui implements InventoryHolder {

    private final int page;
    private final Map<Integer, String> slots = new HashMap<>();
    private int prevSlot = -1, nextSlot = -1, closeSlot = -1;
    private Inventory inventory;

    private WarpGui(int page) {
        this.page = page;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    /** 打开第 page 页（从 0 开始）。没有可用地标时返回 false。 */
    public static boolean open(TeleportModule m, Player p, int page) {
        List<WarpManager.Warp> usable = new ArrayList<>();
        for (WarpManager.Warp w : m.warps().all()) if (m.warps().canUse(p, w)) usable.add(w);
        if (usable.isEmpty()) return false;
        int rows = Math.max(2, Math.min(6, m.config().getInt("warps.gui.rows", 6)));
        int perPage = (rows - 1) * 9;
        int pages = Math.max(1, (usable.size() + perPage - 1) / perPage);
        page = Math.max(0, Math.min(pages - 1, page));

        WarpGui gui = new WarpGui(page);
        Component title = m.api().messages().parse(m.raw("gui.title", "page", page + 1, "pages", pages));
        Inventory inv = Bukkit.createInventory(gui, rows * 9, title);
        gui.inventory = inv;

        Material def = material(m.config().getString("warps.gui.default-icon", "ENDER_PEARL"), Material.ENDER_PEARL);
        boolean showCost = m.economy().isPresent();
        for (int i = 0; i < perPage; i++) {
            int idx = page * perPage + i;
            if (idx >= usable.size()) break;
            WarpManager.Warp w = usable.get(idx);
            List<Component> lore = new ArrayList<>();
            if (w.description() != null && !w.description().isBlank()) {
                for (String line : w.description().split("\\|")) lore.add(text(m, line));
            }
            lore.add(text(m, m.raw("gui.lore-location", "location", w.loc().display())));
            BigDecimal cost = w.cost() > 0 ? BigDecimal.valueOf(w.cost()) : m.teleports().configCost("warp");
            if (showCost && cost.signum() > 0) {
                lore.add(text(m, m.raw("gui.lore-cost", "cost", m.economy().get().format(cost))));
            }
            lore.add(text(m, m.raw("gui.lore-click")));
            inv.setItem(i, item(material(w.icon(), def), text(m, m.raw("gui.item-name", "name", w.name())), lore));
            gui.slots.put(i, w.name());
        }

        int base = (rows - 1) * 9;
        Material filler = material(m.config().getString("warps.gui.filler", "GRAY_STAINED_GLASS_PANE"), null);
        if (filler != null) {
            for (int i = base; i < base + 9; i++) inv.setItem(i, item(filler, Component.text(" "), List.of()));
        }
        Component info = text(m, m.raw("gui.page-info", "page", page + 1, "pages", pages));
        if (page > 0) {
            gui.prevSlot = base + 3;
            inv.setItem(gui.prevSlot, item(Material.ARROW, text(m, m.raw("gui.prev")), List.of(info)));
        }
        if (page < pages - 1) {
            gui.nextSlot = base + 5;
            inv.setItem(gui.nextSlot, item(Material.ARROW, text(m, m.raw("gui.next")), List.of(info)));
        }
        gui.closeSlot = base + 4;
        inv.setItem(gui.closeSlot, item(Material.BARRIER, text(m, m.raw("gui.close")), List.of(info)));
        p.openInventory(inv);
        return true;
    }

    private static Component text(TeleportModule m, String mini) {
        return m.api().messages().parse(mini).decoration(TextDecoration.ITALIC, false);
    }

    private static ItemStack item(Material mat, Component name, List<Component> lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(name);
            if (!lore.isEmpty()) meta.lore(lore);
            it.setItemMeta(meta);
        }
        return it;
    }

    static Material material(String name, Material def) {
        if (name == null || name.isBlank()) return def;
        Material mat = Material.matchMaterial(name.trim());
        return mat != null && mat.isItem() && !mat.isAir() ? mat : def;
    }

    /** 模块禁用时关闭所有打开的 GUI（避免引用已卸载的类）。 */
    static void closeAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof WarpGui) p.closeInventory();
        }
    }

    /** GUI 点击监听。 */
    static final class GuiListener implements Listener {
        private final TeleportModule m;

        GuiListener(TeleportModule m) {
            this.m = m;
        }

        @EventHandler(priority = EventPriority.HIGHEST)
        public void onClick(InventoryClickEvent e) {
            if (!(e.getView().getTopInventory().getHolder() instanceof WarpGui gui)) return;
            e.setCancelled(true);
            HumanEntity who = e.getWhoClicked();
            if (!(who instanceof Player p)) return;
            int slot = e.getRawSlot();
            if (slot < 0 || slot >= e.getView().getTopInventory().getSize()) return;
            if (slot == gui.prevSlot) {
                m.runTask(() -> open(m, p, gui.page - 1));
            } else if (slot == gui.nextSlot) {
                m.runTask(() -> open(m, p, gui.page + 1));
            } else if (slot == gui.closeSlot) {
                m.runTask(p::closeInventory);
            } else {
                String name = gui.slots.get(slot);
                if (name == null) return;
                m.runTask(() -> {
                    p.closeInventory();
                    p.performCommand("warp " + name);
                });
            }
        }

        @EventHandler(priority = EventPriority.HIGHEST)
        public void onDrag(InventoryDragEvent e) {
            if (e.getView().getTopInventory().getHolder() instanceof WarpGui) e.setCancelled(true);
        }
    }
}
