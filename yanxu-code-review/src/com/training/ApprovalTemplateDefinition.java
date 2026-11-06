package com.training;

import java.math.BigDecimal;
import java.util.*;
import static com.training.TrainingSummariesWorkflow.*;

/** Bounded template language shared by M03 and M08. No expression execution or inferred permissions. */
public final class ApprovalTemplateDefinition {
    private ApprovalTemplateDefinition() {}
    public enum Kind { M03_DEMAND, M08_SUMMARY }
    public static final int MAX_EXTRA=4;
    public record Extra(String id,String title,String role,String person) {}
    public record Definition(Kind kind,String order,List<Extra> before,List<Extra> after) {
        public Definition { before=List.copyOf(before);after=List.copyOf(after); }
        public List<Extra> extras(){List<Extra> all=new ArrayList<>(before);all.addAll(after);return List.copyOf(all);}
    }
    public record Node(String id,String title,String role,String person,long account,String resource,int phase,List<String> responsibilities) {
        public Node { responsibilities=List.copyOf(responsibilities); }
        public boolean extra(){return id.startsWith("extra-");}
        public Map<String,Object> json(){return map("node_id",id,"title",title,"review_role",role,"person_code",person,"account_id",account,"resource",resource,"phase",phase,"responsibilities",responsibilities);}
    }
    public record Frozen(Kind kind,String organization,String templateVersion,long revision,String definitionHash,Definition definition,String configVersion,String submitter,long submitterAccount,List<Node> nodes) {
        public Frozen { nodes=List.copyOf(nodes); }
        public Map<String,Object> json(){return map("format","APPROVAL-TEMPLATE-FROZEN-1","business_kind",kind.name(),"organization_code",organization,"template_version",templateVersion,"template_revision",revision,"definition_hash",definitionHash,"definition",ApprovalTemplateDefinition.json(definition),"config_version",configVersion,"submitter_code",submitter,"submitter_account",submitterAccount,"nodes",nodes.stream().map(Node::json).toList());}
        public Node node(String id){return nodes.stream().filter(n->n.id.equals(id)).findFirst().orElse(null);}
        public List<Node> pending(Set<String> approved){
            int phase=nodes.stream().filter(n->!approved.contains(n.id)).mapToInt(Node::phase).min().orElse(Integer.MAX_VALUE);
            return nodes.stream().filter(n->n.phase==phase&&!approved.contains(n.id)).toList();
        }
        public boolean complete(Set<String> approved){return approved.size()==nodes.size()&&nodes.stream().allMatch(n->approved.contains(n.id));}
    }
    public static Definition parse(Kind kind,Object raw)throws Api.ApiException {
        Map<String,Object> m=object(raw);keys(m,"format","core_order","before_nodes","after_nodes");
        if(!"APPROVAL-TEMPLATE-1".equals(m.get("format")))bad("模板格式无效");
        String order=text(m.get("core_order"),32);
        if(!Set.of("BRANCH_THEN_BP","UNORDERED").contains(order)||kind==Kind.M03_DEMAND&&!order.equals("BRANCH_THEN_BP"))bad("需求负责人和BP顺序固定；总结请选择允许的复核顺序");
        List<Extra> before=extras(m.get("before_nodes")),after=extras(m.get("after_nodes"));
        if(before.size()+after.size()>MAX_EXTRA)bad("补充审批节点最多四个");
        Set<String> ids=new HashSet<>(),people=new HashSet<>();for(Extra x:concat(before,after))if(!ids.add(x.id)||!people.add(x.person))bad("补充节点或办理人员重复");
        return new Definition(kind,order,before,after);
    }
    public static Map<String,Object> json(Definition d){return map("format","APPROVAL-TEMPLATE-1","core_order",d.order,"before_nodes",d.before.stream().map(ApprovalTemplateDefinition::json).toList(),"after_nodes",d.after.stream().map(ApprovalTemplateDefinition::json).toList());}
    private static Map<String,Object> json(Extra x){return map("node_id",x.id,"title",x.title,"review_role",x.role,"person_code",x.person);}
    private static List<Extra> extras(Object raw)throws Api.ApiException {
        if(!(raw instanceof List<?> list)||list.size()>MAX_EXTRA)bad("补充节点须为有界列表");
        List<Extra> out=new ArrayList<>();for(Object v:(List<?>)raw){Map<String,Object> x=object(v);keys(x,"node_id","title","review_role","person_code");
            String id=text(x.get("node_id"),38),role=text(x.get("review_role"),8);if(!id.matches("extra-[a-z0-9][a-z0-9-]{0,31}")||!Set.of("BRANCH","BP").contains(role))bad("补充节点标识或复核职责无效");
            out.add(new Extra(id,text(x.get("title"),60),role,text(x.get("person_code"),120)));}
        return List.copyOf(out);
    }
    private static List<Extra> concat(List<Extra>a,List<Extra>b){List<Extra> all=new ArrayList<>(a);all.addAll(b);return all;}
    public static String reviewResource(Kind kind,String role){return kind==Kind.M03_DEMAND?"approval.review":role.equals("BRANCH")?"summary.review.branch":"summary.review.bp";}
    public static List<String> readResources(Kind kind){return kind==Kind.M03_DEMAND?List.of("demand.read"):List.of("summary.read","delivery.read","survey.read");}
    /** Historical restore is structural only. Current identity/authorization belongs to the host at action time. */
    public static Frozen decode(Object raw)throws Api.ApiException {
        Map<String,Object> m=object(raw);keys(m,"format","business_kind","organization_code","template_version","template_revision","definition_hash","definition","config_version","submitter_code","submitter_account","nodes");
        if(!"APPROVAL-TEMPLATE-FROZEN-1".equals(m.get("format")))bad("冻结模板格式无效");
        Kind kind;try{kind=Kind.valueOf(text(m.get("business_kind"),32));}catch(IllegalArgumentException invalid){throw new Api.ApiException(400,"模板业务类型无效");}
        long revision=number(m.get("template_revision"),false);String version=text(m.get("template_version"),96);
        if(!version.matches((kind==Kind.M03_DEMAND?"M03":"M08")+"-TPL-"+revision+"-[a-f0-9]{32}"))bad("模板版本标识无效");
        String digest=text(m.get("definition_hash"),64);Definition definition=parse(kind,m.get("definition"));if(!digest.equals(hash(canonical(json(definition)))))bad("模板摘要无效");
        String submitter=text(m.get("submitter_code"),120);long submitterAccount=number(m.get("submitter_account"),false);
        if(!(m.get("nodes") instanceof List<?> list)||list.isEmpty()||list.size()>MAX_EXTRA+2)bad("冻结节点数量无效");
        List<Node> nodes=new ArrayList<>();Set<String> ids=new HashSet<>(),people=new HashSet<>();Set<Long> accounts=new HashSet<>();int previous=-1,coreCount=0;Set<String> duties=new HashSet<>();boolean extraAfter=false;
        for(Object v:(List<?>)m.get("nodes")){
            Map<String,Object> n=object(v);keys(n,"node_id","title","review_role","person_code","account_id","resource","phase","responsibilities");
            String id=text(n.get("node_id"),38),title=text(n.get("title"),60),role=text(n.get("review_role"),16),person=text(n.get("person_code"),120),resource=text(n.get("resource"),64);long account=number(n.get("account_id"),false),rawPhase=number(n.get("phase"),true);if(rawPhase>MAX_EXTRA+1)bad("冻结节点阶段超出上限");int phase=(int)rawPhase;
            if(!(n.get("responsibilities") instanceof List<?> r)||r.stream().anyMatch(x->!(x instanceof String)))bad("节点职责无效");
            List<String> responsibilities=((List<?>)n.get("responsibilities")).stream().map(String.class::cast).toList();
            if(!ids.add(id)||!people.add(person)||!accounts.add(account)||person.equals(submitter)||account==submitterAccount||phase<previous||phase>previous+1)bad("冻结节点重复、自审或顺序无效");
            boolean extra=id.startsWith("extra-");
            if(extra){if(!id.matches("extra-[a-z0-9][a-z0-9-]{0,31}")||!Set.of("BRANCH","BP").contains(role)||!responsibilities.equals(List.of("EXTRA"))||!resource.equals(reviewResource(kind,role))||phase==previous)bad("补充节点冻结规则无效");if(coreCount>0)extraAfter=true;}
            else {if(extraAfter)bad("补充节点不能插入必需复核组");coreCount++;
                if(id.equals("required-combined")){if(!role.equals("COMBINED")||!responsibilities.equals(List.of("BRANCH","BP"))||!resource.equals(kind==Kind.M03_DEMAND?"approval.review":"summary.review.combined"))bad("兼任必需职责无效");duties.addAll(responsibilities);}
                else if(id.equals("required-branch")){if(!role.equals("BRANCH")||!responsibilities.equals(List.of("BRANCH"))||!resource.equals(reviewResource(kind,role)))bad("负责人节点无效");duties.add("BRANCH");}
                else if(id.equals("required-bp")){if(!role.equals("BP")||!responsibilities.equals(List.of("BP"))||!resource.equals(reviewResource(kind,role)))bad("BP节点无效");duties.add("BP");}
                else bad("不能创建或删除必需职责");
                if(phase==previous&&(kind==Kind.M03_DEMAND||nodes.isEmpty()||nodes.get(nodes.size()-1).extra()||!id.equals("required-bp")))bad("只有普通总结必需两方允许同阶段");
            }
            nodes.add(new Node(id,title,role,person,account,resource,phase,responsibilities));previous=phase;
        }
        if(!duties.equals(Set.of("BRANCH","BP"))||coreCount!=(ids.contains("required-combined")?1:2)||nodes.size()-coreCount>MAX_EXTRA)bad("必需职责缺失或重复");
        if(!ids.contains("required-combined")){int branch=-1,bp=-1;for(int i=0;i<nodes.size();i++){if(nodes.get(i).id.equals("required-branch"))branch=i;if(nodes.get(i).id.equals("required-bp"))bp=i;}if(bp!=branch+1)bad("必需两方顺序无效");}
        List<Node> expected=layout(definition,nodes.stream().filter(n->n.id.equals("required-combined")||n.id.equals("required-branch")).findFirst().orElseThrow(),nodes.stream().filter(n->n.id.equals("required-bp")).findFirst().orElse(null),nodes);
        if(!nodes.equals(expected))bad("冻结节点与发布定义不一致");
        return new Frozen(kind,text(m.get("organization_code"),120),version,revision,digest,definition,text(m.get("config_version"),128),submitter,submitterAccount,nodes);
    }
    private static List<Node> layout(Definition d,Node branch,Node bp,List<Node> candidates)throws Api.ApiException {
        List<Node> out=new ArrayList<>();int phase=0;
        for(Extra x:d.before){out.add(extraNode(d.kind,x,phase++,candidates));}
        boolean combined=branch.id.equals("required-combined");out.add(new Node(branch.id,combined?"负责人及BP（兼任）":"负责人",combined?"COMBINED":"BRANCH",branch.person,branch.account,combined?(d.kind==Kind.M03_DEMAND?"approval.review":"summary.review.combined"):reviewResource(d.kind,"BRANCH"),phase,combined?List.of("BRANCH","BP"):List.of("BRANCH")));
        if(!combined){if(bp==null)bad("缺少BP节点");if(d.order.equals("BRANCH_THEN_BP"))phase++;out.add(new Node("required-bp","BP","BP",bp.person,bp.account,reviewResource(d.kind,"BP"),phase,List.of("BP")));}
        phase++;for(Extra x:d.after){out.add(extraNode(d.kind,x,phase++,candidates));}return List.copyOf(out);
    }
    private static Node extraNode(Kind kind,Extra x,int phase,List<Node> candidates)throws Api.ApiException {
        Node assigned=candidates.stream().filter(n->n.id.equals(x.id)).findFirst().orElse(null);if(assigned==null||!assigned.person.equals(x.person))bad("补充节点与指定人员不符");
        return new Node(x.id,x.title,x.role,x.person,assigned.account,reviewResource(kind,x.role),phase,List.of("EXTRA"));
    }
    /** Accounts must already be resolved from the current authorized M01 configuration by the host. */
    public static Frozen freeze(Definition definition,String org,String version,long revision,String config,String submitter,long submitterAccount,String branch,long branchAccount,String bp,long bpAccount,boolean combined,Map<String,Long> extraAccounts)throws Api.ApiException {
        if(combined!=branch.equals(bp)||combined&&branchAccount!=bpAccount)bad("兼任冻结人员不一致");
        List<Node> candidates=new ArrayList<>();for(Extra x:definition.extras()){Long account=extraAccounts.get(x.person);if(account==null)bad("补充人员账号缺失");candidates.add(new Node(x.id,x.title,x.role,x.person,account,reviewResource(definition.kind,x.role),0,List.of("EXTRA")));}
        Node a=new Node(combined?"required-combined":"required-branch","",combined?"COMBINED":"BRANCH",branch,branchAccount,"",0,List.of()),b=combined?null:new Node("required-bp","","BP",bp,bpAccount,"",0,List.of());
        List<Node> nodes=layout(definition,a,b,candidates);Frozen frozen=new Frozen(definition.kind,org,version,revision,hash(canonical(json(definition))),definition,config,submitter,submitterAccount,nodes);return decode(frozen.json());
    }
    @SuppressWarnings("unchecked") public static Map<String,Object> object(Object v)throws Api.ApiException {if(!(v instanceof Map<?,?>))bad("数据须为对象");return (Map<String,Object>)v;}
    public static void keys(Map<String,Object> m,String... fields)throws Api.ApiException {if(!m.keySet().equals(Set.of(fields)))bad("字段缺失或包含不支持的内容");}
    public static String text(Object raw,int max)throws Api.ApiException {if(!(raw instanceof String s)||s.isBlank()||!s.equals(s.trim())||s.length()>max||s.codePoints().anyMatch(c->Character.isISOControl(c)))bad("文字格式或长度无效");return (String)raw;}
    public static long number(Object raw,boolean zero)throws Api.ApiException {try{if(!(raw instanceof Number))throw new IllegalArgumentException();long n=new BigDecimal(raw.toString()).longValueExact();if(n<(zero?0:1)||n>9007199254740991L)throw new IllegalArgumentException();return n;}catch(RuntimeException e){throw new Api.ApiException(400,"编号或版本须为安全整数");}}
    private static void bad(String message)throws Api.ApiException {throw new Api.ApiException(400,message);}
}
