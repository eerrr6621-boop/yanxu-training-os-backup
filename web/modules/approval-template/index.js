const KINDS={M03_DEMAND:'需求审批',M08_SUMMARY:'培训总结复核'};
const BASES={M03_DEMAND:'/demands/approval-templates',M08_SUMMARY:'/training-summaries/approval-templates'};
const IMPACTS={M03_DEMAND:'用于之后首次提交的需求审批；已进入审批的需求在退回、修改和重提时保留原模板。',M08_SUMMARY:'用于之后新提交的总结版本；已提交版本保留原模板，退回修改后再次提交会重新核对模板和人员。'};
const ORDERS={BRANCH_THEN_BP:'负责人审批后，再由 BP 审批',UNORDERED:'负责人和 BP 无先后顺序，双方均须审批'};
const ROLES={BRANCH:'负责人审批职责',BP:'BP 审批职责'};
const clone=value=>JSON.parse(JSON.stringify(value));
const ordered=value=>Array.isArray(value)?value.map(ordered):value&&typeof value==='object'?Object.fromEntries(Object.keys(value).sort().map(key=>[key,ordered(value[key])])):value;
const equal=(a,b)=>JSON.stringify(ordered(a))===JSON.stringify(ordered(b));
const integer=value=>Number.isSafeInteger(value)&&value>=0;
const validText=(value,max)=>typeof value==='string'&&value.trim()===value&&value.length>0&&value.length<=max&&!/[\u0000-\u001f\u007f]/.test(value);
function definition(value,kind){
  if(!value||!equal(Object.keys(value).sort(),['after_nodes','before_nodes','core_order','format'])||value.format!=='APPROVAL-TEMPLATE-1'||!Object.hasOwn(ORDERS,value.core_order)||kind==='M03_DEMAND'&&value.core_order!=='BRANCH_THEN_BP'||!Array.isArray(value.before_nodes)||!Array.isArray(value.after_nodes))throw Error('模板内容无法核对');
  const extras=[...value.before_nodes,...value.after_nodes],ids=new Set(),people=new Set();
  if(extras.length>4)throw Error('补充审批节点最多四个');
  for(const item of extras){if(!item||!equal(Object.keys(item).sort(),['node_id','person_code','review_role','title'])||!/^extra-[a-z0-9][a-z0-9-]{0,31}$/.test(item.node_id)||!validText(item.title,60)||!Object.hasOwn(ROLES,item.review_role)||!validText(item.person_code,120)||ids.has(item.node_id)||people.has(item.person_code))throw Error('请核对补充节点名称、职责和不重复的办理人');ids.add(item.node_id);people.add(item.person_code);}
  return clone(value);
}
function row(value,kind,org){if(!value||value.business_kind!==kind||value.organization_code!==org||!integer(value.revision)||value.revision<1||!['DRAFT_SAVED','PUBLISHED'].includes(value.operation)||!validText(value.actor_name,500)||typeof value.reason!=='string'||!value.reason.trim()||!Number.isFinite(Date.parse(value.created_at))||!integer(value.active_revision)||value.active_revision>value.revision||!integer(value.previous_active_revision)||value.previous_active_revision>=value.revision)throw Error('变更记录无法核对');definition(value.definition,kind);return value;}
function view(value,kind,org){if(!value||value.business_kind!==kind||value.organization_code!==org||!integer(value.head_revision)||!integer(value.active_revision)||value.active_revision>value.head_revision||value.max_extra_nodes!==4||!equal(value.conditions,['ALWAYS'])||!equal(value.required_gates,['BRANCH','BP'])||value.authority!==(value.active_revision?'ORG_TEMPLATE':kind==='M03_DEMAND'?'M03_BASELINE':'GLOBAL_DEFAULT'))throw Error('当前模板无法核对');if(value.head_revision===0?value.draft!==null:row(value.draft,kind,org).revision!==value.head_revision||value.draft.active_revision!==value.active_revision)throw Error('草稿版本无法核对');if(value.active_revision===0?value.active!==null:row(value.active,kind,org).revision!==value.active_revision||value.active.operation!=='PUBLISHED')throw Error('生效版本无法核对');return value;}
function preflight(value,def){
  if(!value||value.format!=='APPROVAL-TEMPLATE-PREFLIGHT-1'||!validText(value.configuration_version,128)||!integer(value.checked_submitters)||!Array.isArray(value.conflicts)||!Array.isArray(value.unresolved)||typeof value.publishable!=='boolean'||value.publishable!==(value.conflicts.length===0&&value.unresolved.length===0))throw Error('人员预检无法核对');
  const people=new Set(),extras=[...def.before_nodes,...def.after_nodes];
  for(const item of [...value.conflicts,...value.unresolved]){if(!validText(item?.submitter_code,120)||!validText(item.submitter_label,500))throw Error('受影响人员无法核对');people.add(item.submitter_code);}
  for(const item of value.conflicts)if(!['SUBMITTER','BRANCH','BP'].includes(item.conflict_role)||!extras.some(e=>e.node_id===item.node_id&&e.person_code===item.person_code))throw Error('冲突节点无法核对');
  for(const item of value.unresolved)if(!validText(item.reason,500))throw Error('必需关系问题无法核对');
  if(people.size>value.checked_submitters)throw Error('人员预检数量无法核对');return value;
}

