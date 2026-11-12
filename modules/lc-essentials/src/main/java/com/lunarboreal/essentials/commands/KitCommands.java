package com.lunarboreal.essentials.commands;

import com.lunarboreal.api.util.Durations;
import com.lunarboreal.essentials.Cmd;
import com.lunarboreal.essentials.EssentialsModule;
import com.lunarboreal.essentials.feature.Guis;
import com.lunarboreal.essentials.feature.KitManager;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** 礼包命令：/kit /kits /createkit /delkit /kitpreview。 */
public final class KitCommands {

    private KitCommands() {}

    private static final String[] NONE = new String[0];

    public static void register(EssentialsModule m) {
        if (!m.config().getBoolean("kits.enabled", true)) return;

        m.reg((Cmd) m.cmd("kit", "kit", NONE, (s, l, a) -> {
            if (a.length == 0) {
                if (s instanceof Player p && m.config().getBoolean("kits.gui", true)) Guis.openKitList(m, p);
                else listKits(m, s);
                return;
            }
            KitManager.Kit k = m.kits().get(a[0]);
            if (k == null) { m.msg(s, "kit.not-found", "kit", a[0]); return; }
            if (a.length > 1) {
                if (!s.hasPermission("lc.essentials.kit.others")) { m.msg(s, "no-permission-others"); return; }
                Player t = m.online(s, a[1]);
                m.kits().give(t, k, false);
                m.msg(s, "kit.given", "kit", k.name, "player", t.getName());
                m.msg(t, "kit.received", "kit", display(k));
                m.audit(m.actor(s), "kit-give", k.name + " -> " + t.getName());
                return;
            }
            Player p = m.self(s);
            switch (m.kits().give(p, k, true)) {
                case OK -> m.msg(s, "kit.received", "kit", display(k));
                case NO_PERMISSION -> m.msg(s, "kit.no-permission", "kit", k.name);
                case ALREADY_CLAIMED -> m.msg(s, "kit.already", "kit", k.name);
                case LOADING -> m.msg(s, "data-loading");
                case COOLDOWN -> m.msg(s, "kit.cooldown", "kit", k.name, "time", Durations.format(m.kits().remaining(p, k)));
                default -> m.msg(s, "kit.not-found", "kit", a[0]);
            }
        }).tab((s, a) -> a.length == 1 ? Cmd.opts(a[0], usable(m, s)) : a.length == 2 ? Cmd.players(a[1]) : List.of())
                .usage("[礼包] [玩家]").description("领取礼包（无参数打开 GUI）"));

        m.reg((Cmd) m.cmd("kits", "kit", new String[]{"kitlist"}, (s, l, a) -> listKits(m, s)).usage("").description("礼包列表"));

        m.reg((Cmd) m.cmd("kitpreview", "kit", new String[]{"previewkit"}, (s, l, a) -> {
            Player p = m.self(s);
            if (a.length < 1) throw new Cmd.Usage("/" + l + " <礼包>");
            KitManager.Kit k = m.kits().get(a[0]);
            if (k == null) { m.msg(s, "kit.not-found", "kit", a[0]); return; }
            Guis.openKitPreview(m, p, k);
        }).tab((s, a) -> a.length == 1 ? Cmd.opts(a[0], usable(m, s)) : List.of()).playerOnly(true).usage("<礼包>").description("预览礼包"));

        m.reg((Cmd) m.cmd("createkit", "createkit", new String[]{"kitcreate", "setkit"}, (s, l, a) -> {
            Player p = m.self(s);
            if (a.length < 1) throw new Cmd.Usage("/" + l + " <名称> [冷却如 1d|0] [once] [firstjoin]");
            String name = a[0].toLowerCase();
            if (!KitManager.validName(name)) { m.msg(s, "kit.invalid-name"); return; }
            long cd = 0;
            if (a.length > 1 && !a[1].equals("0")) {
                try {
                    Long ms = Durations.parse(a[1]);
                    cd = ms == null ? 0 : ms;
                } catch (IllegalArgumentException e) {
                    m.msg(s, "kit.invalid-cooldown", "value", a[1]);
                    return;
                }
            }
            KitManager.Kit k = new KitManager.Kit(name);
            k.cooldown = cd;
            k.display = name;
            for (int i = 2; i < a.length; i++) {
                if (a[i].equalsIgnoreCase("once")) k.once = true;
                if (a[i].equalsIgnoreCase("firstjoin")) k.firstJoin = true;
                if (a[i].equalsIgnoreCase("nopermission")) k.requirePermission = false;
            }
            List<ItemStack> items = new ArrayList<>();
            for (ItemStack is : p.getInventory().getContents()) {
                if (is != null && !is.getType().isAir()) items.add(is.clone());
            }
            if (items.isEmpty()) { m.msg(s, "kit.empty-inventory"); return; }
            k.items = items;
            ItemStack hand = p.getInventory().getItemInMainHand();
            if (!hand.getType().isAir()) k.icon = hand.getType();
            KitManager.Kit old = m.kits().get(name);
            m.kits().put(k);
            m.msg(s, old == null ? "kit.created" : "kit.updated", "kit", name, "count", items.size(),
                    "cooldown", cd > 0 ? Durations.format(cd) : (Object) com.lunarboreal.api.message.MessageService.rich(m.raw("kit.no-cooldown")));
            m.audit(m.actor(s), "kit-create", name + " items=" + items.size());
        }).tab((s, a) -> a.length == 2 ? Cmd.opts(a[1], List.of("0", "1h", "1d", "7d"))
                : a.length >= 3 ? Cmd.opts(a[a.length - 1], List.of("once", "firstjoin", "nopermission")) : List.of())
                .playerOnly(true).usage("<名称> [冷却] [once] [firstjoin] [nopermission]").description("从当前背包创建礼包"));

        m.reg((Cmd) m.cmd("delkit", "delkit", new String[]{"deletekit", "removekit"}, (s, l, a) -> {
            if (a.length < 1) throw new Cmd.Usage("/" + l + " <名称>");
            if (m.kits().remove(a[0])) {
                m.msg(s, "kit.deleted", "kit", a[0]);
                m.audit(m.actor(s), "kit-delete", a[0]);
            } else m.msg(s, "kit.not-found", "kit", a[0]);
        }).tab((s, a) -> a.length == 1 ? Cmd.opts(a[0], allNames(m)) : List.of()).usage("<名称>").description("删除礼包"));
    }

    private static com.lunarboreal.api.message.MessageService.Rich display(KitManager.Kit k) {
        return com.lunarboreal.api.message.MessageService.rich(k.display == null ? k.name : k.display);
    }

    private static void listKits(EssentialsModule m, CommandSender s) {
        List<String> names = usable(m, s);
        if (names.isEmpty()) m.msg(s, "kit.list-empty");
        else m.msg(s, "kit.list", "kits", String.join(", ", names));
    }

    private static List<String> usable(EssentialsModule m, CommandSender s) {
        List<String> out = new ArrayList<>();
        for (KitManager.Kit k : m.kits().all()) {
            if (!(s instanceof Player p) || m.kits().canUse(p, k)) out.add(k.name);
        }
        return out;
    }

    private static List<String> allNames(EssentialsModule m) {
        List<String> out = new ArrayList<>();
        for (KitManager.Kit k : m.kits().all()) out.add(k.name);
        return out;
    }
}
