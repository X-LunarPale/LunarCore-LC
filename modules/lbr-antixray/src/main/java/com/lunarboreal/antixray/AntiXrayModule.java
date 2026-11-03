package com.lunarboreal.antixray;

import com.lunarboreal.api.module.LbrModule;
import org.bukkit.Bukkit;
import org.bukkit.permissions.PermissionDefault;

import java.util.Locale;
import java.util.Map;

/**
 * 反矿透模块：
 * <ol>
 *   <li>方案一：管理 Paper 内置 anti-xray 配置（{@link PaperXrayConfig}）；</li>
 *   <li>方案二：自研轻量假矿（{@link FakeOreEngine}，默认关闭）；</li>
 *   <li>方案三：挖矿行为检测与告警（{@link MiningMonitor}）。</li>
 * </ol>
 */
public final class AntiXrayModule extends LbrModule {

    private FakeOreEngine fakeOre;
    private MiningMonitor monitor;

    @Override
    public void onEnable() {
        registerPermission("lbr.antixray.admin", PermissionDefault.OP, "使用 /antixray 管理命令");
        registerPermission("lbr.antixray.paper", PermissionDefault.OP, "修改 Paper anti-xray 配置");
        registerPermission("lbr.antixray.notify", PermissionDefault.OP, "接收矿透告警");
        registerPermission("lbr.antixray.bypass", PermissionDefault.FALSE, "不被检测、不被假矿伪装");

        monitor = new MiningMonitor(this, api().database());
        monitor.init();
        monitor.reload();
        registerListener(monitor);

        fakeOre = new FakeOreEngine(this);
        registerListener(fakeOre);
        if (api().isPaper()) registerListener(new PaperChunkListener(fakeOre));
        fakeOre.start(api().isPaper());

        // 每 5 分钟写回挖矿统计
        runAsyncTimer(() -> monitor.flushAsync(), 20L * 300, 20L * 300);

        registerCommand(new AntiXrayCommand(this).usage("/antixray <stats|alerts|top|paper|fakeore|reload>"));

        placeholder((player, key) -> {
            if (player == null) return null;
            MiningMonitor.Session s = monitor.session(player.getUniqueId());
            return switch (key.toLowerCase(Locale.ROOT)) {
                case "alerts" -> String.valueOf(s == null ? 0 : s.alerts);
                case "veins" -> String.valueOf(s == null ? 0 : s.totalVeins);
                default -> {
                    if (key.toLowerCase(Locale.ROOT).startsWith("mined_") && s != null) {
                        Long v = s.counts.get(key.substring(6).toUpperCase(Locale.ROOT));
                        yield String.valueOf(v == null ? 0 : v);
                    }
                    yield null;
                }
            };
        });

        new AntiXrayWeb(this).register();
        if (!api().isPaper()) logger().info("当前不是 Paper 服务端：Paper anti-xray 设置不可用，可开启 fake-ore 方案二。");
    }

    @Override
    public void onDisable() {
        if (fakeOre != null) fakeOre.stop();
        if (monitor != null) monitor.flushBlocking();
    }

    @Override
    public void onReload() {
        reloadConfig();
        monitor.reload();
        fakeOre.reload();
    }

    FakeOreEngine fakeOre() { return fakeOre; }

    MiningMonitor monitor() { return monitor; }

    boolean paperAvailable() { return api().isPaper(); }

    /** 在主线程执行（若已在主线程直接执行）。 */
    void onMain(Runnable r) {
        if (Bukkit.isPrimaryThread()) r.run();
        else Bukkit.getScheduler().runTask(plugin(), () -> { if (isEnabled()) r.run(); });
    }

    static String summary(Map<String, Object> s) {
        return "enabled=" + s.get("enabled") + ", engine-mode=" + s.get("engine-mode") + ", max-block-height=" + s.get("max-block-height");
    }
}
