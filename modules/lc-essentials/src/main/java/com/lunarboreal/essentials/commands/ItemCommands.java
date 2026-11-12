package com.lunarboreal.essentials.commands;

import com.lunarboreal.api.service.EconomyService;
import com.lunarboreal.essentials.Cmd;
import com.lunarboreal.essentials.EssentialsModule;
import com.lunarboreal.essentials.data.UserData;
import com.lunarboreal.essentials.feature.Guis;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MenuType;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.inventory.meta.WritableBookMeta;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/** 物品、虚拟工作台、价格/出售类命令。 */
public final class ItemCommands {

    private ItemCommands() {}

    private static final String[] NONE = new String[0];
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    @SuppressWarnings("deprecation")
    public static void register(EssentialsModule m) {
        // ---------------- give / item / more ----------------
        m.reg((Cmd) m.cmd("give", "give", NONE, (s, l, a) -> {
            if (a.length < 2) throw new Cmd.Usage("/" + l + " <玩家> <物品> [数量]");
            Player t = m.online(s, a[0]);
            Material mat = material(m, s, a[1]);
            int amount = a.length > 2 ? m.parseInt(s, a[2]) : defaultAmount(m, mat);
            giveItem(m, s, t, mat, amount);
        }).tab((s, a) -> a.length == 1 ? Cmd.players(a[0]) : a.length == 2 ? materials(a[1]) : List.of())
                .usage("<玩家> <物品> [数量]").description("给予物品"));
        m.reg((Cmd) m.cmd("item", "item", new String[]{"i"}, (s, l, a) -> {
            Player p = m.self(s);
            if (a.length < 1) throw new Cmd.Usage("/" + l + " <物品> [数量]");
            Material mat = material(m, s, a[0]);
            int amount = a.length > 1 ? m.parseInt(s, a[1]) : defaultAmount(m, mat);
            giveItem(m, s, p, mat, amount);
        }).tab((s, a) -> a.length == 1 ? materials(a[0]) : List.of()).playerOnly(true).usage("<物品> [数量]").description("获取物品"));
        m.reg((Cmd) m.cmd("more", "more", NONE, (s, l, a) -> {
            Player p = m.self(s);
            ItemStack hand = hand(m, p);
            int max = p.hasPermission("lc.essentials.more.oversized")
                    ? m.config().getInt("give.oversized-stack-size", 64) : hand.getMaxStackSize();
            hand.setAmount(Math.max(hand.getAmount(), Math.min(99, max)));
            m.msg(s, "more.done", "amount", hand.getAmount());
        }).playerOnly(true).description("补满手中物品"));

        // ---------------- repair ----------------
        m.reg((Cmd) m.cmd("repair", "repair", new String[]{"fix"}, (s, l, a) -> {
            Player p = m.self(s);
            if (a.length > 0 && a[0].equalsIgnoreCase("all")) {
                if (!p.hasPermission("lc.essentials.repair.all")) {
                    m.msg(s, "no-permission-sub", "sub", "repair all");
                    return;
                }
                int count = 0;
                PlayerInventory inv = p.getInventory();
                for (ItemStack is : inv.getContents()) if (repair(m, p, is)) count++;
                m.msg(s, "repair.all", "count", count);
            } else {
                ItemStack hand = hand(m, p);
                if (repair(m, p, hand)) m.msg(s, "repair.hand", "item", hand.getType().name().toLowerCase(Locale.ROOT));
                else m.msg(s, "repair.cannot");
            }
        }).tab((s, a) -> a.length == 1 ? Cmd.opts(a[0], List.of("hand", "all")) : List.of())
                .playerOnly(true).usage("[hand|all]").description("修复物品"));

        // ---------------- enchant ----------------
        m.reg((Cmd) m.cmd("enchant", "enchant", NONE, (s, l, a) -> {
            Player p = m.self(s);
            if (a.length < 1) {
                List<String> names = new ArrayList<>();
                for (Enchantment e : enchantments()) names.add(e.getKey().getKey());
                m.msg(s, "enchant.list", "list", String.join(", ", names));
                return;
            }
            ItemStack hand = hand(m, p);
            Enchantment ench = enchantment(a[0]);
            if (ench == null) {
                m.msg(s, "enchant.unknown", "name", a[0]);
                return;
            }
            int level = a.length > 1 ? m.parseInt(s, a[1]) : ench.getMaxLevel();
            if (level <= 0) {
                hand.removeEnchantment(ench);
                m.msg(s, "enchant.removed", "enchant", ench.getKey().getKey());
                return;
            }
            boolean unsafe = p.hasPermission("lc.essentials.enchant.unsafe") && m.config().getBoolean("enchant.allow-unsafe", true);
            int maxUnsafe = m.config().getInt("enchant.max-unsafe-level", 255);
            if (!unsafe && (level > ench.getMaxLevel() || !ench.canEnchantItem(hand))) {
                m.msg(s, "enchant.unsafe", "max", ench.getMaxLevel());
                return;
            }
            if (level > maxUnsafe) {
                m.msg(s, "enchant.too-high", "max", maxUnsafe);
                return;
            }
            hand.addUnsafeEnchantment(ench, level);
            m.msg(s, "enchant.done", "enchant", ench.getKey().getKey(), "level", level);
        }).tab((s, a) -> {
            if (a.length == 1) {
                List<String> names = new ArrayList<>();
                for (Enchantment e : enchantments()) names.add(e.getKey().getKey());
                return Cmd.opts(a[0], names);
            }
            return a.length == 2 ? Cmd.opts(a[1], List.of("0", "1", "2", "3", "4", "5", "10")) : List.of();
        }).playerOnly(true).usage("<附魔> [等级]").description("附魔手中物品（等级 0 移除）"));

        // ---------------- hat ----------------
        m.reg((Cmd) m.cmd("hat", "hat", new String[]{"head"}, (s, l, a) -> {
            Player p = m.self(s);
            PlayerInventory inv = p.getInventory();
            if (a.length > 0 && a[0].equalsIgnoreCase("remove")) {
                ItemStack helm = inv.getHelmet();
                if (helm == null || helm.getType().isAir()) { m.msg(s, "hat.none"); return; }
                inv.setHelmet(null);
                for (ItemStack left : inv.addItem(helm).values()) p.getWorld().dropItemNaturally(p.getLocation(), left);
                m.msg(s, "hat.removed");
                return;
            }
            ItemStack hand = inv.getItemInMainHand();
            if (hand.getType().isAir()) { m.msg(s, "hand-empty"); return; }
            ItemStack old = inv.getHelmet();
            inv.setHelmet(hand);
            inv.setItemInMainHand(old);
            m.msg(s, "hat.done");
        }).playerOnly(true).usage("[remove]").description("把手中物品戴在头上"));

        // ---------------- skull ----------------
        m.reg((Cmd) m.cmd("skull", "skull", new String[]{"playerhead"}, (s, l, a) -> {
            Player p = m.self(s);
            String owner = a.length > 0 ? a[0] : p.getName();
            if (!owner.equalsIgnoreCase(p.getName()) && !p.hasPermission("lc.essentials.skull.others")) {
                m.msg(s, "no-permission-others");
                return;
            }
            if (!owner.matches("[A-Za-z0-9_]{1,16}")) { m.msg(s, "skull.invalid", "name", owner); return; }
            Player on = Bukkit.getPlayerExact(owner);
            if (on != null) {
                ItemStack head = new ItemStack(Material.PLAYER_HEAD);
                SkullMeta meta = (SkullMeta) head.getItemMeta();
                meta.setOwningPlayer(on);
                head.setItemMeta(meta);
                addOrDrop(p, head);
                m.msg(s, "skull.done", "name", on.getName());
                return;
            }
            final String name = owner;
            // Paper PlayerProfile 异步补全皮肤
            Bukkit.createProfile(name).update().whenComplete((profile, err) -> m.runTask(() -> {
                if (!p.isOnline()) return;
                ItemStack head = new ItemStack(Material.PLAYER_HEAD);
                SkullMeta meta = (SkullMeta) head.getItemMeta();
                if (err == null && profile != null) meta.setPlayerProfile(profile);
                head.setItemMeta(meta);
                addOrDrop(p, head);
                m.msg(p, "skull.done", "name", name);
            }));
        }).playerOnly(true).usage("[玩家名]").description("获取玩家头颅"));

        // ---------------- itemname / lore ----------------
        m.reg((Cmd) m.cmd("itemname", "itemname", new String[]{"rename", "iname"}, (s, l, a) -> {
            Player p = m.self(s);
            ItemStack hand = hand(m, p);
            ItemMeta meta = hand.getItemMeta();
            if (a.length == 0) {
                meta.displayName(null);
                hand.setItemMeta(meta);
                m.msg(s, "itemname.reset");
                return;
            }
            meta.displayName(text(m, p, String.join(" ", a)));
            hand.setItemMeta(meta);
            m.msg(s, "itemname.done");
        }).playerOnly(true).usage("[名称，留空重置]").description("重命名手中物品（支持 MiniMessage / & 颜色）"));
        m.reg((Cmd) m.cmd("lore", "lore", NONE, (s, l, a) -> {
            Player p = m.self(s);
            if (a.length < 1) throw new Cmd.Usage("/" + l + " <add|set|insert|remove|clear> ...");
            ItemStack hand = hand(m, p);
            ItemMeta meta = hand.getItemMeta();
            List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
            String op = a[0].toLowerCase(Locale.ROOT);
            switch (op) {
                case "add" -> {
                    if (a.length < 2) throw new Cmd.Usage("/" + l + " add <文本>");
                    lore.add(text(m, p, join(a, 1)));
                }
                case "set", "insert" -> {
                    if (a.length < 3) throw new Cmd.Usage("/" + l + " " + op + " <行号> <文本>");
                    int line = m.parseInt(s, a[1]) - 1;
                    if (line < 0 || line > lore.size() || (op.equals("set") && line == lore.size())) {
                        m.msg(s, "lore.bad-line", "max", lore.size());
                        return;
                    }
                    if (op.equals("set")) lore.set(line, text(m, p, join(a, 2)));
                    else lore.add(line, text(m, p, join(a, 2)));
                }
                case "remove" -> {
                    int line = a.length > 1 ? m.parseInt(s, a[1]) - 1 : lore.size() - 1;
                    if (line < 0 || line >= lore.size()) { m.msg(s, "lore.bad-line", "max", lore.size()); return; }
                    lore.remove(line);
                }
                case "clear" -> lore.clear();
                default -> throw new Cmd.Usage("/" + l + " <add|set|insert|remove|clear> ...");
            }
            meta.lore(lore.isEmpty() ? null : lore);
            hand.setItemMeta(meta);
            m.msg(s, "lore.done");
        }).tab((s, a) -> a.length == 1 ? Cmd.opts(a[0], List.of("add", "set", "insert", "remove", "clear")) : List.of())
                .playerOnly(true).usage("<add|set|insert|remove|clear> [行号] [文本]").description("编辑物品描述"));

        // ---------------- book ----------------
        m.reg((Cmd) m.cmd("book", "book", NONE, (s, l, a) -> {
            Player p = m.self(s);
            ItemStack hand = hand(m, p);
            ItemMeta im = hand.getItemMeta();
            String sub = a.length > 0 ? a[0].toLowerCase(Locale.ROOT) : "";
            if (hand.getType() == Material.WRITTEN_BOOK && im instanceof BookMeta bm) {
                boolean own = bm.hasAuthor() && p.getName().equals(bm.getAuthor());
                switch (sub) {
                    case "author" -> {
                        if (!p.hasPermission("lc.essentials.book.author")) { m.msg(s, "no-permission-sub", "sub", "book author"); return; }
                        if (a.length < 2) throw new Cmd.Usage("/" + l + " author <作者>");
                        bm.setAuthor(a[1]);
                        hand.setItemMeta(bm);
                        m.msg(s, "book.author", "author", a[1]);
                    }
                    case "title" -> {
                        if (!own && !p.hasPermission("lc.essentials.book.others")) { m.msg(s, "book.not-owner"); return; }
                        if (a.length < 2) throw new Cmd.Usage("/" + l + " title <标题>");
                        bm.title(text(m, p, join(a, 1)));
                        hand.setItemMeta(bm);
                        m.msg(s, "book.title");
                    }
                    default -> {
                        // 解锁为可编辑的书与笔：Adventure 5 后 BookMeta 不再实现 Book，使用 pages() 取得 Component 页面
                        if (!own && !p.hasPermission("lc.essentials.book.others")) { m.msg(s, "book.not-owner"); return; }
                        List<String> pages = new ArrayList<>();
                        for (Component c : bm.pages()) pages.add(PLAIN.serialize(c));
                        ItemStack writable = new ItemStack(Material.WRITABLE_BOOK, hand.getAmount());
                        WritableBookMeta wm = (WritableBookMeta) writable.getItemMeta();
                        wm.setPages(pages);
                        writable.setItemMeta(wm);
                        p.getInventory().setItemInMainHand(writable);
                        m.msg(s, "book.unlocked");
                    }
                }
            } else if (hand.getType() == Material.WRITABLE_BOOK && im instanceof WritableBookMeta wm) {
                // 锁定为成书
                String title = a.length > 0 ? join(a, 0) : "Book";
                ItemStack written = new ItemStack(Material.WRITTEN_BOOK, hand.getAmount());
                BookMeta bm = (BookMeta) written.getItemMeta();
                bm.setTitle(title.length() > 32 ? title.substring(0, 32) : title);
                bm.setAuthor(p.getName());
                List<Component> pages = new ArrayList<>();
                for (String page : wm.getPages()) pages.add(Component.text(page));
                if (pages.isEmpty()) pages.add(Component.empty());
                bm.pages(pages);
                written.setItemMeta(bm);
                p.getInventory().setItemInMainHand(written);
                m.msg(s, "book.locked", "title", title);
            } else {
                m.msg(s, "book.not-book");
            }
        }).tab((s, a) -> a.length == 1 ? Cmd.opts(a[0], List.of("author", "title")) : List.of())
                .playerOnly(true).usage("[author|title] [值] | [标题]").description("解锁成书 / 锁定书与笔 / 修改署名"));

        // ---------------- condense ----------------
        m.reg((Cmd) m.cmd("condense", "condense", new String[]{"compact", "blocks"}, (s, l, a) -> {
            Player p = m.self(s);
            PlayerInventory inv = p.getInventory();
            int made = 0;
            for (var e : CONDENSE.entrySet()) {
                Material from = e.getKey();
                Material to = e.getValue().to;
                int ratio = e.getValue().ratio;
                int total = 0;
                for (ItemStack is : inv.getStorageContents()) {
                    if (is != null && is.getType() == from && isPlain(is)) total += is.getAmount();
                }
                int blocks = total / ratio;
                if (blocks <= 0) continue;
                removePlain(inv, from, blocks * ratio);
                addOrDrop(p, new ItemStack(to, blocks));
                made += blocks;
            }
            m.msg(s, made > 0 ? "condense.done" : "condense.none", "count", made);
        }).playerOnly(true).description("把背包中的矿物等压缩成方块"));

        // ---------------- unbreakable ----------------
        m.reg((Cmd) m.cmd("unbreakable", "unbreakable", NONE, (s, l, a) -> {
            Player p = m.self(s);
            ItemStack hand = hand(m, p);
            ItemMeta meta = hand.getItemMeta();
            boolean v = !meta.isUnbreakable();
            meta.setUnbreakable(v);
            hand.setItemMeta(meta);
            m.msg(s, "unbreakable", "state", m.onOff(v));
        }).playerOnly(true).description("切换手中物品无法破坏"));

        // ---------------- powertool ----------------
        m.reg((Cmd) m.cmd("powertool", "powertool", new String[]{"pt"}, (s, l, a) -> {
            Player p = m.self(s);
            UserData d = m.data(p);
            if (a.length > 0 && a[0].equalsIgnoreCase("clearall")) {
                d.powertools.clear();
                d.markDirty();
                m.msg(s, "powertool.cleared-all");
                return;
            }
            ItemStack hand = hand(m, p);
            String key = hand.getType().name();
            List<String> cmds = d.powertools.computeIfAbsent(key, k -> new ArrayList<>());
            if (a.length == 0 || a[0].equalsIgnoreCase("clear")) {
                d.powertools.remove(key);
                m.msg(s, "powertool.cleared", "item", key.toLowerCase(Locale.ROOT));
            } else if (a[0].equalsIgnoreCase("list")) {
                if (cmds.isEmpty()) m.msg(s, "powertool.empty");
                else m.msg(s, "powertool.list", "item", key.toLowerCase(Locale.ROOT), "commands", String.join(" | ", cmds));
                if (cmds.isEmpty()) d.powertools.remove(key);
                return;
            } else {
                String line = join(a, 0);
                boolean append = line.startsWith("a:");
                if (append) line = line.substring(2);
                if (!append) cmds.clear();
                cmds.add(line);
                m.msg(s, "powertool.set", "item", key.toLowerCase(Locale.ROOT), "command", line);
            }
            d.markDirty();
        }).tab((s, a) -> a.length == 1 ? Cmd.opts(a[0], List.of("list", "clear", "clearall", "a:")) : List.of())
                .playerOnly(true).usage("<命令|a:追加命令|list|clear|clearall>").description("为手中物品绑定命令"));

        // ---------------- recipe ----------------
        m.reg((Cmd) m.cmd("recipe", "recipe", new String[]{"formula", "recipes"}, (s, l, a) -> {
            Player p = m.self(s);
            Material mat = a.length > 0 ? material(m, s, a[0]) : hand(m, p).getType();
            var recipes = com.lunarboreal.essentials.feature.RecipeGui.recipesFor(new ItemStack(mat));
            if (recipes.isEmpty()) {
                m.msg(s, "recipe.none", "item", mat.name().toLowerCase(Locale.ROOT));
                return;
            }
            int page = a.length > 1 ? m.parseInt(s, a[1]) - 1 : 0;
            com.lunarboreal.essentials.feature.RecipeGui.open(m, p, recipes, page);
        }).tab((s, a) -> a.length == 1 ? materials(a[0]) : List.of())
                .playerOnly(true).usage("[物品] [页码]").description("查看物品合成配方"));

        // ---------------- 虚拟工作台 ----------------
        station(m, "workbench", new String[]{"craft", "wb"}, "workbench", MenuType.CRAFTING, p -> p.openWorkbench(null, true));
        station(m, "anvil", NONE, "anvil", MenuType.ANVIL, p -> p.openAnvil(null, true));
        station(m, "cartographytable", new String[]{"cartography", "ct"}, "cartographytable", MenuType.CARTOGRAPHY_TABLE, p -> p.openCartographyTable(null, true));
        station(m, "grindstone", NONE, "grindstone", MenuType.GRINDSTONE, p -> p.openGrindstone(null, true));
        station(m, "loom", NONE, "loom", MenuType.LOOM, p -> p.openLoom(null, true));
        station(m, "smithingtable", new String[]{"smithing"}, "smithingtable", MenuType.SMITHING, p -> p.openSmithingTable(null, true));
        station(m, "stonecutter", NONE, "stonecutter", MenuType.STONECUTTER, p -> p.openStonecutter(null, true));
        station(m, "enchantingtable", new String[]{"enchanttable", "etable"}, "enchantingtable", MenuType.ENCHANTMENT, p -> p.openEnchanting(null, true));
        m.reg((Cmd) m.cmd("disposal", "disposal", new String[]{"trash", "lcdisposal", "dispose"}, (s, l, a) ->
                Guis.openDisposal(m, m.self(s))).playerOnly(true).description("打开垃圾桶"));

        // ---------------- worth / sell ----------------
        if (m.config().getBoolean("worth.enabled", true)) {
            m.reg((Cmd) m.cmd("worth", "worth", new String[]{"price"}, (s, l, a) -> {
                Material mat;
                int amount;
                if (a.length > 0) {
                    mat = material(m, s, a[0]);
                    amount = a.length > 1 ? m.parseInt(s, a[1]) : 1;
                } else {
                    ItemStack hand = hand(m, m.self(s));
                    mat = hand.getType();
                    amount = hand.getAmount();
                }
                Double price = m.worth().price(mat);
                if (price == null) { m.msg(s, "worth.none", "item", mat.name().toLowerCase(Locale.ROOT)); return; }
                m.msg(s, "worth.show", "item", mat.name().toLowerCase(Locale.ROOT), "price", money(m, price),
                        "amount", amount, "total", money(m, price * amount));
            }).tab((s, a) -> a.length == 1 ? materials(a[0]) : List.of()).usage("[物品] [数量]").description("查询物品价格"));
            m.reg((Cmd) m.cmd("setworth", "setworth", NONE, (s, l, a) -> {
                if (a.length < 1) throw new Cmd.Usage("/" + l + " [物品] <价格>");
                Material mat;
                double price;
                if (a.length >= 2) {
                    mat = material(m, s, a[0]);
                    price = m.parseDouble(s, a[1]);
                } else {
                    mat = hand(m, m.self(s)).getType();
                    price = m.parseDouble(s, a[0]);
                }
                m.worth().set(mat, price);
                m.msg(s, "worth.set", "item", mat.name().toLowerCase(Locale.ROOT), "price", money(m, price));
                m.audit(m.actor(s), "setworth", mat.name() + "=" + price);
            }).tab((s, a) -> a.length == 1 ? materials(a[0]) : List.of()).usage("[物品] <价格>").description("设置物品价格（0 删除）"));
            m.reg((Cmd) m.cmd("sell", "sell", NONE, (s, l, a) -> {
                Player p = m.self(s);
                EconomyService eco = m.api().services().get(EconomyService.class).orElse(null);
                if (eco == null) { m.msg(s, "sell.no-economy"); return; }
                // 防刷：创造/旁观模式默认禁止出售
                if ((p.getGameMode() == org.bukkit.GameMode.CREATIVE || p.getGameMode() == org.bukkit.GameMode.SPECTATOR)
                        && !m.config().getBoolean("worth.allow-creative-sell", false)
                        && !p.hasPermission("lc.essentials.sell.creative")) {
                    m.msg(s, "sell.creative");
                    return;
                }
                String mode = a.length > 0 ? a[0].toLowerCase(Locale.ROOT) : "hand";
                PlayerInventory inv = p.getInventory();
                List<Integer> slots = new ArrayList<>();
                if (mode.equals("hand")) {
                    slots.add(inv.getHeldItemSlot());
                } else if (mode.equals("all") || mode.equals("inventory") || mode.equals("invent")) {
                    if (!p.hasPermission("lc.essentials.sell.all")) { m.msg(s, "no-permission-sub", "sub", "sell all"); return; }
                    for (int i = 0; i < 36; i++) slots.add(i);
                } else {
                    Material mat = material(m, s, a[0]);
                    for (int i = 0; i < 36; i++) {
                        ItemStack is = inv.getItem(i);
                        if (is != null && is.getType() == mat) slots.add(i);
                    }
                }
                // 1) 先计价，不改动背包
                double total = 0;
                int count = 0;
                Map<Integer, ItemStack> sold = new LinkedHashMap<>();
                for (int i : slots) {
                    ItemStack is = inv.getItem(i);
                    Double price = m.worth().sellPrice(is);
                    if (price == null) continue;
                    total += price * is.getAmount();
                    count += is.getAmount();
                    sold.put(i, is.clone());
                }
                if (count == 0) { m.msg(s, "sell.cannot"); return; }
                // 2) 移除物品（同步主线程内，deposit 失败则原样退回）
                for (int i : sold.keySet()) inv.setItem(i, null);
                BigDecimal amount = BigDecimal.valueOf(total).setScale(2, RoundingMode.HALF_DOWN);
                EconomyService.Result r;
                try {
                    r = eco.deposit(p.getUniqueId(), amount, "essentials:sell");
                } catch (RuntimeException ex) {
                    r = EconomyService.Result.fail(BigDecimal.ZERO, String.valueOf(ex.getMessage()));
                }
                if (r == null || !r.success()) {
                    for (var e : sold.entrySet()) {
                        ItemStack cur = inv.getItem(e.getKey());
                        if (cur == null || cur.getType().isAir()) inv.setItem(e.getKey(), e.getValue());
                        else addOrDrop(p, e.getValue());
                    }
                    m.msg(s, "sell.failed", "error", r == null ? "?" : String.valueOf(r.error()));
                    return;
                }
                m.msg(s, "sell.done", "count", count, "money", eco.format(amount));
                m.audit(p.getName(), "sell", count + " items = " + amount.toPlainString());
            }).tab((s, a) -> a.length == 1 ? Cmd.opts(a[0], List.of("hand", "all")) : List.of())
                    .playerOnly(true).usage("<hand|all|物品>").description("出售物品"));
        }
    }

