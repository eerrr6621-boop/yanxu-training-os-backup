'use strict';
// Reuse the original app/modal harness without starting a browser, HTTP server, or database.
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm'), assert = require('node:assert/strict');
const source = fs.readFileSync(path.join(__dirname, 'IntegrationCourseMaintenanceUI.test.cjs'), 'utf8');
const support = vm.runInNewContext(source.slice(0, source.indexOf('\n(async()=>{')) + '\n({harness,fixture,read,data,setModules: support.setModules});', {require,__dirname,console,structuredClone,AbortController,DOMException,URL,URLSearchParams,Buffer,setImmediate});
let checks = 0;
const check = (value, label) => { assert.ok(value, label); checks++; };
const equal = (value, expected, label) => { assert.deepEqual(JSON.parse(JSON.stringify(value)), expected, label); checks++; };
async function filter(h, key, value) { const field=h.node('[data-maintenance-filter="'+key+'"]');check(field&&!field.disabled,'Filter available');field.value=value;await h.emit(field,key==='status'?'change':'input'); }
const visible = h => h.node('[data-course-maintenance]').querySelectorAll('[data-maintenance-field="course_code"]').map(field=>field.value);
(async()=>{
  let index=support.read('index.js').replace(/'\/modules\/course-catalog\/course-maintenance\.js\?v=[^']+'/,JSON.stringify(support.data(support.read('course-maintenance.js'))));
  support.setModules({catalog:await import(support.data(index))});
  const current=support.fixture(47);current.courses[1].active=false;current.courses[21].active=false;current.courses[31].course_name=current.courses[1].course_name='同名课程';
  const h=support.harness({current});await h.open();
  check(h.node('[data-course-maintenance]').textContent.includes('无需一次准备完整目录'),'Partial known course materials are explicitly supported');
  check(h.node('[data-course-maintenance]').textContent.includes('不会自动授予讲师资格'),'Adding a course never claims qualification');
  await filter(h,'search','  syn-21 ');equal(visible(h),['SYN-21'],'Code lookup tolerates case and surrounding search whitespace');
  check(h.node('[data-maintenance-field="course_code"][data-row="21"]').disabled,'Filtered saved code remains immutable');
  await h.mEdit('course_name','筛选后修改第21门',21);await filter(h,'search','同名课程');equal(visible(h),['SYN-1','SYN-31'],'Equal names remain separate explicit codes');
  await filter(h,'status','inactive');equal(visible(h),['SYN-1'],'Status and text filters combine');await h.mEdit('course_name','仅修改同名第一门',1);
  await h.mClick('clear-filter');equal(visible(h).length,20,'Clear restores paged directory');await h.mClick('next');
  equal(h.node('[data-maintenance-field="course_name"][data-row="21"]').value,'筛选后修改第21门','Other filtered draft survives page/filter changes');
  await filter(h,'search','不存在');equal(visible(h),[],'Unmatched search has empty result');check(h.node('[data-course-maintenance]').textContent.includes('没有符合筛选条件'),'No-match state differs from empty directory');
  await h.mEdit('catalog_version','daily-v2');await h.mEdit('change_comment','两门课程日常修改');await h.mClick('preview');
  const body=JSON.parse(h.requests('/courses-preview')[0].opts.body);equal(body.courses.length,47,'Filtered view submits full unchanged-plus-edited course list');equal(body.courses[21].course_name,'筛选后修改第21门','Hidden edited course is preserved');equal(body.courses[31].course_name,'同名课程','Second equal-name course is untouched');equal(body.courses[1].course_name,'仅修改同名第一门','Correct original row edited');
  check(h.node('[data-course-maintenance]').textContent.includes('SYN-21')&&h.node('[data-course-maintenance]').textContent.includes('SYN-1'),'Preview discloses all edits including hidden matches');
  await filter(h,'search','SYN-31');check(!h.mButton('confirm').disabled,'Changing display filter does not invalidate same saved preview');await h.mClick('confirm');check(h.notice().includes('已保存'),'Filtered editing completes original preview/confirm workflow');equal(h.requests('/course-management').length,2,'Confirmation rereads the published list');
  const saved=JSON.parse(h.requests('/courses-preview')[0].opts.body);equal(Object.keys(saved).sort(),['catalog_version','change_comment','courses','expected_version'],'Filtering never sends search as business data');
  await filter(h,'search','不存在');await filter(h,'status','inactive');await h.mClick('add');equal(h.node('[data-maintenance-filter="search"]').value,'','Add reveals new row by clearing query');equal(h.node('[data-maintenance-filter="status"]').value,'all','Add reveals new row by clearing status');
  equal(h.node('[data-maintenance-field="course_code"][data-row="47"]').value,'','New business code is never invented');await h.mEdit('course_code','KNOWN-ONLY',47);await h.mEdit('course_name','补充一门课程',47);await h.mEdit('catalog_version','daily-v3');await h.mEdit('change_comment','逐门补充');await h.mClick('preview');
  const addition=JSON.parse(h.requests('/courses-preview')[1].opts.body);equal(addition.courses.length,48,'One new known course needs no complete external directory');equal(addition.courses[47],{course_code:'KNOWN-ONLY',course_name:'补充一门课程',active:true},'Only explicit course facts are added');
  const clean=support.harness();await clean.open();await filter(clean,'search','SYN-1');await filter(clean,'status','active');equal(clean.posts().length,0,'Read-only filtering causes no writes');check(clean.sandbox.closeModal()!==false&&!clean.modal(),'Display filters alone do not create unsaved-change guard');
  const paged=support.harness({current:support.fixture(45)});await paged.open();await filter(paged,'search','SYN-');await paged.mClick('next');equal(visible(paged).length,20,'Filtered pagination retains page size');await paged.mClick('next');equal(visible(paged).length,5,'Filtered final page is bounded');await filter(paged,'search','SYN-44');equal(visible(paged),['SYN-44'],'New filter resets page');
  console.log(JSON.stringify({ok:true,suite:'stage2 daily course search and editing',checks,transport:'synthetic',browser:false,database:false}));
})().catch(error=>{console.error(error);process.exitCode=1;});
