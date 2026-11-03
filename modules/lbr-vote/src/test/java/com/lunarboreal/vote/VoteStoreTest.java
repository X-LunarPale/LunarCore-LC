package com.lunarboreal.vote;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class VoteStoreTest {
    @TempDir Path dir;
    @Test void roundTripAllStateAndBallots()throws Exception{VoteStore store=new VoteStore(dir.resolve("state.properties"));UUID player=UUID.randomUUID();VoteState vote=new VoteState(UUID.randomUUID(),"slow",12345,90,Set.of(player),Map.of(player,false),1,.6);VoteStore.Snapshot original=new VoteStore.Snapshot(vote,99999,"speed",88888,List.of("测试历史"));store.save(original);assertEquals(original,store.load());assertEquals(1,Files.list(dir).count());}
    @Test void failClosedOnCorruption()throws Exception{Path p=dir.resolve("state.properties");Files.writeString(p,"version=broken\n");assertThrows(java.io.IOException.class,()->new VoteStore(p).load());assertEquals("version=broken\n",Files.readString(p));}
    @Test void failedWriteDoesNotReplaceOldFile()throws Exception{Path p=dir.resolve("state.properties");VoteStore store=new VoteStore(p);VoteStore.Snapshot baseline=store.load();store.save(baseline);Path child=p.resolve("child");assertThrows(java.io.IOException.class,()->new VoteStore(child).save(baseline));assertEquals(baseline,store.load());}
}
