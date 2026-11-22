#!/usr/bin/env python3
"""Inventory and validated CLI plans for the benchmark inputs actually in use.

This tool never launches an analysis or downloads/distributes benchmark binaries.
"""
import argparse
import ast
import hashlib
import json
from pathlib import Path

JOLDEN_REPOSITORY = 'https://github.com/reprogrammer/jolden'
JOLDEN_COMMIT = 'b0ea8356a88702f5d3549b871aae8fdbcba6db7c'


def constants(path):
    values = {}
    for node in ast.parse(path.read_text()).body:
        if isinstance(node, ast.Assign) and len(node.targets)==1 and isinstance(node.targets[0], ast.Name):
            try:
                values[node.targets[0].id] = ast.literal_eval(node.value)
            except (ValueError, TypeError):
                pass
    return values


def catalogue(repo):
    legacy = constants(repo/'artifact/util/benchmark.py')
    bach = constants(repo/'artifact/util/dacapobach.py')
    result = {'jolden:treeadd': {'main':'TreeAdd', 'jre':'jre1.6.0_45',
        'app':None, 'dependencies':None, 'reflection':None,
        'source_repository':JOLDEN_REPOSITORY, 'source_commit':JOLDEN_COMMIT}}
    for name in legacy['BENCHMARKS']:
        app = legacy['APPPATH'][name]
        if not app.startswith('benchmarks/dacapo2006/'):
            continue
        result['dacapo2006:'+name] = {'main':legacy['MAINCLASSES'][name],
            'jre':legacy['JREVERSION'][name], 'app':app.removeprefix('benchmarks/'),
            'dependencies':legacy['LIBPATH'][name].removeprefix('benchmarks/'),
            'reflection':legacy['TAMIFLEXLOG'][name].removeprefix('benchmarks/')}
    for name in bach['BENCHMARKS']:
        result['dacapo-bach:'+name] = {'main':'Harness', 'jre':'jre1.8.0_121_debug',
            'app':'dacapo-bach/'+name+'.jar', 'dependencies':'dacapo-bach/'+name+'-deps.jar',
            'reflection':'dacapo-bach/'+name+('-tamiflex.log' if name=='tradebeans' else '-tamiflex-default.log')}
    return result


def fingerprint(path):
    path = path.resolve()
    if not path.is_file():
        raise FileNotFoundError('Required benchmark input: '+str(path))
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda:stream.read(1024*1024), b''):
            h.update(chunk)
    return {'path':str(path), 'bytes':path.stat().st_size, 'sha256':h.hexdigest()}


def plan(repo, case_id, benchmark_root, jolden_jar, jre, out, solver, reach):
    cases = catalogue(repo)
    if case_id not in cases:
        raise ValueError('Unknown/unused case: '+case_id)
    if solver not in ('plain','typed','none'):
        raise ValueError('This workflow supports plain, typed, or export-only none')
    case = cases[case_id]
    if case['app'] is None:
        if jolden_jar is None:
            raise ValueError('JOlden requires --jolden-jar (see README preparation)')
        app = Path(jolden_jar).resolve()
    else:
        app = (benchmark_root/case['app']).resolve()
    jre = (Path(jre) if jre is not None else benchmark_root/'JREs'/case['jre']).resolve()
    # A partial/missing JRE must never silently fall back to the runtime JDK.
    fingerprints = [fingerprint(app), fingerprint(jre/'lib/rt.jar')]
    model_jars = sorted(p for p in jre.rglob('*.jar') if p != jre/'lib/rt.jar')
    fingerprints.extend(fingerprint(p) for p in model_jars)
    command = [str((repo/'qilin.ucfs/build/install/qilin.ucfs/bin/qilin.ucfs').resolve()),
        '--solver='+solver]
    if solver != 'none':
        command.append('--dump-pts='+str((out/'points-to.tsv').resolve()))
    if reach:
        command.append('--reach-only')
    command += ['--out='+str(out.resolve()), '--', '-pta=insens', '-singleentry',
        '-apppath='+str(app), '-mainclass='+case['main'], '-jre='+str(jre),
        '-clinit=ONFLY', '-lcs', '-mh', '-pae', '-pe']
    for key, flag in [('dependencies','-libpath='),('reflection','-reflectionlog=')]:
        if case[key] is not None:
            path = (benchmark_root/case[key]).resolve()
            fingerprints.append(fingerprint(path)); command.append(flag+str(path))
    return {'case':case_id, 'executed':False, 'entrypoint':'qilin.ucfs.CliKt',
        'case_model':case, 'command':command, 'environment':{'JAVA_OPTS':'-Xmx48g -Xss4m -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError'},
        'inputs':fingerprints, 'required_output_directories':[str(out.resolve())], 'limits':{'heap_GiB':48,'whole_process_GiB':52,'full_wall_seconds':7200},
        'resource_control':'Planning only. Apply whole-process/time/Heavy controls before executing; see README.',
        'measurement_scope':'CI preanalysis + export + chosen UCFS solver + output. This CLI does not select Qilin s-2c.'}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--list',action='store_true')
    parser.add_argument('--case')
    parser.add_argument('--benchmark-root',type=Path)
    parser.add_argument('--jolden-jar',type=Path)
    parser.add_argument('--jre',type=Path)
    parser.add_argument('--out',type=Path)
    parser.add_argument('--solver',choices=['plain','typed','none'],default='typed')
    parser.add_argument('--reach-only',action='store_true')
    args=parser.parse_args(); repo=Path(__file__).resolve().parents[2]
    if args.list:
        print(json.dumps(catalogue(repo),indent=2)); return
    if args.case is None or args.out is None:
        parser.error('--case and --out are required unless --list')
    try:
        data=plan(repo,args.case,args.benchmark_root or repo/'artifact/benchmarks',args.jolden_jar,args.jre,args.out,args.solver,args.reach_only)
    except (ValueError,FileNotFoundError) as exc:
        parser.error(str(exc))
    print(json.dumps(data,indent=2))

if __name__=='__main__':main()
