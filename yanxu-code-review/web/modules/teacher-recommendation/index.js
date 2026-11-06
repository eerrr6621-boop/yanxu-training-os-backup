// Original-page V2 panel. The host supplies authenticated api, route lifecycle and identity.
const ROOT='/teacher-recommendations/decision/v2',CATALOG='/course-catalog/scopes';
const STATES={READY_FOR_REVIEW:'条件已核对 · 人工复核',PENDING:'待确认 · 不代表没有能力',EXCLUDED:'本次已知条件不符'};
const PREFERENCES={LOCAL_OR_ONLINE:'本地或线上优先',RAIL_WITHIN_180:'已核对高铁车程 3 小时内',AIR_WITHIN_150:'已核对航程 2.5 小时内',OTHER_FEASIBLE:'其他已确认可行安排',UNCONFIRMED:'出行条件待核'};
const AI_FAILURES={DISABLED:'辅助模型尚未启用',PRIVACY_REJECTED:'需求或资料无法安全表达为已支持的结构化线索，未发送',TIMED_OUT:'等待模型超时；上游是否继续处理尚不确定',INVALID_OUTPUT:'模型输出未通过候选或证据核验',BUSY:'辅助模型忙碌，本次未排队',USAGE_LIMIT:'已达到当前服务累计额度或分钟限额；本分析保留本次回执，不会自动重试',AUTH_REQUIRED:'本机 CPA 要求鉴权，请由用户处理',UNAVAILABLE:'辅助模型暂不可用',INPUT_LIMIT:'发送内容超过本地大小限制',OUTPUT_LIMIT:'响应超过本地大小限制',NO_CANDIDATES:'本次没有可解释的短名单',NO_CONFIRMED_EXPERIENCE:'没有当前有效的人工确认经历，本次未发送、未调用 AI；请在本次人工核对中明确确认',STALE_CONFIRMED_EXPERIENCE:'人工确认的来源或条件已变化，本次未发送、未调用 AI；请重新核对',NO_USABLE_EVIDENCE:'没有可安全使用的结构化经历线索，本次未调用 AI；请补充或核对资料，不据此判断候选能力',CANCELLED:'本次等待已结束；上游是否继续处理尚不确定',CLOSED:'辅助模型已关闭',RATE_LIMIT:'上游调用额度受限',CLIENT_ERROR:'未取得可核验响应；上游是否处理尚不确定'};
const CONFIRMATIONS=[['UNKNOWN','待核实'],['CONFIRMED','操作人已核实'],['DECLINED','明确不能']];
const array=value=>Array.isArray(value)?value:[],copy=value=>JSON.parse(JSON.stringify(value));
const integer=value=>Number.isSafeInteger(value)&&value>=0;
const key=input=>`${input.scope_id}:${input.context_type}:${input.context_id}`;
const text=value=>typeof value==='string'&&value.length>0;
const AI_TOPICS=new Set(['亲子沟通','家庭教育','心理健康','情绪管理','压力管理','团队沟通','跨部门沟通','团队建设','团队管理','领导力','战略管理','项目管理','时间管理','目标管理','绩效管理','质量管理','风险管理','安全生产','合规管理','人力资源','人才培养','员工培训','企业文化','组织发展','变革管理','创新思维','设计思维','问题解决','决策分析','数据分析','数字化转型','人工智能','信息安全','网络安全','财务管理','预算管理','成本管理','市场营销','品牌管理','销售技巧','客户服务','商务谈判','商务礼仪','公文写作','演讲表达','职业规划','职业素养','沟通','亲子']);
const EXPERIENCE={role:['TEACHING','LEAD_TEACHING','ASSISTANT','PARTICIPANT','EXPERIENCE'],assertion:['AFFIRMED','DENIED','LIMITED','UNKNOWN'],audience:['NEW_SUPERVISORS','BEGINNERS','EXPERIENCED_PROJECT_MANAGERS','MANAGERS','EMPLOYEES','PARENTS','CHILDREN','UNKNOWN'],depth:['INTRODUCTORY','PRACTICAL','ADVANCED','UNKNOWN'],limits:['ONLY_PARTICIPATED','ASSISTANT_ONLY','INTRODUCTORY_ONLY','LIMITED_LEAD_EXPERIENCE','LIMITED_TEACHING_EXPERIENCE','ROLE_SCOPE_UNCLEAR','NONE_RECORDED','UNKNOWN'],requirementLimits:['REQUIRE_LEAD_TEACHING','REQUIRE_TEACHING_EXPERIENCE','REQUIRE_PRACTICAL_EXPERIENCE','EXCLUDE_THEORY_ONLY','INTRODUCTORY_ONLY','ADVANCED_ONLY','ONLY_INCLUDED_TOPICS','NONE_RECORDED','UNKNOWN']};
const EXPERIENCE_LABELS={TEACHING:'讲授',LEAD_TEACHING:'主讲',ASSISTANT:'助教',PARTICIPANT:'参加或学习',EXPERIENCE:'课程经历（未限定角色）',AFFIRMED:'明确肯定',DENIED:'明确否定',LIMITED:'明确有限制',UNKNOWN:'明确选择未知或无法确认',NEW_SUPERVISORS:'新任主管',BEGINNERS:'初学者',EXPERIENCED_PROJECT_MANAGERS:'资深项目经理',MANAGERS:'管理者',EMPLOYEES:'员工',PARENTS:'家长',CHILDREN:'儿童',INTRODUCTORY:'入门',PRACTICAL:'实战',ADVANCED:'进阶',ONLY_PARTICIPATED:'仅参加或学习',ASSISTANT_ONLY:'仅助教',INTRODUCTORY_ONLY:'仅入门',LIMITED_LEAD_EXPERIENCE:'主讲经历有限',LIMITED_TEACHING_EXPERIENCE:'讲授经历有限',ROLE_SCOPE_UNCLEAR:'角色范围未明确',NONE_RECORDED:'已核对，未记录限制',REQUIRE_LEAD_TEACHING:'要求主讲经历',REQUIRE_TEACHING_EXPERIENCE:'要求讲授经历',REQUIRE_PRACTICAL_EXPERIENCE:'要求实战经历',EXCLUDE_THEORY_ONLY:'排除纯理论',ADVANCED_ONLY:'仅进阶',ONLY_INCLUDED_TOPICS:'仅本次所选课程'};
const exactKeys=(value,keys)=>value&&typeof value==='object'&&!Array.isArray(value)&&Object.keys(value).length===keys.length&&keys.every(key=>Object.hasOwn(value,key));
const boundedText=(value,max)=>text(value)&&value.length<=max;

