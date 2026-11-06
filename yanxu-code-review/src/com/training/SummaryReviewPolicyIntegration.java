package com.training;

import com.sun.net.httpserver.HttpExchange;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import static com.training.TrainingSummariesWorkflow.map;
import static com.training.TrainingSummariesWorkflow.canonical;
import static com.training.TrainingSummariesWorkflow.hash;

/** Global ordinary M08 defaults. Does not grant review rights or change frozen business events. */
public final class SummaryReviewPolicyIntegration {
    private SummaryReviewPolicyIntegration() {}
    private static final String BASE="/api/training-summaries/review-policy", ZERO="0".repeat(64);
    private static final int LIMIT=2000, CAPACITY=128, MAX_BODY=16384, MAX_REQUESTS=20000;
    private static final long TTL=300_000L;
    private static final Set<String> ORDERS=Set.of("BRANCH_THEN_BP","UNORDERED");
    private static final Map<String,Preview> PREVIEWS=new LinkedHashMap<>();
    public record Policy(String version,String order) {}
    private record Stored(long version,String head,List<Map<String,Object>> revisions) {
        Policy policy(){if(version==0)return null;Map<String,Object> row=revisions.get(revisions.size()-1);return new Policy((String)row.get("policy_version"),(String)row.get("review_order"));}
    }
    private record Effective(Stored stored,Policy policy) {
        String authority(){return stored.version==0?"DEPLOYMENT":"DATABASE";}
        Map<String,Object> before(){return map("authority",authority(),"policy_version",policy==null?null:policy.version,"review_order",policy==null?null:policy.order);}
        String fingerprint(){return hash(canonical(map("revision",stored.version,"head",stored.head,"before",before())));}
    }
    private record Preview(Auth.Session actor,long expires,long expected,String fingerprint,Map<String,Object> before,Policy after,String reason,boolean changed) {}

