package com.lunarboreal.land;

import com.lunarboreal.api.service.LandService.Flag;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Module-local menus; every click is consumed before a main-thread action and rechecks live authorization. */
final class LandGui implements Listener {
    private final LandModule m;
    private final LandCommand command;
    private static final class Menu implements InventoryHolder {
        final UUID viewer;
        final Map<Integer, Runnable> actions = new HashMap<>();
        Inventory inventory;
        final MenuClickGate gate = new MenuClickGate();
        Menu(UUID viewer) { this.viewer = viewer; }
        @Override public Inventory getInventory() { return inventory; }
    }
    LandGui(LandModule m, LandCommand command) { this.m=m; this.command=command; }
    private Menu menu(Player p, String title) {
        Menu menu = new Menu(p.getUniqueId());
        menu.inventory = Bukkit.createInventory(menu, 54, Component.text(title));
        return menu;
    }
    private void button(Menu menu, int slot, Material material, String text, Runnable action) {
        ItemStack item = new ItemStack(material);
        var meta = item.getItemMeta(); meta.displayName(Component.text(text)); item.setItemMeta(meta);
        menu.inventory.setItem(slot,item);
        if (action != null) menu.actions.put(slot,action);
    }
    void openList(Player p, int requestedPage) {
        if (!m.loaded() || !p.hasPermission("lbr.land.use")) return;
        List<Land> lands = new ArrayList<>(m.manager().all());
        lands.sort(Comparator.comparing(l -> l.name));
        int page = Math.max(0, Math.min(requestedPage, Math.max(0,(lands.size()-1)/45)));
        Menu menu=menu(p,"领地列表 / Lands " + (page+1));
        for (int i=page*45; i<Math.min(lands.size(),(page+1)*45); i++) {
            Land l=lands.get(i);
            button(menu,i-page*45,Material.GRASS_BLOCK,l.name+" | "+l.ownerName+(l.salePrice==null?"":" | $"+l.salePrice),()->detail(p,l));
        }
        if(page>0) button(menu,45,Material.ARROW,"上一页 / Previous",()->openList(p,page-1));
        if((page+1)*45<lands.size()) button(menu,53,Material.ARROW,"下一页 / Next",()->openList(p,page+1));
        p.openInventory(menu.inventory);
    }
    private boolean live(Land l) { return m.loaded() && m.manager().byName(l.name)==l; }
    private boolean manage(Player p, Land l) { return live(l) && m.canManage(p,l) && (!l.frozen || m.isBypassing(p)); }
    private void detail(Player p, Land l) {
        if(!live(l)) { openList(p,0); return; }
        Menu menu=menu(p,l.name);
        button(menu,4,Material.MAP,l.ownerName+" | "+l.world+" | "+l.coords(),null);
        button(menu,10,Material.ENDER_PEARL,"传送 / Teleport",()->{if(live(l)) command.guiTeleport(p,l);});
        button(menu,12,Material.PLAYER_HEAD,"成员 / Members",()->members(p,l,0));
        button(menu,14,Material.LEVER,"访客 flag / Global flags",()->flags(p,l,false));
        button(menu,16,Material.REPEATER,"成员 flag / Member flags",()->flags(p,l,true));
        if(l.salePrice!=null && !l.isOwner(p.getUniqueId())) {
            UUID owner=l.owner; BigDecimal price=l.salePrice;
            button(menu,22,Material.EMERALD,"购买 / Buy $"+price,()->confirm(p,"确认购买 $"+price,()->command.guiBuy(p,l,owner,price)));
        }
        if(m.isOwnerOrBypass(p,l)) {
            button(menu,30,Material.PAPER,"出售: /land gui sell <price>",()->p.sendMessage("/land gui sell <price> (stand inside this land)"));
            if(l.salePrice!=null) button(menu,32,Material.BARRIER,"确认取消出售 / Cancel listing",()->confirmSale(p,l,null));
        }
        button(menu,49,Material.ARROW,"列表 / List",()->openList(p,0));
        p.openInventory(menu.inventory);
    }
    private void members(Player p,Land l,int requestedPage) {
        if(!live(l)) return;
        List<Land.Member> members=new ArrayList<>(l.members.values());
        int page=Math.max(0,Math.min(requestedPage,Math.max(0,(members.size()-1)/45)));
        Menu menu=menu(p,"成员 / Members "+l.name);
        for(int i=page*45;i<Math.min(members.size(),(page+1)*45);i++) {
            Land.Member member=members.get(i);
            button(menu,i-page*45,Material.PLAYER_HEAD,member.name()+" | "+member.role()+" (点击移除)",()->{
                if(!manage(p,l) || (member.role()==Land.Role.MANAGER && !m.isOwnerOrBypass(p,l))) return;
                confirm(p,"移除 / Remove "+member.name(),()->{
                    if(manage(p,l) && (member.role()!=Land.Role.MANAGER || m.isOwnerOrBypass(p,l))
                            && l.members.remove(member.uuid(),member)) {
                        m.storage().saveMembers(l); m.audit(p.getName(),"gui-untrust",l.name+" "+member.name());
                    }
                });
            });
        }
        if(page>0) button(menu,45,Material.ARROW,"Previous",()->members(p,l,page-1));
        button(menu,49,Material.ARROW,"详情 / Details",()->detail(p,l));
        if((page+1)*45<members.size()) button(menu,53,Material.ARROW,"Next",()->members(p,l,page+1));
        p.openInventory(menu.inventory);
    }
    private void flags(Player p,Land l,boolean member) {
        if(!live(l)) return;
        Menu menu=menu(p,(member?"成员":"访客")+" flags "+l.name);
        int slot=0;
        for(Flag flag:Flag.values()) {
            if(member && !Settings.PLAYER_FLAGS.contains(flag)) continue;
            boolean value=member?l.memberFlag(flag):l.globalFlag(flag);
            button(menu,slot++,value?Material.LIME_DYE:Material.GRAY_DYE,flag+" = "+value,()->{
                if(!manage(p,l)) return;
                (member?l.memberFlags:l.globalFlags).put(flag,!value);
                m.storage().saveFlags(l); m.audit(p.getName(),"gui-flag",l.name+" "+member+" "+flag+"="+!value);
                flags(p,l,member);
            });
        }
        button(menu,49,Material.ARROW,"详情 / Details",()->detail(p,l)); p.openInventory(menu.inventory);
    }
    void confirmSale(Player p,Land land,BigDecimal price) {
        UUID owner=land.owner;
        confirm(p,price==null?"取消出售 / Cancel sale":"确认出售 / Sell $"+price,()->{
            if(live(land) && owner.equals(land.owner)) command.guiSale(p,land,price);
        });
    }
    private void confirm(Player p,String title,Runnable action) {
        Menu menu=menu(p,title);
        button(menu,20,Material.LIME_CONCRETE,"确认 / Confirm",action);
        button(menu,24,Material.RED_CONCRETE,"取消 / Cancel",()->openList(p,0));
        p.openInventory(menu.inventory);
    }
    @EventHandler public void click(InventoryClickEvent e) {
        if(!(e.getView().getTopInventory().getHolder() instanceof Menu menu)) return;
        e.setCancelled(true);
        if(!(e.getWhoClicked() instanceof Player p) || !menu.viewer.equals(p.getUniqueId())
                || !p.hasPermission("lbr.land.use") || !m.loaded() || e.getRawSlot()<0 || e.getRawSlot()>=54) return;
        Runnable action=menu.actions.get(e.getRawSlot());
        if(action==null || !menu.gate.consume()) return;
        // Closing before dispatch makes queued double-clicks and stale inventory packets harmless.
        m.runTask(()->{p.closeInventory(); if(p.isOnline() && p.hasPermission("lbr.land.use") && m.loaded()) action.run();});
    }
    @EventHandler public void drag(InventoryDragEvent e) {
        if(e.getView().getTopInventory().getHolder() instanceof Menu) e.setCancelled(true);
    }
}
