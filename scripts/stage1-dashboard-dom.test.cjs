'use strict';
// Actual dashboard function/module, existing repository DOM substitute, real API-produced synthetic DTOs.
const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm'),assert=require('node:assert/strict');
// Usage: node scripts/stage1-dashboard-dom.test.cjs REPO TEMP_OUTPUT
if(process.argv.length!==4)throw Error('Repository and temporary output directory required');
const app=fs.realpathSync(process.argv[2]),out=fs.realpathSync(process.argv[3]);
const outputRelative=path.relative(app,out);
if(!outputRelative||(!path.isAbsolute(outputRelative)&&outputRelative!=='..'&&!outputRelative.startsWith('..'+path.sep)))throw Error('Output must be outside the repository');
const source=fs.readFileSync(path.join(app,'web/app.js'),'utf8');
const from=source.indexOf('  async function pageDashboard(c) {'),to=source.indexOf('  // ============ 师资调度 ============',from);
assert.ok(from>0&&to>from);
const dashboard=source.slice(from,to).replace("import('/modules/dashboard/formal-settlement.js?v=20261026stage1')",'loadDashboardModule()');
const support=fs.readFileSync(path.join(app,'scripts/M01-account-bindings-check.mjs'),'utf8');
const {NodeStub}=vm.runInNewContext(support.slice(support.indexOf('const decode ='),support.indexOf('function fixture()'))+'\n({NodeStub})');
const fixtures=JSON.parse(fs.readFileSync(path.join(out,'project-api-evidence.json'),'utf8'));
let checks=0;
const check=(value,message)=>{assert.ok(value,message);checks++;};
const eq=(a,b,message)=>{assert.deepEqual(a,b,message);checks++;};
const escape=s=>String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
(async()=>{
 // Preserve the product source exactly while loading ESM on Node 18+ without package.json assumptions.
 const moduleSource=fs.readFileSync(path.join(app,'web/modules/dashboard/formal-settlement.js'),'utf8');
 const module=await import('data:text/javascript;base64,'+Buffer.from(moduleSource).toString('base64'));
 const doc=new NodeStub('document'),content=new NodeStub('main',{id:'content'});doc.children.push(content);
 const data={projects:[],overview:{project_amount:1000,received:500,fee_amount:0,cost_amount:0,profit:999999}};
 const calls=[];let moduleFailure=false;
 const sandbox={console,Date,Intl,Map,Set,document:doc,state:{user:{id:2},page:'dashboard'},routeEpoch:1,
  $:(s,r=doc)=>r.querySelector(s),$$:(s,r=doc)=>r.querySelectorAll(s),esc:escape,
  money:x=>Number(x||0).toFixed(2),num:x=>Number(x||0),countTag:(x,f)=>f==='money'?'¥ '+Number(x).toFixed(2):String(x??0),
  icon:()=>'',businessArt:()=>'',refreshIcons:()=>{},canWrite:()=>false,readonlyAction:()=>'',workflowBindingNotice:()=>'',workflowQueueTasks:()=>[],projectCompletedHours:()=>0,deliveryHoursText:String,
  mergeContextLabels:x=>x.join(' / '),taskArtKind:()=>'',navigateTo:()=>{},quickCreateDemand:()=>{},
  loadDashboardModule:async()=>{if(moduleFailure)throw Error('Synthetic module failure');return module;},
  isRouteCurrent:(epoch,c,page)=>epoch===sandbox.routeEpoch&&sandbox.state.page===page&&c.isConnected,
  api:async p=>{calls.push(p);if(p==='/stats/overview')return data.overview;if(p==='/projects')return data.projects;if(p==='/approvals/tasks')return {tasks:[]};if(p==='/organization/me')return {};return [];}
 };
 vm.createContext(sandbox);vm.runInContext(dashboard,sandbox);
 const render=async(projects,overview={})=>{data.projects=projects;Object.assign(data.overview,overview);await sandbox.pageDashboard(content);};
 const finance=()=>content.querySelector('[data-dashboard-formal-settlement]');
 const values=()=>Array.from(content.querySelectorAll('[data-dashboard-financial]'),node=>node.querySelector('b').textContent);
 for(const [name,expected] of [['paid',['¥ 133.00','¥ 133.00','¥ 0.00']],['unpaid',['¥ 133.00','¥ 0.00','¥ 133.00']],['correctedBeforeRefund',['¥ 80.00','¥ 133.00','¥ -53.00']],['refunded',['¥ 80.00','¥ 80.00','¥ 0.00']]]){
  await render(fixtures[name]);eq(values(),expected,name+' amount from real project API');check(finance().getAttribute('data-status')==='AVAILABLE',name+' valid coverage');
 }
 await render(fixtures.paid,{fee_amount:25,cost_amount:7});
 check(content.textContent.includes('历史课酬记录')&&content.textContent.includes('其他已录成本'),'legacy fields separate');
 check(!content.textContent.includes('999999')&&!content.textContent.includes('估算余额')&&!content.textContent.includes('已录课酬与成本'),'no mixed cost/profit');
 eq(values(),['¥ 133.00','¥ 133.00','¥ 0.00'],'legacy amounts do not inflate formal amount');
 fs.writeFileSync(path.join(out,'dashboard-paid-markup.html'),content.innerHTML);
 check(finance().children.length===5&&finance().children.every(n=>n.classList.contains('v9-snap-cell')),'existing five-column classes');
 await render(fixtures.missingClaim);check(finance().getAttribute('data-status')==='PARTIAL','missing claim not complete');check(finance().textContent.includes('以上仅为已核对部分'),'partial amounts clearly labeled');eq(values(),['¥ 80.00','¥ 80.00','¥ 0.00'],'known subtotal kept');
 for(const name of ['deliveryDenied','limitedProject']){await render(fixtures[name]);eq(values(),['—','—','—'],name+' has no values');check(finance().textContent.includes('不能视为零'),name+' unknown not zero');}
 await render([...fixtures.paid,{id:12,delivery_controlled:true,delivery_settlement:null}]);check(finance().getAttribute('data-status')==='PARTIAL','mixed readability partial');check(finance().textContent.includes('1 个不可读取'),'unreadable coverage explicit');eq(values(),['¥ 133.00','¥ 133.00','¥ 0.00'],'same readable subset across totals');
 for(const mutate of [p=>p.read_access={limited:true},p=>p.read_access={limited:'true'},p=>p.read_access='unexpected',p=>p.delivery_settlement.known_balance='1.00',p=>p.delivery_settlement.known_confirmed_amount=null,p=>p.delivery_settlement.status='UNKNOWN',p=>p.delivery_settlement.known_paid_amount='1e2',p=>p.delivery_settlement.known_confirmed_amount='<img src=x onerror=alert(1)>',p=>p.delivery_settlement.project_id=999,p=>p.delivery_controlled='true',p=>p.delivery_settlement.missing_claim_count=1]){
  const rows=structuredClone(fixtures.paid);mutate(rows[0]);await render(rows);eq(values(),['—','—','—'],'malformed or restricted money rejected');
 }
 check(!content.querySelector('img'),'untrusted amount cannot create markup');
 await render([...fixtures.paid,...fixtures.paid]);eq(values(),['—','—','—'],'duplicate projects not double counted');
 await render([]);eq(values(),['—','—','—'],'empty visible set not zero');check(finance().textContent.includes('暂无接入'),'empty coverage explicit');
 await render([{id:99,delivery_controlled:false}]);eq(values(),['—','—','—'],'legacy-only set has no formal amount');
 const exact=structuredClone(fixtures.paid);Object.assign(exact[0].delivery_settlement,{confirmed_amount:'123456789012345678901234.01',known_confirmed_amount:'123456789012345678901234.01',paid_amount:'123456789012345678901234.02',known_paid_amount:'123456789012345678901234.02',balance:'-0.01',known_balance:'-0.01'});await render(exact);eq(values(),['¥ 123456789012345678901234.01','¥ 123456789012345678901234.02','¥ -0.01'],'long money exact');
 const offset=structuredClone(fixtures.unpaid);offset[0].id=12;offset[0].delivery_settlement.project_id=12;Object.assign(offset[0].delivery_settlement,{confirmed_amount:'0.00',known_confirmed_amount:'0.00',paid_amount:'133.00',known_paid_amount:'133.00',balance:'-133.00',known_balance:'-133.00'});await render([...fixtures.unpaid,...offset]);check(values()[2]==='¥ 0.00'&&finance().textContent.includes('不代表逐笔结清'),'net-zero does not claim all settled');
 const zero=structuredClone(fixtures.paid);for(const key of ['confirmed_amount','known_confirmed_amount','paid_amount','known_paid_amount','balance','known_balance'])zero[0].delivery_settlement[key]='0.00';await render(zero);eq(values(),['¥ 0.00','¥ 0.00','¥ 0.00'],'authorized complete zero is distinct from unavailable');
 const oldApi=sandbox.api;let release;sandbox.api=p=>p==='/projects'?new Promise(r=>{release=r;}):oldApi(p);
 let pending=sandbox.pageDashboard(content);sandbox.state.user={id:99};content.textContent='NEW-IDENTITY';release(fixtures.paid);await pending;eq(content.textContent,'NEW-IDENTITY','late identity response discarded');
 pending=sandbox.pageDashboard(content);sandbox.routeEpoch++;content.textContent='NEW-ROUTE';release(fixtures.paid);await pending;eq(content.textContent,'NEW-ROUTE','late route response discarded');sandbox.api=oldApi;
 moduleFailure=true;await render(fixtures.paid);check(content.textContent.includes('摘要组件未加载'),'module failure clearly unavailable');check(!finance(),'module failure never fabricates totals');
 check(calls.every(p=>['/stats/overview','/projects','/demands','/dispatches','/charges','/fees','/approvals/tasks','/organization/me'].includes(p)),'no new financial endpoint or hidden-amount requests');
 check(!source.slice(from,to).includes('countTag(d.profit'),'dashboard ignores legacy computed profit');
 fs.writeFileSync(path.join(out,'dashboard-dom-result.json'),JSON.stringify({ok:true,checks,scope:'actual pageDashboard function/module; repository DOM substitute; existing real Api.handle fixture DTOs',browser:false,visual_verified:false,external_network:false,production_touched:false},null,2)+'\n');
 console.log('Dashboard original-function DOM checks passed: '+checks);
})().catch(e=>{console.error(e);process.exitCode=1;});
