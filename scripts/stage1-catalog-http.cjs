'use strict';
// Real Main + Api + Auth + M01 on loopback; synthetic-only, fresh evidence-local H2 and classes.
// Usage: node scripts/stage1-catalog-http.cjs --java /absolute/java --evidence /absolute/yanxu-stage1/evidence/m04
const fs = require('node:fs'), path = require('node:path'), net = require('node:net');
const crypto = require('node:crypto'), assert = require('node:assert/strict');
const {spawn, execFile} = require('node:child_process');
const run = require('node:util').promisify(execFile);
const args = {};
for (let i = 2; i < process.argv.length; i += 2) {
  assert.ok(['--java', '--evidence'].includes(process.argv[i]) && process.argv[i + 1], 'Explicit --java PATH --evidence PATH required');
  args[process.argv[i]] = process.argv[i + 1];
}
assert.ok(args['--java'] && args['--evidence'], 'Explicit Java and evidence directory required');
const repo = fs.realpathSync(path.resolve(__dirname, '..'));
assert.equal(repo, '/Users/tongyuqing/Documents/Codex/2026-10-26/yanxu-stage1/worktree', 'Only the isolated stage1 worktree is supported');
const evidence = fs.realpathSync(args['--evidence']);
assert.equal(evidence, '/Users/tongyuqing/Documents/Codex/2026-10-26/yanxu-stage1/evidence/m04');
const own = fs.mkdtempSync(path.join(evidence, 'http-' + new Date().toISOString().replace(/[:.]/g, '-') + '-'));
const classes = path.join(own, 'classes'), data = path.join(own, 'data'), tmp = path.join(own, 'tmp');
for (const dir of [classes, data, tmp]) fs.mkdirSync(dir);
const password = crypto.randomBytes(24).toString('hex');
const tokens = {}, checks = [], traffic = [], batchIds = new Set();
const env = {...process.env, YANXU_LOGIN_MAIL_TRANSPORT: 'disabled', YANXU_SEMANTIC_PORT: '', YANXU_SEMANTIC_TOKEN: '',
  TMPDIR: tmp, TMP: tmp, TEMP: tmp, JAVA_TOOL_OPTIONS: '', _JAVA_OPTIONS: '', JDK_JAVA_OPTIONS: ''};
