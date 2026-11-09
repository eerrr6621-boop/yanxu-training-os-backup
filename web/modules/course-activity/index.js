// Uses the existing host modal/API and existing account-scoped course and delivery permissions.
const API = '/course-activity';
const display = value => value === null || value === undefined || value === '' ? '—' : String(value);
const inert = () => ({ ready: Promise.resolve(false), refresh: async () => false, destroy() {} });
const safeId = value => Number.isSafeInteger(value) && value > 0;
export function openCourseActivity(context) {
  if (context.signal?.aborted || context.isCurrent?.() === false) return inert();
  let mounted, closed = false;
  const onClose = () => { closed = true; context.signal?.removeEventListener('abort', destroy); mounted?.destroy(); };
  const mask = context.openModal('按标准课程统计授课', '<div data-course-activity></div>', { wide: true, noFoot: true, onClose });
  if (!mask) return inert();
  mounted = mountCourseActivity(mask.querySelector('[data-course-activity]'), { ...context, isCurrent: () => (context.isCurrent?.() ?? true) && (!context.getModal || context.getModal() === mask) });
  function destroy() { mounted?.destroy(); context.signal?.removeEventListener('abort', destroy); if (!closed && (!context.getModal || context.getModal() === mask)) context.closeModal?.(true); closed = true; }
  context.signal?.addEventListener('abort', destroy, { once: true });
  return { ready: mounted.ready, refresh: mounted.refresh, destroy };
}

