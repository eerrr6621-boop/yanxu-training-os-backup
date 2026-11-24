const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const time = value => { const date = new Date(value); return value && Number.isFinite(date.getTime()) ? date.toLocaleString('zh-CN', { hour12: false }) : '—'; };
const personLabel = (code, label) => label && label !== code ? `${label}（${code}）` : code;
const requestId = () => `team-todo-${globalThis.crypto?.randomUUID?.() || `${Date.now()}-${Math.random().toString(36).slice(2)}`}`;

export function visibleItems(items, filter = 'active', keyword = '') {
  const key = keyword.trim().toLocaleLowerCase();
  return items.filter(item => (filter === 'active' ? item.status !== 'accepted' : filter === 'mine' ? item.mine && item.status !== 'accepted' : item.status === filter)
    && (!key || [item.title, item.owner_code, item.owner_label, item.organization_code, item.category_text].some(value => String(value || '').toLocaleLowerCase().includes(key))));
}

export function renderItem(item) {
  const members = Array.isArray(item.members) ? item.members : [];
  const owner = item.owner_code ? personLabel(item.owner_code, item.owner_label || members.find(person => person.code === item.owner_code)?.label) : '待认领';
  const history = (item.history || []).map(event => `<li><strong>${esc(event.operation === 'claim' ? '负责人调整' : '跟进记录')}</strong> · ${esc(event.actor_code)} · ${esc(time(event.created_at))}<p>${esc(event.owner_code)}${event.note ? `：${esc(event.note)}` : ''}</p></li>`).join('');
  const canEdit = item.status !== 'accepted';
  return `<article class="team-todo-item" data-todo-id="${esc(item.id)}">
    <div class="team-todo-line"><div><h3>${esc(item.title)}</h3><p>${esc(item.ready_reason)} · ${esc(item.organization_code)}${item.category_text ? ` · ${esc(item.category_text)}` : ''}${item.expected_start_date ? ` · 计划 ${esc(item.expected_start_date)}` : ''}</p></div><span class="team-todo-state">${esc(item.status_label)}</span></div>
    <p><b>跟进负责人：</b>${esc(owner)}${item.mine ? '（我）' : ''}${!item.owner_available && item.status !== 'accepted' ? ' · 原负责人当前不可办理，请改派' : ''}</p>
    ${item.last_note ? `<p class="team-todo-note">${esc(item.last_note)}</p>` : ''}
    ${item.status === 'accepted' ? `<p>承接人：${esc(personLabel(item.accepted_by, item.accepted_by_label))} · ${esc(time(item.accepted_at))}</p>` : ''}
    <div class="toolbar"><button type="button" class="btn gray" data-todo-action="detail">需求详情</button>${item.actions?.accept ? '<button type="button" class="btn" data-todo-action="accept">承接并立项</button>' : ''}${item.status === 'accepted' && item.project_id ? '<button type="button" class="btn gray" data-todo-action="project">查看项目</button>' : ''}</div>
    ${canEdit && (item.actions?.claim || item.actions?.follow_up) ? `<details class="team-todo-editor"><summary>${item.status === 'unclaimed' ? '认领或指定负责人' : '记录跟进 / 改派'}</summary>
      ${item.actions?.claim ? `<label>跟进负责人<select data-todo-owner aria-label="跟进负责人"><option value="">请选择同团队人员</option>${members.map(person => `<option value="${esc(person.code)}" ${person.code === item.owner_code ? 'selected' : ''}>${esc(person.label)}（${esc(person.code)}）</option>`).join('')}</select></label>` : ''}
      <label>跟进说明<textarea data-todo-note rows="2" maxlength="2000" placeholder="记录联系进展、下一步安排；改派时请说明原因"></textarea></label>
      <div class="toolbar">${item.actions?.claim ? `<button type="button" class="btn gray" data-todo-action="claim">${item.status === 'unclaimed' ? '确认认领 / 指定' : '保存改派'}</button>` : ''}${item.actions?.follow_up ? '<button type="button" class="btn" data-todo-action="follow-up">保存跟进</button>' : ''}</div>
    </details>` : ''}
    ${history ? `<details class="team-todo-history"><summary>查看跟进记录（${item.history.length}）</summary><ol>${history}</ol></details>` : ''}
  </article>`;
}

