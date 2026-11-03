package com.lunarboreal.permissions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PermissionEngineTest {
    @TempDir Path dir;
    private final UUID id=UUID.randomUUID();
    private PermissionState.Grant grant(boolean value) {return new PermissionState.Grant(value,0);}
    private PermissionState.Group group(String name,int weight,Set<String> parents,Map<String,PermissionState.Grant> grants){return new PermissionState.Group(name,weight,parents,grants);}
    private PermissionState state(Map<String,PermissionState.Group> extra,Map<String,PermissionState.Membership> memberships,Map<String,PermissionState.Grant> grants){
        var groups=new HashMap<>(extra);groups.putIfAbsent("default",group("default",0,Set.of(),Map.of()));
        return new PermissionState(groups,Map.of(id,new PermissionState.User(id,"Player",memberships,grants)),"default");
    }
    @Test void directOverridesGroupsAndExactOverridesWildcard() {
        var state=state(Map.of("admin",group("admin",999,Set.of(),Map.of("core.delete",grant(true)))),Map.of("admin",new PermissionState.Membership(0)),Map.of("*",grant(false),"core.read",grant(true),"core.read.*",grant(false)));
        assertEquals(Optional.of(false),PermissionResolver.resolve(state,id,"core.delete",1));
        assertEquals(Optional.of(true),PermissionResolver.resolve(state,id,"core.read",1));
        assertEquals(Optional.of(false),PermissionResolver.resolve(state,id,"core.read.child",1));
        assertEquals(Optional.empty(),PermissionResolver.resolve(PermissionState.initial(),id,"unknown.node",1));
    }
    @Test void groupSpecificityWeightDepthAndDenyTiesAreDeterministic() {
        var groups=Map.of("default",group("default",999,Set.of(),Map.of("core.*",grant(false))),
            "child",group("child",10,Set.of("parent"),Map.of("core.use",grant(true))),
            "parent",group("parent",10,Set.of(),Map.of("core.use",grant(false))),
            "tie",group("tie",10,Set.of(),Map.of("core.use",grant(false))));
        var state=state(groups,Map.of("child",new PermissionState.Membership(0)),Map.of());
        assertEquals(Optional.of(true),PermissionResolver.resolve(state,id,"core.use",1));
        var tied=state(groups,Map.of("child",new PermissionState.Membership(0),"tie",new PermissionState.Membership(0)),Map.of());
        assertEquals(Optional.of(false),PermissionResolver.resolve(tied,id,"core.use",1));
        var heavier=state(Map.of("a",group("a",20,Set.of(),Map.of("core.use",grant(true))),"b",group("b",10,Set.of(),Map.of("core.use",grant(false)))),Map.of("a",new PermissionState.Membership(0),"b",new PermissionState.Membership(0)),Map.of());
        assertEquals(Optional.of(true),PermissionResolver.resolve(heavier,id,"core.use",1));
    }
    @Test void expiresDirectDenyAndMembershipWithAllInheritedGrants() {
        var state=state(Map.of("child",group("child",0,Set.of("parent"),Map.of()),"parent",group("parent",0,Set.of(),Map.of("a.b",grant(true)))),Map.of("child",new PermissionState.Membership(100)),Map.of("a.b",new PermissionState.Grant(false,50)));
        assertEquals(Optional.of(false),PermissionResolver.resolve(state,id,"a.b",49));
        assertEquals(Optional.of(true),PermissionResolver.resolve(state,id,"a.b",50));
        assertEquals(Optional.empty(),PermissionResolver.resolve(state,id,"a.b",100));
        assertTrue(PermissionResolver.effective(state,id,100).isEmpty());
    }
    @Test void rejectsCyclesUnknownGroupsAndCopiesMutableData() {
        assertThrows(IllegalArgumentException.class,()->state(Map.of("a",group("a",0,Set.of("b"),Map.of()),"b",group("b",0,Set.of("a"),Map.of())),Map.of(),Map.of()));
        assertThrows(IllegalArgumentException.class,()->state(Map.of(),Map.of("missing",new PermissionState.Membership(0)),Map.of()));
        assertThrows(IllegalArgumentException.class,()->PermissionState.node("a.*.b"));
        Map<String,PermissionState.Grant> mutable=new HashMap<>();mutable.put("a.b",grant(true));var group=group("x",0,Set.of(),mutable);mutable.clear();assertEquals(1,group.permissions().size());
        assertThrows(UnsupportedOperationException.class,()->group.permissions().clear());
    }
    @Test void defaultGroupAppliesToUnknownPlayersAndWildcardIsScoped() {
        var state=new PermissionState(Map.of("default",group("default",0,Set.of(),Map.of("a.*",grant(true)))),Map.of(),"default");
        assertEquals(Optional.of(true),PermissionResolver.resolve(state,id,"a.b.c",1));
        assertEquals(Optional.empty(),PermissionResolver.resolve(state,id,"a",1));
        assertEquals(Optional.empty(),PermissionResolver.resolve(state,id,"ab.c",1));
    }
    @Test void jsonRoundtripPreservesDotNodesAndExpiredRecords() throws Exception {
        Path path=dir.resolve("state.json");var store=new PermissionStore(path);
        var expected=state(Map.of(),Map.of(),Map.of("some.node.*",new PermissionState.Grant(false,123)));
        store.update(old->expected);var reloaded=new PermissionStore(path);reloaded.load();assertEquals(expected,reloaded.snapshot());
        assertEquals(123,reloaded.snapshot().users().get(id).permissions().get("some.node.*").expiresAt());
    }
    @Test void failedWriteNeverPublishesAndCorruptFileIsFailClosed() throws Exception {
        Path blocked=dir.resolve("blocked");Files.writeString(blocked,"file");var store=new PermissionStore(blocked.resolve("state.json"));var previous=store.snapshot();
        assertThrows(java.io.IOException.class,()->store.update(old->state(Map.of(),Map.of(),Map.of("x",grant(true)))));assertEquals(previous,store.snapshot());
        Path damaged=dir.resolve("damaged.json");Files.writeString(damaged,"{broken");var corrupt=new PermissionStore(damaged);
        assertThrows(java.io.IOException.class,corrupt::load);assertThrows(java.io.IOException.class,()->corrupt.update(old->old));assertEquals("{broken",Files.readString(damaged));
    }
}