export function mountRecommendationDecision(root,context){
  if(!root?.ownerDocument||typeof context?.api!=='function')throw TypeError('Recommendation panel requires original-page host');
  const doc=root.ownerDocument,identity=context.getUser?.();
  let alive=true,epoch=0,controller=null,busy='',aiPending=null,snapshot=null,contexts=[],analysis=null,assessment=null,basket=[],resultButtons=[],selected=new Set();
  const active=()=>alive&&!context.signal?.aborted&&(context.isCurrent?.()??true)&&(!context.getUser||context.getUser()===identity);
  const el=(tag,value,cls)=>{const n=doc.createElement(tag);if(value!==undefined)n.textContent=String(value);if(cls)n.className=cls;return n;};
  const button=(label,action,run)=>{const n=el('button',label,'btn gray sm');n.type='button';n.setAttribute('data-recommend-action',action);n.addEventListener('click',run);return n;};
  const panel=el('section',undefined,'card');panel.setAttribute('aria-label','按本次需求核对师资');
  panel.append(el('h3','按本次需求核对师资'),el('p','按已确认的规则，先核对资格、完整档期和出行，再按本地与交通偏好给出候选。资料不足单列待确认；人工最终确认，不自动派课。'));
  const status=el('p');status.setAttribute('role','status');status.setAttribute('aria-live','polite');
  const cancelAdviceButton=button('停止等待','ai-cancel',()=>stopAdvice());cancelAdviceButton.hidden=true;cancelAdviceButton.disabled=true;
  const form=el('form'),grid=el('div',undefined,'form-grid'),fields={},basketArea=el('div'),results=el('div',undefined,'recommend-output'),assessmentArea=el('div');form.append(grid);
  const field=(parent,target,name,label,type='text',choices)=>{const wrap=el('div',undefined,'form-item'),control=el(choices?'select':type==='textarea'?'textarea':'input');if(!choices&&type!=='textarea')control.type=type;control.name=name;control.setAttribute('aria-label',label);wrap.append(el('label',label),control);parent.append(wrap);target[name]=control;if(choices)options(control,choices,choices[0]?.[0]??'');return control;};
  function options(control,choices,value=choices[0]?.[0]??''){control.replaceChildren();for(const [id,label]of choices){const option=el('option',label);option.value=String(id);control.append(option);}control.value=String(value);}
  field(grid,fields,'scope','机构课程范围','select',[['','请选择已获权范围']]);
  field(grid,fields,'context','本次已保存需求或项目','select',[['UNSAVED:0','未保存主题：仅分析，不能记录核对或联合建议']]);
  field(grid,fields,'topic','本次手工课程主题').maxLength=500;fields.topic.required=true;fields.topic.placeholder='例如：新审查人物26的团队沟通';
  field(grid,fields,'course','标准课程（可选）','select',[['','手工课程：逐课资格待确认']]);
  field(grid,fields,'date','授课日期（未知可留空）','date');field(grid,fields,'start','开始时间','time');field(grid,fields,'end','结束时间','time');
  field(grid,fields,'mode','授课形式','select',[['UNKNOWN','待确认'],['ONLINE','线上'],['ONSITE','线下']]);
  field(grid,fields,'place','本次授课地点').maxLength=200;
  field(grid,fields,'cities','本次允许的常驻城市（可选，每行一个精确值）','textarea').maxLength=3249;
  form.append(el('p','常驻地不等于本次出发地；车程或航程不等于门到门时间。未知距离和费用不按零比较，未记录授课也不等于没有能力。'));
  form.append(el('p','准备顺序：先在“课程与认证”维护机构范围、教师绑定和适用课程资格；再关联本次已保存需求或项目，并在候选卡片中“核对本次服务与档期”。未选标准课程、未核实服务意愿或完整档期会列为待确认；线下还需核对出行方式与前后缓冲。','recommend-input-foot'));
  const toolbar=el('div',undefined,'toolbar'),submit=el('button','核对当前候选','btn');submit.type='submit';submit.setAttribute('data-recommend-action','analyze');
  const refreshButton=button('刷新范围','refresh',()=>refresh()),addButton=button('加入联合需求篮','add-basket',()=>addBasket()),batchButton=button('核对篮中需求与联合建议','analyze-basket',()=>analyze(true));
  toolbar.append(submit,refreshButton,addButton,batchButton);
  let catalogButton=null;if(typeof context.openCourseCatalog==='function'){catalogButton=button('课程与认证','catalog',()=>{if(!guard()||assessment)return;invalidate();context.openCourseCatalog();});toolbar.append(catalogButton);}
  form.append(toolbar);panel.append(status,cancelAdviceButton,form,basketArea,assessmentArea,results);root.append(panel);
  const say=message=>{if(active())status.textContent=message;};
  function guard(){if(active())return true;if(alive)destroy();return false;}
  function current(ticket){return guard()&&ticket===epoch;}
  function controls(){
    if(!active())return;const locked=!!assessment;
    for(const input of Object.values(fields))input.disabled=locked||(input!==fields.scope&&!snapshot);
    fields.context.disabled=locked||!snapshot;submit.disabled=locked||!!busy||!snapshot||!array(snapshot.bindings).length;
    refreshButton.disabled=locked||!!busy;addButton.disabled=locked||!!busy||!snapshot||fields.context.value==='UNSAVED:0'||basket.length>=3;
    batchButton.disabled=locked||!!busy||!basket.length;if(catalogButton)catalogButton.disabled=locked||!!busy;
    for(const {item,enabled}of resultButtons)item.disabled=locked||!!busy||!enabled();
    cancelAdviceButton.hidden=busy!=='ai-advice'||!aiPending;cancelAdviceButton.disabled=locked||cancelAdviceButton.hidden;
    for(const item of basketArea.querySelectorAll('button'))item.disabled=locked||!!busy;
    if(assessment){const a=assessment;for(const input of Object.values(a.fields||{}))input.disabled=!!busy||a.unknown;for(const item of a.actions||[])item.disabled=!!busy||a.unknown;if(a.experience){for(const item of a.experience.controls)item.disabled=!!busy||a.unknown;for(const {item,enabled}of a.experience.buttons)item.disabled=!!busy||a.unknown||!enabled();}a.retryButton&&(a.retryButton.disabled=!!busy||!a.unknown);}
  }
  function clearAiWait(){if(aiPending)clearTimeout(aiPending.timer);aiPending=null;}
  function invalidate(message=''){epoch++;controller?.abort();controller=null;clearAiWait();busy='';analysis=null;selected.clear();results.replaceChildren();resultButtons=[];if(message)say(message);controls();}
  function start(operation){controller?.abort();clearAiWait();controller=new AbortController();const ticket=++epoch;busy=operation;controls();return{ticket,signal:controller.signal};}
  function finish(ticket){if(current(ticket)){clearAiWait();busy='';controls();}}
  function clearAssessment(){assessment=null;assessmentArea.replaceChildren();controls();}
  function clearPrivate(message){invalidate();snapshot=null;contexts=[];basket=[];clearAssessment();for(const name of ['topic','date','start','end','place','cities'])fields[name].value='';fields.mode.value='UNKNOWN';options(fields.scope,[['','请重新核对登录与范围权限']]);options(fields.context,[['UNSAVED:0','未保存主题：仅分析']]);options(fields.course,[['','手工课程：逐课资格待确认']]);renderBasket();say(message);controls();}
  function fail(error){
    const code=Number(error?.status||error?.code);
    if(code===401||code===403){clearPrivate(code===401?'登录已失效，请重新登录。':'当前权限已变化，旧范围、核对记录和候选已清除。');return true;}
    if(error?.name==='AbortError')return false;
    if(code===409){invalidate();basket=[];renderBasket();say('依据或版本已变化，请刷新范围后重新核对。');}
    else if(code===404){invalidate();say('本次分析或记录已失效，请重新读取并核对。');}
    else say(code===429?'当前核对较多，请稍后重试。':`本次未取得可用结果：${error?.message||'请稍后重试。'}`);
    return false;
  }
  async function refresh(){
    if(!guard()||assessment)return false;invalidate();snapshot=null;contexts=[];basket=[];renderBasket();options(fields.course,[['','手工课程：逐课资格待确认']]);options(fields.context,[['UNSAVED:0','未保存主题：仅分析']]);options(fields.scope,[['','请选择已获权范围']]);
    const {ticket,signal}=start('scopes');say('正在读取获权课程范围…');
    try{const data=await context.api(CATALOG,{quiet:true,signal});if(!current(ticket))return false;if(!Array.isArray(data?.scopes))throw Error('范围响应无法核对');
      const visible=data.scopes.filter(v=>v.can_read===true&&integer(v.scope_id)&&v.scope_id>0);if(new Set(visible.map(v=>v.scope_id)).size!==visible.length)throw Error('范围重复');
      options(fields.scope,[['','请选择已获权范围'],...visible.map(v=>[v.scope_id,`机构 ${v.organization_code} · 范围 ${v.scope_id}`])]);say(visible.length?'请选择范围、本次需求和课程主题。':'暂无明确获权范围，请在课程与认证中配置范围与教师绑定。');return true;
    }catch(error){if(current(ticket))fail(error);return false;}finally{finish(ticket);}
  }
  async function readScope(){
    if(!guard()||assessment)return;const selectedScope=fields.scope.value;invalidate();snapshot=null;contexts=[];options(fields.context,[['UNSAVED:0','未保存主题：仅分析']]);options(fields.course,[['','手工课程：逐课资格待确认']]);if(!selectedScope){controls();return;}
    const {ticket,signal}=start('scope');say('正在读取绑定、课程和可见需求…');
    try{const [data,available]=await Promise.all([context.api(`${CATALOG}/${encodeURIComponent(selectedScope)}`,{quiet:true,signal}),context.api(`${ROOT}/contexts?scope_id=${encodeURIComponent(selectedScope)}`,{quiet:true,signal})]);if(!current(ticket)||fields.scope.value!==selectedScope)return;
      if(String(data?.scope_id)!==selectedScope||!integer(data.version)||!Array.isArray(data.bindings)||available?.scope_id!==data.scope_id||!Array.isArray(available.contexts))throw Error('范围或需求响应无法核对');
      if(available.contexts.some(c=>!['DEMAND','PROJECT'].includes(c.type)||!integer(c.id)||c.id<1||!text(c.label))||new Set(available.contexts.map(c=>`${c.type}:${c.id}`)).size!==available.contexts.length)throw Error('需求身份无法核对');
      snapshot=data;contexts=available.contexts;options(fields.context,[['UNSAVED:0','未保存主题：仅分析，不能记录核对或联合建议'],...contexts.map(c=>[`${c.type}:${c.id}`,c.label])]);
      options(fields.course,[['','手工课程：逐课资格待确认'],...array(data.catalog?.courses).filter(c=>c.active===true&&typeof c.course_code==='string').map(c=>[c.course_code,`${c.course_name} · ${c.course_code}`])]);say(data.bindings.length?'可直接填写手工主题；关联已保存需求后，可由有权操作人记录本次核对。':'本范围尚无教师绑定，请先在课程与认证维护。');
    }catch(error){if(current(ticket)){snapshot=null;contexts=[];options(fields.course,[['','手工课程：逐课资格待确认']]);options(fields.context,[['UNSAVED:0','未保存主题：仅分析']]);fail(error);}}finally{finish(ticket);}
  }
  function requestBody(){
    if(!snapshot||String(snapshot.scope_id)!==fields.scope.value)throw Error('请先选择当前获权范围');const topic=fields.topic.value.trim();if(!topic)throw Error('请填写本次课程主题');
    const cities=fields.cities.value.split(/\r?\n/).map(v=>v.trim()).filter(Boolean);if(cities.length>50||new Set(cities).size!==cities.length||cities.some(v=>v.length>64))throw Error('常驻城市须为不重复的精确值，最多 50 项');
    const linked=contexts.find(c=>`${c.type}:${c.id}`===fields.context.value);if(fields.context.value!=='UNSAVED:0'&&!linked)throw Error('请选择当前后端返回的需求或项目');
    if(fields.course.value&&!array(snapshot.catalog?.courses).some(c=>c.active===true&&c.course_code===fields.course.value))throw Error('标准课程已不在当前列表');
    return{scope_id:snapshot.scope_id,expected_version:snapshot.version,topic,course_code:fields.course.value,date:fields.date.value,start_time:fields.start.value,end_time:fields.end.value,mode:fields.mode.value,allowed_base_cities:cities,context_type:linked?.type||'UNSAVED',context_id:linked?.id||0,teaching_place:fields.place.value.trim()};
  }
  function inputLabel(input){const queued=basket.find(v=>key(v.input)===key(input));if(queued&&queued.input.topic===input.topic)return queued.label;const c=snapshot?.scope_id===input.scope_id?contexts.find(v=>v.type===input.context_type&&v.id===input.context_id):null;return `${input.context_type==='UNSAVED'?'未保存主题':c?.label||`${input.context_type==='DEMAND'?'需求':'项目'} ${input.context_id}`} · ${input.topic}`;}
  function renderBasket(){basketArea.replaceChildren();if(!basket.length)return;basketArea.append(el('h4',`联合需求篮 · ${basket.length}/3`),el('p','每项保留加入时的条件。联合分析会重新核对全部机构权限；加入需求篮不会锁定老师。'));
    basket.forEach((item,index)=>{const row=el('div',undefined,'toolbar');row.append(el('span',item.label),button('移除','remove-basket',()=>{if(!guard()||assessment||busy)return;basket.splice(index,1);invalidate();renderBasket();controls();}));basketArea.append(row);});}
  function addBasket(){if(!guard()||assessment||busy)return;try{const input=requestBody();if(input.context_type==='UNSAVED')throw Error('请先关联已保存需求或项目');if(basket.length>=3)throw Error('一次最多三个需求');if(basket.some(v=>key(v.input)===key(input)))throw Error('该需求已在篮中；需要变更时请先移除再加入');basket.push({input:copy(input),label:inputLabel(input)});invalidate('已加入需求篮，尚未分析或锁定人员。');renderBasket();controls();}catch(error){say(error.message);}}
  function validateResponse(value,inputs){
    if(value?.aiUsage!==undefined)validateUsage(value.aiUsage);
    if(value?.schemaVersion!=='recommendation_decision_v2'||!text(value.analysisId)||!text(value.snapshotId)||value.assignmentCreated!==false||value.rulePolicyStatus!=='CONFIRMED_RULES_LOCAL_IMPLEMENTATION'||!Array.isArray(value.results)||value.results.length!==inputs.length)throw Error('分析回执无法核对');
    value.results.forEach((r,index)=>{const input=inputs[index],ids=new Set();if(r.requestId!==`request-${index+1}`||r.scopeId!==input.scope_id||r.catalogVersion!==input.expected_version||r.contextType!==input.context_type||r.contextId!==input.context_id||!Array.isArray(r.candidates)||!text(r.ruleVersion)||!integer(r.normalCount)||!integer(r.shortage)||!integer(r.scanned))throw Error('本次需求回执不一致');
      for(const c of r.candidates){if(!Object.hasOwn(STATES,c.decision)||!integer(c.teacherId)||c.teacherId<1||c.candidateId!==`teacher:${c.teacherId}`||ids.has(c.candidateId)||!text(c.name)||!Object.hasOwn(PREFERENCES,c.preference)||typeof c.canRecordAssessment!=='boolean'||!integer(c.assessmentRevision)||input.context_type==='UNSAVED'&&c.canRecordAssessment)throw Error('候选身份或分组无法核对');ids.add(c.candidateId);for(const e of array(c.evidence))if(e.candidateId!==c.candidateId)throw Error('资料线索不属于本候选');}
      for(const c of r.candidates)if(c.coordinationNotices!==undefined&&(!Array.isArray(c.coordinationNotices)||c.coordinationNotices.length>1||c.coordinationNotices.some(v=>v!=='存在其他待答复邀约，请人工协调')))throw Error('邀约提示无法核对');
      const advised=r.candidates.filter(c=>c.topicAdvice);for(const c of advised)validateTopicAdvice(c,r.model);if(advised.length>6||r.model?.source==='AI'&&advised.length===0)throw Error('AI 短名单数量无效');
      if(r.normalCount!==r.candidates.filter(c=>c.decision==='READY_FOR_REVIEW').length||r.shortage!==Math.max(0,3-r.normalCount)||r.scanned<r.candidates.length)throw Error('正常候选数量无法核对');
    });
    const joint=value.joint;if(!joint||joint.assignmentCreated!==false||typeof joint.limited!=='boolean'||!Array.isArray(joint.choices)||joint.choices.length!==inputs.length||!integer(joint.assignedCount)||new Set(joint.choices.map(c=>c.requestId)).size!==inputs.length)throw Error('联合建议无法核对');
    for(const c of joint.choices){const result=value.results.find(r=>r.requestId===c.requestId);if(!result||c.teacherId!==null&&!result.candidates.some(v=>v.teacherId===c.teacherId&&v.decision==='READY_FOR_REVIEW'))throw Error('联合建议含非正常候选');}if(joint.assignedCount!==joint.choices.filter(c=>c.teacherId!==null).length)throw Error('联合数量无法核对');return value;
  }
  async function analyze(batch=false){
    if(!guard()||assessment||busy)return;let inputs;try{inputs=batch?basket.map(v=>copy(v.input)):[requestBody()];if(!inputs.length)throw Error('请先加入需求');}catch(error){say(error.message);return;}
    const previous=analysis,previousSelected=new Set(selected);invalidate();const {ticket,signal}=start('analyze');say('正在核对全部候选、本次事实与当前占用…');
    try{const value=await context.api(batch?`${ROOT}/batch`:ROOT,{method:'POST',body:batch?{requests:inputs}:inputs[0],quiet:true,signal});if(!current(ticket))return;validateResponse(value,inputs);analysis={value,inputs:copy(inputs),expanded:new Set(),stoppedAdvice:new Map()};render();say('已核对全部候选。默认展示正常前三；不足如实保留，待确认单列。');}
    catch(error){if(current(ticket)){if(Number(error?.status||error?.code)===429&&previous&&JSON.stringify(previous.inputs)===JSON.stringify(inputs)){analysis=previous;selected=previousSelected;render();}fail(error);}}finally{finish(ticket);}
  }
  function register(item,enabled=()=>true){resultButtons.push({item,enabled});return item;}
  // Pure-rule policy: AI controls appear only if the server explicitly reports an enabled channel, which the pure-rule server never does.
  function aiEnabled(value){return value?.aiUsage?.enabled===true;}
  function render(){
    const bundle=analysis;if(!bundle)return;const value=bundle.value;results.replaceChildren();resultButtons=[];if(aiEnabled(value))results.append(el('p',usageNotice(value.aiUsage),'recommend-notice'));
    for(const [index,r]of value.results.entries()){
      const input=bundle.inputs[index],section=el('section');section.append(el('div',inputLabel(input),'recommend-results-head'));
      const notice=el('div',undefined,'recommend-notice');notice.append(el('span',`${aiEnabled(value)?modelNotice(r.model):'规则筛选结果'} ${r.notice||''}`));section.append(notice);
      if(aiEnabled(value)){const aiToolbar=el('div',undefined,'toolbar');aiToolbar.append(register(button('AI 辅助比较本次短名单','ai-advice',()=>advice(r.requestId,bundle)),()=>analysis===bundle&&!bundle.stoppedAdvice.has(r.requestId)&&bundle.value.aiUsage?.remaining!==0&&r.candidates.some(c=>c.decision!=='EXCLUDED')));section.append(aiToolbar,el('p','仅发送编号、操作人本次明确确认的单课程需求和经历事实及固定模板脱敏摘要，不从履历推断、不发送原文。每次最多 6 位候选；人工核对后仍须另行点击才调用 AI，不生成资格或派课。','recommend-input-foot'));}
      if(bundle.stoppedAdvice.has(r.requestId))section.append(el('p','本页已停止等待。上游可能继续处理并产生用量或费用；该需求的 AI 按钮已禁用，请重新核对候选后再决定是否调用。查看更多或刷新范围不会调用模型。','recommend-notice'));
      const head=el('div',undefined,'recommend-results-head');head.append(el('h2',`正常候选 · ${r.normalCount} 位`),el('small',r.shortage?`不足 3 位，尚缺 ${r.shortage} 位；不以待确认或已排除者补足。`:`已核对 ${r.scanned} 位，默认展示前三。`));section.append(head);
      const normal=r.candidates.filter(c=>c.decision==='READY_FOR_REVIEW'),list=el('div',undefined,'teacher-match-list');for(const c of bundle.expanded.has(r.requestId)?normal:normal.slice(0,3))list.append(card(c,r,input,bundle));section.append(list);
      if(normal.length>3&&!bundle.expanded.has(r.requestId))section.append(register(button(`查看全部 ${normal.length} 位正常候选`,'more',()=>more(r.requestId,bundle))));
      for(const state of ['PENDING','EXCLUDED']){const candidates=r.candidates.filter(c=>c.decision===state),group=el('details',undefined,'teacher-evidence'),summary=el('summary',`${STATES[state]} · ${candidates.length} 位`),items=el('div',undefined,'teacher-match-list');group.append(summary);for(const c of candidates)items.append(card(c,r,input,bundle));group.append(items);section.append(group);}
      results.append(section);
    }
    if(value.results.length>1){const section=el('section',undefined,'card');section.append(el('h3','联合建议 · 仅供人工确认'),el('p',`本轮建议覆盖 ${value.joint.assignedCount}/${value.results.length} 项。展示不锁人、不派课，也不表示总成本最低。`));if(value.joint.limited)section.append(el('p','已达到本次搜索上限；当前建议未穷尽全部组合，不能称为最优。','recommend-notice'));
      for(const choice of value.joint.choices){const r=value.results.find(v=>v.requestId===choice.requestId),index=value.results.indexOf(r),candidate=r.candidates.find(v=>v.teacherId===choice.teacherId);section.append(el('p',`${inputLabel(bundle.inputs[index])}：${candidate?candidate.name+'（'+candidate.candidateId+'）':'暂未分配'}${choice.reason==='JOINT_CONFLICT_ALTERNATIVE'?'；为避开本轮已知冲突采用兼容备选。':''}`));}results.append(section);}
    controls();
  }
  function card(candidate,result,input,bundle){
    const article=el('article',undefined,'teacher-match-card'),main=el('div',undefined,'teacher-match-main'),header=el('header'),avatar=el('span',Array.from(candidate.name).slice(-2).join(''),'person-avatar large'),identity=el('div');avatar.setAttribute('aria-hidden','true');identity.append(el('h3',candidate.name),el('p',STATES[candidate.decision]),el('small',PREFERENCES[candidate.preference]));header.append(avatar,identity);main.append(header);
    const detail=el('div',undefined,'teacher-match-detail');const block=(label,messages,cls)=>{if(!messages.length)return;const section=el('section',undefined,cls),list=el('ul');section.append(el('b',label));for(const message of messages)list.append(el('li',message));section.append(list);detail.append(section);};
    block('邀约协调提示',array(candidate.coordinationNotices),'match-gaps');
    block('条件核对',array(candidate.checks).filter(c=>c.state!=='PENDING').map(c=>c.message),'match-reasons');block('待确认事项',[...new Set([...array(candidate.missingInfo),...array(candidate.checks).filter(c=>c.state==='PENDING').map(c=>c.message)])],'match-gaps');block('主题资料线索',[candidate.reason,...array(candidate.topicReview)].filter(Boolean),'match-reasons');
    if(candidate.topicAdvice){const refs=array(candidate.topicAdvice.evidenceIds);block('本次操作人已确认事实（非资格认证）',[candidate.topicAdvice.reason,...candidate.topicAdvice.localEvidence.map(e=>`${refs.includes(e.evidenceId)?'AI选中线索':'同次已核来源（AI未选中）'}：${e.summary} · ${e.sourceRef}`)].filter(Boolean),'match-reasons');}main.append(detail);
    const evidence=el('details',undefined,'teacher-evidence'),summary=el('summary','查看可追溯资料线索'),list=el('ul');summary.append(el('span',array(candidate.evidence).length));evidence.append(summary);for(const item of array(candidate.evidence)){const line=el('li');line.append(el('b',`${item.title} · ${item.sourceRef}`),el('p',item.text));list.append(line);}if(!array(candidate.evidence).length)list.append(el('li','本次未返回主题资料线索；不据此判断能力。'));evidence.append(list);main.append(evidence);
    const footer=el('footer'),selectionKey=`${result.requestId}:${candidate.candidateId}`;
    if(candidate.decision!=='EXCLUDED')footer.append(register(button(selected.has(selectionKey)?'已核对为本页备选':'核对并选入本页备选','select',()=>select(candidate,result,bundle)),()=>analysis===bundle&&!selected.has(selectionKey)));
    if(candidate.canRecordAssessment)footer.append(register(button('核对本次服务与档期','assessment',()=>openAssessment(candidate,input,bundle)),()=>analysis===bundle));main.append(footer);article.append(main);return article;
  }
  function validateUsage(value){
    if(!value||value.scope!=='CURRENT_SERVICE_CYCLE'||typeof value.enabled!=='boolean'||!integer(value.limit)||value.limit<1||!integer(value.used)||value.used>value.limit||value.remaining!==value.limit-value.used||!text(value.cycleStartedAt)||!Number.isFinite(Date.parse(value.cycleStartedAt)))throw Error('AI 额度回执无法核对');
  }
  function usageNotice(value){
    validateUsage(value);
    const started=new Date(value.cycleStartedAt).toLocaleString('zh-CN',{hour12:false});
    const gate={SERVICE_TOTAL:'本次服务周期总额度已耗尽。',MINUTE_REQUESTS:'本分钟调用次数已用完，需等待分钟窗口恢复；不会自动重试。',MINUTE_BYTES:'上一请求达到本分钟输入总量上限；剩余调用次数不代表当前输入仍可发送。需等待分钟窗口恢复；不会自动重试。',CONCURRENT_REQUEST:'当前已有 AI 请求处理中，本次未排队。',NONE:''}[value.blockedBy]||'';

    return `AI 本次服务启动周期的共享额度（${started} 起）：已用 ${value.used}/${value.limit} 次，剩余 ${value.remaining} 次。${value.enabled?'':'当前 AI 通道未启用。'}${gate}所有获权操作人共用，按发送前预留次数计数，已发送失败或超时也计入。规则筛选仍可使用。刷新页面不会恢复总额度；耗尽后由操作者按正式启动流程重启服务才开始新周期，系统不会自动重启。`;
  }
  function modelNotice(model){
    if(model?.source==='AI'&&model.status==='COMPLETE')return 'AI · 已确认事实提示已通过编号与证据归属校验；事实由本次操作人明确记录，非资格认证，课程适配仍须人工判断。硬门槛、交通层级和顺序仍由规则决定。';
    if(model?.status==='NOT_REQUESTED')return 'RULES · 尚未请求 AI；点击辅助比较后才发送脱敏短名单。';
    if(model?.source==='MOCK')return 'MOCK · 合成模型桩主题建议，不是实际 AI 推理。';
    if(model?.status&&AI_FAILURES[model.status])return `RULES · ${model.status}：${AI_FAILURES[model.status]}。保留规则筛选结果，没有本次 AI 结论。`;
    return 'RULES · 当前为规则筛选结果，未调用 AI 模型。';
  }
  const stableValue=v=>Array.isArray(v)?v.map(stableValue):v&&typeof v==='object'?Object.fromEntries(Object.keys(v).sort().map(k=>[k,stableValue(v[k])])):v;
  function validLimits(values,allowed){return Array.isArray(values)&&values.length>0&&values.length<=allowed.length&&new Set(values).size===values.length&&values.every(v=>allowed.includes(v))&&!(values.length>1&&(values.includes('UNKNOWN')||values.includes('NONE_RECORDED')));}
  function validExperienceClaim(c){return AI_TOPICS.has(c.topic)&&EXPERIENCE.role.includes(c.role)&&EXPERIENCE.assertion.includes(c.assertion)&&EXPERIENCE.audience.includes(c.audience)&&EXPERIENCE.depth.includes(c.depth)&&validLimits(c.limits,EXPERIENCE.limits)&&!(c.limits.includes('INTRODUCTORY_ONLY')&&c.depth==='ADVANCED')&&!(c.assertion==='AFFIRMED'&&(c.limits.includes('ONLY_PARTICIPATED')&&c.role!=='PARTICIPANT'||c.limits.includes('ASSISTANT_ONLY')&&c.role!=='ASSISTANT'));}
  function validateTopicAdvice(candidate,model){
    const a=candidate.topicAdvice;if(!a)return;
    if(model?.source!=='AI'||model.status!=='COMPLETE'||candidate.decision==='EXCLUDED'||!exactKeys(a,['reasonCodes','evidenceIds','reason','localEvidence'])||!boundedText(a.reason,500)||!Array.isArray(a.reasonCodes)||a.reasonCodes.length!==1||!['TOPIC_RELATED','TOPIC_UNCLEAR'].includes(a.reasonCodes[0])||!Array.isArray(a.evidenceIds)||a.evidenceIds.length>8||new Set(a.evidenceIds).size!==a.evidenceIds.length||!Array.isArray(a.localEvidence)||a.localEvidence.length>8)throw Error('AI 结构化提示字段无效');
    const owned=new Set(),selectable=new Set();for(const e of a.localEvidence){
      if(!exactKeys(e,['evidenceId','candidateId','kind','title','sourceRef','sourceVersion','summary','claims','verification'])||e.verification!=='STAFF_CONFIRMED'||typeof e.evidenceId!=='string'||!/^ev:[a-f0-9]{64}$/.test(e.evidenceId)||owned.has(e.evidenceId)||e.candidateId!==candidate.candidateId||!['PROFILE_TOPIC','HISTORY_TOPIC'].includes(e.kind)||!boundedText(e.title,200)||!boundedText(e.sourceRef,200)||!boundedText(e.sourceVersion,128)||!boundedText(e.summary,1200)||!Array.isArray(e.claims)||e.claims.length>4)throw Error('AI 本地结构化证据归属或字段无效');
      owned.add(e.evidenceId);const seenClaims=new Set();for(const claim of e.claims){
        if(!exactKeys(claim,['topic','role','assertion','audience','depth','limits','verification'])||claim.verification!=='STAFF_CONFIRMED')throw Error('AI 线索确认字段无效');
        if(!validExperienceClaim(claim))throw Error('AI 线索枚举无效');if(claim.assertion!=='UNKNOWN')selectable.add(e.evidenceId);
        const signature=JSON.stringify(stableValue(claim));if(seenClaims.has(signature))throw Error('AI 线索重复');seenClaims.add(signature);
      }
    }
    if(a.evidenceIds.some(id=>typeof id!=='string'||!selectable.has(id))||(a.reasonCodes[0]==='TOPIC_RELATED')===!a.evidenceIds.length)throw Error('AI 选中线索不属于本候选已确认事实或理由不一致');
  }
  function ruleOnly(value){
    const normalized=copy(value);delete normalized.cacheHit;delete normalized.aiUsage;
    for(const r of normalized.results){delete r.model;for(const c of r.candidates)delete c.topicAdvice;}
    return JSON.stringify(stableValue(normalized));
  }
  function validateAdvice(value,bundle,requestId){
    validateResponse(value,bundle.inputs);
    if(value.analysisId!==bundle.value.analysisId||value.snapshotId!==bundle.value.snapshotId||ruleOnly(value)!==ruleOnly(bundle.value))throw Error('AI 回执改变了规则候选、顺序或联合结果');
    for(const old of bundle.value.results)if(old.requestId!==requestId&&JSON.stringify(stableValue(old))!==JSON.stringify(stableValue(value.results.find(item=>item.requestId===old.requestId))))throw Error('AI 回执改变了其他需求的结果');
    const r=value.results.find(item=>item.requestId===requestId),model=r?.model;
    if(!model||!['AI','RULES'].includes(model.source)||typeof model.status!=='string'||(model.source==='AI'?model.status!=='COMPLETE':!AI_FAILURES[model.status]))throw Error('AI 状态回执无效');
    const advised=r.candidates.filter(candidate=>candidate.topicAdvice);for(const candidate of advised)validateTopicAdvice(candidate,model);if(advised.length>6||model.source==='AI'&&advised.length===0)throw Error('AI 短名单数量无效');return r;
  }
  async function advice(requestId,bundle){
    if(!guard()||!aiEnabled(bundle.value)||assessment||busy||analysis!==bundle||bundle.stoppedAdvice.has(requestId)||bundle.value.aiUsage?.remaining===0)return;let received=false;const {ticket,signal}=start('ai-advice');aiPending={ticket,requestId,bundle,timer:setTimeout(()=>{if(aiPending?.ticket===ticket)stopAdvice('TIMED_OUT');},50000)};controls();say('AI 处理中：模型最多等待45秒，另预留5秒接收回执；停止等待不保证上游停止或不计费。');
    try{const value=await context.api(`${ROOT}/advice`,{method:'POST',body:{analysis_id:bundle.value.analysisId,request_id:requestId},quiet:true,signal});received=true;if(!current(ticket)||analysis!==bundle)return;for(const [id,status]of bundle.stoppedAdvice)stoppedResult(value,id,status);const result=validateAdvice(value,bundle,requestId);bundle.value=value;render();say(modelNotice(result.model));}
    catch(error){if(!current(ticket)||analysis!==bundle)return;const code=Number(error?.status||error?.code);if([401,403,404,409].includes(code)){fail(error);return;}
      const r=bundle.value.results.find(item=>item.requestId===requestId);if(r){r.model={source:'RULES',status:code===429?'USAGE_LIMIT':error?.name==='AbortError'?'CANCELLED':received?'INVALID_OUTPUT':'CLIENT_ERROR'};for(const c of r.candidates)delete c.topicAdvice;render();say(modelNotice(r.model));}}
    finally{finish(ticket);}
  }
  function stoppedResult(value,requestId,status){const r=value.results.find(item=>item.requestId===requestId);if(r){r.model={source:'RULES',status};for(const c of r.candidates)delete c.topicAdvice;}return r;}
  function stopAdvice(status='CANCELLED'){
    if(!guard()||busy!=='ai-advice'||!aiPending||analysis!==aiPending.bundle||aiPending.ticket!==epoch)return;
    const {requestId,bundle}=aiPending;epoch++;controller?.abort();controller=null;clearAiWait();busy='';bundle.stoppedAdvice.set(requestId,status);const r=stoppedResult(bundle.value,requestId,status);render();say(`${modelNotice(r?.model)} 本页已停止等待；不保证上游停止，也不保证没有用量或费用。重新核对候选前不会再次调用。`);
  }
  async function more(requestId,bundle){if(!guard()||assessment||busy||analysis!==bundle)return;const {ticket,signal}=start('more');try{const value=await context.api(`${ROOT}?analysis_id=${encodeURIComponent(bundle.value.analysisId)}`,{quiet:true,signal});if(!current(ticket)||analysis!==bundle)return;validateResponse(value,bundle.inputs);if(value.analysisId!==bundle.value.analysisId||value.snapshotId!==bundle.value.snapshotId)throw Error('复核结果版本不一致');for(const [id,status]of bundle.stoppedAdvice)stoppedResult(value,id,status);bundle.value=value;bundle.expanded.add(requestId);render();say('已重新核对当前权限与版本，展示本次完整正常候选。');}catch(error){if(current(ticket)){if(Number(error?.status||error?.code)!==429){analysis=null;results.replaceChildren();resultButtons=[];}fail(error);}}finally{finish(ticket);}}
  async function select(candidate,result,bundle){if(!guard()||assessment||busy||analysis!==bundle)return;const {ticket,signal}=start('select');try{const value=await context.api(`${ROOT}/selection`,{method:'POST',body:{analysis_id:bundle.value.analysisId,request_id:result.requestId,candidate_id:candidate.candidateId},quiet:true,signal});if(!current(ticket)||analysis!==bundle)return;if(value?.analysisId!==bundle.value.analysisId||value.snapshotId!==bundle.value.snapshotId||value.requestId!==result.requestId||value.candidateId!==candidate.candidateId||value.status!=='SHORTLIST_ONLY'||value.assignmentCreated!==false||value.persisted!==false)throw Error('本页备选回执无法核对');selected.add(`${result.requestId}:${candidate.candidateId}`);render();say(`${candidate.name}：${value.message}`);}catch(error){if(current(ticket))fail(error);}finally{finish(ticket);}}
  function assessmentView(value){if(!value||!integer(value.revision)||typeof value.assessment_id!=='string'||value.revision===0&&value.facts!==null||value.revision>0&&(!value.facts||!text(value.recorded_by)||!text(value.recorded_at)))throw Error('核对记录无法核实');return value;}
  function validateExperienceReview(value){
    if(!exactKeys(value,['schema_version','confirmed','reviewed_entire_requirement','reviewed_all_sources','requirement','source_manifest','claims'])||value.schema_version!=='experience_review_v1'||value.confirmed!==true||value.reviewed_entire_requirement!==true||value.reviewed_all_sources!==true)throw Error('请明确完成需求和全部来源的人工核对');
    const q=value.requirement;if(!exactKeys(q,['topic','audience','depth','limits'])||!AI_TOPICS.has(q.topic)||!EXPERIENCE.audience.includes(q.audience)||!EXPERIENCE.depth.includes(q.depth)||!validLimits(q.limits,EXPERIENCE.requirementLimits))throw Error('请明确选择单课程需求的对象、深度和限制');
    if(q.limits.includes('INTRODUCTORY_ONLY')&&(q.depth==='ADVANCED'||q.limits.includes('ADVANCED_ONLY'))||q.limits.includes('ADVANCED_ONLY')&&q.depth==='INTRODUCTORY')throw Error('需求深度与限制相互矛盾');
    if(!Array.isArray(value.source_manifest)||value.source_manifest.length>8||!Array.isArray(value.claims)||value.claims.length>4)throw Error('经历来源或声明数量超出范围');
    const sources=new Map();for(const e of value.source_manifest){if(!exactKeys(e,['evidence_id','source_version'])||typeof e.evidence_id!=='string'||!/^ev:[a-f0-9]{64}$/.test(e.evidence_id)||!boundedText(e.source_version,128)||sources.has(e.evidence_id))throw Error('经历来源编号或版本无效');sources.set(e.evidence_id,e.source_version);}
    const seen=new Set();for(const c of value.claims){if(!exactKeys(c,['evidence_id','source_version','topic','role','assertion','audience','depth','limits','confirmed'])||c.confirmed!==true||c.topic!==q.topic||sources.get(c.evidence_id)!==c.source_version||!validExperienceClaim(c))throw Error('请逐行明确课程、来源、角色、陈述性质、对象、深度和限制并确认归属');const signature=JSON.stringify(stableValue(c));if(seen.has(signature))throw Error('经历声明重复');seen.add(signature);}
    const overlaps=(a,b)=>a===b||a==='UNKNOWN'||b==='UNKNOWN';
    for(const denied of value.claims.filter(c=>c.assertion==='DENIED'))for(const affirmed of value.claims.filter(c=>c.assertion==='AFFIRMED'))if((denied.role===affirmed.role||denied.role==='EXPERIENCE'||denied.role==='TEACHING'&&affirmed.role==='LEAD_TEACHING')&&overlaps(denied.audience,affirmed.audience)&&overlaps(denied.depth,affirmed.depth))throw Error('本课程存在范围重叠的肯定与否定，请先人工澄清');
    return value;
  }
  function experienceData(value,candidate){
    if(!Object.hasOwn(value,'experience_sources')&&!Object.hasOwn(value,'experience_review_status')&&!Object.hasOwn(value,'experience_review'))return null;
    const status=value.experience_review_status,sources=value.experience_sources,review=value.experience_review??null;if(!['NONE','CURRENT','STALE','UNAVAILABLE'].includes(status)||!Array.isArray(sources)||sources.length>8)throw Error('经历来源回执无法核实');
    const ids=new Set();for(const e of sources){if(!exactKeys(e,['evidenceId','candidateId','kind','title','sourceRef','sourceVersion','text','verifiedQualification'])||typeof e.evidenceId!=='string'||!/^ev:[a-f0-9]{64}$/.test(e.evidenceId)||ids.has(e.evidenceId)||e.candidateId!==candidate.candidateId||!['PROFILE_TOPIC','HISTORY_TOPIC'].includes(e.kind)||!boundedText(e.title,200)||!boundedText(e.sourceRef,200)||!boundedText(e.sourceVersion,128)||!boundedText(e.text,1600)||e.verifiedQualification!==false)throw Error('经历完整来源的归属、版本或内容无法核实');ids.add(e.evidenceId);}
    if(review!==null)validateExperienceReview(review);
    if(status==='CURRENT'){if(!review||JSON.stringify([...review.source_manifest].map(e=>`${e.evidence_id}:${e.source_version}`).sort())!==JSON.stringify(sources.map(e=>`${e.evidenceId}:${e.sourceVersion}`).sort()))throw Error('已确认经历的完整来源版本不一致');}
    if(status==='NONE'&&review!==null)throw Error('经历确认状态不一致');return{status,sources,review};
  }
  async function openAssessment(candidate,input,bundle){
    if(!guard()||assessment||busy||analysis!==bundle||!candidate.canRecordAssessment)return;const {ticket,signal}=start('assessment-read');
    try{const value=assessmentView(await context.api(`${ROOT}/assessments/read`,{method:'POST',body:{input:copy(input),teacher_id:candidate.teacherId},quiet:true,signal}));if(!current(ticket)||analysis!==bundle)return;const experience=experienceData(value,candidate);assessment={candidate,input:copy(input),view:value,experienceData:experience,fields:{},actions:[],dirty:false,unknown:false,retry:null};renderAssessment();}
    catch(error){if(current(ticket)){clearAssessment();fail(error);}}finally{finish(ticket);}
  }
  const toLocal=value=>{if(!value)return '';const d=new Date(value);if(!Number.isFinite(d.getTime()))throw Error('核对日期无效');return new Date(d.getTime()-d.getTimezoneOffset()*60000).toISOString().slice(0,16);};
  function renderAssessment(){
    const a=assessment;if(!a)return;assessmentArea.replaceChildren();const section=el('section',undefined,'card'),form=el('form'),grid=el('div',undefined,'form-grid');a.notice=el('p');a.notice.setAttribute('role','status');a.leave=el('div');section.append(el('h3',`本次人工核对 · ${a.candidate.name}`),el('p','有权操作人本次核实记录；不创建课程资格，不代替教师本人签认，也不会通知或派课。','recommend-notice'),el('p',inputLabel(a.input)),a.notice,a.leave);
    if(a.view.revision)section.append(el('p',`当前修订 ${a.view.revision} · 记录人 ${a.view.recorded_by} · ${a.view.recorded_at}`),el('p',`原修改理由：${a.view.reason||''}`));else section.append(el('p','尚无本次核对记录。请依据已经完成的核实填写，默认均为待核实。'));
    form.append(grid);const f=a.fields,v=a.view.facts||{};
    for(const [name,label]of [['service','本次服务条件'],['availability','完整档期'],['travel_feasibility','本次完整出行可达']])field(grid,f,name,label,'select',CONFIRMATIONS).value=v[name]||'UNKNOWN';
    for(const [name,label]of [['available_from','可用开始（本地时间）'],['available_until','可用结束（本地时间）']])field(grid,f,name,label,'datetime-local').value=toLocal(v[name]);
    field(grid,f,'transport_mode','交通方式','select',[['UNKNOWN','待核实'],['LOCAL','本地出行'],['RAIL','高铁'],['AIR','飞机'],['OTHER','其他']]).value=v.transport_mode||'UNKNOWN';
    field(grid,f,'departure_place','本次实际出发地').value=v.departure_place||'';field(grid,f,'teaching_place','本次授课地点').value=a.input.teaching_place;f.teaching_place.readOnly=true;
    if(a.input.mode!=='ONLINE'){field(grid,f,'reference_city','授课城市（查交通参考表用，例如：杭州）').maxLength=64;a.travelButton=button('按交通参考表预填出行信息','assessment-travel-reference',()=>prefillTravel(a));grid.append(a.travelButton);}
    for(const [name,label]of [['leg_minutes','车程或航程（分钟）'],['door_to_door_minutes','门到门时长（分钟）'],['before_buffer_minutes','课前出行缓冲（分钟）'],['after_buffer_minutes','课后出行缓冲（分钟）']]){field(grid,f,name,label,'number').value=v[name]??'';f[name].min='0';f[name].step='1';}
    field(grid,f,'travel_cost','已核交通费用（未知留空）').value=v.travel_cost??'';field(grid,f,'currency','费用币种').value=v.currency||'';f.currency.maxLength=3;
    field(grid,f,'cost_coverage','费用覆盖范围','select',[['','未知'],['ONE_WAY','单程'],['ROUND_TRIP','往返'],['TOTAL_REQUEST','覆盖本次整段请求']]).value=v.cost_coverage||'';
    field(grid,f,'distance_km','实际出发地至授课地点距离（公里）').value=v.distance_km??'';
    for(const [name,label]of [['source_observed_at','实际核实时间（本地时间）'],['valid_until','核对有效至（本地时间）']])field(grid,f,name,label,'datetime-local').value=toLocal(v[name]);
    field(grid,f,'source_note','本次事实说明','textarea').value=v.source_note||'';f.source_note.maxLength=1000;field(grid,f,'reason','本次保存理由','textarea').maxLength=1000;
    form.append(el('p','空白表示未知，0 表示已明确核实为零。确认可用窗口须覆盖授课及已知出行缓冲；线上无需填写出行。单程报价不当整次费用，未知不作最低成本判断。'));
    renderExperienceSection(a,form);
    const actions=el('div',undefined,'toolbar'),save=el('button','保存本次核对记录','btn');save.type='submit';save.setAttribute('data-recommend-action','assessment-save');const close=button('关闭核对','assessment-close',()=>closeAssessment());a.actions=[save,close,...(a.travelButton?[a.travelButton]:[])];a.retryButton=button('核对同一次保存结果','assessment-retry',()=>saveAssessment(true));a.retryButton.hidden=true;actions.append(save,close,a.retryButton);form.append(actions);section.append(form);assessmentArea.append(section);
    const edited=event=>{if(assessment===a&&!busy&&!a.unknown){a.dirty=true;experienceEdited(a,event.target);}};form.addEventListener('input',edited);form.addEventListener('change',edited);form.addEventListener('submit',event=>{event.preventDefault();return saveAssessment(false);});controls();
  }
  function experienceEdited(a,target){const exp=a.experience;if(!exp||target===exp.finalConfirm)return;if(exp.finalConfirm)exp.finalConfirm.checked=false;if(Object.values(exp.fields).includes(target)||exp.requirementLimits?.some(v=>v.input===target))exp.reviewRequirement&&(exp.reviewRequirement.checked=false);for(const row of exp.rows)if(row.controls.includes(target)&&target!==row.confirmed)row.confirmed.checked=false;}
  function experienceCheckbox(a,parent,name,label){const wrap=el('label',undefined,'recommend-budget-toggle'),input=el('input');input.type='checkbox';input.name=name;input.checked=false;input.setAttribute('aria-label',label);wrap.append(input,el('span',label));parent.append(wrap);a.experience.controls.push(input);return input;}
  function experienceSelect(a,parent,target,name,label,values){const control=field(parent,target,name,label,'select',[['','请选择，尚未确认'],...values.map(v=>[v,EXPERIENCE_LABELS[v]||v])]);a.experience.controls.push(control);return control;}
  function experienceLimits(a,parent,prefix,label,values){const group=el('fieldset'),items=[];group.append(el('legend',label+'（至少一项；未知或未记录须单独选择）'));parent.append(group);for(const value of values)items.push({value,input:experienceCheckbox(a,group,prefix+'_'+value,EXPERIENCE_LABELS[value])});return items;}
  function experienceSummary(claim){return `${claim.topic}：${EXPERIENCE_LABELS[claim.role]} / ${EXPERIENCE_LABELS[claim.assertion]}；对象 ${EXPERIENCE_LABELS[claim.audience]}；深度 ${EXPERIENCE_LABELS[claim.depth]}；限制 ${claim.limits.map(v=>EXPERIENCE_LABELS[v]).join('、')}`;}
  function renderExperienceSection(a,form){
    const data=a.experienceData;if(!data)return;const section=el('section',undefined,'card');section.append(el('h4','可选：本次人工确认课程经历'),el('p','保存会提交本表单全部已核信息，请重新核对服务、档期、出行及有效时间；不会因确认经历而自动续期旧交通事实。此记录不生成课程资格，不是教师本人签认；保存不调用 AI。','recommend-notice'));
    const stateText={NONE:'尚未确认经历。原文仅供本地人工核对，不进入模型。',CURRENT:'已有当前有效的经历确认；本次保存须明确保留、重新确认或清除。',STALE:'已有经历确认已失效，不能继续外发；请重新确认或明确清除。',UNAVAILABLE:'无法完整显示当前来源，未截断或省略；本次不能确认经历，仍可保存服务和交通事实。'};section.append(el('p',stateText[data.status]));
    const original=el('details');original.append(el('summary',`完整来源 · ${data.sources.length} 份（未核原文，不出站）`));for(const [index,e]of data.sources.entries()){const block=el('section');block.append(el('b',`来源 ${index+1} · ${e.title} · ${e.sourceRef}`));const raw=el('p',e.text);raw.style.whiteSpace='pre-wrap';block.append(raw);original.append(block);}section.append(original);
    if(data.review){const stored=el('details');stored.append(el('summary','查看已保存的人工确认（不自动带入新确认）'),el('p',`单课程需求：${data.review.requirement.topic}；对象 ${EXPERIENCE_LABELS[data.review.requirement.audience]}；深度 ${EXPERIENCE_LABELS[data.review.requirement.depth]}；限制 ${data.review.requirement.limits.map(v=>EXPERIENCE_LABELS[v]).join('、')}`));for(const c of data.review.claims)stored.append(el('p',`${experienceSummary(c)} · ${c.evidence_id}`));if(!data.review.claims.length)stored.append(el('p','已核来源但未记录结构化经历声明；不据此判断能力。'));section.append(stored);}
    const exp={data,controls:[],buttons:[],rows:[],sequence:0,fields:{},body:el('div')};a.experience=exp;
    const choices=[['',data.review?'请选择如何处理已有经历确认':'本次不确认经历（默认）']];if(data.status==='CURRENT')choices.push(['KEEP','明确保留当前已确认经历']);if(data.status!=='UNAVAILABLE')choices.push(['CONFIRM','逐项填写并确认结构化经历']);if(data.review)choices.push(['CLEAR','明确清除已保存的经历确认']);
    exp.mode=field(section,{},'experience_mode','经历确认处理方式','select',choices);exp.controls.push(exp.mode);section.append(exp.body);form.append(section);exp.mode.addEventListener('change',()=>{if(assessment!==a||busy||a.unknown)return;a.dirty=true;renderExperienceMode(a);});
  }
  function renderExperienceMode(a){
    const exp=a.experience,{data}=exp;exp.body.replaceChildren();exp.controls=[exp.mode];exp.buttons=[];exp.rows=[];exp.fields={};exp.requirementLimits=[];exp.reviewRequirement=exp.reviewSources=exp.finalConfirm=null;
    if(exp.mode.value==='KEEP'){exp.finalConfirm=experienceCheckbox(a,exp.body,'experience_keep_confirm','我已重新核对完整需求、全部来源及原经历事实，明确保留当前确认；本表服务和出行事实也已重新核对。');}
    else if(exp.mode.value==='CLEAR'){exp.finalConfirm=experienceCheckbox(a,exp.body,'experience_clear_confirm','明确清除经历确认，仅保存本表服务、档期和出行事实；原文不会据此进入模型。');}
    else if(exp.mode.value==='CONFIRM'&&data.status!=='UNAVAILABLE'){
      exp.body.append(el('p','请逐项明确选择；不从原文推断，不把第三人、疑问句或未说明内容自动当作候选事实。仅支持一个课程，多个课程请分别核对。'),el('p',`本次完整课程主题/需求：${a.input.topic}`));const grid=el('div',undefined,'form-grid');exp.body.append(grid);
      experienceSelect(a,grid,exp.fields,'experience_topic','人工确认的单课程',[...AI_TOPICS]);experienceSelect(a,grid,exp.fields,'experience_audience','该课程需求的对象',EXPERIENCE.audience);experienceSelect(a,grid,exp.fields,'experience_depth','该课程需求的深度',EXPERIENCE.depth);exp.requirementLimits=experienceLimits(a,exp.body,'experience_requirement_limit','该课程需求的限制',EXPERIENCE.requirementLimits);
      exp.reviewRequirement=experienceCheckbox(a,exp.body,'experience_entire_requirement','我已核对完整需求，所选单课程、对象、深度和限制能完整表示本次需求；无法表示时不确认。');exp.reviewSources=experienceCheckbox(a,exp.body,'experience_all_sources','我已阅读全部来源，核对主体归属、事件范围、否定及限制；未录入的来源不代表没有能力或经历。');
      exp.body.append(el('p','每行仅记录同一来源中的一个课程经历事件。入门主讲与进阶学员须分行，不可合并。可以不添加行，表示已核来源但未记录该课程声明。'));exp.rowsArea=el('div');exp.body.append(exp.rowsArea);const add=button('添加一条经历声明','experience-add-claim',()=>{if(assessment!==a||busy||a.unknown||exp.rows.length>=4)return;a.dirty=true;if(exp.finalConfirm)exp.finalConfirm.checked=false;addExperienceClaim(a);controls();});exp.buttons.push({item:add,enabled:()=>exp.mode.value==='CONFIRM'&&exp.rows.length<4&&data.sources.length>0});exp.body.append(add);
      exp.finalConfirm=experienceCheckbox(a,exp.body,'experience_confirm','我明确确认以上结构化记录属于本候选、本课程及所列来源，不是自动推断或课程资格认证。');
    }controls();
  }
  function addExperienceClaim(a){
    const exp=a.experience,id=++exp.sequence,row={id,fields:{},controls:[],container:el('section',undefined,'card')},prefix=`experience_claim_${id}_`;exp.rows.push(row);row.container.append(el('h4',`经历声明 ${id}`));const grid=el('div',undefined,'form-grid');row.container.append(grid);const start=exp.controls.length;
    const source=field(grid,row.fields,prefix+'source','本行来源','select',[['','请选择完整来源'],...exp.data.sources.map((e,i)=>[e.evidenceId,`来源 ${i+1} · ${e.title} · ${e.sourceRef}`])]);exp.controls.push(source);row.source=source;
    for(const [name,label]of [['role','本行角色'],['assertion','对该角色的陈述性质'],['audience','本行经历对象'],['depth','本行经历深度']])row[name]=experienceSelect(a,grid,row.fields,prefix+name,label,EXPERIENCE[name]);row.limits=experienceLimits(a,row.container,prefix+'limit','本行经历限制',EXPERIENCE.limits);row.confirmed=experienceCheckbox(a,row.container,prefix+'confirmed','我已核对本行是本候选、本课程及同一经历事件，否定和限制没有遗漏。');row.controls=exp.controls.slice(start);
    const remove=button('移除此行','experience-remove-claim',()=>{if(assessment!==a||busy||a.unknown)return;a.dirty=true;if(exp.finalConfirm)exp.finalConfirm.checked=false;exp.rows=exp.rows.filter(r=>r!==row);exp.controls=exp.controls.filter(c=>!row.controls.includes(c));exp.buttons=exp.buttons.filter(b=>b.item!==remove);row.container.remove();controls();});exp.buttons.push({item:remove,enabled:()=>true});row.container.append(remove);exp.rowsArea.append(row.container);
  }
  function experienceBody(a){
    const exp=a.experience;if(!exp)return undefined;const {data}=exp,mode=exp.mode.value;
    if(!mode){if(data.review)throw Error('已有经历确认，请明确选择保留、重新确认或清除；不能默默省略');return undefined;}
    if(mode==='CLEAR'){if(!data.review||!exp.finalConfirm?.checked)throw Error('请明确确认清除已有经历记录');return null;}
    if(mode==='KEEP'){if(data.status!=='CURRENT'||!data.review||!exp.finalConfirm?.checked)throw Error('请重新核对并明确确认保留当前经历记录');return copy(validateExperienceReview(data.review));}
    if(mode!=='CONFIRM'||data.status==='UNAVAILABLE')throw Error('完整来源当前无法核实，不能确认经历');
    const getLimits=items=>items.filter(item=>item.input.checked).map(item=>item.value),topic=exp.fields.experience_topic.value;
    const review={schema_version:'experience_review_v1',confirmed:!!exp.finalConfirm?.checked,reviewed_entire_requirement:!!exp.reviewRequirement?.checked,reviewed_all_sources:!!exp.reviewSources?.checked,requirement:{topic,audience:exp.fields.experience_audience.value,depth:exp.fields.experience_depth.value,limits:getLimits(exp.requirementLimits)},source_manifest:data.sources.map(e=>({evidence_id:e.evidenceId,source_version:e.sourceVersion})),claims:exp.rows.map(row=>{const source=data.sources.find(e=>e.evidenceId===row.source.value);return{evidence_id:row.source.value,source_version:source?.sourceVersion||'',topic,role:row.role.value,assertion:row.assertion.value,audience:row.audience.value,depth:row.depth.value,limits:getLimits(row.limits),confirmed:!!row.confirmed.checked};})};return validateExperienceReview(review);
  }
  function closeAssessment(){if(!guard()||!assessment||busy)return;const a=assessment;if(a.unknown){a.notice.textContent='保存结果尚未核实，请先核对同一次结果。';return;}if(!a.dirty){clearAssessment();return;}a.leave.replaceChildren(el('p','尚有未保存的核对内容。'),button('继续编辑','assessment-keep',()=>a.leave.replaceChildren()),button('放弃并关闭','assessment-discard',()=>{if(assessment===a&&!busy&&!a.unknown)clearAssessment();}));}
  function factsBody(a){
    const f=a.fields,iso=(name,optional)=>{const value=f[name].value;if(!value){if(optional)return null;throw Error('请填写实际核实时间与有效期');}const d=new Date(value);if(!Number.isFinite(d.getTime()))throw Error('日期时间无效');return d.toISOString();};
    const number=name=>{const value=f[name].value;if(value==='')return null;if(!/^\d+$/.test(value)||!Number.isSafeInteger(Number(value))||Number(value)>20160)throw Error('时长和缓冲须为 0 至 20160 的整数或留空');return Number(value);};
    const confirmations={};for(const name of ['service','availability','travel_feasibility']){if(!CONFIRMATIONS.some(([id])=>id===f[name].value))throw Error('请选择有效核对状态');confirmations[name]=f[name].value;}
    const from=iso('available_from',true),until=iso('available_until',true),observed=iso('source_observed_at',false),valid=iso('valid_until',false),now=context.now?.()??Date.now();
    if((from===null)!==(until===null)||from&&Date.parse(from)>=Date.parse(until)||Date.parse(observed)>now||Date.parse(valid)<=now||Date.parse(observed)>=Date.parse(valid))throw Error('核对时间必须已经发生，且有效期和可用时段完整有效');
    if(confirmations.availability==='CONFIRMED'&&!from)throw Error('操作人确认空闲须填写完整可用窗口');
    const cost=f.travel_cost.value.trim(),currency=f.currency.value.trim().toUpperCase(),coverage=f.cost_coverage.value,distance=f.distance_km.value.trim();
    if(cost&&!/^\d{1,7}(?:\.\d{1,2})?$/.test(cost)||cost&&(!/^[A-Z]{3}$/.test(currency)||!['ONE_WAY','ROUND_TRIP','TOTAL_REQUEST'].includes(coverage))||!cost&&(currency||coverage))throw Error('请明确费用金额、币种和覆盖范围；未知费用应全部留空');
    if(distance&&!/^\d{1,6}(?:\.\d{1,3})?$/.test(distance))throw Error('距离须为明确公里数或留空');
    const leg=number('leg_minutes'),door=number('door_to_door_minutes');if(leg!==null&&door!==null&&door<leg)throw Error('门到门时长不能小于车程或航程');
    if(!f.source_note.value.trim()||f.source_note.value.length>1000||!f.reason.value.trim()||f.reason.value.length>1000)throw Error('请填写不超过 1000 字的事实说明及保存理由');
    return{...confirmations,available_from:from,available_until:until,transport_mode:f.transport_mode.value,departure_place:f.departure_place.value.trim(),teaching_place:a.input.teaching_place,leg_minutes:leg,door_to_door_minutes:door,before_buffer_minutes:number('before_buffer_minutes'),after_buffer_minutes:number('after_buffer_minutes'),travel_cost:cost||null,currency,cost_coverage:coverage,distance_km:distance||null,distance_coverage:distance?'DEPARTURE_TO_TEACHING':'',source_observed_at:observed,valid_until:valid,source_note:f.source_note.value.trim(),source_kind:'STAFF_RECORDED'};
  }
  // Pre-fill only: values land in the form; nothing is stored until staff review them and press save.
  const TRAVEL_KEYS=['travel_feasibility','transport_mode','departure_place','leg_minutes','door_to_door_minutes','before_buffer_minutes','after_buffer_minutes','travel_cost','currency','cost_coverage','valid_until','source_note'];
  function applyTravel(a,s){
    if(!exactKeys(s,TRAVEL_KEYS)||s.travel_feasibility!=='CONFIRMED'||!['LOCAL','RAIL','AIR'].includes(s.transport_mode)||!boundedText(s.departure_place,200)||!boundedText(s.source_note,1000))throw Error('交通参考回执无法核实');
    for(const k of ['leg_minutes','door_to_door_minutes','before_buffer_minutes','after_buffer_minutes'])if(!Number.isSafeInteger(s[k])||s[k]<0||s[k]>20160)throw Error('交通参考回执无法核实');
    const f=a.fields,limit=Date.parse(s.valid_until);if(!Number.isFinite(limit))throw Error('交通参考回执无法核实');
    for(const k of ['travel_feasibility','transport_mode','departure_place','leg_minutes','door_to_door_minutes','before_buffer_minutes','after_buffer_minutes'])f[k].value=String(s[k]);
    f.travel_cost.value=s.travel_cost??'';f.currency.value=s.currency||'';f.cost_coverage.value=s.cost_coverage||'';
    if(!f.source_observed_at.value)f.source_observed_at.value=toLocal(new Date(context.now?.()??Date.now()).toISOString());
    const existing=f.valid_until.value?new Date(f.valid_until.value).getTime():NaN;if(!Number.isFinite(existing)||existing>limit)f.valid_until.value=toLocal(s.valid_until);
    const note=f.source_note.value.trim();if(!note.includes(s.source_note))f.source_note.value=(note?note+'\n':'')+s.source_note;
  }
  async function prefillTravel(a){
    if(!guard()||assessment!==a||busy||a.unknown)return;const city=a.fields.reference_city.value.trim();if(!city){a.notice.textContent='请先填写授课城市。';return;}
    const {ticket,signal}=start('assessment-travel');
    try{const value=await context.api(`${ROOT}/assessments/travel-reference`,{method:'POST',body:{input:copy(a.input),teacher_id:a.candidate.teacherId,destination_city:city},quiet:true,signal});if(!current(ticket)||assessment!==a)return;
      if(!value||typeof value.status!=='string'||!boundedText(value.message,500))throw Error('交通参考回执无法核实');
      if(value.suggestion){applyTravel(a,value.suggestion);a.dirty=true;}
      a.notice.textContent=value.message;}
    catch(error){if(current(ticket)&&assessment===a)a.notice.textContent=error.message||'交通参考暂不可用，请手工填写。';}
    finally{finish(ticket);}
  }
  async function saveAssessment(retry){
    if(!guard()||!assessment||busy)return;const a=assessment;if(a.unknown&&!retry)return;
    try{if(!retry){const facts=factsBody(a),experience=experienceBody(a),id=context.newRequestId?.()??globalThis.crypto?.randomUUID?.();if(!id||!/^[A-Za-z0-9][A-Za-z0-9._:-]{7,95}$/.test(id))throw Error('无法生成本次保存编号，请重新打开核对');a.retry={input:copy(a.input),teacher_id:a.candidate.teacherId,expected_revision:a.view.revision,request_id:id,facts,reason:a.fields.reason.value.trim()};if(experience!==undefined)a.retry.experience_review=experience;}if(!a.retry)return;}catch(error){a.notice.textContent=error.message;return;}
    a.leave.replaceChildren();const {ticket,signal}=start('assessment-save');a.notice.textContent=retry?'正在核对同一次保存结果…':'正在保存本次核对…';
    try{const response=await context.api(`${ROOT}/assessments/save`,{method:'POST',body:copy(a.retry),quiet:true,signal});if(!current(ticket)||assessment!==a)return;const result=assessmentView(response?.result);if(typeof response.replayed!=='boolean'||!integer(response.current_revision)||response.current_revision<result.revision||result.revision!==a.retry.expected_revision+1||a.view.assessment_id&&result.assessment_id!==a.view.assessment_id||!text(result.assessment_id))throw Error('保存回执未能核实');clearAssessment();invalidate('本次核对已保存。旧分析已清除，请重新核对候选；没有创建资格、通知、锁定或派课。');}
    catch(error){if(!current(ticket)||assessment!==a)return;const code=Number(error?.status||error?.code);if(code===401||code===403){fail(error);return;}if([400,404,409,413].includes(code)){a.unknown=false;a.retry=null;a.retryButton.hidden=true;if(code===409){clearAssessment();invalidate();say('核对依据或版本已变化，请刷新范围后重新读取。');}else a.notice.textContent=error.message||'本次保存未通过，请核对填写内容。';}else{a.unknown=true;a.retryButton.hidden=false;a.notice.textContent='本次保存结果未知。保留原内容和编号，请核对同一次结果；不能换号盲重试。';}}
    finally{finish(ticket);}
  }
  form.addEventListener('input',event=>{if(!guard()||assessment)return;if(event.target!==fields.scope)invalidate('条件已修改，请重新核对候选。');});
  form.addEventListener('change',event=>{if(!guard()||assessment)return;if(event.target===fields.scope)readScope();else invalidate('条件已修改，请重新核对候选。');});
  form.addEventListener('submit',event=>{event.preventDefault();return analyze(false);});
  function destroy(){if(!alive)return;alive=false;epoch++;controller?.abort();clearAiWait();analysis=snapshot=assessment=null;basket=[];contexts=[];selected.clear();resultButtons=[];context.signal?.removeEventListener('abort',destroy);panel.replaceChildren();panel.remove();}
  context.signal?.addEventListener('abort',destroy,{once:true});return{ready:refresh(),refresh,destroy};
}
