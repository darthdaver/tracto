#!/usr/bin/env python3
"""Generation of the training datasets over the repositories of the SEART list (Maven repositories only).

Replaces bash/oracles-dataset.sh, bash/tokens-dataset.sh and bash/dataset-parallel.sh for the generation: the same jar,
configuration and dependency resolution as the A/B runs (tools/ab/ab.py, dataset v4), with the scheduling a run of
weeks needs. Requirements: Python 3.9+, git, Maven (`mvn` on the PATH), the JDK of TRACTO_JAVA_HOME (default: the
project-local Corretto 21.0.6), the JDK sources in lib/.

Subcommands:
  select [--info CSV] [--out CSV] [-j N]
      The Maven repositories of the list: for each repository of the SEART information file (id, name, lastCommitSHA,
      codeLines), whether a `pom.xml` exists at the root at that commit (one request to raw.githubusercontent.com, no
      clone). Resumable (the answers are kept in <out>.jsonl; the requests that failed are asked again). Writes the
      Maven repositories (id, name, commit, codeLines), the smallest first.
  run <run-dir> --jar JAR [--config JSON] [--repos CSV] [-j N] [--xmx 12g] [--oracles-timeout S] [--tokens-timeout S]
      [--min-free-disk-gb G] [--retry-failed] [--only id,...] [--limit N] [--keep-repos] [--deps-cache DIR]
      Generates the oracles and the tokens datasets of each repository into <run-dir>/output (the layout of the bash
      scripts: output/raw-tokens-dataset-training/<id>/...), with at most N repositories at a time. For each one:
        1. clones the pinned commit only (no history) into <run-dir>/work/<id>;
        2. skips it when no test file under a `src/test` folder imports JUnit (`org.junit`): nothing to generate;
        3. resolves its dependencies (tools/deps/resolve_maven.py; a failure is logged, the generation goes on without
           them, as in the A/B runs);
        4. runs the oracles stage, then the tokens stage when it produced datapoints; each stage has a heap limit and a
           timeout (the whole process tree is killed);
        5. writes <run-dir>/status/<id>.json (done, skipped, failed or timeout, the reason, the time, CPU and peak
           memory of each stage, the datapoints, the dependencies, the source of the Java version) and deletes the
           clone.
      Resumable: a repository with a status is not run again (`--retry-failed`: the failed ones and the timeouts are);
      its partial outputs are removed before a run. Memory: a repository starts only when the reservations of the
      running ones (heap + 2 GiB each) leave room in the memory limit (the cgroup limit of the container) and the
      memory in use is under 80%; at 90% the repository started last is stopped and queued again (twice: failed).
      Disk: no repository starts when the free disk is under the minimum; the run stops when the running ones end.
      SIGINT/SIGTERM: the running stages are stopped, their repositories keep no status (run again at the next start).
  report <run-dir>
      Totals of a run: the repositories by status and reason, datapoints, CPU hours, peak memory, the source of the
      Java version (how often it was not detected), the size of the output. Writes <run-dir>/report.json.
"""
import argparse
import collections
import csv
import json
import os
import shutil
import signal
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor

MODULE = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..'))
REPO_ROOT = os.path.abspath(os.path.join(MODULE, '..', '..'))
JAVA_HOME = os.environ.get('TRACTO_JAVA_HOME', os.path.join(REPO_ROOT, 'scripts', 'utils', 'resources', 'sdkman', 'candidates', 'java', '21.0.6-amzn'))
JAVA = os.path.join(JAVA_HOME, 'bin', 'java')
INFO_CSV = os.path.join(REPO_ROOT, 'resources', 'seart-github-repos-info.csv')
CONFIG = os.path.join(MODULE, 'src', 'main', 'resources', 'dataset', 'oracles-dataset_config.json')
RESOLVE_MAVEN = os.path.join(MODULE, 'tools', 'deps', 'resolve_maven.py')
JAVA_VERSION_ARG = 'auto'
OUT_OF_MEMORY = 'java.lang.OutOfMemoryError'
CGROUP = '/sys/fs/cgroup'
GIB = 1 << 30
FINAL = ('done', 'skipped')
RETRYABLE = ('failed', 'timeout')
# Memory: admission and pressure, in percent of the limit; the overhead reserved per repository besides the heap (the
# JVM outside the heap, Maven, git)
ADMIT_PCT, PRESSURE_PCT, OVERHEAD_GIB = 80.0, 90.0, 2.0
MEMORY_KILLS_BEFORE_FAILURE = 2
CLONE_SECONDS, DEPS_SECONDS = 1800, 3600


