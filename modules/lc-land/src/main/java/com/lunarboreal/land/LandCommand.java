package com.lunarboreal.land;

import com.lunarboreal.api.command.LcCommand;
import com.lunarboreal.api.message.MessageService;
import com.lunarboreal.api.service.EconomyService;
import com.lunarboreal.api.service.LandService.Flag;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.BlockFace;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class LandCommand extends LcCommand {

    private static final List<String> SUBS = List.of("help", "gui", "pos1", "pos2", "create", "delete", "info", "list", "rename",
            "settp", "tp", "trust", "untrust", "flag", "expand", "sell", "buy", "transfer", "show", "message", "admin");
    private static final List<String> ADMIN_SUBS = List.of("delete", "tp", "list", "bypass");
    private static final List<String> DIRS = List.of("north", "south", "east", "west", "facing");

    private final LandModule m;
    LandGui gui;
    /** 待确认删除：uuid → (land, 过期时间)。 */
    private final Map<UUID, Object[]> pendingDelete = new ConcurrentHashMap<>();

    LandCommand(LandModule module) {
        super("land", "lc.land.use", "lands", "ld", "res");
        this.m = module;
        description("领地系统");
        usage("/land help");
    }

    @Override
    public void execute(CommandSender sender, String label, String[] args) {
        if (!m.loaded()) {
            m.msg(sender, "error.loading");
            return;
        }
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        String[] rest = args.length == 0 ? new String[0] : Arrays.copyOfRange(args, 1, args.length);
        if (sub.equals("admin")) {
            admin(sender, rest);
            return;
        }
        if (sub.equals("help")) {
            m.msg(sender, "help");
            return;
        }
        if (sub.equals("list")) {
            list(sender, rest);
            return;
        }
        if (!(sender instanceof Player p)) {
            m.msg(sender, "error.player-only");
            return;
        }
        switch (sub) {
            case "gui" -> {
                if (rest.length == 2 && rest[0].equalsIgnoreCase("sell")) {
                    Land land = m.manager().at(p.getLocation());
                    BigDecimal price = parseMoney(rest[1]);
                    if (land != null && price != null && requireOwner(p, land) && requireNotFrozen(p, land))
                        gui.confirmSale(p, land, price);
                    else p.sendMessage("/land gui sell <price> (stand inside your land)");
                } else gui.openList(p, 0);
            }
            case "pos1", "pos2" -> m.session().setPos(p, p.getLocation(), sub.equals("pos1") ? 0 : 1);
            case "create" -> create(p, rest);
            case "delete", "remove" -> delete(p, rest);
            case "info" -> info(p, rest);
            case "rename" -> rename(p, rest);
            case "settp" -> settp(p);
            case "tp" -> tp(p, rest);
            case "trust" -> trust(p, rest);
            case "untrust" -> untrust(p, rest);
            case "flag" -> flag(p, rest);
            case "expand" -> expand(p, rest);
            case "sell" -> sell(p, rest);
            case "buy" -> buy(p, rest);
            case "transfer" -> transfer(p, rest);
            case "show" -> show(p, rest);
            case "message", "msg" -> message(p, rest);
            default -> m.msg(p, "help");
        }
    }

    // ------------------------------------------------------------ 工具

    /** 解析目标领地：args[idx] 为领地名，否则为脚下领地。失败时已提示。 */
    private Land target(Player p, String[] args, int idx) {
        if (args.length > idx) {
            Land l = m.manager().byName(args[idx]);
            if (l == null) m.msg(p, "error.no-such-land", "land", args[idx]);
            return l;
        }
        Land l = m.manager().at(p.getLocation());
        if (l == null) m.msg(p, "error.not-in-land");
        return l;
    }

    private boolean requireOwner(Player p, Land land) {
        if (m.isOwnerOrBypass(p, land)) return true;
        m.msg(p, "error.not-owner", "land", land.name);
        return false;
    }

    private boolean requireManager(Player p, Land land) {
        if (m.canManage(p, land)) return true;
        m.msg(p, "error.not-manager", "land", land.name);
        return false;
    }

    private boolean requireNotFrozen(Player p, Land land) {
        if (!land.frozen || m.isBypassing(p)) return true;
        m.msg(p, "error.frozen", "land", land.name);
        return false;
    }

    private boolean validName(Player p, String name) {
        if (!m.settings().namePattern.matcher(name).matches()) {
            m.msg(p, "error.invalid-name");
            return false;
        }
        if (m.manager().byName(name) != null) {
            m.msg(p, "error.name-taken", "land", name);
            return false;
        }
        return true;
    }

    private static BigDecimal parseMoney(String s) {
        try {
            BigDecimal d = new BigDecimal(s);
            return d.signum() < 0 ? null : d.setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ------------------------------------------------------------ create / delete

    private void create(Player p, String[] a) {
        if (!p.hasPermission("lc.land.create")) {
            m.msg(p, "error.no-permission");
            return;
        }
        if (a.length < 1) {
            m.msg(p, "usage.create");
            return;
        }
        Location[] sel = m.selections.get(p.getUniqueId());
        if (sel == null || sel[0] == null || sel[1] == null) {
            m.msg(p, "error.no-selection", "tool", m.settings().tool.name());
            return;
        }
        if (sel[0].getWorld() != sel[1].getWorld() || sel[0].getWorld() == null) {
            m.msg(p, "error.no-selection", "tool", m.settings().tool.name());
            return;
        }
        String name = a[0];
        if (!validName(p, name)) return;
        String world = sel[0].getWorld().getName();
        int minX = Math.min(sel[0].getBlockX(), sel[1].getBlockX()), maxX = Math.max(sel[0].getBlockX(), sel[1].getBlockX());
        int minZ = Math.min(sel[0].getBlockZ(), sel[1].getBlockZ()), maxZ = Math.max(sel[0].getBlockZ(), sel[1].getBlockZ());
        if (!m.isBypassing(p)) {
            int max = m.maxLands(p);
            if (max >= 0 && m.manager().ownedBy(p.getUniqueId()).size() >= max) {
                m.msg(p, "error.max-lands", "max", max);
                return;
            }
        }
        int minY = Math.min(sel[0].getBlockY(), sel[1].getBlockY()), maxY = Math.max(sel[0].getBlockY(), sel[1].getBlockY());
        Object[] err = m.validateArea(p, world, minX, minY, minZ, maxX, maxY, maxZ, null);
        if (err != null) {
            m.msg(p, (String) err[0], Arrays.copyOfRange(err, 1, err.length));
            return;
        }
        long area = (long) (maxX - minX + 1) * (maxZ - minZ + 1);
        BigDecimal cost = m.cost(world, area, minY, maxY);
        if (!m.charge(p, cost, "创建领地 " + name)) return;
        BigDecimal paid = m.economy().isPresent() ? cost : BigDecimal.ZERO;
        Land land = m.createLand(p, name, world, minX, minY, minZ, maxX, maxY, maxZ, paid);
        m.selections.remove(p.getUniqueId());
        m.msg(p, "create.success", "land", name, "area", area, "cost", m.fmt(cost));
        m.visualizer().show(p, world, land.minX, land.minY, land.minZ, land.maxX, land.maxY, land.maxZ, m.settings().showSeconds, Visualizer.OWN);
        m.audit(p.getName(), "create", name + " " + world + " " + land.coords() + " area=" + area + " cost=" + cost);
    }

    private void delete(Player p, String[] a) {
        boolean confirm = a.length > 0 && a[a.length - 1].equalsIgnoreCase("confirm");
        if (confirm) {
            Object[] pending = pendingDelete.remove(p.getUniqueId());
            if (pending == null || (long) pending[1] < System.currentTimeMillis()) {
                m.msg(p, "delete.no-pending");
                return;
            }
            Land land = (Land) pending[0];
            if (m.manager().byName(land.name) != land) {
                m.msg(p, "error.no-such-land", "land", land.name);
                return;
            }
            if (!requireOwner(p, land)) return;
            BigDecimal refund = land.paid.multiply(BigDecimal.valueOf(m.settings().refundRatio)).setScale(2, RoundingMode.HALF_UP);
            m.deleteLand(land);
            m.refund(land.owner, refund, "删除领地 " + land.name + " 退款");
            m.msg(p, "delete.success", "land", land.name, "refund", m.fmt(refund));
            m.audit(p.getName(), "delete", land.name + " owner=" + land.ownerName + " refund=" + refund);
            return;
        }
        Land land = target(p, a, 0);
        if (land == null || !requireOwner(p, land)) return;
        BigDecimal refund = land.paid.multiply(BigDecimal.valueOf(m.settings().refundRatio)).setScale(2, RoundingMode.HALF_UP);
        pendingDelete.put(p.getUniqueId(), new Object[]{land, System.currentTimeMillis() + 30_000});
        m.msg(p, "delete.confirm", "land", land.name, "refund", m.fmt(refund));
    }

    // ------------------------------------------------------------ info / list

    private void info(Player p, String[] a) {
        Land land = target(p, a, 0);
        if (land == null) return;
        sendInfo(p, land);
    }

    void sendInfo(CommandSender s, Land land) {
        List<String> mem = new ArrayList<>(), mgr = new ArrayList<>();
        for (Land.Member mb : land.members.values()) (mb.role() == Land.Role.MANAGER ? mgr : mem).add(mb.name());
        StringBuilder g = new StringBuilder(), mf = new StringBuilder();
        for (Flag f : Flag.values()) {
            g.append(land.globalFlag(f) ? "<green>" : "<red>").append(f.name()).append(' ');
            if (Settings.PLAYER_FLAGS.contains(f)) mf.append(land.memberFlag(f) ? "<green>" : "<red>").append(f.name()).append(' ');
        }
        m.msg(s, "info", "land", land.name, "id", land.id, "owner", land.ownerName, "world", land.world,
                "coords", land.coords(), "width", land.width(), "length", land.length(), "area", land.area(),
                "created", new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(land.createdAt)),
                "managers", mgr.isEmpty() ? "-" : String.join(", ", mgr),
                "members", mem.isEmpty() ? "-" : String.join(", ", mem),
                "global_flags", MessageService.rich(g.toString().trim()), "member_flags", MessageService.rich(mf.toString().trim()),
                "sale", land.salePrice == null ? "-" : m.fmt(land.salePrice),
                "frozen", MessageService.rich(land.frozen ? m.raw("word.yes") : m.raw("word.no")));
    }

    private void list(CommandSender s, String[] a) {
        UUID target;
        String name;
        if (a.length > 0) {
            if (s instanceof Player pl && !pl.getName().equalsIgnoreCase(a[0]) && !s.hasPermission("lc.land.list.others")) {
                m.msg(s, "error.no-permission");
                return;
            }
            target = m.resolvePlayer(a[0]);
            if (target == null) {
                m.msg(s, "error.unknown-player", "player", a[0]);
                return;
            }
            name = a[0];
        } else if (s instanceof Player pl) {
            target = pl.getUniqueId();
            name = pl.getName();
        } else {
            m.msg(s, "usage.list");
            return;
        }
        sendList(s, name, m.manager().ownedBy(target));
    }

    private void sendList(CommandSender s, String name, List<Land> lands) {
        long total = 0;
        for (Land l : lands) total += l.area();
        m.msg(s, "list.header", "player", name, "count", lands.size(), "area", total);
        for (Land l : lands) {
            m.msg(s, "list.entry", "land", l.name, "world", l.world, "coords", l.coords(), "area", l.area(),
                    "owner", l.ownerName, "sale", MessageService.rich(l.salePrice == null ? "" : m.raw("list.on-sale", "price", m.fmt(l.salePrice))));
        }
    }

    // ------------------------------------------------------------ 设置类

    private void rename(Player p, String[] a) {
        if (a.length < 1) {
            m.msg(p, "usage.rename");
            return;
        }
        Land land = a.length >= 2 ? target(p, a, 0) : target(p, a, 99);
        if (land == null || !requireOwner(p, land) || !requireNotFrozen(p, land)) return;
        String newName = a[a.length - 1];
        if (!validName(p, newName)) return;
        String old = land.name;
        m.manager().rename(land, newName);
        m.storage().save(land);
        m.msg(p, "rename.success", "old", old, "land", newName);
        m.audit(p.getName(), "rename", old + " -> " + newName);
    }

    private void settp(Player p) {
        Land land = target(p, new String[0], 0);
        if (land == null || !requireManager(p, land) || !requireNotFrozen(p, land)) return;
        Location l = p.getLocation();
        land.tp = new double[]{l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch()};
        m.storage().save(land);
        m.msg(p, "settp.success", "land", land.name);
    }

    private void tp(Player p, String[] a) {
        if (a.length < 1) {
            m.msg(p, "usage.tp");
            return;
        }
        Land land = m.manager().byName(a[0]);
        if (land == null) {
            m.msg(p, "error.no-such-land", "land", a[0]);
            return;
        }
        if (land.isOwner(p.getUniqueId()) && !requireNotFrozen(p, land)) return;
        if (!m.can(p, land, Flag.TELEPORT)) {
            m.msg(p, "protect.teleport", "land", land.name);
            return;
        }
        Location to = land.teleportLocation();
        if (to == null) {
            m.msg(p, "error.world-missing");
            return;
        }
        p.teleport(to);
        m.msg(p, "tp.success", "land", land.name);
    }

    private void trust(Player p, String[] a) {
        if (a.length < 1) {
            m.msg(p, "usage.trust");
            return;
        }
        Land land = m.manager().at(p.getLocation());
        if (land == null) {
            m.msg(p, "error.not-in-land");
            return;
        }
        if (!requireManager(p, land) || !requireNotFrozen(p, land)) return;
        Land.Role role = Land.Role.MEMBER;
        if (a.length >= 2) {
            String r = a[1].toLowerCase(Locale.ROOT);
            if (r.equals("manager") || r.equals("admin") || r.equals("管理员")) role = Land.Role.MANAGER;
            else if (!(r.equals("member") || r.equals("成员"))) {
                m.msg(p, "usage.trust");
                return;
            }
        }
        if (role == Land.Role.MANAGER && !m.isOwnerOrBypass(p, land)) {
            m.msg(p, "error.not-owner", "land", land.name);
            return;
        }
        UUID u = m.resolvePlayer(a[0]);
        if (u == null) {
            m.msg(p, "error.unknown-player", "player", a[0]);
            return;
        }
        if (land.isOwner(u)) {
            m.msg(p, "trust.is-owner");
            return;
        }
        Land.Member existing = land.members.get(u);
        if (existing != null && existing.role() == Land.Role.MANAGER && !m.isOwnerOrBypass(p, land)) {
            m.msg(p, "error.not-owner", "land", land.name);
            return;
        }
        String name = m.nameOf(u);
        land.members.put(u, new Land.Member(u, name, role));
        m.storage().saveMembers(land);
        m.msg(p, "trust.success", "player", name, "land", land.name, "role", MessageService.rich(m.raw("role." + role.name().toLowerCase(Locale.ROOT))));
        Player t = Bukkit.getPlayer(u);
        if (t != null) m.msg(t, "trust.notify", "land", land.name, "owner", land.ownerName);
        m.audit(p.getName(), "trust", land.name + " " + name + " " + role);
    }

    private void untrust(Player p, String[] a) {
        if (a.length < 1) {
            m.msg(p, "usage.untrust");
            return;
        }
        Land land = m.manager().at(p.getLocation());
        if (land == null) {
            m.msg(p, "error.not-in-land");
            return;
        }
        if (!requireManager(p, land)) return;
        UUID u = m.resolvePlayer(a[0]);
        Land.Member mb = u == null ? null : land.members.get(u);
        if (mb == null) {
            for (Land.Member x : land.members.values()) if (x.name() != null && x.name().equalsIgnoreCase(a[0])) mb = x;
        }
        if (mb == null) {
            m.msg(p, "untrust.not-member", "player", a[0]);
            return;
        }
        if (mb.role() == Land.Role.MANAGER && !m.isOwnerOrBypass(p, land)) {
            m.msg(p, "error.not-owner", "land", land.name);
            return;
        }
        land.members.remove(mb.uuid());
        m.storage().saveMembers(land);
        m.msg(p, "untrust.success", "player", mb.name(), "land", land.name);
        m.audit(p.getName(), "untrust", land.name + " " + mb.name());
    }

    private void flag(Player p, String[] a) {
        Land land = m.manager().at(p.getLocation());
        if (land == null) {
            m.msg(p, "error.not-in-land");
            return;
        }
        if (a.length < 2) {
            sendInfo(p, land);
            m.msg(p, "usage.flag");
            return;
        }
        if (!requireManager(p, land) || !requireNotFrozen(p, land)) return;
        Flag f;
        try {
            f = Flag.valueOf(a[0].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            m.msg(p, "flag.unknown", "flags", Arrays.toString(Flag.values()));
            return;
        }
        Boolean v = switch (a[1].toLowerCase(Locale.ROOT)) {
            case "true", "allow", "on", "yes", "1" -> true;
            case "false", "deny", "off", "no", "0" -> false;
            default -> null;
        };
        if (v == null) {
            m.msg(p, "usage.flag");
            return;
        }
        boolean member = a.length >= 3 && (a[2].equalsIgnoreCase("member") || a[2].equals("成员"));
        if (member && !Settings.PLAYER_FLAGS.contains(f)) {
            m.msg(p, "flag.not-member-flag", "flag", f.name());
            return;
        }
        (member ? land.memberFlags : land.globalFlags).put(f, v);
        m.storage().saveFlags(land);
        m.msg(p, "flag.success", "land", land.name, "flag", f.name(), "value", String.valueOf(v),
                "scope", MessageService.rich(m.raw(member ? "scope.member" : "scope.global")));
        m.audit(p.getName(), "flag", land.name + " " + (member ? "member." : "global.") + f + "=" + v);
    }

    private void expand(Player p, String[] a) {
        if (a.length < 1) {
            m.msg(p, "usage.expand");
            return;
        }
        Land land = m.manager().at(p.getLocation());
        if (land == null) {
            m.msg(p, "error.not-in-land");
            return;
        }
        if (!requireOwner(p, land) || !requireNotFrozen(p, land)) return;
        String dirArg = a.length >= 2 ? a[0].toLowerCase(Locale.ROOT) : "facing";
        int amount;
        try {
            amount = Integer.parseInt(a[a.length - 1]);
        } catch (NumberFormatException e) {
            m.msg(p, "usage.expand");
            return;
        }
        if (amount == 0) {
            m.msg(p, "usage.expand");
            return;
        }
        BlockFace face = switch (dirArg) {
            case "north", "n", "北" -> BlockFace.NORTH;
            case "south", "s", "南" -> BlockFace.SOUTH;
            case "east", "e", "东" -> BlockFace.EAST;
            case "west", "w", "西" -> BlockFace.WEST;
            case "facing", "f", "前" -> facing(p);
            default -> null;
        };
        if (face == null) {
            m.msg(p, "usage.expand");
            return;
        }
        int minX = land.minX, minZ = land.minZ, maxX = land.maxX, maxZ = land.maxZ;
        switch (face) {
            case NORTH -> minZ -= amount;
            case SOUTH -> maxZ += amount;
            case EAST -> maxX += amount;
            case WEST -> minX -= amount;
            default -> {
            }
        }
        if (minX > maxX || minZ > maxZ) {
            m.msg(p, "error.too-small", "min", m.settings().minSide);
            return;
        }
        Object[] err = m.validateArea(p, land.world, minX, land.minY, minZ, maxX, land.maxY, maxZ, land);
        if (err != null) {
            m.msg(p, (String) err[0], Arrays.copyOfRange(err, 1, err.length));
            return;
        }
        long newArea = (long) (maxX - minX + 1) * (maxZ - minZ + 1);
        long delta = newArea - land.area();
        BigDecimal cost = delta > 0 ? m.cost(land.world, delta, land.minY, land.maxY) : BigDecimal.ZERO;
        if (!m.charge(p, cost, "扩建领地 " + land.name)) return;
        if (m.economy().isPresent()) land.paid = land.paid.add(cost);
        m.manager().beginResize(land);
        land.minX = minX;
        land.minZ = minZ;
        land.maxX = maxX;
        land.maxZ = maxZ;
        m.manager().endResize(land);
        m.storage().save(land);
        m.msg(p, "expand.success", "land", land.name, "dir", face.name().toLowerCase(Locale.ROOT), "amount", amount,
                "area", newArea, "cost", m.fmt(cost));
        m.visualizer().show(p, land.world, minX, land.minY, minZ, maxX, land.maxY, maxZ, m.settings().showSeconds, Visualizer.OWN);
        m.audit(p.getName(), "expand", land.name + " " + face + " " + amount + " area=" + newArea + " cost=" + cost);
    }

    private static BlockFace facing(Player p) {
        float yaw = (p.getLocation().getYaw() % 360 + 360) % 360;
        if (yaw >= 45 && yaw < 135) return BlockFace.WEST;
        if (yaw >= 135 && yaw < 225) return BlockFace.NORTH;
        if (yaw >= 225 && yaw < 315) return BlockFace.EAST;
        return BlockFace.SOUTH;
    }

    // ------------------------------------------------------------ 交易

    private void sell(Player p, String[] a) {
        if (a.length < 1) {
            m.msg(p, "usage.sell");
            return;
        }
        Land land = m.manager().at(p.getLocation());
        if (land == null) {
            m.msg(p, "error.not-in-land");
            return;
        }
        if (!requireOwner(p, land) || !requireNotFrozen(p, land)) return;
        if (a[0].equalsIgnoreCase("cancel") || a[0].equals("取消")) {
            land.salePrice = null;
            m.storage().save(land);
            m.msg(p, "sell.cancelled", "land", land.name);
            return;
        }
        if (m.economy().isEmpty()) {
            m.msg(p, "error.no-economy");
            return;
        }
        BigDecimal price = parseMoney(a[0]);
        if (price == null) {
            m.msg(p, "usage.sell");
            return;
        }
        land.salePrice = price;
        m.storage().save(land);
        m.msg(p, "sell.success", "land", land.name, "price", m.fmt(price),
                "tax", BigDecimal.valueOf(m.settings().saleTax * 100).setScale(1, RoundingMode.HALF_UP).toPlainString());
        m.audit(p.getName(), "sell", land.name + " price=" + price);
    }

    private void buy(Player p, String[] a) {
        Land land = target(p, a, 0);
        if (land == null) return;
        if (land.salePrice == null) {
            m.msg(p, "buy.not-for-sale", "land", land.name);
            return;
        }
        if (land.isOwner(p.getUniqueId())) {
            m.msg(p, "buy.own-land");
            return;
        }
        Optional<EconomyService> eco = m.economy();
        if (eco.isEmpty()) {
            m.msg(p, "error.no-economy");
            return;
        }
        int max = m.maxLands(p);
        if (max >= 0 && m.manager().ownedBy(p.getUniqueId()).size() >= max) {
            m.msg(p, "error.max-lands", "max", max);
            return;
        }
        if (m.manager().totalArea(p.getUniqueId()) + land.area() > m.maxArea(p)) {
            m.msg(p, "error.total-area", "max", m.maxArea(p));
            return;
        }
        BigDecimal price = land.salePrice;
        BigDecimal tax = price.multiply(BigDecimal.valueOf(m.settings().saleTax)).setScale(2, RoundingMode.HALF_UP);
        BigDecimal toSeller = price.subtract(tax);
        UUID oldOwner = land.owner;
        String oldName = land.ownerName;
        // Transfer checks both accounts atomically (including the seller's balance cap).
        // Reserve tax first; if settlement fails, do not transfer ownership.
        if (tax.signum() > 0 && !eco.get().withdraw(p.getUniqueId(), tax, "领地交易税 " + land.name).success()) {
            m.msg(p, "buy.payment-failed");
            return;
        }
        EconomyService.Result r = toSeller.signum() > 0
                ? eco.get().transfer(p.getUniqueId(), oldOwner, toSeller, "购买领地 " + land.name)
                : EconomyService.Result.ok(eco.get().balance(p.getUniqueId()));
        if (!r.success()) {
            if (tax.signum() > 0 && !eco.get().deposit(p.getUniqueId(), tax, "领地购买失败退税 " + land.name).success()) {
                m.logger().severe("领地购买退税失败，需人工核账: buyer=" + p.getUniqueId() + " land=" + land.name + " tax=" + tax);
            }
            m.msg(p, "buy.payment-failed");
            return;
        }
        m.manager().changeOwner(land, p.getUniqueId(), p.getName());
        land.salePrice = null;
        land.members.clear();
        land.frozen = false;
        land.frozenSince = 0;
        land.lastTax = System.currentTimeMillis();
        m.storage().save(land);
        m.storage().saveMembers(land);
        m.msg(p, "buy.success", "land", land.name, "price", m.fmt(price));
        Player seller = Bukkit.getPlayer(oldOwner);
        if (seller != null) m.msg(seller, "buy.sold", "land", land.name, "player", p.getName(), "amount", m.fmt(toSeller), "tax", m.fmt(tax));
        m.audit(p.getName(), "buy", land.name + " from=" + oldName + " price=" + price + " tax=" + tax);
    }

    private void transfer(Player p, String[] a) {
        if (a.length < 1) {
            m.msg(p, "usage.transfer");
            return;
        }
        Land land = m.manager().at(p.getLocation());
        if (land == null) {
            m.msg(p, "error.not-in-land");
            return;
        }
        if (!requireOwner(p, land) || !requireNotFrozen(p, land)) return;
        Player t = Bukkit.getPlayerExact(a[0]);
        if (t == null) {
            m.msg(p, "error.player-offline", "player", a[0]);
            return;
        }
        if (land.isOwner(t.getUniqueId())) {
            m.msg(p, "trust.is-owner");
            return;
        }
        int max = m.maxLands(t);
        if (!m.isBypassing(p) && max >= 0 && m.manager().ownedBy(t.getUniqueId()).size() >= max) {
            m.msg(p, "transfer.target-limit", "player", t.getName());
            return;
        }
        String old = land.ownerName;
        m.manager().changeOwner(land, t.getUniqueId(), t.getName());
        land.salePrice = null;
        m.storage().save(land);
        m.storage().saveMembers(land);
        m.msg(p, "transfer.success", "land", land.name, "player", t.getName());
        m.msg(t, "transfer.received", "land", land.name, "player", p.getName());
        m.audit(p.getName(), "transfer", land.name + " " + old + " -> " + t.getName());
    }

    private void show(Player p, String[] a) {
        Land land = a.length > 0 ? m.manager().byName(a[0]) : m.manager().at(p.getLocation());
        if (land == null) {
            // 显示附近（当前区块范围内）的领地
            land = nearest(p);
            if (land == null) {
                m.msg(p, "show.none");
                return;
            }
        }
        m.visualizer().show(p, land.world, land.minX, land.minY, land.minZ, land.maxX, land.maxY, land.maxZ, m.settings().showSeconds,
                land.isMember(p.getUniqueId()) ? Visualizer.OWN : Visualizer.OTHER);
        m.msg(p, "show.success", "land", land.name, "seconds", m.settings().showSeconds);
    }

    private Land nearest(Player p) {
        Location l = p.getLocation();
        String w = l.getWorld().getName();
        List<Land> near = m.manager().overlapping(w, l.getBlockX() - 48, l.getBlockZ() - 48, l.getBlockX() + 48, l.getBlockZ() + 48, null);
        Land best = null;
        double bd = Double.MAX_VALUE;
        for (Land x : near) {
            double dx = (x.minX + x.maxX) / 2.0 - l.getX(), dz = (x.minZ + x.maxZ) / 2.0 - l.getZ();
            double d = dx * dx + dz * dz;
            if (d < bd) {
                bd = d;
                best = x;
            }
        }
        return best;
    }

    private void message(Player p, String[] a) {
        if (a.length < 1) {
            m.msg(p, "usage.message");
            return;
        }
        Land land = m.manager().at(p.getLocation());
        if (land == null) {
            m.msg(p, "error.not-in-land");
            return;
        }
        if (!requireManager(p, land) || !requireNotFrozen(p, land)) return;
        String type = a[0].toLowerCase(Locale.ROOT);
        if (!type.equals("enter") && !type.equals("leave")) {
            m.msg(p, "usage.message");
            return;
        }
        String text = a.length >= 2 ? String.join(" ", Arrays.copyOfRange(a, 1, a.length)) : "";
        if (text.equalsIgnoreCase("clear") || text.equals("清除")) text = "";
        if (text.length() > 200) text = text.substring(0, 200);
        if (type.equals("enter")) land.enterMessage = text.isEmpty() ? null : text;
        else land.leaveMessage = text.isEmpty() ? null : text;
        m.storage().save(land);
        m.msg(p, text.isEmpty() ? "message.cleared" : "message.success", "land", land.name, "type", type);
    }

    // ------------------------------------------------------------ admin

    private void admin(CommandSender s, String[] a) {
        if (!s.hasPermission("lc.land.admin")) {
            m.msg(s, "error.no-permission");
            return;
        }
        if (a.length < 1) {
            m.msg(s, "usage.admin");
            return;
        }
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "bypass" -> {
                if (!(s instanceof Player p)) {
                    m.msg(s, "error.player-only");
                    return;
                }
                boolean on = !m.isBypassing(p);
                m.bypass.remove(p.getUniqueId());
                m.bypassDisabled.remove(p.getUniqueId());
                if (on) m.bypass.add(p.getUniqueId());
                else m.bypassDisabled.add(p.getUniqueId());
                m.msg(p, on ? "admin.bypass-on" : "admin.bypass-off");
                m.audit(p.getName(), "bypass", on ? "on" : "off");
            }
            case "delete" -> {
                Land land = adminTarget(s, a);
                if (land == null) return;
                m.deleteLand(land);
                m.msg(s, "admin.deleted", "land", land.name, "owner", land.ownerName);
                m.notifyOwner(land, "admin.deleted-notify", "land", land.name);
                m.audit(m.actor(s), "admin-delete", land.name + " owner=" + land.ownerName + " " + land.world + " " + land.coords());
            }
            case "tp" -> {
                if (!(s instanceof Player p)) {
                    m.msg(s, "error.player-only");
                    return;
                }
                Land land = adminTarget(s, a);
                if (land == null) return;
                Location to = land.teleportLocation();
                if (to == null) {
                    m.msg(s, "error.world-missing");
                    return;
                }
                m.teleportBypass.add(p.getUniqueId());
                p.teleport(to);
                m.teleportBypass.remove(p.getUniqueId());
                m.msg(p, "tp.success", "land", land.name);
            }
            case "list" -> {
                if (a.length >= 2) {
                    UUID u = m.resolvePlayer(a[1]);
                    if (u == null) {
                        m.msg(s, "error.unknown-player", "player", a[1]);
                        return;
                    }
                    sendList(s, a[1], m.manager().ownedBy(u));
                } else {
                    List<Land> all = new ArrayList<>(m.manager().all());
                    all.sort((x, y) -> Long.compare(y.area(), x.area()));
                    sendList(s, "*", all.size() > 50 ? all.subList(0, 50) : all);
                }
            }
            default -> m.msg(s, "usage.admin");
        }
    }

    private Land adminTarget(CommandSender s, String[] a) {
        if (a.length >= 2) {
            Land l = m.manager().byName(a[1]);
            if (l == null) {
                try {
                    l = m.manager().byId(Long.parseLong(a[1]));
                } catch (NumberFormatException ignored) {
                }
            }
            if (l == null) m.msg(s, "error.no-such-land", "land", a[1]);
            return l;
        }
        if (s instanceof Player p) {
            Land l = m.manager().at(p.getLocation());
            if (l == null) m.msg(s, "error.not-in-land");
            return l;
        }
        m.msg(s, "usage.admin");
        return null;
    }

    void guiBuy(Player p, Land land, UUID owner, BigDecimal price) {
        if (m.manager().byName(land.name) != land || !owner.equals(land.owner)
                || price == null || land.salePrice == null || price.compareTo(land.salePrice) != 0) return;
        buy(p, new String[]{land.name});
    }

    void guiTeleport(Player p, Land land) { tp(p, new String[]{land.name}); }

    void guiSale(Player p, Land land, BigDecimal price) {
        if (m.manager().byName(land.name) != land || !requireOwner(p, land) || !requireNotFrozen(p, land)) return;
        if (m.economy().isEmpty()) { m.msg(p, "error.no-economy"); return; }
        land.salePrice = price;
        m.storage().save(land);
        m.audit(p.getName(), "gui-sell", land.name + " price=" + price);
    }

    // ------------------------------------------------------------ tab

    @Override
    public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
        if (args.length <= 1) return filter(SUBS, args.length == 0 ? "" : args[0]);
        String sub = args[0].toLowerCase(Locale.ROOT);
        String last = args[args.length - 1];
        if (args.length == 2) {
            switch (sub) {
                case "admin":
                    return filter(ADMIN_SUBS, last);
                case "trust", "untrust", "transfer", "list":
                    return onlinePlayers(last);
                case "flag":
                    List<String> fl = new ArrayList<>();
                    for (Flag f : Flag.values()) fl.add(f.name().toLowerCase(Locale.ROOT));
                    return filter(fl, last);
                case "expand":
                    return filter(DIRS, last);
                case "message":
                    return filter(List.of("enter", "leave"), last);
                case "sell":
                    return filter(List.of("cancel"), last);
                case "tp", "info", "show", "buy", "delete":
                    return filter(landNames(sender, sub.equals("buy")), last);
                default:
                    return Collections.emptyList();
            }
        }
        if (args.length == 3) {
            switch (sub) {
                case "trust":
                    return filter(List.of("member", "manager"), last);
                case "flag":
                    return filter(List.of("true", "false"), last);
                case "admin":
                    if (args[1].equalsIgnoreCase("list")) return onlinePlayers(last);
                    return filter(landNames(sender, false), last);
                default:
                    return Collections.emptyList();
            }
        }
        if (args.length == 4 && sub.equals("flag")) return filter(List.of("global", "member"), last);
        return Collections.emptyList();
    }

    private List<String> landNames(CommandSender s, boolean forSale) {
        List<String> out = new ArrayList<>();
        for (Land l : m.manager().all()) {
            if (forSale && l.salePrice == null) continue;
            if (out.size() > 100) break;
            out.add(l.name);
        }
        return out;
    }
}
