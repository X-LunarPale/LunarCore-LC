package com.lunarboreal.core.command;

import com.lunarboreal.api.command.LbrCommand;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class LbrCommandRegistryTest {
    /** Models Paper 26.3 KeySet.iterator(): values transformed with Command::getName. */
    private static final class ForwardingMap extends LinkedHashMap<String, Command> {
        @Override public Set<String> keySet() {
            return new AbstractSet<>() {
                @Override public int size() { return ForwardingMap.this.size(); }
                @Override public Iterator<String> iterator() {
                    return new ArrayList<>(values()).stream().map(Command::getName).iterator();
                }
            };
        }
    }

    private static Command command(String name) {
        return new Command(name) {
            @Override public boolean execute(CommandSender sender, String label, String[] args) { return true; }
        };
    }

    @Test void removesEveryAliasAndNamespaceAndRestoresNativeCommands() {
        Map<String, Command> known = new ForwardingMap();
        Command wrapper = command("unban");
        Command nativePardon = command("pardon");
        Command nativePardonIp = command("pardon-ip");
        Map<String, Command> displaced = new LinkedHashMap<>();
        for (String label : List.of("pardon", "minecraft:pardon")) displaced.put(label, nativePardon);
        for (String label : List.of("pardon-ip", "minecraft:pardon-ip")) displaced.put(label, nativePardonIp);
        List<String> labels = List.of("unban", "pardon", "lc:unban", "lc:pardon", "lbr:unban", "lbr:pardon",
                "unbanip", "pardon-ip", "unipban", "lbr:unbanip", "lbr:pardon-ip", "lbr:unipban");
        labels.forEach(label -> known.put(label, wrapper));
        // This is the real server failure: keySet iteration cannot see "pardon".
        assertFalse(new ArrayList<>(known.keySet()).contains("pardon"));
        assertEquals(labels, LbrCommandRegistry.ownedLabels(known, wrapper));

        LbrCommandRegistry.restoreMappings(known, wrapper, displaced);

        assertTrue(LbrCommandRegistry.ownedLabels(known, wrapper).isEmpty());
        assertEquals(4, known.size());
        displaced.forEach((label, original) -> assertSame(original, known.get(label), label));
        assertNull(known.get("unban"));
        assertNull(known.get("lbr:pardon"));
        assertNull(known.get("lc:pardon"));
    }

    @Test void preservesLaterOwnerAndRestoresFallbackNamespaceCollision() {
        Map<String, Command> known = new ForwardingMap();
        Command wrapper = command("ban");
        Command original = command("ban");
        Command later = command("ban");
        known.put("ban", later);
        known.put(LbrCommandRegistry.NAMESPACE + ":ban", wrapper);
        known.put("minecraft:ban", later);
        LbrCommandRegistry.restoreMappings(known, wrapper,
                Map.of("ban", original, "minecraft:ban", original, "lc:ban", original));
        assertSame(later, known.get("ban"));
        assertSame(later, known.get("minecraft:ban"));
        assertSame(original, known.get("lc:ban"));
    }

    @Test void repeatedEnableDisableRestoresEveryBanFamilyLabel() {
        Map<String, Command> known = new ForwardingMap();
        Map<String, List<String>> families = new LinkedHashMap<>();
        families.put("ban", List.of("ban"));
        families.put("ipban", List.of("ipban", "ban-ip", "banip"));
        families.put("unban", List.of("unban", "pardon"));
        families.put("unbanip", List.of("unbanip", "pardon-ip", "unipban"));
        families.put("kick", List.of("kick"));
        families.put("banlist", List.of("banlist"));
        Map<String, Command> originals = new LinkedHashMap<>();
        for (String name : List.of("ban", "ban-ip", "pardon", "pardon-ip", "kick", "banlist")) {
            Command vanilla = command(name);
            originals.put(name, vanilla);
            originals.put("minecraft:" + name, vanilla);
        }
        known.putAll(originals);
        for (int cycle = 0; cycle < 3; cycle++) {
            Map<Command, Map<String, Command>> displacedByCommand = new LinkedHashMap<>();
            families.forEach((name, aliases) -> {
                Command wrapper = command(name);
                Map<String, Command> displaced = new LinkedHashMap<>();
                for (String alias : aliases) {
                    for (String key : List.of(alias, "minecraft:" + alias, "lc:" + alias, "lbr:" + alias)) {
                        Command previous = known.get(key);
                        if (previous != null) displaced.put(key, previous);
                        if (key.startsWith("minecraft:")) known.remove(key);
                        else known.put(key, wrapper);
                    }
                }
                displacedByCommand.put(wrapper, displaced);
            });
            displacedByCommand.forEach((wrapper, displaced) ->
                    LbrCommandRegistry.restoreMappings(known, wrapper, displaced));
            assertEquals(originals.size(), known.size());
            originals.forEach((label, original) -> assertSame(original, known.get(label), label));
        }
    }

    @Test void retainedWrappersCannotExecuteOrCompleteAfterUnregisterOrOwnerDisable() {
        AtomicBoolean enabled = new AtomicBoolean(true);
        AtomicInteger executions = new AtomicInteger();
        AtomicInteger completions = new AtomicInteger();
        LbrCommand command = new LbrCommand("unban", null, "pardon") {
            @Override public void execute(CommandSender sender, String label, String[] args) {
                executions.incrementAndGet();
            }
            @Override public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
                completions.incrementAndGet();
                return List.of("player");
            }
        };
        LbrCommandRegistry.Wrapper old = new LbrCommandRegistry.Wrapper(null, command, enabled::get);
        old.active = true;
        assertTrue(old.execute(null, "pardon", new String[]{"player"}));
        assertEquals(List.of("player"), old.tabComplete(null, "pardon", new String[]{""}));
        old.active = false;
        old.execute(null, "lbr:pardon", new String[]{"player"});
        assertTrue(old.tabComplete(null, "pardon", new String[]{""}, null).isEmpty());
        // Re-enabling an owner must never reactivate a retained old wrapper.
        enabled.set(false);
        enabled.set(true);
        old.execute(null, "pardon", new String[]{"player"});
        assertTrue(old.tabComplete(null, "pardon", new String[]{""}).isEmpty());
        assertEquals(1, executions.get());
        assertEquals(1, completions.get());

        LbrCommandRegistry.Wrapper current = new LbrCommandRegistry.Wrapper(null, command, enabled::get);
        current.active = true;
        enabled.set(false);
        current.execute(null, "pardon", new String[]{"player"});
        assertTrue(current.tabComplete(null, "pardon", new String[]{""}).isEmpty());
        assertEquals(1, executions.get());
        assertEquals(1, completions.get());
    }
}
