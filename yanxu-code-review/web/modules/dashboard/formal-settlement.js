// Only summarize amounts already authorized and returned with the original project cards.
// This is not an organization-wide reports.read query and never requests hidden amounts.
const escape = value => String(value).replace(/[&<>"']/g, ch => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[ch]));
const positive = value => Number.isSafeInteger(value) && value > 0;
const count = value => Number.isSafeInteger(value) && value >= 0;
function minor(value) {
  if (typeof value !== 'string' || !/^-?\d{1,72}(?:\.\d{1,2})?$/.test(value)) throw Error('Invalid authorized amount');
  const negative = value.startsWith('-'), [whole, fraction = ''] = (negative ? value.slice(1) : value).split('.');
  return (negative ? -1n : 1n) * (BigInt(whole) * 100n + BigInt(fraction.padEnd(2, '0')));
}
function amount(value) {
  const sign = value < 0n ? '-' : '', raw = (value < 0n ? -value : value).toString().padStart(3, '0');
  return sign + raw.slice(0, -2) + '.' + raw.slice(-2);
}
function checked(project) {
  if (project.read_access != null && (typeof project.read_access !== 'object' || Array.isArray(project.read_access) || project.read_access.limited !== false)) throw Error('Restricted or invalid project card');
  const v = project.delivery_settlement;
  if (!v || v.project_id !== project.id || typeof v.organization_code !== 'string' || !v.organization_code
      || v.payment_integration !== 'CONFIGURED' || !['AVAILABLE', 'INCOMPLETE'].includes(v.status)
      || !count(v.missing_claim_count) || typeof v.pending_cases !== 'boolean') throw Error('Unavailable settlement');
  const due = minor(v.known_confirmed_amount), paid = minor(v.known_paid_amount), balance = minor(v.known_balance);
  if (due < 0n || paid < 0n || due - paid !== balance) throw Error('Inconsistent settlement');
  if (v.status === 'AVAILABLE') {
    if (v.missing_claim_count || v.pending_cases || minor(v.confirmed_amount) !== due
        || minor(v.paid_amount) !== paid || minor(v.balance) !== balance) throw Error('Inconsistent complete settlement');
  } else if ([v.confirmed_amount, v.paid_amount, v.balance].some(x => x !== null)) throw Error('Unconfirmed total');
  return { due, paid, balance, incomplete: v.status === 'INCOMPLETE', missing: v.missing_claim_count };
}

export function summarizeProjectSettlements(projects) {
  const out = { status: 'UNAVAILABLE', controlled: 0, readable: 0, unavailable: 0, incomplete: 0, legacy: 0, missing: 0, due: null, paid: null, balance: null };
  if (!Array.isArray(projects)) return out;
  const seen = new Set();
  let due = 0n, paid = 0n, balance = 0n;
  for (const project of projects) {
    if (!project || !positive(project.id) || seen.has(project.id) || typeof project.delivery_controlled !== 'boolean') return { ...out, status: 'UNAVAILABLE', readable: 0, due: null, paid: null, balance: null };
    seen.add(project.id);
    if (!project.delivery_controlled) { out.legacy++; continue; }
    out.controlled++;
    try {
      const v = checked(project);
      due += v.due; paid += v.paid; balance += v.balance;
      out.readable++; out.missing += v.missing;
      if (v.incomplete) out.incomplete++;
    } catch { out.unavailable++; }
  }
  if (!out.controlled) { out.status = 'EMPTY'; return out; }
  if (!out.readable) return out;
  out.status = out.unavailable || out.incomplete ? 'PARTIAL' : 'AVAILABLE';
  return { ...out, due: amount(due), paid: amount(paid), balance: amount(balance) };
}

export function renderFormalSettlement(projects) {
  const v = summarizeProjectSettlements(projects), partial = v.status === 'PARTIAL';
  const label = partial ? '已核对部分' : '';
  const cell = (key, title, value, note) => `<div class="v9-snap-cell" data-dashboard-financial="${key}"><small>${escape(title)}</small><b style="overflow-wrap:anywhere">${value === null ? '—' : '¥ ' + escape(value)}</b><span class="v9-snap-note">${escape(note)}</span></div>`;
  let coverage;
  if (v.status === 'EMPTY') coverage = '当前可见项目中暂无接入正式课酬的项目。';
  else if (v.status === 'UNAVAILABLE') coverage = '正式课酬摘要暂不可用，请核对权限或重新读取；不能视为零。';
  else coverage = `${v.controlled} 个已接入项目中，${v.readable} 个可读取课酬摘要` + (partial ? `；${v.unavailable} 个不可读取，${v.incomplete} 个尚未完整核对。以上仅为已核对部分。` : '。') + (v.missing ? ` ${v.missing} 条授课尚无确认课酬。` : '');
  const legacy = v.legacy ? ` ${v.legacy} 个原台账项目不计入正式课酬。` : '';
  return `<section class="v9-snap" aria-label="当前可见项目的正式课酬" data-dashboard-formal-settlement data-status="${v.status}">
    ${cell('due', label + '正式应付', v.due, '已核准的当前应付课酬')}
    ${cell('paid', label + '登记净实付', v.paid, '已登记付款减退款，不代表银行转账执行')}
    ${cell('balance', label + '课酬净余额', v.balance, '正数待付、负数待退；净额为零不代表逐笔结清')}
    <div class="v9-snap-cell"><small>当前可见项目范围</small><span class="v9-snap-note" role="status">${escape(coverage + legacy)}</span></div>
    <div class="v9-snap-cell"><small>核对口径</small><span class="v9-snap-note">与项目详情使用同一授权摘要；汇总当前已核准应付及历次登记收付，不限定本月。旧课酬与其他成本另列，不据此估算利润。</span></div>
  </section>`;
}
