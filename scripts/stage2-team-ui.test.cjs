'use strict';
// Existing minimal DOM substitute; synthetic transport only. No browser, package install or listener.
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm'), assert = require('node:assert/strict');
const base = fs.readFileSync(path.join(__dirname, 'M01-account-bindings-check.mjs'), 'utf8');
const { NodeStub } = vm.runInNewContext(base.slice(base.indexOf('const decode ='), base.indexOf('\nfunction fixture()')) + '\n({ NodeStub });', {});
const source = fs.readFileSync(path.join(__dirname, '../web/modules/intake/team-todos.js'), 'utf8');
const deferred = () => { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; };
const tick = () => new Promise(yes => setImmediate(yes));
let checks = 0;
const check = (value, label) => { assert.ok(value, label); checks++; };
const fixture = () => [
  { id: 1, workflow_version: 4, version: 0, title: '合成需求 A', organization_code: '001', category_text: '亲子财商', ready_reason: '审批已通过', status: 'unclaimed', status_label: '未认领', owner_code: '', owner_available: true, mine: false, members: [{ code: 'P5', label: '合成甲' }, { code: 'P7', label: '合成乙' }], actions: { claim: true, follow_up: false, accept: true }, history: [] },
  { id: 2, workflow_version: 6, version: 2, title: '合成需求 B', organization_code: '001', ready_reason: '已中标', status: 'following', status_label: '跟进中', owner_code: 'P5', owner_available: true, mine: true, last_note: '等待档期', members: [{ code: 'P5', label: '合成甲' }, { code: 'P7', label: '合成乙' }], actions: { claim: true, follow_up: true, accept: true }, history: [{ operation: 'claim', actor_code: 'P5', owner_code: 'P5', note: '先联系', created_at: '2026-10-26T10:00:00Z' }] },
  { id: 3, workflow_version: 7, version: 1, title: '合成已承接', organization_code: '001', ready_reason: '审批已通过', status: 'accepted', status_label: '已承接', owner_code: 'P7', owner_available: true, accepted_by: 'P7', accepted_at: '2026-10-26T11:00:00Z', mine: false, project_id: 9, members: [], actions: {}, history: [{ operation: 'claim', actor_code: 'P7', owner_code: 'P7', note: '已联系', created_at: '2026-10-26T10:00:00Z' }] },
];
let component;
function harness(override) {
  const h = { root: new NodeStub(), items: fixture(), calls: [], navigation: [], current: true, signal: new AbortController() };
  const normal = async (route, options) => {
    if (route === '/team-todos') return { items: structuredClone(h.items) };
    const item = h.items.find(item => item.id === options.body.id);
    item.version++; item.workflow_version++; item.last_note = options.body.note;
    if (route === '/team-todos/claim') { item.owner_code = options.body.owner_code; item.status = 'following'; item.status_label = '跟进中'; item.mine = item.owner_code === 'P5'; item.actions.follow_up = item.mine; }
    return { item: structuredClone(item) };
  };
  h.host = { api: async (route, options = {}) => { h.calls.push({ route, options: { ...options, ...(options.body ? { body: structuredClone(options.body) } : {}) } }); return override ? override(route, options, h, normal) : normal(route, options); }, signal: h.signal.signal, isCurrent: () => h.current, openDemand: (row, action) => h.navigation.push({ row, action }), openProject: id => h.navigation.push({ project: id }) };
  h.mount = async () => { h.cleanup = await component.mountTeamTodos(h.root, h.host); };
  h.emit = async (node, type = 'click') => { assert.ok(node, 'event target exists'); const event = { target: node, type }; for (let at = node; at; at = at.parentNode) for (const handler of at.listeners.get(type) || []) await handler(event); await tick(); };
  h.card = id => h.root.querySelector(`[data-todo-id="${id}"]`);
  h.click = (id, action) => h.emit(h.card(id).querySelector(`[data-todo-action="${action}"]`));
  h.filter = key => h.emit(h.root.querySelector(`[data-todo-filter="${key}"]`));
  h.posts = () => h.calls.filter(call => call.options.body);
  return h;
}
(async () => {
  component = await import('data:text/javascript;base64,' + Buffer.from(source).toString('base64'));
  const named = { ...fixture()[2], owner_label: '合成乙', accepted_by_label: '合成乙' };
  check(component.renderItem(named).includes('跟进负责人：</b>合成乙（P7）') && component.renderItem(named).includes('承接人：合成乙（P7）'), 'accepted names and stable codes render without editing candidates');
  check(component.visibleItems([named], 'accepted', '合成乙').length === 1, 'authorized owner display name is searchable');
  check(component.renderItem(fixture()[2]).includes('承接人：P7') && !component.renderItem(fixture()[2]).includes('P7（P7）'), 'missing trusted display name keeps code');
  check(!component.renderItem({ ...named, owner_label: '<img src=x>', accepted_by_label: '<script>x</script>' }).includes('<script>'), 'trusted-label fields remain escaped text');
  check(!source.includes('class="filter-tabs"'), 'team tabs do not collide with original demand status selectors');
  const h = harness(); await h.mount();
  check(h.card(1) && h.card(2) && !h.card(3), 'default active queue excludes accepted');
  await h.filter('mine'); check(!h.card(1) && h.card(2), 'my following excludes unclaimed and accepted');
  await h.filter('accepted'); check(h.card(3) && !h.card(3).querySelector('textarea'), 'completed history is readonly');
  await h.click(3, 'project'); check(h.navigation.at(-1).project === 9, 'existing project workspace navigation');
  await h.filter('active'); await h.click(1, 'accept'); check(h.navigation.at(-1).action === 'ACCEPT' && h.navigation.at(-1).row.id === 1 && h.posts().length === 0, 'accept uses original dialog without direct mutation');
  await h.click(1, 'detail'); check(h.navigation.at(-1).action === '', 'detail uses original demand dialog');
  h.card(1).querySelector('select').value = 'P5'; await h.click(1, 'claim');
  check(h.posts()[0].options.body.expected_version === 4 && h.posts()[0].options.body.expected_todo_version === 0, 'claim sends both authoritative versions');
  check(h.posts()[0].options.body.owner_code === 'P5' && !Object.hasOwn(h.posts()[0].options.body, 'team_code'), 'identity scope is not sent by client');
  check(h.card(1).textContent.includes('跟进中'), 'server refresh determines following state');
  h.card(2).querySelector('textarea').value = '本周继续沟通'; await h.click(2, 'follow-up');
  check(h.posts().at(-1).options.body.note === '本周继续沟通', 'owner can record follow-up');
  h.card(2).querySelector('select').value = 'P7'; await h.click(2, 'claim');
  check(h.posts().length === 2 && h.root.textContent.includes('请填写本次跟进或改派说明'), 'reassignment needs explicit reason');
  h.card(2).querySelector('textarea').value = '由乙继续'; await h.click(2, 'claim'); check(h.posts().length === 3, 'same team reassignment submitted');
  const search = h.root.querySelector('[data-todo-search]'); search.value = '亲子财商'; await h.emit(search, 'input'); check(h.card(1) && !h.card(2), 'search filters category');
  h.cleanup(); check(h.calls.every(call => call.options.signal?.aborted), 'cleanup aborts every network request');

  const retry = harness((route, options, h, normal) => { if (options.body && h.posts().length === 1) throw new Error('合成连接失败'); return normal(route, options); }); await retry.mount(); retry.card(1).querySelector('select').value = 'P5'; await retry.click(1, 'claim'); await retry.click(1, 'claim');
  check(retry.posts()[0].options.body.request_id === retry.posts()[1].options.body.request_id, 'uncertain retry retains request id'); retry.cleanup();

  const conflict = harness((route, options, h, normal) => { if (options.body) { h.items[0].status = 'following'; h.items[0].status_label = '跟进中'; h.items[0].owner_code = 'P7'; throw Object.assign(new Error('已被其他人认领'), { status: 409 }); } return normal(route, options); });
  await conflict.mount(); conflict.card(1).querySelector('select').value = 'P5'; await conflict.click(1, 'claim');
  check(conflict.calls.filter(call => call.route === '/team-todos').length === 2 && conflict.root.textContent.includes('已重新读取'), 'conflict reloads authoritative state and explains'); conflict.cleanup();

  const pending = deferred(); const late = harness(() => pending.promise); const mounting = late.mount(); await tick(); late.signal.abort(); late.root.innerHTML = '<p>后续页面</p>'; pending.resolve({ items: fixture() }); await mounting;
  check(late.root.textContent === '后续页面', 'late load cannot overwrite later page');
  const write = deferred(); const canceled = harness((route, options, h, normal) => options.body ? write.promise : normal(route, options)); await canceled.mount(); canceled.card(1).querySelector('select').value = 'P5'; const saving = canceled.click(1, 'claim'); await tick();
  check(canceled.card(1).querySelector('[data-todo-action="claim"]').disabled, 'pending mutation disables duplicate clicks'); canceled.signal.abort(); canceled.root.innerHTML = '<p>退出后页面</p>'; write.resolve({ item: fixture()[0] }); await saving;
  check(canceled.root.textContent === '退出后页面' && canceled.calls.length === 2, 'late mutation completion cannot render or refresh after exit');
  const bad = { ...fixture()[0], title: '<img src=x onerror=alert(1)>', owner_code: '"/><script>boom</script>', last_note: '<b>note</b>', history: [{ operation: 'follow-up', note: '<script>history</script>' }] };
  const html = component.renderItem(bad); check(!html.includes('<img') && !html.includes('<script>') && html.includes('&lt;b&gt;'), 'stored text and history escaped');
  const none = harness(() => ({ items: [] })); await none.mount(); check(none.root.textContent.includes('当前没有待承接事项'), 'empty authorized scope clearly displayed'); none.cleanup();
  console.log(`Stage2TeamUI: ${checks} checks passed; synthetic DOM and transport only`);
})().catch(error => { console.error(error); process.exitCode = 1; });