def log(message):
    print(time.strftime('%H:%M:%S ') + message, flush=True)


def read_text(path, errors=None):
    with open(path, errors=errors) as f:
        return f.read()


def read_json(path):
    with open(path) as f:
        return json.load(f)


def write_json(path, value):
    """Atomically (a status is either absent or complete)."""
    temporary = f'{path}.tmp{os.getpid()}'
    with open(temporary, 'w') as f:
        json.dump(value, f, indent=1)
    os.replace(temporary, path)


# ------------------------------------------------------------------------------------------------------------- select

def pom_exists(name, commit, timeout=30):
    """True / False, or None when the answer is unknown (network error, rate limit): asked again at the next select."""
    url = f'https://raw.githubusercontent.com/{name}/{commit}/pom.xml'
    request = urllib.request.Request(url, method='HEAD', headers={'User-Agent': 'tracto-dataset-generation'})
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.status == 200
    except urllib.error.HTTPError as e:
        return False if e.code == 404 else None
    except (urllib.error.URLError, OSError):
        return None


def read_info(path):
    """The repositories of the SEART information file: id, name, commit, codeLines (the ones without a commit left out)."""
    repos = []
    with open(path, newline='') as f:
        for row in csv.DictReader(f):
            commit = (row.get('lastCommitSHA') or '').strip()
            if not commit:
                continue
            code_lines = row.get('codeLines') or '0'
            repos.append({'id': row['id'].strip(), 'name': row['name'].strip(), 'commit': commit,
                          'codeLines': int(code_lines) if code_lines.isdigit() else 0})
    return repos


def select(info, out, jobs, check=pom_exists):
    repos = read_info(info)
    answers_path = out + '.jsonl'
    answers = {}
    if os.path.exists(answers_path):
        with open(answers_path) as f:
            for line in f:
                answer = json.loads(line)
                answers[answer['id']] = answer
    todo = [r for r in repos if answers.get(r['id'], {}).get('maven') is None]
    log(f'select: {len(repos)} repositories, {len(repos) - len(todo)} already answered, {len(todo)} to ask')
    lock = threading.Lock()
    with open(answers_path, 'a') as answers_file:
        def ask(repo):
            answer = dict(repo, maven=check(repo['name'], repo['commit']))
            with lock:
                answers[repo['id']] = answer
                answers_file.write(json.dumps(answer) + '\n')
                answers_file.flush()
        with ThreadPoolExecutor(jobs) as pool:
            list(pool.map(ask, todo))
    maven = sorted((a for a in answers.values() if a['maven']), key=lambda a: (a['codeLines'], a['id']))
    with open(out, 'w', newline='') as f:
        writer = csv.writer(f)
        writer.writerow(['id', 'name', 'commit', 'codeLines'])
        for a in maven:
            writer.writerow([a['id'], a['name'], a['commit'], a['codeLines']])
    counts = collections.Counter({True: 'maven', False: 'not maven', None: 'unknown'}[a['maven']] for a in answers.values())
    log(f'select: {dict(counts)}; {len(maven)} Maven repositories written to {out}')
    return counts


# ---------------------------------------------------------------------------------------------------------- processes

class Stopped(Exception):
    """The run is stopping (signal) or the job was stopped for memory: the repository keeps no status of this attempt."""


def run_process(command, log_path, cwd, timeout, job=None, env=None):
    """Runs a command in its own process group with its output in a log file: exit status, wall and CPU time, peak
    memory, and whether it timed out (then the whole group is killed)."""
    if job is not None and job.cancelled():
        raise Stopped()
    start = time.time()
    with open(log_path, 'a') as log_file:
        log_file.write('$ ' + ' '.join(command) + '\n')
        log_file.flush()
        process = subprocess.Popen(command, cwd=cwd, stdout=log_file, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL,
                                   start_new_session=True, env=env)
    if job is not None:
        job.process = process
    timed_out = False
    deadline = start + timeout
    while True:
        pid, status, usage = os.wait4(process.pid, os.WNOHANG)
        if pid:
            break
        if time.time() > deadline:
            timed_out = True
            kill_group(process.pid)
            _, status, usage = os.wait4(process.pid, 0)
            break
        time.sleep(0.5)
    kill_group(process.pid)  # what the process left behind (daemons of Maven, ...)
    if job is not None:
        job.process = None
    return {
        'exit': os.waitstatus_to_exitcode(status),
        'timeout': timed_out,
        'wall_s': round(time.time() - start, 1),
        'cpu_s': round(usage.ru_utime + usage.ru_stime, 1),
        'max_rss_mb': round(usage.ru_maxrss / ((1 << 20) if sys.platform == 'darwin' else (1 << 10))),
    }


