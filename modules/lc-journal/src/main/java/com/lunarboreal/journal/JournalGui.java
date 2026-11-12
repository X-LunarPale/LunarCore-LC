package com.lunarboreal.journal;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

final class JournalGui implements InventoryHolder {

    private static final int PAGE_SIZE = 36;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());
    private final JournalModule module;
    private final UUID owner;
    private final Inventory inventory;
    private JournalEntry.Category category = JournalEntry.Category.BIOMES;
    private int page = 1;
    private long pages = 1;
    private int request;
    private boolean loading;
    private boolean queryRunning;

    private JournalGui(JournalModule module, Player player) {
        this.module = module;
        this.owner = player.getUniqueId();
        inventory = Bukkit.createInventory(this, 54, text("gui.title"));
    }

    JournalModule module() { return module; }

    @Override
    public Inventory getInventory() { return inventory; }

    static void open(JournalModule module, Player player) {
        if (player.getOpenInventory().getTopInventory().getHolder() instanceof JournalGui existing
                && existing.module == module) {
            existing.load(player);
            return;
        }
        JournalGui gui = new JournalGui(module, player);
        player.openInventory(gui.inventory);
        gui.load(player);
    }

    private Component text(String key, Object... values) {
        return module.api().messages().component("journal." + key, values)
                .decoration(TextDecoration.ITALIC, false);
    }

    private ItemStack button(Material material, String key, Object... values) {
        return item(material, text(key, values), List.of());
    }

    private ItemStack item(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        var meta = item.getItemMeta();
        meta.displayName(name.decoration(TextDecoration.ITALIC, false));
        meta.lore(lore.stream().map(line -> line.decoration(TextDecoration.ITALIC, false)).toList());
        item.setItemMeta(meta);
        return item;
    }

    private void frame() {
        inventory.clear();
        inventory.setItem(0, button(Material.GRASS_BLOCK, "gui.biomes"));
        inventory.setItem(1, button(Material.BOOK, "gui.advancements"));
        inventory.setItem(2, button(Material.FISHING_ROD, "gui.fishing"));
        inventory.setItem(8, button(Material.BARRIER, "gui.close"));
        if (page > 1) inventory.setItem(45, button(Material.ARROW, "gui.previous"));
        inventory.setItem(49, button(Material.PAPER, "gui.page", "page", page, "pages", pages));
        if (page < pages) inventory.setItem(53, button(Material.ARROW, "gui.next"));
    }

    private void load(Player player) {
        loading = true;
        request++;
        frame();
        inventory.setItem(22, button(Material.CLOCK, "gui.loading"));
        if (queryRunning) return;
        query(player);
    }

    private void query(Player player) {
        queryRunning = true;
        int token = request;
        var selected = category;
        int selectedPage = page;
        if (!module.executeIO(() -> {
            if (!module.active()) return;
            try {
                var result = module.repository().page(owner, selected, "", (long) (selectedPage - 1) * PAGE_SIZE, PAGE_SIZE);
                module.onMain(() -> {
                    queryRunning = false;
                    if (!current(player)) return;
                    if (token != request) {
                        query(player);
                        return;
                    }
                    loading = false;
                    pages = Math.max(1, (result.total() + PAGE_SIZE - 1) / PAGE_SIZE);
                    frame();
                    if (result.entries().isEmpty()) inventory.setItem(22, button(Material.GLASS_BOTTLE, "gui.empty"));
                    else {
                        int slot = 9;
                        for (JournalEntry entry : result.entries()) inventory.setItem(slot++, entryItem(entry));
                    }
                });
            } catch (Exception error) {
                module.logger().log(Level.WARNING, "Cannot load journal menu", error);
                module.onMain(() -> {
                    queryRunning = false;
                    if (!current(player)) return;
                    if (token != request) {
                        query(player);
                        return;
                    }
                    loading = false;
                    frame();
                    inventory.setItem(22, button(Material.REDSTONE_BLOCK, "gui.failed"));
                    module.msg(player, "error.database");
                });
            }
        })) {
            queryRunning = false;
            loading = false;
            frame();
            inventory.setItem(22, button(Material.REDSTONE_BLOCK, "gui.failed"));
        }
    }

    private boolean current(Player player) {
        return player.isOnline() && player.hasPermission("lc.journal.use")
                && player.getOpenInventory().getTopInventory() == inventory;
    }

    private ItemStack entryItem(JournalEntry entry) {
        Material material = Material.PAPER;
        Component name = Component.text(entry.label(), NamedTextColor.WHITE);
        NamespacedKey key = NamespacedKey.fromString(entry.key());
        if (entry.category() == JournalEntry.Category.BIOMES) {
            material = Material.GRASS_BLOCK;
            if (key != null && "minecraft".equals(key.getNamespace()))
                name = Component.translatable("biome.minecraft." + key.getKey().replace('/', '.'));
        } else if (entry.category() == JournalEntry.Category.ADVANCEMENTS && key != null) {
            var advancement = Bukkit.getAdvancement(key);
            if (advancement != null && advancement.getDisplay() != null) {
                name = advancement.getDisplay().title();
                material = advancement.getDisplay().icon().getType();
                if (material.isAir()) material = Material.BOOK;
            }
        } else if (entry.category() == JournalEntry.Category.FISHING) {
            Material caught = Material.matchMaterial(entry.key());
            if (caught != null && caught.isItem() && !caught.isAir()) {
                material = caught;
                name = Component.translatable(caught.translationKey());
            }
        }
        return item(material, name, List.of(
                text("gui.entry-key", "key", entry.key()),
                text("gui.entry-world", "world", entry.world()),
                text("gui.entry-time", "time", TIME.format(Instant.ofEpochMilli(entry.firstSeen())))));
    }

    private void click(Player player, int slot) {
        if (slot == 8) {
            module.onMain(player::closeInventory);
            return;
        }
        JournalEntry.Category selected = switch (slot) {
            case 0 -> JournalEntry.Category.BIOMES;
            case 1 -> JournalEntry.Category.ADVANCEMENTS;
            case 2 -> JournalEntry.Category.FISHING;
            default -> null;
        };
        if (selected != null) {
            category = selected;
            page = 1;
        } else if (loading) return;
        else if (slot == 45 && page > 1) page--;
        else if (slot == 53 && page < pages) page++;
        else return;
        load(player);
    }

    static final class Listener implements org.bukkit.event.Listener {
        @EventHandler(ignoreCancelled = false)
        public void onClick(InventoryClickEvent event) {
            if (!(event.getView().getTopInventory().getHolder() instanceof JournalGui gui)) return;
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player)
                    || !player.getUniqueId().equals(gui.owner)
                    || !player.hasPermission("lc.journal.use") || !gui.module.active()) return;
            if ((event.getClick() == ClickType.LEFT || event.getClick() == ClickType.RIGHT)
                    && event.getRawSlot() >= 0 && event.getRawSlot() < gui.inventory.getSize())
                gui.click(player, event.getRawSlot());
        }

        @EventHandler(ignoreCancelled = false)
        public void onDrag(InventoryDragEvent event) {
            if (event.getView().getTopInventory().getHolder() instanceof JournalGui) event.setCancelled(true);
        }

        @EventHandler
        public void onClose(InventoryCloseEvent event) {
            if (event.getInventory().getHolder() instanceof JournalGui gui) gui.request++;
        }
    }
}
