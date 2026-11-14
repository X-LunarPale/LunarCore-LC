package com.lunarboreal.teleport;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import com.lunarboreal.api.service.SoundService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** tpa 请求管理。所有方法主线程调用。 */
public final class TpaManager {

    /** here = true 表示 tpahere（被请求者传送到请求者处）。 */
    public record TpaRequest(UUID requester, String requesterName, UUID target, String targetName, boolean here, long created) {}

    private final TeleportModule m;
    /** target → (requester → request)，按时间顺序。 */
    private final Map<UUID, LinkedHashMap<UUID, TpaRequest>> incoming = new ConcurrentHashMap<>();
    /** 关闭了传送请求的玩家。 */
    private final Set<UUID> toggledOff = ConcurrentHashMap.newKeySet();

    public TpaManager(TeleportModule m) {
        this.m = m;
    }

    // ---------------- tptoggle ----------------

    public void loadToggles() {
        String t = m.db().table("teleport_toggles");
        m.db().queryAsync("SELECT uuid FROM " + t, rs -> rs.getString(1)).thenAccept(list -> {
            for (String s : list) {
                try { toggledOff.add(UUID.fromString(s)); } catch (IllegalArgumentException ignored) {}
            }
        }).exceptionally(e -> { m.logger().warning("加载 tptoggle 失败: " + e.getMessage()); return null; });
    }

    public boolean accepts(UUID uuid) {
        return !toggledOff.contains(uuid);
    }

    /** @return 切换后是否接受请求 */
    public boolean toggle(UUID uuid) {
        String t = m.db().table("teleport_toggles");
        if (toggledOff.remove(uuid)) {
            m.db().updateAsync("DELETE FROM " + t + " WHERE uuid = ?", uuid.toString());
            return true;
        }
        toggledOff.add(uuid);
        m.db().updateAsync(m.db().upsert(t, List.of("uuid"), List.of("uuid")), uuid.toString());
        return false;
    }

    public int pendingCount() {
        int n = 0;
        for (LinkedHashMap<UUID, TpaRequest> map : incoming.values()) n += map.size();
        return n;
    }

    // ---------------- 请求 ----------------

    /** 发送请求，返回是否成功（失败时已提示）。 */
    public boolean send(Player requester, Player target, boolean here) {
        if (requester.getUniqueId().equals(target.getUniqueId())) {
            m.msg(requester, "tpa.self");
            return false;
        }
        if (!accepts(target.getUniqueId())) {
            m.msg(requester, "tpa.target-disabled", "player", target.getName());
            return false;
        }
        String key = here ? "tpahere" : "tpa";
        long left = m.teleports().cooldownLeft(requester, key);
        if (left > 0) {
            m.msg(requester, "teleport.cooldown", "seconds", (left + 999) / 1000);
            return false;
        }
        LinkedHashMap<UUID, TpaRequest> map = incoming.computeIfAbsent(target.getUniqueId(), k -> new LinkedHashMap<>());
        map.remove(requester.getUniqueId());
        TpaRequest req = new TpaRequest(requester.getUniqueId(), requester.getName(), target.getUniqueId(),
                target.getName(), here, System.currentTimeMillis());
        map.put(requester.getUniqueId(), req);
        int timeout = timeoutSeconds();
        m.msg(requester, here ? "tpa.sent-here" : "tpa.sent", "player", target.getName(), "seconds", timeout);
        m.msg(target, here ? "tpa.received-here" : "tpa.received", "player", requester.getName(), "seconds", timeout);
        target.sendMessage(buttons(requester.getName()));
        m.api().services().get(SoundService.class).ifPresent(sounds -> {
            sounds.play(requester, "teleport-request");
            sounds.play(target, "teleport-request");
        });
        return true;
    }

    /** 构建可点击的 [接受] [拒绝] 按钮。 */
    private Component buttons(String requesterName) {
        Component accept = m.api().messages().parse(m.raw("tpa.button-accept"))
                .clickEvent(ClickEvent.runCommand("/tpaccept " + requesterName))
                .hoverEvent(HoverEvent.showText(m.api().messages().parse(m.raw("tpa.button-accept-hover", "player", requesterName))));
        Component deny = m.api().messages().parse(m.raw("tpa.button-deny"))
                .clickEvent(ClickEvent.runCommand("/tpdeny " + requesterName))
                .hoverEvent(HoverEvent.showText(m.api().messages().parse(m.raw("tpa.button-deny-hover", "player", requesterName))));
        return Component.empty().append(accept).append(Component.text("  ")).append(deny);
    }

