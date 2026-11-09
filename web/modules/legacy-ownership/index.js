/** Explicit administrator registration; no ownership is inferred from names. */
export function mount(container, { api, getUser, isCurrent = () => true }) {
  const identity = getUser();
  let destroyed = false, pending = false, snapshot = null, request = null, generation = 0;
  const alive = () => !destroyed && isCurrent() && getUser() === identity && identity?.role === 'admin';
  const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const el = key => container.querySelector(`[data-legacy-${key}]`);
  if (!alive()) { container.replaceChildren(); return { ready: Promise.resolve(), destroy() {} }; }
  container.innerHTML = `<section class="card data-card"><h2>历史归属登记</h2><p>仅把核实后的历史需求和项目登记到现有机构。相关排课、课酬、收费、成本及内部统计将按该机构的现有权限开放；同名单位不会自动合并。</p><p>未登记记录仅全局系统管理员可访问，不能确认排课。登记不会恢复历史问卷外部作答链接，也不会把历史项目接入新审批、课酬或总结流程。</p><p data-legacy-status role="status">正在读取可登记记录…</p><form data-legacy-form><div class="form-grid"><label>历史记录<select data-legacy-record><option value="">请选择</option></select></label><label>核实的机构<select data-legacy-org required><option value="">请选择</option></select></label></div><div class="form-grid"><label>列表中没有所需记录时，按类型定位<select data-legacy-direct-type><option value="PROJECT">历史项目</option><option value="DEMAND">历史需求</option></select></label><label>明确的历史记录编号（可选）<input data-legacy-direct-id inputmode="numeric" pattern="[1-9][0-9]*" placeholder="填写后优先按此编号预览"></label></div><label>登记依据与原因<textarea data-legacy-reason maxlength="1000" rows="3" placeholder="填写核对过的来源及归属依据" required></textarea></label><div class="toolbar"><button class="btn gray" type="button" data-legacy-refresh>刷新记录</button><button class="btn" type="submit" data-legacy-preview>预览关联记录</button></div></form><div data-legacy-result></div></section>`;
  const notice = message => { if (alive()) el('status').textContent = message; };
  const busy = value => { pending = value; if (alive()) container.querySelectorAll('button,select,textarea,input').forEach(node => { node.disabled = value; }); };
  const invalidate = () => { snapshot = null; request = null; generation++; el('result').replaceChildren(); };
  async function load() {
    if (!alive() || pending) return;
    invalidate(); busy(true); notice('正在读取历史记录与机构…');
    try {
      const data = await api('/legacy-ownership/context');
      if (!alive()) return;
      if (!Array.isArray(data.records) || !Array.isArray(data.organizations)) throw new Error('登记上下文响应不完整');
      el('record').innerHTML = '<option value="">请选择历史记录</option>' + data.records.map(row => `<option value="${esc(row.type)}:${esc(row.id)}">${esc(row.type === 'DEMAND' ? '需求' : '项目')} #${esc(row.id)} · ${esc(row.title)}${row.unit ? ' · ' + esc(row.unit) : ''}${row.registered ? '（已登记 ' + esc(row.organization_code) + '）' : ''}</option>`).join('');
      el('org').innerHTML = '<option value="">请选择核实后的机构</option>' + data.organizations.map(org => `<option value="${esc(org.code)}">${esc(org.name)} · ${esc(org.code)}</option>`).join('');
      notice(data.organizations.length ? `已载入 ${data.records.length} 条历史记录（每类最近 500 条）。更早记录可按明确编号定位；名称不作为归属依据。` : '尚无可登记的启用机构，请先由有权管理员维护机构配置。');
    } catch (error) { notice(error.message || '读取失败，请刷新后重试'); }
    finally { if (alive()) busy(false); }
  }
  el('direct-id').addEventListener('input', invalidate); el('direct-type').addEventListener('change', invalidate); el('record').addEventListener('change', invalidate); el('org').addEventListener('change', invalidate); el('reason').addEventListener('input', () => { request = null; });
  el('refresh').onclick = load;
  el('form').onsubmit = async event => {
    event.preventDefault(); if (!alive() || pending) return;
    const direct = el('direct-id').value.trim(); const [type, raw] = direct ? [el('direct-type').value, direct] : el('record').value.split(':'); const id = Number(raw), organization_code = el('org').value;
    if (!['DEMAND', 'PROJECT'].includes(type) || !Number.isSafeInteger(id) || id < 1 || !organization_code) { notice('请选择明确的历史记录与机构。'); return; }
    invalidate(); const current = generation; busy(true); notice('正在核对实际来源关联…');
    try {
      const data = await api('/legacy-ownership/preview', { body: { type, id, organization_code } });
      if (!alive() || current !== generation) return;
      if (!Array.isArray(data.records) || !/^[a-f0-9]{64}$/.test(data.source_version) || data.type !== type || data.id !== id || data.organization_code !== organization_code) throw new Error('预览响应不完整，请重新读取');
      snapshot = data;
      el('result').innerHTML = `<h3>核对本次登记范围</h3><p>目标机构：<strong>${esc(organization_code)}</strong>。以下记录通过系统保存的来源编号关联，登记会同时覆盖它们的从属业务数据。</p><table class="data-table"><thead><tr><th>类型与编号</th><th>名称</th><th>原需求单位</th><th>登记状态</th></tr></thead><tbody>${data.records.map(row => `<tr><td>${esc(row.type === 'DEMAND' ? '需求' : '项目')} #${esc(row.id)}</td><td>${esc(row.title)}</td><td>${esc(row.unit)}</td><td>${row.registered ? '已登记同机构' : '待登记'}</td></tr>`).join('')}</tbody></table><p>${esc(data.notice)}</p>${data.can_register ? '<label><input type="checkbox" data-legacy-ack> 我已逐项核对以上记录，确认均归属于所选机构。</label><button class="btn" type="button" data-legacy-confirm>确认登记以上记录</button>' : '<p>该记录已登记，不能通过普通编辑重新分配机构。</p><button class="btn gray" type="button" data-legacy-audit>查看登记审计</button>'}`;
      if (data.can_register) el('confirm').onclick = commit; else el('audit').onclick = showAudit;
      notice(data.can_register ? '预览已就绪；请核对完整范围、填写依据并明确确认。' : '登记已存在，可以查看原审计。');
    } catch (error) { snapshot = null; notice(error.message || '预览失败'); }
    finally { if (alive()) busy(false); }
  };
  async function commit() {
    if (!alive() || pending || !snapshot) return;
    const reason = el('reason').value.trim();
    if (!el('ack').checked || !reason) { notice('请填写登记依据，并勾选已核对全部关联记录。'); return; }
    request ||= { type: snapshot.type, id: snapshot.id, organization_code: snapshot.organization_code, expected_version: snapshot.expected_version, source_version: snapshot.source_version, reason, request_id: 'legacy-' + crypto.randomUUID() };
    if (request.reason !== reason) request = { ...request, reason, request_id: 'legacy-' + crypto.randomUUID() };
    const sent = request; busy(true); notice('正在保存明确登记与审计…');
    try {
      const result = await api('/legacy-ownership/register', { body: sent });
      if (!alive()) return;
      if (result.registered !== true || result.type !== sent.type || result.id !== sent.id || result.organization_code !== sent.organization_code) throw new Error('登记结果无法核对，请刷新后查看审计');
      snapshot.can_register = false; request = null;
      el('result').innerHTML = `<p>登记成功：${esc(result.organization_code)}。请刷新业务页面后继续核对或确认排课。</p><button class="btn gray" type="button" data-legacy-audit>查看登记审计</button>`;
      el('audit').onclick = showAudit; notice('历史归属和原因已留存。已有机构权限仍决定各账号可办理的操作。');
    } catch (error) { notice(error.message || '登记结果暂不确定；再次点击会使用同一请求编号核对，或刷新后查看审计。'); }
    finally { if (alive()) busy(false); }
  }
  async function showAudit() {
    if (!alive() || pending || !snapshot) return; busy(true);
    try {
      const result = await api('/legacy-ownership/audit?type=' + encodeURIComponent(snapshot.type) + '&id=' + snapshot.id);
      if (!alive()) return;
      const audit = result.audit;
      if (!audit) throw new Error('该记录尚无可核实的登记审计');
      const detail = document.createElement('div'); detail.innerHTML = `<h3>登记审计</h3><p>机构：${esc(result.organization_code)} · 操作账号 #${esc(audit.actor_account)} · ${esc(audit.created_at)}</p><p>依据：${esc(audit.reason)}</p><p>操作：${esc(audit.operation === 'DERIVE_PROJECT' ? '从已登记历史需求中标立项' : '管理员显式登记')}</p>`;el('result').append(detail);notice('审计已读取。');
    } catch (error) { notice(error.message || '审计读取失败'); }
    finally { if (alive()) busy(false); }
  }
  const ready = load();
  return { ready, destroy() { destroyed = true; snapshot = null; request = null; generation++; container.replaceChildren(); } };
}
