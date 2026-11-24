package com.training;

import com.sun.net.httpserver.HttpExchange;
import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import static com.training.ApprovalTemplateDefinition.*;
import static com.training.TrainingSummariesWorkflow.*;
import static com.training.OrganizationAccess.*;

/** Versioned template configuration, shared storage; business instances freeze their own validated copy. */
public final class ApprovalTemplatesIntegration {
    private ApprovalTemplatesIntegration() {}
    public static final int MAX_BODY=32768;
    private static final int MAX_REVISIONS=2000,MAX_REQUESTS=20000;
    private static final String ZERO="0".repeat(64),M08="/api/training-summaries/approval-templates",M03="/api/demands/approval-templates";
    private static final Map<String,Preview> PREVIEWS=new LinkedHashMap<>();
    public record Published(Kind kind,String organization,long revision,String version,Definition definition,String hash) {}
    private record Stored(Kind kind,String org,long revision,long active,String hash,List<Map<String,Object>> rows) {
        Map<String,Object> last(){return rows.isEmpty()?null:rows.get(rows.size()-1);}
        Map<String,Object> activeRow(){return active==0?null:rows.get((int)active-1);}
        Published published()throws Api.ApiException {var a=activeRow();return a==null?null:new Published(kind,org,active,(String)a.get("template_version"),parse(kind,a.get("definition")),TrainingSummariesWorkflow.hash(canonical(a)));}
    }
    private record Preview(Auth.Session actor,long expires,Kind kind,String org,long revision,String fingerprint) {}
    private record Preflight(Map<String,Object> view,List<Map<String,Object>> identities) {
        boolean publishable(){return Boolean.TRUE.equals(view.get("publishable"));}
    }
    public static void init()throws SQLException {
        Db.exec("CREATE TABLE IF NOT EXISTS approval_template_heads(business_kind VARCHAR(24) NOT NULL,organization_code VARCHAR(120) NOT NULL,head_revision BIGINT NOT NULL,head_hash VARCHAR(64) NOT NULL,active_revision BIGINT NOT NULL,PRIMARY KEY(business_kind,organization_code))");
        Db.exec("CREATE TABLE IF NOT EXISTS approval_template_revisions(business_kind VARCHAR(24) NOT NULL,organization_code VARCHAR(120) NOT NULL,revision BIGINT NOT NULL,payload_json CLOB NOT NULL,previous_hash VARCHAR(64) NOT NULL,revision_hash VARCHAR(64) NOT NULL,PRIMARY KEY(business_kind,organization_code,revision))");
        Db.exec("CREATE TABLE IF NOT EXISTS approval_template_requests(account_id BIGINT NOT NULL,request_id VARCHAR(96) NOT NULL,business_kind VARCHAR(24) NOT NULL,organization_code VARCHAR(120) NOT NULL,operation VARCHAR(16) NOT NULL,payload_hash VARCHAR(64) NOT NULL,result_revision BIGINT NOT NULL,saved BOOLEAN NOT NULL,receipt_hash VARCHAR(64) NOT NULL,PRIMARY KEY(account_id,request_id))");
    }
    public static boolean matches(String path){return path!=null&&(path.equals(M08)||path.startsWith(M08+"/")||path.equals(M03)||path.startsWith(M03+"/"));}
    private static Kind kind(String path){return path.startsWith(M03)?Kind.M03_DEMAND:Kind.M08_SUMMARY;}
    private static String op(HttpExchange ex)throws Api.ApiException {
        String path=ex.getRequestURI().getPath(),base=kind(path)==Kind.M03_DEMAND?M03:M08,op=path.equals(base)?"read":path.substring(base.length()+1);
        if(!Set.of("read","context","candidates","history","draft","preview","publish").contains(op)||op.equals("read")&&!path.equals(base))fail(404,"审批模板接口不存在");
        String method=Set.of("draft","preview","publish").contains(op)?"POST":"GET";if(!method.equals(ex.getRequestMethod())){ex.getResponseHeaders().set("Allow",method);fail(405,"请求方法不支持");}return op;
    }
    public static void preflight(HttpExchange ex)throws Api.ApiException {
        synchronized(Api.MUTATION_LOCK){admin(Auth.get(Api.token(ex)));op(ex);ex.getResponseHeaders().set("Cache-Control","no-store");String n=ex.getRequestHeaders().getFirst("Content-Length");if(n!=null)try{if(Long.parseLong(n)>MAX_BODY)fail(413,"模板请求过大");}catch(NumberFormatException bad){fail(400,"请求长度无效");}}
    }
    public static boolean handle(HttpExchange ex,Auth.Session supplied)throws Exception {
        if(!matches(ex.getRequestURI().getPath()))return false;
        synchronized(Api.MUTATION_LOCK){Auth.Session actor=admin(supplied);if(actor!=Auth.get(Api.token(ex)))fail(401,"登录已失效");String op=op(ex);Kind kind=kind(ex.getRequestURI().getPath());Map<String,String> q=query(ex);Object result;
            if(op.equals("context")){if(!q.isEmpty())fail(400,"不支持查询参数");Configuration c=OrganizationAccessStore.configuration();result=map("business_kind",kind.name(),"organizations",c==null?List.of():c.organizations().stream().filter(o->enabledOrg(c,o.organizationCode())).map(o->map("organization_code",o.organizationCode(),"label",o.displayName()==null||o.displayName().isBlank()?o.organizationCode():o.displayName())).toList(),"max_extra_nodes",MAX_EXTRA,"conditions",List.of("ALWAYS"));}
            else if(Set.of("draft","preview","publish").contains(op)){if(!q.isEmpty())fail(400,"不支持查询参数");result=command(actor,kind,op,Api.body(ex));}
            else {Set<String> expected=op.equals("candidates")?Set.of("organization_code","review_role"):op.equals("history")?Set.of("organization_code","offset","limit"):Set.of("organization_code");if(!expected.containsAll(q.keySet())||!q.containsKey("organization_code"))fail(400,"查询参数无效");String org=text(q.get("organization_code"),120);requireOrg(org);Stored s=stored(kind,org);
                if(op.equals("read"))result=view(s);
                else if(op.equals("candidates")){String role=text(q.get("review_role"),8);if(!Set.of("BRANCH","BP").contains(role))fail(400,"复核职责无效");result=map("business_kind",kind.name(),"organization_code",org,"review_role",role,"configuration_version",OrganizationAccessStore.configuration().version(),"items",candidates(kind,org,role));}
                else {int offset=queryInt(q.getOrDefault("offset","0"),0,MAX_REVISIONS),limit=queryInt(q.getOrDefault("limit","20"),1,50);List<Map<String,Object>> items=new ArrayList<>();for(int i=s.rows.size()-1-offset;i>=0&&items.size()<limit;i--)items.add(s.rows.get(i));result=map("business_kind",kind.name(),"organization_code",org,"head_revision",s.revision,"active_revision",s.active,"offset",offset,"limit",limit,"total",s.rows.size(),"items",items);}}
            admin(actor);ex.getResponseHeaders().set("Cache-Control","no-store");Api.ok(ex,result);return true;
        }
    }
    /** Safe inside the existing business transaction. Absence alone permits the module's legacy fallback. */
    public static Published resolve(Kind kind,String org)throws Exception {synchronized(Api.MUTATION_LOCK){return stored(kind,org).published();}}
    public static Map<String,Object> read(Auth.Session supplied,Kind kind,String org)throws Exception {synchronized(Api.MUTATION_LOCK){Auth.Session actor=admin(supplied);requireOrg(text(org,120));Map<String,Object> result=view(stored(kind,org));admin(actor);return result;}}
    public static Map<String,Object> command(Auth.Session supplied,Kind kind,String operation,Map<String,Object> body)throws Exception {synchronized(Api.MUTATION_LOCK){Auth.Session actor=admin(supplied);if(!Set.of("draft","preview","publish").contains(operation))fail(404,"审批模板操作不存在");Map<String,Object> result=mutate(actor,kind,operation,body);admin(actor);return result;}}
    public static Frozen freeze(Published p,Person submitter,long submitterAccount,String branch,String bp,boolean combined)throws Exception {
        Configuration c=requireOrg(p.organization);Map<String,Long> extras=validatePeople(p.kind,p.organization,p.definition,c);long a=eligible(c,p.kind,p.organization,branch,"BRANCH"),b=eligible(c,p.kind,p.organization,bp,"BP");
        try{return ApprovalTemplateDefinition.freeze(p.definition,p.organization,p.version,p.revision,c.version(),submitter.personCode(),submitterAccount,branch,a,bp,b,combined,extras);}catch(Api.ApiException conflict){throw new Api.ApiException(409,"模板人员与本次填报人或必需复核人冲突，请调整未来模板后再提交");}
    }
    /** Runtime checks use only the frozen target and current rights, never current template assignment. */
    public static void requireActor(Auth.Session session,Frozen frozen,Node node)throws Exception {
        Auth.Session current=Auth.current(session);if(current==null)fail(401,"登录已失效");Person person=OrganizationAccessStore.person(current);
        if(current.uid!=node.account()||!person.personCode().equals(node.person())||current.uid==frozen.submitterAccount()||person.personCode().equals(frozen.submitter()))fail(403,"当前账号不是本次冻结的节点办理人");
        if(node.role().equals("COMBINED")){if(frozen.kind()==Kind.M08_SUMMARY){require(current,"summary.review.branch",Action.HANDLE,frozen.organization());require(current,"summary.review.bp",Action.HANDLE,frozen.organization());}else require(current,"approval.review",Action.HANDLE,frozen.organization());}
        else require(current,node.resource(),Action.HANDLE,frozen.organization());
        require(current,frozen.kind()==Kind.M03_DEMAND?"demand.read":"summary.read",Action.VIEW,frozen.organization());
    }
    private static Map<String,Object> mutate(Auth.Session actor,Kind kind,String op,Map<String,Object> body)throws Exception {
        if(!Db.get().getAutoCommit())fail(409,"模板管理接口不能嵌套事务");
        if(op.equals("preview")){
            keys(body,"organization_code","expected_revision");String org=text(body.get("organization_code"),120);Configuration c=requireOrg(org);Stored s=stored(kind,org);
            if(number(body.get("expected_revision"),true)!=s.revision||s.revision==0)throw stale();Definition definition=parse(kind,s.last().get("definition"));Preflight checked=preflight(kind,org,definition,c);
            String token=null,expires=null;
            if(checked.publishable()){String fp=fingerprint(s,definition,checked);sweep();if(PREVIEWS.size()>=128)fail(409,"待确认预览过多，请稍后重试");token=random()+random();long until=System.currentTimeMillis()+300000;PREVIEWS.put(token,new Preview(actor,until,kind,org,s.revision,fp));expires=Instant.ofEpochMilli(until).toString();}
            return map("business_kind",kind.name(),"organization_code",org,"head_revision",s.revision,"active_revision",s.active,"before",s.activeRow()==null?null:s.activeRow().get("definition"),"after",json(definition),"changed",s.activeRow()==null||!canonical(s.activeRow().get("definition")).equals(canonical(json(definition))),"preflight",checked.view,"review_token",token,"expires_at",expires,"applies_to",kind==Kind.M03_DEMAND?"FUTURE_M03_INSTANCES":"FUTURE_M08_SUBMISSIONS","required_gates",List.of("BRANCH","BP"));
        }
        if(op.equals("draft"))keys(body,"organization_code","expected_revision","request_id","definition","reason");else keys(body,"review_token","request_id");
        String request=request(body.get("request_id")),payload=hash(canonical(map("business_kind",kind.name(),"operation",op,"body",body)));
        return Db.transaction(()->{
            admin(actor);Map<String,Object> retry=Db.one("SELECT * FROM approval_template_requests WHERE account_id=? AND request_id=?",actor.uid,request);
            if(retry!=null){if(!kind.name().equals(retry.get("business_kind"))||!op.equals(retry.get("operation"))||!payload.equals(retry.get("payload_hash")))throw stale();String org=(String)retry.get("organization_code");requireOrg(org);Stored s=stored(kind,org);return receipt(s,number(retry.get("result_revision"),false),Boolean.TRUE.equals(retry.get("saved")),true);}
            String org;Stored s;Definition definition;String reason;boolean changed;Preview preview=null;
            if(op.equals("draft")){org=text(body.get("organization_code"),120);requireOrg(org);s=stored(kind,org);if(number(body.get("expected_revision"),true)!=s.revision)throw stale();definition=parse(kind,body.get("definition"));reason=reason(body.get("reason"));validatePeople(kind,org,definition,OrganizationAccessStore.configuration());changed=s.last()==null||!canonical(s.last().get("definition")).equals(canonical(json(definition)))||!reason.equals(s.last().get("reason"));}
            else {String token=text(body.get("review_token"),64);if(!token.matches("[a-f0-9]{64}"))fail(400,"预览标识无效");preview=PREVIEWS.get(token);if(preview==null||preview.actor!=actor||preview.kind!=kind||preview.expires<System.currentTimeMillis())throw stale();org=preview.org;Configuration c=requireOrg(org);s=stored(kind,org);if(s.revision!=preview.revision||s.revision==0)throw stale();definition=parse(kind,s.last().get("definition"));Preflight checked=preflight(kind,org,definition,c);if(!checked.publishable())fail(409,"当前填报人员存在模板冲突或必需关系未核实，请重新预览查看人员清单");if(!preview.fingerprint.equals(fingerprint(s,definition,checked)))throw stale();reason=(String)s.last().get("reason");changed=s.activeRow()==null||!canonical(s.activeRow().get("definition")).equals(canonical(json(definition)));}
            long result=s.revision;
            if(changed){if(s.revision>=MAX_REVISIONS)fail(409,"模板审计版本达到上限");result=s.revision+1;long active=op.equals("publish")?result:s.active;String version=op.equals("publish")?(kind==Kind.M03_DEMAND?"M03":"M08")+"-TPL-"+result+"-"+random():null;
                String name=actor.name==null||actor.name.isBlank()?actor.username:actor.name;
                Map<String,Object> row=map("business_kind",kind.name(),"organization_code",org,"revision",result,"operation",op.equals("publish")?"PUBLISHED":"DRAFT_SAVED","definition",json(definition),"previous_active_revision",s.active,"active_revision",active,"source_draft_revision",op.equals("publish")?s.revision:0,"template_version",version,"actor_account_id",actor.uid,"actor_name",name,"created_at",Instant.now().toString(),"reason",reason,"configuration_version",OrganizationAccessStore.configuration().version());
                String data=canonical(row),digest=hash(s.hash+"\n"+data);Db.exec("INSERT INTO approval_template_revisions VALUES(?,?,?,?,?,?)",kind.name(),org,result,data,s.hash,digest);
                if(s.revision==0)Db.exec("INSERT INTO approval_template_heads VALUES(?,?,?,?,?)",kind.name(),org,result,digest,active);
                else try(PreparedStatement ps=Db.get().prepareStatement("UPDATE approval_template_heads SET head_revision=?,head_hash=?,active_revision=? WHERE business_kind=? AND organization_code=? AND head_revision=?")){ps.setLong(1,result);ps.setString(2,digest);ps.setLong(3,active);ps.setString(4,kind.name());ps.setString(5,org);ps.setLong(6,s.revision);if(ps.executeUpdate()!=1)throw stale();}
            }
            if(number(Db.one("SELECT COUNT(*) AS n FROM approval_template_requests").get("n"),true)>=MAX_REQUESTS)fail(409,"模板请求审计达到上限");
            String receiptHash=receiptHash(actor.uid,request,kind.name(),org,op,payload,result,changed);Db.exec("INSERT INTO approval_template_requests VALUES(?,?,?,?,?,?,?,?,?)",actor.uid,request,kind.name(),org,op,payload,result,changed,receiptHash);
            admin(actor);requireOrg(org);validatePeople(kind,org,definition,OrganizationAccessStore.configuration());Stored after=stored(kind,org);return receipt(after,result,changed,false);
        });
    }
    private static Map<String,Object> receipt(Stored s,long result,boolean saved,boolean replayed){Map<String,Object> row=s.rows.get((int)result-1);long active=((Number)row.get("active_revision")).longValue();Map<String,Object> policy=active==0?null:s.rows.get((int)active-1);return map("business_kind",s.kind.name(),"organization_code",s.org,"result_revision",result,"result_active_revision",active,"result_template_version",policy==null?null:policy.get("template_version"),"current_revision",s.revision,"current_active_revision",s.active,"saved",saved,"replayed",replayed);}
    private static Map<String,Object> view(Stored s){return map("business_kind",s.kind.name(),"organization_code",s.org,"head_revision",s.revision,"active_revision",s.active,"active",s.activeRow(),"draft",s.last(),"authority",s.active==0?s.kind==Kind.M03_DEMAND?"M03_BASELINE":"GLOBAL_DEFAULT":"ORG_TEMPLATE","required_gates",List.of("BRANCH","BP"),"max_extra_nodes",MAX_EXTRA,"conditions",List.of("ALWAYS"));}
    private static String fingerprint(Stored s,Definition definition,Preflight checked)throws Exception {Configuration c=requireOrg(s.org);Map<String,Long> people=validatePeople(s.kind,s.org,definition,c);return hash(canonical(map("kind",s.kind.name(),"organization",s.org,"revision",s.revision,"head",s.hash,"active",s.active,"definition",json(definition),"configuration",c.version(),"accounts",people,"preflight",checked.view,"submitters",checked.identities)));}

