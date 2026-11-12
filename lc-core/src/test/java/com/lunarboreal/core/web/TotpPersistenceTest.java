package com.lunarboreal.core.web;

import com.lunarboreal.api.database.Database;
import com.lunarboreal.api.database.RowMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class TotpPersistenceTest {
    @Test void encryptsSecretsAndPreservesRecoveryConsumptionAcrossReloads() throws Exception {
        Map<String, Object[]> rows = new HashMap<>();
        Database db = (Database) Proxy.newProxyInstance(Database.class.getClassLoader(), new Class[]{Database.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "table" -> "test_" + args[0];
                    case "upsert" -> "UPSERT";
                    case "update" -> {
                        if ("UPSERT".equals(args[0])) {
                            Object[] values = ((Object[]) args[1]).clone();
                            rows.put((String) values[0], values);
                        }
                        yield 1;
                    }
                    case "query" -> {
                        List<Object> result = new ArrayList<>();
                        RowMapper<?> mapper = (RowMapper<?>) args[1];
                        for (Object[] row : rows.values()) {
                            ResultSet rs = (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(),
                                    new Class[]{ResultSet.class}, (p, m, a) -> switch (m.getName()) {
                                        case "getString" -> (String) row[(Integer) a[0] - 1];
                                        case "getLong" -> (Long) row[(Integer) a[0] - 1];
                                        default -> throw new UnsupportedOperationException(m.getName());
                                    });
                            result.add(mapper.map(rs));
                        }
                        yield result;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        var manager = new TotpManager(db, "fixture-jwt-secret-not-for-production");
        var user = new AuthManager.UserRow("alice", "hash", "viewer", 0, 0, "", null, null, 3);
        var enrollment = manager.begin(user);
        var recovery = manager.confirm(user, 3,
                Totp.code(Totp.decode(enrollment.secret()), System.currentTimeMillis() / 30_000, 6));
        assertFalse(List.of(rows.get("alice")).toString().contains(enrollment.secret()));
        assertNotEquals(enrollment.secret(), rows.get("alice")[1]);
        for (String code : recovery) assertFalse(((String) rows.get("alice")[3]).contains(code.replace("-", "")));
        assertTrue(manager.verify("alice", recovery.getFirst()));
        var reloaded = new TotpManager(db, "fixture-jwt-secret-not-for-production");
        assertTrue(reloaded.enabled("alice"));
        assertFalse(reloaded.verify("alice", recovery.getFirst()));
        assertTrue(reloaded.verify("alice", recovery.get(1)));
        var changedKey = new TotpManager(db, "different-jwt-secret");
        assertThrows(IllegalStateException.class, () -> changedKey.verify("alice", "123456"));
    }
}