    // ---------------- 虚拟工作台实现 ----------------

    @SuppressWarnings("deprecation")
    private static void station(EssentialsModule m, String name, String[] aliases, String perm,
                                MenuType.Typed<?, ?> type, java.util.function.Consumer<Player> fallback) {
        m.reg((Cmd) m.cmd(name, perm, aliases, (s, l, a) -> {
            Player p = m.self(s);
            if (!openMenu(p, type)) fallback.accept(p);
        }).playerOnly(true).description("打开虚拟" + name));
    }

    /** 使用 MenuType 构建器（不检查方块可达），失败返回 false 走 Bukkit 旧方法。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean openMenu(Player p, MenuType.Typed type) {
        try {
            Object builder = type.builder();
            if (builder instanceof org.bukkit.inventory.view.builder.LocationInventoryViewBuilder lb) {
                lb = lb.checkReachable(false).location(p.getLocation());
                InventoryView view = (InventoryView) lb.build(p);
                p.openInventory(view);
                return true;
            }
            InventoryView view = (InventoryView) type.create(p);
            p.openInventory(view);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------------- 工具 ----------------

    private record Condense(Material to, int ratio) {}

    private static final Map<Material, Condense> CONDENSE = new LinkedHashMap<>();

    static {
        c(Material.IRON_NUGGET, Material.IRON_INGOT, 9);
        c(Material.GOLD_NUGGET, Material.GOLD_INGOT, 9);
        c(Material.IRON_INGOT, Material.IRON_BLOCK, 9);
        c(Material.GOLD_INGOT, Material.GOLD_BLOCK, 9);
        c(Material.COPPER_INGOT, Material.COPPER_BLOCK, 9);
        c(Material.DIAMOND, Material.DIAMOND_BLOCK, 9);
        c(Material.EMERALD, Material.EMERALD_BLOCK, 9);
        c(Material.LAPIS_LAZULI, Material.LAPIS_BLOCK, 9);
        c(Material.REDSTONE, Material.REDSTONE_BLOCK, 9);
        c(Material.COAL, Material.COAL_BLOCK, 9);
        c(Material.NETHERITE_INGOT, Material.NETHERITE_BLOCK, 9);
        c(Material.RAW_IRON, Material.RAW_IRON_BLOCK, 9);
        c(Material.RAW_GOLD, Material.RAW_GOLD_BLOCK, 9);
        c(Material.RAW_COPPER, Material.RAW_COPPER_BLOCK, 9);
        c(Material.SLIME_BALL, Material.SLIME_BLOCK, 9);
        c(Material.BONE_MEAL, Material.BONE_BLOCK, 9);
        c(Material.WHEAT, Material.HAY_BLOCK, 9);
        c(Material.DRIED_KELP, Material.DRIED_KELP_BLOCK, 9);
        c(Material.QUARTZ, Material.QUARTZ_BLOCK, 4);
        c(Material.AMETHYST_SHARD, Material.AMETHYST_BLOCK, 4);
        c(Material.CLAY_BALL, Material.CLAY, 4);
        c(Material.SNOWBALL, Material.SNOW_BLOCK, 4);
        c(Material.GLOWSTONE_DUST, Material.GLOWSTONE, 4);
    }

    private static void c(Material from, Material to, int ratio) {
        CONDENSE.put(from, new Condense(to, ratio));
    }

    private static boolean isPlain(ItemStack is) {
        if (!is.hasItemMeta()) return true;
        ItemMeta meta = is.getItemMeta();
        return !meta.hasDisplayName() && !meta.hasLore() && !meta.hasEnchants();
    }

    private static void removePlain(PlayerInventory inv, Material mat, int amount) {
        ItemStack[] contents = inv.getStorageContents();
        for (int i = 0; i < contents.length && amount > 0; i++) {
            ItemStack is = contents[i];
            if (is == null || is.getType() != mat || !isPlain(is)) continue;
            int take = Math.min(amount, is.getAmount());
            amount -= take;
            if (take == is.getAmount()) contents[i] = null;
            else is.setAmount(is.getAmount() - take);
        }
        inv.setStorageContents(contents);
    }

    public static boolean repair(EssentialsModule m, Player p, ItemStack is) {
        if (is == null || is.getType().isAir() || !is.hasItemMeta()) return false;
        if (!(is.getItemMeta() instanceof Damageable dm) || !dm.hasDamage() || dm.getDamage() <= 0) return false;
        List<String> blacklist = m.config().getStringList("repair.blacklist");
        if (blacklist.stream().anyMatch(b -> b.equalsIgnoreCase(is.getType().name()))
                && !p.hasPermission("lc.essentials.repair.bypass")) return false;
        if (dm.hasEnchants() && !m.config().getBoolean("repair.allow-enchanted", true)
                && !p.hasPermission("lc.essentials.repair.enchanted")) return false;
        dm.setDamage(0);
        is.setItemMeta(dm);
        return true;
    }

    private static ItemStack hand(EssentialsModule m, Player p) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) {
            m.msg(p, "hand-empty");
            throw new Cmd.Stop();
        }
        return hand;
    }

    public static Material material(EssentialsModule m, CommandSender s, String name) {
        Material mat = parseMaterial(m, name);
        if (mat == null || !mat.isItem() || mat.isAir()) {
            m.msg(s, "invalid-item", "item", name);
            throw new Cmd.Stop();
        }
        return mat;
    }

    public static Material parseMaterial(EssentialsModule m, String name) {
        String alias = m.config().getString("item-aliases." + name.toLowerCase(Locale.ROOT));
        if (alias != null) name = alias;
        Material mat = Material.matchMaterial(name);
        if (mat == null) {
            try {
                var key = NamespacedKey.fromString(name.toLowerCase(Locale.ROOT));
                if (key != null) mat = Registry.MATERIAL.get(key);
            } catch (Exception ignored) {
                // 忽略
            }
        }
        return mat;
    }

    private static int defaultAmount(EssentialsModule m, Material mat) {
        int def = m.config().getInt("give.default-amount", 0);
        return def <= 0 ? mat.getMaxStackSize() : def;
    }

    public static void giveItem(EssentialsModule m, CommandSender s, Player t, Material mat, int amount) {
        if (amount <= 0) {
            m.msg(s, "invalid-number", "value", amount);
            return;
        }
        int max = m.config().getInt("give.max-amount", 2304);
        if (amount > max && !s.hasPermission("lc.essentials.give.bypass")) amount = max;
        List<String> blacklist = m.config().getStringList("give.blacklist");
        if (blacklist.stream().anyMatch(b -> b.equalsIgnoreCase(mat.name())) && !s.hasPermission("lc.essentials.give.bypass")) {
            m.msg(s, "give.blacklisted", "item", mat.name().toLowerCase(Locale.ROOT));
            return;
        }
        int left = amount;
        int stack = mat.getMaxStackSize();
        while (left > 0) {
            int n = Math.min(stack, left);
            addOrDrop(t, new ItemStack(mat, n));
            left -= n;
        }
        String item = mat.name().toLowerCase(Locale.ROOT);
        m.msg(s, "give.done", "player", t.getName(), "item", item, "amount", amount);
        if (!t.equals(s)) {
            m.msg(t, "give.received", "item", item, "amount", amount);
            m.audit(m.actor(s), "give", t.getName() + " " + item + " x" + amount);
        }
    }

    public static void addOrDrop(Player p, ItemStack is) {
        for (ItemStack left : p.getInventory().addItem(is).values()) {
            Location loc = p.getLocation();
            loc.getWorld().dropItemNaturally(loc, left);
        }
    }

    /** 26.x：优先通过 Paper RegistryAccess 获取附魔注册表，非 Paper 回退 Bukkit Registry。 */
    @SuppressWarnings("deprecation")
    public static Registry<Enchantment> enchantments() {
        try {
            return io.papermc.paper.registry.RegistryAccess.registryAccess()
                    .getRegistry(io.papermc.paper.registry.RegistryKey.ENCHANTMENT);
        } catch (Throwable t) {
            return Registry.ENCHANTMENT;
        }
    }

