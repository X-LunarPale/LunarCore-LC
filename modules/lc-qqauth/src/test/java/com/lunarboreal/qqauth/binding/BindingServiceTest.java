package com.lunarboreal.qqauth.binding;

import com.lunarboreal.qqauth.auth.AuthService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class BindingServiceTest {
    @TempDir Path temp;
    BindingRepository repo;
    BindingService service;
    MutableClock clock;
    @BeforeEach void setup() throws Exception {
        repo = new BindingRepository(new BindingTestDatabase(temp.resolve("bindings.db"))); repo.initialize();
        clock = new MutableClock();
        service = new BindingService(repo, BindingSettings::defaults, (n,g,p)->true, new java.security.SecureRandom(), clock);
    }
    @Test void bindIsOneUseAndPendingCodeRefreshes() throws Exception {
        UUID id=UUID.randomUUID(); var first=service.issueBind(id,"Alice"); clock.time+=1000;
        var again=service.issueBind(id,"Alice"); assertEquals(first.code(),again.code()); assertTrue(again.expiresAt()>first.expiresAt());
        assertEquals(BindingService.Status.INVALID_CODE,service.redeemUnbind(first.code(),"group","openid").status());
        assertEquals(BindingService.Status.SUCCESS,service.redeemBind(first.code(),"group","openid").status());
        assertEquals(BindingService.Status.INVALID_CODE,service.redeemBind(first.code(),"group","openid").status());
        assertEquals("openid",repo.find(id).orElseThrow().qq());
    }
    @Test void quotaRetainsCodeAndOverrideWorks() throws Exception {
        var a=service.issueBind(UUID.randomUUID(),"Alice"); service.redeemBind(a.code(),"g","qq");
        var b=service.issueBind(UUID.randomUUID(),"Bob");
        assertEquals(BindingService.Status.LIMIT_REACHED,service.redeemBind(b.code(),"g","qq").status());
        assertEquals(BindingService.Status.SUCCESS,service.redeemBind(b.code(),"g","other").status());
        BindingSettings s=new BindingSettings(8,Duration.ofMinutes(1),Duration.ofMinutes(1),Duration.ofMinutes(1),100,1,Map.of("qq",2));
        var overridden=new BindingService(repo,()->s,(n,g,p)->true);
        var c=overridden.issueBind(UUID.randomUUID(),"Carol"); assertEquals(8,c.code().length());
        assertEquals(BindingService.Status.SUCCESS,overridden.redeemBind(c.code(),"g","qq").status());
    }
    @Test void wrongSenderOrGroupDoesNotConsumeAndUnbindCancelsAll() throws Exception {
        UUID id=UUID.randomUUID(); var bind=service.issueBind(id,"Alice"); service.redeemBind(bind.code(),"g","opaque_QQ");
        var a=service.issueUnbind(id); var b=service.issueUnbind(id);
        assertEquals(BindingService.Status.QQ_MISMATCH,service.redeemUnbind(a.code(),"g","intruder").status());
        assertEquals(BindingService.Status.QQ_MISMATCH,service.redeemUnbind(a.code(),"other","opaque_QQ").status());
        assertEquals(BindingService.Status.SUCCESS,service.redeemUnbind(a.code(),"g","opaque_QQ").status());
        assertEquals(BindingService.Status.INVALID_CODE,service.redeemUnbind(b.code(),"g","opaque_QQ").status());
        assertTrue(repo.find(id).isEmpty());
    }
    @Test void expiresAndCapacityAreBounded() {
        BindingSettings s=new BindingSettings(6,Duration.ofSeconds(1),Duration.ofSeconds(1),Duration.ofSeconds(1),1,1,Map.of());
        service=new BindingService(repo,()->s,(n,g,p)->true,new java.security.SecureRandom(),clock);
        var old=service.issueBind(UUID.randomUUID(),"Alice");
        assertThrows(IllegalStateException.class,()->service.issueBind(UUID.randomUUID(),"Bob"));
        clock.time+=1000; assertEquals(1,service.sweepExpired());
        assertDoesNotThrow(()->service.issueBind(UUID.randomUUID(),"Bob"));
        assertTrue(old.code().matches("[0-9]{6}"));
    }
    @Test void nameCaseCollisionCannotBindAnotherUuid() throws Exception {
        var a=service.issueBind(UUID.randomUUID(),"Alice"); service.redeemBind(a.code(),"g","qq");
        var b=service.issueBind(UUID.randomUUID(),"aLiCe");
        assertEquals(BindingService.Status.IDENTITY_CONFLICT,service.redeemBind(b.code(),"g","other").status());
        assertEquals("Alice",repo.findByName("ALICE").orElseThrow().playerName());
    }
    @Test void identityRecoveryRequiresTheOriginalQqAndGroupAndPreservesBindingDetails() throws Exception {
        UUID previous=UUID.randomUUID(), next=UUID.randomUUID();
        var original=new Binding(previous,"Alice","original-qq","original-group",1234);
        assertTrue(repo.bind(original,1));
        var code=service.issueIdentityRecovery(next,"Alice");
        assertEquals(BindingService.Status.QQ_MISMATCH,
                service.redeemBind(code.code(),"original-group","other-qq").status());
        assertEquals(BindingService.Status.QQ_MISMATCH,
                service.redeemBind(code.code(),"other-group","original-qq").status());
        assertEquals(original,repo.find(previous).orElseThrow());
        assertTrue(repo.find(next).isEmpty());

        var result=service.redeemBind(code.code(),"original-group","original-qq");
        assertEquals(BindingService.Status.SUCCESS,result.status());
        assertEquals(previous,result.previousUuid());
        var migrated=new Binding(next,"Alice","original-qq","original-group",1234);
        assertEquals(migrated,result.binding());
        assertEquals(migrated,repo.find(next).orElseThrow());
        assertTrue(repo.find(previous).isEmpty());
        assertEquals(1,repo.countForQq("original-qq"));
        assertEquals(BindingService.Status.INVALID_CODE,
                service.redeemBind(code.code(),"original-group","original-qq").status());
    }
    @Test void identityRecoveryCannotOverwriteATargetBoundAfterTheCodeWasIssued() throws Exception {
        UUID previous=UUID.randomUUID(), next=UUID.randomUUID();
        var original=new Binding(previous,"Alice","qq","g",1);
        assertTrue(repo.bind(original,1));
        var code=service.issueIdentityRecovery(next,"Alice");
        var occupied=new Binding(next,"Bob","other-qq","g",2);
        assertTrue(repo.bind(occupied,1));

        assertEquals(BindingService.Status.IDENTITY_CONFLICT,service.redeemBind(code.code(),"g","qq").status());
        assertEquals(original,repo.find(previous).orElseThrow());
        assertEquals(occupied,repo.find(next).orElseThrow());
    }
    @Test void expiredIdentityRecoveryCannotMoveTheBinding() throws Exception {
        UUID previous=UUID.randomUUID(), next=UUID.randomUUID();
        var original=new Binding(previous,"Alice","qq","g",1);
        assertTrue(repo.bind(original,1));
        var code=service.issueIdentityRecovery(next,"Alice");
        clock.time=code.expiresAt();

        assertEquals(BindingService.Status.INVALID_CODE,service.redeemBind(code.code(),"g","qq").status());
        assertEquals(original,repo.find(previous).orElseThrow());
        assertTrue(repo.find(next).isEmpty());
    }
    @Test void importIsAdditiveAndRollsBackEveryRowOnConflict() throws Exception {
        UUID original=UUID.randomUUID(); repo.bind(new Binding(original,"Alice","qq","g",1),1);
        var b=new Binding(UUID.randomUUID(),"Bob","b","g",1);
        var conflict=new Binding(UUID.randomUUID(),"ALICE","c","g",1);
        assertThrows(SQLException.class,()->repo.importBindings(List.of(b,conflict),BindingSettings.defaults()));
        assertEquals(1,repo.all().size()); assertTrue(repo.findByName("Bob").isEmpty());
        repo.importBindings(List.of(b),BindingSettings.defaults()); assertEquals(2,repo.all().size());
    }
    @Test void opaqueQqIsCaseSensitiveAndQuotaAtomicAcrossRepositories() throws Exception {
        assertTrue(repo.bind(new Binding(UUID.randomUUID(),"Alice","OpenId","g",1),1));
        assertTrue(repo.bind(new Binding(UUID.randomUUID(),"Bob","openid","g",1),1));
        var other=new BindingRepository(new BindingTestDatabase(temp.resolve("bindings.db"))); other.initialize();
        var executor=Executors.newFixedThreadPool(2);
        try {
            var a=executor.submit(()->repo.bind(new Binding(UUID.randomUUID(),"Carol","same","g",1),1));
            var b=executor.submit(()->other.bind(new Binding(UUID.randomUUID(),"David","same","g",1),1));
            assertNotEquals(a.get(),b.get()); assertEquals(1,repo.countForQq("same"));
        } finally {executor.shutdownNow();}
    }
    @Test void importQuotaFailurePreservesExistingData() throws Exception {
        repo.bind(new Binding(UUID.randomUUID(),"Alice","qq","g",1),1);
        assertThrows(SQLException.class,()->repo.importBindings(List.of(new Binding(UUID.randomUUID(),"Bob","qq","g",2)),BindingSettings.defaults()));
        assertEquals(1,repo.all().size());
    }
    @Test void cancellationAndExpiryDiscardResetPayloads() throws Exception {
        UUID id=UUID.randomUUID(); repo.bind(new Binding(id,"Alice","qq","g",1),1);
        var constructor=AuthService.PreparedPassword.class.getDeclaredConstructor(String.class); constructor.setAccessible(true);
        var prepared=constructor.newInstance("prepared-hash");
        var a=service.issueReset(id,prepared,"generation"); service.cancel(id);
        assertEquals(BindingService.Status.INVALID_CODE,service.redeemReset(a.code(),"g","qq").status());
        var b=service.issueReset(id,prepared,"generation"); clock.time+=Duration.ofMinutes(5).toMillis();
        assertEquals(BindingService.Status.INVALID_CODE,service.redeemReset(b.code(),"g","qq").status());
    }
    @Test void concurrentRedemptionPublishesSuccessOnce() throws Exception {
        var code=service.issueBind(UUID.randomUUID(),"Alice").code(); var executor=Executors.newFixedThreadPool(2);
        try {
            Callable<BindingService.Result> redeem=()->service.redeemBind(code,"g","qq");
            var a=executor.submit(redeem); var b=executor.submit(redeem);
            assertEquals(Set.of(BindingService.Status.SUCCESS,BindingService.Status.INVALID_CODE),Set.of(a.get().status(),b.get().status()));
        } finally {executor.shutdownNow();}
    }
    @Test void resetWrongSenderPreservesPreparedPayloadAndGenerationIsChecked() throws Exception {
        UUID id=UUID.randomUUID(); repo.bind(new Binding(id,"Alice","qq","g",1),1);
        // Construct only a prepared hash, never a pending plaintext password.
        var constructor=AuthService.PreparedPassword.class.getDeclaredConstructor(String.class); constructor.setAccessible(true);
        var prepared=constructor.newInstance("$2a$04$prepared-for-domain-test"); AtomicInteger called=new AtomicInteger();
        service=new BindingService(repo,BindingSettings::defaults,(name,generation,p)-> {called.incrementAndGet(); assertSame(prepared,p); assertEquals("generation",generation); return false;});
        var a=service.issueReset(id,prepared,"generation"); var b=service.issueReset(id,prepared,"generation");
        assertEquals(BindingService.Status.INVALID_CODE,service.redeemBind(a.code(),"g","qq").status());
        assertEquals(BindingService.Status.QQ_MISMATCH,service.redeemReset(a.code(),"g","bad").status()); assertEquals(0,called.get());
        assertEquals(BindingService.Status.STALE_GENERATION,service.redeemReset(a.code(),"g","qq").status());
        assertEquals(BindingService.Status.INVALID_CODE,service.redeemReset(b.code(),"g","qq").status()); assertEquals(1,called.get());
    }
    @Test void failedPersistenceKeepsResetCodeForRetry() throws Exception {
        UUID id=UUID.randomUUID(); repo.bind(new Binding(id,"Alice","qq","g",1),1);
        var constructor=AuthService.PreparedPassword.class.getDeclaredConstructor(String.class); constructor.setAccessible(true);
        var prepared=constructor.newInstance("prepared-hash"); AtomicInteger attempts=new AtomicInteger();
        service=new BindingService(repo,BindingSettings::defaults,(n,g,p)-> { if(attempts.getAndIncrement()==0) throw new SQLException("write failed"); return true; });
        var code=service.issueReset(id,prepared,"generation").code();
        assertThrows(SQLException.class,()->service.redeemReset(code,"g","qq"));
        assertEquals(BindingService.Status.SUCCESS,service.redeemReset(code,"g","qq").status());
        assertEquals(BindingService.Status.INVALID_CODE,service.redeemReset(code,"g","qq").status());
    }
    static final class MutableClock extends Clock {
        long time=10000;
        public ZoneId getZone(){return ZoneOffset.UTC;} public Clock withZone(ZoneId zone){return this;}
        public Instant instant(){return Instant.ofEpochMilli(time);} public long millis(){return time;}
    }
}
