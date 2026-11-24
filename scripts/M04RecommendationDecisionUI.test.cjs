'use strict';
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
let checks=0;const check=(value,label)=>{assert.ok(value,label);checks++;};
class Node {
  constructor(tag,doc){this.tagName=tag.toUpperCase();this.ownerDocument=doc;this.children=[];this.listeners={};this.attributes={};this._text='';this._value=undefined;this.disabled=false;}
  set textContent(v){this._text=String(v);this.children=[];}get textContent(){return this._text+this.children.map(v=>v.textContent).join('');}
  set value(v){this._value=String(v);}get value(){return this._value??(this.tagName==='SELECT'?this.children[0]?.value:'')??'';}
  get options(){return this.children;}setAttribute(k,v){this.attributes[k]=String(v);}
  append(...nodes){for(const node of nodes){node.parentNode=this;this.children.push(node);}}
  replaceChildren(...nodes){this.children=[];this._text='';this._value=undefined;this.append(...nodes);}
  addEventListener(t,fn){(this.listeners[t]??=[]).push(fn);}remove(){if(this.parentNode)this.parentNode.children=this.parentNode.children.filter(v=>v!==this);this.parentNode=null;}
  all(test){return [...(test(this)?[this]:[]),...this.children.flatMap(n=>n.all(test))];}
  async emit(type){const event={target:this,currentTarget:this,preventDefault(){}};for(let node=this;node;node=node.parentNode){event.currentTarget=node;for(const fn of node.listeners[type]||[])await fn(event);}}
}
const deferred=()=>{let resolve;return {promise:new Promise(r=>resolve=r),resolve:v=>resolve(v)};};
const receipt=()=>({schemaVersion:'recommendation_decision_v1',rulePolicyStatus:'DISCUSSION_DRAFT',analysisId:'10000000-0000-0000-0000-000000000001',scopeId:1,catalogVersion:2,snapshotId:'s1',notice:'人工选择，不派课',model:{source:'RULES'},candidates:['READY_FOR_REVIEW','PENDING','EXCLUDED'].map((decision,i)=>({candidateId:`teacher:${i+1}`,name:`<script>候选${i+1}</script>`,decision,checks:[{message:'本次事实核验'}],reason:'规则内容线索',missingInfo:decision==='PENDING'?['资格未知']:[],topicReview:['没有历史不代表没有能力'],evidence:[{candidateId:`teacher:${i+1}`,title:'脱敏主题片段',sourceRef:'resume:1',text:'<img src=x onerror=alert(1)>沟通'}]}))});
async function harness(mount,override){
 const doc={createElement:tag=>new Node(tag,doc)},root=new Node('main',doc),calls=[],user={},signal=new AbortController();
 const normal=(url,options)=>{
  if(url==='/course-catalog/scopes')return {scopes:[{scope_id:1,organization_code:'合成机构',can_read:true},{scope_id:2,organization_code:'无权',can_read:false}]};
  if(url==='/course-catalog/scopes/1')return {scope_id:1,version:2,organization_code:'合成机构',bindings:[{teacher_id:1}],catalog:{courses:[{course_code:'C1',course_name:'旧范围课程',active:true}]}};
  if(url==='/teacher-recommendations/decision')return receipt();
  if(url.endsWith('/selection'))return {analysisId:options.body.analysis_id,candidateId:options.body.candidate_id,snapshotId:'s1',status:'SHORTLIST_ONLY',assignmentCreated:false,message:'仅本页备选'};
  throw new Error('unexpected route '+url);
 };
 const api=async(url,options={})=>{calls.push({url,options});return override?override(url,options,()=>normal(url,options)):normal(url,options);};
 const instance=mount(root,{api,getUser:()=>user,isCurrent:()=>true,signal:signal.signal});await instance.ready;
 const field=name=>root.all(n=>n.name===name)[0],button=text=>root.all(n=>n.tagName==='BUTTON'&&n.textContent===text)[0];
 return {root,calls,instance,field,button,signal,async choose(){field('scope').value='1';await field('scope').emit('change');},async submit(){field('topic').value='沟通';await root.all(n=>n.tagName==='FORM')[0].emit('submit');}};
}
(async()=>{
 const source=fs.readFileSync(path.join(__dirname,'../web/modules/teacher-recommendation/index.js'),'utf8');
 const {mountRecommendationDecision:mount}=await import('data:text/javascript;base64,'+Buffer.from(source).toString('base64'));
 const h=await harness(mount);check(h.field('scope').options.length===2,'only explicitly readable scope');
 check(h.root.textContent.includes('规则方案待确认'),'draft rule status visible');
 await h.choose();check(!h.button('核对候选条件').disabled,'bound manual course can analyze');
 await h.submit();const body=h.calls.find(c=>c.url==='/teacher-recommendations/decision').options.body;
 check(body.course_code===''&&body.topic==='沟通'&&body.expected_version===2,'manual topic independent of standard course');
 check(h.root.textContent.includes('当前为规则筛选结果，未调用AI模型'),'rules label not AI claim');
 check(h.root.textContent.includes('待确认 · 不代表没有能力 · 1 位'),'pending group separate');
 check(h.root.all(n=>n.tagName==='BUTTON'&&n.textContent==='核对并选入本页备选').length===2,'excluded has no shortlist action');
 check(h.root.all(n=>['SCRIPT','IMG'].includes(n.tagName)).length===0,'evidence and names rendered only as text');
 await h.button('核对并选入本页备选').emit('click');check(h.calls.at(-1).url.endsWith('/selection'),'manual selection goes through revalidation endpoint');
 check(h.calls.every(c=>!c.url.includes('/dispatch')),'no dispatch endpoint');
 await h.field('topic').emit('input');check(!h.root.textContent.includes('候选1'),'editing criteria clears earlier result');
 h.field('cities').value='合成甲城\n合成甲城';const before=h.calls.length;await h.submit();check(h.calls.length===before,'duplicate city constraint blocks request');
 h.instance.destroy();check(h.root.children.length===0,'destroy removes panel');
 let wait;const late=await harness(mount,(url,options,normal)=>url==='/teacher-recommendations/decision'?(wait=deferred()).promise:normal());await late.choose();
 const pending=late.submit();await Promise.resolve();await late.field('topic').emit('input');wait.resolve(receipt());await pending;check(!late.root.textContent.includes('候选1'),'late changed-input response discarded');late.instance.destroy();
 const denied=await harness(mount,(url,options,normal)=>{if(url.endsWith('/selection'))throw Object.assign(new Error('revoked'),{status:403});return normal();});await denied.choose();await denied.submit();await denied.button('核对并选入本页备选').emit('click');
 check(!denied.root.textContent.includes('旧范围课程')&&!denied.root.textContent.includes('候选1'),'revocation clears course names and candidate details');
 check(denied.field('scope').options.length===1&&denied.button('核对候选条件').disabled,'revocation clears scope and disables analysis');denied.instance.destroy();
 let firstChoice;const choices=await harness(mount,(url,options,normal)=>url.endsWith('/selection')&&options.body.candidate_id==='teacher:1'?(firstChoice=deferred()).promise:normal());await choices.choose();await choices.submit();
 const choicesButtons=choices.root.all(n=>n.tagName==='BUTTON'&&n.textContent==='核对并选入本页备选');const oldChoice=choicesButtons[0].emit('click');await choicesButtons[1].emit('click');firstChoice.resolve({analysisId:receipt().analysisId,candidateId:'teacher:1',snapshotId:'s1',status:'SHORTLIST_ONLY',assignmentCreated:false,message:'过时备选回执'});await oldChoice;check(!choices.root.textContent.includes('过时备选回执'),'older concurrent selection cannot overwrite newest selection');choices.instance.destroy();
 const empty=await harness(mount,(url,options,normal)=>url==='/course-catalog/scopes'?{scopes:[]}:normal());check(empty.root.textContent.includes('暂无明确获权范围'),'missing scope points to configuration without library fallback');empty.instance.destroy();
 const noCourse=await harness(mount,(url,options,normal)=>url==='/course-catalog/scopes/1'?{scope_id:1,version:2,bindings:[{teacher_id:1}],catalog:{courses:[]}}:normal());await noCourse.choose();await noCourse.submit();check(noCourse.calls.some(c=>c.url==='/teacher-recommendations/decision'),'manual topic works with zero standard courses');noCourse.instance.destroy();
 console.log(`M04RecommendationDecisionUI: ${checks} checks passed; synthetic DOM/transport only, no browser/server.`);
})().catch(error=>{console.error(error);process.exitCode=1;});