    /** 查找发给 target 的请求；fromName 为 null 时取最近一条。 */
    public TpaRequest find(UUID target, String fromName) {
        LinkedHashMap<UUID, TpaRequest> map = incoming.get(target);
        if (map == null || map.isEmpty()) return null;
        if (fromName == null) {
            TpaRequest last = null;
            for (TpaRequest r : map.values()) last = r;
            return last;
        }
        for (TpaRequest r : map.values()) {
            if (r.requesterName().equalsIgnoreCase(fromName)) return r;
        }
        return null;
    }

    public void remove(TpaRequest r) {
        LinkedHashMap<UUID, TpaRequest> map = incoming.get(r.target());
        if (map != null) {
            map.remove(r.requester());
            if (map.isEmpty()) incoming.remove(r.target());
        }
    }

    public List<TpaRequest> all(UUID target) {
        LinkedHashMap<UUID, TpaRequest> map = incoming.get(target);
        return map == null ? Collections.emptyList() : new ArrayList<>(map.values());
    }

    public void accept(Player target, TpaRequest r) {
        remove(r);
        Player requester = Bukkit.getPlayer(r.requester());
        if (requester == null) {
            m.msg(target, "tpa.requester-offline", "player", r.requesterName());
            return;
        }
        m.msg(target, "tpa.accepted-target", "player", requester.getName());
        m.msg(requester, "tpa.accepted-requester", "player", target.getName());
        if (r.here()) {
            // 被请求者（target）传送到请求者处，由请求者付费
            m.teleports().submit(m.teleports().request(target, "tpahere")
                    .payer(requester)
                    .to(() -> requester.isOnline() ? requester.getLocation() : null)
                    .targetFail("tpa.target-gone")
                    .success("tpa.teleported", "player", requester.getName())
                    .onSuccess(l -> m.teleports().setCooldown(requester, "tpahere")));
        } else {
            m.teleports().submit(m.teleports().request(requester, "tpa")
                    .to(() -> target.isOnline() ? target.getLocation() : null)
                    .targetFail("tpa.target-gone")
                    .success("tpa.teleported", "player", target.getName()));
        }
    }

    public void deny(Player target, TpaRequest r) {
        remove(r);
        m.msg(target, "tpa.denied-target", "player", r.requesterName());
        Player requester = Bukkit.getPlayer(r.requester());
        if (requester != null) m.msg(requester, "tpa.denied-requester", "player", target.getName());
    }

    /** 取消 requester 发出的请求；targetName 为 null 时取消全部。返回取消数量。 */
    public int cancel(Player requester, String targetName) {
        int n = 0;
        for (Iterator<Map.Entry<UUID, LinkedHashMap<UUID, TpaRequest>>> it = incoming.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, LinkedHashMap<UUID, TpaRequest>> e = it.next();
            TpaRequest r = e.getValue().get(requester.getUniqueId());
            if (r == null) continue;
            if (targetName != null && !r.targetName().equalsIgnoreCase(targetName)) continue;
            e.getValue().remove(requester.getUniqueId());
            if (e.getValue().isEmpty()) it.remove();
            n++;
            Player t = Bukkit.getPlayer(r.target());
            if (t != null) m.msg(t, "tpa.cancelled-target", "player", requester.getName());
        }
        return n;
    }

    /** 每秒调用，清理超时请求。 */
    public void tick() {
        long now = System.currentTimeMillis();
        long timeout = timeoutSeconds() * 1000L;
        for (Iterator<Map.Entry<UUID, LinkedHashMap<UUID, TpaRequest>>> it = incoming.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, LinkedHashMap<UUID, TpaRequest>> e = it.next();
            for (Iterator<TpaRequest> ri = e.getValue().values().iterator(); ri.hasNext(); ) {
                TpaRequest r = ri.next();
                if (now - r.created() < timeout) continue;
                ri.remove();
                Player req = Bukkit.getPlayer(r.requester());
                if (req != null) m.msg(req, "tpa.expired-requester", "player", r.targetName());
                Player tgt = Bukkit.getPlayer(r.target());
                if (tgt != null) m.msg(tgt, "tpa.expired-target", "player", r.requesterName());
            }
            if (e.getValue().isEmpty()) it.remove();
        }
    }

    /** 玩家退出：清除其收到与发出的请求。 */
    public void forget(UUID uuid) {
        incoming.remove(uuid);
        for (Iterator<Map.Entry<UUID, LinkedHashMap<UUID, TpaRequest>>> it = incoming.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, LinkedHashMap<UUID, TpaRequest>> e = it.next();
            e.getValue().remove(uuid);
            if (e.getValue().isEmpty()) it.remove();
        }
    }

    public int timeoutSeconds() {
        return Math.max(5, m.config().getInt("tpa.timeout", 120));
    }

    public void clear() {
        incoming.clear();
    }
}
