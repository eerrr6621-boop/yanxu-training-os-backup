'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { ordinaryProjectFields, projectEditFields } = require('./project-edit-payload.cjs');
let checks = 0;
function check(work) { work(); checks++; }

// Fail if the real page changes its contract; do not maintain a second, looser list.
const app = fs.readFileSync(path.join(__dirname, '../web/app.js'), 'utf8');
const body = app.match(/const projectFields = \[([\s\S]*?)\];/);
check(() => assert.ok(body, 'The original project editor whitelist must exist'));
const pageFields = [...body[1].matchAll(/'([^']+)'/g)].map(match => match[1]);
check(() => assert.deepEqual(ordinaryProjectFields, pageFields));
const row = Object.fromEntries(ordinaryProjectFields.map((key, index) => [key, 'SYNTHETIC-' + index]));
Object.assign(row, { id: 7, demand_id: 3, bid_id: 0, status: '进行中',
  organization_code: '001', ownership_version: 1, legacy_ownership: { registered: true },
  workflow_source: { origin: 'SYNTHETIC' }, delivery_controlled: true, future_projection: true });
const before = JSON.stringify(row);
const result = projectEditFields(row);
check(() => assert.deepEqual(Object.keys(result), [...ordinaryProjectFields, 'demand_id', 'bid_id', 'id']));
for (const key of [...ordinaryProjectFields, 'demand_id', 'bid_id', 'id']) {
  check(() => assert.equal(result[key], row[key], 'Preserve ordinary/source field ' + key));
}
for (const key of ['organization_code', 'ownership_version', 'legacy_ownership', 'workflow_source', 'status', 'delivery_controlled', 'future_projection']) {
  check(() => assert.ok(!Object.hasOwn(result, key), 'Do not roundtrip GET-only field ' + key));
}
check(() => assert.equal(JSON.stringify(row), before, 'Input row remains unchanged'));
check(() => assert.notEqual(result, row, 'Projection is a fresh object'));
check(() => assert.throws(() => projectEditFields(null), TypeError));
check(() => assert.throws(() => projectEditFields([]), TypeError));
check(() => assert.equal({ ...result, demand_id: 999 }.demand_id, 999, 'Explicit source-rebinding negative tests remain possible'));
console.log(JSON.stringify({ suite: 'project-edit-payload', checks, failures: [] }));
