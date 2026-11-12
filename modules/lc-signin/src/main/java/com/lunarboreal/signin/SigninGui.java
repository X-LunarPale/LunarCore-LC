package com.lunarboreal.signin;

import com.lunarboreal.api.service.EconomyService;
import com.lunarboreal.signin.SigninStore.PlayerData;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 签到日历界面（6 行箱子）。
 * <pre>
 *  列 0：信息 / 签到按钮 / 奖励说明   列 1-7：周一 ~ 周日   列 8：关闭
 * </pre>
 */
public final class SigninGui implements InventoryHolder {

    private static final int SLOT_INFO = 0;
    private static final int SLOT_SIGN = 9;
    private static final int SLOT_REWARDS = 18;
    private static final int SLOT_CLOSE = 8;

    private final SigninModule m;
    private final UUID owner;
    private final YearMonth month;
    private final Map<Integer, LocalDate> slots = new HashMap<>();
    private final Inventory inventory;

    private SigninGui(SigninModule m, Player p, YearMonth month) {
        this.m = m;
        this.owner = p.getUniqueId();
        this.month = month;
        Component title = m.api().messages().parse(m.raw("gui.title", "year", month.getYear(), "month", month.getMonthValue()));
        this.inventory = Bukkit.createInventory(this, 54, title);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public static void open(SigninModule m, Player p) {
        m.withData(p, d -> {
            SigninGui gui = new SigninGui(m, p, YearMonth.from(m.settings().today()));
            gui.render(d);
            p.openInventory(gui.inventory);
        });
    }

    private ItemStack item(Material mat, int amount, String nameKey, String loreKey, Object... ph) {
        ItemStack stack = new ItemStack(mat, Math.max(1, Math.min(64, amount)));
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(Reward.noItalic(m.api().messages().parse(m.raw(nameKey, ph))));
            if (loreKey != null) {
                String raw = m.raw(loreKey, ph);
                if (raw != null && !raw.isBlank()) {
                    List<Component> lore = new ArrayList<>();
                    for (String line : raw.split("\n|<br>")) lore.add(Reward.noItalic(m.api().messages().parse(line)));
                    meta.lore(lore);
                }
            }
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private void render(PlayerData d) {
        SigninSettings s = m.settings();
        LocalDate today = s.today();
        inventory.clear();
        slots.clear();

        ItemStack filler = item(s.matFiller, 1, "gui.filler", null);
        for (int i = 0; i < 54; i++) {
            int col = i % 9;
            if (col == 0 || col == 8) inventory.setItem(i, filler);
        }

        EconomyService eco = m.api().services().get(EconomyService.class).orElse(null);
        String cost = eco == null ? s.makeupCost.toPlainString() : eco.format(s.makeupCost);
        int used = d.makeupCount(month);
        int left = Math.max(0, s.makeupLimit - used);
        boolean canMakeup = s.makeupEnabled && left > 0;

        // 日历：第一周从周一开始
        int offset = month.atDay(1).getDayOfWeek().getValue() - DayOfWeek.MONDAY.getValue();
        for (int day = 1; day <= month.lengthOfMonth(); day++) {
            int cell = offset + day - 1;
            int row = cell / 7;
            int col = cell % 7 + 1;
            if (row > 5) break;
            int slot = row * 9 + col;
            LocalDate date = month.atDay(day);
            ItemStack it;
            if (d.signed(date)) {
                it = d.isMakeup(date)
                        ? item(s.matMakeup, day, "gui.day.makeup-signed", "gui.day.makeup-signed-lore", "date", date)
                        : item(s.matSigned, day, "gui.day.signed", "gui.day.signed-lore", "date", date);
            } else if (date.equals(today)) {
                it = item(s.matToday, day, "gui.day.today", "gui.day.today-lore", "date", date,
                        "reward", SigninModule.rich(s.daily.description()));
            } else if (date.isBefore(today)) {
                it = canMakeup
                        ? item(s.matMissed, day, "gui.day.missed", "gui.day.missed-lore", "date", date, "cost", cost, "left", left)
                        : item(s.matLocked, day, "gui.day.locked", "gui.day.locked-lore", "date", date);
            } else {
                it = item(s.matFuture, day, "gui.day.future", null, "date", date);
            }
            inventory.setItem(slot, it);
            slots.put(slot, date);
        }

        int streak = d.streak(today);
        inventory.setItem(SLOT_INFO, item(s.matInfo, 1, "gui.info", "gui.info-lore",
                "streak", streak, "total", d.total(), "month", d.monthCount(month),
                "left", left, "limit", s.makeupLimit));
        inventory.setItem(SLOT_SIGN, d.signed(today)
                ? item(s.matSigned, 1, "gui.sign-done", null)
                : item(s.matToday, 1, "gui.sign-button", "gui.day.today-lore",
                        "reward", SigninModule.rich(s.daily.description()), "date", today));

        StringBuilder rewards = new StringBuilder();
        for (var e : s.streak.entrySet()) {
            rewards.append(m.raw("gui.rewards-streak-line", "days", e.getKey(),
                    "reward", SigninModule.rich(e.getValue().description()))).append('\n');
        }
        for (var e : s.milestones.entrySet()) {
            rewards.append(m.raw("gui.rewards-milestone-line", "days", e.getKey(),
                    "reward", SigninModule.rich(e.getValue().description()))).append('\n');
        }
        ItemStack rewardsItem = item(s.matRewards, 1, "gui.rewards", null);
        ItemMeta meta = rewardsItem.getItemMeta();
        if (meta != null && !rewards.isEmpty()) {
            List<Component> lore = new ArrayList<>();
            for (String line : rewards.toString().split("\n")) {
                if (!line.isBlank()) lore.add(Reward.noItalic(m.api().messages().parse(line)));
            }
            meta.lore(lore);
            rewardsItem.setItemMeta(meta);
        }
        inventory.setItem(SLOT_REWARDS, rewardsItem);
        inventory.setItem(SLOT_CLOSE, item(s.matClose, 1, "gui.close", null));
    }

    private void click(Player p, int slot) {
        if (slot == SLOT_CLOSE) {
            m.runTask(p::closeInventory);
            return;
        }
        PlayerData d = m.store().get(owner);
        if (d == null) {
            m.runTask(p::closeInventory);
            return;
        }
        LocalDate today = m.settings().today();
        if (!YearMonth.from(today).equals(month)) {
            // 跨月了，重新打开
            m.runTask(() -> open(m, p));
            return;
        }
        boolean changed = false;
        if (slot == SLOT_SIGN) {
            changed = !d.signed(today) && m.signToday(p, d);
        } else {
            LocalDate date = slots.get(slot);
            if (date == null) return;
            if (date.equals(today)) {
                changed = !d.signed(today) && m.signToday(p, d);
            } else if (date.isBefore(today) && !d.signed(date)) {
                changed = m.makeup(p, d, date);
            }
        }
        if (changed) render(d);
    }

    /** 界面点击监听（防止拿取物品）。 */
    public static final class Listener implements org.bukkit.event.Listener {
        private final SigninModule m;

        public Listener(SigninModule m) {
            this.m = m;
        }

        @EventHandler(ignoreCancelled = false)
        public void onClick(InventoryClickEvent e) {
            if (!(e.getInventory().getHolder() instanceof SigninGui gui)) return;
            e.setCancelled(true);
            if (!(e.getWhoClicked() instanceof Player p)) return;
            int raw = e.getRawSlot();
            if (raw < 0 || raw >= gui.inventory.getSize()) return;
            if (!p.getUniqueId().equals(gui.owner)) return;
            gui.click(p, raw);
        }

        @EventHandler
        public void onDrag(InventoryDragEvent e) {
            if (e.getInventory().getHolder() instanceof SigninGui) e.setCancelled(true);
        }
    }
}
