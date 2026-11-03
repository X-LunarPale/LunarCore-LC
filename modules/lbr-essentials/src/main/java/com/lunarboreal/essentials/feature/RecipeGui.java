package com.lunarboreal.essentials.feature;

import com.lunarboreal.essentials.EssentialsModule;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Registry;
import org.bukkit.entity.Player;
import org.bukkit.inventory.BlastingRecipe;
import org.bukkit.inventory.CampfireRecipe;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.SmithingRecipe;
import org.bukkit.inventory.SmithingTransformRecipe;
import org.bukkit.inventory.SmokingRecipe;
import org.bukkit.inventory.StonecuttingRecipe;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** /recipe 配方展示 GUI（Bukkit Recipe API，只读，可翻页）。 */
public final class RecipeGui {

    /** 3x3 合成格对应的槽位。 */
    static final int[] GRID = {10, 11, 12, 19, 20, 21, 28, 29, 30};
    static final int ARROW = 23;
    static final int RESULT = 25;
    static final int TYPE = 4;
    static final int PREV = 36;
    static final int NEXT = 44;

    public static final class Holder implements InventoryHolder {
        public final List<Recipe> recipes;
        public int index;
        private Inventory inv;

        Holder(List<Recipe> recipes) {
            this.recipes = recipes;
        }

        @Override
        public Inventory getInventory() { return inv; }
    }

    private RecipeGui() {}

    /** 可在 GUI 中展示的配方。 */
    public static List<Recipe> recipesFor(ItemStack target) {
        List<Recipe> out = new ArrayList<>();
        for (Recipe r : Bukkit.getRecipesFor(target)) {
            if (r instanceof ShapedRecipe || r instanceof ShapelessRecipe || r instanceof CookingRecipe<?>
                    || r instanceof StonecuttingRecipe || r instanceof SmithingRecipe) {
                out.add(r);
            }
        }
        return out;
    }

    public static void open(EssentialsModule m, Player p, List<Recipe> recipes, int index) {
        Holder h = new Holder(recipes);
        h.index = Math.floorMod(index, recipes.size());
        Inventory inv = Bukkit.createInventory(h, 45, c(m, "recipe.title",
                "item", recipes.get(h.index).getResult().getType().name().toLowerCase(),
                "page", h.index + 1, "pages", recipes.size()));
        h.inv = inv;
        render(m, h);
        p.openInventory(inv);
    }

    private static void render(EssentialsModule m, Holder h) {
        Inventory inv = h.inv;
        inv.clear();
        ItemStack filler = named(new ItemStack(Material.GRAY_STAINED_GLASS_PANE), Component.text(" "));
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, filler);
        for (int s : GRID) inv.setItem(s, null);

