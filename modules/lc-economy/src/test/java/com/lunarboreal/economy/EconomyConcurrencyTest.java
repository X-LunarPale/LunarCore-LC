package com.lunarboreal.economy;

import com.lunarboreal.api.database.Database;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class EconomyConcurrencyTest {
    private EconomyServiceImpl service() throws Exception {
        EconomyModule module = new EconomyModule();
        var settings = EconomyModule.class.getDeclaredField("settings");
        settings.setAccessible(true);
        settings.set(module, new EconomySettings(new YamlConfiguration()));
        Database db = (Database) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Database.class},
                (proxy, method, args) -> method.getName().equals("table") ? "test_" + args[0] : null);
        EconomyServiceImpl service = new EconomyServiceImpl(module, db);
        var loaded = EconomyServiceImpl.class.getDeclaredField("loaded");
        loaded.setAccessible(true);
        loaded.set(service, true);
        return service;
    }

    @Test void concurrentWithdrawalsCannotOverdraw() throws Exception {
        EconomyServiceImpl service = service();
        UUID player = UUID.randomUUID();
        service.getOrCreate(player, "player");
        assertTrue(service.set(player, new BigDecimal("100"), "test").success());
        AtomicInteger wins = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(8)) {
            for (int i = 0; i < 200; i++) executor.submit(() -> {
                if (service.withdraw(player, BigDecimal.ONE, "test").success()) wins.incrementAndGet();
            });
        }
        assertEquals(100, wins.get());
        assertEquals(0, service.balance(player).signum());
        assertFalse(service.withdraw(player, new BigDecimal("-1"), "test").success());
    }

    @Test void transferRejectionDoesNotDebitSender() throws Exception {
        EconomyServiceImpl service = service();
        UUID sender = UUID.randomUUID(), receiver = UUID.randomUUID();
        service.getOrCreate(sender, "sender");
        service.getOrCreate(receiver, "receiver");
        service.set(sender, new BigDecimal("10"), "test");
        assertFalse(service.transfer(sender, receiver, new BigDecimal("11"), "test").success());
        assertEquals(0, new BigDecimal("10").compareTo(service.balance(sender)));
        assertEquals(0, service.balance(receiver).signum());
    }
}
