package com.lunarboreal.chat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import java.util.*;

/** Main-thread TAB owner; yields individual fields when another writer replaces them. */
final class TabList {
    private static final String CURRENT = "%lc_playertitle_current%";
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder().character('&').hexColors().useUnusualXRepeatedCharacterHexFormat().build();
    private static final class View {
        Component originalName, originalHeader, originalFooter, lastName, lastHeader, lastFooter;
        boolean nameOwned, headerOwned, footerOwned, nameBlocked, headerBlocked, footerBlocked;
    }
    private final ChatModule module;
    private final Map<UUID, View> previous = new HashMap<>();
    private BukkitTask task;
    TabList(ChatModule module) { this.module=module; }
    static long period(long seconds) { return Math.max(1,Math.min(3600,seconds))*20L; }
    static String nameFormat(org.bukkit.configuration.file.FileConfiguration config) {
        if(config.contains("tablist.player-name-format",true))return config.getString("tablist.player-name-format","{title} {player}");
        if(config.contains("tablist.name-format",true))return config.getString("tablist.name-format","{title} {player}");
        return "{title} {player}";
    }
    void restart() {
        stop(); if(!module.config().getBoolean("tablist.enabled",true))return;
        refresh(); long ticks=period(module.config().getLong("tablist.refresh-seconds",5));
        task=module.runTaskTimer(this::refresh,ticks,ticks);
    }
    void stop() {
        if(task!=null){task.cancel();task=null;}
        for(Player p:Bukkit.getOnlinePlayers())restore(p);
        previous.clear();
    }
    void forget(Player p){restore(p);previous.remove(p.getUniqueId());}
    static boolean stillOwns(Component current,Component last,boolean owned){return owned&&Objects.equals(current,last);}
    private void restore(Player p){
        View v=previous.get(p.getUniqueId());if(v==null)return;
        if(stillOwns(p.playerListName(),v.lastName,v.nameOwned))p.playerListName(v.originalName);
        Component h=empty(p.playerListHeader()),f=empty(p.playerListFooter());
        Component restoreH=stillOwns(h,v.lastHeader,v.headerOwned)?v.originalHeader:h;
        Component restoreF=stillOwns(f,v.lastFooter,v.footerOwned)?v.originalFooter:f;
        if(!h.equals(restoreH)||!f.equals(restoreF))p.sendPlayerListHeaderAndFooter(restoreH,restoreF);
    }
    private static Component empty(Component value){return value==null?Component.empty():value;}
    private boolean externalProvider(){
        for(String name:List.of("TAB","PlayerListPlus","AnimatedNames","PlayerScoreboard"))if(Bukkit.getPluginManager().isPluginEnabled(name))return true;
        return false;
    }
    void refresh(){
        double tps=20,mspt=0;
        try{tps=Bukkit.getServer().getTPS()[0];mspt=Bukkit.getServer().getAverageTickTime();}catch(LinkageError ignored){}
        String header=String.join("<newline>",module.config().getStringList("tablist.header"));
        String footer=String.join("<newline>",module.config().getStringList("tablist.footer"));
        String name=nameFormat(module.config());boolean foreign=externalProvider();Set<UUID>online=new HashSet<>();
        for(Player p:Bukkit.getOnlinePlayers()){
            online.add(p.getUniqueId());
            if(foreign){restore(p);previous.remove(p.getUniqueId());continue;}
            View v=previous.computeIfAbsent(p.getUniqueId(),ignored->{View created=new View();created.originalName=p.playerListName();created.originalHeader=empty(p.playerListHeader());created.originalFooter=empty(p.playerListFooter());
                created.nameBlocked=created.originalName!=null&&!created.originalName.equals(Component.text(p.getName()));
                created.headerBlocked=!created.originalHeader.equals(Component.empty());created.footerBlocked=!created.originalFooter.equals(Component.empty());return created;});
            if(v.nameOwned&&!Objects.equals(p.playerListName(),v.lastName)){v.nameOwned=false;v.nameBlocked=true;}
            if(v.headerOwned&&!empty(p.playerListHeader()).equals(v.lastHeader)){v.headerOwned=false;v.headerBlocked=true;}
            if(v.footerOwned&&!empty(p.playerListFooter()).equals(v.lastFooter)){v.footerOwned=false;v.footerBlocked=true;}
            Map<String,String>vars=module.vars("player",p.getName(),"world",ChatFormatter.MM.escapeTags(p.getWorld().getName()),"ping",p.getPing(),"online",Bukkit.getOnlinePlayers().size(),"max_players",Bukkit.getMaxPlayers(),"tps",String.format(Locale.ROOT,"%.2f",Math.min(20,tps)),"mspt",String.format(Locale.ROOT,"%.2f",mspt));
            Component title=currentTitle(p);var titleTag=Placeholder.component("title",title);var display=Placeholder.component("displayname",p.displayName());
            Component nextH=v.headerBlocked?empty(p.playerListHeader()):render(p,header,vars,display,titleTag);
            Component nextF=v.footerBlocked?empty(p.playerListFooter()):render(p,footer,vars,display,titleTag);
            if(!v.headerBlocked){v.lastHeader=nextH;v.headerOwned=true;}if(!v.footerBlocked){v.lastFooter=nextF;v.footerOwned=true;}
            if(!nextH.equals(empty(p.playerListHeader()))||!nextF.equals(empty(p.playerListFooter())))p.sendPlayerListHeaderAndFooter(nextH,nextF);
            if(!v.nameBlocked&&name!=null&&!name.isBlank()){
                String template=withoutEmptyTitle(name,title);
                Component desired=render(p,template,vars,display,titleTag);
                if(!Objects.equals(desired,p.playerListName()))p.playerListName(desired);
                v.lastName=desired;v.nameOwned=true;
            }
        }
        previous.keySet().retainAll(online);
    }
    private Component currentTitle(Player p){
        if(!module.config().getBoolean("tablist.show-current-title",true))return Component.empty();
        String raw=module.api().placeholders().apply(p,CURRENT);
        if(raw==null||raw.equals(CURRENT)){
            String local=module.api().placeholders().apply(p,"%lc_titles_title%");
            return local==null||local.equals("%lc_titles_title%")?Component.empty():renderTitle(local);
        }
        return vendorTitle(raw);
    }
    static Component vendorTitle(String raw){
        if(raw==null||raw.isBlank()||raw.equals(CURRENT))return Component.empty();
        Component parsed=LEGACY.deserialize(raw.replace('§','&'));
        String plain=PlainTextComponentSerializer.plainText().serialize(parsed).strip();
        return plain.equals("未佩戴")||plain.isBlank()?Component.empty():parsed;
    }
    static String withoutEmptyTitle(String template,Component title){
        if(!PlainTextComponentSerializer.plainText().serialize(title).isBlank())return template;
        return template.replaceAll("(?:\\{title}|<title>|%lc_(?:playertitle_current|titles_title)%)\\s*","").strip();
    }
    static Component renderTitle(String sanitizedMiniMessage){return ChatFormatter.SAFE_MM.deserialize(sanitizedMiniMessage);}
    static Component renderName(String template,Component displayName,Component title){
        return ChatFormatter.MM.deserialize(withoutEmptyTitle(template,title).replace("{title}","<title>"),Placeholder.component("displayname",displayName),Placeholder.component("title",title));
    }
    private Component render(Player player,String template,Map<String,String>vars,net.kyori.adventure.text.minimessage.tag.resolver.TagResolver...tags){
        String text=template.replace("{title}","<title>").replace("%lc_titles_title%","<title>").replace("%lc_playertitle_current%","<title>").replace(CURRENT,"<title>");
        for(var e:vars.entrySet())text=text.replace("{"+e.getKey()+"}",e.getValue());
        var matcher=java.util.regex.Pattern.compile("%lc_[A-Za-z0-9_]+%",java.util.regex.Pattern.CASE_INSENSITIVE).matcher(text);StringBuilder out=new StringBuilder();
        while(matcher.find())matcher.appendReplacement(out,java.util.regex.Matcher.quoteReplacement(ChatFormatter.MM.escapeTags(Objects.toString(module.api().placeholders().apply(player,matcher.group()),""))));
        matcher.appendTail(out);return ChatFormatter.MM.deserialize(out.toString(),tags);
    }
}
