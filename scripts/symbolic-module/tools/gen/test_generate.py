#!/usr/bin/env python3
"""Tests of generate.py (offline): python3 -m unittest tools/gen/test_generate.py"""
import csv
import json
import os
import shutil
import sys
import tempfile
import time
import types
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.dont_write_bytecode = True
import generate as g  # noqa: E402

INFO_HEADER = ['id', 'name', 'codeLines', 'lastCommitSHA']


def read_csv(path):
    with open(path, newline='') as f:
        return list(csv.DictReader(f))


def args(run_dir, **overrides):
    values = dict(run_dir=run_dir, jar='tracto.jar', config='config.json', jobs=2, xmx='1g', oracles_timeout=60,
                  tokens_timeout=60, min_free_disk_gb=0, deps_cache=None, retry_failed=False, keep_repos=False)
    values.update(overrides)
    return types.SimpleNamespace(**values)


class TempDirTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()

    def tearDown(self):
        shutil.rmtree(self.dir, ignore_errors=True)

    def write(self, relative, text=''):
        path = os.path.join(self.dir, relative)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, 'w') as f:
            f.write(text)
        return path


class SelectTest(TempDirTest):
    def info(self, rows):
        path = os.path.join(self.dir, 'info.csv')
        with open(path, 'w', newline='') as f:
            writer = csv.writer(f)
            writer.writerow(INFO_HEADER)
            writer.writerows(rows)
        return path

    def test_maven_repositories_smallest_first_and_resumable(self):
        info = self.info([['1', 'a/big', '900', 'c1'], ['2', 'b/small', '10', 'c2'], ['3', 'c/gradle', '5', 'c3'],
                          ['4', 'd/flaky', '50', 'c4'], ['5', 'e/nocommit', '1', '']])
        out = os.path.join(self.dir, 'maven.csv')
        asked = []
        answers = {'a/big': True, 'b/small': True, 'c/gradle': False, 'd/flaky': None}

        def check(name, commit):
            asked.append((name, commit))
            return answers[name]
        counts = g.select(info, out, 2, check)
        self.assertEqual({'maven': 2, 'not maven': 1, 'unknown': 1}, dict(counts))
        self.assertEqual(4, len(asked))  # the repository without a commit is left out
        rows = read_csv(out)
        self.assertEqual(['2', '1'], [r['id'] for r in rows])
        self.assertEqual(['b/small', 'c2', '10'], [rows[0]['name'], rows[0]['commit'], rows[0]['codeLines']])
        # Again: only the unknown answer is asked
        asked.clear()
        answers['d/flaky'] = True
        g.select(info, out, 2, check)
        self.assertEqual([('d/flaky', 'c4')], asked)
        self.assertEqual(['2', '4', '1'], [r['id'] for r in read_csv(out)])


class ProcessTest(TempDirTest):
    def test_a_timeout_kills_the_whole_process_tree(self):
        pid_file = os.path.join(self.dir, 'child.pid')
        log_path = os.path.join(self.dir, 'log.txt')
        start = time.time()
        result = g.run_process(['sh', '-c', f'sleep 300 & echo $! > {pid_file}; sleep 300'], log_path, self.dir, 2)
        self.assertTrue(result['timeout'])
        self.assertLess(time.time() - start, 30)
        with open(pid_file) as f:
            child = int(f.read())
        time.sleep(0.5)
        with self.assertRaises(ProcessLookupError):
            os.kill(child, 0)
        with open(log_path) as f:
            self.assertIn('$ sh -c', f.read())

    def test_what_a_finished_process_leaves_behind_is_killed(self):
        # A daemon started by the stage (a Maven or Gradle daemon) does not outlive it
        pid_file = os.path.join(self.dir, 'daemon.pid')
        result = g.run_process(['sh', '-c', f'sleep 300 & echo $! > {pid_file}'], os.path.join(self.dir, 'log.txt'), self.dir, 30)
        self.assertFalse(result['timeout'])
        with open(pid_file) as f:
            daemon = int(f.read())
        time.sleep(0.5)
        with self.assertRaises(ProcessLookupError):
            os.kill(daemon, 0)

    def test_exit_status_and_usage(self):
        result = g.run_process(['sh', '-c', 'exit 3'], os.path.join(self.dir, 'log.txt'), self.dir, 30)
        self.assertEqual(3, result['exit'])
        self.assertFalse(result['timeout'])
        self.assertIn('max_rss_mb', result)

    def test_no_process_once_the_job_is_cancelled(self):
        job = g.Job({'id': 'x'})
        job.memory_kill = True
        with self.assertRaises(g.Stopped):
            g.run_process(['true'], os.path.join(self.dir, 'log.txt'), self.dir, 30, job)


