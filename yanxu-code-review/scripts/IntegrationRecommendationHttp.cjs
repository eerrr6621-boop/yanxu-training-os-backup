'use strict';
// Actual Main/Api HTTP, synthetic identities and fresh H2 only. No app/data, production or notification channel.
const fs = require('node:fs'), os = require('node:os'), path = require('node:path'), net = require('node:net');
const crypto = require('node:crypto'), assert = require('node:assert/strict');
const {spawn, execFile} = require('node:child_process'), {promisify} = require('node:util');
const run = promisify(execFile), args = {};
for (let i = 2; i < process.argv.length; i += 2) {
  assert.ok(['--java', '--classes'].includes(process.argv[i]) && process.argv[i + 1], 'Use --java PATH --classes ISOLATED_DIRECTORY');
  args[process.argv[i]] = process.argv[i + 1];
}
assert.ok(args['--java'] && args['--classes'], 'Explicit Java and isolated compiled classes required');
const repo = process.env.YANXU_INTEGRATION_REPO ? path.resolve(process.env.YANXU_INTEGRATION_REPO) : path.resolve(__dirname, '..'), classes = path.resolve(args['--classes']);
assert.ok(!classes.startsWith(path.join(repo, 'out')), 'Use isolated compiled classes');
assert.ok(fs.existsSync(path.join(classes, 'com/training/IntegrationRecommendationHttpFixture.class')), 'Compile the scripts-only fixture first');
const own = fs.mkdtempSync(path.join(os.tmpdir(), 'yanxu-recommendation-http-'));
const data = path.join(own, 'data'); fs.mkdirSync(data);
const password = crypto.randomBytes(24).toString('hex'), tokens = {}, failures = [];
const env = {...process.env, YANXU_SEMANTIC_PORT: '', YANXU_SEMANTIC_TOKEN: ''};
let child, port, checks = 0, phase = 'setup', fatal, configuration;
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
function check(ok, label) { checks++; if (!ok) failures.push(`${phase}: ${label}`); }
function same(actual, expected, label) { let ok = true; try { assert.deepEqual(actual, expected); } catch { ok = false; } check(ok, label); }
function options() { return ['-Dbind.address=127.0.0.1', '-Dbootstrap.demo=false', '-Drecommendation.cpa.enabled=false', '-Dbootstrap.admin.password=' + password,
  '-Dintegration.recommendation.root=' + own, '-Ddata.dir=' + data, '-Dsemantic.port=', '-Dsemantic.token=', '-cp', classes + path.delimiter + path.join(repo, 'lib', '*')]; }