for (const key of Object.keys(env)) if (key.startsWith('YANXU_LOGIN_SMTP_')) delete env[key];
let child, port, servicePid = null, stopped = false, phase = 'compile', fatal = null, inspection = null;
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
function check(ok, label, detail) { checks.push({phase, pass: Boolean(ok), label, ...(detail === undefined ? {} : {detail})}); }
function same(actual, expected, label) {
  try { assert.deepEqual(actual, expected); check(true, label); }
  catch { check(false, label, {actual, expected}); }
}
function options() {
  return ['-Dfile.encoding=UTF-8', '-Djava.io.tmpdir=' + tmp, '-Dbind.address=127.0.0.1', '-Dbootstrap.demo=false',
    '-Dbootstrap.admin.password=' + password, '-Dstage1.catalog.evidence=' + evidence, '-Dstage1.catalog.run=' + own,
    '-Ddata.dir=' + data, '-Dsemantic.port=', '-Dsemantic.token=', '-Daccount.import.manifest=', '-Daccount.import.manifest.sha256=',
    '-cp', classes + path.delimiter + path.join(repo, 'lib', '*')];
}
async function request(route, body, actor = 'manager', overrides = {}) {
  const method = overrides.method || (body === undefined ? 'GET' : 'POST');
  const headers = tokens[actor] ? {'X-Token': tokens[actor]} : {};
  if (body !== undefined && overrides.contentType !== null) headers['Content-Type'] = overrides.contentType || 'application/json';
  const response = await fetch(`http://127.0.0.1:${port}/api${route}`, {
    method, headers, body: body === undefined ? undefined : overrides.raw ? body : JSON.stringify(body), signal: AbortSignal.timeout(6000),
  });
  const text = await response.text();
  let envelope = null;
  if (text) { try { envelope = JSON.parse(text); } catch { throw new Error(`${phase}: Non-JSON ${route} HTTP ${response.status}`); } }
  if (route !== '/login') traffic.push({phase, method, route, actor, status: response.status, body, response: envelope});
  return {status: response.status, headers: response.headers, text, envelope};
}
async function api(route, body, actor = 'manager') {
  const r = await request(route, body, actor);
  assert.equal(r.status, 200, `${phase}: ${route} HTTP ${r.status}: ${r.envelope?.msg}`);
  assert.equal(r.envelope?.code, 0, `${phase}: ${route}: ${r.envelope?.msg}`);
  const value = r.envelope.data;
  if (route.endsWith('-preview') && value?.ready && value.batch_id) batchIds.add(value.batch_id);
  return value;
}
async function denied(route, body, actor, status, label, overrides = {}) {
  const r = await request(route, body, actor, overrides);
  check(r.status === status && (overrides.method === 'HEAD' || r.envelope?.code === status), label,
    r.status === status ? undefined : {expected: status, actual: r.status, response: r.envelope});
  return r;
}
async function head(route, actor = 'manager', expected = 200) {
  const r = await request(route, undefined, actor, {method: 'HEAD'});
  check(r.status === expected && r.text === '', `HEAD ${route} returns ${expected} with no body`);
}
async function login(actor) {
  const username = actor === 'admin' ? 'admin' : 'stage1-synthetic-' + actor;
  tokens[actor] = (await api('/login', {username, password}, 'anonymous')).token;
}
async function allLogins() { for (const actor of ['admin','manager','reader','other','colleague','manageonly','unbound']) await login(actor); }
async function stop() {
  const running = child; child = null;
  if (!running || running.exitCode !== null || running.signalCode !== null) { stopped = true; return; }
  await new Promise(resolve => {
    const timer = setTimeout(() => { if (running.exitCode === null && running.signalCode === null) running.kill('SIGKILL'); }, 2500);
    running.once('exit', () => { clearTimeout(timer); stopped = true; resolve(); });
    running.kill('SIGTERM');
  });
}
async function compileAndBoot() {
  const sources = fs.readdirSync(path.join(repo, 'src/com/training')).filter(f => f.endsWith('.java')).sort().map(f => path.join(repo, 'src/com/training', f));
  sources.push(path.join(repo, 'scripts/stage1-catalog-fixture.java'));
  const hashes = Object.fromEntries([...sources, __filename].map(f => [path.relative(repo, f), crypto.createHash('sha256').update(fs.readFileSync(f)).digest('hex')]));
  fs.writeFileSync(path.join(own, 'source-sha256.json'), JSON.stringify(hashes, null, 2));
  const compileArgs = ['-Djava.io.tmpdir=' + tmp, '-jar', path.join(repo,'lib/ecj.jar'), '-17', '-encoding', 'UTF-8', '-nowarn',
    '-cp', ['h2.jar','pdfbox-app-3.0.8.jar','ip2region-3.3.7.jar'].map(f => path.join(repo,'lib',f)).join(path.delimiter), '-d', classes, ...sources];
  fs.writeFileSync(path.join(own, 'invocation.json'), JSON.stringify({node: process.execPath, script: __filename, java: args['--java'],
    compileArgs, policy: {syntheticOnly: true, initialScopes: 0, bind: '127.0.0.1', emailTransport: 'disabled', semantic: 'disabled', manifest: 'disabled'}}, null, 2));
  try {
    const compiled = await run(args['--java'], compileArgs, {cwd: repo, env, timeout: 120000, maxBuffer: 1024*1024});
    fs.writeFileSync(path.join(own,'compile.log'), compiled.stdout + compiled.stderr);
  } catch (error) {
    fs.writeFileSync(path.join(own,'compile.log'), (error.stdout || '') + (error.stderr || ''));
    throw error;
  }
  check(true, 'Fresh isolated full production source and scripts-only fixture compilation');
  phase = 'boot';
  const listener = net.createServer();
  await new Promise((resolve, reject) => { listener.once('error', reject); listener.listen(0, '127.0.0.1', resolve); });
  port = listener.address().port;
  await new Promise(resolve => listener.close(resolve));
  const log = fs.openSync(path.join(own,'server.log'),'wx');
  child = spawn(args['--java'], [...options(), 'com.training.Stage1CatalogHttpFixture', String(port)], {cwd: repo, env, stdio: ['ignore', log, log]});
  fs.closeSync(log);
  servicePid = child.pid;
  let launchError;
  child.once('error', error => { launchError = error; });
  for (let i = 0; i < 150; i++) {
    if (launchError) throw launchError;
    assert.equal(child.exitCode, null, 'Synthetic loopback service exited before login; inspect server.log');
    try { await login('admin'); if (tokens.admin) return; } catch {}
    await pause(100);
  }
  throw new Error('Synthetic loopback service did not become ready');
}
const ROOT = '/course-catalog/scopes', PROVISION = ROOT + '/provisioning';
const EMPTY = {added: [], updated: [], removed: []};
const COURSE = {course_code: 'EXPLICIT-C-001', course_name: 'STAGE1 SYNTHETIC 明确课程', active: true};
const qualifyRequest = {course_code: COURSE.course_code, as_of: '2026-10-26', accepted_levels: [], allowed_cities: []};
function provisionBody(org, identity = 'stage1-synthetic-identity-v1') { return {organization_code: org, expected_identity_version: identity, confirm: true}; }
function bindingBody(version, suffix, bindings) { return {expected_version: version, catalog_version: 'stage1-synthetic-' + suffix, bindings, change_comment: 'STAGE1 SYNTHETIC explicit existing profile binding'}; }
function confirmBody(preview, version) { return {batch_id: preview.batch_id, expected_version: version, confirm: true}; }
function ready(preview, scope, version, codes, label) {
  check(preview.ready === true && preview.confirmation_required === true && /^[0-9a-f-]{36}$/.test(preview.batch_id), label + ' separate confirmable batch');
  same([preview.scope_id, preview.current_version], [scope, version], label + ' scope and CAS');
  same(preview.changes?.courses, EMPTY, label + ' leaves courses unchanged');
  same(preview.changes?.certifications, EMPTY, label + ' creates no certification');
  for (const key of ['teachers','bindings']) {
    same([...preview.changes[key].added].sort(), [...codes].sort(), label + ' explicit ' + key + ' additions');
    same([preview.changes[key].updated, preview.changes[key].removed], [[],[]], label + ' no ' + key + ' updates/removals');
  }
  same(preview.data.certifications, [], label + ' retains empty certifications');
}
async function flow() {
  phase = 'initial';
  await allLogins();
  const initial = JSON.parse(fs.readFileSync(path.join(own,'initial-state.json'),'utf8'));
  for (const [key,value] of Object.entries(initial)) if (key.startsWith('m04_')) same(value, 0, 'Fixture starts with zero ' + key);
  same(initial.teachers, 5, 'Fixture contains only five synthetic existing teacher profiles');
  same(await api(ROOT), {status:'NOT_CONFIGURED',scopes:[]}, 'M01 grants never pre-create a scope');
  const offered = await api(PROVISION);
  same(offered, {identity_version:'stage1-synthetic-identity-v1', organizations:[{organization_code:'001',scope_id:null}]}, 'Provisioning offers only granted organization and no implicit scope');
  same((await api(PROVISION,undefined,'reader')).organizations, [], 'Read-only M01 person has no provisioning choices');
  same((await api(PROVISION,undefined,'other')).organizations, [{organization_code:'002',scope_id:null}], 'Other organization offers only its own scope');
  await head(PROVISION);
  for (const actor of ['admin','unbound']) {
    await denied(PROVISION,undefined,actor,403, actor + ' gets no implicit provisioning authority');
    await denied(PROVISION,provisionBody('001'),actor,403, actor + ' cannot provision');
  }
  await denied(PROVISION,undefined,'anonymous',401,'Provisioning requires real authentication');
  await denied(PROVISION,provisionBody('001'),'reader',403,'Read grant cannot provision');
  await denied(PROVISION,provisionBody('002'),'manager',403,'Cannot choose another organization');
  await denied(PROVISION,provisionBody('001','stage1-stale'),'manager',409,'Provisioning rejects stale M01 version');
  for (const [label,body] of [
    ['explicit confirmation', {...provisionBody('001'),confirm:false}],
    ['extra fields', {...provisionBody('001'),can_manage:true}],
    ['missing identity', {organization_code:'001',confirm:true}],
    ['nonstring org', {...provisionBody('001'),organization_code:1}],
  ]) await denied(PROVISION,body,'manager',400,'Provisioning rejects ' + label);
  await denied(PROVISION,provisionBody('001'),'manager',415,'Provisioning requires JSON content type',{contentType:'text/plain'});
  await denied(PROVISION + '?org=001',undefined,'manager',400,'Provisioning rejects query routing');
  const wrongMethod = await denied(PROVISION,undefined,'manager',405,'Provisioning rejects DELETE',{method:'DELETE'});
  check(wrongMethod.headers.get('allow')?.includes('POST'),'Provisioning 405 advertises allowed methods');
  same((await api(ROOT)).scopes,[],'All rejected provisioning requests leave scope list empty');

  phase = 'provision';
  const [created,replayed] = await Promise.all([api(PROVISION,provisionBody('001')),api(PROVISION,provisionBody('001'))]);
  same(created,replayed,'Concurrent provisioning of same organization is idempotent');
  check(Number.isSafeInteger(created.scope_id) && created.scope_id > 0,'Scope uses server-issued internal ID');
  same({...created,scope_id:0},{scope_id:0,organization_code:'001',version:0,can_read:true,can_manage:true},'Provisioning creates an empty granted scope');
  const scope = created.scope_id, route = ROOT + '/' + scope;
  const other = await api(PROVISION,provisionBody('002'),'other');
  const writeOnly = await api(PROVISION,provisionBody('003'),'manageonly');
  same([writeOnly.can_read,writeOnly.can_manage,writeOnly.version],[false,true,0],'Manage-only authority does not imply catalog.read');
  await denied(ROOT + '/' + writeOnly.scope_id,undefined,'manageonly',403,'Manage-only scope original read denied');
  await denied(ROOT + '/' + writeOnly.scope_id + '/qualify',{expected_version:0,request:qualifyRequest},'manageonly',403,'Manage-only scope qualification denied');
  same((await api(PROVISION)).organizations,[{organization_code:'001',scope_id:scope}],'Provisioning read reports explicit saved scope');
  same((await api(route)).catalog,null,'New scope contains no invented catalog');
  same((await api(route)).bindings,[],'New scope contains no invented teacher bindings');
  await head(route);
  for (const actor of ['reader','admin','other','unbound']) await denied(route + '/teacher-binding-management',undefined,actor,403, actor + ' cannot manage target bindings');
  await denied(ROOT + '/' + other.scope_id + '/teacher-binding-management',undefined,'manager',403,'Manager cannot access other scope candidates');

  phase = 'course';
  const courseInput = {expected_version:0,catalog_version:'stage1-synthetic-course-v1',courses:[COURSE],change_comment:'STAGE1 SYNTHETIC explicit confirmed course code'};
  const cp = await api(route + '/courses-preview',courseInput);
  check(cp.ready && cp.confirmation_required,'Explicit course preview creates pending batch');
  same(cp.changes.courses.added,[COURSE.course_code],'User-provided course code is preserved');
  for (const table of ['teachers','bindings','certifications']) same(cp.changes[table],EMPTY,'Course preview does not create ' + table);
  same((await api(route)).version,0,'Course preview does not publish automatically');
  await denied(route + '/confirm',confirmBody(cp,0),'colleague',403,'Another manager cannot confirm course batch');
  const confirms = await Promise.all([api(route + '/confirm',confirmBody(cp,0)),api(route + '/confirm',confirmBody(cp,0))]);
  same(confirms.map(r => r.status).sort(),['ALREADY_CONFIRMED','CONFIRMED'],'Concurrent same-batch confirmation publishes once');
  same(confirms.map(r => r.version),[1,1],'Idempotent course confirmation returns one publication version');
  const courseSaved = await api(route);
  same(courseSaved.catalog.courses,[COURSE],'Explicit course is available through original read');
  same([courseSaved.catalog.teachers,courseSaved.catalog.certifications,courseSaved.bindings],[[],[],[]],'Course publication does not infer teachers or certifications');

  phase = 'binding-validation';
  const management = await api(route + '/teacher-binding-management');
  const legacyTeachers = await api('/teachers');
  same(management.candidates.map(t=>t.teacher_id).sort((a,b)=>a-b),legacyTeachers.map(t=>t.id).sort((a,b)=>a-b),'Candidate identities equal original GET teachers visibility');
  same(Object.keys(management).sort(),['bindings','can_manage','candidates','catalog_version','organization_code','scope_id','version'],'Binding management DTO stays narrow');
  same(Object.keys(management.candidates[0]).sort(),['city','in_catalog','name','org','status','teacher_code','teacher_id','teacher_level'],'Candidate DTO contains no private contact or inferred ownership fields');
  check(management.candidates.every(t=>t.teacher_code === null && t.in_catalog === false),'All candidates initially have no inferred business code');
  const teachers = [...management.candidates].sort((a,b)=>a.teacher_id-b.teacher_id);
  const [t1,t2,t3,t4,t5] = teachers;
  check(t1.name === t2.name && t1.teacher_id !== t2.teacher_id,'Same-name existing profiles stay distinct by internal ID');
  same(t2.org,'002','Candidate remains visible despite org text matching a different M01 organization');
  await head(route + '/teacher-binding-management');
  await denied(route + '/teacher-binding-management',{},'manager',405,'Binding management rejects POST');
  await denied(route + '/teacher-binding-management?q=x',undefined,'manager',400,'Binding management rejects query parameters');
  const firstBinding = {teacher_code:'EXPLICIT-T-020',teacher_id:t2.teacher_id};
  const basic = bindingBody(1,'binding-v2',[firstBinding]);
  for (const field of ['name','teacher_level','city','certifications','organization_code','can_manage'])
    await denied(route + '/teacher-bindings-preview',{...basic,[field]:'SYNTHETIC FORGED'},'manager',400,'Binding preview rejects client ' + field);
  for (const field of ['name','teacher_level','city','certifications'])
    await denied(route + '/teacher-bindings-preview',{...basic,bindings:[{...firstBinding,[field]:'SYNTHETIC FORGED'}]},'manager',400,'Binding row rejects client ' + field);
  for (const [label,row] of [
    ['missing explicit business code',{teacher_id:t1.teacher_id}],['blank code',{teacher_code:'',teacher_id:t1.teacher_id}],
    ['numeric code',{teacher_code:42,teacher_id:t1.teacher_id}],['whitespace code',{teacher_code:' X ',teacher_id:t1.teacher_id}],
    ['bad code characters',{teacher_code:'X/Y',teacher_id:t1.teacher_id}],['string internal ID',{teacher_code:'X',teacher_id:String(t1.teacher_id)}],
    ['zero ID',{teacher_code:'X',teacher_id:0}],['negative ID',{teacher_code:'X',teacher_id:-1}],['fractional ID',{teacher_code:'X',teacher_id:1.5}],
    ['unsafe ID',{teacher_code:'X',teacher_id:Number.MAX_SAFE_INTEGER+1}],['missing ID',{teacher_code:'X'}],
  ]) await denied(route + '/teacher-bindings-preview',{...basic,bindings:[row]},'manager',400,'Binding rejects ' + label);
  await denied(route + '/teacher-bindings-preview',{...basic,bindings:[{teacher_code:'ABSENT',teacher_id:999999}]},'manager',403,'Nonexistent teacher uses same denial as invisible teacher');
  await denied(route + '/teacher-bindings-preview',{...basic,bindings:[]},'manager',409,'No-op binding preview cannot create batch');
  await denied(route + '/teacher-bindings-preview',{...basic,bindings:[firstBinding,firstBinding]},'manager',400,'Duplicate input mapping is invalid');
  await denied(route + '/teacher-bindings-preview',{...basic,bindings:[firstBinding,{teacher_code:'EXPLICIT-T-DUP',teacher_id:t2.teacher_id}]},'manager',400,'Duplicate profile within one request is invalid');
  await denied(route + '/teacher-bindings-preview',{...basic,bindings:[firstBinding,{teacher_code:firstBinding.teacher_code,teacher_id:t1.teacher_id}]},'manager',400,'Duplicate business code within one request is invalid');
  for (const actor of ['reader','admin','other','unbound']) await denied(route + '/teacher-bindings-preview',basic,actor,403,actor + ' cannot preview target binding');
  await denied(route + '/teacher-bindings-preview',undefined,'manager',405,'GET cannot create binding preview');
  await head(route + '/teacher-bindings-preview','manager',405);
  await denied(route + '/teacher-bindings-preview',basic,'manager',415,'Binding preview requires JSON',{contentType:'text/plain'});
  await denied(route + '/teacher-bindings-preview','{','manager',400,'Malformed JSON cannot create binding batch',{raw:true});
  await denied(route + '/teacher-bindings-preview?q=x',basic,'manager',400,'Binding preview rejects query parameters');
  const invalid = await api(route + '/teacher-bindings-preview',{...basic,catalog_version:''});
  same([invalid.ready,invalid.data,invalid.batch_id,invalid.changes,invalid.confirmation_required],[false,null,null,null,false],'Invalid catalog material returns ready=false with no changes or batch');
  const incomplete = await api(route + '/teacher-bindings-preview',bindingBody(1,'unknown-metadata',[{teacher_code:'EXPLICIT-UNKNOWN',teacher_id:t5.teacher_id}]));
  ready(incomplete,scope,1,['EXPLICIT-UNKNOWN'],'Unknown teacher metadata');
  same(incomplete.data.teachers,[{teacher_code:'EXPLICIT-UNKNOWN',teacher_level:'',city:''}],'Unknown level/city remain empty and are not invented');
  check(incomplete.issues.some(i=>i.code === 'UNKNOWN_METADATA'),'Unknown metadata is explicitly reported');

  phase = 'first-binding';
  const bp = await api(route + '/teacher-bindings-preview',basic);
  ready(bp,scope,1,[firstBinding.teacher_code],'First binding');
  same(bp.binding_selections,[{...firstBinding,name:t2.name,org:t2.org}],'Preview review contains exact selected identity and explicit business code');
  same(bp.data.teachers,[{teacher_code:firstBinding.teacher_code,teacher_level:t2.teacher_level,city:t2.city}],'Metadata comes from explicitly selected profile, not same-name row');
  same((await api(route)).bindings,[],'Binding preview leaves published mapping untouched');
  const competing = await api(route + '/teacher-bindings-preview',{...basic,catalog_version:'stage1-synthetic-competing'});
  await denied(route + '/confirm',confirmBody(bp,1),'colleague',403,'Binding confirm requires original preview person');
  await denied(route + '/confirm',{...confirmBody(bp,1),confirm:false},'manager',400,'Binding confirm requires explicit confirmation');
  await denied(route + '/confirm',confirmBody(bp,0),'manager',409,'Binding confirm rejects mismatched expected version');
  await denied(ROOT + '/' + other.scope_id + '/confirm',confirmBody(bp,1),'other',404,'Batch cannot cross scope even for other scope manager');
  await api('/teachers',{id:t2.teacher_id,name:'STAGE1 SYNTHETIC 已改变姓名'},'admin');
  await denied(route + '/confirm',confirmBody(bp,1),'manager',409,'Changed selected teacher name invalidates confirmation');
  await api('/teachers',{id:t2.teacher_id,name:t2.name},'admin');
  await api('/teachers',{id:t2.teacher_id,org:'STAGE1 SYNTHETIC changed display organization'},'admin');
  await denied(route + '/confirm',confirmBody(bp,1),'manager',409,'Changed selected teacher org display invalidates confirmation');
  await api('/teachers',{id:t2.teacher_id,org:t2.org},'admin');
  const bc = await api(route + '/confirm',confirmBody(bp,1));
  same([bc.status,bc.version],['CONFIRMED',2],'Restoring selected profile facts lets original binding batch publish version two');
  same((await api(route + '/confirm',confirmBody(bp,1))).status,'ALREADY_CONFIRMED','Same binding confirm can safely retry');
  await denied(route + '/confirm',confirmBody(competing,1),'manager',409,'Competing preview loses CAS after first publication');
  await denied(route + '/teacher-bindings-preview',basic,'manager',409,'Stale binding preview is rejected');
  const saved = await api(route,undefined,'reader');
  same(saved.bindings,[firstBinding],'Original read exposes exactly explicit profile mapping');
  same(saved.catalog.courses,[COURSE],'Binding publication preserves existing course');
  same(saved.catalog.certifications,[],'Binding publication creates no certification');
  const qualification = await api(route + '/qualify',{expected_version:2,request:qualifyRequest},'reader');
  same(qualification.eligible,[],'Teacher remains ineligible without a certification');
  check(qualification.gaps.some(g=>g.teacher_code===firstBinding.teacher_code && g.reasons.some(r=>r.code==='CERTIFICATION_MISSING')),'Qualification explicitly reports missing exact-course certification');
  const after = await api(route + '/teacher-binding-management');
  const chosen = after.candidates.find(t=>t.teacher_id===t2.teacher_id);
  same([chosen.teacher_code,chosen.in_catalog],[firstBinding.teacher_code,true],'Saved candidate displays fixed explicit mapping');
  check(after.candidates.find(t=>t.teacher_id===t1.teacher_id).teacher_code === null,'Same display name never propagates a binding');
  for (const [label,row] of [
    ['duplicate saved pair',firstBinding],['new code for fixed profile',{teacher_code:'RENAMED',teacher_id:t2.teacher_id}],
    ['fixed code to another profile',{teacher_code:firstBinding.teacher_code,teacher_id:t1.teacher_id}],
  ]) await denied(route + '/teacher-bindings-preview',bindingBody(2,'conflict',[row]),'manager',409,'Rejects ' + label);
  await denied(route + '/teacher-bindings-preview',bindingBody(2,'course-v1',[{teacher_code:'UNUSED',teacher_id:t3.teacher_id}]),'manager',409,'Previously published catalog material version cannot be reused');

  phase = 'multiple-explicit-bindings';
  const additional = [{teacher_code:'EXPLICIT-Z-091',teacher_id:t1.teacher_id},{teacher_code:'EXPLICIT-A-007',teacher_id:t3.teacher_id}];
  const multi = await api(route + '/teacher-bindings-preview',bindingBody(2,'binding-v3',additional));
  ready(multi,scope,2,additional.map(r=>r.teacher_code),'Multiple explicit mappings');
  await api(route + '/confirm',confirmBody(multi,2));
  const three = await api(route);
  same(three.bindings.slice().sort((a,b)=>a.teacher_id-b.teacher_id),[...additional,firstBinding].sort((a,b)=>a.teacher_id-b.teacher_id),'Row ordering does not generate or reassign any business code');
  same(three.catalog.certifications,[],'Additional bindings retain empty certifications');
  const q3 = await api(route + '/qualify',{expected_version:3,request:qualifyRequest});
  check(q3.eligible.length===0 && q3.gaps.length===3 && q3.gaps.every(g=>g.reasons.some(r=>r.code==='CERTIFICATION_MISSING')),'Every explicitly bound teacher remains ineligible without certification');

  phase = 'facts-and-identity';
  const fourth = {teacher_code:'EXPLICIT-FACT-004',teacher_id:t4.teacher_id};
  const factBatch = await api(route + '/teacher-bindings-preview',bindingBody(3,'fact-change',[fourth]));
  await api('/teachers',{id:t4.teacher_id,teacher_level:'高级讲师',base_province:'浙江',base_city:'宁波'},'admin');
  await denied(route + '/confirm',confirmBody(factBatch,3),'manager',409,'Changed current teacher level/city invalidates preview confirmation');
  same((await api(route)).version,3,'Fact conflict cannot publish');
  await api('/teachers',{id:t4.teacher_id,teacher_level:'讲师',base_province:'浙江',base_city:'杭州'},'admin');
  const statusBatch = await api(route + '/teacher-bindings-preview',bindingBody(3,'status-change',[fourth]));
  await api('/teachers/checkout',{id:t4.teacher_id},'admin');
  await denied(route + '/confirm',confirmBody(statusBatch,3),'manager',409,'Changed teacher status invalidates confirmation');
  await api('/teachers/checkin',{id:t4.teacher_id},'admin');
  const identityBatch = await api(route + '/teacher-bindings-preview',bindingBody(3,'identity-change',[fourth]));
  const config = await api('/organization/config',undefined,'admin');
  const updated = structuredClone(config.configuration); updated.version = 'stage1-synthetic-identity-v2';
  await api('/organization/config',{expectedVersion:config.version,configuration:updated},'admin');
  await allLogins();
  await denied(PROVISION,provisionBody('001'),'manager',409,'Provisioning rejects old identity version after real M01 publish');
  await denied(route + '/confirm',confirmBody(identityBatch,3),'manager',409,'Pending binding requires current M01 identity version');
  const afterIdentity = await api(PROVISION,provisionBody('001',updated.version));
  same([afterIdentity.scope_id,afterIdentity.version],[scope,3],'Reprovision after identity refresh returns same existing scope');
  const revokeBatch = await api(route + '/teacher-bindings-preview',bindingBody(3,'revoke-pending',[fourth]));
  const revoked = structuredClone(updated); revoked.version = 'stage1-synthetic-identity-v3';
  revoked.people.find(p=>p.personCode==='P1').roleCodes=['READER'];
  await api('/organization/config',{expectedVersion:updated.version,configuration:revoked},'admin');
  await denied(route + '/teacher-binding-management',undefined,'manager',401,'M01 person-role change invalidates old HTTP session');
  await allLogins();
  same((await api(PROVISION)).organizations,[],'Fresh login after revoke has no provisioning authority');
  await denied(PROVISION,provisionBody('001',revoked.version),'manager',403,'Revoked manager cannot replay provisioning');
  await denied(route + '/teacher-binding-management',undefined,'manager',403,'Revoked manager cannot read candidate management');
  await denied(route + '/teacher-bindings-preview',bindingBody(3,'forbidden',[fourth]),'manager',403,'Revoked manager cannot create new batch');
  await denied(route + '/confirm',confirmBody(revokeBatch,3),'manager',403,'Revoked manager cannot confirm pending batch');
  await denied(route + '/confirm',confirmBody(bp,1),'manager',403,'Revoked manager cannot use confirmed-batch idempotency to bypass authorization');
  const final = await api(route,undefined,'colleague');
  same([final.version,final.catalog.courses,final.catalog.certifications],[3,[COURSE],[]],'Rejected operations preserve accepted catalog and empty certifications');
  same((await api(route + '/history',undefined,'colleague')).events.map(e=>e.version),[3,2,1],'Only three explicit confirmations create audit revisions');
  same((await api('/teachers',undefined,'colleague')).length,5,'Binding never creates teacher profiles');
  check((await api(route + '/qualify',{expected_version:3,request:qualifyRequest},'colleague')).eligible.length===0,'Final original qualification remains ineligible');
  same((await api(ROOT,undefined,'other')).scopes.map(s=>s.organization_code),['002'],'Other scope stays isolated');
  same((await api(ROOT + '/' + other.scope_id,undefined,'other')).version,0,'Other scope stays empty');
}
(async()=>{
  console.log('Stage1 M04 synthetic real HTTP evidence: ' + own);
  try { await compileAndBoot(); await flow(); }
  catch(error) { fatal = String(error.stack || error); }
  finally {
    await stop();
    phase = 'final-inspection';
    check(stopped,'Synthetic Main HTTP service has stopped');
    if (fs.existsSync(path.join(data,'training.mv.db')) && fs.existsSync(path.join(classes,'com/training/Stage1CatalogHttpFixture.class'))) {
      try {
        const result = await run(args['--java'],[...options(),'com.training.Stage1CatalogHttpFixture','inspect'],{cwd:repo,env,timeout:15000,maxBuffer:1024*1024});
        inspection = JSON.parse(result.stdout.trim());
        fs.writeFileSync(path.join(own,'final-state.json'),JSON.stringify(inspection,null,2));
        if (!fatal) {
          same(inspection.m04_catalog_scopes,3,'Only three explicitly provisioned scopes exist');
          same(inspection.m04_catalog_revisions,3,'Exactly three accepted revisions persisted');
          same(inspection.m04_catalog_events,3,'Exactly three audit events persisted');
          same(inspection.m04_teacher_bindings,3,'Exactly three explicit fixed teacher bindings persisted');
          same(inspection.m04_catalog_batches,batchIds.size,'Only accepted ready previews create batches');
          same(inspection.teachers,5,'No profile is synthesized during catalog work');
          check(inspection.revisions.every(r=>r.certifications.length===0),'Every persisted catalog revision has certifications=[]');
        }
      } catch(error) { fatal = [fatal,String(error.stack || error)].filter(Boolean).join('\n'); }
    }
    if (port) {
      try { await fetch(`http://127.0.0.1:${port}/api/me`,{signal:AbortSignal.timeout(1000)}); check(false,'Stopped service no longer accepts HTTP'); }
      catch { check(true,'Stopped service no longer accepts HTTP'); }
    }
    const failures = checks.filter(c=>!c.pass);
    const summary = {status:fatal || failures.length?'FAIL':'PASS',checks:checks.length,passed:checks.length-failures.length,failed:failures.length,
      fatal,failures,service:{pid:servicePid,port,stopped},evidence:own,acceptedPreviewBatches:batchIds.size,
      isolation:{freshH2:true,initialScopes:0,syntheticOnly:true,email:'disabled',semantic:'disabled',accountImportManifest:'disabled'}};
    fs.writeFileSync(path.join(own,'checks.json'),JSON.stringify(checks,null,2));
    fs.writeFileSync(path.join(own,'http-transcript.json'),JSON.stringify(traffic,null,2));
    fs.writeFileSync(path.join(own,'summary.json'),JSON.stringify(summary,null,2));
    console.log(JSON.stringify(summary,null,2));
    process.exitCode = summary.status === 'PASS' ? 0 : 1;
  }
})();