def kill_group(pid):
    try:
        os.killpg(pid, signal.SIGKILL)
    except (ProcessLookupError, PermissionError):
        pass


# ------------------------------------------------------------------------------------------------------------- memory

def memory_limit():
    try:
        value = read_text(os.path.join(CGROUP, 'memory.max')).strip()
        if value != 'max':
            return int(value)
    except OSError:
        pass
    return meminfo()['MemTotal']


def meminfo():
    return {line.split(':')[0]: int(line.split()[1]) * 1024 for line in read_text('/proc/meminfo').splitlines()}


# The memory of a cgroup the kernel cannot reclaim: the page cache (`file`, active or not) is left out, it is dropped
# under pressure (reading the datasets fills it: 69 GiB of active cache after a comparison of v3 and v4, 2026-11-18)
UNRECLAIMABLE = ('anon', 'shmem', 'kernel_stack', 'pagetables', 'slab_unreclaimable', 'sock')


def memory_used():
    """The memory the kernel cannot reclaim: of the cgroup (memory.stat), else MemTotal - MemAvailable."""
    try:
        stat = dict(line.split() for line in read_text(os.path.join(CGROUP, 'memory.stat')).splitlines())
        return sum(int(stat[key]) for key in UNRECLAIMABLE if key in stat)
    except (OSError, ValueError):
        info = meminfo()
        return info['MemTotal'] - info['MemAvailable']


def heap_bytes(xmx):
    units = {'k': 1 << 10, 'm': 1 << 20, 'g': 1 << 30}
    xmx = xmx.lower()
    return int(xmx[:-1]) * units[xmx[-1]] if xmx[-1] in units else int(xmx)


def can_admit(running, reservation, limit, used):
    """A new repository fits: the reservations of the running ones and its own within the limit, the memory in use under
    the admission threshold."""
    return (running + 1) * reservation <= limit and 100.0 * used / limit < ADMIT_PCT


# --------------------------------------------------------------------------------------------------------- repository

def has_junit_tests(repo):
    """A Java file under a `src/test` folder that imports JUnit (the pipeline generates from JUnit assertions only)."""
    for root, dirs, files in os.walk(repo):
        dirs[:] = [d for d in dirs if d not in ('.git', 'target', 'node_modules')]
        if f'{os.sep}src{os.sep}test{os.sep}' not in root + os.sep:
            continue
        for name in files:
            if name.endswith('.java'):
                try:
                    with open(os.path.join(root, name), errors='replace') as f:
                        if 'org.junit' in f.read():
                            return True
                except OSError:
                    continue
    return False


def clone(repo, commit, path, log_path, job=None):
    """The pinned commit only, no history; True if checked out."""
    env = dict(os.environ, GIT_TERMINAL_PROMPT='0')
    url = f'https://github.com/{repo}.git'
    os.makedirs(path)
    for command in (['git', 'init', '-q'], ['git', 'remote', 'add', 'origin', url],
                    ['git', 'fetch', '-q', '--depth', '1', '--no-tags', 'origin', commit],
                    ['git', 'checkout', '-q', '--detach', 'FETCH_HEAD']):
        result = run_process(command, log_path, path, CLONE_SECONDS, job, env)
        if result['exit'] != 0 or result['timeout']:
            return False
    head = subprocess.run(['git', 'rev-parse', 'HEAD'], cwd=path, capture_output=True, text=True).stdout.strip()
    return head == commit


def remove_outputs(output, repo_id):
    """The partial outputs of a repository (an earlier attempt that did not end)."""
    if os.path.isdir(output):
        for kind in os.listdir(output):
            shutil.rmtree(os.path.join(output, kind, repo_id), ignore_errors=True)


class Job:
    def __init__(self, repo, runner=None):
        self.repo = repo
        self.runner = runner
        self.process = None
        self.started = None
        self.memory_kill = False

    def cancelled(self):
        """The run is stopping or the job was stopped for memory: no new process."""
        return self.memory_kill or (self.runner is not None and self.runner.stopping)


