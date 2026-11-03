package com.lunarboreal.land;

import com.lunarboreal.api.module.LbrModule;
import com.lunarboreal.api.service.EconomyService;
import com.lunarboreal.api.service.LandService;
import com.lunarboreal.api.service.LandService.Flag;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.permissions.PermissionDefault;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class LandModule extends LbrModule {

    private Settings settings;
    private final LandManager manager = new LandManager();
    private final Visualizer visualizer = new Visualizer();
    private LandStorage storage;
    private volatile boolean loaded;
    private SessionListener session;

    /** 选区：uuid → [pos1, pos2]（方块坐标）。 */
    final Map<UUID, Location[]> selections = new ConcurrentHashMap<>();
    /** 开启管理员绕过的玩家。 */
    final Set<UUID> bypass = ConcurrentHashMap.newKeySet();
    final Set<UUID> bypassDisabled = ConcurrentHashMap.newKeySet();
    /** 本次传送豁免（管理员 tp）。 */
    final Set<UUID> teleportBypass = new HashSet<>();

    @Override
    public void onEnable() {
        settings = new Settings(config());
        storage = new LandStorage(api().database(), logger());
        loaded = false;
        manager.clear();

        defaultPermissions("lbr.land.use", "lbr.land.create");
        registerPermission("lbr.land.admin", PermissionDefault.OP, "领地管理员（删除/传送/列出/绕过）");
        registerPermission("lbr.land.list.others", PermissionDefault.OP, "查看他人领地列表");

        runAsync(() -> {
            try {
                storage.createTables();
                List<Land> lands = storage.loadAll();
                runTask(() -> {
                    for (Land l : lands) manager.add(l);
                    loaded = true;
                    logger().info("已加载 " + lands.size() + " 块领地");
                });
            } catch (Exception e) {
                logger().severe("加载领地数据失败: " + e.getMessage());
            }
        });

        provide(LandService.class, new LandServiceImpl(this));
        registerListener(new ProtectionListener(this));
        session = new SessionListener(this);
        registerListener(session);
        LandCommand command = new LandCommand(this);
        LandGui gui = new LandGui(this, command);
        command.gui = gui;
        registerListener(gui);
        registerCommand(command);
        runTaskTimer(visualizer::tick, 20L, 10L);
        // 领地税：每 10 分钟检查一次
        runTaskTimer(this::taxTick, 20L * 60, 20L * 600);
        registerPlaceholders();
        new LandWeb(this).register();
    }

    @Override
    public void onDisable() {
        visualizer.clear();
        selections.clear();
        if (storage != null) storage.shutdown();
        manager.clear();
        loaded = false;
    }

    @Override
    public void onReload() {
        reloadConfig();
        settings = new Settings(config());
    }

    // ------------------------------------------------------------ 访问器

    public Settings settings() { return settings; }

    public LandManager manager() { return manager; }

    public LandStorage storage() { return storage; }

    public Visualizer visualizer() { return visualizer; }

    public boolean loaded() { return loaded; }

    SessionListener session() { return session; }

    public boolean isBypassing(Player p) {
        return LandGeometry.bypass(p.hasPermission("lbr.land.admin"), settings.adminDefaultBypass,
                bypass.contains(p.getUniqueId()), bypassDisabled.contains(p.getUniqueId()));
    }

    /** 玩家能否管理领地（主人/管理员角色/绕过管理员）。 */
    public boolean canManage(Player p, Land land) {
        return land.isManager(p.getUniqueId()) || isBypassing(p);
    }

    public boolean isOwnerOrBypass(Player p, Land land) {
        return land.isOwner(p.getUniqueId()) || isBypassing(p);
    }

    /** 完整权限判断（含绕过）。 */
    public boolean can(Player p, Land land, Flag flag) {
        if (land == null) return true;
        if (isBypassing(p)) return true;
        return land.allows(p.getUniqueId(), flag);
    }

    // ------------------------------------------------------------ 经济

    public Optional<EconomyService> economy() {
        return api().services().get(EconomyService.class);
    }

    public String fmt(BigDecimal amount) {
        return economy().map(e -> e.format(amount)).orElse(amount.setScale(2, RoundingMode.HALF_UP).toPlainString());
    }

    public BigDecimal cost(String world, long area) {
        return settings.priceIn(world).multiply(BigDecimal.valueOf(area)).setScale(2, RoundingMode.HALF_UP);
    }

    BigDecimal cost(String world, long area, int minY, int maxY) {
        World w = Bukkit.getWorld(world);
        // Vanilla-compatible fallback for an unavailable world; never use the legacy integer sentinels as height.
        return LandGeometry.cost(settings.priceIn(world), area, settings.volumePricing, minY, maxY,
                w == null ? -64 : w.getMinHeight(), w == null ? 320 : w.getMaxHeight());
    }

    /**
     * 向玩家收费。返回 null 表示成功；否则返回已发送提示后的失败标记 false。
     */
    public boolean charge(Player p, BigDecimal amount, String reason) {
        if (amount.signum() <= 0) return true;
        Optional<EconomyService> eco = economy();
        if (eco.isEmpty()) {
            if (settings.noEconomyFree) return true;
            msg(p, "error.no-economy");
            return false;
        }
        EconomyService.Result r = eco.get().withdraw(p.getUniqueId(), amount, reason);
        if (!r.success()) {
            msg(p, "error.not-enough-money", "cost", fmt(amount), "balance", fmt(r.balance() == null ? BigDecimal.ZERO : r.balance()));
            return false;
        }
        return true;
    }

    public void refund(UUID player, BigDecimal amount, String reason) {
        if (amount.signum() <= 0) return;
        economy().ifPresent(e -> e.deposit(player, amount, reason));
    }

    // ------------------------------------------------------------ 限制

    public int maxLands(Player p) {
        return permLimit(p, "lbr.land.limit.", settings.maxLands);
    }

    public long maxArea(Player p) {
        long v = permLimit(p, "lbr.land.area.", (int) Math.min(Integer.MAX_VALUE, settings.maxTotalArea));
        return v < 0 ? Long.MAX_VALUE : v;
    }

    /** 扫描 prefix.<n> 权限取最大值；prefix.unlimited 返回 -1。 */
    private int permLimit(Player p, String prefix, int def) {
        int best = -2;
        for (PermissionAttachmentInfo info : p.getEffectivePermissions()) {
            if (!info.getValue()) continue;
            String perm = info.getPermission();
            if (!perm.startsWith(prefix)) continue;
            String tail = perm.substring(prefix.length());
            if (tail.equals("unlimited") || tail.equals("*")) return -1;
            try {
                best = Math.max(best, Integer.parseInt(tail));
            } catch (NumberFormatException ignored) {
            }
        }
        return best == -2 ? def : best;
    }

    /**
     * 校验一个矩形是否可作为某玩家的领地（新建或扩建）。通过返回 null，否则返回语言 key 与参数。
     * @param existing 扩建时的原领地
     */
    public Object[] validateArea(Player p, String world, int minX, int minZ, int maxX, int maxZ, Land existing) {
        return validateArea(p, world, minX, Integer.MIN_VALUE, minZ, maxX, Integer.MAX_VALUE, maxZ, existing);
    }

    public Object[] validateArea(Player p, String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ, Land existing) {
        long w = (long) maxX - minX + 1, l = (long) maxZ - minZ + 1;
        long area = (long) w * l;
        if (settings.worldBlacklist.contains(world) && !isBypassing(p)) return new Object[]{"error.world-blacklisted"};
        if (Math.min(w, l) < settings.minSide) return new Object[]{"error.too-small", "min", settings.minSide};
        if (Math.max(w, l) > settings.maxSide) return new Object[]{"error.too-large", "max", settings.maxSide};
        if (area < settings.minArea) return new Object[]{"error.area-too-small", "min", settings.minArea};
        if (minY > maxY) return new Object[]{"error.no-selection", "tool", settings.tool.name()};
        List<Land> overlaps = manager.overlapping(world, minX, minY, minZ, maxX, maxY, maxZ, existing);
        if (!overlaps.isEmpty()) return new Object[]{"error.overlap", "land", overlaps.get(0).name};
        if (settings.spawnDistance > 0 && !isBypassing(p)) {
            World wd = Bukkit.getWorld(world);
            if (wd != null) {
                Location s = wd.getSpawnLocation();
                int sx = s.getBlockX(), sz = s.getBlockZ();
                int dx = Math.max(Math.max(minX - sx, 0), sx - maxX);
                int dz = Math.max(Math.max(minZ - sz, 0), sz - maxZ);
                if ((long) dx * dx + (long) dz * dz < (long) settings.spawnDistance * settings.spawnDistance)
                    return new Object[]{"error.near-spawn", "distance", settings.spawnDistance};
            }
        }
        if (!isBypassing(p)) {
            UUID owner = existing == null ? p.getUniqueId() : existing.owner;
            long total = manager.totalArea(owner) - (existing == null ? 0 : existing.area()) + area;
            if (total > maxArea(p)) return new Object[]{"error.total-area", "max", maxArea(p)};
        }
        return null;
    }

    // ------------------------------------------------------------ 领地操作

    public Land createLand(Player p, String name, String world, int minX, int minZ, int maxX, int maxZ, BigDecimal paid) {
        return createLand(p, name, world, minX, Integer.MIN_VALUE, minZ, maxX, Integer.MAX_VALUE, maxZ, paid);
    }

    public Land createLand(Player p, String name, String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ, BigDecimal paid) {
        Land land = new Land(name, p.getUniqueId(), p.getName(), world, minX, minZ, maxX, maxZ, System.currentTimeMillis());
        land.minY = minY;
        land.maxY = maxY;
        land.globalFlags.putAll(settings.defaultGlobal);
        land.memberFlags.putAll(settings.defaultMember);
        land.paid = paid;
        manager.add(land);
        storage.insert(land);
        return land;
    }

    public void deleteLand(Land land) {
        manager.remove(land);
        storage.delete(land);
    }

    public String nameOf(UUID uuid) {
        Player p = Bukkit.getPlayer(uuid);
        if (p != null) return p.getName();
        return api().players().get(uuid).map(r -> r.name()).orElseGet(() -> {
            OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
            return op.getName() == null ? uuid.toString().substring(0, 8) : op.getName();
        });
    }

    /** 解析玩家名为 UUID（在线 → core 档案 → Bukkit 缓存）。 */
    public UUID resolvePlayer(String name) {
        Player p = Bukkit.getPlayerExact(name);
        if (p != null) return p.getUniqueId();
        Optional<UUID> u = api().players().uuidOf(name);
        if (u.isPresent()) return u.get();
        OfflinePlayer op = Bukkit.getOfflinePlayerIfCached(name);
        return op == null ? null : op.getUniqueId();
    }

    public void notifyOwner(Land land, String key, Object... ph) {
        Player p = Bukkit.getPlayer(land.owner);
        if (p != null) msg(p, key, ph);
    }

    public String actor(CommandSender s) {
        return s instanceof Player ? s.getName() : "console";
    }

    // ------------------------------------------------------------ 领地税

    private void taxTick() {
        if (!loaded || !settings.taxEnabled) return;
        long now = System.currentTimeMillis();
        long day = 86_400_000L;
        for (Land land : List.copyOf(manager.all())) {
            if (now - land.lastTax < day) continue;
            BigDecimal amount = settings.taxPerBlock.multiply(BigDecimal.valueOf(land.area())).setScale(2, RoundingMode.HALF_UP);
            boolean ok;
            if (amount.signum() <= 0) ok = true;
            else {
                Optional<EconomyService> eco = economy();
                ok = eco.isEmpty() || eco.get().withdraw(land.owner, amount, "领地税: " + land.name).success();
            }
            if (ok) {
                land.lastTax += day;
                if (now - land.lastTax >= day) land.lastTax = now; // 长时间停服不追溯多天
                if (land.frozen) {
                    land.frozen = false;
                    land.frozenSince = 0;
                    notifyOwner(land, "tax.unfrozen", "land", land.name);
                }
                if (amount.signum() > 0) notifyOwner(land, "tax.paid", "land", land.name, "amount", fmt(amount));
            } else {
                if (settings.taxFreeze && !land.frozen) {
                    land.frozen = true;
                    land.frozenSince = now;
                    audit("system", "tax-freeze", land.name + " (" + land.ownerName + ")");
                }
                notifyOwner(land, "tax.failed", "land", land.name, "amount", fmt(amount));
                if (land.frozen && settings.taxDeleteAfterDays > 0 && now - land.frozenSince > settings.taxDeleteAfterDays * day) {
                    deleteLand(land);
                    audit("system", "tax-delete", land.name + " (" + land.ownerName + ")");
                    continue;
                }
            }
            storage.save(land);
        }
    }

    // ------------------------------------------------------------ 变量

    private void registerPlaceholders() {
        placeholder((player, key) -> {
            if (player == null) return "";
            switch (key) {
                case "current", "current_owner" -> {
                    Player online = player.getPlayer();
                    if (online == null) return "";
                    Land l = manager.at(online.getLocation());
                    if (l == null) return key.equals("current") ? raw("placeholder.wilderness") : "";
                    return key.equals("current") ? l.name : l.ownerName;
                }
                case "count" -> {
                    return String.valueOf(manager.ownedBy(player.getUniqueId()).size());
                }
                case "total_area" -> {
                    return String.valueOf(manager.totalArea(player.getUniqueId()));
                }
                default -> {
                    return null;
                }
            }
        });
    }
}