/** Host owns the original modal, identity lifecycle and /api prefix. No new page or shared styles. */
export function createApprovalTemplates(host){
  for(const name of ['api','getUser','getModal','openModal','closeModal','toast','newRequestId'])if(typeof host?.[name]!=='function')throw TypeError('Missing template host: '+name);
  const identity=host.getUser();let alive=true,owned=null,serial=0;
  const active=()=>alive&&!host.signal?.aborted&&(host.isCurrent?.()??true)&&host.getUser()===identity&&identity?.role==='admin';
  const current=s=>active()&&owned===s&&!s.closed&&s.mask?.isConnected!==false&&host.getModal()===s.mask;
  const now=()=>host.now?.()??Date.now();
  const base=s=>BASES[s.kind];
  function node(s,tag,text,cls){const n=s.doc.createElement(tag);if(text!==undefined)n.textContent=String(text);if(cls)n.className=cls;return n;}
  function button(s,text,action,run){const b=node(s,'button',text,'btn gray sm');b.type='button';b.dataset.templateAction=action;b.addEventListener('click',run);return b;}
  function say(s,message){if(current(s))s.ui.notice.textContent=message;}
  function dispose(s){if(!s||s.closed)return;s.closed=true;s.ticket++;s.controller?.abort();s.snapshot=s.review=s.retry=s.history=s.def=null;s.people={};s.context=null;s.leave=null;s.ui.root.replaceChildren();s.mask.dataset.locked='false';if(owned===s)owned=null;}
  function forceClose(s){dispose(s);if(host.getModal()===s.mask)host.closeModal(true);}
  function ensure(s){if(current(s))return true;if(owned===s&&!active())forceClose(s);return false;}
  function clearPreview(s){s.review=null;s.ui.review.replaceChildren();}
  function clearScope(s){s.snapshot=s.review=s.history=s.retry=null;s.people={};s.def=null;s.baseline=null;s.editables=[];s.unknown=false;s.dirty=false;s.offset=0;s.ui.reason.value='';for(const key of ['current','editor','review','history'])s.ui[key].replaceChildren();}
  function controls(s){
    if(!current(s))return;const locked=!!s.busy||s.unknown;
    s.ui.kind.disabled=locked;s.ui.org.disabled=locked||!s.context;s.ui.read.disabled=locked;
    s.ui.reason.disabled=locked||!s.snapshot;
    for(const item of s.editables)item.disabled=locked;
    s.ui.save.disabled=locked||!s.snapshot;s.ui.preview.disabled=locked||!s.snapshot?.head_revision||s.dirty;
    s.ui.publish.disabled=locked||!s.review?.preflight.publishable;s.ui.retry.disabled=!!s.busy||!s.unknown;s.ui.retry.hidden=!s.unknown;
    s.ui.previous.disabled=locked||!s.history||s.offset===0;s.ui.next.disabled=locked||!s.history||s.offset+20>=s.history.total;
    s.mask.dataset.locked=['draft','publish'].includes(s.busy)?'true':'false';
  }
  function leave(s,action){
    if(!ensure(s))return false;
    if(['draft','publish'].includes(s.busy)){say(s,'正在提交，请等待本次结果。');return false;}
    if(s.unknown){say(s,'本次提交结果尚未核实，请先核对同一次结果，再离开或切换。');return false;}
    if(!s.dirty){action();return true;}
    s.leave=action;s.ui.leave.replaceChildren(node(s,'p','尚有未保存的修改。放弃后将重新读取服务器上的模板。'),button(s,'保留编辑','keep',()=>{s.leave=null;s.ui.leave.replaceChildren();}),button(s,'放弃修改并继续','discard',()=>{const next=s.leave;s.leave=null;s.dirty=false;s.ui.leave.replaceChildren();next?.();}));return false;
  }
  function changed(s){if(!ensure(s)||s.busy||s.unknown||!s.snapshot)return;clearPreview(s);s.dirty=!equal(s.def,s.baseline.definition)||s.ui.reason.value!==s.baseline.reason;say(s,'修改尚未生效。请保存草稿后预览，再明确发布。');controls(s);}
  function field(s,label,control,parent=s.ui.editor){const wrap=node(s,'div',undefined,'form-item');wrap.append(node(s,'label',label),control);control.setAttribute('aria-label',label);parent.append(wrap);return control;}
  function select(s,items,value){const n=node(s,'select');for(const [v,label]of items){const option=node(s,'option',label);option.value=v;n.append(option);}n.value=value;return n;}
  function describe(def,people){if(!def)return '此前未发布机构模板，沿用原默认安排';const names=nodes=>nodes.map(n=>{const person=people?.[n.review_role]?.find(p=>p.person_code===n.person_code);return `${n.title}（${ROLES[n.review_role]}，${person?person.label+'（'+n.person_code+'）':n.person_code}）`;});return [...names(def.before_nodes),`必需组：${ORDERS[def.core_order]}`,...names(def.after_nodes)].join(' → ');}
  function renderEditor(s){
    s.ui.editor.replaceChildren();s.editables=[];if(!s.def)return;
    function group(key,label){
      const area=node(s,'section');area.append(node(s,'h4',label));s.ui.editor.append(area);
      s.def[key].forEach((extra,index)=>{
        const box=node(s,'div',undefined,'inline-note');box.style.display='block';box.style.margin='8px 0';area.append(box);
        const title=field(s,'节点名称',node(s,'input'),box);title.value=extra.title;title.maxLength=60;title.addEventListener('input',()=>{if(!ensure(s)||s.busy||s.unknown)return;extra.title=title.value;changed(s);});
        const role=field(s,'审批职责',select(s,Object.entries(ROLES),extra.review_role),box);role.addEventListener('change',()=>{if(!ensure(s)||s.busy||s.unknown)return;extra.review_role=role.value;extra.person_code='';changed(s);renderEditor(s);});
        const candidates=s.people[extra.review_role]||[];
        const person=field(s,'办理人',select(s,[['','请选择当前可用办理人'],...candidates.map(p=>[p.person_code,p.label])],candidates.some(p=>p.person_code===extra.person_code)?extra.person_code:''),box);
        person.addEventListener('change',()=>{if(!ensure(s)||s.busy||s.unknown)return;extra.person_code=person.value;changed(s);});
        if(extra.person_code&&!candidates.some(p=>p.person_code===extra.person_code))box.append(node(s,'p',`原办理人 ${extra.person_code} 当前不可选，请核对后重新选择。`));
        const actions=node(s,'div',undefined,'toolbar');
        const mutate=fn=>()=>{if(!ensure(s)||s.busy||s.unknown)return;fn();changed(s);renderEditor(s);};
        const up=button(s,'上移','up',mutate(()=>{if(index>0)[s.def[key][index-1],s.def[key][index]]=[s.def[key][index],s.def[key][index-1]];}));
        const down=button(s,'下移','down',mutate(()=>{if(index+1<s.def[key].length)[s.def[key][index+1],s.def[key][index]]=[s.def[key][index],s.def[key][index+1]];}));
        up.hidden=index===0;down.hidden=index===s.def[key].length-1;
        const move=button(s,key==='before_nodes'?'移至必需组后':'移至必需组前','move',mutate(()=>{s.def[key].splice(index,1);s.def[key==='before_nodes'?'after_nodes':'before_nodes'].push(extra);}));
        const remove=button(s,'删除补充节点','remove',mutate(()=>s.def[key].splice(index,1)));
        actions.append(up,down,move,remove);box.append(actions);s.editables.push(title,role,person,up,down,move,remove);
      });
      const add=button(s,'添加补充节点','add-'+key,()=>{
        if(!ensure(s)||s.busy||s.unknown||s.def.before_nodes.length+s.def.after_nodes.length>=4)return;
        let id;do{id='extra-'+String(host.newRequestId()).toLowerCase().replace(/[^a-z0-9-]/g,'').slice(0,24)+'-'+(++serial);}while([...s.def.before_nodes,...s.def.after_nodes].some(n=>n.node_id===id));
        s.def[key].push({node_id:id,title:'',review_role:'BRANCH',person_code:''});changed(s);renderEditor(s);
      });add.hidden=s.def.before_nodes.length+s.def.after_nodes.length>=4;area.append(add);s.editables.push(add);
    }
    group('before_nodes','必需复核组之前');
    const core=node(s,'section',undefined,'inline-note');core.style.display='block';core.append(node(s,'h4','负责人和 BP · 必需复核组'),node(s,'p','两项职责均须完成，补充节点不能插入组内或跳过该组。'));
    if(s.kind==='M08_SUMMARY'){
      const order=field(s,'必需组顺序',select(s,[['','请选择未来普通总结顺序'],...Object.entries(ORDERS)],s.def.core_order),core);order.dataset.templateField='order';order.addEventListener('change',()=>{if(!ensure(s)||s.busy||s.unknown)return;s.def.core_order=order.value;changed(s);});s.editables.push(order);
    }else core.append(node(s,'p',ORDERS.BRANCH_THEN_BP));
    core.append(node(s,'p','已有兼任安排仍按当前人员授权核对。'));s.ui.editor.append(core);group('after_nodes','必需复核组之后');controls(s);
  }
  function historyData(value,s,offset,limit=20){if(!value||value.business_kind!==s.kind||value.organization_code!==s.org||!integer(value.head_revision)||value.total!==value.head_revision||value.offset!==offset||value.limit!==limit||!Array.isArray(value.items)||value.items.length!==Math.min(limit,Math.max(0,value.total-offset)))throw Error('历史分页无法核对');value.items.forEach((item,i)=>{row(item,s.kind,s.org);if(item.revision!==value.total-offset-i)throw Error('历史顺序无法核对');});return value;}
  async function readHistory(s,offset,signal){
    const h=historyData(await host.api(`${base(s)}/history?organization_code=${encodeURIComponent(s.org)}&offset=${offset}&limit=20`,{quiet:true,signal}),s,offset);
    let previous=null;if(h.total>offset+h.items.length){const tail=historyData(await host.api(`${base(s)}/history?organization_code=${encodeURIComponent(s.org)}&offset=${offset+h.items.length}&limit=1`,{quiet:true,signal}),s,offset+h.items.length,1);if(tail.head_revision!==h.head_revision)throw Error('历史版本已变化');previous=tail.items[0];}
    return {...h,previous};
  }
  function renderHistory(s,h){
    s.history=h;s.ui.history.replaceChildren(node(s,'h4','模板变更记录'));
    if(!h.items.length){s.ui.history.append(node(s,'p','尚无草稿或发布记录。'));return;}
    const byRevision=new Map([...h.items,...(h.previous?[h.previous]:[])].map(r=>[r.revision,r]));
    for(const item of h.items){
      const published=item.operation==='PUBLISHED',previous=published?item.previous_active_revision:item.revision-1,prior=byRevision.get(previous);
      const before=previous===0?describe(null):prior?describe(prior.definition):`机构模板修订 ${previous}（具体节点请在对应历史页核对）`;
      const card=node(s,'div',undefined,'inline-note');card.style.display='block';card.style.margin='8px 0';card.append(node(s,'b',`修订 ${item.revision} · ${published?'已发布':'已保存草稿'}`),node(s,'p',`${published?'发布前生效':'草稿改前'}：${before}`),node(s,'p',`此修订：${describe(item.definition)}`),node(s,'p',`${item.actor_name} · ${item.created_at}`),node(s,'p',`理由：${item.reason}`));s.ui.history.append(card);
    }
  }
  function start(s,operation){s.controller?.abort();s.controller=new AbortController();s.busy=operation;const ticket=++s.ticket;controls(s);return {ticket,signal:s.controller.signal};}
  function final(s,ticket){if(current(s)&&ticket===s.ticket){s.busy=false;controls(s);}}
  function failure(s,error){if([401,403].includes(Number(error?.status||error?.code))){forceClose(s);host.toast('登录或模板管理权限已变化，请重新核对。',true);return true;}return false;}
  async function context(s,kind){
    if(!ensure(s))return false;s.kind=kind;s.org='';s.context=null;clearScope(s);s.ui.kind.value=kind;s.ui.impact.textContent=IMPACTS[kind];s.ui.org.replaceChildren();s.ui.leave.replaceChildren();const {ticket,signal}=start(s,'context');say(s,'正在读取可维护机构…');
    try{const data=await host.api(base(s)+'/context',{quiet:true,signal});if(!ensure(s)||ticket!==s.ticket)return false;if(data?.business_kind!==kind||data.max_extra_nodes!==4||!equal(data.conditions,['ALWAYS'])||!Array.isArray(data.organizations)||new Set(data.organizations.map(o=>o.organization_code)).size!==data.organizations.length||!data.organizations.every(o=>validText(o.organization_code,120)&&validText(o.label,500)))throw Error('机构范围无法核对');s.context=data;const options=select(s,[['','请选择明确机构'],...data.organizations.map(o=>[o.organization_code,o.label])],'');s.ui.org.replaceChildren(...options.children);s.ui.org.value='';say(s,data.organizations.length?'请选择要维护的业务机构。':'暂无可维护的正式机构，请先核对组织配置。');return true;}catch(error){if(ensure(s)&&ticket===s.ticket&&error?.name!=='AbortError'&&!failure(s,error))say(s,'机构范围读取失败，请重新读取。');return false;}finally{final(s,ticket);}
  }
  async function read(s,org=s.org,notice=''){
    if(!ensure(s)||!org)return false;if(!s.context?.organizations.some(item=>item.organization_code===org)){say(s,'请从当前机构列表中选择。');return false;}s.org=org;s.ui.org.value=org;clearScope(s);s.ui.leave.replaceChildren();const {ticket,signal}=start(s,'read');say(s,'正在读取当前模板、可选人员和记录…');
    try{
      const [raw,branch,bp,h]=await Promise.all([host.api(`${base(s)}?organization_code=${encodeURIComponent(org)}`,{quiet:true,signal}),...['BRANCH','BP'].map(role=>host.api(`${base(s)}/candidates?organization_code=${encodeURIComponent(org)}&review_role=${role}`,{quiet:true,signal})),readHistory(s,0,signal)]);
      if(!ensure(s)||ticket!==s.ticket||s.org!==org)return false;const snapshot=view(raw,s.kind,org);
      for(const [role,list]of [['BRANCH',branch],['BP',bp]]){if(list?.business_kind!==s.kind||list.organization_code!==org||list.review_role!==role||!validText(list.configuration_version,128)||!Array.isArray(list.items)||list.items.some(p=>p.review_role!==role||!validText(p.person_code,120)||!validText(p.label,500))||new Set(list.items.map(p=>p.person_code)).size!==list.items.length)throw Error('可选人员无法核对');s.people[role]=list.items;}
      if(branch.configuration_version!==bp.configuration_version||h.head_revision!==snapshot.head_revision||h.active_revision!==snapshot.active_revision)throw Error('当前依据已变化');
      s.snapshot=snapshot;s.def=clone(snapshot.draft?.definition||{format:'APPROVAL-TEMPLATE-1',core_order:s.kind==='M03_DEMAND'?'BRANCH_THEN_BP':'',before_nodes:[],after_nodes:[]});s.ui.reason.value=snapshot.draft?.reason||'';s.baseline={definition:clone(s.def),reason:s.ui.reason.value};
      s.ui.current.append(node(s,'h4',`当前生效：${snapshot.active_revision?'机构模板 · 修订 '+snapshot.active_revision:s.kind==='M03_DEMAND'?'原需求审批安排':'全局普通总结默认安排'}`),node(s,'p',describe(snapshot.active?.definition)),node(s,'p',`最新草稿 / 记录修订 ${snapshot.head_revision}。保存草稿不会改变生效安排。`));renderEditor(s);renderHistory(s,h);say(s,notice||'请核对审批节点和下方生效范围。');return true;
    }catch(error){if(!ensure(s)||ticket!==s.ticket||error?.name==='AbortError')return false;clearScope(s);if(!failure(s,error))say(s,'当前模板、人员或版本无法核对，请重新读取。');return false;}finally{final(s,ticket);}
  }
  function validated(s){const def=definition(s.def,s.kind);for(const extra of [...def.before_nodes,...def.after_nodes])if(!s.people[extra.review_role]?.some(p=>p.person_code===extra.person_code))throw Error('请从当前机构的可选人员中选择办理人');const reason=s.ui.reason.value;if(!reason.trim()||reason.length>1000)throw Error('请填写不超过 1000 字的修改理由');return {definition:def,reason};}
  function checkReceipt(value,s,request){
    const expected=request.operation==='draft'?request.body.expected_revision:request.review.head_revision;
    if(!value||value.business_kind!==s.kind||value.organization_code!==s.org||typeof value.saved!=='boolean'||typeof value.replayed!=='boolean'||value.result_revision<1||value.result_revision!==expected+(value.saved?1:0)||!integer(value.current_revision)||value.current_revision<value.result_revision||!integer(value.result_active_revision)||value.result_active_revision>value.result_revision||!integer(value.current_active_revision)||value.current_active_revision>value.current_revision)throw Error('提交回执无法核对');
    if(request.operation==='publish'&&value.saved!==request.review.changed)throw Error('发布回执无法核对');return value;
  }
  async function submit(s,request){
    if(!ensure(s)||s.busy)return;const {ticket,signal}=start(s,request.operation);s.retry=request;say(s,s.unknown?'正在核对同一次提交结果…':request.operation==='draft'?'正在保存草稿…':'正在发布未来规则…');
    try{const result=checkReceipt(await host.api(base(s)+'/'+request.operation,{body:clone(request.body),quiet:true,signal}),s,request);if(!ensure(s)||ticket!==s.ticket)return;s.retry=null;s.unknown=false;s.busy=false;await read(s,s.org,`${result.replayed?'同一次提交结果已核实。':''}${request.operation==='draft'?'草稿已核对，尚未生效。':'发布结果已核对。'}下方已重新读取当前记录。`);}
    catch(error){if(!ensure(s)||ticket!==s.ticket)return;if(failure(s,error))return;const code=Number(error?.status||error?.code);if([400,404,409,413].includes(code)){s.retry=null;s.unknown=false;clearPreview(s);if(code===409){clearScope(s);say(s,'模板、人员或预览已变化，请重新读取后核对。');}else say(s,error.message||'本次提交未通过，请核对填写内容。');}else{s.unknown=true;say(s,'本次提交结果尚未核实。请核对同一次结果；核实前保留原提交，不切换或重新生成请求。');}}
    finally{final(s,ticket);}
  }
  async function save(s){if(!ensure(s)||s.busy||s.unknown||!s.snapshot)return;try{const value=validated(s);clearPreview(s);await submit(s,{operation:'draft',body:{organization_code:s.org,expected_revision:s.snapshot.head_revision,request_id:host.newRequestId(),...value}});}catch(error){say(s,error.message);controls(s);}}
  async function preview(s){
    if(!ensure(s)||s.busy||s.unknown||s.dirty||!s.snapshot?.head_revision)return;const snapshot=s.snapshot,{ticket,signal}=start(s,'preview');clearPreview(s);
    try{const v=await host.api(base(s)+'/preview',{body:{organization_code:s.org,expected_revision:snapshot.head_revision},quiet:true,signal});if(!ensure(s)||ticket!==s.ticket)return;
      const changed=!equal(snapshot.active?.definition??null,snapshot.draft.definition);
      if(!v||v.business_kind!==s.kind||v.organization_code!==s.org||v.head_revision!==snapshot.head_revision||v.active_revision!==snapshot.active_revision||!equal(v.before,snapshot.active?.definition??null)||!equal(v.after,snapshot.draft.definition)||v.changed!==changed||!equal(v.required_gates,['BRANCH','BP'])||v.applies_to!==(s.kind==='M03_DEMAND'?'FUTURE_M03_INSTANCES':'FUTURE_M08_SUBMISSIONS'))throw Error('预览无法核对');
      const checked=preflight(v.preflight,v.after);
      if(checked.publishable? !/^[a-f0-9]{64}$/.test(v.review_token)||!Number.isFinite(Date.parse(v.expires_at))||Date.parse(v.expires_at)<=now():v.review_token!==null||v.expires_at!==null)throw Error('预览无法核对或已过期');
      definition(v.after,s.kind);s.review=v;s.ui.review.append(node(s,'h4','发布前只读核对'),node(s,'p',`改前：${describe(v.before)}`),node(s,'p',`改后：${describe(v.after,s.people)}`),node(s,'p','改后姓名为当前可选办理人的姓名，请同时核对编号。'),node(s,'p',`理由：${snapshot.draft.reason}`),node(s,'p',IMPACTS[s.kind]),node(s,'p',v.before===null?'首次发布会启用本机构模板，即使没有补充节点也会明确采用所选必需组安排。':v.changed?'发布后按以上范围采用新安排。':'与当前生效安排一致，不会新增有效模板版本。'));say(s,'尚未发布。请核对节点、办理人和影响范围，再确认发布。');
      s.ui.review.append(node(s,'h4','当前人员关系预检'),node(s,'p',`已核对 ${checked.checked_submitters} 位当前具备本机构提交权限的启用人员。人员关系或账号权限以后变化时，提交仍会重新校验。`));
      if(!checked.checked_submitters)s.ui.review.append(node(s,'p','当前没有具备完整提交权限的人员，尚无可模拟的提交关系；这不代表未来新增人员已经通过核对。'));
      for(const item of checked.conflicts)s.ui.review.append(node(s,'p',`${item.submitter_label}（${item.submitter_code}）：补充节点 ${item.node_id} 的人员 ${item.person_code} 与${{SUBMITTER:'填报人本人',BRANCH:'必需负责人',BP:'必需 BP'}[item.conflict_role]}重合。请调整补充节点，必需关卡仍须保留。`));
      for(const item of checked.unresolved)s.ui.review.append(node(s,'p',`${item.submitter_label}（${item.submitter_code}）：必需关系尚未核实，${item.reason}。请先核对人员配置。`));
      if(!checked.publishable)say(s,'暂不能发布：请按下方清单修正模板或人员配置。草稿已保留，所有必需及补充关卡均未跳过。');
    }catch(error){if(ensure(s)&&ticket===s.ticket&&error?.name!=='AbortError'){clearPreview(s);if(!failure(s,error))say(s,'预览未能核实，请重新读取或重新预览。');}}finally{final(s,ticket);}
  }
  async function publish(s){if(!ensure(s)||s.busy||s.unknown||!s.review?.preflight.publishable)return;if(Date.parse(s.review.expires_at)<=now()){clearPreview(s);say(s,'预览已过期，请重新预览。');controls(s);return;}await submit(s,{operation:'publish',review:clone(s.review),body:{review_token:s.review.review_token,request_id:host.newRequestId()}});}
  async function page(s,offset){if(!ensure(s)||s.busy||s.unknown||!s.snapshot)return;const {ticket,signal}=start(s,'history');try{const h=await readHistory(s,offset,signal);if(!ensure(s)||ticket!==s.ticket)return;if(h.head_revision!==s.snapshot.head_revision){clearScope(s);say(s,'模板版本已变化，请重新读取。');return;}s.offset=offset;renderHistory(s,h);}catch(error){if(ensure(s)&&ticket===s.ticket&&!failure(s,error))say(s,'历史记录读取失败，请重试。');}finally{final(s,ticket);}}
  async function open(kind='M08_SUMMARY'){
    if(!active()){if(owned)forceClose(owned);return false;}if(!Object.hasOwn(KINDS,kind))return false;if(host.getModal()){host.toast('请先完成或关闭当前窗口。',true);return false;}
    const s={kind,org:'',mask:null,closed:false,ticket:0,busy:false,unknown:false,dirty:false,editables:[],people:{},context:null,snapshot:null,review:null,retry:null,history:null,def:null,leave:null,offset:0};
    s.mask=host.openModal('审批节点模板','<div data-approval-template-mount></div>',{wide:true,noFoot:true,beforeClose:()=>{if(!current(s))return true;if(!s.dirty&&!s.unknown&&!['draft','publish'].includes(s.busy))return true;return leave(s,()=>host.closeModal(true));},onClose:()=>dispose(s)});
    if(!s.mask)return false;owned=s;s.doc=s.mask.ownerDocument;const root=s.mask.querySelector('[data-approval-template-mount]');if(!root){forceClose(s);throw Error('Template mount missing');}
    s.ui={root,notice:node(s,'p'),leave:node(s,'div'),current:node(s,'div'),editor:node(s,'div'),review:node(s,'div'),history:node(s,'div')};s.ui.notice.setAttribute('role','status');s.ui.notice.setAttribute('aria-live','polite');
    root.append(node(s,'p','负责人和 BP 始终保留；补充节点均逐一审批。保存草稿后，请预览并明确发布。'),s.ui.notice,s.ui.leave);
    const grid=node(s,'div',undefined,'form-grid');root.append(grid);s.ui.kind=field(s,'业务类型',select(s,Object.entries(KINDS),kind),grid);s.ui.org=field(s,'机构',select(s,[['','请选择明确机构']],''),grid);
    s.ui.kind.dataset.templateField='kind';s.ui.org.dataset.templateField='org';root.append(s.ui.current,s.ui.editor);
    s.ui.reason=field(s,'修改理由',node(s,'textarea'),root);s.ui.reason.maxLength=1000;s.ui.reason.rows=2;s.ui.reason.dataset.templateField='reason';s.ui.reason.addEventListener('input',()=>changed(s));
    const toolbar=node(s,'div',undefined,'toolbar');
    s.ui.save=button(s,'保存草稿','save',()=>save(s));s.ui.preview=button(s,'只读预览','preview',()=>preview(s));s.ui.publish=button(s,'确认发布未来规则','publish',()=>publish(s));s.ui.retry=button(s,'核对同一次提交结果','retry',()=>s.retry&&submit(s,s.retry));s.ui.read=button(s,'重新读取','read',()=>leave(s,()=>s.org?read(s):context(s,s.kind)));
    s.ui.impact=node(s,'p',IMPACTS[kind]);s.ui.impact.dataset.templateImpact='';toolbar.append(s.ui.save,s.ui.preview,s.ui.read,button(s,'关闭','close',()=>host.closeModal()));root.append(s.ui.impact,toolbar,s.ui.review,s.ui.publish,s.ui.retry,s.ui.history);
    const nav=node(s,'div',undefined,'toolbar');s.ui.previous=button(s,'上一页','previous',()=>page(s,Math.max(0,s.offset-20)));s.ui.next=button(s,'下一页','next',()=>page(s,s.offset+20));nav.append(s.ui.previous,s.ui.next);root.append(nav);
    s.ui.kind.addEventListener('change',()=>{const next=s.ui.kind.value;s.ui.kind.value=s.kind;if(Object.hasOwn(KINDS,next))leave(s,()=>context(s,next));});s.ui.org.addEventListener('change',()=>{const next=s.ui.org.value;s.ui.org.value=s.org;leave(s,()=>next?read(s,next):context(s,s.kind));});
    await context(s,kind);return current(s);
  }
  function destroy(){if(!alive)return;if(owned)forceClose(owned);alive=false;host.signal?.removeEventListener('abort',destroy);}
  if(host.signal?.aborted)alive=false;else host.signal?.addEventListener('abort',destroy,{once:true});return {open,destroy};
}
