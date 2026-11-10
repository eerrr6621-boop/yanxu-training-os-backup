/* 当前师资档案/地区/权限回归。旧推荐语义逐段移至 scripts/legacy/test_faculty.js.original，断言未删除。 */
'use strict';

const assert = require('node:assert/strict');
const target = new URL(String(process.env.TRAINING_API_BASE || 'invalid:'));
if (!['http:', 'https:'].includes(target.protocol) ||
    !['127.0.0.1', 'localhost', '[::1]'].includes(target.hostname) ||
    target.username || target.password || target.search || target.hash ||
    target.pathname.replace(/\/+$/, '') !== '/api' || !target.port || target.port === '8081') {
  throw new Error('必须设置 TRAINING_API_BASE 为非生产端口的 loopback /api 隔离实例');
}
const BASE = target.origin + '/api';
let token = '';
let passed = 0;
let failed = 0;
const check = (name, condition) => {
  if (condition) { passed++; console.log('PASS', name); }
  else { failed++; console.error('FAIL', name); }
};
async function request(path, body) {
  const res = await fetch(BASE + path, {
    method: body === undefined ? 'GET' : 'POST',
    headers: { 'Content-Type': 'application/json', ...(token ? { 'X-Token': token } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  return { status: res.status, ...(await res.json()) };
}
async function api(path, body) {
  const result = await request(path, body);
  assert.equal(result.code, 0, `${path}: ${result.msg || result.code}`);
  return result.data;
}
async function recommend(requirement, options = {}) {
  return api('/teacher-recommendations', { requirement, max_results: 20, ...options });
}
const found = (result, id) => result.recommendations.find((item) => Number(item.teacher_id) === Number(id));
const pendingTravel = (result, id) => (result.analysis.dispatch_preferences.selection?.travel_pending || []).find((item) => Number(item.teacher_id) === Number(id));

function crc32(bytes) {
  let crc = 0xffffffff;
  for (const byte of bytes) {
    crc ^= byte;
    for (let i = 0; i < 8; i++) crc = (crc >>> 1) ^ ((crc & 1) ? 0xedb88320 : 0);
  }
  return (crc ^ 0xffffffff) >>> 0;
}
function zip(entries) {
  const localParts = [], centralParts = [];
  let offset = 0;
  for (const [name, text] of entries) {
    const filename = Buffer.from(name), data = Buffer.from(text);
    const crc = crc32(data), local = Buffer.alloc(30), central = Buffer.alloc(46);
    local.writeUInt32LE(0x04034b50); local.writeUInt16LE(20, 4); local.writeUInt16LE(0x800, 6);
    local.writeUInt32LE(crc, 14); local.writeUInt32LE(data.length, 18); local.writeUInt32LE(data.length, 22); local.writeUInt16LE(filename.length, 26);
    central.writeUInt32LE(0x02014b50); central.writeUInt16LE(20, 4); central.writeUInt16LE(20, 6); central.writeUInt16LE(0x800, 8);
    central.writeUInt32LE(crc, 16); central.writeUInt32LE(data.length, 20); central.writeUInt32LE(data.length, 24); central.writeUInt16LE(filename.length, 28); central.writeUInt32LE(offset, 42);
    localParts.push(local, filename, data); centralParts.push(central, filename);
    offset += local.length + filename.length + data.length;
  }
  const directory = Buffer.concat(centralParts), end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50); end.writeUInt16LE(entries.length, 8); end.writeUInt16LE(entries.length, 10); end.writeUInt32LE(directory.length, 12); end.writeUInt32LE(offset, 16);
  return Buffer.concat([...localParts, directory, end]);
}
const xmlEscape = (value) => String(value).replace(/[<>&]/g, (x) => ({ '<': '&lt;', '>': '&gt;', '&': '&amp;' }[x]));
function pptx(paragraphs) {
  // Each paragraph can contain several formatting runs: plain text must survive that split.
  const body = paragraphs.map((paragraph) => `<a:p>${(Array.isArray(paragraph) ? paragraph : [paragraph]).map((run) => `<a:r><a:t>${xmlEscape(run)}</a:t></a:r>`).join('')}</a:p>`).join('');
  return zip([
    ['[Content_Types].xml', '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="xml" ContentType="application/xml"/></Types>'],
    ['ppt/presentation.xml', '<p:presentation xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"/>'],
    ['ppt/slides/slide1.xml', `<p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><p:cSld><p:spTree><p:sp><p:txBody>${body}</p:txBody></p:sp></p:spTree></p:cSld></p:sld>`],
  ]);
}
function pdf(text) {
  const escaped = text.replace(/\\/g, '\\\\').replace(/\(/g, '\\(').replace(/\)/g, '\\)');
  const stream = `BT /F1 12 Tf 72 720 Td (${escaped}) Tj ET\n`;
  const objects = [
    '<< /Type /Catalog /Pages 2 0 R >>', '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
    '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>',
    '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',
    `<< /Length ${Buffer.byteLength(stream)} >>\nstream\n${stream}endstream`,
  ];
  let output = '%PDF-1.4\n';
  const offsets = [];
  for (const object of objects) { offsets.push(Buffer.byteLength(output)); output += `${offsets.length} 0 obj\n${object}\nendobj\n`; }
  const xref = Buffer.byteLength(output);
  output += `xref\n0 6\n0000000000 65535 f \n${offsets.map((n) => `${String(n).padStart(10, '0')} 00000 n \n`).join('')}trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`;
  return Buffer.from(output);
}
async function upload(id, name, bytes) {
  const result = await fetch(`${BASE}/teacher-resumes/upload?teacher_id=${id}`, {
    method: 'POST', headers: {
      'X-Token': token, 'X-Resume-Name': Buffer.from(name).toString('base64url'),
      'Content-Type': name.endsWith('.pptx') ? 'application/vnd.openxmlformats-officedocument.presentationml.presentation' : 'application/pdf',
    }, body: bytes,
  }).then((res) => res.json());
  assert.equal(result.code, 0, result.msg);
  return result.data;
}
async function teacher(name, fee = 1800, field = '', title = '') {
  return api('/teachers', { name, org: '隔离测试机构', title, field, fee_rate: fee, intro: '', gender: '', phone: '', email: '', status: '在库', in_date: '2026-09-01', out_date: '', base_province: '浙江', base_city: '杭州' });
}
async function profile(id) { return api(`/teacher-resumes/profile?teacher_id=${id}`); }
async function reparse(id) {
  await api('/teacher-resumes/reparse', { teacher_id: id });
  const deadline = Date.now() + 20000;
  while (Date.now() < deadline) {
    const result = await profile(id);
    if (!['queued', 'processing'].includes(result.parse_status)) return result;
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  throw new Error('隔离解析超时');
}

(async () => {
  token = (await api('/login', { username: 'admin', password: 'admin123' })).token;
  const fixture = await require('./scripts/IntegrationHttpFixtures.cjs').createIntegrationHttpFixtures(request, check, async () => { const login = await request('/login', { username: 'admin', password: 'admin123' }); if (login.code !== 0 || !login.data?.token) throw new Error('Synthetic refresh login failed'); token = login.data.token; });
  assert.equal((await api('/teacher-resumes/manage')).items.length, 0, '本套回归要求全新数据库：已有简历时拒绝运行');
  const bankId = await teacher('回归甲', 1800, '领导力', '副教授');
  const expensiveId = await teacher('回归乙', 4000);
  const unknownFeeId = await teacher('回归丙', 0);
  const pdfId = await teacher('回归丁', 2000);
  const negativeId = await teacher('回归戊', 1800);
  const levelId = await teacher('【灰度测试】讲师等级', 1800, '', '高级讲师');
  let levelRow = (await api('/teachers')).find(row => row.id === levelId);
  check('职称不自动推定讲师等级', levelRow.teacher_level === null);
  for (const level of ['讲师', '高级讲师', '特级讲师', '特聘讲师']) {
    await api('/teachers', { ...levelRow, teacher_level: level });
    levelRow = (await api('/teachers')).find(row => row.id === levelId);
    check('讲师等级保存并读取：' + level, levelRow.teacher_level === level);
  }
  const legacyUpdate = { ...levelRow }; delete legacyUpdate.teacher_level;
  await api('/teachers', legacyUpdate);
  levelRow = (await api('/teachers')).find(row => row.id === levelId);
  check('旧客户端缺省等级不覆盖', levelRow.teacher_level === '特聘讲师');
  for (const invalid of ['金牌讲师', 4, { name: '讲师' }, ['讲师']]) {
    check('非法等级拒绝写入：' + JSON.stringify(invalid), (await request('/teachers', { ...levelRow, teacher_level: invalid })).status === 400);
  }
  await api('/teachers', { ...levelRow, teacher_level: '' });
  check('等级显式清空保存为NULL', (await api('/teachers')).find(row => row.id === levelId).teacher_level === null);
  const railCatalog = await api('/teacher-resumes/dispatch-priorities?province=安徽&city=合肥&date=2099-01-01');
  check('没有高铁参考不伪装已配置', railCatalog.status === 'rail_references_pending' && railCatalog.priorities.every(row => row.tier === 0));
  check('参考目录不返回私有文件路径', !JSON.stringify(railCatalog).includes('/Users/') && !JSON.stringify(railCatalog).includes('source_thread'));
  const original = pptx([
    '个人简介：副教授，持续开展员工职业能力培养与实践教学。',
    '通过案例研讨及小组协作帮助员工改善工作方法并提升服务品质。',
    '拥有丰富课堂组织和教学方案设计经验，可根据需求调整课程案例。',
    ['连续七年服务于星海银', '行大连分行，为柜员提供服务资格认证。'],
    '主讲课程：量子纠错实训、银行客户服务、领导力。',
    '累计授课700课时、12场，满意度95%。',
    '服务资格认证项目联系人：13912345678，faculty@example.test。',
  ]);
  await upload(bankId, '原始简历.pptx', original);
  const initial = await profile(bankId);
  check('PPTX文字格式分段后仍可提取完整服务机构', JSON.stringify(initial.profile.service_cases).includes('星海银行大连分行'));
  check('无书名号的主讲课程也能被提取', initial.profile.courses.includes('量子纠错实训'));
  check('副教授不会自动升级为教授', initial.profile.credentials.includes('副教授或副高') && !initial.profile.credentials.includes('教授'));
  check('原始简历自述独立保留', initial.profile.resume_claims.claimed_training_hours === '700' && initial.profile.resume_claims.claimed_satisfaction_percent === '95');

  await upload(expensiveId, '课程乙.pptx', pptx(['擅长银行客户服务与投诉处理，面向柜员的服务资格认证课程，通过情景演练帮助员工提高沟通能力和服务质量。']));
  await upload(unknownFeeId, '课程丙.pptx', pptx(['擅长银行客户服务与投诉处理，面向柜员的服务资格认证课程，通过情景演练帮助员工提高沟通能力和服务质量。']));
  await upload(pdfId, 'office.pdf', pdf('Professional training and course design. Practical Excel worksheets and pivot tables for office employees. More than ten years of classroom experience.'));
  const pdfProfile = await profile(pdfId);
  check('有效PDF仍可完成解析', pdfProfile.parse_status === 'ready' && Number(pdfProfile.page_count) === 1);
  check('英文training不会误识别为AI', !pdfProfile.profile.tags.includes('数字化与人工智能') && pdfProfile.profile.tags.includes('办公效能与Excel'));
  await upload(negativeId, '否定要求.pptx', pptx(['专业范围说明：不擅长人工智能，不包含领导力，不需要财务管理。不包含课程：量子引力绘图。实际专注职业形象和服务礼仪，采用角色扮演提升现场展示和服务质量。']));
  const negative = (await profile(negativeId)).profile;
  check('明确否定句不会成为正向专业标签', !negative.tags.includes('数字化与人工智能') && !negative.tags.includes('领导力与管理') && !negative.tags.includes('财务与税务'));

  const manual = '副教授。专业领域：银行客户服务、投诉处理。服务案例：星海银行大连分行服务资格认证。主讲课程：量子纠错实训。人工补充自述：累计授课9999课时。';
  await api('/teacher-resumes/profile', { teacher_id: bankId, manual_profile: manual });
  const reviewed = await profile(bankId);
  check('人工校准按NFKC保存完整画像且仍保存原简历自述', reviewed.manual_profile === manual.normalize('NFKC') && reviewed.profile.resume_claims.claimed_training_hours === '700');
  const reparsed = await reparse(bankId);
  check('重新解析不会复活人工删除的专业标签', reparsed.parse_status === 'ready' && reparsed.manual_profile === reviewed.manual_profile && !reparsed.profile.tags.includes('领导力与管理'));
  check('重解析仍使用原文自述而非系统实绩', reparsed.profile.resume_claims.claimed_training_hours === '700');
  const replacement = pptx(['新版简历：擅长领导力和战略管理，累计授课900课时、20场，满意度96%。曾为多家客户开展管理能力培养及案例研讨，帮助员工实现持续改进。']);
  await upload(bankId, '更新简历.pptx', replacement);
  const replaced = await profile(bankId);
  check('替换新版简历保留人工画像与审核时间', replaced.manual_profile === reviewed.manual_profile && replaced.reviewed_at === reviewed.reviewed_at && Boolean(replaced.reviewed_at));
  check('替换后采用人工校准专业并更新原文声明', !replaced.profile.tags.includes('领导力与管理') && replaced.profile.resume_claims.claimed_training_hours === '900');
  const staleEdit = await request('/teacher-resumes/profile', { id: reviewed.id, manual_profile: '旧版本编辑', base_province: '江苏', base_city: '南京' });
  check('保存已替换简历不能返回虚假成功', [404, 409].includes(staleEdit.status));
  check('旧简历校准失败不部分保存常驻地区', (await api('/teachers')).find((row) => Number(row.id) === bankId).base_city === '杭州');


  const { projectId } = await fixture.project({ title: '【灰度测试】隔离师资回归项目', unit: '测试客户', hours: 8, amount: 20000, content: '银行客户服务', expect_date: '2099-01-01' });
  const conflictId = await api('/dispatches', { project_id: projectId, teacher_id: bankId, subject: '银行客户服务', teach_date: ' 2099-1-1 ', start_time: '09:00', end_time: '12:00', hours: 4, status: '待发送' });
  check('保存排课规范日期格式', (await api('/dispatches')).find((row) => Number(row.id) === conflictId).teach_date === '2099-01-01');
  check('非法排课日期在写入时拒绝', (await request('/dispatches', { project_id: projectId, teacher_id: bankId, subject: '银行客户服务', teach_date: '2099-02-30', hours: 4, status: '待发送' })).status === 400);
  const before = await api('/dispatches');
  const retired = await request('/teacher-recommendations', {requirement:'银行客户服务'});
  check('旧推荐明确停用，不冒充旧业务成功', retired.status === 410 && retired.code === 410);
  check('已停用推荐请求不会自动增加排课', (await api('/dispatches')).length === before.length);
  const completedId = await api('/dispatches', { project_id: projectId, teacher_id: bankId, subject: '银行客户服务实践', teach_date: '2026-01-01', start_time: '09:00', end_time: '12:00', hours: 3, status: '待发送' });
  await api('/dispatches/send', { id: completedId });
  const confirmation = await api('/dispatches/confirmation-preview?id='+completedId);
  check('完成授课夹具先通过当前确认预览', confirmation.can_confirm === true);
  await api('/dispatches/confirm', { id: completedId, accept: 1, expected_version:confirmation.expected_version, request_id:'faculty-synthetic-confirm' });
  const verifiedDelivery = await fixture.verifyDispatch(completedId, '135');
  await api('/dispatches/complete', { id: completedId, expected_version: verifiedDelivery.version });
  await api('/teacher_evals', { teacher_id: bankId, project_id: projectId, score: 4.8, comment: '隔离回归实际评价', evaluator: '测试员', eval_date: '2026-01-01' });

  const regularPayload = { name: '区域回归师资', org: '测试机构', fee_rate: 1000, field: '量子传感测绘实操', status: '在库' };
  check('新建师资强制常驻地区', (await request('/teachers', regularPayload)).status === 400);
  check('空白地区不允许保存', (await request('/teachers', { ...regularPayload, base_province: '浙江', base_city: '　' })).status === 400);
  const nearIds = [];
  for (const [province, city] of [['江苏', '南京'], ['浙江', '宁波'], ['浙江省', '杭州市']]) {
    nearIds.push(await api('/teachers', { ...regularPayload, name: `区域测试${city}`, base_province: province, base_city: city }));
  }
  const logistics = { training_province: '浙江省', training_city: '杭州市', training_mode: '线下', training_period: '上午', max_results: 3 };
  const arrivalDispatchId = await api('/dispatches', { project_id: projectId, teacher_id: nearIds[0], subject: '量子传感测绘实操', teach_date: '2099-1-1', hours: 3, status: '待发送' });
  const unrelatedLocal = await teacher('本地无关老师', 500, '深海岩芯鉴定');
  await api('/dispatches/delete', { id: arrivalDispatchId }); // Remove only this suite's disposable pending fixture before check-out.
  await api('/teachers/checkout', { id: nearIds[0] });
  const localRow = (await api('/teachers')).find((row) => Number(row.id) === nearIds[2]);
  const legacyPayload = { ...localRow }; delete legacyPayload.base_province; delete legacyPayload.base_city;
  await api('/teachers', legacyPayload);
  check('旧客户端编辑不清空已知地区', (await api('/teachers')).find((row) => Number(row.id) === nearIds[2]).base_city === '杭州');
  check('显式清空常驻地区被拒绝', (await request('/teachers', { ...localRow, base_city: '' })).status === 400);
  await api('/teachers/residence', { id: nearIds[2], base_province: '江苏', base_city: '南京', name: '不能改名', status: '出库' });
  const updatedRow = (await api('/teachers')).find((row) => Number(row.id) === nearIds[2]);
  check('地区专用动作不改变姓名状态等其他资料', updatedRow.name === localRow.name && updatedRow.status === localRow.status && updatedRow.base_city === '南京');
  check('超长地区返回400而不是数据库错误', (await request('/teachers/residence', { id: nearIds[2], base_province: '江苏', base_city: '城'.repeat(65) })).status === 400);
  check('无效地区不修改旧值', (await api('/teachers')).find((row) => Number(row.id) === nearIds[2]).base_city === '南京');
  let prebidWorkflow = await fixture.draft({ title: '投标前区域回归', unit: '区域测试客户', hours: 4, content: '量子传感测绘实操', ...logistics });
  const prebid = prebidWorkflow.id;
  const businessBefore = await Promise.all(['/demands', '/bids', '/projects', '/dispatches', '/fees'].map((path) => api(path)));
  check('关联需求的旧推荐入口同样停用', (await request('/teacher-recommendations', {demand_id:prebid,max_results:3})).status === 410);
  const businessAfter = await Promise.all(['/demands', '/bids', '/projects', '/dispatches', '/fees'].map((path) => api(path)));
  check('已停用投标前推荐不改变任何需求投标项目排课课酬', JSON.stringify(businessBefore) === JSON.stringify(businessAfter));
  const prebidRow = (await api('/demands')).find((row) => Number(row.id) === prebid);
  const oldDemandPayload = { ...prebidRow }; for (const key of ['training_province', 'training_city', 'training_mode', 'training_period']) delete oldDemandPayload[key];
  check('旧通用入口不能修改受管需求', (await request('/demands', oldDemandPayload)).status === 409);
  prebidWorkflow = await fixture.saveDraft(prebidWorkflow, { title: oldDemandPayload.title, unit: oldDemandPayload.unit, content: oldDemandPayload.content });
  check('省略新增字段的需求草稿编辑保留调度字段', (await api('/demands')).find((row) => Number(row.id) === prebid).training_city === '杭州');
  check('非法新需求不能绕过草稿入口', (await request('/demands', { title: '非法日期兼容测试', unit: '测试', content: '客户服务', hours: 3, expect_date: '2099-02-30' })).status === 409);
  check('草稿接口拒绝非法需求日期', (await request('/demands/draft', { title: '非法日期兼容测试', organization_code: 'SYNTHETIC-HTTP', hours: '3', expect_date: '2099-02-30', request_id: 'faculty-invalid-date' })).status === 422);
  const blankRegionDemand = (await fixture.draft({ title: '未知地区测试', unit: '测试', hours: 3, training_province: '　', training_city: ' ' })).id;
  check('未知授课地区保存为空而不是空格', (await api('/demands')).find((row) => Number(row.id) === blankRegionDemand).training_city === '');
  const adminToken = token;
  token = (await api('/login', { username: 'viewer', password: 'viewer123' })).token;
  check('只读用户不能维护常驻地区', (await request('/teachers/residence', { id: nearIds[2], base_province: '浙江', base_city: '杭州' })).status === 403);
  token = ''; check('未登录不能维护常驻地区', (await request('/teachers/residence', { id: nearIds[2], base_province: '浙江', base_city: '杭州' })).status === 401);
  token = adminToken;
  console.log('Historical recommendation assertions NOT RUN here; exact original and line map: scripts/legacy/');
  console.log(`Faculty current product regression: ${passed} passed, ${failed} failed`);
  process.exitCode = failed ? 1 : 0;
})().catch((error) => { console.error(error); process.exitCode = 1; });
