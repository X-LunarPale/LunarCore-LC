package com.lunarboreal.api.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * 经济服务（由 economy 模块提供，并桥接到 Vault）。
 * 所有方法线程安全，内部带内存缓存，可在主线程调用。
 */
public interface EconomyService {

    record Result(boolean success, BigDecimal balance, String error) {
        public static Result ok(BigDecimal balance) { return new Result(true, balance, null); }
        public static Result fail(BigDecimal balance, String error) { return new Result(false, balance, error); }
    }

    record TopEntry(UUID uuid, String name, BigDecimal balance) {}

    String currencySingular();

    String currencyPlural();

    String format(BigDecimal amount);

    boolean hasAccount(UUID player);

    void createAccount(UUID player, String name);

    BigDecimal balance(UUID player);

    boolean has(UUID player, BigDecimal amount);

    /** reason 会写入流水，便于 web 面板查账。 */
    Result withdraw(UUID player, BigDecimal amount, String reason);

    Result deposit(UUID player, BigDecimal amount, String reason);

    Result set(UUID player, BigDecimal amount, String reason);

    Result transfer(UUID from, UUID to, BigDecimal amount, String reason);

    List<TopEntry> top(int limit);
}
