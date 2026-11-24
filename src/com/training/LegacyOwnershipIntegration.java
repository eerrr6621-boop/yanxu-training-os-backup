package com.training;

import com.sun.net.httpserver.HttpExchange;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import static com.training.OrganizationAccess.*;

/** Explicit ownership of historical records. Registration never fabricates a workflow acceptance. */
public final class LegacyOwnershipIntegration {
    private LegacyOwnershipIntegration() {}
    private static final String ROOT="/api/legacy-ownership";
    private static final Set<String> SCOPED=Set.of("demands","bids","projects","dispatches","teacher_evals","questionnaires","charges","fees","costs","q_sends","q_responses");
    private static final Set<String> CHILDREN=Set.of("dispatches","teacher_evals","questionnaires","charges","fees","costs");
    private record Scope(boolean exists,boolean historical,boolean registered,String org) {}

    static void init() throws SQLException {
        Db.exec("CREATE TABLE IF NOT EXISTS legacy_ownership(entity_type VARCHAR(16) NOT NULL,entity_id BIGINT NOT NULL,organization_code VARCHAR(120) NOT NULL,version BIGINT NOT NULL,source_json CLOB NOT NULL,event_json CLOB NOT NULL,digest VARCHAR(64) NOT NULL,PRIMARY KEY(entity_type,entity_id))");
        Db.exec("CREATE TABLE IF NOT EXISTS legacy_ownership_requests(account_id BIGINT NOT NULL,request_id VARCHAR(96) NOT NULL,payload CLOB NOT NULL,result CLOB NOT NULL,digest VARCHAR(64) NOT NULL,PRIMARY KEY(account_id,request_id))");
    }
    private static boolean installed() throws Exception {
        return ((Number)Db.one("SELECT COUNT(*) n FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='PUBLIC' AND TABLE_NAME='LEGACY_OWNERSHIP'").get("n")).intValue()==1;
    }
    static boolean matches(String path){return path.equals(ROOT)||path.startsWith(ROOT+"/");}
    /** Validate numeric lexemes before the legacy JSON parser converts them to Double. */
    static Map<String,Object> parseBody(byte[] bytes)throws Api.ApiException {
        if(bytes==null||bytes.length>8192)throw error(413,"历史归属请求过大");
        try {
            String raw=java.nio.charset.StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();boolean quoted=false;
            for(int i=0;i<raw.length();i++){char c=raw.charAt(i);if(quoted){if(c=='\\')i++;else if(c=='"')quoted=false;continue;}if(c=='"'){quoted=true;continue;}if(c=='-'||c>='0'&&c<='9'){int start=i;while(i+1<raw.length()&&"0123456789.eE+-".indexOf(raw.charAt(i+1))>=0)i++;String token=raw.substring(start,i+1);if(!token.matches("0|[1-9][0-9]{0,15}")||Long.parseLong(token)>9007199254740991L)throw new IllegalArgumentException();}}
            return Json.parseMap(raw);
        }catch(Exception invalid){throw error(400,"登记请求须为有效UTF-8 JSON，编号与版本须使用原始安全整数");}
    }
    public static boolean handle(HttpExchange ex,Auth.Session supplied) throws Exception {
        String path=ex.getRequestURI().getPath(); if(!path.equals(ROOT)&&!path.startsWith(ROOT+"/"))return false;
        Auth.Session actor=admin(supplied);ex.getResponseHeaders().set("Cache-Control","no-store");
        String method=ex.getRequestMethod();Object result;
        if(path.equals(ROOT+"/context")) { method(method,"GET","HEAD"); if(!Api.query(ex).isEmpty())throw error(400,"本接口不接受筛选参数");result=context(actor); }
        else if(path.equals(ROOT+"/audit")) { method(method,"GET","HEAD");Map<String,String> q=Api.query(ex);if(!q.keySet().equals(Set.of("type","id")))throw error(400,"请提供历史记录类型与编号");result=audit(actor,type(q.get("type")),id(q.get("id"))); }
        else if(path.equals(ROOT+"/preview")) { method(method,"POST");result=preview(actor,Api.body(ex)); }
        else if(path.equals(ROOT+"/register")) { method(method,"POST");result=register(actor,Api.body(ex)); }
        else throw error(404,"历史归属操作不存在");
        admin(actor);Api.ok(ex,result);return true;
    }
    private static Map<String,Object> context(Auth.Session actor)throws Exception {
        admin(actor);Configuration c=OrganizationAccessStore.configuration();List<Object> organizations=new ArrayList<>(),records=new ArrayList<>();
        if(c!=null)for(Organization o:c.organizations())if(active(c,o.organizationCode()))organizations.add(map("code",o.organizationCode(),"name",o.displayName()==null?o.organizationCode():o.displayName()));
        for(String type:List.of("DEMAND","PROJECT")) {
            String table=type.equals("DEMAND")?"demands":"projects";
            for(Map<String,Object> row:Db.query("SELECT * FROM "+table+" ORDER BY id DESC LIMIT 500")) {
                long n=id(row.get("id"));if(managed(type,n))continue;
                Map<String,Object> registered=source(type,n);
                records.add(map("type",type,"id",n,"title",str(row,"title"),"unit",str(row,"unit"),"registered",registered!=null,"organization_code",registered==null?null:registered.get("organization_code")));
            }
        }
        return map("organizations",organizations,"records",records,"notice","仅登记明确核实的历史归属；同名单位不会自动合并。登记不接入新审批、课酬或总结流程，也不恢复历史问卷外链。","limit",500);
    }
    public static Map<String,Object> preview(Auth.Session actor,Map<String,Object> body)throws Exception {
        admin(actor);keys(body,"type","id","organization_code");if(!(body.get("id") instanceof Number))throw error(400,"登记编号须为JSON整数");String type=type(body.get("type")),org=text(body.get("organization_code"),120);long n=id(body.get("id"));
        requireOrg(org);List<Map<String,Object>> records=family(type,n);List<Object> rows=new ArrayList<>();
        for(Map<String,Object> r:records) {
            String t=(String)r.get("type");long key=id(r.get("id"));Map<String,Object> old=source(t,key);
            if(old!=null&&!org.equals(old.get("organization_code")))throw error(409,"关联历史记录已登记到其他机构，请核对来源；不能重新分配归属");
            rows.add(map("type",t,"id",key,"title",r.get("title"),"unit",r.get("unit"),"registered",old!=null));
        }
        Map<String,Object> existing=source(type,n);String fingerprint=previewVersion(type,n,org,records);
        return map("type",type,"id",n,"organization_code",org,"expected_version",existing==null?0:1,"source_version",fingerprint,"records",rows,"can_register",existing==null,"notice","确认将把以上明确列出的需求与项目及其从属历史记录登记到所选机构；归属与来源不能通过普通编辑改绑。历史问卷外链继续不可用。");
    }
    public static Map<String,Object> register(Auth.Session supplied,Map<String,Object> body)throws Exception {
        synchronized(Api.MUTATION_LOCK) {
            Auth.Session actor=admin(supplied);keys(body,"type","id","organization_code","expected_version","source_version","reason","request_id");if(!(body.get("id") instanceof Number))throw error(400,"登记编号须为JSON整数");
            String type=type(body.get("type")),org=text(body.get("organization_code"),120),reason=text(body.get("reason"),1000),request=text(body.get("request_id"),96),version=text(body.get("source_version"),64);long n=id(body.get("id"));
            if(!version.matches("[a-f0-9]{64}")||!request.matches("[A-Za-z0-9][A-Za-z0-9._:-]{7,95}")||!(body.get("expected_version") instanceof Number)||number(body.get("expected_version"))!=0)throw error(400,"登记版本或请求编号无效");
            String payload=canonical(body);if(!Db.get().getAutoCommit())throw error(409,"历史登记不能嵌套其他业务事务");
            return Db.transaction(()->{
                admin(actor);requireOrg(org);Map<String,Object> receipt=Db.one("SELECT * FROM legacy_ownership_requests WHERE account_id=? AND request_id=?",actor.uid,request);
                if(receipt!=null){if(!receiptDigest(actor.uid,request,(String)receipt.get("payload"),(String)receipt.get("result")).equals(receipt.get("digest")))throw error(409,"历史登记回执无法核实");if(!payload.equals(receipt.get("payload")))throw error(409,"同一请求编号不能用于不同登记内容");Map<String,Object> response=Json.parseMap((String)receipt.get("result"));if(!(response.get("records") instanceof List<?> records))throw error(409,"历史登记回执来源无法核实");for(Object value:records){if(!(value instanceof Map<?,?> row))throw error(409,"历史登记回执来源无法核实");Map<String,Object> registered=source(type(row.get("type")),id(row.get("id")));if(registered==null||!org.equals(registered.get("organization_code")))throw error(409,"历史登记回执与当前来源不一致");}admin(actor);return response;}
                Map<String,Object> current=preview(actor,map("type",type,"id",n,"organization_code",org));
                if(!Boolean.TRUE.equals(current.get("can_register")))throw error(409,"该历史记录已登记，请重新读取归属");
                if(!version.equals(current.get("source_version")))throw error(409,"来源或机构配置已变化，请重新预览后登记");
                List<Map<String,Object>> family=family(type,n);List<Object> ids=new ArrayList<>();for(Map<String,Object> row:family)ids.add(map("type",row.get("type"),"id",row.get("id")));
                Map<String,Object> event=map("schema","LEGACY-OWNERSHIP-1","operation","REGISTER","actor_account",actor.uid,"created_at",Instant.now().toString(),"reason",reason,"request_id",request,"organization_code",org,"configuration_version",OrganizationAccessStore.configuration().version(),"source_version",version,"records",ids);
                for(Map<String,Object> row:family)if(source((String)row.get("type"),id(row.get("id")))==null)insert((String)row.get("type"),id(row.get("id")),org,event);
                Map<String,Object> result=map("registered",true,"type",type,"id",n,"organization_code",org,"version",1,"records",ids,"source_version",source(type,n).get("source_version"));String response=canonical(result);
                Db.exec("INSERT INTO legacy_ownership_requests VALUES(?,?,?,?,?)",actor.uid,request,payload,response,receiptDigest(actor.uid,request,payload,response));admin(actor);return result;
            });
        }
    }
    private static String receiptDigest(long actor,String request,String payload,String result){return hash(canonical(map("actor",actor,"request",request,"payload",payload,"result",result)));}
    private static void insert(String type,long n,String org,Map<String,Object> event)throws Exception {
        String src=canonical(structure(type,n)),audit=canonical(event);Db.exec("INSERT INTO legacy_ownership VALUES(?,?,?,?,?,?,?)",type,n,org,1,src,audit,hash(src+"\n"+audit));
    }
    /** Trusted host-only contract. Null means no historical registration, never a grant. */
    public static Map<String,Object> source(String type,long n)throws Exception {
        type=type(type);if(n<=0)return null;
        Map<String,Object> saved=stored(type,n);if(saved==null)return null;
        if(managed(type,n))throw error(409,"历史归属与新流程来源冲突，请核对原记录");
        Map<String,Object> structure=structure(type,n);String src=(String)saved.get("source_json"),audit=(String)saved.get("event_json"),org=str(saved,"organization_code");
        if(number(saved.get("version"))!=1||!canonical(structure).equals(src)||!hash(src+"\n"+audit).equals(saved.get("digest")))throw error(409,"历史归属审计或来源关联无法核实");
        Map<String,Object> event=Json.parseMap(audit);if(!"LEGACY-OWNERSHIP-1".equals(event.get("schema"))||!org.equals(event.get("organization_code"))||!Set.of("REGISTER","DERIVE_PROJECT").contains(event.get("operation"))||number(event.get("actor_account"))<=0||!(event.get("records") instanceof List<?>))throw error(409,"历史归属审计无法核实");
        boolean included=false;for(Object raw:(List<?>)event.get("records"))if(raw instanceof Map<?,?> entry&&type.equals(entry.get("type"))&&number(entry.get("id"))==n)included=true;
        if(!included)throw error(409,"历史归属审计未包括该来源记录");
        try{Instant.parse(text(event.get("created_at"),40));text(event.get("reason"),1000);}catch(RuntimeException invalid){throw error(409,"历史归属审计时间无法核实");}
        long demand=number(structure.get("demand_id"));String parent="";
        if(type.equals("PROJECT")&&demand>0){Map<String,Object> from=source("DEMAND",demand);if(from==null||!org.equals(from.get("organization_code")))throw error(409,"历史项目与来源需求归属不一致");parent=(String)from.get("source_version");}
        return map("type",type,"id",n,"organization_code",org,"demand_id",type.equals("DEMAND")?n:demand,"project_id",type.equals("PROJECT")?n:0,"ownership_version",1,"source_version",hash(str(saved,"digest")+"\n"+parent));
    }
    public static List<Map<String,Object>> contexts(Auth.Session actor,String org)throws Exception {
        current(actor);List<Map<String,Object>> result=new ArrayList<>();if(!installed()||!OrganizationAccessStore.authorize(actor,"demand.read",Action.VIEW,org).allowed())return result;
        for(Map<String,Object> row:Db.query("SELECT entity_type,entity_id FROM legacy_ownership WHERE organization_code=? ORDER BY entity_id DESC LIMIT 501",org)) {
            String type=(String)row.get("entity_type");long n=id(row.get("entity_id"));Map<String,Object> source=source(type,n);Map<String,Object> record=Db.one("SELECT title FROM "+(type.equals("DEMAND")?"demands":"projects")+" WHERE id=?",n);
            if(source!=null&&record!=null)result.add(map("type",type,"id",n,"label",(type.equals("DEMAND")?"历史需求 #":"历史项目 #")+n+" "+str(record,"title"),"source_version",source.get("source_version"),"legacy_registered",true));
        }current(actor);return result;
    }
    private static Map<String,Object> audit(Auth.Session actor,String type,long n)throws Exception {admin(actor);Map<String,Object> saved=stored(type,n);if(saved==null)return map("registered",false,"type",type,"id",n);Map<String,Object> result=new LinkedHashMap<>(source(type,n));result.put("registered",true);result.put("audit",Json.parseMap((String)saved.get("event_json")));return result;}
    private static Map<String,Object> stored(String type,long n)throws Exception {return installed()?Db.one("SELECT * FROM legacy_ownership WHERE entity_type=? AND entity_id=?",type,n):null;}
    private static boolean managed(String type,long n)throws Exception {
        if(type.equals("DEMAND"))return Db.one("SELECT demand_id FROM workflow_demands WHERE demand_id=?",n)!=null;
        Map<String,Object> p=Db.one("SELECT demand_id FROM projects WHERE id=?",n);
        return Db.one("SELECT project_id FROM workflow_acceptances WHERE project_id=?",n)!=null||(p!=null&&number(p.get("demand_id"))>0&&managed("DEMAND",number(p.get("demand_id"))));
    }
    private static Map<String,Object> structure(String type,long n)throws Exception {
        Map<String,Object> row=Db.one("SELECT * FROM "+(type.equals("DEMAND")?"demands":"projects")+" WHERE id=?",n);if(row==null)throw error(409,"历史来源记录不存在");
        long demand=type.equals("DEMAND")?0:number(row.get("demand_id")),bid=type.equals("DEMAND")?0:number(row.get("bid_id"));
        if(demand>0&&Db.one("SELECT id FROM demands WHERE id=?",demand)==null)throw error(409,"历史项目来源需求缺失，不能登记归属");
        if(bid>0){Map<String,Object> b=Db.one("SELECT demand_id FROM bids WHERE id=?",bid);if(b==null||demand<=0||number(b.get("demand_id"))!=demand)throw error(409,"历史投标与项目需求来源不一致");}
        return map("type",type,"id",n,"demand_id",demand,"bid_id",bid);
    }
    private static List<Map<String,Object>> family(String type,long n)throws Exception {
        if(managed(type,n))throw error(409,"该记录属于新流程或来源受损，不能登记为历史记录");Map<String,Object> root=structure(type,n);long demand=type.equals("DEMAND")?n:number(root.get("demand_id"));List<Map<String,Object>> result=new ArrayList<>();
        if(demand>0){result.add(previewRow("DEMAND",demand));for(Map<String,Object> p:Db.query("SELECT id FROM projects WHERE demand_id=? ORDER BY id LIMIT 201",demand))result.add(previewRow("PROJECT",id(p.get("id"))));}
        else result.add(previewRow("PROJECT",n));if(result.size()>200)throw error(409,"历史来源关联超过本次可核对容量，请分批核对原始资料");return result;
    }
    private static Map<String,Object> previewRow(String type,long n)throws Exception {if(managed(type,n))throw error(409,"关联记录已接入新流程，不能登记为历史来源");Map<String,Object> result=new LinkedHashMap<>(structure(type,n));Map<String,Object> row=Db.one("SELECT title,unit,status FROM "+(type.equals("DEMAND")?"demands":"projects")+" WHERE id=?",n);result.putAll(row);return result;}
    private static String previewVersion(String type,long n,String org,List<Map<String,Object>> family)throws Exception {List<Object> registered=new ArrayList<>();for(Map<String,Object> row:family){Map<String,Object> saved=source((String)row.get("type"),id(row.get("id")));registered.add(saved==null?null:saved.get("source_version"));}return hash(canonical(map("type",type,"id",n,"organization",org,"configuration",OrganizationAccessStore.configuration().version(),"records",family,"registered",registered)));}