class RepositoryTest(TempDirTest):
    def test_junit_tests_under_any_src_test_folder(self):
        self.write('repo/module/src/main/java/A.java', 'import org.junit.Test;')
        self.assertFalse(g.has_junit_tests(os.path.join(self.dir, 'repo')))
        self.write('repo/module/src/testing/java/B.java', 'import org.junit.Test;')
        self.assertFalse(g.has_junit_tests(os.path.join(self.dir, 'repo')))
        self.write('repo/module/src/test/java/C.java', 'class C {}')
        self.assertFalse(g.has_junit_tests(os.path.join(self.dir, 'repo')))
        self.write('repo/module/src/test/java/com/acme/DTest.java', 'import static org.junit.jupiter.api.Assertions.*;')
        self.assertTrue(g.has_junit_tests(os.path.join(self.dir, 'repo')))

    def test_admission(self):
        limit, reservation = 100 * g.GIB, 14 * g.GIB
        self.assertTrue(g.can_admit(6, reservation, limit, 10 * g.GIB))   # 7 x 14 = 98
        self.assertFalse(g.can_admit(7, reservation, limit, 10 * g.GIB))  # 8 x 14 = 112
        self.assertFalse(g.can_admit(0, reservation, limit, 81 * g.GIB))  # 81% in use
        self.assertEqual(12 * g.GIB, g.heap_bytes('12g'))
        self.assertEqual(512 << 20, g.heap_bytes('512m'))

    def test_partial_outputs_of_one_repository_removed(self):
        for kind in ('raw-oracles-dataset-training', 'raw-tokens-dataset-training'):
            self.write(f'output/{kind}/7/a.json')
            self.write(f'output/{kind}/77/a.json')
        g.remove_outputs(os.path.join(self.dir, 'output'), '7')
        for kind in ('raw-oracles-dataset-training', 'raw-tokens-dataset-training'):
            self.assertFalse(os.path.exists(os.path.join(self.dir, 'output', kind, '7')))
            self.assertTrue(os.path.exists(os.path.join(self.dir, 'output', kind, '77', 'a.json')))


REPOS = [{'id': str(i), 'name': f'o/r{i}', 'commit': f'c{i}', 'codeLines': i} for i in range(1, 5)]


class FakeRunner(g.Runner):
    """generate() replaced: the outcomes by repository id, a callable for the special cases."""
    outcomes = {}

    def generate(self, job, path, result):
        os.makedirs(path, exist_ok=True)
        self.write_output(job.repo['id'])
        outcome = self.outcomes.get(job.repo['id'], {'status': 'done', 'reason': None})
        if callable(outcome):
            return outcome(job)
        return dict(outcome)

    def write_output(self, repo_id):
        directory = os.path.join(self.output, 'raw-tokens-dataset-training', repo_id)
        os.makedirs(directory, exist_ok=True)
        open(os.path.join(directory, 'oracles-datapoints-0.json'), 'w').close()


