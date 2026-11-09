package com.training;

import com.sun.net.httpserver.HttpExchange;
import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import static com.training.OrganizationAccess.*;

/** Team follow-up metadata only. Readiness, acceptance, projects and notices remain in M02/M03/S01. */
public final class TeamTodoIntegration {
    private TeamTodoIntegration() {}

    static void init() throws SQLException {
        Db.exec("CREATE TABLE IF NOT EXISTS workflow_team_todos(demand_id BIGINT PRIMARY KEY REFERENCES workflow_demands(demand_id),version BIGINT NOT NULL,team_code VARCHAR(120) NOT NULL,owner_code VARCHAR(120) NOT NULL,last_note VARCHAR(2000) NOT NULL,updated_by VARCHAR(120) NOT NULL,updated_at VARCHAR(40) NOT NULL)");
        Db.exec("CREATE TABLE IF NOT EXISTS workflow_team_todo_events(demand_id BIGINT NOT NULL REFERENCES workflow_demands(demand_id),version BIGINT NOT NULL,operation VARCHAR(24) NOT NULL,owner_code VARCHAR(120) NOT NULL,note VARCHAR(2000) NOT NULL,actor_code VARCHAR(120) NOT NULL,created_at VARCHAR(40) NOT NULL,PRIMARY KEY(demand_id,version))");
    }

    static boolean handle(HttpExchange ex,Auth.Session session) throws Exception {
        String path=ex.getRequestURI().getPath();
        if(path.equals("/api/team-todos")) {
            method(ex,"GET"); if(!Api.query(ex).isEmpty()) fail(422,"承接待办不接受身份或机构筛选参数");
            Api.ok(ex,list(session)); return true;
        }
        if(path.equals("/api/team-todos/claim")||path.equals("/api/team-todos/follow-up")) {
            method(ex,"POST"); Api.ok(ex,Map.of("item",mutate(path.endsWith("/claim")?"claim":"follow-up",session,Api.body(ex)))); return true;
        }
        return false;
    }

    /** Authoritative acceptance always wins; no editable completed flag exists. */
    static Map<String,Object> summary(long id,Person actor) throws Exception {
        Map<String,Object> todo=Db.one("SELECT * FROM workflow_team_todos WHERE demand_id=?",id);
        Map<String,Object> accepted=Db.one("SELECT * FROM workflow_acceptances WHERE demand_id=?",id);
        String owner=todo!=null?s(todo,"owner_code"):accepted!=null?s(accepted,"actor_code"):"";
        String team=todo!=null?s(todo,"team_code"):accepted!=null?s(accepted,"team_code"):"";
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("version",todo==null?0:n(todo,"version")); result.put("status",accepted!=null?"accepted":todo!=null?"following":"unclaimed");
        result.put("status_label",accepted!=null?"已承接":todo!=null?"跟进中":"未认领");
        result.put("owner_code",owner); result.put("team_code",team); result.put("mine",actor.personCode().equals(owner));
        result.put("accepted_by",accepted==null?"":s(accepted,"actor_code")); result.put("accepted_at",accepted==null?"":s(accepted,"created_at"));
        return result;
    }

    static boolean canAccept(long id,Person actor) throws Exception {
        Map<String,Object> todo=Db.one("SELECT team_code,owner_code FROM workflow_team_todos WHERE demand_id=?",id);
        return todo==null||(actor.organizationCode().equals(s(todo,"team_code"))&&actor.personCode().equals(s(todo,"owner_code")));
    }
    static void requireAcceptanceOwner(long id,Person actor) throws Exception {
        if(!canAccept(id,actor)) fail(409,"该需求已有跟进负责人，请由负责人承接，或先由同团队获授权人员改派");
    }

    static Map<String,Object> list(Auth.Session session) throws Exception {
        synchronized(Api.MUTATION_LOCK) {
            Person actor=OrganizationAccessStore.person(session);
            List<Map<String,Object>> items=new ArrayList<>();
            Map<String,Integer> counts=new LinkedHashMap<>(Map.of("unclaimed",0,"following",0,"accepted",0,"mine",0));
            for(Map<String,Object> row:Db.query("SELECT demand_id,organization_code FROM workflow_demands WHERE draft=FALSE ORDER BY demand_id DESC")) {
                String org=s(row,"organization_code");
                if(!allowed(session,"demand.read",Action.VIEW,org)||!allowed(session,"demand.accept",Action.HANDLE,org)) continue;
                Map<String,Object> view=WorkflowIntegration.detail(n(row,"demand_id"),session);
                Map<String,Object> todo=map(view.get("team_todo"));
                // Today's directory may pause a formerly approved flow; a real acceptance remains history.
                if(!"ready".equals(s(view,"queue"))&&!"accepted".equals(s(todo,"status"))) continue;
                if(!s(todo,"team_code").isEmpty()&&!actor.organizationCode().equals(s(todo,"team_code"))) continue;
                Map<String,Object> item=item(view,session,actor);
                items.add(item); String status=s(todo,"status"); counts.put(status,counts.get(status)+1);
                if(Boolean.TRUE.equals(todo.get("mine"))&&!status.equals("accepted")) counts.put("mine",counts.get("mine")+1);
            }
            return Map.of("items",items,"counts",counts,"person_code",actor.personCode(),"team_code",actor.organizationCode());
        }
    }