class Runner:
    def __init__(self, args):
        self.args = args
        self.run_dir = os.path.abspath(args.run_dir)
        self.output = os.path.join(self.run_dir, 'output')
        self.status_dir = os.path.join(self.run_dir, 'status')
        self.work = os.path.join(self.run_dir, 'work')
        self.logs = os.path.join(self.run_dir, 'logs')
        for d in (self.output, self.status_dir, self.work, self.logs):
            os.makedirs(d, exist_ok=True)
        self.jar = os.path.abspath(args.jar)
        self.config = os.path.abspath(args.config)
        self.jvm = [f'-Xmx{args.xmx}']
        self.reservation = heap_bytes(args.xmx) + int(OVERHEAD_GIB * GIB)
        self.limit = memory_limit()
        self.stopping = False
        self.lock = threading.Lock()
        self.running = {}
        self.memory_kills = collections.Counter()

    # The status of a repository: None when it has none (never run, or stopped)
    def status(self, repo_id):
        path = os.path.join(self.status_dir, f'{repo_id}.json')
        return read_json(path) if os.path.exists(path) else None

    def to_run(self, repos):
        selected = []
        for repo in repos:
            status = self.status(repo['id'])
            if status is None or (self.args.retry_failed and status['status'] in RETRYABLE):
                selected.append(repo)
        return selected

    def stage(self, job, name, command, timeout, result):
        log_path = os.path.join(self.logs, job.repo['id'], f'{name}.log')
        outcome = run_process(command, log_path, MODULE, timeout, job)
        result[name] = outcome
        if self.stopping or job.memory_kill:
            raise Stopped()
        return outcome, read_text(log_path, 'replace')

    def process(self, job):
        repo = job.repo
        repo_id = repo['id']
        start = time.time()
        shutil.rmtree(os.path.join(self.logs, repo_id), ignore_errors=True)
        os.makedirs(os.path.join(self.logs, repo_id))
        path = os.path.join(self.work, repo_id)
        shutil.rmtree(path, ignore_errors=True)
        remove_outputs(self.output, repo_id)
        result = {'id': repo_id, 'name': repo['name'], 'commit': repo['commit'], 'codeLines': repo.get('codeLines'),
                  'jar': self.jar, 'xmx': self.args.xmx}
        try:
            result.update(self.generate(job, path, result))
        except Stopped:
            shutil.rmtree(path, ignore_errors=True)
            remove_outputs(self.output, repo_id)
            return None
        result['seconds'] = round(time.time() - start, 1)
        if result['status'] in FINAL and not self.args.keep_repos:
            shutil.rmtree(path, ignore_errors=True)
        if result['status'] != 'done':
            remove_outputs(self.output, repo_id)
        write_json(os.path.join(self.status_dir, f'{repo_id}.json'), result)
        return result

    def generate(self, job, path, result):
        repo_id = job.repo['id']
        if not clone(job.repo['name'], job.repo['commit'], path, os.path.join(self.logs, repo_id, 'clone.log'), job):
            if self.stopping or job.memory_kill:
                raise Stopped()
            return {'status': 'failed', 'reason': 'clone'}
        if not os.path.exists(os.path.join(path, 'pom.xml')):
            return {'status': 'skipped', 'reason': 'no pom.xml'}
        if not has_junit_tests(path):
            return {'status': 'skipped', 'reason': 'no JUnit test'}
        # The dependencies (a failure is not a failure of the generation: it goes on without them)
        env = dict(os.environ, JAVA_HOME=JAVA_HOME, PYTHONDONTWRITEBYTECODE='1')
        deps_log = os.path.join(self.logs, repo_id, 'deps.log')
        command = [sys.executable, RESOLVE_MAVEN, path, '--tracto-jar', self.jar]
        if self.args.deps_cache:
            command += ['--cache', os.path.abspath(self.args.deps_cache)]
        result['deps'] = run_process(command, deps_log, MODULE, DEPS_SECONDS, job, env)
        if self.stopping or job.memory_kill:
            raise Stopped()
        report_path = os.path.join(path, 'processed_libs', 'dependencies.json')
        if os.path.exists(report_path):
            report = read_json(report_path)
            result['deps'].update({k: report.get(k) for k in ('status', 'mode', 'modules', 'counts', 'error', 'cached')})
        # The oracles stage
        outcome, text = self.stage(job, 'oracles', [JAVA, *self.jvm, '-jar', self.jar, 'oracles', repo_id, path, self.output,
                                                    MODULE, JAVA_VERSION_ARG, self.config], self.args.oracles_timeout, result)
        statistics_path = os.path.join(self.output, 'statistics-oracles-dataset-training', repo_id, f'statistics-{repo_id}.json')
        if os.path.exists(statistics_path):
            statistics = read_json(statistics_path)
            result['datapoints'] = statistics.get('oracleDatapoints')
            result['javaVersion'] = statistics.get('javaVersion')
            result['javaVersionSource'] = statistics.get('javaVersionSource')
        if outcome['timeout']:
            return {'status': 'timeout', 'reason': 'oracles'}
        if OUT_OF_MEMORY in text:
            return {'status': 'failed', 'reason': 'oracles OutOfMemoryError'}
        if outcome['exit'] != 0:
            return {'status': 'failed', 'reason': f'oracles exit status {outcome["exit"]}'}
        if not os.path.exists(statistics_path) or 'Oracles datapoints generated' not in text:
            return {'status': 'failed', 'reason': 'oracles stage did not end'}
        if not result.get('datapoints'):
            return {'status': 'done', 'reason': 'no datapoint'}
        # The tokens stage
        outcome, text = self.stage(job, 'tokens', [JAVA, *self.jvm, '-jar', self.jar, 'tokens', repo_id, path, self.output,
                                                   MODULE, JAVA_VERSION_ARG], self.args.tokens_timeout, result)
        tokens_dir = os.path.join(self.output, 'raw-tokens-dataset-training', repo_id)
        if outcome['timeout']:
            return {'status': 'timeout', 'reason': 'tokens'}
        if OUT_OF_MEMORY in text:
            return {'status': 'failed', 'reason': 'tokens OutOfMemoryError'}
        if outcome['exit'] != 0:
            return {'status': 'failed', 'reason': f'tokens exit status {outcome["exit"]}'}
        if not os.path.isdir(tokens_dir) or not os.listdir(tokens_dir):
            return {'status': 'failed', 'reason': 'no tokens dataset'}
        result['outputBytes'] = sum(os.path.getsize(os.path.join(root, f)) for root, _, files in os.walk(tokens_dir) for f in files)
        return {'status': 'done', 'reason': None}

    # ---- scheduling
    def stop(self, *_):
        if not self.stopping:
            log('stopping: the running stages are killed, their repositories keep no status')
        self.stopping = True
        with self.lock:
            for job in self.running.values():
                if job.process is not None:
                    kill_group(job.process.pid)

    def watch_memory(self):
        while not self.stopping:
            used = memory_used()
            if 100.0 * used / self.limit >= PRESSURE_PCT:
                with self.lock:
                    candidates = [j for j in self.running.values() if not j.memory_kill and j.process is not None]
                    if candidates:
                        youngest = max(candidates, key=lambda j: j.started)
                        youngest.memory_kill = True
                        log(f'memory {100.0 * used / self.limit:.1f}%: stopping {youngest.repo["id"]} (queued again)')
                        kill_group(youngest.process.pid)
                time.sleep(10)
            time.sleep(2)

    def run(self, repos):
        signal.signal(signal.SIGINT, self.stop)
        signal.signal(signal.SIGTERM, self.stop)
        queue = collections.deque(self.to_run(repos))
        log(f'run: {len(repos)} repositories, {len(queue)} to run, at most {self.args.jobs} at a time, '
            f'memory limit {self.limit / GIB:.1f} GiB, reservation {self.reservation / GIB:.1f} GiB each')
        threading.Thread(target=self.watch_memory, daemon=True).start()
        pool = ThreadPoolExecutor(self.args.jobs)
        futures = {}
        disk_full = False
        while (queue or futures) and not self.stopping:
            for future in [f for f in futures if f.done()]:
                job = futures.pop(future)
                with self.lock:
                    self.running.pop(job.repo['id'], None)
                result = future.result()
                if result is None and job.memory_kill:
                    self.memory_kills[job.repo['id']] += 1
                    if self.memory_kills[job.repo['id']] >= MEMORY_KILLS_BEFORE_FAILURE:
                        write_json(os.path.join(self.status_dir, f'{job.repo["id"]}.json'),
                                   {'id': job.repo['id'], 'name': job.repo['name'], 'commit': job.repo['commit'],
                                    'status': 'failed', 'reason': 'memory pressure'})
                    else:
                        queue.append(job.repo)
                elif result is not None:
                    log(f'{result["id"]}: {result["status"]}{" (" + result["reason"] + ")" if result.get("reason") else ""}, '
                        f'{result.get("datapoints") or 0} datapoints, {result["seconds"]:.0f} s')
            free = shutil.disk_usage(self.run_dir).free
            if free < self.args.min_free_disk_gb * GIB:
                if not disk_full:
                    log(f'free disk {free / GIB:.0f} GiB under {self.args.min_free_disk_gb} GiB: no new repository')
                disk_full = True
            if queue and not disk_full and len(futures) < self.args.jobs and \
                    can_admit(len(futures), self.reservation, self.limit, memory_used()):
                job = Job(queue.popleft(), self)
                job.started = time.time()
                with self.lock:
                    self.running[job.repo['id']] = job
                futures[pool.submit(self.process, job)] = job
                continue
            if disk_full and not futures:
                break
            time.sleep(1)
        pool.shutdown(wait=True)
        log(f'run: {"stopped" if self.stopping else "paused (disk)" if disk_full else "complete"}')
        return 2 if disk_full else 1 if self.stopping else 0


