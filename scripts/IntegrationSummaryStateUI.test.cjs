'use strict';
// Execute the original UI/harness against the exact candidate file. Synthetic DOM/HTTP only; no DB.
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm'), assert = require('node:assert/strict');
const appRoot = path.resolve(process.argv[2] || path.join(__dirname, '..'));
const candidate = path.resolve(process.argv[3] || path.join(appRoot, 'web/app.js'));
const read = fs.readFileSync.bind(fs), source = read(candidate, 'utf8');
fs.readFileSync = (file, ...args) => typeof file === 'string' && path.resolve(file) === path.join(appRoot, 'web/app.js')
  ? args[0] === 'utf8' ? source : Buffer.from(source) : read(file, ...args);
const formalTest = read(path.join(appRoot, 'scripts/IntegrationSummaryFormalUI.test.cjs'), 'utf8');
const context = { require, process: { argv: [] }, __dirname: path.join(appRoot, 'scripts'), console, structuredClone,
  AbortController, DOMException, URL, URLSearchParams, Buffer, setImmediate, Blob, Uint8Array, Response };
vm.runInNewContext(formalTest.slice(0, formalTest.indexOf('(async()=>{')) + '\nglobalThis.support={setup,formal,lib};\n}', context);
const { setup, formal, lib } = context.support;
let checks = 0;
const check = (v, message) => { assert.ok(v, message); checks++; };
const decision = role => ({ review_role: role, responsibilities: role === 'COMBINED' ? ['BRANCH', 'BP'] : [role],
  actor_code: 'SYNTHETIC-' + role, reviewed_at: '2026-10-26T01:00:00Z', workflow_version: role === 'BP' ? 3 : 2 });
