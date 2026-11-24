'use strict';
// CURRENT contract, not a reinterpretation of the historical 55 recommendation assertions.
// Exact prior suite is retained in scripts/legacy/*.original; see docs/TEST_INVENTORY_20261110.md.
const fs=require('node:fs'),os=require('node:os'),path=require('node:path'),net=require('node:net');
const crypto=require('node:crypto'),assert=require('node:assert/strict'),{spawn}=require('node:child_process');
const args=Object.fromEntries(process.argv.slice(2).reduce((a,v,i,all)=>i%2?a:[...a,[v,all[i+1]]],[]));
assert.ok(args['--java']&&args['--classes'],'Explicit --java and --classes required');
const repo=path.resolve(args['--repo']||path.join(__dirname,'..'));
const root=fs.mkdtempSync(path.join(os.tmpdir(),'yanxu-retired-recommendation-http-'));
const password=crypto.randomBytes(24).toString('hex'),checks=[];let child,token='',error=null;
const env=Object.fromEntries(Object.entries(process.env).filter(([k])=>!k.startsWith('YANXU_')&&!['JAVA_TOOL_OPTIONS','_JAVA_OPTIONS','JDK_JAVA_OPTIONS','CLASSPATH'].includes(k)));
env.YANXU_LOGIN_MAIL_TRANSPORT='disabled';env.YANXU_CITY_PLANNING_COLLECTION_FILE='';
function check(value,label){checks.push({label,passed:Boolean(value)});assert.ok(value,label);}
(async()=>{
 const listener=net.createServer();await new Promise((r,j)=>{listener.once('error',j);listener.listen(0,'127.0.0.1',r)});
 const port=listener.address().port;await new Promise(r=>listener.close(r));
 const log=fs.openSync(path.join(root,'server.log'),'a',0o600);
 const props=['semantic.port','semantic.token','account.import.manifest','account.import.manifest.sha256','dispatch.timetable.file','dispatch.transport.file','dispatch.city.references.file','dispatch.city.references.dir','dispatch.public.city.references.file','dispatch.public.city.references.dir','dispatch.city.planning.references.file','dispatch.city.planning.collection.file'].map(k=>'-D'+k+'=');
 child=spawn(args['--java'],['-Xmx512m','-Dbind.address=127.0.0.1','-Dbootstrap.demo=false','-Dlogin.email.mode=legacy','-Drecommendation.cpa.enabled=false','-Dbootstrap.admin.password='+password,'-Ddata.dir='+path.join(root,'data'),...props,'-cp',path.resolve(args['--classes'])+path.delimiter+path.join(repo,'lib','*'),'com.training.Main',String(port)],{cwd:repo,env,stdio:['ignore',log,log]});fs.closeSync(log);
 async function request(route,body,auth=token){const response=await fetch(`http://127.0.0.1:${port}/api`+route,{method:body===undefined?'GET':'POST',headers:{'Content-Type':'application/json',...(auth?{'X-Token':auth}:{})},body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(5000)});return {status:response.status,cache:response.headers.get('cache-control'),body:await response.json()};}
 async function api(route,body){const r=await request(route,body);assert.equal(r.body.code,0,r.body.msg);return r.body.data;}
 for(let i=0;i<100;i++){assert.equal(child.exitCode,null,'Isolated Main exited');try{token=(await api('/login',{username:'admin',password})).token;break;}catch{await new Promise(r=>setTimeout(r,100))}}
 check(Boolean(token),'Fresh synthetic administrator authenticates');
 const collections=['/teachers','/demands','/projects','/dispatches','/teacher-resumes'];
 async function state(){return JSON.stringify(await Promise.all(collections.map(r=>api(r))));}
 const before=await state();check((await api('/teachers')).length===0,'No demo or real teachers');
 const retired=['/teacher-recommendations','/teacher-recommendations/jobs','/teacher-recommendations/jobs/cancel','/teacher-recommendations/decision','/teacher-recommendations/decision/selection'];
 for(const route of retired){
  const anonymous=await request(route,{},'');check(anonymous.status===401&&anonymous.body.code===401,route+' still requires authentication');
  for(const method of ['POST','GET']){
   const r=await request(route,method==='GET'?undefined:{requirement:'合成旧需求，不应执行',max_results:3});
   check(r.status===410&&r.body.code===410,route+' '+method+' is explicitly retired');
   check(r.cache==='no-store',route+' '+method+' retirement response is not cached');
   check(r.body.msg.includes('/api/teacher-recommendations/decision/v2'),route+' '+method+' directs to the current V2 route');
   check(!r.body.data&&!('recommendations' in r.body),route+' '+method+' never fabricates a successful legacy result');
  }
 }
 const advice=await request('/teacher-recommendations/decision/v2/advice',{analysis_id:'synthetic-none',request_id:'synthetic-retired-advice'});
 check(advice.status===410&&advice.body.code===410,'V2 AI advice is disabled with HTTP and body 410');
 check(advice.body.msg.includes('只使用规则推荐')&&!advice.body.data,'AI refusal explains rules-only and returns no model output');
 const current=await request('/teacher-recommendations/decision/v2',{});
 check(current.status===400&&current.body.code===400,'Current V2 still validates its structured input, not blanket 410');
 check(await state()===before,'All retired requests leave synthetic business collections unchanged');
})().catch(e=>{error=e.stack;process.exitCode=1;}).finally(async()=>{
 if(child&&child.exitCode===null){child.kill('SIGTERM');await new Promise(resolve=>{child.once('exit',resolve);setTimeout(()=>{if(child.exitCode===null)child.kill('SIGKILL');resolve()},2000).unref()})}
 const result={suite:'current-retired-recommendation-http',checks:checks.length,passed:checks.filter(c=>c.passed).length,results:checks,status:error?'FAILED':'PASSED',error,historical_assertions_passed:0,production_touched:false,model_executed:false,artifacts:root};
 fs.writeFileSync(path.join(root,'result.json'),JSON.stringify(result,null,2),{mode:0o600});console.log(JSON.stringify(result));
});