class RunnerTest(TempDirTest):
    def status(self, repo_id):
        path = os.path.join(self.dir, 'status', f'{repo_id}.json')
        return g.read_json(path) if os.path.exists(path) else None

    def test_statuses_outputs_and_resume(self):
        FakeRunner.outcomes = {'2': {'status': 'skipped', 'reason': 'no JUnit test'},
                               '3': {'status': 'failed', 'reason': 'oracles exit status 1'}}
        runner = FakeRunner(args(self.dir))
        self.assertEqual(0, runner.run(REPOS))
        self.assertEqual(['done', 'skipped', 'failed', 'done'], [self.status(r['id'])['status'] for r in REPOS])
        # The outputs of a repository that is not done are removed, and the clones of the final ones
        self.assertTrue(os.path.exists(os.path.join(self.dir, 'output', 'raw-tokens-dataset-training', '1')))
        self.assertFalse(os.path.exists(os.path.join(self.dir, 'output', 'raw-tokens-dataset-training', '3')))
        self.assertFalse(os.path.exists(os.path.join(self.dir, 'work', '1')))
        self.assertTrue(os.path.exists(os.path.join(self.dir, 'work', '3')))
        # Resume: nothing to run; with --retry-failed, the failed one only
        self.assertEqual([], FakeRunner(args(self.dir)).to_run(REPOS))
        self.assertEqual(['3'], [r['id'] for r in FakeRunner(args(self.dir, retry_failed=True)).to_run(REPOS)])
        FakeRunner.outcomes = {}
        FakeRunner(args(self.dir, retry_failed=True)).run(REPOS)
        self.assertEqual('done', self.status('3')['status'])

    def test_a_stopped_repository_keeps_no_status_nor_output(self):
        def stopped(job):
            raise g.Stopped()
        FakeRunner.outcomes = {'1': stopped}
        runner = FakeRunner(args(self.dir))
        runner.run(REPOS[:1])
        self.assertIsNone(self.status('1'))
        self.assertFalse(os.path.exists(os.path.join(self.dir, 'output', 'raw-tokens-dataset-training', '1')))
        self.assertEqual(['1'], [r['id'] for r in FakeRunner(args(self.dir)).to_run(REPOS[:1])])

    def test_memory_pressure_queues_again_then_fails(self):
        attempts = []

        def memory_killed(job):
            attempts.append(job.repo['id'])
            if job.repo['id'] == '1' or len([a for a in attempts if a == '2']) == 1:
                job.memory_kill = True
                raise g.Stopped()
            return {'status': 'done', 'reason': None}
        FakeRunner.outcomes = {'1': memory_killed, '2': memory_killed}
        runner = FakeRunner(args(self.dir, jobs=1))
        runner.run(REPOS[:2])
        # '1' is stopped twice: failed; '2' once, then done
        self.assertEqual(['1', '2', '1', '2'], attempts)
        self.assertEqual(('failed', 'memory pressure'), (self.status('1')['status'], self.status('1')['reason']))
        self.assertEqual('done', self.status('2')['status'])

    def test_no_repository_starts_when_the_disk_is_full(self):
        FakeRunner.outcomes = {}
        runner = FakeRunner(args(self.dir, min_free_disk_gb=10 ** 9))
        self.assertEqual(2, runner.run(REPOS))
        self.assertEqual([None] * 4, [self.status(r['id']) for r in REPOS])

    def test_report(self):
        FakeRunner.outcomes = {'2': {'status': 'skipped', 'reason': 'no JUnit test'},
                               '3': lambda job: {'status': 'done', 'reason': None, 'datapoints': 5, 'javaVersionSource': 'detected',
                                                 'oracles': {'cpu_s': 3600, 'max_rss_mb': 100}}}
        FakeRunner(args(self.dir)).run(REPOS)
        summary = g.report(self.dir)
        self.assertEqual({'done': 3, 'skipped': 1}, summary['byStatus'])
        self.assertEqual(5, summary['datapoints'])
        self.assertEqual(1.0, summary['cpuHours'])
        self.assertEqual({'detected': 1}, summary['javaVersionSource'])


if __name__ == '__main__':
    unittest.main()