    private static Map<String,Object> item(Map<String,Object> view,Auth.Session session,Person actor) throws Exception {
        Map<String,Object> todo=map(view.get("team_todo")),form=map(view.get("form"));
        boolean ready="ready".equals(s(view,"queue"));
        boolean sameTeam=s(todo,"team_code").isEmpty()||actor.organizationCode().equals(s(todo,"team_code"));
        List<Map<String,Object>> members=members(actor,s(view,"organization_code"));
        Map<String,Object> result=new LinkedHashMap<>(todo);
        // Notes/history belong only to this authorized team endpoint, never the general demand detail.
        Map<String,Object> stored=Db.one("SELECT last_note,updated_at FROM workflow_team_todos WHERE demand_id=?",view.get("id"));
        result.put("last_note",stored==null?"":s(stored,"last_note")); result.put("updated_at",stored==null?s(todo,"accepted_at"):s(stored,"updated_at"));
        result.put("id",view.get("id")); result.put("workflow_version",view.get("version")); result.put("title",s(form,"title")); result.put("organization_code",view.get("organization_code"));
        result.put("business_path",view.get("business_path")); result.put("ready_reason","direct".equals(s(view,"business_path"))?"审批已通过":"已中标");
        result.put("expected_start_date",s(form,"expected_start_date")); result.put("category_text",s(form,"category_text")); result.put("project_id",view.get("project_id"));
        result.put("owner_available",s(todo,"owner_code").isEmpty()||members.stream().anyMatch(m->s(m,"code").equals(s(todo,"owner_code"))));
        // Display names reuse only currently authorized same-team candidates; codes remain authoritative.
        result.put("owner_label",memberLabel(members,s(todo,"owner_code")));
        result.put("accepted_by_label",memberLabel(members,s(todo,"accepted_by")));
        result.put("members",ready&&sameTeam?members:List.of());
        result.put("actions",Map.of("claim",ready&&sameTeam,"follow_up",ready&&sameTeam&&Boolean.TRUE.equals(todo.get("mine")),"accept",Boolean.TRUE.equals(map(view.get("actions")).get("accept"))));
        result.put("history",Db.query("SELECT version,operation,owner_code,note,actor_code,created_at FROM workflow_team_todo_events WHERE demand_id=? ORDER BY version",view.get("id")));
        return result;
    }

    /** Current M01 grants plus active bound accounts; never creates a team, role or person. */
    private static String memberLabel(List<Map<String,Object>> members,String code) {
        return members.stream().filter(m->s(m,"code").equals(code)).map(m->s(m,"label")).findFirst().orElse(code);
    }

    private static List<Map<String,Object>> members(Person actor,String org) throws Exception {
        Configuration config=OrganizationAccessStore.configuration(); Engine engine=new Engine(config);
        List<Map<String,Object>> result=new ArrayList<>(); if(config==null) return result;
        for(Person p:config.people()) {
            if(!p.enabled()||!p.organizationCode().equals(actor.organizationCode())) continue;
            for(AccountBinding binding:config.accountBindings()) {
                if(!binding.enabled()||!binding.personCode().equals(p.personCode())) continue;
                Map<String,Object> account=Db.one("SELECT status,name FROM users WHERE id=?",binding.accountId());
                if(account==null||n(account,"status")!=1) continue;
                if(!engine.authorize("current-bound-account",ignored->OptionalLong.of(binding.accountId()),new Resource("demand.accept",org),Action.HANDLE).allowed()
                    ||!engine.authorize("current-bound-account",ignored->OptionalLong.of(binding.accountId()),new Resource("demand.read",org),Action.VIEW).allowed()) continue;
                result.add(Map.of("code",p.personCode(),"label",s(account,"name").isBlank()?p.personCode():s(account,"name"))); break;
            }
        }
        return result;
    }