    /** Inspect only current authorized submission identities, never infer a submitter from business data. */
    private static Preflight preflight(Kind kind,String org,Definition definition,Configuration c)throws Exception {
        Map<String,Long> extras=validatePeople(kind,org,definition,c);Engine engine=new Engine(c);
        List<Map<String,Object>> conflicts=new ArrayList<>(),unresolved=new ArrayList<>(),identities=new ArrayList<>();int checked=0;
        for(Person person:c.people()){
            if(!person.enabled()||!enabledOrg(c,person.organizationCode()))continue;
            List<AccountBinding> bindings=c.accountBindings().stream().filter(b->b.enabled()&&b.personCode().equals(person.personCode())).toList();
            if(bindings.size()!=1)continue;long account=bindings.get(0).accountId();Map<String,Object> user=Db.one("SELECT status,name FROM users WHERE id=?",account);
            if(user==null||!(user.get("status") instanceof Number status)||status.intValue()!=1)continue;
            if(!authorized(engine,account,kind==Kind.M03_DEMAND?"demand.write":"summary.edit",Action.HANDLE,org))continue;
            // M08 submission verifies both source permissions; a writer with only edit rights cannot submit.
            boolean canRead=true;for(String resource:readResources(kind))if(!authorized(engine,account,resource,Action.VIEW,org))canRead=false;if(!canRead)continue;
            checked++;String label=user.get("name") instanceof String n&&!n.isBlank()?n:person.personCode();String branch=person.leaderPersonCode(),bp=person.bpPersonCode();
            identities.add(map("person",person.personCode(),"account",account));
            int before=conflicts.size();
            for(Extra extra:definition.extras()){
                if(extra.person().equals(person.personCode())||extras.get(extra.person())==account)conflict(conflicts,person,label,extra,"SUBMITTER");
                if(Objects.equals(extra.person(),branch))conflict(conflicts,person,label,extra,"BRANCH");
                if(Objects.equals(extra.person(),bp))conflict(conflicts,person,label,extra,"BP");
            }
            try{
                if(branch==null||branch.isBlank()||bp==null||bp.isBlank())fail(409,"缺少必需负责人或BP关系");
                if(person.personCode().equals(branch)||person.personCode().equals(bp))fail(409,"填报人不能担任自己的必需复核人");
                long a=eligible(c,kind,org,branch,"BRANCH"),b=eligible(c,kind,org,bp,"BP");
                for(Extra extra:definition.extras()){
                    long extraAccount=extras.get(extra.person());
                    if(extraAccount==a&&!extra.person().equals(branch))conflict(conflicts,person,label,extra,"BRANCH");
                    if(extraAccount==b&&!extra.person().equals(bp))conflict(conflicts,person,label,extra,"BP");
                }
                boolean combined=branch.equals(bp);
                if(combined){if(kind==Kind.M03_DEMAND){if(OrganizationAccessStore.combinedAssignment(org,branch)==null)fail(409,"必需负责人及BP兼任依据未核实");}else TrainingSummariesCombinedReview.capture(person,org);}
                if(before==conflicts.size())ApprovalTemplateDefinition.freeze(definition,org,(kind==Kind.M03_DEMAND?"M03":"M08")+"-TPL-1-"+"0".repeat(32),1,c.version(),person.personCode(),account,branch,a,bp,b,combined,extras);
            }catch(Api.ApiException missing){if(!Set.of(400,409,422).contains(missing.code))throw missing;unresolved.add(map("submitter_code",person.personCode(),"submitter_label",label,"reason",missing.getMessage()));}
        }
        Map<String,Object> view=map("format","APPROVAL-TEMPLATE-PREFLIGHT-1","configuration_version",c.version(),"checked_submitters",checked,"conflicts",conflicts,"unresolved",unresolved,"publishable",conflicts.isEmpty()&&unresolved.isEmpty());
        return new Preflight(view,identities);
    }
    private static boolean authorized(Engine engine,long account,String resource,Action action,String org){return engine.authorize("template-submit-preflight",ignored->OptionalLong.of(account),new Resource(resource,org),action).allowed();}
    private static void conflict(List<Map<String,Object>> out,Person submitter,String label,Extra extra,String role){out.add(map("submitter_code",submitter.personCode(),"submitter_label",label,"node_id",extra.id(),"person_code",extra.person(),"conflict_role",role));}
    private static Stored stored(Kind kind,String org)throws Exception {
        try {Map<String,Object> head=Db.one("SELECT * FROM approval_template_heads WHERE business_kind=? AND organization_code=?",kind.name(),org);List<Map<String,Object>> rows=Db.query("SELECT * FROM approval_template_revisions WHERE business_kind=? AND organization_code=? ORDER BY revision LIMIT 2001",kind.name(),org);
            if(rows.size()>MAX_REVISIONS)throw unavailable();String previous=ZERO;long active=0;List<Map<String,Object>> checked=new ArrayList<>();
            for(int i=0;i<rows.size();i++){Map<String,Object> db=rows.get(i);long revision=i+1;if(number(db.get("revision"),false)!=revision||!previous.equals(db.get("previous_hash")))throw unavailable();String raw=(String)db.get("payload_json");if(raw==null||raw.length()>MAX_BODY)throw unavailable();Map<String,Object> row=object(Json.parse(raw));keys(row,"business_kind","organization_code","revision","operation","definition","previous_active_revision","active_revision","source_draft_revision","template_version","actor_account_id","actor_name","created_at","reason","configuration_version");
                if(!kind.name().equals(row.get("business_kind"))||!org.equals(row.get("organization_code"))||number(row.get("revision"),false)!=revision||number(row.get("previous_active_revision"),true)!=active||!raw.equals(canonical(row)))throw unavailable();parse(kind,row.get("definition"));number(row.get("actor_account_id"),false);text(row.get("actor_name"),500);Instant.parse(text(row.get("created_at"),40));reason(row.get("reason"));text(row.get("configuration_version"),128);
                if("PUBLISHED".equals(row.get("operation"))){long source=number(row.get("source_draft_revision"),false);if(source!=revision-1||checked.isEmpty()||!canonical(checked.get(checked.size()-1).get("definition")).equals(canonical(row.get("definition")))||!text(row.get("template_version"),96).matches((kind==Kind.M03_DEMAND?"M03":"M08")+"-TPL-"+revision+"-[a-f0-9]{32}"))throw unavailable();active=revision;}
                else if(!"DRAFT_SAVED".equals(row.get("operation"))||number(row.get("source_draft_revision"),true)!=0||row.get("template_version")!=null)throw unavailable();
                if(number(row.get("active_revision"),true)!=active)throw unavailable();previous=hash(previous+"\n"+raw);if(!previous.equals(db.get("revision_hash")))throw unavailable();checked.add(row);
            }
            if(rows.isEmpty()?head!=null:head==null||number(head.get("head_revision"),false)!=rows.size()||number(head.get("active_revision"),true)!=active||!previous.equals(head.get("head_hash")))throw unavailable();validateReceipts(kind,org,checked);return new Stored(kind,org,rows.size(),active,previous,List.copyOf(checked));
        }catch(Api.ApiException e){if(e.code==503)throw e;throw unavailable();}catch(IllegalArgumentException|DateTimeException e){throw unavailable();}
    }
    private static void validateReceipts(Kind kind,String org,List<Map<String,Object>> revisions)throws Exception {
        long count=number(Db.one("SELECT COUNT(*) AS n FROM approval_template_requests WHERE business_kind=? AND organization_code=?",kind.name(),org).get("n"),true);if(count>MAX_REQUESTS)throw unavailable();Set<Long> writes=new HashSet<>();long seen=0;
        for(int offset=0;offset<count;offset+=200)for(var row:Db.query("SELECT * FROM approval_template_requests WHERE business_kind=? AND organization_code=? ORDER BY account_id,request_id LIMIT 200 OFFSET ?",kind.name(),org,offset)){
            seen++;long account=number(row.get("account_id"),false),result=number(row.get("result_revision"),false);String req=request(row.get("request_id")),op=text(row.get("operation"),16),payload=text(row.get("payload_hash"),64);if(!Set.of("draft","publish").contains(op)||!payload.matches("[a-f0-9]{64}")||result>revisions.size()||!(row.get("saved") instanceof Boolean))throw unavailable();boolean saved=(Boolean)row.get("saved");if(!receiptHash(account,req,kind.name(),org,op,payload,result,saved).equals(row.get("receipt_hash")))throw unavailable();
            if(saved){Map<String,Object> revision=revisions.get((int)result-1);if(!writes.add(result)||number(revision.get("actor_account_id"),false)!=account||!revision.get("operation").equals(op.equals("draft")?"DRAFT_SAVED":"PUBLISHED"))throw unavailable();}}
        if(seen!=count||writes.size()!=revisions.size())throw unavailable();
    }
    private static String receiptHash(long account,String request,String kind,String org,String op,String payload,long result,boolean saved){return hash(canonical(map("account",account,"request",request,"kind",kind,"organization",org,"operation",op,"payload",payload,"result",result,"saved",saved)));}
    private static List<Map<String,Object>> candidates(Kind kind,String org,String role)throws Exception {Configuration c=requireOrg(org);List<Map<String,Object>> out=new ArrayList<>();for(Person person:c.people())try{long account=eligible(c,kind,org,person.personCode(),role);Map<String,Object> user=Db.one("SELECT name,username FROM users WHERE id=?",account);String label=user.get("name") instanceof String n&&!n.isBlank()?n:person.personCode();out.add(map("person_code",person.personCode(),"label",label,"review_role",role));}catch(Api.ApiException denied){if(denied.code!=409)throw denied;}return List.copyOf(out);}
    private static Map<String,Long> validatePeople(Kind kind,String org,Definition d,Configuration c)throws Exception {Map<String,Long> ids=new LinkedHashMap<>();Set<Long> accounts=new HashSet<>();for(Extra extra:d.extras()){long account=eligible(c,kind,org,extra.person(),extra.role());if(!accounts.add(account))fail(409,"多个补充节点不能由同一账号办理");ids.put(extra.person(),account);}return ids;}
    private static long eligible(Configuration c,Kind kind,String org,String code,String role)throws Exception {
        Person person=c.people().stream().filter(p->p.personCode().equals(code)).findFirst().orElse(null);List<AccountBinding> bindings=c.accountBindings().stream().filter(b->b.enabled()&&b.personCode().equals(code)).toList();if(person==null||!person.enabled()||!enabledOrg(c,person.organizationCode())||bindings.size()!=1)fail(409,"节点人员缺少有效且唯一的启用身份");long account=bindings.get(0).accountId();Map<String,Object> user=Db.one("SELECT status FROM users WHERE id=?",account);if(user==null||!(user.get("status") instanceof Number n)||n.intValue()!=1)fail(409,"节点账号未启用");Engine engine=new Engine(c);
        if(!engine.authorize("template-candidate",ignored->OptionalLong.of(account),new Resource(reviewResource(kind,role),org),Action.HANDLE).allowed())fail(409,"节点人员没有该机构的审批权限");for(String resource:readResources(kind))if(!engine.authorize("template-candidate",ignored->OptionalLong.of(account),new Resource(resource,org),Action.VIEW).allowed())fail(409,"节点人员缺少该机构的必要查看权限");return account;
    }
    private static Configuration requireOrg(String org)throws Exception {Configuration c=OrganizationAccessStore.configuration();if(c==null||!enabledOrg(c,org))fail(409,"请先核对已启用的正式机构");return c;}
    private static boolean enabledOrg(Configuration c,String org){Set<String> seen=new HashSet<>();String current=org;while(current!=null&&!current.isBlank()){if(!seen.add(current))return false;String id=current;Organization o=c.organizations().stream().filter(x->x.organizationCode().equals(id)).findFirst().orElse(null);if(o==null||!o.enabled())return false;current=o.parentOrganizationCode();}return !seen.isEmpty();}
    private static void require(Auth.Session s,String resource,Action action,String org)throws Api.ApiException {if(!OrganizationAccessStore.authorize(s,resource,action,org).allowed())fail(403,"没有该机构当前节点所需权限");}
    private static Auth.Session admin(Auth.Session supplied)throws Api.ApiException {Auth.Session s=Auth.current(supplied);if(s==null)fail(401,"未登录或会话已失效");if(!Auth.isAdmin(s))fail(403,"仅系统管理员可维护审批模板");return s;}
    private static String request(Object raw)throws Api.ApiException {String s=text(raw,96);if(!s.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{7,95}"))fail(400,"请求编号无效");return s;}
    private static String reason(Object raw)throws Api.ApiException {if(!(raw instanceof String s)||s.isBlank()||s.length()>1000||s.codePoints().anyMatch(c->c<32&&c!=9&&c!=10&&c!=13))fail(400,"请填写有效的修改理由");return (String)raw;}
    private static String random(){return UUID.randomUUID().toString().replace("-","");}
    private static void sweep(){long now=System.currentTimeMillis();PREVIEWS.entrySet().removeIf(e->e.getValue().expires<now||Auth.current(e.getValue().actor)==null);}
    /** Existing Json owns syntax and duplicate decoded keys; retain this route's original version token. */
    public static Map<String,Object> parseBody(byte[] bytes)throws Api.ApiException {
        if(bytes.length>MAX_BODY)fail(413,"模板请求过大");
        try{String raw=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();Map<String,Object> body=object(Json.parse(raw));unicode(body,0);
            int i=raw.indexOf('{')+1;while(i<raw.length()){i=space(raw,i);if(raw.charAt(i)=='}')break;int start=i;i=skip(raw,i);String key=(String)Json.parse(raw.substring(start,i));i=space(raw,i)+1;i=space(raw,i);start=i;i=skip(raw,i);if(key.equals("expected_revision")){String token=raw.substring(start,i).strip();if(token.length()>64||!token.matches("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?"))throw new IllegalArgumentException();body.put(key,new BigDecimal(token));number(body.get(key),true);}i=space(raw,i);if(raw.charAt(i)=='}')break;i++;}return body;
        }catch(Exception invalid){throw new Api.ApiException(400,"请求须为有效UTF-8 JSON，版本须为精确安全整数");}
    }
    private static int space(String s,int i){while(i<s.length()&&Character.isWhitespace(s.charAt(i)))i++;return i;}
    private static int skip(String s,int i){char first=s.charAt(i);if(first=='"'){for(i++;i<s.length();i++){char c=s.charAt(i);if(c=='\\')i++;else if(c=='"')return i+1;}return i;}
        if(first=='{'||first=='['){int depth=1;for(i++;i<s.length();i++){char c=s.charAt(i);if(c=='"'){i=skip(s,i)-1;continue;}if(c=='{'||c=='[')depth++;else if((c=='}'||c==']')&&--depth==0)return i+1;}return i;}
        while(i<s.length()&&s.charAt(i)!=','&&s.charAt(i)!='}')i++;return i;
    }
    private static void unicode(Object value,int depth){if(depth>8)throw new IllegalArgumentException();if(value instanceof String s){for(int i=0;i<s.length();i++){char c=s.charAt(i);if(Character.isHighSurrogate(c)){if(++i>=s.length()||!Character.isLowSurrogate(s.charAt(i)))throw new IllegalArgumentException();}else if(Character.isLowSurrogate(c))throw new IllegalArgumentException();}}
        else if(value instanceof Map<?,?> map){if(map.size()>20)throw new IllegalArgumentException();for(var e:map.entrySet()){unicode(e.getKey(),depth+1);unicode(e.getValue(),depth+1);}}
        else if(value instanceof List<?> list){if(list.size()>16)throw new IllegalArgumentException();for(Object e:list)unicode(e,depth+1);}
    }
    private static Map<String,String> query(HttpExchange ex)throws Api.ApiException {Map<String,String> result=new LinkedHashMap<>();String q=ex.getRequestURI().getRawQuery();if(q==null||q.isEmpty())return result;try{for(String item:q.split("&",-1)){String[] kv=item.split("=",-1);if(kv.length!=2)fail(400,"查询参数无效");String k=URLDecoder.decode(kv[0],StandardCharsets.UTF_8),v=URLDecoder.decode(kv[1],StandardCharsets.UTF_8);if(result.putIfAbsent(k,v)!=null)fail(400,"查询参数重复");}return result;}catch(IllegalArgumentException e){throw new Api.ApiException(400,"查询编码无效");}}
    private static int queryInt(String raw,int min,int max)throws Api.ApiException {try{if(!raw.matches("[0-9]+"))throw new IllegalArgumentException();int n=Integer.parseInt(raw);if(n<min||n>max)throw new IllegalArgumentException();return n;}catch(RuntimeException e){throw new Api.ApiException(400,"分页参数无效");}}
    private static Api.ApiException stale(){return new Api.ApiException(409,"模板、人员或预览已变化，请重新核对");}
    private static Api.ApiException unavailable(){return new Api.ApiException(503,"审批模板记录暂不可核对，请联系管理员");}
    private static void fail(int code,String message)throws Api.ApiException {throw new Api.ApiException(code,message);}
}
