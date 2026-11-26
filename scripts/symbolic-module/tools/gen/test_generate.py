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
from unittest import mock

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


# Recorded on the MacBook Pro M5 Pro (64 GB, macOS 27.2), 2026-11-24, with Chrome, Mail and other applications open
SYSCTL_MEMSIZE = '68719476736\n'
VM_STAT = '''Mach Virtual Memory Statistics: (page size of 16384 bytes)
Pages free:                                   329882.
Pages active:                                1591289.
Pages inactive:                              1591866.
Pages speculative:                             27505.
Pages throttled:                                   0.
Pages wired down:                             264144.
Pages purgeable:                              110511.
"Translation faults":                      443871441.
Pages copy-on-write:                        20216255.
Pages zero filled:                         485063825.
Pages reactivated:                           7204860.
Pages purged:                                4043494.
File-backed pages:                           1468505.
Anonymous pages:                             1742155.
Pages stored in compressor:                   643355.
Pages occupied by compressor:                 329113.
Decompressions:                              2153291.
Compressions:                                3294681.
Pageins:                                    11869209.
Pageouts:                                        561.
Swapins:                                           0.
Swapouts:                                          0.
Pages tagged:                                 279390.
Pages tagged resident:                        211976.
Pages tagged compressed:                       67414.
Pages tag-storage:                            131072.
Pages tag-storage holding tags:                 7626.
Pages tag-storage free:                         4060.
Pages tag-storage non-tag pageable:           119233.
Pages tag-storage non-tag wired:                 139.
Bytes of compressed tags:                    9380160.
Tagged compressions:                          196991.
Tagged decompressions:                        119321.
'''
# The formats of the Linux files (cgroup v2 memory.stat, /proc/meminfo; values in bytes and kB)
MEMORY_STAT = '''anon 30000000000
file 70000000000
kernel 1500000000
kernel_stack 40000000
pagetables 300000000
sec_pagetables 0
percpu 20000000
sock 5000000
vmalloc 1000000
shmem 200000000
file_mapped 900000000
file_dirty 1000000
inactive_anon 1000000
active_anon 29000000000
inactive_file 40000000000
active_file 30000000000
unevictable 0
slab_reclaimable 800000000
slab_unreclaimable 100000000
slab 900000000
'''
MEMINFO = '''MemTotal:       65843264 kB
MemFree:         1203456 kB
MemAvailable:   40123904 kB
Buffers:          123456 kB
Cached:         35000000 kB
SwapCached:            0 kB
'''


