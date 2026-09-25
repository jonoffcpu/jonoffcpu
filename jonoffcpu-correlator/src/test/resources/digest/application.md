# jonoffcpu analysis digest

- **Recorded:** 2026-09-24 23:44:33 UTC to 23:46:02 UTC (1 min 29 s)
- **Analysed:** 2026-09-24 23:45:09.903 UTC to 23:45:47.924 UTC (38.0 s), the selected window
- **Process:** PID 72 (host TGID 2976336), OpenJDK 64-Bit Server VM 25.0.4.1+8-LTS, async-profiler 4.5
- **System:** Wolfi, Linux 7.1.5-76070105-generic x86_64, glibc 2.44; Intel(R) Core(TM) i9-9980HK CPU @ 2.40GHz, 8 cores, 16 hardware threads

Blocked with an application frame: 3.000 s, 23.1 % of the 13.000 s blocked; 50.000 s waiting left out. Terms: [About this digest](#about-this-digest).

> Most of the blocked time has no application frame: see [Blocked without an application frame, by pool](#blocked-without-an-application-frame-by-pool).

## Blocked, by the application method that waited

Each row is the last application method before the blocking call and what it blocked on.

| # | Boundary | Blocker | s | Share | Intervals | Reason | Caller |
|---:|---|---|---:|---:|---:|---|---|
| 1 | `x.Svc.work` | `java.util.concurrent.locks.ReentrantLock.lock` | 2.000 | 66.7 % | 2 | blocked | `x.Svc.lambda$go$0` |
| 2 | `x.Map.get` | `java.util.concurrent.locks.StampedLock.readLock` | 1.000 | 33.3 % | 1 | blocked | `x.Svc.handle` |

## Blocked, by application root

Each row is the first application frame of a stack, where a thread entered the application.

| # | Root | s | Share | Intervals | Reason | Heaviest stack |
|---:|---|---:|---:|---:|---|---|
| 1 | `x.Svc.lambda$go$0` | 2.000 | 66.7 % | 2 | blocked | `x.Svc.lambda$go$0;x.Svc.work;j.u.c.l.ReentrantLock.lock` |
| 2 | `x.Svc.handle` | 1.000 | 33.3 % | 1 | blocked | `x.Svc.handle;x.Map.get;j.u.c.l.StampedLock.readLock` |

## Blocked, by application method

Each row is a chain of application methods always found in the same stacks, with the time of every stack it is in.

| # | Application methods | s | Share | Self s | Stacks | Intervals |
|---:|---|---:|---:|---:|---:|---:|
| 1 | `x.Svc.lambda$go$0 → x.Svc.work` | 2.000 | 66.7 % | 2.000 | 1 | 2 |
| 2 | `x.Svc.handle → x.Map.get` | 1.000 | 33.3 % | 1.000 | 1 | 1 |

## Where the time went

| Slice | Entries | Intervals | s | Share of blocked | Share of all selected |
|---|---:|---:|---:|---:|---:|
| Blocked | 3 | 6 | 13.000 | 100.0 % | 20.6 % |
| Blocked with an application frame | 2 | 3 | 3.000 | 23.1 % | 4.8 % |
| Blocked without an application frame | 1 | 3 | 10.000 | 76.9 % | 15.9 % |
| Waiting, left out | 1 | 7 | 50.000 |  | 79.4 % |
| All selected | 4 | 13 | 63.000 |  | 100.0 % |
| Over-exclusion check: waiting entries with a lock-acquire frame | 0 | 0 | 0.000 |  | 0.0 % |

## Blocked without an application frame, by pool

Each row is a thread pool whose blocked time has no application frame.

| # | Pool | s | Share | Intervals | Reason |
|---:|---|---:|---:|---:|---|
| 1 | `web-#` | 10.000 | 76.9 % | 3 | blocked |

## Capture

- Sampling: `{"reasons":["OFF_CPU_REASON_BLOCKED"],"none":{}}`
- Source rows 20, matched 13, outside the selected JFR window 0, orphan JFR 0, invalid 0/0

## About this digest

**Terms**

- **Blocked**: a thread off the CPU while it had work to do: on a lock, a monitor, I/O, a safepoint.
- **Waiting**: a thread off the CPU until work arrives, such as an event loop or an executor worker waiting for a task; recognized by the waiting patterns and left out of the tables.
- **Application frame**: a frame matching the application patterns:
  - `^x\.`
- **Application root**: the first application frame of a stack once dispatch frames are hidden, where a thread entered the application.
- **Application method that waited**: the last application frame before the blocking call.
- **Self time** (application methods table): the time of the stacks whose application method that waited is in the row.
- **Observed seconds**: the tables weigh each interval by its observed off-CPU time; the population estimate is unavailable.

**Notes**

- Waiting intervals, threads waiting for work, are left out of every table: a frame of their stack matched one of the waiting patterns:
  - `preset:jvm-waiting`
- A nonzero over-exclusion check means waiting patterns hid waits on a lock or monitor.
- The application method table is inclusive: a method has the time of every stack it is in, so its shares add up to more than 100 %.
- Native symbolization: on musl every native frame is /lib/ld-musl-<arch>.so.1, so the JVM's own threads waiting for work cannot be recognized and count as blocked.
- Session 72157cd7-7952-4b1b-913c-152c1d31fdd7; JFR broker.jfr, SHA-256 7bef8e83.
- Written by jonoffcpu-correlator test.

## How to reproduce

**By the application method that waited** (also the blocked time without an application frame, by pool):

```bash
java -jar jonoffcpu-correlator.jar top \
  --profile run.pb \
  --app '^x\.' \
  --waiting-from preset:jvm-waiting \
  --canonical-names \
  --hide-from preset:jvm-dispatch \
  --root-at '^x\.' \
  --root-at-unmatched hide \
  --collapse-leaf-from preset:jvm-wait-machinery \
  --by boundary \
  --limit 20 \
  --format md
```

**By application root:**

```bash
java -jar jonoffcpu-correlator.jar top \
  --profile run.pb \
  --app '^x\.' \
  --waiting-from preset:jvm-waiting \
  --canonical-names \
  --hide-from preset:jvm-dispatch \
  --root-at '^x\.' \
  --root-at-unmatched hide \
  --collapse-leaf-from preset:jvm-wait-machinery \
  --by root \
  --limit 20 \
  --format md
```

**By application method:**

```bash
java -jar jonoffcpu-correlator.jar top \
  --profile run.pb \
  --app '^x\.' \
  --waiting-from preset:jvm-waiting \
  --canonical-names \
  --hide-from preset:jvm-dispatch \
  --root-at '^x\.' \
  --root-at-unmatched hide \
  --collapse-leaf-from preset:jvm-wait-machinery \
  --by app-method \
  --limit 20 \
  --format md
```

**Application-rooted flame graph input:**

```bash
java -jar jonoffcpu-correlator.jar stacks \
  --profile run.pb \
  --exclude-from preset:jvm-waiting \
  --canonical-names \
  --hide-from preset:jvm-dispatch \
  --root-at '^x\.' \
  --root-at-unmatched hide \
  --collapse-leaf-from preset:jvm-wait-machinery \
  --output blocked-app.collapsed
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

