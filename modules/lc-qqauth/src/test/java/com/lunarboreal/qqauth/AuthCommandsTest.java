package com.lunarboreal.qqauth;

import com.lunarboreal.api.LC;
import com.lunarboreal.api.LunarBorealAPI;
import com.lunarboreal.api.module.ModuleManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AuthCommandsTest {
    @AfterEach void cleanup() { LC.setInstance(null); }

    @Test void reloadRebuildsTheModuleEvenWhenNotReady() {
        List<String> calls = new ArrayList<>();
        List<String> replies = new ArrayList<>();
        installManager(calls, true);
        AuthCommands.execute(new QQAuthModule(), sender(true, replies), "qqauth", new String[]{"reload"});
        assertEquals(List.of("qqauth"), calls);
        assertEquals(List.of("QQAuth 已重载，请重新连接服务器。"), replies);
    }

    @Test void reloadReportsFailureAndHonorsPermission() {
        List<String> calls = new ArrayList<>();
        List<String> replies = new ArrayList<>();
        installManager(calls, false);
        AuthCommands.execute(new QQAuthModule(), sender(true, replies), "qqauth", new String[]{"reload"});
        assertEquals(List.of("qqauth"), calls);
        assertEquals(List.of("QQAuth 重载失败，请查看控制台日志。"), replies);
        calls.clear();
        AuthCommands.execute(new QQAuthModule(), sender(false, replies), "qqauth", new String[]{"reload"});
        assertTrue(calls.isEmpty());
    }

    private void installManager(List<String> calls, boolean success) {
        ModuleManager manager = (ModuleManager) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{ModuleManager.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("reload")) throw new UnsupportedOperationException(method.getName());
                    calls.add((String) args[0]);
                    return success;
                });
        LC.setInstance((LunarBorealAPI) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{LunarBorealAPI.class}, (proxy, method, args) -> {
                    if (method.getName().equals("modules")) return manager;
                    throw new UnsupportedOperationException(method.getName());
                }));
    }

    private CommandSender sender(boolean allowed, List<String> replies) {
        return (CommandSender) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{CommandSender.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "hasPermission" -> allowed;
                    case "sendMessage" -> {
                        if (args[0] instanceof Component component)
                            replies.add(PlainTextComponentSerializer.plainText().serialize(component));
                        yield null;
                    }
                    default -> null;
                });
    }
}
