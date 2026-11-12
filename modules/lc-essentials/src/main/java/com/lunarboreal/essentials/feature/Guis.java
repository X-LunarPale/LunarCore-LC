package com.lunarboreal.essentials.feature;

import com.lunarboreal.api.util.Durations;
import com.lunarboreal.essentials.EssentialsModule;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 本模块的 GUI：礼包列表/预览、只读背包查看、垃圾桶。 */
public final class Guis {

    /** 只读 GUI（礼包列表/预览）。 */
    public static final class KitHolder implements InventoryHolder {
        public final boolean list;
        public final Map<Integer, String> slots = new HashMap<>();
        private Inventory inv;

        KitHolder(boolean list) { this.list = list; }

        @Override
        public Inventory getInventory() { return inv; }
    }

    /** 垃圾桶 holder，用于识别。 */
    public static final class DisposalHolder implements InventoryHolder {
        private Inventory inv;

        @Override
        public Inventory getInventory() { return inv; }
    }

    /** 以只读方式查看他人背包/末影箱的玩家。 */
    public static final Set<UUID> READONLY_VIEWERS = ConcurrentHashMap.newKeySet();

    private Guis() {}

    private static Component c(EssentialsModule m, String key, Object... ph) {
        return m.api().messages().component("essentials." + key, ph).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public static void openKitList(EssentialsModule m, Player p) {
        List<KitManager.Kit> visible = new ArrayList<>();
        for (KitManager.Kit k : m.kits().all()) if (m.kits().canUse(p, k)) visible.add(k);
        int size = Math.min(54, Math.max(9, ((visible.size() + 8) / 9) * 9));
        KitHolder h = new KitHolder(true);
        Inventory inv = Bukkit.createInventory(h, size, c(m, "kit.gui-title"));
        h.inv = inv;
        int slot = 0;
        for (KitManager.Kit k : visible) {
            if (slot >= size) break;
            ItemStack icon = new ItemStack(KitManager.iconOr(k.icon));
            ItemMeta meta = icon.getItemMeta();
            meta.displayName(m.api().messages().parse(k.display == null ? k.name : k.display)
                    .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
            List<Component> lore = new ArrayList<>();
            long remain = m.kits().remaining(p, k);
            if (k.once && m.data(p).kitOnce.contains(k.name)) lore.add(c(m, "kit.gui-claimed"));
            else if (remain > 0) lore.add(c(m, "kit.gui-cooldown", "time", Durations.format(remain)));
            else lore.add(c(m, "kit.gui-available"));
            lore.add(c(m, "kit.gui-items", "count", k.items.size()));
            lore.add(c(m, "kit.gui-hint"));
            meta.lore(lore);
            icon.setItemMeta(meta);
            inv.setItem(slot, icon);
            h.slots.put(slot, k.name);
            slot++;
        }
        p.openInventory(inv);
    }

    public static void openKitPreview(EssentialsModule m, Player p, KitManager.Kit k) {
        int size = Math.min(54, Math.max(9, ((k.items.size() + 8) / 9) * 9));
        KitHolder h = new KitHolder(false);
        Inventory inv = Bukkit.createInventory(h, size, c(m, "kit.preview-title", "kit", k.name));
        h.inv = inv;
        for (int i = 0; i < k.items.size() && i < size; i++) inv.setItem(i, k.items.get(i).clone());
        p.openInventory(inv);
    }

    public static void openDisposal(EssentialsModule m, Player p) {
        DisposalHolder h = new DisposalHolder();
        Inventory inv = Bukkit.createInventory(h, 36, c(m, "disposal.title"));
        h.inv = inv;
        p.openInventory(inv);
    }
}
