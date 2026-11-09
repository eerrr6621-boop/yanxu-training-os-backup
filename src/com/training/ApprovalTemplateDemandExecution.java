package com.training;

import java.time.Instant;
import java.util.*;
import static com.training.ApprovalWorkflow.*;
import static com.training.ApprovalTemplateDefinition.*;

/** M03's frozen linear template execution. Rebuild every round from business events, never today's template. */
public final class ApprovalTemplateDemandExecution {
    private ApprovalTemplateDemandExecution() {}
    private record Proof(Status status,long revision,int round,Set<String> approvals,Stage returned,String target) {}
    private static void need(boolean ok,String code,String message){if(!ok)throw new Failure(code,message);}
    private static void valid(boolean ok,String message){need(ok,"INVALID_STATE",message);}
    public static Policy attach(Policy original,Frozen frozen) {
        Set<Status> withdrawal=new HashSet<>(original.withdrawableStatuses());withdrawal.add(Status.EXTRA_PENDING);
        return new Policy(frozen.templateVersion(),original.leaderReturnTo(),original.bpReturnTo(),original.resubmitFrom(),original.selfApproval(),original.combinedRoles(),original.delegation(),original.inactiveAssigneeDelegation(),original.withdrawalConfigured(),withdrawal,original.documentChanges(),original.combinedAssignment(),frozen);
    }
    private static void binding(Participants participants,Policy policy) {
        Frozen f=policy.template();valid(f!=null,"缺少冻结模板");
        try{valid(ApprovalTemplateDefinition.decode(f.json()).equals(f),"冻结模板结构不一致");}catch(Api.ApiException bad){throw new Failure("INVALID_STATE","冻结模板结构无效");}
        valid(f.kind()==Kind.M03_DEMAND&&f.definition().order().equals("BRANCH_THEN_BP")&&f.submitter().equals(participants.submitterId())&&f.templateVersion().equals(policy.version()),"冻结模板业务或填报人不符");
        boolean combined=policy.reviewMode()==ReviewMode.SINGLE_EXPLICIT;
        Node branch=f.node(combined?"required-combined":"required-branch"),bp=f.node("required-bp");
        valid(branch!=null&&branch.person().equals(participants.leaderId())&&(combined?bp==null&&participants.leaderId().equals(participants.bpId()):bp!=null&&bp.person().equals(participants.bpId())&&f.node("required-combined")==null),"冻结必需人员与业务参与者不符");
        if(combined)valid(policy.combinedAssignment().organizationCode().equals(f.organization())&&policy.combinedAssignment().directoryVersion().equals(f.configVersion()),"冻结兼任来源不符");
        valid(policy.selfApproval()==Permission.DENY&&policy.delegation()==Permission.DENY&&policy.inactiveAssigneeDelegation()==Permission.DENY&&policy.leaderReturnTo()==ReturnTo.SUBMITTER&&policy.bpReturnTo()==ReturnTo.SUBMITTER&&policy.resubmitFrom()==Restart.FROM_LEADER&&policy.documentChanges()==ChangeRule.RESTART_FROM_LEADER&&policy.withdrawalConfigured()&&policy.withdrawableStatuses().contains(Status.EXTRA_PENDING),"模板实例规则不符");
    }
    private static Node pending(Frozen f,Set<String> done){List<Node> nodes=f.pending(done);valid(nodes.size()<=1,"需求模板必须逐节点执行");return nodes.isEmpty()?null:nodes.get(0);}
    private static Stage stage(Node node){return node==null?Stage.NONE:node.extra()?Stage.EXTRA:node.role().equals("BP")?Stage.BP:Stage.LEADER;}
    private static Status status(Node node){return node==null?Status.READY_FOR_TEAM:switch(stage(node)){case LEADER->Status.LEADER_PENDING;case BP->Status.BP_PENDING;case EXTRA->Status.EXTRA_PENDING;case NONE->throw new Failure("INVALID_STATE","节点无效");};}
    private static boolean leader(Proof p){return p.approvals.contains("required-branch")||p.approvals.contains("required-combined");}
    private static boolean bp(Proof p){return p.approvals.contains("required-bp")||p.approvals.contains("required-combined");}
    private static Proof fold(Participants participants,Policy policy,List<Event> events) {
        binding(participants,policy);Frozen f=policy.template();valid(!events.isEmpty(),"模板事件缺失");
        Set<String> done=new LinkedHashSet<>(),requests=new HashSet<>();Status current=null;long revision=0;int round=1;Stage returned=Stage.NONE;String target="";Instant time=null;
        for(int i=0;i<events.size();i++){
            Event e=events.get(i);valid(e.version()==i+1&&requests.add(e.requestId())&&e.from()==current&&e.onBehalfOf().isEmpty()&&e.dataRevision()>0&&e.dataRevision()>=revision&&(time==null||!e.at().isBefore(time)),"模板事件版本、人员代理或时间不符");
            valid(e.stage()==(current==null?Stage.NONE:ApprovalWorkflow.stageFor(current)),"模板事件阶段不符");
            Node node=current!=null&&ApprovalWorkflow.stageFor(current)!=Stage.NONE?pending(f,done):null;
            boolean review=Set.of(Action.APPROVE,Action.APPROVE_COMBINED,Action.RETURN).contains(e.action());
            if(review)valid(node!=null&&node.id().equals(e.nodeId())&&node.person().equals(e.actorId())&&e.dataRevision()==revision,"模板办理节点、人员或正文不符");
            else valid(e.nodeId().isEmpty()&&e.actorId().equals(participants.submitterId()),"模板填报人事件不符");
            switch(e.action()){
                case SUBMIT->{valid(i==0,"模板不能再次首次提交");current=status(pending(f,done));}
                case APPROVE,APPROVE_COMBINED->{valid(i>0&&(node.role().equals("COMBINED")?e.action()==Action.APPROVE_COMBINED:e.action()==Action.APPROVE),"模板批准动作与真实职责不符");done.add(node.id());current=status(pending(f,done));returned=Stage.NONE;target="";}
                case RETURN->{valid(i>0&&!e.comment().isBlank(),"模板退回须有原因");returned=stage(node);target=participants.submitterId();done.clear();current=Status.RETURNED;}
                case WITHDRAW->{valid(i>0&&policy.withdrawableStatuses().contains(current)&&e.dataRevision()==revision,"模板撤回记录无效");done.clear();current=Status.WITHDRAWN;returned=Stage.NONE;target="";}
                case RESUBMIT->{valid(i>0&&(current==Status.RETURNED||current==Status.WITHDRAWN),"模板重提状态无效");done.clear();current=status(pending(f,done));round++;returned=Stage.NONE;target="";}
                case REVISE->{valid(i>0&&node!=null&&e.dataRevision()>revision&&!e.comment().isBlank(),"模板改稿须有新正文和原因");done.clear();current=status(pending(f,done));round++;returned=Stage.NONE;target="";}
            }
            valid(e.to()==current,"模板目标节点与全部审批证据不符");revision=e.dataRevision();time=e.at();
        }
        return new Proof(current,revision,round,Set.copyOf(done),returned,target);
    }
    static void validate(Participants participants,Policy policy,Status status,long version,long revision,int round,boolean leader,boolean bp,Stage returned,String target,List<Event> events){
        Proof p=fold(participants,policy,events);valid(version==events.size()&&p.status==status&&p.revision==revision&&p.round==round&&leader(p)==leader&&bp(p)==bp&&p.returned==returned&&p.target.equals(target),"模板快照与事件恢复不一致");
    }
    public static Node current(State state){if(state.stage()==Stage.NONE)return null;return pending(state.policy().template(),fold(state.participants(),state.policy(),state.history()).approvals);}
    public static boolean complete(State state){Proof p=fold(state.participants(),state.policy(),state.history());return p.status==Status.READY_FOR_TEAM&&state.policy().template().complete(p.approvals);}
    public static State submit(long id,long business,String title,Participants participants,Policy policy,long revision,String request,Instant at,String org){
        binding(participants,policy);need(policy.template().organization().equals(org),"FORBIDDEN","冻结模板不覆盖当前机构");Status first=status(pending(policy.template(),Set.of()));
        Event event=new Event(1,request,Action.SUBMIT,participants.submitterId(),"",null,first,Stage.NONE,"",revision,at,"");return build(id,business,title,participants,policy,List.of(event));
    }
    public static State apply(State state,Command c,Access access){
        Proof p=fold(state.participants(),state.policy(),state.history());Frozen f=state.policy().template();Set<String> done=new HashSet<>(p.approvals);Node node=state.stage()==Stage.NONE?null:pending(f,done);
        boolean review=Set.of(Action.APPROVE,Action.APPROVE_COMBINED,Action.RETURN).contains(c.action());
        String expected=review?(node==null?"":node.person()):state.participants().submitterId();need(access.actorId().equals(expected),"FORBIDDEN","当前用户不是冻结的节点办理人");need(access.activity().getOrDefault(access.actorId(),Activity.UNKNOWN)==Activity.ACTIVE,"FORBIDDEN","当前人员未启用");
        need(review?node!=null&&c.nodeId().equals(node.id()):c.nodeId().isEmpty(),"STALE_STAGE","审批节点已变化，请刷新后办理");Status next;
        switch(c.action()){
            case APPROVE,APPROVE_COMBINED->{need(node!=null,"INVALID_TRANSITION","当前没有审批节点");need(node.role().equals("COMBINED")?c.action()==Action.APPROVE_COMBINED:c.action()==Action.APPROVE,"INVALID_TRANSITION","请按本节点的实际职责办理");done.add(node.id());next=status(pending(f,done));}
            case RETURN->{need(!c.comment().isBlank(),"COMMENT_REQUIRED","请填写退回原因");next=Status.RETURNED;}
            case WITHDRAW->{need(state.policy().withdrawableStatuses().contains(state.status()),"INVALID_TRANSITION","当前阶段不可撤回");next=Status.WITHDRAWN;}
            case RESUBMIT->{need(state.status()==Status.RETURNED||state.status()==Status.WITHDRAWN,"INVALID_TRANSITION","仅退回或撤回后可重提");need(c.dataRevision()>=state.dataRevision(),"INVALID_INPUT","正文版本不可回退");next=status(pending(f,Set.of()));}
            case REVISE->{need(node!=null&&c.dataRevision()>state.dataRevision(),"INVALID_TRANSITION","改稿须有新版本且流程仍待审");need(!c.comment().isBlank(),"COMMENT_REQUIRED","请填写变更原因");next=status(pending(f,Set.of()));}
            default->throw new Failure("INVALID_TRANSITION","模板流程不能重复首次提交");
        }
        List<Event> events=new ArrayList<>(state.history());events.add(new Event(state.version()+1,c.requestId(),c.action(),access.actorId(),"",state.status(),next,state.stage(),c.comment(),c.dataRevision(),c.at(),c.nodeId()));return build(state.id(),state.businessId(),state.title(),state.participants(),state.policy(),events);
    }
    private static State build(long id,long business,String title,Participants participants,Policy policy,List<Event> events){Proof p=fold(participants,policy,events);return new State(id,business,title,participants,policy,p.status,events.size(),p.revision,p.round,leader(p),bp(p),p.returned,p.target,events);}
    public static State prefix(State state,long version){valid(version>=1&&version<=state.version(),"通知版本无效");return build(state.id(),state.businessId(),state.title(),state.participants(),state.policy(),state.history().subList(0,(int)version));}
    public static Map<String,Object> view(State state){Proof p=fold(state.participants(),state.policy(),state.history());Node current=state.stage()==Stage.NONE?null:pending(state.policy().template(),p.approvals);Frozen f=state.policy().template();List<Map<String,Object>> nodes=new ArrayList<>();for(Node n:f.nodes()){Map<String,Object> row=new LinkedHashMap<>();row.put("node_id",n.id());row.put("title",n.title());row.put("person_code",n.person());row.put("phase",n.phase());row.put("required",!n.extra());row.put("responsibilities",n.responsibilities());row.put("status",p.approvals.contains(n.id())?"APPROVED":current!=null&&current.id().equals(n.id())?"CURRENT":state.status()==Status.RETURNED||state.status()==Status.WITHDRAWN?"INVALIDATED":"WAITING");nodes.add(row);}Map<String,Object> result=new LinkedHashMap<>();result.put("format","APPROVAL-TEMPLATE-VIEW-1");result.put("business_kind",f.kind().name());result.put("organization_code",f.organization());result.put("template_version",f.templateVersion());result.put("template_revision",f.revision());result.put("definition_hash",f.definitionHash());result.put("data_revision",state.dataRevision());result.put("round",state.round());result.put("nodes",nodes);result.put("current_node_id",current==null?null:current.id());return result;}
}
