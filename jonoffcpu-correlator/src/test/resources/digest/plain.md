# jonoffcpu analysis digest

- **Recorded:** 2026-09-24 23:44:33 UTC to 23:46:02 UTC (1 min 29 s)
- **Analysed:** 2026-09-24 23:45:09.903 UTC to 23:45:47.924 UTC (38.0 s), the selected window
- **Process:** PID 72 (host TGID 2976336), OpenJDK 64-Bit Server VM 25.0.4.1+8-LTS, async-profiler 4.5
- **System:** Wolfi, Linux 7.1.5-76070105-generic x86_64, glibc 2.44; Intel(R) Core(TM) i9-9980HK CPU @ 2.40GHz, 8 cores, 16 hardware threads

Blocked: 13.000 s, 20.6 % of the 63.000 s selected; 50.000 s waiting left out. Terms: [About this digest](#about-this-digest).

## Blocked, by leaf

Each row is the stack's leaf after collapsing the wait machinery.

| # | Key | s | Share | Intervals | Reason |
|---:|---|---:|---:|---:|---|
| 1 | `jdk.internal.misc.Unsafe.park` | 10.000 | 76.9 % | 3 | blocked |
| 2 | `java.util.concurrent.locks.ReentrantLock.lock` | 2.000 | 15.4 % | 2 | blocked |
| 3 | `java.util.concurrent.locks.StampedLock.readLock` | 1.000 | 7.7 % | 1 | blocked |

## Blocked, by pool

Each row is a thread pool: the thread name with every run of digits as #.

| # | Pool | s | Share | Intervals | Reason |
|---:|---|---:|---:|---:|---|
| 1 | `web-#` | 10.000 | 76.9 % | 3 | blocked |
| 2 | `svc-#` | 3.000 | 23.1 % | 3 | blocked |

## Where the time went

| Slice | Entries | Intervals | s | Share of blocked | Share of all selected |
|---|---:|---:|---:|---:|---:|
| Blocked | 3 | 6 | 13.000 | 100.0 % | 20.6 % |
| Waiting, left out | 1 | 7 | 50.000 |  | 79.4 % |
| All selected | 4 | 13 | 63.000 |  | 100.0 % |
| Over-exclusion check: waiting entries with a lock-acquire frame | 0 | 0 | 0.000 |  | 0.0 % |

## Capture

- Sampling: `{"reasons":["OFF_CPU_REASON_BLOCKED"],"none":{}}`
- Source rows 20, matched 13, outside the selected JFR window 0, orphan JFR 0, invalid 0/0

## About this digest

**Terms**

- **Blocked**: a thread off the CPU while it had work to do: on a lock, a monitor, I/O, a safepoint.
- **Waiting**: a thread off the CPU until work arrives, such as an event loop or an executor worker waiting for a task; recognized by the waiting patterns and left out of the tables.
- **Observed seconds**: the tables weigh each interval by its observed off-CPU time; the population estimate is unavailable.

**Notes**

- Waiting intervals, threads waiting for work, are left out of every table: a frame of their stack matched one of the waiting patterns:
  - `preset:jvm-waiting`
- A nonzero over-exclusion check means waiting patterns hid waits on a lock or monitor.
- Native symbolization: on musl every native frame is /lib/ld-musl-<arch>.so.1, so the JVM's own threads waiting for work cannot be recognized and count as blocked.
- Session 72157cd7-7952-4b1b-913c-152c1d31fdd7; JFR broker.jfr, SHA-256 7bef8e83.
- Written by jonoffcpu-correlator test.

## How to reproduce

**By leaf:**

```bash
java -jar jonoffcpu-correlator.jar top \
  --profile run.pb \
  --waiting-from preset:jvm-waiting \
  --canonical-names \
  --by self \
  --collapse-leaf-from preset:jvm-wait-machinery \
  --limit 20 \
  --format md
```

**By pool:**

```bash
java -jar jonoffcpu-correlator.jar top \
  --profile run.pb \
  --waiting-from preset:jvm-waiting \
  --canonical-names \
  --by pool \
  --collapse-leaf-from preset:jvm-wait-machinery \
  --limit 20 \
  --format md
```

**Flame graph input:**

```bash
java -jar jonoffcpu-correlator.jar stacks \
  --profile run.pb \
  --exclude-from preset:jvm-waiting \
  --trim-root-from preset:jvm-infra \
  --collapse-leaf-from preset:jvm-wait-machinery \
  --canonical-names \
  --package-names drop \
  --output blocked.collapsed
```

**Waiting left out** (its waiting table, by pool):

```bash
java -jar jonoffcpu-correlator.jar top \
  --profile run.pb \
  --waiting-from preset:jvm-waiting \
  --canonical-names \
  --by pool \
  --limit 20 \
  --format md
```

**Any other question:**

```bash
java -jar jonoffcpu-correlator.jar export --profile run.pb --format jsonl --output entries.jsonl
```