async function request(route, body, actor = 'manager', options = {}) {
  const headers = {...(tokens[actor] ? {'X-Token': tokens[actor]} : {}), ...(body === undefined ? {} : {'Content-Type': 'application/json'}), ...(options.headers || {})};
  const response = await fetch(`http://127.0.0.1:${port}/api${route}`, {method: options.method || (body === undefined ? 'GET' : 'POST'), headers,
    body: body === undefined ? undefined : options.raw ? body : JSON.stringify(body), signal: AbortSignal.timeout(10000)});
  const text = await response.text(); const envelope = text ? JSON.parse(text) : null;
  return {status: response.status, headers: response.headers, envelope, text};
}
async function api(route, body, actor = 'manager') {
  const r = await request(route, body, actor);
  assert.equal(r.status, 200, `${phase}: ${route}: HTTP ${r.status}: ${r.envelope?.msg}`);
  assert.equal(r.envelope?.code, 0, `${phase}: ${route}: ${r.envelope?.msg}`); return r.envelope.data;
}
async function denied(route, body, actor, status, label, options = {}) {
  const r = await request(route, body, actor, options);
  check(r.status === status && (options.method === 'HEAD' ? r.text === '' : r.envelope?.code === status), `${label} (HTTP ${r.status}: ${r.envelope?.msg})`);
  return r;
}
async function login(actor, username = 'synthetic-recommendation-' + actor) { tokens[actor] = (await api('/login', {username, password}, 'anonymous')).token; }
async function boot(fixture = false) {
  Object.keys(tokens).forEach(key => delete tokens[key]);
  const listener = net.createServer();
  await new Promise((resolve, reject) => { listener.once('error', reject); listener.listen(0, '127.0.0.1', resolve); });
  port = listener.address().port; await new Promise(resolve => listener.close(resolve));
  child = spawn(args['--java'], [...options(), fixture ? 'com.training.IntegrationRecommendationHttpFixture' : 'com.training.Main', String(port)], {cwd: repo, env, stdio: 'ignore'});
  let launchError; child.once('error', error => { launchError = error; });
  for (let i = 0; i < 150; i++) {
    if (launchError) throw launchError;
    assert.equal(child.exitCode, null, 'Isolated service exited before login');
    try { await login('admin', 'admin'); return; } catch {}
    await pause(100);
  }
  throw new Error('Isolated recommendation service did not become ready');
}
async function stop() {
  const running = child; child = null;
  if (!running || running.exitCode !== null || running.signalCode !== null) return;
  await new Promise(resolve => {
    const timer = setTimeout(() => { if (running.exitCode === null && running.signalCode === null) running.kill('SIGKILL'); }, 2000);
    running.once('exit', () => { clearTimeout(timer); resolve(); }); running.kill('SIGTERM');
  });
}
// Retirement regression: the previous optional-course engine is deliberately no longer executable over HTTP.
// Pure qualification/worker tests retain their isolated unit coverage; production recommendation uses V2.
async function runChecks(){
 await boot(true);await login('manager');await login('viewer');
 phase='all legacy recommendation entry points retired';
 const oldRoutes=['/teacher-recommendations','/teacher-recommendations/jobs','/teacher-recommendations/jobs/cancel','/teacher-recommendations/decision','/teacher-recommendations/decision/selection'];
 for(const route of oldRoutes){
   for(const method of ['GET','HEAD','POST']){
     const result=await denied(route,method==='POST'?{}:undefined,'manager',410,'Authenticated legacy '+method+' '+route,{method});
     if(method!=='HEAD')check(result.envelope.msg.includes('已停用')&&result.envelope.msg.includes('/api/teacher-recommendations/decision/v2'),'Retirement identifies the supported replacement');
     check(result.envelope?.data===undefined||result.envelope.data===null,'Retired response cannot expose old results');
   }
   await denied(route,{},'anonymous',401,'Authentication precedes legacy retirement');
   await denied(route,{},'viewer',403,'Read-only account cannot enter legacy routes');
 }
 await denied('/teacher-recommendations/jobs?id=old-result-id',undefined,'manager',410,'Stored job GET cannot bypass retirement');
 await denied('/teacher-recommendations/decision?analysis_id=00000000-0000-0000-0000-000000000001',undefined,'manager',410,'Old v1 receipt GET cannot bypass retirement');
 const empty={scope_id:1,expected_version:0,topic:'合成主题',course_code:'',date:'2026-11-10',start_time:'09:00',end_time:'11:00',mode:'ONLINE',allowed_base_cities:[],context_type:'UNSAVED',context_id:0,teaching_place:''};
 const supported=await request('/teacher-recommendations/decision/v2',empty);
 check(supported.status!==410,'V2 reaches supported validation instead of legacy retirement');
 return {legacy_routes_retired:oldRoutes,old_results_returned:false,production_touched:false};
}
let evidence;runChecks().then(value=>{evidence=value;}).catch(error=>{fatal=String(error.message||error).replaceAll(password,'[redacted]');process.exitCode=1;}).finally(async()=>{await stop();const summary={suite:'recommendation-http',checks,failures,...(fatal?{error:fatal}:{}),...(evidence||{}),production_touched:false,notifications_sent:false,artifacts:own};fs.writeFileSync(path.join(own,'result.json'),JSON.stringify(summary,null,2),{mode:0o600});console.log(JSON.stringify(summary));if(failures.length||fatal)process.exitCode=1;});