class MemoryTest(TempDirTest):
    """The memory limit and the memory in use (that the kernel cannot reclaim), on Linux and on macOS."""

    def commands(self, outputs):
        def command_output(command):
            return outputs[tuple(command)]
        return mock.patch.object(g, 'command_output', command_output)

    def test_macos_limit_is_the_physical_memory(self):
        with mock.patch.object(g, 'PLATFORM', 'darwin'), self.commands({('sysctl', '-n', 'hw.memsize'): SYSCTL_MEMSIZE}):
            self.assertEqual(64 * g.GIB, g.memory_limit())

    def test_macos_memory_used_is_the_anonymous_wired_and_compressed_pages(self):
        # Activity Monitor's "Memory Used": the anonymous pages but the purgeable ones, wired, occupied by the compressor
        with mock.patch.object(g, 'PLATFORM', 'darwin'), self.commands({('vm_stat',): VM_STAT}):
            self.assertEqual((1742155 - 110511 + 264144 + 329113) * 16384, g.memory_used())

    def test_macos_page_cache_is_not_memory_used(self):
        # Reading the datasets fills the page cache (file-backed pages, active or inactive): the kernel drops it under
        # pressure, so it does not stop a repository (as the `file` pages of the cgroup on Linux)
        cached = (VM_STAT.replace('Pages active:                                1591289.', 'Pages active:                                2591289.')
                  .replace('File-backed pages:                           1468505.', 'File-backed pages:                           2468505.')
                  .replace('Pages free:                                   329882.', 'Pages free:                                         0.'))
        self.assertNotEqual(VM_STAT, cached)
        self.assertEqual(g.vm_stat_used(VM_STAT), g.vm_stat_used(cached))

    def test_macos_page_size_of_the_header(self):
        # Intel Macs have 4 KiB pages
        small_pages = VM_STAT.replace('page size of 16384 bytes', 'page size of 4096 bytes')
        self.assertEqual(g.vm_stat_used(VM_STAT) // 4, g.vm_stat_used(small_pages))

    def test_macos_unexpected_output_is_an_error(self):
        with self.assertRaises(ValueError):
            g.vm_stat_used('Mach Virtual Memory Statistics: (page size of 16384 bytes)\nPages free: 1.\n')

    def linux(self, memory_max=None, memory_stat=None):
        cgroup = os.path.join(self.dir, 'cgroup')
        os.makedirs(cgroup)
        if memory_max is not None:
            self.write('cgroup/memory.max', memory_max)
        if memory_stat is not None:
            self.write('cgroup/memory.stat', memory_stat)
        meminfo = self.write('meminfo', MEMINFO)
        return mock.patch.multiple(g, PLATFORM='linux', CGROUP=cgroup, MEMINFO=meminfo)

    def test_linux_limit_of_the_cgroup(self):
        with self.linux(memory_max='157840048128\n'):
            self.assertEqual(157840048128, g.memory_limit())

    def test_linux_limit_without_cgroup_limit_is_the_total_memory(self):
        with self.linux(memory_max='max\n'):
            self.assertEqual(65843264 * 1024, g.memory_limit())
        shutil.rmtree(os.path.join(self.dir, 'cgroup'))
        with self.linux():
            self.assertEqual(65843264 * 1024, g.memory_limit())

    def test_linux_memory_used_of_the_cgroup_without_page_cache(self):
        with self.linux(memory_stat=MEMORY_STAT):
            self.assertEqual(30000000000 + 200000000 + 40000000 + 300000000 + 100000000 + 5000000, g.memory_used())

    def test_linux_memory_used_without_cgroup_is_total_minus_available(self):
        with self.linux():
            self.assertEqual((65843264 - 40123904) * 1024, g.memory_used())

    def test_the_memory_of_this_machine(self):
        physical = os.sysconf('SC_PAGE_SIZE') * os.sysconf('SC_PHYS_PAGES')
        limit, used = g.memory_limit(), g.memory_used()
        if sys.platform == 'darwin':
            self.assertEqual(physical, limit)
        self.assertGreater(limit, 0)
        self.assertTrue(0 < used < limit, (used, limit))


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
    def setUp(self):
        super().setUp()
        # The scheduling does not depend on the memory in use of the machine running the tests (else a test waits
        # forever for a memory it does not need, when other programs use 80% of it)
        patch = mock.patch.object(g, 'memory_used', lambda: 0)
        patch.start()
        self.addCleanup(patch.stop)

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

    def watched(self, percent_used):
        runner = FakeRunner(args(self.dir))
        runner.limit = 100 * g.GIB
        for repo_id, started, pid in (('old', 1.0, 101), ('young', 2.0, 102)):
            job = g.Job({'id': repo_id}, runner)
            job.started, job.process = started, types.SimpleNamespace(pid=pid)
            runner.running[repo_id] = job
        return runner, mock.patch.object(g, 'memory_used', lambda: percent_used * g.GIB)

    def test_memory_pressure_stops_the_repository_started_last(self):
        runner, memory = self.watched(91)
        killed = []
        with memory, mock.patch.object(g, 'kill_group', killed.append):
            self.assertTrue(runner.check_memory())
        self.assertEqual([102], killed)
        self.assertEqual([False, True], [runner.running[i].memory_kill for i in ('old', 'young')])
        runner, memory = self.watched(89)
        with memory, mock.patch.object(g, 'kill_group', killed.append):
            self.assertFalse(runner.check_memory())
            self.assertFalse(runner.admits(0))  # 89% is over the admission threshold
        self.assertEqual([102], killed)

    def test_a_memory_measurement_that_fails_stops_nothing_and_admits_nothing(self):
        # On macOS the measurement runs vm_stat: if it fails once (no process can be started), the watcher goes on
        runner, _ = self.watched(0)
        killed = []

        def fails():
            raise OSError('Resource temporarily unavailable')
        with mock.patch.object(g, 'memory_used', fails), mock.patch.object(g, 'kill_group', killed.append):
            self.assertFalse(runner.check_memory())
            self.assertFalse(runner.admits(0))
        self.assertEqual([], killed)
        with mock.patch.object(g, 'memory_used', lambda: 0):
            self.assertTrue(runner.admits(0))

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
