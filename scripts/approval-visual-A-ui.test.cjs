'use strict';
const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm'),assert=require('node:assert/strict');
const repo=path.resolve(__dirname,'..'),source=fs.readFileSync(path.join(repo,'web/modules/approval-visual/index.js'),'utf8');
const domSource=fs.readFileSync(path.join(__dirname,'M01-account-bindings-check.mjs'),'utf8');
const {NodeStub}=vm.runInNewContext(domSource.slice(domSource.indexOf('const decode ='),domSource.indexOf('\nfunction fixture()'))+'\n({NodeStub});',{});
let requests=0;const sandbox={fetch:()=>{requests++;throw Error('Read-only visual must not issue a request');}};vm.runInNewContext(source,sandbox);
const {renderDemand,renderSummary}=sandbox.YanxuApprovalVisual,results=[];
const check=(condition,label)=>{results.push({label,passed:!!condition});assert.ok(condition,label);};
const parse=html=>{const root=new NodeStub();root.innerHTML=html;return {root,html,nodes:root.querySelectorAll('.approval-visual-node')};};
const states=view=>view.nodes.map(n=>n.getAttribute('class').match(/is-(\w+)/)[1]);
const same=(actual,expected,label)=>check(JSON.stringify(actual)===JSON.stringify(expected),label);
const a={status:'LEADER_PENDING',stage:'LEADER',participants:{submitterId:'P1',leaderId:'P2',bpId:'P3'},assigneeId:'P2',reviewMode:'SEQUENTIAL',version:1,dataRevision:1,round:1,paused:false,history:[]};
const demand=(approval=a,extra={})=>({id:1,business_path:'direct',filler_code:'P1',draft:false,queue:'waiting',project_id:null,approval:structuredClone(approval),...extra});
const summary=(extra={})=>({revision:1,submitted_revision:1,workflow_version:1,status:'SUBMITTED',review_mode:'DUAL_REVIEW',review_order:'BRANCH_THEN_BP',submitter_code:'P1',branch_reviewer_code:'P2',bp_reviewer_code:'P3',decisions:[],policy_configured:true,capabilities:{review:false,edit:false},...extra});
try {
  let v=parse(renderDemand(demand()));same(states(v),['done','current','waiting','waiting'],'M03 leader pending; acceptance is a separate future node');
  check(v.html.includes('P2')&&v.html.includes('提交记录')&&v.html.includes('当前权限'),'frozen participants and live permission distinction is explicit');
  v=parse(renderDemand(demand({...a,status:'BP_PENDING',assigneeId:'P3'})));same(states(v),['done','done','current','waiting'],'M03 BP stage reflects actual server state');
  v=parse(renderDemand(demand({...a,status:'READY_FOR_TEAM',assigneeId:''},{queue:'ready'})));same(states(v),['done','done','done','current'],'approval ready does not invent acceptance');
  v=parse(renderDemand(demand({...a,status:'READY_FOR_TEAM'},{queue:'accepted',project_id:9})));same(states(v),['done','done','done','done'],'acceptance needs real accepted queue and project');
  v=parse(renderDemand(demand({...a,status:'READY_FOR_TEAM'},{queue:'accepted',project_id:null})));check(states(v).at(-1)!=='done','incomplete acceptance projection is not shown completed');
  v=parse(renderDemand(demand({...a,status:'RETURNED',assigneeId:'P1',history:[{action:'APPROVE',stage:'LEADER'}]})));same(states(v),['returned','waiting','waiting','waiting'],'old approvals never color a returned new round');
  v=parse(renderDemand(demand({...a,status:'RETURNED',assigneeId:'P2'})));same(states(v),['done','returned','waiting','waiting'],'return target can be the frozen leader');
  v=parse(renderDemand(demand({...a,status:'WITHDRAWN',assigneeId:'P1'})));check(v.html.includes('已撤回')&&!states(v).includes('current'),'withdrawn flow has no active approval node');
  v=parse(renderDemand(demand({...a,paused:true,blocked_reason:'合成职责已变化'})));same(states(v),['done','blocked','waiting','waiting'],'paused flow is visibly paused, not authorized by visual');
  v=parse(renderDemand(demand({...a,reviewMode:'SINGLE_EXPLICIT',participants:{submitterId:'P1',leaderId:'P2',bpId:'P2'}})));check(v.nodes.length===3&&v.html.includes('负责人及 BP（兼任）'),'explicit combined duty is one node, not two people');
  v=parse(renderDemand(demand(null,{draft:true})));check(v.nodes.length===1&&!v.html.includes('负责人审批'),'unsubmitted demand does not invent frozen reviewers');
  v=parse(renderDemand(demand(null,{business_path:'bid',queue:'waiting_bid_result'})));same(states(v),['done','current','waiting'],'bid path uses actual result registration, no internal approval fiction');
  v=parse(renderDemand(demand(null,{business_path:'bid',queue:'archived_lost',bid_result:{result:'lost'}})));check(states(v).at(-1)==='inactive'&&v.html.includes('未中标'),'lost bid never becomes acceptance');
  v=parse(renderDemand(demand({...a,status:'UNKNOWN'})));check(v.nodes.length===0&&v.html.includes('重新打开'),'unknown demand status fails closed visually');
  v=parse(renderSummary(summary()));same(states(v),['done','current','waiting','waiting'],'sequential summary starts at branch');
  v=parse(renderSummary(summary({status:'APPROVED',policy_configured:false,decisions:[{review_role:'BRANCH'},{review_role:'BP'}]})));check(states(v).every(s=>s==='done')&&v.html.includes('负责人通过后由 BP 复核')&&!v.html.includes('可先保存正文'),'approved frozen order survives unavailable new-submission configuration');
  v=parse(renderSummary(summary({policy_configured:false})));same(states(v),['done','current','waiting','waiting'],'submitted frozen order survives unavailable new-submission configuration');check(v.html.includes('负责人通过后由 BP 复核')&&!v.html.includes('可先保存正文'),'submitted flow description uses its frozen route');
  v=parse(renderSummary(summary({decisions:[{review_role:'BRANCH'}]})));same(states(v),['done','done','current','waiting'],'BP begins after current branch decision');
  v=parse(renderSummary(summary({review_order:'UNORDERED'})));same(states(v),['done','current','current','waiting'],'unordered reviewers are simultaneous, not an invented sequential edge');check(v.html.includes('无先后顺序'),'parallel order is explained');
  v=parse(renderSummary(summary({review_order:'UNORDERED',decisions:[{review_role:'BP'}]})));same(states(v),['done','current','done','waiting'],'BP can finish first in unordered mode');
  v=parse(renderSummary(summary({review_mode:'SINGLE_EXPLICIT',review_order:'COMBINED',bp_reviewer_code:'P2'})));check(v.nodes.length===3&&states(v)[1]==='current','summary explicit combined is one node with two responsibilities');
  v=parse(renderSummary(summary({status:'RETURNED',decisions:[{review_role:'BRANCH'}]})));same(states(v),['returned','waiting','waiting','waiting'],'returned summary cannot reuse earlier partial approvals');
  v=parse(renderSummary(summary({status:'APPROVED',decisions:[{review_role:'BRANCH'},{review_role:'BP'}]})));check(states(v).every(s=>s==='done'),'current approved summary is complete');
  for(const mode of ['SINGLE_EXPLICIT','DUAL_REVIEW']){
    v=parse(renderSummary(summary({revision:2,submitted_revision:1,status:'DRAFT',review_mode:mode,review_order:mode==='SINGLE_EXPLICIT'?'COMBINED':'UNORDERED',decisions:[{review_role:mode==='SINGLE_EXPLICIT'?'COMBINED':'BRANCH'}]})));
    same(states(v),['current','waiting'],mode+' old approval after new draft/relationship change does not predict next routing');check(!v.html.includes('P2')&&!v.html.includes('P3')&&!v.html.includes('兼任'),'new draft does not reuse prior frozen people or mode');check(v.html.includes('历史批准不适用于本版'),'new draft explicitly separates historical approval');
  }
  v=parse(renderSummary(summary({status:'DRAFT',revision:0,submitted_revision:null,review_order:null,review_mode:null,branch_reviewer_code:null,bp_reviewer_code:null})));check(v.nodes.length===2&&v.html.includes('提交时确定')&&!v.html.includes('负责人通过后'),'never-submitted null order does not guess deployment order');
  v=parse(renderSummary(summary({status:'DRAFT',submitted_revision:null,policy_configured:false})));check(v.html.includes('新提交复核规则尚未配置')&&v.html.includes('提交时确定')&&states(v).join(',')==='current,waiting','unconfigured new draft keeps actionable notice without guessing the next route');
  v=parse(renderSummary(summary({review_blocked_reason:'冻结关系已变化'})));same(states(v),['done','blocked','waiting','waiting'],'summary pause retains frozen participant but blocks current node');
  v=parse(renderSummary(summary({capabilities:{review:false,submit:false}})));check(states(v)[1]==='current'&&v.root.querySelectorAll('button').length===0,'read-authorized non-assignee sees current stage without added action');
  for(const extra of [{review_mode:null},{review_order:null},{review_mode:'SINGLE_EXPLICIT',review_order:'BRANCH_THEN_BP'},{review_mode:'UNRECOGNIZED'}]){v=parse(renderSummary(summary(extra)));check(v.nodes.length===0&&v.html.includes('重新打开'),'unknown submitted mode/order never defaults to sequential approval');}
  const xss='<img src=x onerror=alert(1)><script>bad</script>';
  for(const html of [renderDemand(demand({...a,blocked_reason:xss,round:xss}),()=>xss),renderSummary(summary({branch_reviewer_code:xss,review_blocked_reason:xss}))]){const x=parse(html);check(!x.root.querySelector('img')&&!x.root.querySelector('script')&&!html.includes('<img'),'untrusted labels/reasons render as escaped text');}
  const frozen=demand(),before=JSON.stringify(frozen);renderDemand(frozen);check(JSON.stringify(frozen)===before,'component never mutates server input');check(requests===0,'pure visual performs no HTTP reads or writes');
  const host=fs.readFileSync(path.join(repo,'web/app.js'),'utf8'),index=fs.readFileSync(path.join(repo,'web/index.html'),'utf8');
  check(host.includes('renderDemand(workflow, code => workflowName(context.people, code))'),'demand uses only existing filtered context for labels');check(host.includes('renderSummary(flow)'),'summary consumes existing validated projection');
  check(index.indexOf('/modules/approval-visual/index.js')<index.indexOf('/app.js?v='),'readonly renderer loads before original host');const hostVersion=(index.match(/\/app\.js\?v=([0-9]{8}[a-z0-9]*)"/)||[])[1]||'';check(/^[0-9]{8}/.test(hostVersion)&&hostVersion.slice(0,8)>='20261027'&&index.split('/app.js?v=').length===2,'host cache version advanced: one versioned app.js at or after the approval-visual release (not pinned to one release key)');
  console.log(JSON.stringify({suite:'approval-visual-A-ui',checks:results.length,failures:[],results}));
}catch(e){console.log(JSON.stringify({suite:'approval-visual-A-ui',checks:results.length,failures:[e.message],results}));process.exitCode=1;}