    static boolean scoped(String module){return SCOPED.contains(module);}
    static boolean visible(String module,Map<String,Object> row,Auth.Session actor)throws Exception {
        if(!scoped(module))return true;Auth.Session s=current(actor);Scope scope=scope(module,id(row.get("id")));return scope.exists()&&allowed(scope,s,false);
    }
    static void requireDemand(long demand,Auth.Session actor,boolean write)throws Exception {require(scope("demands",demand),actor,write);}
    static void requireProject(long project,Auth.Session actor,boolean write)throws Exception {require(scope("projects",project),actor,write);}
    static void requireRow(String module,long n,Auth.Session actor,boolean write)throws Exception {if(scoped(module))require(scope(module,n),actor,write);}
    private static void require(Scope scope,Auth.Session actor,boolean write)throws Exception {Auth.Session s=current(actor);if(!scope.exists())throw error(404,"记录不存在或来源已缺失");if(!allowed(scope,s,write))throw error(403,"无权访问该机构的历史业务记录");}
    private static boolean allowed(Scope scope,Auth.Session s,boolean write){if(scope.historical()&&!scope.registered())return Auth.isAdmin(s);return !scope.org().isEmpty()&&OrganizationAccessStore.authorize(s,write?"demand.write":"demand.read",write?Action.HANDLE:Action.VIEW,scope.org()).allowed();}
    private static Scope scope(String module,long n)throws Exception {
        if(n<=0)return new Scope(false,true,false,"");Map<String,Object> row=Db.one("SELECT * FROM "+module+" WHERE id=?",n);if(row==null)return new Scope(false,true,false,"");
        if(module.equals("demands")) {Map<String,Object> w=Db.one("SELECT organization_code FROM workflow_demands WHERE demand_id=?",n);if(w!=null)return new Scope(true,false,true,str(w,"organization_code"));return legacyScope("DEMAND",n);}
        if(module.equals("projects")) {
            long demand=number(row.get("demand_id"));List<Map<String,Object>> accepted=Db.query("SELECT demand_id FROM workflow_acceptances WHERE project_id=?",n);Map<String,Object> w=demand<=0?null:Db.one("SELECT organization_code FROM workflow_demands WHERE demand_id=?",demand);
            if(w!=null||!accepted.isEmpty()){if(w==null||accepted.size()!=1||number(accepted.get(0).get("demand_id"))!=demand)return new Scope(true,false,false,"");return new Scope(true,false,true,str(w,"organization_code"));}return legacyScope("PROJECT",n);
        }
        if(module.equals("bids")){long demand=number(row.get("demand_id"));return parent(scope("demands",demand));}
        if(CHILDREN.contains(module))return parent(scope("projects",number(row.get("project_id"))));
        if(module.equals("q_sends")||module.equals("q_responses"))return parent(scope("questionnaires",number(row.get("questionnaire_id"))));
        throw new IllegalArgumentException("未知历史范围");
    }
    private static Scope parent(Scope p){return p.exists()?p:new Scope(true,true,false,"");}
    private static Scope legacyScope(String type,long n)throws Exception {try{Map<String,Object> source=source(type,n);return source==null?new Scope(true,true,false,""):new Scope(true,true,true,(String)source.get("organization_code"));}catch(Api.ApiException invalid){if(invalid.code!=409)throw invalid;return new Scope(true,false,false,"");}}
    static void guardMutation(String module,long n,Map<String,Object> body,Auth.Session actor)throws Exception {
        if(!scoped(module))return;if(body!=null)for(String key:List.of("organization_code","ownership_version","legacy_ownership","workflow_source"))if(body.containsKey(key))throw error(400,"普通业务编辑不能修改历史归属");
        if(n>0)requireRow(module,n,actor,true);
        if(body==null){if((module.equals("demands")||module.equals("projects"))&&stored(module.equals("demands")?"DEMAND":"PROJECT",n)!=null)throw error(409,"历史记录已有归属审计，不能删除；请保留原记录");return;}
        String parent=module.equals("bids")?"demands":CHILDREN.contains(module)?"projects":null;String field=module.equals("bids")?"demand_id":"project_id";
        if(parent!=null&&body.containsKey(field)){long next=id(body.get(field));requireRow(parent,next,actor,true);if(n>0){Map<String,Object> old=Db.one("SELECT "+field+" FROM "+module+" WHERE id=?",n);long previous=number(old.get(field));if(previous!=next){Scope from=scope(parent,previous),to=scope(parent,next);if(!from.registered()||!to.registered()||!from.org().equals(to.org()))throw error(409,"历史业务记录不能通过编辑跨机构或未登记来源移动");}}}
    }
    /** Called inside the original bids/win transaction, after access to its stable demand was checked. */
    static void deriveProject(Auth.Session actor,long demand,long project)throws Exception {
        if(Db.get().getAutoCommit())throw error(409,"历史项目归属须与中标立项同时保存");current(actor);Map<String,Object> parent=source("DEMAND",demand);if(parent==null)return;
        requireDemand(demand,actor,true);if(managed("PROJECT",project)||number(structure("PROJECT",project).get("demand_id"))!=demand||stored("PROJECT",project)!=null)throw error(409,"新项目与历史需求来源不一致");
        Map<String,Object> event=map("schema","LEGACY-OWNERSHIP-1","operation","DERIVE_PROJECT","actor_account",actor.uid,"created_at",Instant.now().toString(),"reason","既有中标立项操作按已登记需求来源生成项目","request_id","BID-WIN-PROJECT-"+project,"organization_code",parent.get("organization_code"),"configuration_version",OrganizationAccessStore.configuration().version(),"source_version",parent.get("source_version"),"records",List.of(map("type","PROJECT","id",project)),"demand_id",demand);
        insert("PROJECT",project,(String)parent.get("organization_code"),event);
    }
    /** Historical external questionnaires remain unavailable; registration is not publication authority. */
    static void rejectPublicQuestionnaire()throws Api.ApiException {throw error(409,"历史问卷外部作答入口暂未启用；归属登记不会恢复或重发旧链接，请通过内部历史统计查看已有结果");}
    private static Auth.Session current(Auth.Session s)throws Api.ApiException {Auth.Session current=Auth.current(s);if(current==null||current!=s)throw error(401,"登录已失效");return current;}
    private static Auth.Session admin(Auth.Session s)throws Api.ApiException {s=current(s);if(!Auth.isAdmin(s))throw error(403,"仅全局系统管理员可登记历史归属");return s;}
    private static void requireOrg(String org)throws Exception {Configuration c=OrganizationAccessStore.configuration();if(c==null||!active(c,org))throw error(409,"请选择现有且启用的机构，不会自动创建机构或权限");}
    private static boolean active(Configuration c,String code){Set<String> seen=new HashSet<>();String next=code;while(next!=null&&!next.isBlank()){if(!seen.add(next))return false;Organization found=null;for(Organization o:c.organizations())if(o.organizationCode().equals(next)){found=o;break;}if(found==null||!found.enabled())return false;next=found.parentOrganizationCode();}return !seen.isEmpty();}
    private static void method(String actual,String... expected)throws Api.ApiException {if(!Arrays.asList(expected).contains(actual))throw error(405,"请求方法不支持");}
    private static String type(Object value)throws Api.ApiException {if(!(value instanceof String s)||!Set.of("DEMAND","PROJECT").contains(s))throw error(400,"历史类型须为 DEMAND 或 PROJECT");return s;}
    private static void keys(Map<String,Object> body,String... fields)throws Api.ApiException {if(!body.keySet().equals(Set.of(fields)))throw error(400,"登记字段缺失或含有不支持的字段");}
    private static String text(Object value,int max)throws Api.ApiException {if(!(value instanceof String s)||s.isBlank()||s.length()>max||!s.equals(s.strip()))throw error(400,"登记文字字段无效");return s;}
    private static long id(Object value)throws Api.ApiException {long n=number(value);if(n<=0)throw error(400,"记录编号必须为正整数");return n;}
    private static long number(Object value)throws Api.ApiException {if(value==null||"".equals(value))return 0;try{long n=new java.math.BigDecimal(value.toString()).longValueExact();if(n<0||n>9007199254740991L)throw new IllegalArgumentException();return n;}catch(RuntimeException e){throw error(400,"记录编号必须为安全整数");}}
    private static String str(Map<String,Object> row,String key){return Objects.toString(row.get(key),"");}
    private static String canonical(Object value){return TrainingSummariesWorkflow.canonical(value);}
    private static String hash(String value){return RecommendationDecision.hash(value);}
    private static Map<String,Object> map(Object... args){Map<String,Object> m=new LinkedHashMap<>();for(int i=0;i<args.length;i+=2)m.put((String)args[i],args[i+1]);return m;}
    private static Api.ApiException error(int code,String message){return new Api.ApiException(code,message);}
}