    /** Empty schema only. No deployment value is automatically published. */
    static void init() throws SQLException {
        Db.exec("CREATE TABLE IF NOT EXISTS m08_review_policy_revisions(version BIGINT PRIMARY KEY CHECK(version>0),payload_json CLOB NOT NULL,previous_hash VARCHAR(64) NOT NULL,revision_hash VARCHAR(64) NOT NULL)");
        Db.exec("CREATE TABLE IF NOT EXISTS m08_review_policy_head(id INT PRIMARY KEY CHECK(id=1),version BIGINT NOT NULL REFERENCES m08_review_policy_revisions(version),head_hash VARCHAR(64) NOT NULL)");
        Db.exec("CREATE TABLE IF NOT EXISTS m08_review_policy_requests(account_id BIGINT NOT NULL,request_id VARCHAR(96) NOT NULL,payload_hash VARCHAR(64) NOT NULL,result_version BIGINT NOT NULL REFERENCES m08_review_policy_revisions(version),saved BOOLEAN NOT NULL,receipt_hash VARCHAR(64) NOT NULL,PRIMARY KEY(account_id,request_id))");
    }
    /** Read-only and safe inside the existing business transaction. Never falls back after publication. */
    public static Policy resolve() throws Exception {
        synchronized(Api.MUTATION_LOCK){return effective().policy;}
    }
    public static boolean matches(String path){return path!=null&&(path.equals(BASE)||path.startsWith(BASE+"/"));}
    public static void preflight(HttpExchange ex)throws Api.ApiException {
        if(!matches(ex.getRequestURI().getPath()))return;
        synchronized(Api.MUTATION_LOCK){noStore(ex);requireAdmin(Auth.get(Api.token(ex)));operation(ex);
            String length=ex.getRequestHeaders().getFirst("Content-Length");if(length!=null)try{if(Long.parseLong(length)>MAX_BODY)fail(413,"规则请求过大");}catch(NumberFormatException e){fail(400,"请求长度无效");}}
    }
    public static boolean handle(HttpExchange ex,Auth.Session supplied)throws Exception {
        if(!matches(ex.getRequestURI().getPath()))return false;
        synchronized(Api.MUTATION_LOCK){noStore(ex);Auth.Session actor=requireAdmin(supplied);if(Auth.get(Api.token(ex))!=actor)fail(401,"登录会话无效或已失效");
            String op=operation(ex);Object value;
            if(op.equals("read"))value=read(actor);
            else if(op.equals("history")){Map<String,String> q=Api.query(ex);value=history(actor,queryNumber(q.getOrDefault("offset","0"),0,LIMIT),queryNumber(q.getOrDefault("limit","20"),1,100));}
            else {Map<String,Object> body=Api.body(ex);if(canonical(body).getBytes(java.nio.charset.StandardCharsets.UTF_8).length>MAX_BODY)fail(413,"规则请求过大");value=op.equals("preview")?preview(actor,body):confirm(actor,body);}
            Api.ok(ex,value);return true;
        }
    }
    public static Map<String,Object> read(Auth.Session supplied)throws Exception {
        synchronized(Api.MUTATION_LOCK){requireAdmin(supplied);noTransaction();Effective state=effective();return map("scope","GLOBAL","authority",state.authority(),"revision",state.stored.version,"configured",state.policy!=null,"current",policyView(state.policy),"deployment",deploymentView(),"editable",true,"future_only",true);}
    }
    public static Map<String,Object> history(Auth.Session supplied,int offset,int limit)throws Exception {
        synchronized(Api.MUTATION_LOCK){requireAdmin(supplied);noTransaction();if(offset<0||offset>LIMIT||limit<1||limit>100)fail(400,"分页参数无效");Stored state=stored();List<Map<String,Object>> rows=new ArrayList<>();for(int i=state.revisions.size()-1-offset;i>=0&&rows.size()<limit;i--)rows.add(new LinkedHashMap<>(state.revisions.get(i)));return map("revision",state.version,"total",state.revisions.size(),"offset",offset,"limit",limit,"read_only",true,"items",rows);}
    }
    public static Map<String,Object> preview(Auth.Session supplied,Map<String,Object> body)throws Exception {
        synchronized(Api.MUTATION_LOCK){Auth.Session actor=requireAdmin(supplied);noTransaction();fields(body,Set.of("expected_version","review_order","reason"));long expected=number(body.get("expected_version"),true);String order=order(body.get("review_order")),reason=reason(body.get("reason"));Effective state=effective();if(expected!=state.stored.version)throw stale();
            boolean changed=state.stored.version==0||!state.policy.order.equals(order);if(changed&&expected>=LIMIT)fail(409,"规则版本数量已达上限，请联系维护人员");
            Policy after=changed?new Policy("M08-DB-"+(expected+1)+"-"+Auth.randomToken(16),order):state.policy;
            sweep();if(PREVIEWS.size()>=CAPACITY)fail(409,"待核对的规则较多，请稍后重新预览");String token;do{token=Auth.randomToken(32);}while(PREVIEWS.containsKey(token));
            requireAdmin(actor);Preview value=new Preview(actor,System.currentTimeMillis()+TTL,expected,state.fingerprint(),state.before(),after,reason,changed);PREVIEWS.put(token,value);
            return map("expected_version",expected,"before",state.before(),"after",policyView(after),"changed",changed,"authority_change",state.stored.version==0,"future_only",true,"review_token",token,"expires_at",Instant.ofEpochMilli(value.expires).toString());
        }
    }
    public static Map<String,Object> confirm(Auth.Session supplied,Map<String,Object> body)throws Exception {
        synchronized(Api.MUTATION_LOCK){Auth.Session actor=requireAdmin(supplied);noTransaction();fields(body,Set.of("review_token","request_id"));String token=text(body.get("review_token"),64),request=text(body.get("request_id"),96);if(!token.matches("[a-f0-9]{64}")||!request.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{7,95}"))fail(400,"核对标识或请求编号格式无效");String payload=hash(canonical(body));
            try{Map<String,Object> resultReceipt=Db.transaction(()->{
                requireAdmin(actor);Stored state=stored();Map<String,Object> retry=Db.one("SELECT * FROM m08_review_policy_requests WHERE account_id=? AND request_id=?",actor.uid,request);
                if(retry!=null){if(!Objects.equals(retry.get("payload_hash"),payload))fail(409,"请求编号已用于不同的确认内容");long result=number(retry.get("result_version"),false);boolean saved=Boolean.TRUE.equals(retry.get("saved"));if(!Objects.equals(retry.get("receipt_hash"),receiptHash(actor.uid,request,payload,result,saved))||result>state.version)throw unavailable();return receipt(state,result,saved,true);}
                sweep();Preview review=PREVIEWS.get(token);if(review==null||review.actor!=actor)throw stale();Effective current=effective(state);if(review.expected!=state.version||!review.fingerprint.equals(current.fingerprint()))throw stale();
                long result=state.version;
                if(review.changed){result=state.version+1;Map<String,Object> data=map("revision",result,"policy_version",review.after.version,"review_order",review.after.order,"previous_revision",state.version,"before",review.before,"actor_account_id",actor.uid,"actor_name",actor.name==null||actor.name.isBlank()?actor.username:actor.name,"created_at",Instant.now().toString(),"reason",review.reason);String payloadJson=canonical(data),digest=revisionHash(payloadJson,state.head);
                    Db.exec("INSERT INTO m08_review_policy_revisions(version,payload_json,previous_hash,revision_hash) VALUES(?,?,?,?)",result,payloadJson,state.head,digest);
                    if(state.version==0)Db.exec("INSERT INTO m08_review_policy_head(id,version,head_hash) VALUES(1,?,?)",result,digest);
                    else try(var update=Db.get().prepareStatement("UPDATE m08_review_policy_head SET version=?,head_hash=? WHERE id=1 AND version=? AND head_hash=?")){update.setLong(1,result);update.setString(2,digest);update.setLong(3,state.version);update.setString(4,state.head);if(update.executeUpdate()!=1)throw stale();}
                }
                requireAdmin(actor);if(number(Db.one("SELECT COUNT(*) AS n FROM m08_review_policy_requests").get("n"),true)>=MAX_REQUESTS)fail(409,"规则确认记录已达上限，请联系维护人员");Db.exec("INSERT INTO m08_review_policy_requests(account_id,request_id,payload_hash,result_version,saved,receipt_hash) VALUES(?,?,?,?,?,?)",actor.uid,request,payload,result,review.changed,receiptHash(actor.uid,request,payload,result,review.changed));
                Stored verified=stored();requireAdmin(actor);return receipt(verified,result,review.changed,false);
            });PREVIEWS.remove(token);return resultReceipt;}catch(SQLException e){if(Set.of("23505","40001").contains(e.getSQLState()))throw stale();throw e;}
        }
    }
    private static Map<String,Object> receipt(Stored state,long result,boolean saved,boolean replayed)throws Api.ApiException {if(result<1||result>state.revisions.size())throw unavailable();Map<String,Object> row=state.revisions.get((int)result-1);return map("saved",saved,"unchanged",!saved,"replayed",replayed,"result_revision",result,"current_revision",state.version,"policy_version",row.get("policy_version"),"review_order",row.get("review_order"),"authority","DATABASE");}
    private static String receiptHash(long actor,String request,String payload,long result,boolean saved){return hash(canonical(map("account_id",actor,"request_id",request,"payload_hash",payload,"result_version",result,"saved",saved)));}
    private static String revisionHash(String payload,String previous){return hash(canonical(map("format","M08-REVIEW-POLICY-1","payload",payload,"previous_hash",previous)));}
    private static Map<String,Object> policyView(Policy policy){return policy==null?null:map("policy_version",policy.version,"review_order",policy.order);}
    private static Effective effective()throws Exception{return effective(stored());}
    private static Effective effective(Stored state)throws Api.ApiException{return new Effective(state,state.version==0?deployment():state.policy());}
    private static Policy deployment()throws Api.ApiException {String order=System.getProperty("training.summary.review.order","").strip(),version=System.getProperty("training.summary.review.policyVersion","").strip();if(order.isEmpty()&&version.isEmpty())return null;if(!ORDERS.contains(order)||!version.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,95}"))throw unavailable();return new Policy(version,order);}
    private static Map<String,Object> deploymentView(){try{Policy p=deployment();return map("valid",true,"configured",p!=null,"review_order",p==null?null:p.order,"policy_version",p==null?null:p.version);}catch(Api.ApiException e){return map("valid",false,"configured",false,"review_order",null,"policy_version",null);}}
    @SuppressWarnings("unchecked") private static Stored stored()throws Exception {
        try {
            List<Map<String,Object>> rows=Db.query("SELECT * FROM m08_review_policy_revisions ORDER BY version LIMIT 2001");List<Map<String,Object>> heads=Db.query("SELECT * FROM m08_review_policy_head LIMIT 2");
            if(rows.isEmpty()){if(!heads.isEmpty()||number(Db.one("SELECT COUNT(*) AS n FROM m08_review_policy_requests").get("n"),true)!=0)throw unavailable();return new Stored(0,ZERO,List.of());}
            if(rows.size()>LIMIT||heads.size()!=1)throw unavailable();long version=0;String previous=ZERO;Map<String,Object> last=null;List<Map<String,Object>> history=new ArrayList<>();
            for(Map<String,Object> row:rows){version++;if(number(row.get("version"),false)!=version)throw unavailable();String json=String.valueOf(row.get("payload_json"));if(json.length()>12000)throw unavailable();Object parsed=Json.parse(json);if(!(parsed instanceof Map<?,?>))throw unavailable();Map<String,Object> data=(Map<String,Object>)parsed;
                if(!data.keySet().equals(Set.of("revision","policy_version","review_order","previous_revision","before","actor_account_id","actor_name","created_at","reason"))||number(data.get("revision"),false)!=version||number(data.get("previous_revision"),true)!=version-1)throw unavailable();
                order(data.get("review_order"));reason(data.get("reason"));number(data.get("actor_account_id"),false);text(data.get("actor_name"),500);Instant.parse(text(data.get("created_at"),40));String pv=text(data.get("policy_version"),96);if(!pv.matches("M08-DB-"+version+"-[a-f0-9]{32}"))throw unavailable();
                if(!(data.get("before") instanceof Map<?,?>))throw unavailable();Map<String,Object> before=(Map<String,Object>)data.get("before");if(!before.keySet().equals(Set.of("authority","policy_version","review_order")))throw unavailable();
                if(version==1){if(!"DEPLOYMENT".equals(before.get("authority")))throw unavailable();if(before.get("policy_version")==null){if(before.get("review_order")!=null)throw unavailable();}else{if(!text(before.get("policy_version"),96).matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,95}"))throw unavailable();order(before.get("review_order"));}}
                else if(!canonical(before).equals(canonical(map("authority","DATABASE","policy_version",last.get("policy_version"),"review_order",last.get("review_order")))))throw unavailable();
                String expected=revisionHash(canonical(data),previous);if(!canonical(data).equals(json)||!Objects.equals(row.get("previous_hash"),previous)||!Objects.equals(row.get("revision_hash"),expected))throw unavailable();previous=expected;last=data;history.add(data);
            }
            Map<String,Object> head=heads.get(0);if(number(head.get("id"),false)!=1||number(head.get("version"),false)!=version||!Objects.equals(head.get("head_hash"),previous))throw unavailable();verifyRequests(version);return new Stored(version,previous,List.copyOf(history));
        }catch(Api.ApiException|IllegalArgumentException|java.time.DateTimeException e){throw unavailable();}
    }
    private static void verifyRequests(long head)throws Exception {
        long count=number(Db.one("SELECT COUNT(*) AS n FROM m08_review_policy_requests").get("n"),true);if(count>MAX_REQUESTS)throw unavailable();Set<Long> publications=new HashSet<>();long seen=0;
        for(int offset=0;offset<count;offset+=200){var rows=Db.query("SELECT * FROM m08_review_policy_requests ORDER BY account_id,request_id LIMIT 200 OFFSET ?",offset);for(var row:rows){seen++;long account=number(row.get("account_id"),false),result=number(row.get("result_version"),false);String request=text(row.get("request_id"),96),payload=text(row.get("payload_hash"),64);if(!request.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{7,95}")||!payload.matches("[a-f0-9]{64}")||result>head||!(row.get("saved") instanceof Boolean))throw unavailable();boolean saved=(Boolean)row.get("saved");if(!Objects.equals(row.get("receipt_hash"),receiptHash(account,request,payload,result,saved))||saved&&!publications.add(result))throw unavailable();}}
        if(seen!=count||publications.size()!=head)throw unavailable();
    }
    private static Auth.Session requireAdmin(Auth.Session supplied)throws Api.ApiException{Auth.Session actor=Auth.current(supplied);if(actor==null)fail(401,"未登录或会话已过期，请重新登录");if(!Auth.isAdmin(actor))fail(403,"仅系统管理员可维护总结复核规则");return actor;}
    private static void noTransaction()throws Exception{if(!Db.get().getAutoCommit())fail(409,"规则管理接口不能嵌入事务");}
    private static void noStore(HttpExchange ex){ex.getResponseHeaders().set("Cache-Control","no-store");}
    private static String operation(HttpExchange ex)throws Api.ApiException {
        String path=ex.getRequestURI().getPath(),op=path.equals(BASE)?"read":path.startsWith(BASE+"/")?path.substring(BASE.length()+1):"";if(!path.equals(BASE)&&op.equals("read")||!Set.of("read","history","preview","confirm").contains(op))fail(404,"规则接口不存在");String method=Set.of("read","history").contains(op)?"GET":"POST";if(!method.equals(ex.getRequestMethod())){ex.getResponseHeaders().set("Allow",method);fail(405,"请求方法不支持");}
        String query=ex.getRequestURI().getRawQuery();if(query!=null&&!query.isEmpty()&&(!op.equals("history")||!query.matches("(?:offset=[0-9]+(?:&limit=[0-9]+)?|limit=[0-9]+(?:&offset=[0-9]+)?)")))fail(400,"查询参数无效");return op;
    }
    /** Uses the existing duplicate-key rejecting parser after strict UTF-8 and scalar checks. */
    @SuppressWarnings("unchecked") public static Map<String,Object> parseBody(byte[] bytes)throws Api.ApiException {
        if(bytes==null)fail(400,"请求内容必须为JSON对象");if(bytes.length>MAX_BODY)fail(413,"请求内容过大");
        try{String decoded=java.nio.charset.StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();Object parsed=Json.parse(decoded);if(!(parsed instanceof Map<?,?>))throw new IllegalArgumentException();Map<String,Object> result=(Map<String,Object>)parsed;
            for(var entry:result.entrySet()){if(!validSurrogates(entry.getKey())||!(entry.getValue() instanceof String||entry.getValue() instanceof Number)||entry.getValue() instanceof String value&&!validSurrogates(value))throw new IllegalArgumentException();}
            preserveExactVersion(decoded,result);return result;
        }catch(Exception invalid){throw new Api.ApiException(400,"请求必须为有效UTF-8 JSON对象，字段值为文字或版本数字");}
    }
    /** Json has already validated syntax/duplicate decoded keys; retain the version's original numeric precision. */
    private static void preserveExactVersion(String source,Map<String,Object> result)throws Api.ApiException {
        int i=source.indexOf('{')+1;
        while(i<source.length()){while(i<source.length()&&Character.isWhitespace(source.charAt(i)))i++;if(source.charAt(i)=='}')return;
            int start=i++;while(i<source.length()){char c=source.charAt(i++);if(c=='\\')i++;else if(c=='"')break;}String key=(String)Json.parse(source.substring(start,i));
            while(source.charAt(i)!=':')i++;i++;while(Character.isWhitespace(source.charAt(i)))i++;start=i;
            if(source.charAt(i)=='"'){i++;while(i<source.length()){char c=source.charAt(i++);if(c=='\\')i++;else if(c=='"')break;}}
            else {while(i<source.length()&&source.charAt(i)!=','&&source.charAt(i)!='}')i++;if(key.equals("expected_version")){String raw=source.substring(start,i).strip();if(raw.length()>64)fail(400,"版本须为安全整数");try{long value=new BigDecimal(raw).longValueExact();if(value<0||value>9007199254740991L)throw new IllegalArgumentException();result.put(key,value);}catch(RuntimeException invalid){fail(400,"版本须为安全整数");}}}
            while(i<source.length()&&Character.isWhitespace(source.charAt(i)))i++;if(source.charAt(i)=='}')return;i++;
        }
    }
    private static boolean validSurrogates(String s){for(int i=0;i<s.length();i++){char c=s.charAt(i);if(Character.isHighSurrogate(c)){if(++i>=s.length()||!Character.isLowSurrogate(s.charAt(i)))return false;}else if(Character.isLowSurrogate(c))return false;}return true;}
    private static void fields(Map<String,Object> body,Set<String> keys)throws Api.ApiException{if(body==null||!body.keySet().equals(keys))fail(400,"请求字段不完整或包含未知字段");}
    private static long number(Object value,boolean zero)throws Api.ApiException{try{if(!(value instanceof Number))throw new IllegalArgumentException();long n=new BigDecimal(value.toString()).longValueExact();if(n<(zero?0:1)||n>9007199254740991L)throw new IllegalArgumentException();return n;}catch(RuntimeException e){throw new Api.ApiException(400,"版本须为安全整数");}}
    private static int queryNumber(String value,int min,int max)throws Api.ApiException{try{int n=Integer.parseInt(value);if(n<min||n>max)throw new IllegalArgumentException();return n;}catch(RuntimeException e){throw new Api.ApiException(400,"分页参数无效");}}
    private static String text(Object value,int max)throws Api.ApiException{if(!(value instanceof String s)||s.isBlank()||s.length()>max||!validSurrogates(s))fail(400,"文字格式或长度无效");return (String)value;}
    private static String order(Object value)throws Api.ApiException{if(!(value instanceof String s)||!ORDERS.contains(s))fail(400,"请选择负责人后BP或两方无先后复核");return (String)value;}
    private static String reason(Object value)throws Api.ApiException{String s=text(value,1000);if(s.codePoints().anyMatch(c->c<32&&c!=9&&c!=10&&c!=13))fail(400,"变更理由包含无效字符");return s;}
    private static void sweep(){long now=System.currentTimeMillis();PREVIEWS.entrySet().removeIf(e->e.getValue().expires<=now||Auth.current(e.getValue().actor)==null);}
    private static Api.ApiException stale(){return new Api.ApiException(409,"规则或预览已变化，请重新读取并核对");}
    private static Api.ApiException unavailable(){return new Api.ApiException(503,"总结新提交规则暂不可核对，请联系系统管理员");}
    private static void fail(int code,String message)throws Api.ApiException{throw new Api.ApiException(code,message);}
}
