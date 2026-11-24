package com.training;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.*;

/** Synthetic-only cross-account regression. Uses real Auth and audited first-bind stores. */
public final class SharedMailboxTest {
    static int checks;
    static final long MINUTE=60_000L;
    interface Work { void run() throws Exception; }
    static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    static void reject(int status,Work work,String label)throws Exception {
        try {work.run();throw new AssertionError("Expected rejection: "+label);}
        catch(Api.ApiException e){check(e.code==status,label+" expected="+status+" actual="+e.code);}
    }
    static Auth.Credential credential(long uid)throws Exception{return FirstBindTest.credential(uid);}
    static final class Codes extends SecureRandom {
        private int code=123000;
        @Override public int nextInt(int bound){return (++code)%bound;}
    }
    static final class Engine {
        final FirstBindTest.Clock clock=new FirstBindTest.Clock();
        final FirstBindTest.Mail mail=new FirstBindTest.Mail();
        final NotificationChannelsLoginVerification service=new NotificationChannelsLoginVerification(clock,mail);
        Engine()throws Exception {
            // Test-only deterministic distinct OTPs; random capability bytes remain SecureRandom.
            Field random=NotificationChannelsLoginVerification.class.getDeclaredField("random");
            random.setAccessible(true);random.set(service,new Codes());
        }
        void minute(){clock.advance(MINUTE);}
        NotificationChannelsLoginVerification.Verified bind(long uid,String address)throws Exception {
            var enrollment=service.enroll(credential(uid));check(enrollment!=null,"ready account has its own enrollment");
            var challenge=service.beginFirst(enrollment.enrollmentId(),address,"127.0.0.1");
            var result=service.verifyFirst(challenge.challengeId(),mail.code());
            check(result.credential().uid()==uid&&result.snapshot().userId()==uid,"first bind preserves subject");return result;
        }
        NotificationChannelsLoginVerification.Challenge begin(long uid)throws Exception{return service.begin(credential(uid),"127.0.0.1");}
    }
    static String issue(NotificationChannelsLoginVerification.Verified value)throws Exception{return Auth.issueVerified(value.credential(),value.snapshot());}
    static void active(String token,long uid,String label){var s=Auth.get(token);check(s!=null&&s.uid==uid,label);}
    static void policy(){System.setProperty("login.email.mode","required");System.setProperty("login.device.maxAgeSeconds","1800");System.setProperty("login.device.origin","https://shared.example.invalid");}
    static void challenges()throws Exception {
        Engine e=new Engine();String email="shared-main@example.invalid";
        var a=e.bind(100,email);String aSession=issue(a);TrustedDevices devices=new TrustedDevices(e.clock);
        var aDevice=devices.enroll(a.credential(),a.snapshot(),null);
        e.minute();var b=e.bind(101,"SHARED-MAIN@example.invalid");String bSession=issue(b);
        active(aSession,100,"B binding leaves A short session valid");active(aDevice.sessionToken(),100,"B binding leaves A device session valid");
        var restored=devices.restore(aDevice.deviceToken());active(restored.sessionToken(),100,"B binding leaves A persistent trust valid");
        check(!a.snapshot().equals(b.snapshot()),"same mailbox never merges account snapshots");
        check(Auth.checkedCredential(email,FirstBindTest.PASSWORD)==null,"mailbox is not a login username alias");
        check(Auth.checkedCredential(FirstBindTest.username(100),"wrong-synthetic-password")==null,"username still requires its own password");
        e.minute();var ca=e.begin(100);String codeA=e.mail.code();e.minute();var cb=e.begin(101);String codeB=e.mail.code();
        check(!codeA.equals(codeB),"fixture supplies distinct OTPs");
        reject(400,()->e.service.verify(cb.challengeId(),codeA),"A OTP cannot satisfy B challenge");
        reject(400,()->e.service.verify(ca.challengeId(),codeB),"B OTP cannot satisfy A challenge");
        active(issue(e.service.verify(ca.challengeId(),codeA)),100,"A independent login success");
        active(issue(e.service.verify(cb.challengeId(),codeB)),101,"B independent login success");
        reject(401,()->e.service.verify(ca.challengeId(),codeA),"A proof is single use");
        // A's resend and cancel cannot replace B's pending challenge.
        e.clock.advance(60*MINUTE);var oldA=e.begin(100);e.minute();var pendingB=e.begin(101);String pendingCodeB=e.mail.code();
        e.minute();var freshA=e.service.resend(oldA.challengeId(),"127.0.0.1");
        reject(401,()->e.service.verify(oldA.challengeId(),codeA),"A resend invalidates only old A challenge");
        e.service.cancel(freshA.challengeId());
        active(issue(e.service.verify(pendingB.challengeId(),pendingCodeB)),101,"A resend and cancel preserve B proof");
        active(bSession,101,"A challenge operations preserve existing B session");
    }
    static void quotas()throws Exception {
        Engine e=new Engine();var a=e.begin(100);
        reject(429,()->e.begin(101),"case-folded shared mailbox enforces cross-account cooldown");
        check(e.mail.values.size()==1,"cooldown failure sends no mail");e.service.cancel(a.challengeId());
        for(int i=1;i<5;i++){e.minute();var c=e.begin(i%2==0?100:101);e.service.cancel(c.challengeId());}
        e.minute();reject(429,()->e.begin(101),"five mailbox sends exhaust hourly quota across accounts");
        check(e.mail.values.size()==5,"hourly rejection and cancellation cannot free sends");
        e.clock.advance(60*MINUTE);check(e.begin(101)!=null,"mailbox quota recovers after hour");
        Engine mixed=new Engine();mixed.begin(100);var enrollment=mixed.service.enroll(credential(102));
        reject(429,()->mixed.service.beginFirst(enrollment.enrollmentId(),"SHARED-MAIN@example.invalid","127.0.0.2"),"first bind shares ordinary-login recipient cooldown");
        mixed.minute();var challenge=mixed.service.beginFirst(enrollment.enrollmentId(),"SHARED-MAIN@example.invalid","127.0.0.2");
        check(mixed.service.verifyFirst(challenge.challengeId(),mixed.mail.code()).credential().uid()==102,"first bind succeeds after shared cooldown");
    }
    static void mutations()throws Exception {
        for(int kind=0;kind<4;kind++) {
            final int operation=kind;long a=110+kind*2,b=a+1;Engine e=new Engine();String mailbox="shared-mutation-"+kind+"@example.invalid";
            var va=e.bind(a,mailbox);e.minute();var vb=e.bind(b,mailbox);String sa=issue(va),sb=issue(vb);
            TrustedDevices devices=new TrustedDevices(e.clock);var da=devices.enroll(va.credential(),va.snapshot(),null);var db=devices.enroll(vb.credential(),vb.snapshot(),null);
            e.minute();var ca=e.begin(a);String codeA=e.mail.code();e.minute();var cb=e.begin(b);String codeB=e.mail.code();
            var beforeB=NotificationChannelsAccountEmailPreparation.loginSnapshot(b);
            var rowsB=Db.query("SELECT * FROM s01_trusted_devices WHERE user_id=?",b);
            if(kind==0) {
                // Use actual maintenance save, with a fixture admin authenticated in legacy mode.
                Auth.Session admin=FirstBindTest.admin();System.setProperty("login.email.mode","legacy");
                try {NotificationChannelsAccountEmailMaintenance.save(admin,Map.of("user_id",a,"expected_revision",1L,"request_id",UUID.randomUUID().toString(),"email","changed-"+a+"@example.invalid","confirmed_username",FirstBindTest.username(a),"purpose","LOGIN_VERIFICATION"));}
                finally {System.setProperty("login.email.mode","required");}
            } else {
                TrustedDevices.mutation(a,true,()->{
                    if(operation==1)Db.exec("UPDATE users SET password=? WHERE id=?",Auth.hash("changed-synthetic-password"),a);
                    else if(operation==2)Db.exec("UPDATE users SET status=0 WHERE id=?",a);
                    return null;
                });Auth.revokeUserSessions(a);
            }
            check(Auth.get(sa)==null&&Auth.get(da.sessionToken())==null,"A mutation revokes A sessions kind="+kind);
            reject(401,()->devices.restore(da.deviceToken()),"A mutation revokes own device kind="+kind);
            reject(401,()->e.service.verify(ca.challengeId(),codeA),"A mutation invalidates own pending proof kind="+kind);
            active(sb,b,"A mutation preserves B ordinary session kind="+kind);active(db.sessionToken(),b,"A mutation preserves B trusted session kind="+kind);
            check(beforeB.equals(NotificationChannelsAccountEmailPreparation.loginSnapshot(b)),"A mutation preserves B snapshot kind="+kind);
            check(rowsB.equals(Db.query("SELECT * FROM s01_trusted_devices WHERE user_id=?",b)),"A mutation leaves B device rows untouched kind="+kind);
            active(issue(e.service.verify(cb.challengeId(),codeB)),b,"A mutation preserves B pending proof kind="+kind);
            active(devices.restore(db.deviceToken()).sessionToken(),b,"A mutation preserves B restoration kind="+kind);
        }
    }
    static void reopen()throws Exception {
        FirstBindStore.init();policy();Engine e=new Engine();
        var before=FirstBindTest.rows(FirstBindTest.EMAIL_TABLES);
        for(long uid:List.of(100L,101L,102L)) {
            var snapshot=NotificationChannelsAccountEmailPreparation.loginSnapshot(uid);
            check(snapshot.userId()==uid&&snapshot.revision()==1&&snapshot.email().equalsIgnoreCase("shared-main@example.invalid"),"cold JVM preserves shared audited snapshot "+uid);
            check(FirstBindStore.allowsSession(uid),"cold completed first-bind gate remains independent");
            var c=e.begin(uid);active(issue(e.service.verify(c.challengeId(),e.mail.code())),uid,"cold shared-mailbox account signs in independently");e.minute();
        }
        check(before.equals(FirstBindTest.rows(FirstBindTest.EMAIL_TABLES)),"cold reads and login do not rewrite email history");
    }
    public static void main(String[] args)throws Exception {
        boolean restart=args.length>0&&args[0].equals("--reopen");Path data=FirstBindTest.configure(args,restart);boolean opened=false;
        try {
            check(Db.get().getMetaData().getURL().startsWith("jdbc:h2:"+data.resolve("training")),"owned synthetic H2 only");opened=true;
            if(restart)reopen();else {FirstBindTest.setup(false);policy();challenges();quotas();mutations();}
            System.out.println("Shared mailbox "+(restart?"cold JVM":"core")+": "+checks+" checks passed; synthetic H2, memory mail, no external contact.");
        } finally {if(opened)Db.exec("SHUTDOWN");}
    }
}
