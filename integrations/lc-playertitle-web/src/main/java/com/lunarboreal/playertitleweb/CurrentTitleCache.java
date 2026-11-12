package com.lunarboreal.playertitleweb;

import com.lunarboreal.api.LC;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.scheduler.BukkitTask;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Placeholder callbacks never invoke the vendor API or perform I/O. */
final class CurrentTitleCache implements Listener, AutoCloseable {
    static final String EMPTY = "未佩戴";
    private final PlayerTitleWebPlugin plugin;
    private final PublicTitleApi api;
    private final Map<UUID,Value> values = new ConcurrentHashMap<>();
    private BukkitTask task;
    private long warned;
    record Value(String raw, String plain) {}
    CurrentTitleCache(PlayerTitleWebPlugin plugin, PublicTitleApi api) { this.plugin=plugin;this.api=api; }
    void start() {
        Bukkit.getPluginManager().registerEvents(this,plugin);
        LC.get().placeholders().register("playertitle",(player,key)-> {
            if (!key.equals("current") && !key.equals("current_plain")) return null;
            Value value=player==null ? null : values.get(player.getUniqueId());
            return value==null ? EMPTY : key.equals("current") ? value.raw() : value.plain();
        },plugin);
        refreshAll();task=Bukkit.getScheduler().runTaskTimer(plugin,this::refreshAll,20L,20L);
    }
    void refreshAll() { for(var player:Bukkit.getOnlinePlayers()) refresh(player.getUniqueId()); }
    void refresh(UUID uuid) {
        values.remove(uuid);
        if(Bukkit.getPlayer(uuid)==null)return;
        try { values.put(uuid,select(api.owned(uuid),System.currentTimeMillis())); }
        catch(Exception ex) {
            if(warned==0 || System.nanoTime()-warned>60_000_000_000L) { warned=System.nanoTime();plugin.getLogger().warning("当前佩戴称号刷新失败，暂显示未佩戴："+ex.getClass().getSimpleName()); }
        }
    }
    static Value select(List<Map<String,Object>> rows,long now) {
        for(var row:rows) {
            if(!Boolean.TRUE.equals(row.get("isUseShow")) && !(row.get("isUseShow") instanceof Number flag && flag.intValue()==1))continue;
            Object expires=row.get("expirationTime");
            if(expires instanceof Number expiry && expiry.longValue()>0 && expiry.longValue()<=now)continue;
            if(expires!=null && !(expires instanceof Number))continue;
            if(!(row.get("titleName") instanceof String raw) || raw.isBlank())continue;
            // Preserve vendor legacy formatting; never interpret MiniMessage/user tags.
            String safe=raw.codePoints().filter(cp->!Character.isISOControl(cp)).limit(1024).collect(StringBuilder::new,StringBuilder::appendCodePoint,StringBuilder::append).toString();
            return new Value(safe,plain(safe));
        }
        return new Value(EMPTY,EMPTY);
    }
    static String plain(String raw) {
        return PlainTextComponentSerializer.plainText().serialize(LegacyComponentSerializer.legacyAmpersand().deserialize(raw.replace('§','&')));
    }
    @EventHandler public void join(PlayerJoinEvent event) { refresh(event.getPlayer().getUniqueId()); }
    @EventHandler public void quit(PlayerQuitEvent event) { values.remove(event.getPlayer().getUniqueId()); }
    @Override public void close() {
        if(task!=null)task.cancel();HandlerList.unregisterAll(this);
        if(LC.isLoaded())LC.get().placeholders().unregisterAll(plugin);values.clear();
    }
}