    public static Enchantment enchantment(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        NamespacedKey key = NamespacedKey.fromString(n.contains(":") ? n : "minecraft:" + n);
        Enchantment e = key == null ? null : enchantments().get(key);
        if (e == null) {
            for (Enchantment x : enchantments()) {
                if (x.getKey().getKey().replace("_", "").equals(n.replace("_", ""))) return x;
            }
        }
        return e;
    }

    /** 受限 MiniMessage：仅颜色/装饰/渐变/彩虹/重置，禁止 selector/nbt/score/font/click 等。 */
    private static final net.kyori.adventure.text.minimessage.MiniMessage SAFE_MM = net.kyori.adventure.text.minimessage.MiniMessage.builder()
            .tags(net.kyori.adventure.text.minimessage.tag.resolver.TagResolver.resolver(
                    net.kyori.adventure.text.minimessage.tag.standard.StandardTags.color(),
                    net.kyori.adventure.text.minimessage.tag.standard.StandardTags.decorations(),
                    net.kyori.adventure.text.minimessage.tag.standard.StandardTags.gradient(),
                    net.kyori.adventure.text.minimessage.tag.standard.StandardTags.rainbow(),
                    net.kyori.adventure.text.minimessage.tag.standard.StandardTags.reset()))
            .build();

    private static Component text(EssentialsModule m, Player p, String raw) {
        Component c;
        if (!p.hasPermission("lc.essentials.itemname.color")) {
            // 无格式权限：纯文本
            c = Component.text(raw);
        } else if (raw.indexOf('<') >= 0 && raw.indexOf('>') > raw.indexOf('<')) {
            c = SAFE_MM.deserialize(raw);
        } else {
            c = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand().deserialize(raw);
        }
        return c.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    private static String join(String[] a, int from) {
        return String.join(" ", Arrays.copyOfRange(a, from, a.length));
    }

    static List<String> materials(String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        if (p.isEmpty()) return out;
        for (Material mat : Material.values()) {
            if (mat.isLegacy() || !mat.isItem() || mat.isAir()) continue;
            String n = mat.name().toLowerCase(Locale.ROOT);
            if (n.startsWith(p)) {
                out.add(n);
                if (out.size() >= 60) break;
            }
        }
        return out;
    }

    static String money(EssentialsModule m, double v) {
        BigDecimal bd = BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
        return m.api().services().get(EconomyService.class).map(e -> e.format(bd)).orElse(bd.toPlainString());
    }

    @SuppressWarnings("unused")
    private static <T> Function<T, T> id() {
        return Function.identity();
    }
}
