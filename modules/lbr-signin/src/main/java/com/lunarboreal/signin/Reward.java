package com.lunarboreal.signin;

import com.lunarboreal.api.service.EconomyService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 一组奖励：金币 / 物品 / 控制台命令 / 经验。 */
public record Reward(String description, BigDecimal money, int exp, List<ItemSpec> items, List<String> commands) {

    public record ItemSpec(Material material, int amount, String name, List<String> lore) {}

    public static final Reward EMPTY = new Reward(null, BigDecimal.ZERO, 0, List.of(), List.of());

    public boolean isEmpty() {
        return money.signum() <= 0 && exp <= 0 && items.isEmpty() && commands.isEmpty();
    }

    public static Reward parse(ConfigurationSection sec, java.util.logging.Logger log) {
        if (sec == null) return EMPTY;
        BigDecimal money;
        try {
            money = new BigDecimal(String.valueOf(sec.get("money", 0)).trim());
        } catch (NumberFormatException e) {
            money = BigDecimal.ZERO;
        }
        int exp = sec.getInt("exp", 0);
        List<ItemSpec> items = new ArrayList<>();
        for (Map<?, ?> map : sec.getMapList("items")) {
            Object matObj = map.get("material");
            Material mat = matObj == null ? null : Material.matchMaterial(String.valueOf(matObj));
            if (mat == null || !mat.isItem()) {
                log.warning("签到奖励中的物品材质无效: " + matObj);
                continue;
            }
            int amount = 1;
            Object a = map.get("amount");
            if (a instanceof Number n) amount = n.intValue();
            else if (a != null) try { amount = Integer.parseInt(String.valueOf(a)); } catch (NumberFormatException ignored) {}
            Object name = map.get("name");
            List<String> lore = new ArrayList<>();
            if (map.get("lore") instanceof List<?> l) for (Object o : l) lore.add(String.valueOf(o));
            items.add(new ItemSpec(mat, Math.max(1, amount), name == null ? null : String.valueOf(name), lore));
        }
        return new Reward(sec.getString("description"), money, exp, List.copyOf(items), List.copyOf(sec.getStringList("commands")));
    }

    /** 主线程调用：发放奖励。 */
    public void give(SigninModule m, Player p, String reason) {
        if (money.signum() > 0) {
            EconomyService eco = m.api().services().get(EconomyService.class).orElse(null);
            if (eco != null) {
                eco.deposit(p.getUniqueId(), money, reason);
            } else {
                m.logger().warning("未加载 economy 模块，无法发放签到金币奖励给 " + p.getName());
            }
        }
        if (exp > 0) p.giveExp(exp);
        for (ItemSpec spec : items) {
            ItemStack stack = new ItemStack(spec.material(), spec.amount());
            if (spec.name() != null || !spec.lore().isEmpty()) {
                ItemMeta meta = stack.getItemMeta();
                if (meta != null) {
                    if (spec.name() != null) meta.displayName(noItalic(m.api().messages().parse(spec.name())));
                    if (!spec.lore().isEmpty()) {
                        List<Component> lore = new ArrayList<>();
                        for (String line : spec.lore()) lore.add(noItalic(m.api().messages().parse(line)));
                        meta.lore(lore);
                    }
                    stack.setItemMeta(meta);
                }
            }
            for (ItemStack left : p.getInventory().addItem(stack).values()) {
                p.getWorld().dropItemNaturally(p.getLocation(), left);
            }
        }
        for (String cmd : commands) {
            String c = cmd.replace("{player}", p.getName()).replace("{uuid}", p.getUniqueId().toString());
            if (c.startsWith("/")) c = c.substring(1);
            try {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c);
            } catch (Exception e) {
                m.logger().warning("执行签到奖励命令失败 [" + c + "]: " + e.getMessage());
            }
        }
    }

    static Component noItalic(Component c) {
        return c.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }
}
