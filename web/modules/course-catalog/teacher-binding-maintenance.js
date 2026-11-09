// Add-only teacher binding editor. Business codes are explicitly supplied by the user.
const SIZE = 20;
const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
const integer = value => Number.isSafeInteger(value) && value >= 0;
const code = value => typeof value === 'string' && /^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/.test(value);
const uuid = value => typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(value);
const sameSet = (a, b) => Array.isArray(a) && a.length === b.length && new Set(a).size === a.length && a.every(value => b.includes(value));
const nullableText = value => value === null || typeof value === 'string';
const validBindings = list => Array.isArray(list) && list.length <= 5000 && list.every(row => row && code(row.teacher_code) && integer(row.teacher_id) && row.teacher_id > 0) && new Set(list.map(row => row.teacher_code)).size === list.length && new Set(list.map(row => row.teacher_id)).size === list.length;
function onlyAdditions(changes, additions) {
  return changes && ['courses', 'teachers', 'certifications', 'bindings'].every(key => changes[key] && ['added', 'updated', 'removed'].every(op => sameSet(changes[key][op], op === 'added' && ['teachers', 'bindings'].includes(key) ? additions : [])));
}
function errorText(error) {
  const status = Number(error?.status || error?.code);
  if (status === 401) return '登录状态已失效，请重新登录。';
  if (status === 403) return '当前已无教师绑定维护权限，请重新核对授权。';
  if (status === 409) return '目录版本、师资档案或组织权限已变化，请刷新后核对再填写。';
  return error?.message || '暂时无法完成教师绑定，请稍后重试。';
}
export function mountTeacherBindingMaintenance(root, context, scope) {
  const doc = root.ownerDocument, identity = context.getUser?.(), url = `/course-catalog/scopes/${scope.scope_id}`;
  let alive = true, epoch = 0, controller, current = null, bindings = [], catalogVersion = '', comment = '', search = '', candidatePage = 0, draftPage = 0;
  let busy = false, preview = null, unknown = false, leaveAction = null;
  const node = (tag, value, className) => { const el = doc.createElement(tag); if (value !== undefined) el.textContent = value; if (className) el.className = className; return el; };
  const button = (value, action) => { const el = node('button', value, 'btn gray sm'); el.type = 'button'; el.dataset.bindingAction = action; return el; };
  const wrap = node('section'); wrap.setAttribute('data-teacher-binding-maintenance', ''); wrap.setAttribute('aria-label', '绑定教师');
  const notice = node('p'); notice.setAttribute('role', 'status'); notice.setAttribute('aria-live', 'polite'); notice.setAttribute('data-binding-notice', '');
  const summary = node('p'), form = node('div'), candidateArea = node('div'), draftArea = node('div'), result = node('div'), guard = node('div'); guard.setAttribute('role', 'status');
  const actions = node('div', undefined, 'toolbar'); actions.style.flexWrap = 'wrap';
  const previewButton = button('保存绑定预览', 'preview'), confirmButton = button('确认保存绑定', 'confirm'), refreshButton = button('刷新可选教师', 'refresh');
  previewButton.className = confirmButton.className = 'btn sm'; actions.append(previewButton, confirmButton, refreshButton);
  wrap.append(node('h3', '绑定教师'), node('p', '从本人可见的教师档案中选择，再填写已有或已核定的讲师业务编码。档案 ID 仅为系统内部引用，不是教师业务码；业务码未知时请留空并先核实。已绑定档案与编码永久保留，不能重新绑定。'), node('p', '绑定只建立目录与档案的关联，不表示取得任何课程认证；认证需在“维护认证”中单独记录。'), notice, summary, form, candidateArea, draftArea, actions, result, guard); root.append(wrap);
  const active = () => alive && !context.signal?.aborted && (context.isCurrent?.() ?? true) && (!context.getUser || context.getUser() === identity);
  function permitted() { if (active() && scope.can_manage === true) return true; destroy(); return false; }
  const dirty = () => !!current && (bindings.length > 0 || catalogVersion !== (current.catalog_version || '') || comment !== '') || !!preview || unknown;
  const available = row => row.teacher_code === null && !bindings.some(binding => binding.teacher_id === row.teacher_id);
  function controls() {
    const locked = busy || unknown || !current;
    for (const field of wrap.querySelectorAll('[data-binding-field], [data-binding-search]')) field.disabled = locked;
    for (const target of wrap.querySelectorAll('[data-binding-choice]')) target.disabled = locked || !available(current.candidates[Number(target.dataset.bindingChoice)]) || current.bindings.length + bindings.length >= 5000;
    for (const target of wrap.querySelectorAll('[data-binding-remove]')) target.disabled = locked;
    previewButton.disabled = locked || !bindings.length || bindings.some(binding => !code(binding.teacher_code));
    confirmButton.disabled = busy || !preview?.ready; confirmButton.style.display = preview?.ready ? '' : 'none'; confirmButton.textContent = unknown ? '重试确认本次绑定' : '确认保存绑定'; refreshButton.disabled = busy;
    for (const target of wrap.querySelectorAll('[data-binding-action="candidate-previous"], [data-binding-action="candidate-next"], [data-binding-action="draft-previous"], [data-binding-action="draft-next"]')) {
      const [type, direction] = target.dataset.bindingAction.split('-'), page = type === 'candidate' ? candidatePage : draftPage, count = type === 'candidate' ? filteredCandidates().length : bindings.length;
      target.disabled = locked || (direction === 'previous' ? page === 0 : (page + 1) * SIZE >= count);
    }
  }
  function invalidate() { preview = null; unknown = false; result.replaceChildren(); guard.replaceChildren(); leaveAction = null; controls(); }
  function clear() { current = null; bindings = []; catalogVersion = comment = search = ''; candidatePage = draftPage = 0; preview = null; unknown = false; leaveAction = null; form.replaceChildren(); candidateArea.replaceChildren(); draftArea.replaceChildren(); result.replaceChildren(); guard.replaceChildren(); summary.textContent = ''; controls(); }
  function filteredCandidates() { return (current?.candidates || []).map((row, index) => ({ ...row, index })).filter(row => [row.name, row.org, String(row.teacher_id), row.teacher_code || ''].some(value => String(value ?? '').includes(search))); }
  function renderCandidates(reset = false) {
    if (!current) return;
    if (reset) {
      candidateArea.replaceChildren(node('h4', '可见教师档案'));
      const searchField = node('div', undefined, 'form-item'); searchField.style.marginBottom = '12px';
      const input = node('input'); input.type = 'search'; input.value = search; input.placeholder = '搜索姓名、机构、档案 ID 或业务码'; input.setAttribute('aria-label', '按姓名、机构、档案 ID 或已绑定业务码筛选'); input.setAttribute('data-binding-search', '');
      searchField.append(input); candidateArea.append(searchField, node('div'));
    }
    const host = candidateArea.children[candidateArea.children.length - 1], list = filteredCandidates(); candidatePage = Math.max(0, Math.min(candidatePage, Math.ceil(list.length / SIZE) - 1));
    host.innerHTML = context.renderTable([
      { k: 'name', l: '姓名' }, { k: 'org', l: '档案机构' }, { k: 'teacher_id', l: '档案 ID（内部引用）' },
      { k: 'teacher_level', l: '当前等级' }, { k: 'city', l: '当前城市' }, { k: 'status', l: '档案状态' },
      { l: '绑定状态', render: row => row.teacher_code !== null ? `已绑定业务码 ${esc(row.teacher_code)}${row.in_catalog ? ' · 已在本目录' : ' · 永久绑定保留'}` : bindings.some(binding => binding.teacher_id === row.teacher_id) ? '已选，待填写业务码' : '尚未绑定' },
      { l: '选择', render: row => `<button class="btn gray sm" type="button" data-binding-choice="${row.index}">选择此档案</button>` },
    ], list.slice(candidatePage * SIZE, (candidatePage + 1) * SIZE), [], '没有匹配的可见教师档案。');
    const nav = node('div', undefined, 'toolbar'); nav.style.flexWrap = 'wrap'; nav.append(node('span', `共 ${list.length} 个档案`), button('上一页教师', 'candidate-previous'), button('下一页教师', 'candidate-next')); host.append(nav); controls();
  }
  function renderDraft() {
    draftPage = Math.max(0, Math.min(draftPage, Math.ceil(bindings.length / SIZE) - 1));
    draftArea.replaceChildren(node('h4', '本次新增绑定'));
    const host = node('div');
    host.innerHTML = context.renderTable([
      { k: 'name', l: '姓名' }, { k: 'org', l: '档案机构' }, { k: 'teacher_id', l: '档案 ID（内部引用）' },
      { l: '已有／已核定讲师业务编码', render: row => `<div class="form-item" style="min-width:0;width:100%"><input aria-label="已有／已核定讲师业务编码 ${row.index + 1}" data-binding-field="teacher_code" data-row="${row.index}" value="${esc(row.teacher_code)}" maxlength="64" placeholder="未知请留空，先核实" style="width:100%;min-width:0;box-sizing:border-box"></div>` },
      { l: '操作', render: row => `<button type="button" class="btn gray sm" data-binding-remove="${row.index}">移除本次选择</button>` },
    ], bindings.slice(draftPage * SIZE, (draftPage + 1) * SIZE).map((row, index) => ({ ...current.candidates.find(candidate => candidate.teacher_id === row.teacher_id), ...row, index: draftPage * SIZE + index })), [], '请先选择教师档案；每条新增绑定均需明确填写正式业务码。');
    const nav = node('div', undefined, 'toolbar'); nav.style.flexWrap = 'wrap'; nav.append(node('span', `本次新增 ${bindings.length} 条；已有 ${current.bindings.length} 条绑定保持不变`), button('上一页新增', 'draft-previous'), button('下一页新增', 'draft-next')); draftArea.append(host, nav); controls();
  }
  function renderCurrent() {
    summary.textContent = `机构编码：${current.organization_code}；已保存版本：${current.version}；现有 ${current.bindings.length} 条目录绑定。`;
    form.innerHTML = context.renderForm([{ k: 'catalog_version', label: '新的版本名称', required: true }, { k: 'change_comment', label: '绑定说明', type: 'textarea', required: true }], { catalog_version: catalogVersion, change_comment: comment });
    for (const key of ['catalog_version', 'change_comment']) form.querySelector(`[data-k="${key}"]`).dataset.bindingField = key;
    renderCandidates(true); renderDraft(); controls();
  }
  function validCurrent(value) {
    if (!value || value.scope_id !== scope.scope_id || value.organization_code !== scope.organization_code || !integer(value.version) || value.can_manage !== true || !validBindings(value.bindings) || !Array.isArray(value.candidates) || new Set(value.candidates.map(row => row?.teacher_id)).size !== value.candidates.length || !value.candidates.every(row => row && integer(row.teacher_id) && row.teacher_id > 0 && ['name', 'org', 'teacher_level', 'city', 'status'].every(key => nullableText(row[key])) && (row.teacher_code === null || code(row.teacher_code)) && typeof row.in_catalog === 'boolean' && (!row.in_catalog || value.bindings.some(binding => binding.teacher_id === row.teacher_id && binding.teacher_code === row.teacher_code)))) return false;
    const permanentCodes = value.candidates.filter(row => row.teacher_code !== null).map(row => row.teacher_code);
    if (new Set(permanentCodes).size !== permanentCodes.length || value.candidates.some(row => value.bindings.some(binding => binding.teacher_id === row.teacher_id && (binding.teacher_code !== row.teacher_code || !row.in_catalog)))) return false;
    return value.version === 0 ? value.catalog_version === null && value.bindings.length === 0 : typeof value.catalog_version === 'string' && value.catalog_version.length > 0;
  }
  async function refresh(afterSave = false) {
    if (!permitted()) return false;
    const id = ++epoch; controller?.abort(); controller = new AbortController(); busy = true; clear(); notice.textContent = afterSave ? '已保存，正在刷新教师绑定…' : '正在读取可见教师档案与已有绑定…'; controls();
    try {
      const value = await context.api(`${url}/teacher-binding-management`, { quiet: true, signal: controller.signal });
      if (!permitted() || id !== epoch) return false;
      if (!validCurrent(value)) throw new Error('教师绑定资料不完整或与当前课程范围不一致。');
      current = value; catalogVersion = value.catalog_version || ''; renderCurrent(); notice.textContent = afterSave ? '已保存，教师绑定资料已刷新；课程认证未作变更。' : '请选择教师档案并填写正式业务码，再保存预览。'; return true;
    } catch (error) { if (permitted() && id === epoch && error?.name !== 'AbortError') { clear(); notice.textContent = `${afterSave ? '已保存，当前列表刷新失败，请刷新。 ' : ''}${errorText(error)}`; if ([401, 403].includes(Number(error?.status || error?.code))) context.onAccessLost?.(); } return false; }
    finally { if (active() && id === epoch) { busy = false; controls(); } }
  }
  function validPreview(value, body) {
    if (!value || value.schema_version !== 'm04_preview_v1' || value.scope_id !== scope.scope_id || value.organization_code !== scope.organization_code || value.current_version !== current.version || value.catalog_version !== body.catalog_version || typeof value.ready !== 'boolean' || !Array.isArray(value.issues)) return false;
    if (!value.ready) return value.batch_id === null && value.confirmation_required === false && value.changes === null && value.data === null;
    const additions = body.bindings.map(row => row.teacher_code), teachers = value.data?.teachers;
    if (!uuid(value.batch_id) || value.confirmation_required !== true || value.issues.some(issue => issue?.severity === 'error') || !onlyAdditions(value.changes, additions) || value.data?.catalog_version !== body.catalog_version || !Array.isArray(teachers) || !sameSet(teachers.map(row => row?.teacher_code), [...current.bindings.map(row => row.teacher_code), ...additions]) || !Array.isArray(value.data.courses) || !Array.isArray(value.data.certifications) || value.counts?.teachers !== teachers.length || value.counts?.courses !== value.data.courses.length || value.counts?.certifications !== value.data.certifications.length) return false;
    if (!validBindings(value.binding_selections) || !sameSet(value.binding_selections.map(row => row.teacher_code), additions)) return false;
    return body.bindings.every(binding => {
      const actual = teachers.find(row => row.teacher_code === binding.teacher_code), candidate = current.candidates.find(row => row.teacher_id === binding.teacher_id), selection = value.binding_selections.find(row => row.teacher_code === binding.teacher_code);
      return actual && candidate && selection?.teacher_id === binding.teacher_id && selection.name === candidate.name && selection.org === candidate.org && actual.teacher_level === (candidate.teacher_level ?? '') && actual.city === (candidate.city ?? '');
    });
  }
  async function savePreview() {
    if (!permitted() || busy || unknown || !current || !bindings.length) return false;
    if (!validBindings(bindings) || bindings.some(row => !current.candidates.some(candidate => candidate.teacher_id === row.teacher_id && candidate.teacher_code === null) || current.bindings.some(old => old.teacher_code === row.teacher_code || old.teacher_id === row.teacher_id) || current.candidates.some(candidate => candidate.teacher_code === row.teacher_code))) { notice.textContent = '请为每个已选档案填写正式业务码（1–64 位字母、数字、点、下划线或短横线，首位须为字母或数字）；未知、重复或已永久绑定的编码不能提交。'; return false; }
    if (!catalogVersion.trim() || catalogVersion.length > 120 || catalogVersion === current.catalog_version || !comment.trim() || comment.length > 500) { notice.textContent = '请填写新的版本名称（最多 120 字）及绑定说明（最多 500 字）。'; return false; }
    invalidate(); const id = ++epoch; controller?.abort(); controller = new AbortController(); busy = true; controls(); notice.textContent = '正在保存绑定预览…';
    const body = { expected_version: current.version, catalog_version: catalogVersion, bindings: bindings.map(row => ({ teacher_code: row.teacher_code, teacher_id: row.teacher_id })), change_comment: comment };
    try {
      const value = await context.api(`${url}/teacher-bindings-preview`, { method: 'POST', body, quiet: true, signal: controller.signal });
      if (!permitted() || id !== epoch) return false;
      if (!validPreview(value, body)) throw new Error('预览内容或教师姓名、机构已变化，无法核实本次绑定，请刷新可选教师后重新核对。');
      if (!value.ready) { notice.textContent = '预览未通过校验，请修改下列问题后再保存预览。'; result.innerHTML = context.renderTable([{ k: 'message', l: '校验问题' }], value.issues, [], ''); return false; }
      preview = value; notice.textContent = '预览已保存，尚未生效。请核对教师姓名、机构、档案 ID 和业务码，再确认绑定。';
      result.innerHTML = (value.issues.length ? context.renderTable([{ k: 'message', l: '核对提示' }], value.issues, [], '') : '') + context.renderTable([{ k: 'name', l: '姓名' }, { k: 'org', l: '档案机构' }, { k: 'teacher_id', l: '档案 ID（内部引用）' }, { k: 'teacher_code', l: '新增讲师业务编码' }, { k: 'before', l: '保存前' }, { k: 'after', l: '确认后' }], value.binding_selections.map(row => ({ ...row, before: '尚未绑定', after: '新增目录关联；认证保持不变' })), [], ''); return true;
    } catch (error) { if (permitted() && id === epoch && error?.name !== 'AbortError') { const status = Number(error?.status || error?.code); if ([401, 403, 409].includes(status)) clear(); if ([401, 403].includes(status)) context.onAccessLost?.(); notice.textContent = errorText(error); } return false; }
    finally { if (active() && id === epoch) { busy = false; controls(); } }
  }
  async function confirm() {
    if (!permitted() || busy || !preview?.ready || !current) return false;
    const pending = preview, version = current.version, id = ++epoch; controller?.abort(); controller = new AbortController(); busy = true; controls(); notice.textContent = '正在确认保存教师绑定…';
    try {
      context.onConfirming?.();
      const value = await context.api(`${url}/confirm`, { method: 'POST', quiet: true, signal: controller.signal, body: { batch_id: pending.batch_id, expected_version: version, confirm: true } });
      if (!permitted() || id !== epoch) return false;
      if (!value || !['CONFIRMED', 'ALREADY_CONFIRMED'].includes(value.status) || value.scope_id !== scope.scope_id || value.batch_id !== pending.batch_id || value.version !== version + 1 || !integer(value.current_version) || value.current_version < value.version || value.status === 'CONFIRMED' && (value.current_version !== value.version || !onlyAdditions(value.changes, bindings.map(row => row.teacher_code)))) throw new Error('保存回执未能核实。');
      preview = null; unknown = false; current = null; bindings = []; result.replaceChildren(); controls();
      let hostFailed = false; try { await context.onSaved?.({ scopeId: scope.scope_id, version: value.version }); } catch { hostFailed = true; }
      if (!permitted() || id !== epoch) return false;
      const refreshed = await refresh(true); if (active() && hostFailed && refreshed) notice.textContent = '已保存，教师绑定已刷新；只读目录刷新失败，请重新读取。'; return true;
    } catch (error) {
      if (permitted() && id === epoch && error?.name !== 'AbortError') {
        const status = Number(error?.status || error?.code);
        if ([400, 401, 403, 404, 409].includes(status)) { clear(); notice.textContent = errorText(error); if ([401, 403].includes(status)) context.onAccessLost?.(); }
        else { unknown = true; notice.textContent = '尚未确认本次绑定结果。请重试确认同一批次，或稍后刷新核对；不会另建预览。'; }
      } return false;
    } finally { if (active() && id === epoch) { busy = false; controls(); } }
  }
  function requestLeave(action) {
    if (!permitted()) return true;
    if (!dirty() && !busy) return true;
    leaveAction = action; guard.replaceChildren(node('p', unknown ? '本次绑定确认结果尚未核实。离开后请刷新核对是否已保存。' : '当前有未确认的教师绑定，是否放弃并继续？'), button('继续编辑', 'stay'), button('放弃并继续', 'discard')); guard.scrollIntoView?.({ block: 'nearest' }); return false;
  }
  function edit(event) {
    if (!permitted() || busy || unknown || !current) return;
    if (event.target.hasAttribute?.('data-binding-search')) { search = event.target.value; candidatePage = 0; renderCandidates(); return; }
    const field = event.target.dataset?.bindingField;
    if (field === 'catalog_version') catalogVersion = event.target.value;
    else if (field === 'change_comment') comment = event.target.value;
    else if (field === 'teacher_code' && bindings[Number(event.target.dataset.row)]) bindings[Number(event.target.dataset.row)].teacher_code = event.target.value;
    else return;
    invalidate(); notice.textContent = '内容已更改，请重新保存绑定预览后确认。';
  }
  function click(event) {
    if (!permitted()) return;
    let target = event.target; while (target && target !== wrap && !target.dataset?.bindingAction && target.dataset?.bindingChoice === undefined && target.dataset?.bindingRemove === undefined) target = target.parentNode;
    if (!target || target.disabled) return;
    const action = target.dataset?.bindingAction, choice = target.dataset?.bindingChoice, removal = target.dataset?.bindingRemove; if (!action && choice === undefined && removal === undefined) return; event.preventDefault();
    if (action === 'stay') { guard.replaceChildren(); leaveAction = null; return; }
    if (action === 'discard') { const next = leaveAction; clear(); busy = false; ++epoch; controller?.abort(); next?.(); return; }
    if (action === 'refresh') { if (requestLeave(() => refresh())) return refresh(); return; }
    if (action === 'preview') return savePreview(); if (action === 'confirm') return confirm();
    if (busy || unknown || !current) return;
    if (['candidate-previous', 'candidate-next'].includes(action)) { candidatePage += action.endsWith('next') ? 1 : -1; renderCandidates(); return; }
    if (['draft-previous', 'draft-next'].includes(action)) { draftPage += action.endsWith('next') ? 1 : -1; renderDraft(); return; }
    if (choice !== undefined) { const candidate = current.candidates[Number(choice)]; if (!candidate || !available(candidate) || current.bindings.length + bindings.length >= 5000) return; bindings.push({ teacher_code: '', teacher_id: candidate.teacher_id }); draftPage = Math.floor((bindings.length - 1) / SIZE); invalidate(); renderCandidates(); renderDraft(); notice.textContent = '已选择档案；请填写已有或已核定的讲师业务编码。不会自动用姓名、行号或档案 ID 生成编码。'; return; }
    if (removal !== undefined && integer(Number(removal)) && bindings[Number(removal)]) { bindings.splice(Number(removal), 1); invalidate(); renderCandidates(); renderDraft(); }
  }
  wrap.addEventListener('click', click); wrap.addEventListener('input', edit); wrap.addEventListener('change', edit);
  function destroy() { if (!alive) return; alive = false; epoch++; controller?.abort(); context.signal?.removeEventListener('abort', destroy); wrap.removeEventListener('click', click); wrap.removeEventListener('input', edit); wrap.removeEventListener('change', edit); clear(); wrap.remove(); }
  context.signal?.addEventListener('abort', destroy, { once: true });
  return { ready: refresh(), refresh, requestLeave, destroy };
}
