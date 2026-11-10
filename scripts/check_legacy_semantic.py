#!/usr/bin/env python3
"""Explicit historical/offline verification. Never a current product acceptance or model run."""
import argparse, hashlib, json, os, pathlib, re, subprocess, sys

repo = pathlib.Path(__file__).resolve().parent.parent
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--java', required=True)
parser.add_argument('--classes', required=True, help='Read-only compiled current product classes')
parser.add_argument('--output', required=True, help='Caller-created empty directory outside the source tree')
parser.add_argument('--historical-source', help='Optional checkout of pinned 5051764d historical source for the five metadata suites')
args = parser.parse_args()
out = pathlib.Path(args.output).resolve(strict=True)
if out == repo or repo in out.parents or not out.is_dir() or any(out.iterdir()):
    parser.error('--output must be empty and outside the repository')
classes = pathlib.Path(args.classes).resolve(strict=True)
manifest = json.loads((repo/'scripts/legacy/semantic-inventory.json').read_text())
entries = manifest['entries']
assert len(entries) == 24 and len({r['path'] for r in entries}) == 24
for row in entries:
    assert hashlib.sha256((repo/row['path']).read_bytes()).hexdigest() == row['sha256'], row['path']
# All executable helper sources in the optional historical tree must match its pinned public commit.
history = pathlib.Path(args.historical_source).resolve(strict=True) if args.historical_source else None
if history:
    for name, digest in json.loads((repo/'scripts/legacy/posttrain-sources.json').read_text()).items():
        assert hashlib.sha256((history/'semantic'/name).read_bytes()).hexdigest() == digest, name
    for row in entries:
        assert hashlib.sha256((history/row['path']).read_bytes()).hexdigest() == row['sha256'], row['path']
(out/'classes').mkdir(); (out/'tmp').mkdir()
env = {k:v for k,v in os.environ.items() if not k.startswith(('YANXU_', 'PYTHON', 'JAVA_', 'JDK_', '_JAVA', 'ACTOR_TEST_')) and k != 'CLASSPATH'}
env.update(PYTHONDONTWRITEBYTECODE='1', TMPDIR=str(out/'tmp'), YANXU_LOGIN_MAIL_TRANSPORT='disabled',
           JAVA_TOOL_OPTIONS='-Xmx512m -Djava.io.tmpdir='+str(out/'tmp'))
cp = os.pathsep.join([str(out/'classes'),str(classes),str(repo/'lib/h2.jar'),str(repo/'lib/pdfbox-app-3.0.8.jar'),str(repo/'lib/ip2region-3.3.7.jar')])
env.update(ACTOR_TEST_JAVA=args.java, ACTOR_TEST_CLASSPATH=cp)
with (out/'compile.log').open('w') as log:
    subprocess.run([args.java,'-Xmx512m','-jar',str(repo/'lib/ecj.jar'),'-17','-encoding','UTF-8','-nowarn','-cp',cp,'-d',str(out/'classes'),
                    *[str(repo/'scripts'/n) for n in ['PosttrainingV2Probe.java','LearnerArtifactTest.java','PostposedExclusionTest.java','MethodContrastTest.java']]],
                   cwd=repo,env=env,stdout=log,stderr=subprocess.STDOUT,check=True)
# A Python audit guard rejects network use; model packages may not be imported.
# Tests retain their original assertions and mocks. Their original skip clauses are not accepted as passes.
wrapper = '''import sys,runpy,pathlib
p=pathlib.Path(sys.argv[1]);sys.path.insert(0,str(p.parent));sys.argv=[str(p)]
def guard(event,args):
 if event in ('socket.connect','socket.bind','socket.getaddrinfo'): raise RuntimeError('Historical offline tests cannot use the network')
 if event=='import' and str(args[0]).split('.')[0] in ('torch','transformers','onnxruntime','sentence_transformers'): raise RuntimeError('Model imports forbidden in historical offline tests')
sys.addaudithook(guard)
runpy.run_path(str(p),run_name='__main__')
'''
results=[]
for row in entries:
    training=row['profile']=='historical-training-metadata'
    if training and not history:
        results.append({'path':row['path'],'status':'NOT_RUN','reason':'Explicit --historical-source required for retired training helpers'})
        continue
    source=(history if training else repo)/row['path']
    log=out/(source.stem+'.log')
    failure=None;code=None
    with log.open('w') as stream:
        try:
            run=subprocess.run([sys.executable,'-B','-c',wrapper,str(source)],cwd=source.parent,env=env,stdout=stream,stderr=subprocess.STDOUT,timeout=120)
            code=run.returncode
        except (subprocess.TimeoutExpired,OSError) as exc:
            failure=str(exc);stream.write('\nFAILED: '+failure+'\n')
    text=log.read_text();match=re.search(r'Ran (\d+) tests?',text)
    skipped=bool(re.search(r'\bskipped[= ]',text))
    record={'path':row['path'],'status':'PASSED' if code==0 and not skipped and match else 'FAILED',
            'exit_code':code,'error':failure,'test_count':int(match.group(1)) if match else 0,'skipped':skipped,'log':str(log)}
    results.append(record);print(json.dumps(record),flush=True)
summary={'profile':'historical-offline-only','current_product_acceptance':False,'model_executed':False,'results':results,
         'passed':sum(r['status']=='PASSED' for r in results),'failed':sum(r['status']=='FAILED' for r in results),
         'not_run':sum(r['status']=='NOT_RUN' for r in results),'test_count':sum(r.get('test_count',0) for r in results)}
(out/'result.json').write_text(json.dumps(summary,indent=2)+'\n');print(json.dumps(summary))
sys.exit(1 if summary['failed'] else 2 if summary['not_run'] else 0)
