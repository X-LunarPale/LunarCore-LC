package com.lunarboreal.permissions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class LegacyPermissionsTest {
    @TempDir Path directory;

    private static String legacy(String suffix) { return "l" + "br." + suffix; }

    @Test void importsDenyAndTemporaryGrantsWhileDroppingExpiredEntries() {
        var grants=LegacyPermissions.grants(Map.of(
                legacy("chat.use"),new PermissionState.Grant(false,0),
                "external.use",new PermissionState.Grant(true,200),
                legacy("expired"),new PermissionState.Grant(true,100)),100);
        assertEquals(2,grants.size());
        assertTrue(grants.contains(new LegacyPermissions.Entry("lc.chat.use",false,0,false)));
        assertTrue(grants.contains(new LegacyPermissions.Entry("external.use",true,200,false)));
    }

    @Test void preservesPermanentAndTemporaryMemberships() {
        var entries=LegacyPermissions.memberships(Map.of("default",new PermissionState.Membership(0),
                "helper",new PermissionState.Membership(200),"expired",new PermissionState.Membership(99)),100);
        assertEquals(Set.of(new LegacyPermissions.Entry("default",true,0,true),
                new LegacyPermissions.Entry("helper",true,200,true)),Set.copyOf(entries));
    }

    @Test void readsFormerDocumentWithoutChangingIt() throws Exception {
        UUID id=UUID.randomUUID();
        String json="""
                {"groups":{"default":{"name":"default","weight":5,"parents":[],"permissions":{"old.use":{"value":false,"expiresAt":0}}}},
                 "users":{"%s":{"uuid":"%s","name":"Player","groups":{"default":{"expiresAt":12345}},"permissions":{}}},
                 "defaultGroup":"default"}
                """.formatted(id,id).replace("old.use",legacy("use"));
        Path file=directory.resolve("permissions.json");
        Files.writeString(file,json);
        var state=LegacyPermissions.read(file);
        assertEquals(5,state.groups().get("default").weight());
        assertFalse(state.groups().get("default").permissions().get(legacy("use")).value());
        assertEquals(12345,state.users().get(id).groups().get("default").expiresAt());
        assertEquals(json,Files.readString(file));
    }

    @Test void acceptsLuckPermsNodesAndDurationSyntax() {
        assertEquals("plugin.*.use",LuckPermsBridge.permission("Plugin.*.Use"));
        assertThrows(IllegalArgumentException.class,()->LuckPermsBridge.permission("invalid node"));
        assertEquals(0,LuckPermsBridge.expiry(""));
        long start=System.currentTimeMillis();
        assertTrue(LuckPermsBridge.expiry("2h")>=start+7200000L);
        assertThrows(IllegalArgumentException.class,()->LuckPermsBridge.expiry("-1d"));
    }
}