export function mountCourseActivity(root, context) {
  if (!root?.ownerDocument || typeof context.api !== 'function') throw new TypeError('Course activity requires a DOM root and host API');
  const doc = root.ownerDocument, identity = context.getUser?.();
  let alive = true, epoch = 0, controller, busy = false, scope = '', report = null, detail = null, pending = null;
  const active = () => alive && !context.signal?.aborted && (context.isCurrent?.() ?? true) && (!context.getUser || context.getUser() === identity);
  const node = (tag, text, className) => { const el = doc.createElement(tag); if (text !== undefined) el.textContent = display(text); if (className) el.className = className; return el; };
  const button = (label, action) => { const el = node('button', label, 'btn gray sm'); el.type = 'button'; el.dataset.action = action; return el; };
  const wrap = node('section'), toolbar = node('div', undefined, 'toolbar'), select = node('select');
  select.setAttribute('aria-label', '课程目录范围'); select.style.maxWidth = '100%';
  const refresh = button('刷新统计', 'refresh'); toolbar.append(select, refresh);
  const notice = node('p'); notice.setAttribute('role', 'status'); notice.setAttribute('aria-live', 'polite');
  const content = node('div'), editor = node('div'); wrap.append(toolbar, notice, content, editor); root.replaceChildren(wrap);
  const errorText = error => error?.message || '暂时无法读取，请重试。';
  function control() { select.disabled = busy || !!pending; refresh.disabled = busy || !!pending; for (const el of editor.querySelectorAll('input,select,textarea,button')) el.disabled = busy || !!pending && el.dataset.action !== 'save'; }
  function clear() { report = null; detail = null; pending = null; content.replaceChildren(); editor.replaceChildren(); }
  function table(columns, rows, empty = '暂无记录') {
    if (!rows.length) return node('p', empty);
    const box = node('div', undefined, 'table-wrap'), t = node('table', undefined, 'tbl'), head = node('thead'), tr = node('tr');
    for (const [label] of columns) tr.append(node('th', label)); head.append(tr); t.append(head); const body = node('tbody');
    for (const row of rows) { const line = node('tr'); for (const [, get] of columns) { const cell = node('td'), value = get(row); cell.style.whiteSpace = 'normal'; cell.style.overflowWrap = 'anywhere'; if (value?.nodeType) cell.append(value); else cell.textContent = display(value); line.append(cell); } body.append(line); }
    t.append(body); box.append(t); return box;
  }
  function associationButton(row) { const b = button(row.association_status === 'SOURCE_CHANGED' ? '重新核对关联' : row.association_status === 'LINKED' ? '查看 / 更正关联' : '关联标准课程', 'detail'); b.dataset.dispatch = row.dispatch_id; return b; }
  function renderReport() {
    content.replaceChildren();
    content.append(node('p', `已核对完成授课 ${report.completed_teaching_count} 次 · 已关联 ${report.linked_teaching_count} 次 · 去重标准课程 ${report.unique_course_count} 门 · 已关联授课教师 ${report.actual_teacher_count} 人 · 待关联 ${report.unlinked_teaching_count} 次`));
    content.append(node('p', report.notice));
    content.append(table([
      ['标准课程编码', r => r.course_code], ['标准课程名称', r => r.course_name], ['当前状态', r => r.active ? '启用' : '停用（保留历史）'],
      ['授课次数', r => r.teaching_count], ['实际授课教师', r => r.teachers.map(t => `${display(t.teacher_name)}（档案 ${t.teacher_id}，${t.teaching_count} 次）`).join('、') || '尚无已关联授课'],
    ], report.courses, '此目录尚未建立标准课程。'));
    const columns = [['项目', r => r.project_title], ['原排课主题', r => r.subject], ['日期', r => r.teach_date], ['实际教师', r => r.teacher_name], ['操作', associationButton]];
    content.append(node('h3', '已完成但尚未关联标准课程'), table(columns, report.unlinked, '当前可见范围没有待关联的已核对完成授课。'));
    content.append(node('h3', '已关联授课明细'), table([['标准课程', r => `${r.course.course_code} · ${r.course.course_name}`], ...columns], report.occurrences));
    content.append(node('h3', '尚不计入授课次数'), table([['排除原因', r => r.exclusion_reason], ...columns], report.not_counted, '无尚未完成或未核对记录。'));
  }
  function validReport(value) {
    return value?.schema_version === 'course_activity_v1' && value.scope_id === Number(scope) && ['courses', 'unlinked', 'occurrences', 'not_counted'].every(k => Array.isArray(value[k])) && ['completed_teaching_count', 'linked_teaching_count', 'unlinked_teaching_count', 'unique_course_count', 'actual_teacher_count'].every(k => Number.isSafeInteger(value[k]) && value[k] >= 0) && value.courses.every(c => typeof c.course_code === 'string' && typeof c.active === 'boolean' && Array.isArray(c.teachers)) && value.linked_teaching_count === value.occurrences.length && value.unlinked_teaching_count === value.unlinked.length && value.completed_teaching_count === value.linked_teaching_count + value.unlinked_teaching_count;
  }
  async function request(work, failurePrefix = '') {
    if (!active()) return false;
    const ticket = ++epoch; controller?.abort(); controller = new AbortController(); busy = true; control();
    try { await work(controller.signal, ticket); return active() && ticket === epoch; }
    catch (error) { if (active() && ticket === epoch && error?.name !== 'AbortError') { if ([401, 403].includes(Number(error?.status || error?.code))) { clear(); select.replaceChildren(); } notice.textContent = failurePrefix + errorText(error); } return false; }
    finally { if (active() && ticket === epoch) { busy = false; control(); } }
  }
  async function loadReport() {
    if (!scope || pending) return false; clear(); notice.textContent = '正在核对当前可见授课与标准课程…';
    return request(async (signal, ticket) => { const value = await context.api(`${API}/scopes/${scope}`, { quiet: true, signal }); if (!active() || ticket !== epoch) return; if (!validReport(value)) throw new Error('统计回执与所选课程范围不一致，请刷新。'); report = value; renderReport(); notice.textContent = `统计日期：${value.as_of_date}。场次按排课唯一编号去重；课酬更正和计酬明细不增加授课次数。`; });
  }
  async function loadScopes() {
    clear(); select.replaceChildren(); scope = ''; notice.textContent = '正在读取可访问的课程范围…';
    const ok = await request(async (signal, ticket) => { const value = await context.api('/course-catalog/scopes', { quiet: true, signal }); if (!active() || ticket !== epoch) return; if (!Array.isArray(value?.scopes)) throw new Error('课程范围回执不完整。'); const scopes = value.scopes.filter(s => s.can_read === true && safeId(s.scope_id)); const empty = node('option', scopes.length ? '请选择课程目录范围' : '尚无可读取的课程目录'); empty.value = ''; select.append(empty); for (const s of scopes) { const opt = node('option', `机构 ${s.organization_code} · 课程目录 ${s.scope_id}`); opt.value = String(s.scope_id); select.append(opt); } if (scopes.length === 1) select.value = scope = String(scopes[0].scope_id); notice.textContent = scopes.length ? '请选择课程目录范围查看授课统计。' : '尚无已授权目录，请先按原流程维护课程范围。'; });
    return ok && scope ? loadReport() : ok;
  }
  async function loadDetail(id) {
    pending = null; detail = null; editor.replaceChildren(); notice.textContent = '正在读取本次授课与可选标准课程…';
    return request(async (signal, ticket) => {
      const value = await context.api(`${API}/dispatches/${id}?scope_id=${scope}`, { quiet: true, signal }); if (!active() || ticket !== epoch) return;
      if (value?.schema_version !== 'course_activity_v1' || value.scope_id !== Number(scope) || value.dispatch?.dispatch_id !== id || !Array.isArray(value.courses) || !Array.isArray(value.history) || !['version', 'fact_version', 'catalog_version'].every(k => Number.isSafeInteger(value[k]) && value[k] >= 0) || !/^[0-9a-f]{64}$/.test(value.source_version || '') || typeof value.can_link !== 'boolean' || !value.courses.every(c => c.scope_id === Number(scope) && typeof c.course_code === 'string' && typeof c.active === 'boolean')) throw new Error('授课关联回执不完整。');
      detail = value; renderDetail(); notice.textContent = '请核对授课记录，明确选择标准课程并填写依据后保存。'; editor.scrollIntoView?.({ block: 'nearest' });
    });
  }
  function renderDetail() {
    editor.replaceChildren(); const d = detail.dispatch; editor.append(node('h3', `授课 ${d.dispatch_id}：${d.project_title}`), node('p', `${d.subject} · ${d.teach_date} · ${d.teacher_name}（档案 ${d.teacher_id}）`));
    editor.append(node('p', detail.current ? `当前关联：${detail.current.course.course_code} · ${detail.current.course.course_name}（版本 ${detail.version}）` : '尚未关联；原排课主题只作核对参考，不用于自动匹配。'));
    if (detail.exclusion_reason) editor.append(node('p', `暂不计入授课次数：${detail.exclusion_reason}`));
    if (detail.can_link) {
      const form = node('div', undefined, 'form-item'), label = node('label', '明确选择标准课程'), options = node('select'); options.dataset.field = 'course'; options.setAttribute('aria-label', '明确选择标准课程');
      const empty = node('option', '请选择，不自动匹配排课主题'); empty.value = ''; options.append(empty);
      for (const c of detail.courses.filter(c => c.active === true)) { const o = node('option', `${c.course_code} · ${c.course_name}`); o.value = c.course_code; options.append(o); } form.append(label, options);
      const note = node('textarea'); note.dataset.field = 'note'; note.maxLength = 1000; note.setAttribute('aria-label', '关联依据或更正说明');
      const confirm = node('input'); confirm.type = 'checkbox'; confirm.dataset.field = 'confirm'; const confirmation = node('label'); confirmation.append(confirm, doc.createTextNode(' 我已核对，本次授课应归入所选标准课程'));
      editor.append(form, node('label', '关联依据或更正说明'), note, confirmation, button('确认保存关联', 'save'));
    } else editor.append(node('p', '当前账号或授课状态仅支持查看关联。'));
    editor.append(button('关闭关联详情', 'close-detail'), table([['关联版本', r => r.version], ['标准课程', r => `${r.course.course_code} · ${r.course.course_name}`], ['依据 / 更正说明', r => r.evidence_note], ['操作人员', r => r.actor_person], ['时间', r => r.created_at]], [...detail.history].reverse(), '暂无关联修订。'));
  }
  async function save() {
    if (!active() || busy || !detail?.can_link) return false;
    if (!pending) {
      const course = editor.querySelector('[data-field="course"]').value, note = editor.querySelector('[data-field="note"]').value.trim(), checked = editor.querySelector('[data-field="confirm"]').checked;
      if (!course || !note || !checked) { notice.textContent = '请选择具体标准课程、填写依据并勾选确认。'; return false; }
      if (!globalThis.crypto?.randomUUID) { notice.textContent = '无法建立本次保存标识，请刷新后重试。'; return false; }
      pending = { scope_id: Number(scope), course_code: course, expected_version: detail.version, expected_fact_version: detail.fact_version, expected_source_version: detail.source_version, expected_catalog_version: detail.catalog_version, evidence_note: note, confirm: true, request_id: `COURSE-${globalThis.crypto.randomUUID()}` };
    }
    const body = pending, id = detail.dispatch.dispatch_id; notice.textContent = '正在保存明确关联…'; let saved = false;
    await request(async (signal, ticket) => {
      try {
        const value = await context.api(`${API}/dispatches/${id}/link`, { method: 'POST', body, quiet: true, signal }); if (!active() || ticket !== epoch) return;
        if (!['LINKED', 'ALREADY_LINKED'].includes(value?.status) || value.dispatch_id !== id || value.version !== body.expected_version + 1 || value.course?.course_code !== body.course_code || value.course?.scope_id !== body.scope_id) throw new Error('保存回执尚未核实');
        pending = null; saved = true;
      } catch (error) { if (!active() || ticket !== epoch) return; if ([400, 401, 403, 404, 409].includes(Number(error?.status || error?.code))) { pending = null; detail = null; editor.replaceChildren(); throw error; } notice.textContent = '保存结果尚未核实，请点击确认保存重试同一次请求。'; }
    });
    if (saved && active()) { const ok = await loadReport(); if (ok) notice.textContent = '授课课程关联已保存，统计已更新。'; else if (active()) notice.textContent = '关联已保存；统计刷新失败，请稍后刷新核对。'; } return saved;
  }
  function click(event) { const b = event.target.closest?.('button[data-action]'); if (!b || !wrap.contains(b) || b.disabled || busy || !active()) return; const action = b.dataset.action;
    if (action === 'save') return save(); if (pending) return;
    if (action === 'refresh') return scope ? loadReport() : loadScopes(); if (action === 'detail') return loadDetail(Number(b.dataset.dispatch)); if (action === 'close-detail') { detail = null; editor.replaceChildren(); }
  }
  const change = () => { if (active() && !busy && !pending) { scope = select.value; clear(); if (scope) loadReport(); } };
  wrap.addEventListener('click', click); select.addEventListener('change', change);
  function destroy() { if (!alive) return; alive = false; ++epoch; controller?.abort(); clear(); select.replaceChildren(); notice.textContent = ''; wrap.removeEventListener('click', click); select.removeEventListener('change', change); context.signal?.removeEventListener('abort', destroy); }
  context.signal?.addEventListener('abort', destroy, { once: true });
  return { ready: loadScopes(), refresh: loadReport, destroy };
}
