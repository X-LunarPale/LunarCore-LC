package com.lunarboreal.signin;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class SigninStoreTest {
    @Test void concurrentMarksHaveExactlyOneWinner() throws Exception {
        SigninStore store = new SigninStore(nullDatabase());
        SigninStore.PlayerData data = new SigninStore.PlayerData(UUID.randomUUID(), "player");
        LocalDate date = LocalDate.of(2026, 10, 17);
        AtomicInteger wins = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(8)) {
            for (int i = 0; i < 100; i++) executor.submit(() -> {
                if (store.mark(data, date, false)) wins.incrementAndGet();
            });
        }
        assertEquals(1, wins.get());
        assertEquals(1, data.total());
        assertEquals(1, data.streak(date));
    }

    @Test void mergingPendingDatesIsIdempotent() {
        SigninStore store = new SigninStore(nullDatabase());
        SigninStore.PlayerData data = new SigninStore.PlayerData(UUID.randomUUID(), "player");
        LocalDate today = LocalDate.of(2026, 10, 17);
        assertTrue(store.mark(data, today.minusDays(1), true));
        assertFalse(store.mark(data, today.minusDays(1), true));
        assertTrue(store.mark(data, today, false));
        assertEquals(2, data.streak(today));
        assertEquals(1, data.makeupCount(YearMonth.from(today)));
    }

    private com.lunarboreal.api.database.Database nullDatabase() {
        return (com.lunarboreal.api.database.Database) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{com.lunarboreal.api.database.Database.class},
                (proxy, method, args) -> method.getName().equals("table") ? "test_" + args[0] : null);
    }
}