function dto(status, { revision = 1, mode = 'DUAL_REVIEW', roles = [], order = 'BRANCH_THEN_BP', submitted, role = null, exportable = false } = {}) {
  const d = formal(status, role, status !== 'SUBMITTED');
  d.version = d.revision = d.latest_version = d.workflow.revision = revision;
  d.current = lib.revision(revision, { ...lib.content(), achievements: '已保存正文 V' + revision });
  Object.assign(d.workflow, { workflow_version: 4, submitted_revision: submitted === undefined ? status === 'DRAFT' ? null : revision : submitted,
    review_mode: mode, review_blocked_reason: null, review_order: mode === 'SINGLE_EXPLICIT' ? 'COMBINED' : order,
    decisions: roles.map(decision) });
  d.capabilities.export = d.workflow.capabilities.export = exportable;
  return d;
}
function harness(initial, override) {
  const h = setup({ initial, fetchOverride: (url, opts, normal, respond, remote) => {
    const u = new URL(url, 'http://synthetic');
    const fallback = () => u.pathname === '/api/training-summaries/workflow' ? respond(remote.get().workflow)
      : u.pathname === '/api/training-summaries/revision' ? respond(lib.revision(Number(u.searchParams.get('revision')), { ...lib.content(), achievements: '已保存正文 V' + u.searchParams.get('revision') })) : normal();
    return override ? override(u, opts, fallback, respond, remote) : fallback();
  } });
  h.viewer = () => h.modal()?.querySelector('#summary-revision');
  h.status = () => h.viewer()?.querySelector('[data-summary-saved-status]')?.textContent || '';
  h.workflowRequests = () => h.calls.filter(c => c.url.startsWith('/api/training-summaries/workflow?'));
  h.historical = async number => { await h.click('show-history'); const binding = h.bindings.at(-1); await binding.actions.find(a => a.l === '查看版本').onClick({ revision: number }); await lib.tick(); };
  return h;
}
const workflowUrl = u => u.pathname === '/api/training-summaries/workflow';
(async () => {
  const cases = [
    [dto('APPROVED', { roles: ['BRANCH', 'BP'] }), '已通过两方复核'],
    [dto('APPROVED', { mode: 'SINGLE_EXPLICIT', roles: ['COMBINED'] }), '已完成兼任复核'],
    [dto('DRAFT'), '草稿 · 待提交'],
    [dto('DRAFT', { revision: 2, submitted: 1, roles: ['BRANCH', 'BP'] }), '草稿 · 待提交'],
    [dto('SUBMITTED'), '待分公司负责人复核'],
    [dto('SUBMITTED', { roles: ['BRANCH'] }), '待 BP 复核'],
    [dto('SUBMITTED', { mode: 'SINGLE_EXPLICIT' }), '待负责人及BP复核（兼任）'],
    [dto('SUBMITTED', { order: 'UNORDERED' }), '待负责人及 BP 复核'],
    [dto('SUBMITTED', { order: 'UNORDERED', roles: ['BP'] }), '待分公司负责人复核'],
    [dto('SUBMITTED', { order: 'UNORDERED', roles: ['BRANCH'] }), '待 BP 复核'],
    [dto('RETURNED', { roles: ['BRANCH'] }), '已退回修改'],
  ];
  for (const [data, label] of cases) {
    const h = harness(data); await h.mount(); check(h.panel().textContent.includes(label), 'card: ' + label);
    await h.open(); check(h.modal().querySelector('[data-summary-flow-status]').textContent.includes(label), 'formal section: ' + label);
    await h.click('preview'); check(h.status().includes(label), 'saved preview: ' + label);
    check(h.workflowRequests().length === 1, 'preview obtains fresh formal status');
    check(!h.viewer().querySelector('input,textarea'), 'preview stays read only');
    if (data.workflow.status === 'APPROVED') {
      check(!h.viewer().textContent.includes('尚未完成正式复核') && !h.status().includes('草稿'), 'approved is never labelled unreviewed');
      check(!h.button('export'), 'approval display does not grant Word permission');
    }
    if (data.workflow.status === 'DRAFT' && data.version === 2) {
      check(h.viewer().textContent.includes('第 1 版的复核记录属于历史') && !h.status().includes('已通过'), 'old approval not reused by new draft');
    }
  }
  const blocked = dto('SUBMITTED', { mode: 'SINGLE_EXPLICIT' });
  blocked.workflow.review_blocked_reason = '<img src=x>兼任职责已变化';
  const blockedView = harness(blocked); await blockedView.mount(); await blockedView.open(); await blockedView.click('preview');
  check(blockedView.viewer().textContent.includes(blocked.workflow.review_blocked_reason) && !blockedView.viewer().querySelector('img'), 'blocked reason is visible and escaped');

  const newDraft = dto('DRAFT', { revision: 2, submitted: 1, roles: ['BRANCH', 'BP'] });
  const old = harness(newDraft); await old.mount(); await old.open(); await old.historical(1);
  check(old.status().includes('历史保存版本') && !old.status().includes('草稿') && !old.status().includes('已通过'), 'old approved content gets no invented current or historical state');
  check(old.viewer().textContent.includes('流程历史'), 'historical review has explicit lookup guidance');
  const newerApproved = harness(dto('APPROVED', { revision: 2, roles: ['BRANCH', 'BP'] })); await newerApproved.mount(); await newerApproved.open(); await newerApproved.historical(1);
  check(newerApproved.status().includes('历史保存版本') && !newerApproved.status().includes('已通过'), 'current v2 approval never borrowed for v1');
  const changed = harness(dto('APPROVED', { roles: ['BRANCH', 'BP'] }), (u, opts, normal, respond) => workflowUrl(u) ? respond(newDraft.workflow) : normal());
  await changed.mount(); await changed.open(); await changed.click('preview');
  check(changed.status().includes('历史保存版本') && changed.viewer().textContent.includes('当前保存版为第 2 版'), 'concurrent save detected while previewing v1');
  check(changed.field('achievements').value === '已保存正文 V1', 'fresh read does not silently rebase editor');

  for (const code of [409, 500]) {
    const h = harness(dto('APPROVED'), (u, opts, normal, respond) => workflowUrl(u) ? respond(null, code) : normal());
    await h.mount(); await h.open(); await h.click('preview');
    check(h.status().includes('复核状态暂无法核实') && !h.status().includes('已通过'), code + ' cannot reuse cached approval');
    check(h.viewer().textContent.includes('已保存正文 V1'), 'failed workflow leaves already authorized body readable');
  }
  const offline = harness(dto('APPROVED'), (u, opts, normal) => { if (workflowUrl(u)) throw Error('synthetic timeout'); return normal(); });
  await offline.mount(); await offline.open(); await offline.click('preview'); check(offline.status().includes('暂无法核实'), 'network failure is unknown');
  for (const change of [f => { f.project_id = 99; }, f => { f.revision = 0; }, f => { f.submitted_revision = 2; }, f => { f.workflow_version = 3; }, f => { f.status = 'UNKNOWN'; }, f => { delete f.capabilities; }]) {
    const invalid = structuredClone(dto('APPROVED').workflow); change(invalid);
    const h = harness(dto('APPROVED'), (u, opts, normal, respond) => workflowUrl(u) ? respond(invalid) : normal());
    await h.mount(); await h.open(); await h.click('preview'); check(h.status().includes('暂无法核实') && !h.status().includes('已通过'), 'invalid/stale workflow fails closed');
  }
  const badCard = dto('APPROVED'); badCard.workflow.submitted_revision = 0;
  const inconsistent = harness(badCard); await inconsistent.mount(); check(!inconsistent.panel().querySelector('[data-summary-open]'), 'mismatched main workflow cannot present approved card');
  const legacy = harness(lib.view()); await legacy.mount(); await legacy.open(); await legacy.click('preview');
  check(legacy.status().includes('复核状态暂无法核实') && !legacy.button('export'), 'draft-only response never invents formal status');
  const zero = harness(lib.view(0)); await zero.mount(); check(zero.panel().querySelector('[data-summary-preview-open]').disabled, 'no saved version cannot preview');
  for (const endpoint of ['workflow', 'revision']) for (const status of [401, 403]) {
    const h = harness(dto('APPROVED'), (u, opts, normal, respond) => u.pathname === '/api/training-summaries/' + endpoint ? respond(null, status) : normal());
    await h.mount(); await h.open(); await h.click('preview'); check(!h.modal(), endpoint + ' ' + status + ' clears private preview');
  }
  for (const action of ['return', 'route', 'identity']) {
    const wait = lib.deferred(); const h = harness(dto('APPROVED'), (u, opts, normal, respond) => workflowUrl(u) ? wait.promise.then(respond) : normal());
    await h.mount(); await h.open(); const reading = h.click('preview');
    for (let i = 0; h.workflowRequests().length === 0 && i < 20; i++) await lib.tick();
    check(h.workflowRequests().length === 1, 'deferred workflow request started');
    if (action === 'return') await h.click('return'); else if (action === 'route') h.leave(); else h.sandbox.state.user = { uid: 99, role: 'admin' };
    wait.resolve(dto('APPROVED').workflow); await reading;
    check(action === 'return' ? !h.viewer().textContent : !h.modal(), action + ' cannot revive delayed preview/status');
  }
  const dirty = harness(dto('APPROVED')); await dirty.mount(); await dirty.open(); await dirty.edit('issues', '尚未保存的本地修改'); await dirty.click('preview');
  check(dirty.status().includes('已通过') && !dirty.viewer().textContent.includes('尚未保存的本地修改'), 'approved preview uses saved body only');
  await dirty.click('return'); check(dirty.field('issues').value === '尚未保存的本地修改', 'local edits survive read-only preview');
  check(dirty.workflowRequests().every(r => r.opts.method === 'GET' && r.opts.signal), 'status reads retain authenticated bounded cancellation transport');
  console.log(JSON.stringify({ suite: 'm08-summary-state-version', checks, status: 'passed', source: candidate, browser: false, database: false }));
})().catch(error => { console.error(error); process.exitCode = 1; });
