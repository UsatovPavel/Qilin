import importlib.util
from pathlib import Path
import tempfile
import unittest

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('used_benchmarks', HERE / 'benchmarks.py')
bench = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bench)
REPO = HERE.parents[1]

class BenchmarksTest(unittest.TestCase):
    def test_only_used_suites_are_registered_with_distinct_dacapo_ids(self):
        cases = bench.catalogue(REPO)
        self.assertEqual(19, len(cases))
        self.assertEqual(18, len([k for k in cases if k.startswith('dacapo')]))
        self.assertIn('jolden:treeadd', cases)
        self.assertIn('dacapo2006:luindex', cases)
        self.assertIn('dacapo-bach:luindex', cases)
        self.assertFalse(any('spec' in k.lower() or 'grande' in k.lower() for k in cases))

    def test_legacy_paths_and_models_come_from_existing_artifact_metadata(self):
        case = bench.catalogue(REPO)['dacapo2006:pmd']
        self.assertEqual('dacapo.pmd.Main', case['main'])
        self.assertEqual('jre1.6.0_45', case['jre'])
        self.assertEqual('dacapo2006/pmd-deps.jar', case['dependencies'])

    def test_bach_preserves_harness_dependencies_and_reflection_variants(self):
        cases = bench.catalogue(REPO)
        self.assertEqual('Harness', cases['dacapo-bach:pmd']['main'])
        self.assertEqual('dacapo-bach/pmd-tamiflex-default.log', cases['dacapo-bach:pmd']['reflection'])
        self.assertEqual('dacapo-bach/tradebeans-tamiflex.log', cases['dacapo-bach:tradebeans']['reflection'])

    def test_plan_is_argument_vector_with_hashes_and_no_jvm_launch(self):
        with tempfile.TemporaryDirectory(prefix='ucfs bench ') as directory:
            root = Path(directory)
            app = root / 'treeadd.jar'; app.write_bytes(b'application')
            jre = root / 'model'; (jre/'lib').mkdir(parents=True); (jre/'lib/rt.jar').write_bytes(b'JRE')
            plan = bench.plan(REPO, 'jolden:treeadd', root, app, jre, root/'output', 'typed', True)
            self.assertFalse(plan['executed'])
            self.assertIn('--solver=typed', plan['command'])
            self.assertIn('--dump-pts='+str((root/'output'/'points-to.tsv').resolve()), plan['command'])
            self.assertIn('--reach-only', plan['command'])
            self.assertIn('-apppath='+str(app), plan['command'])
            self.assertIn('-mainclass=TreeAdd', plan['command'])
            self.assertIn('-Xmx48g', plan['environment']['JAVA_OPTS'])
            self.assertEqual(7200, plan['limits']['full_wall_seconds'])
            self.assertEqual(52, plan['limits']['whole_process_GiB'])
            self.assertEqual(2, len(plan['inputs']))
            self.assertTrue(all(len(x['sha256'])==64 for x in plan['inputs']))

    def test_missing_inputs_fail_before_a_command_is_published(self):
        with tempfile.TemporaryDirectory() as d:
            with self.assertRaises(FileNotFoundError):
                bench.plan(REPO, 'dacapo2006:pmd', Path(d), None, None, Path(d)/'out', 'typed', False)

    def test_unknown_suite_is_not_silently_substituted(self):
        with self.assertRaises(ValueError):
            bench.plan(REPO, 'specjvm98:_200_check', Path('/absent'), None, None, Path('/out'), 'typed', False)

    def test_full_and_reach_plans_differ_only_by_recognizer_flag(self):
        with tempfile.TemporaryDirectory() as d:
            r=Path(d); app=r/'treeadd.jar'; app.write_bytes(b'app');jre=r/'jre';(jre/'lib').mkdir(parents=True);(jre/'lib/rt.jar').write_bytes(b'rt')
            full=bench.plan(REPO,'jolden:treeadd',r,app,jre,r/'out','typed',False)
            reach=bench.plan(REPO,'jolden:treeadd',r,app,jre,r/'out','typed',True)
            self.assertEqual(full['command'],[a for a in reach['command'] if a!='--reach-only'])
            self.assertEqual(full['inputs'],reach['inputs'])

if __name__ == '__main__': unittest.main()