        Recipe r = h.recipes.get(h.index);
        String type;
        if (r instanceof ShapedRecipe sr) {
            type = "crafting";
            String[] shape = sr.getShape();
            Map<Character, RecipeChoice> map = sr.getChoiceMap();
            for (int row = 0; row < shape.length && row < 3; row++) {
                for (int col = 0; col < shape[row].length() && col < 3; col++) {
                    inv.setItem(GRID[row * 3 + col], display(m, map.get(shape[row].charAt(col))));
                }
            }
        } else if (r instanceof ShapelessRecipe sl) {
            type = "shapeless";
            List<RecipeChoice> list = sl.getChoiceList();
            for (int i = 0; i < list.size() && i < 9; i++) inv.setItem(GRID[i], display(m, list.get(i)));
        } else if (r instanceof CookingRecipe<?> cr) {
            type = r instanceof BlastingRecipe ? "blasting" : r instanceof SmokingRecipe ? "smoking"
                    : r instanceof CampfireRecipe ? "campfire" : "furnace";
            inv.setItem(GRID[1], display(m, cr.getInputChoice()));
            inv.setItem(GRID[7], named(new ItemStack(Material.COAL), c(m, "recipe.fuel",
                    "time", String.format(java.util.Locale.ROOT, "%.1f", cr.getCookingTime() / 20.0), "exp", cr.getExperience())));
        } else if (r instanceof StonecuttingRecipe st) {
            type = "stonecutting";
            inv.setItem(GRID[4], display(m, st.getInputChoice()));
        } else if (r instanceof SmithingRecipe sm) {
            type = "smithing";
            if (sm instanceof SmithingTransformRecipe tr) inv.setItem(GRID[3], display(m, tr.getTemplate()));
            inv.setItem(GRID[4], display(m, sm.getBase()));
            inv.setItem(GRID[5], display(m, sm.getAddition()));
        } else {
            type = "other";
        }
        inv.setItem(TYPE, named(new ItemStack(typeIcon(type)), c(m, "recipe.type." + type)));
        inv.setItem(ARROW, named(new ItemStack(Material.ARROW), c(m, "recipe.arrow")));
        inv.setItem(RESULT, r.getResult().clone());
        if (h.recipes.size() > 1) {
            inv.setItem(PREV, named(new ItemStack(Material.SPECTRAL_ARROW), c(m, "recipe.prev", "page", h.index + 1, "pages", h.recipes.size())));
            inv.setItem(NEXT, named(new ItemStack(Material.SPECTRAL_ARROW), c(m, "recipe.next", "page", h.index + 1, "pages", h.recipes.size())));
        }
    }

    /** 处理点击（由 EssListener 调用，事件已取消）。 */
    public static void click(EssentialsModule m, Player p, Holder h, int slot) {
        if (h.recipes.size() <= 1) return;
        if (slot == PREV) m.runTask(() -> open(m, p, h.recipes, h.index - 1));
        else if (slot == NEXT) m.runTask(() -> open(m, p, h.recipes, h.index + 1));
    }

    private static Material typeIcon(String type) {
        return switch (type) {
            case "crafting", "shapeless" -> Material.CRAFTING_TABLE;
            case "furnace" -> Material.FURNACE;
            case "blasting" -> Material.BLAST_FURNACE;
            case "smoking" -> Material.SMOKER;
            case "campfire" -> Material.CAMPFIRE;
            case "stonecutting" -> Material.STONECUTTER;
            case "smithing" -> Material.SMITHING_TABLE;
            default -> Material.BOOK;
        };
    }

    /** RecipeChoice → 展示物品；多选项时在描述中注明。 */
    @SuppressWarnings("deprecation")
    private static ItemStack display(EssentialsModule m, RecipeChoice choice) {
        if (choice == null) return null;
        ItemStack is = null;
        int options = 1;
        try {
            if (choice instanceof RecipeChoice.MaterialChoice mc && !mc.getChoices().isEmpty()) {
                is = new ItemStack(mc.getChoices().get(0));
                options = mc.getChoices().size();
            } else if (choice instanceof RecipeChoice.ExactChoice ec && !ec.getChoices().isEmpty()) {
                is = ec.getChoices().get(0).clone();
                options = ec.getChoices().size();
            } else if (choice instanceof RecipeChoice.ItemTypeChoice tc) {
                var values = tc.itemTypes().values();
                options = values.size();
                for (var key : values) {
                    Material mat = Registry.MATERIAL.get(key.key());
                    if (mat != null) {
                        is = new ItemStack(mat);
                        break;
                    }
                }
            }
            if (is == null) is = choice.getItemStack();
        } catch (Throwable t) {
            return null;
        }
        if (is == null || is.getType().isAir()) return null;
        if (options > 1) {
            ItemMeta meta = is.getItemMeta();
            List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
            lore.add(c(m, "recipe.options", "count", options));
            meta.lore(lore);
            is.setItemMeta(meta);
        }
        return is;
    }

    private static ItemStack named(ItemStack is, Component name) {
        ItemMeta meta = is.getItemMeta();
        meta.displayName(name.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
        is.setItemMeta(meta);
        return is;
    }

    private static Component c(EssentialsModule m, String key, Object... ph) {
        return m.api().messages().component("essentials." + key, ph).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }
}
