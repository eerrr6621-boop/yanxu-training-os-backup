'use strict';
const { projectEditFields } = require('./project-edit-payload.cjs');
const assert = require('node:assert/strict');

/** Synthetic HTTP fixtures only. Caller already requires a fresh loopback instance. */
async function createIntegrationHttpFixtures(call, check = () => {}, refreshSession) {
  assert.equal(typeof refreshSession, 'function', 'Caller must explicitly refresh its synthetic session after identity publication');
  async function data(path, body, label = path) {
    const result = await call(path, body);
    assert.equal(result.code, 0, `${label}: ${result.msg || result.code}`);
    return result.data;
  }
  const existing = await data('/organization/config');
  assert.equal(existing.configuration, null, 'HTTP fixtures require a fresh database without organization configuration');
  const users = await data('/users');
  const required = ['admin', 'manager', 'viewer'].map(name => {
    const user = users.find(row => row.username === name);
    assert.ok(user && Number(user.status) === 1, `Missing synthetic bootstrap user: ${name}`);
    return user;
  });
  const organizationCode = 'SYNTHETIC-HTTP';
  const workerRole = 'HTTP-WORKER', readerRole = 'HTTP-READER';
  const optionalRelation = () => ({ required: false, allowedTargetRoles: [workerRole, readerRole], targetMustCoverOrganization: false, allowSelf: false });
  const configuration = {
    version: 'SYNTHETIC-HTTP-v1',
    codeRules: { organizationPattern: '[A-Z0-9-]+', personPattern: '[A-Z0-9-]+', rolePattern: '[A-Z0-9-]+' },
    roleCodes: [workerRole, readerRole],
    organizations: [{ organizationCode, parentOrganizationCode: null, enabled: true }],
    people: required.map(user => ({ personCode: 'HTTP-' + user.username.toUpperCase(), organizationCode,
      responsibleOrganizationCodes: [], leaderPersonCode: null, bpPersonCode: null,
      roleCodes: [user.username === 'viewer' ? readerRole : workerRole], enabled: true })),
    relations: [workerRole, readerRole].map(roleCode => ({ roleCode, leader: optionalRelation(), bp: optionalRelation() })),
    accountBindings: required.map(user => ({ accountId: user.id, personCode: 'HTTP-' + user.username.toUpperCase(), enabled: true })),
    grants: [['demand.read', 'VIEW'], ['demand.write', 'HANDLE'], ['demand.accept', 'HANDLE'], ['bid.result', 'HANDLE'], ['delivery.read', 'VIEW'], ['delivery.write', 'HANDLE'], ['delivery.verify', 'HANDLE']].map(([resource, action], index) => ({
      ruleId: `HTTP-WORKER-${index}`, roleCode: workerRole, resource, action, effect: 'ALLOW', scope: 'OWN_ORG', organizationCodes: [],
    })).concat([{ ruleId: 'HTTP-READER-READ', roleCode: readerRole, resource: 'demand.read', action: 'VIEW', effect: 'ALLOW', scope: 'OWN_ORG', organizationCodes: [] }]),
  };
  const published = await data('/organization/config', { expectedVersion: null, configuration });
  check('隔离HTTP夹具显式发布组织账号及业务权限', published.version === configuration.version);
  const revoked = await call('/organization/me');
  check('配置发布撤销旧会话，夹具必须重新登录', revoked.code === 401);
  await refreshSession();
  const me = await data('/organization/me');
  check('隔离HTTP夹具当前管理员显式绑定为合成人员', me.status === 'BOUND' && me.person.personCode === 'HTTP-ADMIN');
  let sequence = 0;
  const requestId = operation => `http-${operation}-${++sequence}`;
  const checkedWorkflow = async (path, body) => {
    const result = await data(path, body);
    assert.ok(result.workflow && Number.isInteger(result.workflow.id), `${path}: workflow missing`);
    return result.workflow;
  };
  async function draft(fields = {}) {
    const body = {
      title: fields.title || '隔离HTTP合成需求', business_path: 'bid', organization_code: organizationCode,
      internal_contact_code: 'HTTP-ADMIN', category_text: '合成回归',
      delivery_mode_text: fields.delivery_mode || fields.training_mode || '线下', period_text: fields.training_period || '待协调',
      hours: String(fields.hours ?? 4), participant_count: String(fields.participant_count ?? 10),
      budget_amount: String(fields.amount ?? 0), objectives: fields.content || '核验隔离HTTP业务流程',
      expected_start_date: fields.start_date || fields.expect_date || '2026-08-01',
      expected_end_date: fields.end_date || fields.start_date || fields.expect_date || '2026-08-01',
      external_approval_ref: `SYNTHETIC-OFFICE-${sequence + 1}`, unit: fields.unit || '合成测试单位',
      request_id: requestId('draft'),
    };
    for (const key of ['contact', 'phone', 'content', 'teacher_req', 'remark', 'training_province', 'training_city'])
      if (Object.hasOwn(fields, key)) body[key] = fields[key];
    return checkedWorkflow('/demands/draft', body);
  }
  async function submit(workflow) {
    return checkedWorkflow('/demands/submit', { id: workflow.id, expected_version: workflow.version, request_id: requestId('submit') });
  }
  async function won(workflow) {
    return checkedWorkflow('/demands/bid-result', { id: workflow.id, expected_version: workflow.version,
      request_id: requestId('won'), result: 'won', external_approval_ref: workflow.form.external_approval_ref,
      result_date: '2026-08-01', result_note: '隔离合成签报结果' });
  }
  async function accept(workflow) {
    return checkedWorkflow('/demands/accept', { id: workflow.id, expected_version: workflow.version,
      request_id: requestId('accept'), team_code: organizationCode });
  }
  async function configureProject(projectId, fields = {}) {
    const row = (await data('/projects')).find(item => Number(item.id) === Number(projectId));
    assert.ok(row, 'Accepted project must be visible');
    const update = { ...projectEditFields(row), owner: fields.owner || '合成测试员', venue: fields.venue || '合成测试教室',
      contract_no: fields.contract_no || 'SYNTHETIC-CONTRACT-' + projectId, amount: fields.amount ?? 0 };
    for (const key of ['title', 'unit', 'hours', 'start_date', 'end_date', 'participant_count', 'delivery_mode', 'remark'])
      if (Object.hasOwn(fields, key)) update[key] = fields[key];
    await data('/projects', update);
    return projectId;
  }
  async function project(fields = {}) {
    let workflow = await draft(fields);
    workflow = await submit(workflow); workflow = await won(workflow); workflow = await accept(workflow);
    assert.ok(workflow.project_id > 0, 'Team acceptance must return a persisted project');
    await configureProject(workflow.project_id, fields);
    return { projectId: workflow.project_id, demandId: workflow.id, workflow };
  }
  async function saveDraft(workflow, fields = {}) {
    return checkedWorkflow('/demands/draft', { ...fields, id: workflow.id,
      expected_version: workflow.version, request_id: requestId('edit') });
  }
  async function verifyDispatch(dispatchId, actualMinutes) {
    const before = await data('/delivery-settlement?dispatch_id=' + dispatchId);
    const saved = await data('/delivery-settlement/save', { dispatch_id: dispatchId,
      expected_version: before.version, request_id: requestId('delivery-save'), actual_minutes: String(actualMinutes) });
    const verified = await data('/delivery-settlement/verify', { dispatch_id: dispatchId,
      expected_version: saved.version, request_id: requestId('delivery-verify'), evidence_code: 'SYNTHETIC-TEACHING-EVIDENCE' });
    check('受控授课由真实合成人员核对后才能完成', verified.capabilities.can_complete === true);
    return verified;
  }
  async function confirmDispatch(id) {
    const preview = await data('/dispatches/confirmation-preview?id=' + id);
    assert.equal(preview.can_confirm, true, 'Explicit current confirmation preview must permit this synthetic dispatch');
    return call('/dispatches/confirm', { id, accept: 1, expected_version: preview.expected_version, request_id: requestId('confirm') });
  }
  // Completed legacy delivery is seeded before Main by IntegrationLegacyHttpFixture.
  // It remains an unmanaged historical fact; new confirmation coverage uses accepted projects above.
  async function completeLegacyQuestionnaireProject() {
    const projectId = 2;
    const row = (await data('/projects')).find(item => item.id === projectId);
    assert.ok(row && row.status === '进行中' && Number(row.hours) === 16, 'Expected untouched synthetic seed project 2');
    const legacy = (await data('/dispatches?project_id=' + projectId)).filter(item => item.subject === '历史问卷归档合成授课');
    assert.equal(legacy.length, 1, 'Run the explicit synthetic legacy seed wrapper; do not fabricate a current organization for historical project 2');
    assert.equal(legacy[0].status, '已完成');
    assert.equal(Number(legacy[0].teacher_id), 4);
    assert.equal(Number(legacy[0].hours), 16);
    const invalid = await call('/fees', { project_id: projectId, teacher_id: 4, hours: 16, rate: 2200, amount: 1,
      status: '已发放', pay_date: '2026-08-04', remark: '历史兼容伪造少付' });
    check('历史项目课酬金额仍须与课时及标准一致', invalid.code === 400);
    const fees = await data('/fees/calc', { project_id: projectId });
    check('历史项目仍按原规则计算一次课酬', fees.length === 1 && Number(fees[0].hours) === 16 && Number(fees[0].amount) === 16 * 2200);
    for (const fee of fees) await data('/fees/pay', { id: fee.id });
    check('历史项目重新计算不重复生成已发课酬', (await data('/fees/calc', { project_id: projectId })).length === 0);
    const paid = await data('/fees?project_id=' + projectId);
    check('历史项目保留唯一已发课酬', paid.length === 1 && paid[0].status === '已发放');
    await data('/projects/complete', { id: projectId });
    return projectId;
  }
  return { draft, submit, won, accept, project, configureProject, saveDraft, verifyDispatch, confirmDispatch, completeLegacyQuestionnaireProject };
}
module.exports = { createIntegrationHttpFixtures };
