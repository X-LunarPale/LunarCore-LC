package com.lunarboreal.teleport;

import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataAdapterContext;
import org.bukkit.persistence.PersistentDataContainer;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

final class TestPersistentData {
    private TestPersistentData() {}

    static PersistentDataContainer container() {
        Map<NamespacedKey, Object[]> values = new HashMap<>();
        var context = (PersistentDataAdapterContext) Proxy.newProxyInstance(
                PersistentDataAdapterContext.class.getClassLoader(),
                new Class<?>[]{PersistentDataAdapterContext.class},
                (proxy, method, args) -> method.getName().equals("newPersistentDataContainer") ? container() : null);
        return (PersistentDataContainer) Proxy.newProxyInstance(PersistentDataContainer.class.getClassLoader(),
                new Class<?>[]{PersistentDataContainer.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "set" -> { values.put((NamespacedKey) args[0], new Object[]{args[1], args[2]}); yield null; }
                    case "get" -> {
                        Object[] value = values.get(args[0]);
                        yield value != null && value[0] == args[1] ? value[1] : null;
                    }
                    case "has" -> values.containsKey(args[0])
                            && (args.length == 1 || values.get(args[0])[0] == args[1]);
                    case "remove" -> { values.remove(args[0]); yield null; }
                    case "getAdapterContext" -> context;
                    case "getKeys" -> Set.copyOf(values.keySet());
                    case "isEmpty" -> values.isEmpty();
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "PlayerData";
                    default -> null;
                });
    }
}
