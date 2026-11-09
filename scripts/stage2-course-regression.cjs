'use strict';
// Run an existing course suite with the pending resource-cache update applied in memory.
// Shared test files remain owned by the integration coordinator.
const fs=require('node:fs'),path=require('node:path'),Module=require('node:module');
const allowed=new Set(['IntegrationCourseMaintenanceUI.test.cjs','IntegrationCertificationMaintenanceUI.test.cjs','stage1-catalog-ui.test.cjs']);
const name=process.argv[2];if(!allowed.has(name))throw Error('Pass an existing course regression suite');
const filename=path.join(__dirname,name);
const source=fs.readFileSync(filename,'utf8').replaceAll('course-maintenance.js?v=20260923certmaintenance2','course-maintenance.js?v=20261026stage2courses1').replace("['course-maintenance.js','20260923certmaintenance2']","['course-maintenance.js','20261026stage2courses1']");
const test=new Module(filename,module);test.filename=filename;test.paths=Module._nodeModulePaths(__dirname);test._compile(source,filename);
