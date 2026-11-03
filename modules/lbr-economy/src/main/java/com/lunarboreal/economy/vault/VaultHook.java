package com.lunarboreal.economy.vault;

import com.lunarboreal.economy.EconomyModule;
import com.lunarboreal.economy.EconomyServiceImpl;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicePriority;

/**
 * Vault 注册/注销。EconomyModule 只以 Object 形式持有本类实例，且只在 Vault 类可见时才加载本类，
 * 因此未安装 Vault 时不会触发 NoClassDefFoundError。
 */
public final class VaultHook {

    private final VaultEconomyBridge bridge;

    private VaultHook(VaultEconomyBridge bridge) {
        this.bridge = bridge;
    }

    public static VaultHook register(EconomyModule module, EconomyServiceImpl eco) {
        VaultEconomyBridge bridge = new VaultEconomyBridge(module, eco);
        Bukkit.getServicesManager().register(Economy.class, bridge, module.plugin(), ServicePriority.High);
        return new VaultHook(bridge);
    }

    public void unregister() {
        Bukkit.getServicesManager().unregister(Economy.class, bridge);
    }
}