def read_repos(path):
    with open(path, newline='') as f:
        return [{'id': r['id'], 'name': r['name'], 'commit': r['commit'],
                 'codeLines': int(r['codeLines']) if r.get('codeLines', '').isdigit() else 0} for r in csv.DictReader(f)]


# ------------------------------------------------------------------------------------------------------------- report

def report(run_dir):
    statuses = []
    status_dir = os.path.join(run_dir, 'status')
    for name in sorted(os.listdir(status_dir)):
        if name.endswith('.json'):
            statuses.append(read_json(os.path.join(status_dir, name)))
    by_status = collections.Counter(s['status'] for s in statuses)
    by_reason = collections.Counter(f'{s["status"]}: {s.get("reason")}' for s in statuses if s['status'] != 'done' or s.get('reason'))
    done = [s for s in statuses if s['status'] == 'done']
    cpu = sum(s.get(stage, {}).get('cpu_s', 0) for s in statuses for stage in ('deps', 'oracles', 'tokens'))
    summary = {
        'repositories': len(statuses),
        'byStatus': dict(by_status),
        'byReason': dict(by_reason.most_common()),
        'datapoints': sum(s.get('datapoints') or 0 for s in done),
        'repositoriesWithDatapoints': sum(1 for s in done if s.get('datapoints')),
        'cpuHours': round(cpu / 3600, 1),
        'peakMemoryMb': max((s.get(stage, {}).get('max_rss_mb', 0) for s in statuses for stage in ('oracles', 'tokens')), default=0),
        'javaVersionSource': dict(collections.Counter(s.get('javaVersionSource') for s in statuses if 'javaVersionSource' in s)),
        'tokensOutputGb': round(sum(s.get('outputBytes', 0) for s in done) / GIB, 2),
        'deps': dict(collections.Counter((s.get('deps') or {}).get('status') for s in statuses if 'deps' in s)),
    }
    write_json(os.path.join(run_dir, 'report.json'), summary)
    print(json.dumps(summary, indent=1))
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest='command', required=True)
    s = sub.add_parser('select')
    s.add_argument('--info', default=INFO_CSV)
    s.add_argument('--out', default=os.path.join(MODULE, 'tmp', 'gen', 'maven-repos.csv'))
    s.add_argument('-j', '--jobs', type=int, default=8)
    r = sub.add_parser('run')
    r.add_argument('run_dir')
    r.add_argument('--jar', required=True)
    r.add_argument('--config', default=CONFIG)
    r.add_argument('--repos', default=os.path.join(MODULE, 'tmp', 'gen', 'maven-repos.csv'))
    r.add_argument('-j', '--jobs', type=int, default=9)
    r.add_argument('--xmx', default='12g')
    r.add_argument('--oracles-timeout', type=int, default=2 * 3600)
    r.add_argument('--tokens-timeout', type=int, default=12 * 3600)
    r.add_argument('--min-free-disk-gb', type=int, default=50)
    r.add_argument('--deps-cache', default=None)
    r.add_argument('--retry-failed', action='store_true')
    r.add_argument('--only', default=None)
    r.add_argument('--limit', type=int, default=None)
    r.add_argument('--keep-repos', action='store_true')
    p = sub.add_parser('report')
    p.add_argument('run_dir')
    args = parser.parse_args()
    if args.command == 'select':
        os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
        select(args.info, args.out, args.jobs)
    elif args.command == 'run':
        repos = read_repos(args.repos)
        if args.only:
            only = set(args.only.split(','))
            repos = [r for r in repos if r['id'] in only]
        if args.limit:
            repos = repos[:args.limit]
        sys.exit(Runner(args).run(repos))
    else:
        report(args.run_dir)


if __name__ == '__main__':
    main()
