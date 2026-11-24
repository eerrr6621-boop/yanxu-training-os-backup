'use strict';
// Explicit archival entry only. Does not revive any retired route or start a legacy product.
const fs=require('node:fs'),path=require('node:path'),Module=require('node:module'),assert=require('node:assert/strict');
const repo=path.resolve(__dirname,'..'),[ack,suite,...rest]=process.argv.slice(2);
assert.equal(ack,'--acknowledge-historical-contract','Usage: node scripts/run_legacy_http.cjs --acknowledge-historical-contract SUITE [original arguments]. This is NOT current acceptance.');
const names={api:'test.js',integrity:'test_integrity.js',faculty:'test_faculty.js',requirement:'scripts/test_requirement_input_resolution_http.cjs',workflow:'scripts/IntegrationWorkflowHttp.cjs'};
assert.ok(Object.hasOwn(names,suite),'SUITE must be api, integrity, faculty, requirement, or workflow');
assert.notEqual(process.env.TRAINING_EXPECT_LOCAL_SEMANTIC,'1','Historical archival entry must not enable a model');
const filename=path.join(repo,names[suite]),original=path.join(__dirname,'legacy',path.basename(filename)+'.original');
console.error('HISTORICAL CONTRACT ONLY: original assertions are preserved, not counted as current passes. Current retired routes are expected to fail their former success expectations. No assertion is skipped or converted.');
process.argv=[process.execPath,filename,...rest];
const child=new Module(filename,module);child.filename=filename;child.paths=Module._nodeModulePaths(path.dirname(filename));
child._compile(fs.readFileSync(original,'utf8'),filename);
