/* Read-only projection of already-authorized M03/M08 views. No reads, writes or directory lookup. */
(function (root) {
  'use strict';
  const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const label = {done:'已完成',current:'当前节点',waiting:'待办理',returned:'待修改',blocked:'已暂停',inactive:'不再办理'};
  const node = (title, state='waiting', person='') => ({title,state,person});
  function diagram(title, nodes, {notice='', meta='', parallel=false}={}) {
    return `<section class="approval-visual" aria-label="${esc(title)}" data-approval-visual><div class="approval-visual-heading"><h4>${esc(title)}</h4><span class="approval-visual-readonly">流程查看</span></div>${notice ? `<p class="approval-visual-notice" role="status">${esc(notice)}</p>` : ''}<ol class="approval-visual-nodes${parallel?' approval-visual-parallel':''}">${nodes.map(n=>`<li class="approval-visual-node is-${esc(n.state)}"${['current','returned','blocked'].includes(n.state)?' aria-current="step"':''}><span class="approval-visual-dot" aria-hidden="true"></span><div><strong>${esc(n.title)}</strong><span class="approval-visual-status">${esc(label[n.state]||'待核实')}</span>${n.person?`<small>${esc(n.person)}</small>`:''}</div></li>`).join('')}</ol>${meta?`<p class="approval-visual-meta">${esc(meta)}</p>`:''}</section>`;
  }
  const unavailable = title => diagram(title,[],{notice:'当前流程信息不完整，请重新打开核对。'});
  const exact=(v,keys)=>v&&typeof v==='object'&&!Array.isArray(v)&&Object.keys(v).length===keys.length&&keys.every(k=>Object.hasOwn(v,k));
  const positive=v=>Number.isSafeInteger(v)&&v>0;
  function validTemplate(t,kind,flow){
    const summary=kind==='M08_SUMMARY',keys=['format','business_kind','organization_code','template_version','template_revision','definition_hash','nodes',...(summary?['core_order','submitted_revision','current','current_node_ids']:['data_revision','round','current_node_id'])];
    if(!exact(t,keys)||t.format!=='APPROVAL-TEMPLATE-VIEW-1'||t.business_kind!==kind||typeof t.organization_code!=='string'||!t.organization_code||!positive(t.template_revision)||!new RegExp('^'+(summary?'M08':'M03')+'-TPL-'+t.template_revision+'-[a-f0-9]{32}$').test(t.template_version)||!/^([a-f0-9]{64})$/.test(t.definition_hash)||!Array.isArray(t.nodes)||t.nodes.length<1||t.nodes.length>6)return false;
    const ids=new Set(),people=new Set();let previous=-1;for(const n of t.nodes){const extra=typeof n?.node_id==='string'&&/^extra-[a-z0-9][a-z0-9-]{0,31}$/.test(n.node_id);const duties=extra?['EXTRA']:n?.node_id==='required-combined'?['BRANCH','BP']:n?.node_id==='required-branch'?['BRANCH']:n?.node_id==='required-bp'?['BP']:null;
      if(!exact(n,['node_id','title','person_code','phase','required','responsibilities','status',...(summary?['review_role','permission_role']:[])])||!duties||typeof n.title!=='string'||!n.title.trim()||n.title.length>60||typeof n.person_code!=='string'||!n.person_code||ids.has(n.node_id)||people.has(n.person_code)||!Number.isSafeInteger(n.phase)||n.phase<0||n.phase>5||n.phase<previous||n.phase>previous+1||n.required===extra||typeof n.required!=='boolean'||JSON.stringify(n.responsibilities)!==JSON.stringify(duties)||!['APPROVED','CURRENT','WAITING','RETURNED','INVALIDATED','HISTORICAL'].includes(n.status))return false;
      if(summary&&(n.review_role!==(extra?'EXTRA':n.node_id==='required-combined'?'COMBINED':duties[0])||!(extra?['BRANCH','BP'].includes(n.permission_role):n.permission_role===n.review_role)))return false;
      if(n.phase===previous&&(extra||n.node_id!=='required-bp'||!summary||t.core_order!=='UNORDERED'))return false;
      previous=n.phase;ids.add(n.node_id);people.add(n.person_code);
    }
    const combined=ids.has('required-combined'),core=t.nodes.filter(n=>n.required);if(combined?core.length!==1:core.length!==2||core[0].node_id!=='required-branch'||core[1].node_id!=='required-bp'||t.nodes.indexOf(core[1])!==t.nodes.indexOf(core[0])+1)return false;
    if(t.nodes.filter(n=>!n.required).length>4||combined!==(flow.review_mode==='SINGLE_EXPLICIT'||flow.reviewMode==='SINGLE_EXPLICIT'))return false;
    const pending=t.nodes.filter(n=>n.status==='CURRENT').map(n=>n.node_id);
    if(summary){if(!['DUAL_REVIEW','SINGLE_EXPLICIT'].includes(flow.review_mode)||combined&&flow.review_order!=='COMBINED'||!combined&&flow.review_order!==t.core_order||!['BRANCH_THEN_BP','UNORDERED'].includes(t.core_order)||t.submitted_revision!==flow.submitted_revision||t.current!==(flow.submitted_revision===flow.revision)||!Array.isArray(t.current_node_ids)||JSON.stringify(t.current_node_ids)!==JSON.stringify(pending)||!t.current&&t.nodes.some(n=>n.status!=='HISTORICAL'))return false;}
    else if(t.data_revision!==flow.dataRevision||t.round!==flow.round||t.current_node_id!==(pending[0]??null)||pending.length>1)return false;
    const approved=summary?flow.status==='APPROVED':flow.status==='READY_FOR_TEAM';if(approved&&t.nodes.some(n=>n.status!=='APPROVED'))return false;
    if(['RETURNED','WITHDRAWN'].includes(flow.status)&&t.nodes.some(n=>['APPROVED','CURRENT'].includes(n.status)))return false;
    return true;
  }
  function templateDiagram(t,flow,{summary=false,person=code=>code,accepted=false,blocked=''}={}){
    if(!validTemplate(t,summary?'M08_SUMMARY':'M03_DEMAND',flow))return unavailable(summary?'总结复核进度':'需求办理进度');
    const returned=['RETURNED','WITHDRAWN'].includes(flow.status),done=summary?flow.status==='APPROVED':flow.status==='READY_FOR_TEAM';
    const nodes=[node('填写与提交',returned?'returned':'done',person(summary?flow.submitter_code:flow.participants.submitterId))];
    for(const n of t.nodes){const peers=t.nodes.filter(x=>x.phase===n.phase).length>1;nodes.push(node(`第 ${n.phase+1} 阶段 · ${n.title}${peers?'（同阶段并列）':''}`,({APPROVED:'done',CURRENT:blocked?'blocked':'current',WAITING:'waiting',RETURNED:'returned',INVALIDATED:'inactive',HISTORICAL:'inactive'})[n.status],person(n.person_code)));}
    nodes.push(node(summary?'全部复核完成':'团队受理',summary?done?'done':'waiting':accepted?'done':done?'current':'waiting'));
    return diagram(summary?'总结复核进度':'需求办理进度',nodes,{notice:blocked||(returned?(summary?'已退回，保存新修订后重新提交；提交时重新确认模板与人员。':'已退回或撤回，下一轮从本实例冻结流程首节点重新办理。'):done?'全部必需及补充节点已完成。':'按已冻结的节点顺序办理；同阶段并列的两方均须通过。'),meta:`模板 ${t.template_version} · ${summary?'本次提交正文':'本需求实例'}冻结办理人员与节点；操作仍实时核验当前权限。`});
  }
  function renderDemand(w, personLabel=code=>code||'待确定') {
    if (!w || !Number.isSafeInteger(w.id) || w.id<1) return unavailable('需求办理进度');
    const person=code=>code?personLabel(code):'待确定';
    if(w.business_path==='bid') {
      const accepted=w.queue==='accepted'&&Number.isSafeInteger(w.project_id)&&w.project_id>0;
      const won=w.bid_result?.result==='won',lost=w.bid_result?.result==='lost';
      const nodes=[node('填写需求',w.draft?'current':'done',person(w.filler_code)),node('登记投标结果',w.draft?'waiting':won||lost?'done':'current'),node('团队受理',accepted?'done':lost?'inactive':won?'current':'waiting')];
      return diagram('需求办理进度',nodes,{notice:lost?'未中标，需求留档。':'按原办公签报办理，系统记录投标结果及团队受理。',meta:accepted?'已形成项目，交付在项目工作区继续。':''});
    }
    if(w.business_path!=='direct') return unavailable('需求办理进度');
    const a=w.approval;
    if(!a) return diagram('需求办理进度',[node('填写与提交',w.draft?'current':'waiting',person(w.filler_code))],{notice:w.draft?'尚未提交；审批人员和流程以提交时确认的信息为准。':'尚无可核对的审批实例。'});
    if(!['LEADER_PENDING','BP_PENDING','EXTRA_PENDING','RETURNED','WITHDRAWN','READY_FOR_TEAM'].includes(a.status)||!a.participants||!['SEQUENTIAL','SINGLE_EXPLICIT'].includes(a.reviewMode))return unavailable('需求办理进度');
    const p=a.participants,combined=a.reviewMode==='SINGLE_EXPLICIT',accepted=w.queue==='accepted'&&Number.isSafeInteger(w.project_id)&&w.project_id>0;
    if(a.approvalTemplate)return templateDiagram(a.approvalTemplate,a,{person,accepted,blocked:w.approval_blocked_reason||a.blocked_reason||''});
    if(combined&&a.status==='BP_PENDING')return unavailable('需求办理进度');
    const ready=a.status==='READY_FOR_TEAM',returned=a.status==='RETURNED',withdrawn=a.status==='WITHDRAWN';
    const editing=withdrawn||returned&&a.assigneeId===p.submitterId;
    const nodes=[node('填写与提交',editing?'returned':'done',person(p.submitterId))];
    nodes.push(node(combined?'负责人及 BP（兼任）':'负责人审批',ready||a.status==='BP_PENDING'?'done':a.status==='LEADER_PENDING'?'current':returned&&a.assigneeId===p.leaderId?'returned':'waiting',person(p.leaderId)));
    if(!combined)nodes.push(node('BP 审批',ready?'done':a.status==='BP_PENDING'?'current':returned&&a.assigneeId===p.bpId?'returned':'waiting',person(p.bpId)));
    nodes.push(node('团队受理',accepted?'done':ready?'current':'waiting'));
    const blocked=w.approval_blocked_reason||a.blocked_reason||'';
    if(a.paused||blocked)for(const n of nodes)if(['current','returned'].includes(n.state))n.state='blocked';
    const notice=blocked|| (accepted?'已受理并形成项目。':ready?'审批已完成，等待团队受理。':returned?'已退回，请结合下方历史查看修改意见和当前办理人。':withdrawn?'需求已撤回，当前没有待审批节点。':combined?'同一办理人明确完成负责人和 BP 两项职责。':'');
    return diagram('需求办理进度',nodes,{notice,meta:`第 ${a.round??'待核实'} 轮 · 需求第 ${a.dataRevision??'待核实'} 版 · 审批第 ${a.version??'待核实'} 版。办理人按本轮提交记录显示，可办操作以当前权限为准。`});
  }
  function renderSummary(flow) {
    if(!flow||!['DRAFT','SUBMITTED','RETURNED','APPROVED'].includes(flow.status))return unavailable('总结复核进度');
    const current=flow.submitted_revision===flow.revision,combined=flow.review_mode==='SINGLE_EXPLICIT',parallel=flow.review_order==='UNORDERED';
    if(!current)return diagram('总结复核进度',[node('填写与提交','current'),node('提交后确认复核流程')],{notice:(flow.submitted_revision!==null?'当前正文尚未提交复核，历史批准不适用于本版。':'尚未提交复核；办理人和复核顺序在提交时确定。')+(!flow.policy_configured?' 新提交复核规则尚未配置，可先保存正文。':''),meta:`正文第 ${flow.revision??'待核实'} 版 · 流程第 ${flow.workflow_version??'待核实'} 版`});
    if(flow.approval_template)return templateDiagram(flow.approval_template,flow,{summary:true,blocked:flow.review_blocked_reason||''});
    if(!(combined&&flow.review_order==='COMBINED')&&!(flow.review_mode==='DUAL_REVIEW'&&['BRANCH_THEN_BP','UNORDERED'].includes(flow.review_order)))return unavailable('总结复核进度');
    const submitted=current&&flow.status==='SUBMITTED',approved=current&&flow.status==='APPROVED',returned=current&&flow.status==='RETURNED';
    const decisions=(submitted||approved)&&Array.isArray(flow.decisions)?flow.decisions:[];
    const passed=role=>decisions.some(d=>d.review_role===role);
    const actor=code=>code||'提交后确定';
    const nodes=[node('填写与提交',submitted||approved?'done':returned?'returned':'current',current?actor(flow.submitter_code):'当前正文填写人')];
    if(combined)nodes.push(node('负责人及 BP（兼任）',approved||passed('COMBINED')?'done':submitted?'current':'waiting',current?actor(flow.branch_reviewer_code):'提交后确定'));
    else {
      nodes.push(node('负责人复核',approved||passed('BRANCH')?'done':submitted?'current':'waiting',current?actor(flow.branch_reviewer_code):'提交后确定'));
      nodes.push(node('BP 复核',approved||passed('BP')?'done':submitted&&(parallel||passed('BRANCH'))?'current':'waiting',current?actor(flow.bp_reviewer_code):'提交后确定'));
    }
    nodes.push(node('复核完成',approved?'done':'waiting'));
    if(flow.review_blocked_reason)for(const n of nodes)if(n.state==='current')n.state='blocked';
    const notice=flow.review_blocked_reason||(!current&&flow.submitted_revision!==null?'当前正文尚未提交复核，历史批准不适用于本版。':returned?'已退回，保存新修订后重新提交复核。':combined?'同一人员一次明确完成两项复核职责。':parallel?'负责人和 BP 可分别复核，无先后顺序。':'负责人通过后由 BP 复核。');
    return diagram('总结复核进度',nodes,{notice,parallel,meta:`正文第 ${flow.revision??'待核实'} 版 · 流程第 ${flow.workflow_version??'待核实'} 版。办理人按本版提交记录显示，可办操作以当前权限为准。`});
  }
  root.YanxuApprovalVisual=Object.freeze({renderDemand,renderSummary,validTemplate});
})(globalThis);
