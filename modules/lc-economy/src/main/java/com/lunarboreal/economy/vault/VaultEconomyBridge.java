package com.lunarboreal.economy.vault;

import com.lunarboreal.api.service.EconomyService.Result;
import com.lunarboreal.economy.EconomyModule;
import com.lunarboreal.economy.EconomyServiceImpl;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Vault Economy 实现，全部委托给 {@link EconomyServiceImpl}。
 * 本类引用 Vault API，只能在确认 Vault 类可见后由 {@link VaultHook} 加载。
 * 不支持银行（hasBankSupport = false）；世界参数被忽略（全局经济）。
 */
final class VaultEconomyBridge implements Economy {

    private final EconomyModule module;
    private final EconomyServiceImpl eco;

    VaultEconomyBridge(EconomyModule module, EconomyServiceImpl eco) {
        this.module = module;
        this.eco = eco;
    }

    private static BigDecimal dec(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return null;
        return BigDecimal.valueOf(v);
    }

    /**
     * 按名字解析 UUID：只使用本地缓存（不触发 Mojang 网络请求），未知名字返回 null
     * —— 此时查询类方法返回 false/0，操作类方法返回 FAILURE，且不会为任意名字建账户。
     */
    private UUID byName(String name) {
        if (name == null || name.isEmpty()) return null;
        UUID u = eco.resolve(name);
        if (u != null) return u;
        OfflinePlayer op = Bukkit.getOfflinePlayerIfCached(name);
        return op == null ? null : op.getUniqueId();
    }

    private EconomyResponse response(double amount, Result r) {
        return new EconomyResponse(amount, r.balance() == null ? 0 : r.balance().doubleValue(),
                r.success() ? EconomyResponse.ResponseType.SUCCESS : EconomyResponse.ResponseType.FAILURE,
                r.success() ? null : module.errorText(r.error()));
    }

    private EconomyResponse withdraw(UUID uuid, double amount) {
        return response(amount, eco.withdraw(uuid, dec(amount), "Vault 扣款", "vault"));
    }

    private EconomyResponse deposit(UUID uuid, double amount) {
        return response(amount, eco.deposit(uuid, dec(amount), "Vault 存款", "vault"));
    }

    private static EconomyResponse noBank() {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "LunarCore 经济不支持银行");
    }

    @Override public boolean isEnabled() { return module.isEnabled(); }
    @Override public String getName() { return module.settings().vaultName; }
    @Override public boolean hasBankSupport() { return false; }
    @Override public int fractionalDigits() { return module.settings().decimals; }
    @Override public String format(double amount) { BigDecimal d = dec(amount); return eco.format(d == null ? BigDecimal.ZERO : d); }
    @Override public String currencyNamePlural() { return eco.currencyPlural(); }
    @Override public String currencyNameSingular() { return eco.currencySingular(); }

    @Override public boolean hasAccount(String name) { return eco.hasAccount(byName(name)); }
    @Override public boolean hasAccount(OfflinePlayer p) { return eco.hasAccount(p.getUniqueId()); }
    @Override public boolean hasAccount(String name, String world) { return hasAccount(name); }
    @Override public boolean hasAccount(OfflinePlayer p, String world) { return hasAccount(p); }

    @Override public double getBalance(String name) { return eco.balance(byName(name)).doubleValue(); }
    @Override public double getBalance(OfflinePlayer p) { return eco.balance(p.getUniqueId()).doubleValue(); }
    @Override public double getBalance(String name, String world) { return getBalance(name); }
    @Override public double getBalance(OfflinePlayer p, String world) { return getBalance(p); }

    @Override public boolean has(String name, double amount) { BigDecimal d = dec(amount); return d != null && eco.has(byName(name), d); }
    @Override public boolean has(OfflinePlayer p, double amount) { BigDecimal d = dec(amount); return d != null && eco.has(p.getUniqueId(), d); }
    @Override public boolean has(String name, String world, double amount) { return has(name, amount); }
    @Override public boolean has(OfflinePlayer p, String world, double amount) { return has(p, amount); }

    @Override public EconomyResponse withdrawPlayer(String name, double amount) { return withdraw(byName(name), amount); }
    @Override public EconomyResponse withdrawPlayer(OfflinePlayer p, double amount) { return withdraw(p.getUniqueId(), amount); }
    @Override public EconomyResponse withdrawPlayer(String name, String world, double amount) { return withdrawPlayer(name, amount); }
    @Override public EconomyResponse withdrawPlayer(OfflinePlayer p, String world, double amount) { return withdrawPlayer(p, amount); }

    @Override public EconomyResponse depositPlayer(String name, double amount) { return deposit(byName(name), amount); }
    @Override public EconomyResponse depositPlayer(OfflinePlayer p, double amount) { return deposit(p.getUniqueId(), amount); }
    @Override public EconomyResponse depositPlayer(String name, String world, double amount) { return depositPlayer(name, amount); }
    @Override public EconomyResponse depositPlayer(OfflinePlayer p, String world, double amount) { return depositPlayer(p, amount); }

    @Override public EconomyResponse createBank(String name, String player) { return noBank(); }
    @Override public EconomyResponse createBank(String name, OfflinePlayer player) { return noBank(); }
    @Override public EconomyResponse deleteBank(String name) { return noBank(); }
    @Override public EconomyResponse bankBalance(String name) { return noBank(); }
    @Override public EconomyResponse bankHas(String name, double amount) { return noBank(); }
    @Override public EconomyResponse bankWithdraw(String name, double amount) { return noBank(); }
    @Override public EconomyResponse bankDeposit(String name, double amount) { return noBank(); }
    @Override public EconomyResponse isBankOwner(String name, String player) { return noBank(); }
    @Override public EconomyResponse isBankOwner(String name, OfflinePlayer player) { return noBank(); }
    @Override public EconomyResponse isBankMember(String name, String player) { return noBank(); }
    @Override public EconomyResponse isBankMember(String name, OfflinePlayer player) { return noBank(); }
    @Override public List<String> getBanks() { return List.of(); }

    @Override public boolean createPlayerAccount(String name) {
        UUID u = byName(name);
        if (u == null) return false;
        eco.createAccount(u, name);
        return true;
    }
    @Override public boolean createPlayerAccount(OfflinePlayer p) { eco.createAccount(p.getUniqueId(), p.getName()); return true; }
    @Override public boolean createPlayerAccount(String name, String world) { return createPlayerAccount(name); }
    @Override public boolean createPlayerAccount(OfflinePlayer p, String world) { return createPlayerAccount(p); }
}
