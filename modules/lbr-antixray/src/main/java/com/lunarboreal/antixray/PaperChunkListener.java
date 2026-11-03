package com.lunarboreal.antixray;

import io.papermc.paper.event.packet.PlayerChunkLoadEvent;
import io.papermc.paper.event.packet.PlayerChunkUnloadEvent;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/** Paper 专有：区块发送/卸载事件，驱动假矿即时伪装（仅在 Paper 上注册）。 */
final class PaperChunkListener implements Listener {

    private final FakeOreEngine engine;

    PaperChunkListener(FakeOreEngine engine) {
        this.engine = engine;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(PlayerChunkLoadEvent e) {
        if (!engine.isEnabled()) return;
        main(() -> engine.onChunkSent(e.getPlayer(), e.getChunk()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(PlayerChunkUnloadEvent e) {
        if (!engine.isEnabled()) return;
        main(() -> engine.onChunkUnsent(e.getPlayer(), e.getChunk()));
    }

    /** 引擎状态为主线程独占；若事件在其它线程触发则切回主线程。 */
    private void main(Runnable r) {
        if (Bukkit.isPrimaryThread()) r.run();
        else Bukkit.getScheduler().runTask(engine.plugin(), r);
    }
}
