#!/usr/bin/env python3
"""RAM watchdog for the runs of the pipeline (ab.py run, corpus.py, dev_tokens.py).

Usage: python3 tools/ab/ram_watchdog.py [log file]   (start it before the runs; it runs until killed)

Measures the memory in use against the limit: in a container with a cgroup v2 limit, the working set
(memory.current - inactive_file, what the kernel cannot simply drop) against memory.max (on the 2026-11 server 147 GiB,
NOT the host RAM that `free` shows); otherwise MemTotal - MemAvailable against MemTotal (/proc/meminfo).
On 2026-11-05 three A/B runs started together (27 JVMs) exhausted the container and every run was lost.
- >= KILL_PCT and still growing over the last WINDOW seconds -> kill all experiments (ab.py first, so that it does not
  start the next project, then the pipeline JVMs).
- >= HARD_PCT -> kill regardless of the trend.
Logs every minute, and every sample above WARN_PCT.
"""
import os, signal, sys, time, collections

CG = '/sys/fs/cgroup'
KILL_PCT, HARD_PCT, WARN_PCT = 85.0, 90.0, 75.0
PERIOD, WINDOW = 2, 10
LOG = sys.argv[1] if len(sys.argv) > 1 else 'ram_watchdog.log'


def read(name):
    return open(os.path.join(CG, name)).read()


def meminfo():
    return {line.split(':')[0]: int(line.split()[1]) * 1024 for line in open('/proc/meminfo')}


def cgroup_limit():
    """The cgroup v2 memory limit in bytes, or None (no cgroup v2, or no limit)."""
    try:
        value = read('memory.max').strip()
    except OSError:
        return None
    return None if value == 'max' else int(value)


def used_pct(limit):
    if cgroup_limit() is not None:
        stat = dict(line.split() for line in read('memory.stat').splitlines())
        return 100.0 * (int(read('memory.current')) - int(stat['inactive_file'])) / limit
    info = meminfo()
    return 100.0 * (info['MemTotal'] - info['MemAvailable']) / limit


def targets():
    """(runner processes, pipeline JVMs): the runners are ab.py, corpus.py, dev_tokens.py; the JVMs run a stage."""
    ab, jvms = [], []
    for pid in filter(str.isdigit, os.listdir('/proc')):
        try:
            cmd = open(f'/proc/{pid}/cmdline', 'rb').read().split(b'\0')
        except OSError:
            continue
        text = b' '.join(cmd).decode(errors='replace')
        if os.path.basename(cmd[0]).startswith(b'python') and any(t in text for t in ('tools/ab/ab.py', 'corpus.py', 'dev_tokens.py')):
            ab.append(int(pid))
        elif cmd and cmd[0].endswith(b'/java') and b'-jar' in cmd:
            # The pipeline: java [options] -jar <jar> oracles|tokens <project> ...
            stage = cmd.index(b'-jar') + 2
            if stage < len(cmd) and cmd[stage] in (b'oracles', b'tokens'):
                jvms.append(int(pid))
    return ab, jvms


def log(message):
    with open(LOG, 'a') as f:
        f.write(time.strftime('%Y-%m-%d %H:%M:%S ') + message + '\n')


def kill_all(reason):
    ab, jvms = targets()
    log(f'KILLING ({reason}): ab.py {ab}, jvms {jvms}')
    for pid in ab + jvms:
        try:
            os.kill(pid, signal.SIGKILL)
        except ProcessLookupError:
            pass


def main():
    limit = cgroup_limit() or meminfo()['MemTotal']
    history = collections.deque(maxlen=WINDOW // PERIOD + 1)
    log(f'start: limit {limit / 2**30:.1f} GiB, kill at {KILL_PCT}% if growing, hard kill at {HARD_PCT}%')
    last_log = 0
    while True:
        pct = used_pct(limit)
        history.append(pct)
        growing = len(history) == history.maxlen and pct > history[0]
        now = time.time()
        if pct >= HARD_PCT or (pct >= KILL_PCT and growing):
            kill_all(f'{pct:.1f}% used, growing={growing}')
            history.clear()
            time.sleep(10)
            continue
        if pct >= WARN_PCT or now - last_log >= 60:
            ab, jvms = targets()
            log(f'{pct:.1f}% used ({len(ab)} ab.py, {len(jvms)} jvms)')
            last_log = now
        time.sleep(PERIOD)


if __name__ == '__main__':
    main()