/** Mount only inside the original demands page. The host owns navigation and the existing acceptance dialog. */
export async function mountTeamTodos(root, context) {
  if (!root || typeof context?.api !== 'function') throw new TypeError('承接待办需要原页面接口');
  const controller = new AbortController();
  let disposed = false, items = [], filter = 'active', keyword = '', serial = 0, busy = false;
  const retries = new Map();
  const active = () => !disposed && !controller.signal.aborted && context.isCurrent?.() !== false;
  const cleanup = () => { if (disposed) return; disposed = true; controller.abort(); root.removeEventListener('click', onClick); root.removeEventListener('input', onInput); context.signal?.removeEventListener('abort', cleanup); };
  if (context.signal?.aborted || !active()) { cleanup(); return cleanup; }
  context.signal?.addEventListener('abort', cleanup, { once: true });
  root.innerHTML = `<section class="card team-todos" aria-label="教研团队承接待办">
    <style>.team-todos{margin:0 0 20px}.team-todos .card-heading{gap:16px;flex-wrap:wrap}.team-todos .team-todo-item{border-top:1px solid #e4e8ef;padding:18px 0}.team-todos .team-todo-line{display:flex;align-items:flex-start;justify-content:space-between;gap:16px}.team-todos h3{font-size:16px;margin:0 0 6px}.team-todos p{font-size:13px;line-height:1.65;margin:6px 0;color:#526078}.team-todos .team-todo-state{white-space:nowrap;background:#eef3fb;color:#315d91;border-radius:16px;padding:4px 10px;font-size:12px}.team-todos details{font-size:13px;margin-top:12px}.team-todos summary{cursor:pointer;color:#315d91}.team-todos label{display:block;font-size:13px;margin:12px 0}.team-todos select,.team-todos textarea{display:block;width:100%;max-width:650px;margin-top:6px;border:1px solid #ccd6e5;border-radius:7px;padding:9px;background:#fff;color:#243248;font:inherit}.team-todos .team-todo-note,.team-todos li p{white-space:pre-wrap;overflow-wrap:anywhere}.team-todos [data-todo-status][data-error=true]{color:#a52525}.team-todos [data-todo-list]{max-height:640px;overflow:auto}.team-todos .toolbar{flex-wrap:wrap}.team-todos .team-todo-tabs{display:flex;gap:8px;flex-wrap:wrap;margin:14px 0}.team-todos .team-todo-tabs button{border:1px solid #dce3ef;background:#fff;border-radius:8px;padding:8px 12px;color:#526078;cursor:pointer;font:inherit;font-size:13px}.team-todos .team-todo-tabs button.active{background:#eef3fb;border-color:#9bb7df;color:#315d91}.team-todos .team-todo-tabs span{margin-left:6px}.team-todos [aria-busy=true]{opacity:.7}</style>
    <div class="card-heading"><div><h2>教研团队承接待办</h2><p>已批准或中标的需求在这里认领、跟进，承接后进入项目工作区。</p></div><button type="button" class="btn gray" data-todo-action="refresh">刷新待办</button></div>
    <div class="team-todo-tabs" data-todo-tabs aria-label="筛选承接待办"></div>
    <div class="toolbar"><label class="search-box"><span class="sr-only">搜索承接待办</span><input data-todo-search placeholder="搜索需求、负责人或机构" autocomplete="off"></label></div>
    <p data-todo-status role="status" aria-live="polite">正在读取承接待办…</p><div data-todo-list></div>
  </section>`;
  const status = (message, error = false) => { if (!active()) return; const node = root.querySelector('[data-todo-status]'); node.textContent = message; node.dataset.error = String(error); };
  const render = () => {
    if (!active()) return;
    const counts = { active: items.filter(item => item.status !== 'accepted').length, mine: items.filter(item => item.mine && item.status !== 'accepted').length };
    for (const key of ['unclaimed', 'following', 'accepted']) counts[key] = items.filter(item => item.status === key).length;
    root.querySelector('[data-todo-tabs]').innerHTML = [['active', '待承接'], ['mine', '我的跟进'], ['unclaimed', '未认领'], ['following', '跟进中'], ['accepted', '已承接记录']].map(([key, label]) => `<button type="button" data-todo-filter="${key}" class="${filter === key ? 'active' : ''}" aria-pressed="${filter === key}">${label} <span>${counts[key]}</span></button>`).join('');
    const visible = visibleItems(items, filter, keyword);
    root.querySelector('[data-todo-list]').innerHTML = visible.length ? visible.map(renderItem).join('') : `<p>${keyword ? '没有符合搜索条件的事项。' : filter === 'accepted' ? '暂无本团队已承接记录。' : filter === 'mine' ? '你目前没有跟进中的事项。' : '当前没有待承接事项。'}</p>`;
  };
  async function refresh(message = '') {
    const ticket = ++serial;
    try {
      const data = await context.api('/team-todos', { signal: controller.signal, quiet: true });
      if (!active() || ticket !== serial) return;
      items = Array.isArray(data?.items) ? data.items : []; render();
      status(message || `待承接 ${items.filter(item => item.status !== 'accepted').length} 项；已承接记录可在上方切换查看。`);
    } catch (error) { if (active() && ticket === serial && error?.name !== 'AbortError') status(error?.message || '承接待办读取失败，请刷新重试。', true); }
  }
  function onInput(event) { if (event.target.matches('[data-todo-search]')) { keyword = event.target.value; render(); } }
  async function onClick(event) {
    const button = event.target.closest('button'); if (!button || !root.contains(button) || !active() || busy) return;
    if (button.dataset.todoFilter) { filter = button.dataset.todoFilter; render(); return; }
    const action = button.dataset.todoAction; if (!action) return;
    if (action === 'refresh') { await refresh(); return; }
    const card = button.closest('[data-todo-id]'), item = items.find(item => String(item.id) === card?.dataset.todoId); if (!item) return;
    if (action === 'detail' || action === 'accept') { context.openDemand?.({ id: item.id }, action === 'accept' ? 'ACCEPT' : ''); return; }
    if (action === 'project') { context.openProject?.(item.project_id); return; }
    const note = card.querySelector('[data-todo-note]')?.value.trim() || '';
    const owner = card.querySelector('[data-todo-owner]')?.value || '';
    if (action === 'claim' && (!owner || owner === item.owner_code)) { status('请选择新的跟进负责人；负责人不变时可保存跟进。', true); return; }
    if ((action === 'follow-up' || item.status !== 'unclaimed') && !note) { status('请填写本次跟进或改派说明。', true); return; }
    const body = { id: item.id, expected_version: item.workflow_version, expected_todo_version: item.version, note, ...(action === 'claim' ? { owner_code: owner } : {}) };
    const key = `${action}:${JSON.stringify(body)}`;
    if (!retries.has(key)) retries.set(key, requestId());
    body.request_id = retries.get(key);
    busy = true; card.setAttribute('aria-busy', 'true'); card.querySelectorAll('button').forEach(node => { node.disabled = true; });
    try {
      await context.api(`/team-todos/${action}`, { method: 'POST', body, signal: controller.signal, quiet: true });
      if (!active()) return;
      retries.delete(key); await refresh(action === 'claim' ? '负责人已保存。' : '跟进已保存。');
    } catch (error) {
      if (!active() || error?.name === 'AbortError') return;
      if ([403, 409].includes(error.status || error.code)) { retries.delete(key); await refresh(); status(`${error.message || '事项或权限已变化'}。已重新读取，请核对后办理。`, true); }
      else status(error?.message || '保存结果暂未确认，可重试本次操作。', true);
    } finally { busy = false; if (active()) { card.removeAttribute('aria-busy'); card.querySelectorAll('button').forEach(node => { node.disabled = false; }); } }
  }
  root.addEventListener('click', onClick); root.addEventListener('input', onInput);
  await refresh();
  return cleanup;
}