    /** Package visible for isolated tests; caller still must provide a live Auth session. */
    static Map<String,Object> mutate(String operation,Auth.Session session,Map<String,Object> input) throws Exception {
        synchronized(Api.MUTATION_LOCK) { return Db.transaction(()->{
            if(!Set.of("claim","follow-up").contains(operation)) fail(404,"承接待办操作不存在");
            Person actor=OrganizationAccessStore.person(session);
            Set<String> keys=new HashSet<>(Set.of("id","expected_version","expected_todo_version","request_id","note")); if(operation.equals("claim")) keys.add("owner_code");
            for(String key:input.keySet()) if(!keys.contains(key)) fail(422,"该操作不接受字段："+key);
            long id=number(input.get("id"),false,"需求编号"); String request=text(input.get("request_id"),160,"请求编号"); if(request.isEmpty()) fail(422,"缺少请求编号");
            String payload=Json.write(new TreeMap<>(input)),op="team-todo-"+operation;
            Map<String,Object> row=Db.one("SELECT * FROM workflow_demands WHERE demand_id=? FOR UPDATE",id); if(row==null) fail(404,"需求流程不存在");
            String org=s(row,"organization_code");
            if(!allowed(session,"demand.read",Action.VIEW,org)||!allowed(session,"demand.accept",Action.HANDLE,org)) fail(403,"当前账号没有该需求的团队承接权限");
            Map<String,Object> view=WorkflowIntegration.detail(id,session),todo=map(view.get("team_todo"));
            if(!s(todo,"team_code").isEmpty()&&!actor.organizationCode().equals(s(todo,"team_code"))) fail(403,"该事项已由其他团队认领");
            Map<String,Object> previous=Db.one("SELECT * FROM workflow_requests WHERE actor_code=? AND request_id=?",actor.personCode(),request);
            if(previous!=null) {
                if(!op.equals(s(previous,"operation"))||!payload.equals(s(previous,"payload"))||n(previous,"demand_id")!=id) fail(409,"请求编号已用于不同操作");
                return item(view,session,actor);
            }
            if(!"ready".equals(s(view,"queue"))) fail(409,"仅已批准或中标且尚未承接的需求可认领和跟进");
            if(number(input.get("expected_version"),false,"需求版本")!=n(view,"version")||number(input.get("expected_todo_version"),true,"跟进版本")!=n(todo,"version")) fail(409,"事项已更新，请刷新后办理");
            String note=text(input.get("note"),2000,"跟进说明"),owner=s(todo,"owner_code");
            if(operation.equals("claim")) {
                owner=text(input.get("owner_code"),120,"负责人"); final String candidate=owner;
                if(members(actor,org).stream().noneMatch(p->s(p,"code").equals(candidate))) fail(403,"负责人须为当前同团队、账号启用且获本需求承接权限的人员");
                if(owner.equals(s(todo,"owner_code"))) fail(409,"负责人未变化，请使用记录跟进");
                if(n(todo,"version")>0&&note.isEmpty()) fail(422,"改派请填写说明");
            } else {
                if(!actor.personCode().equals(owner)) fail(403,"仅当前负责人可记录跟进");
                if(note.isEmpty()) fail(422,"请填写本次跟进说明");
            }
            long version=n(todo,"version")+1; String now=Instant.now().toString();
            if(version==1) Db.exec("INSERT INTO workflow_team_todos VALUES(?,?,?,?,?,?,?)",id,version,actor.organizationCode(),owner,note,actor.personCode(),now);
            else update("UPDATE workflow_team_todos SET version=?,owner_code=?,last_note=?,updated_by=?,updated_at=? WHERE demand_id=? AND version=?",version,owner,note,actor.personCode(),now,id,version-1);
            Db.exec("INSERT INTO workflow_team_todo_events VALUES(?,?,?,?,?,?,?)",id,version,operation,owner,note,actor.personCode(),now);
            update("UPDATE workflow_demands SET version=version+1 WHERE demand_id=? AND version=?",id,n(view,"version"));
            Db.exec("INSERT INTO workflow_requests VALUES(?,?,?,?,?)",actor.personCode(),request,op,payload,id);
            return item(WorkflowIntegration.detail(id,session),session,actor);
        }); }
    }

    private static boolean allowed(Auth.Session s,String resource,Action action,String org) { return OrganizationAccessStore.authorize(s,resource,action,org).allowed(); }
    private static void update(String sql,Object... args) throws Exception { try(PreparedStatement ps=Db.get().prepareStatement(sql)) { for(int i=0;i<args.length;i++) ps.setObject(i+1,args[i]); if(ps.executeUpdate()!=1) fail(409,"事项已更新，请刷新后办理"); } }
    private static void method(HttpExchange ex,String method) throws Exception { if(!method.equals(ex.getRequestMethod())&&!(method.equals("GET")&&ex.getRequestMethod().equals("HEAD"))) fail(405,"请求方法不受支持"); }
    private static String text(Object value,int max,String label) throws Exception { if(value==null)return ""; if(!(value instanceof String))fail(422,label+"须为文本"); String s=((String)value).strip(); if(s.length()>max)fail(422,label+"超过长度限制"); return s; }
    private static long number(Object value,boolean zero,String label) throws Exception { try { long n=new BigDecimal(Objects.toString(value,"")).longValueExact(); if(n<(zero?0:1)||n>9007199254740991L)throw new ArithmeticException();return n; }catch(Exception e){throw new Api.ApiException(422,label+"须为有效整数");} }
    private static String s(Map<String,?> m,String key){return Objects.toString(m.get(key),"");}
    private static long n(Map<String,?> m,String key){return new BigDecimal(Objects.toString(m.get(key),"0")).longValueExact();}
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value){return (Map<String,Object>)value;}
    private static void fail(int code,String message)throws Api.ApiException{throw new Api.ApiException(code,message);}
}
