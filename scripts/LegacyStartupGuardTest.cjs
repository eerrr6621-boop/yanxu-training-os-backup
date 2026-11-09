const fs=require('node:fs'),path=require('node:path'),os=require('node:os'),assert=require('node:assert/strict'),{spawnSync}=require('node:child_process');
const root=path.resolve(__dirname,'..'),output=process.argv[2];
if(!output||!path.resolve(output).includes('yanxu-review-fixes'))throw Error('Explicit owned review evidence directory required');
const own=fs.mkdtempSync(path.join(path.resolve(output),'startup-guard-')),bin=path.join(own,'bin'),calls=path.join(own,'calls.txt');fs.mkdirSync(bin);
for(const name of ['java','open','mkdir','find','sleep','kill','python3','lsof'])fs.writeFileSync(path.join(bin,name),'#!/bin/bash\nprintf "%s\\n" "$0 $*" >> "$GUARD_CALLS"\nexit 77\n',{mode:0o700});
let checks=0;const results=[];const check=(condition,label)=>{assert.ok(condition,label);checks++;};
for(const name of ['run.sh','启动系统.command']){
 const script=path.join(root,name),source=fs.readFileSync(script,'utf8');
 // Refuse execution if an actual command was reintroduced, before any runtime probe can touch the host.
 const code=source.split('\n').filter(line=>line.trim()&&!line.trim().startsWith('#'));
 check(code.every(line=>/^\s*(?:printf '%s\\n' \\|'[^']*'(?: \\)?|if \[ "\$#" -eq 1 \] && \[ "\$1" = '--help' \]; then|exit (?:0|64)|fi)\s*$/.test(line)),'guard contains only literal notice, help condition and exits');
 check(source.includes('/Users/tongyuqing/Documents/Codex/2026-10-27/task-3/local-db-adoption/launch_local.py'),'points to approved existing launcher');
 const syntax=spawnSync('/bin/bash',['--noprofile','--norc','-n',script],{env:{PATH:bin},encoding:'utf8'});check(syntax.status===0,'valid bash syntax');
 for(const args of [[],['8080'],['19999'],['--demo'],['--demo','19999'],['--help'],['--help','8080']]){
  const before=fs.readdirSync(own).sort(),r=spawnSync('/bin/bash',['--noprofile','--norc',script,...args],{cwd:own,env:{PATH:bin,GUARD_CALLS:calls},encoding:'utf8',timeout:5000});
  check(!r.error&&r.status===(args.length===1&&args[0]==='--help'?0:64),name+' refuses implicit/legacy args or shows help');
  check(r.stdout.includes('已停用')&&r.stdout.includes('launch_local.py'),'clear local-launcher guidance');
  check(!fs.existsSync(calls)&&JSON.stringify(before)===JSON.stringify(fs.readdirSync(own).sort()),'no child command, working data, output directory or browser side effect');
  results.push({script:name,args,exit_code:r.status,stdout:r.stdout.trim(),stderr:r.stderr});
 }
}
const receipt={suite:'LegacyStartupGuardTest',status:'PASS',checks,pid:process.pid,listener_started:false,user_service_contacted:false,external_launcher_executed:false,results};
fs.writeFileSync(path.join(own,'RESULT.json'),JSON.stringify(receipt,null,2)+'\n');console.log(JSON.stringify({suite:receipt.suite,status:receipt.status,checks,evidence:path.join(own,'RESULT.json')}));
