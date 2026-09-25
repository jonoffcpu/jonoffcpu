# jonoffcpu analysis digest

Profile `run.pb`, run `digest`. Times are observed seconds of off-CPU time; the population estimate is unavailable, so under proportional or uniform sampling short waits are under-weighted.

## Capture

- Sampling: `{"reasons":["OFF_CPU_REASON_BLOCKED"],"uniform":{"probability":"0.01","probabilityThreshold":"42949673"}}`
- Source rows 20, matched 13, outside the selected JFR window 0, orphan JFR 0, invalid 0/0

## Where the time went

| Slice | Entries | Intervals | s |
|---|---:|---:|---:|
| Busy | 3 | 6 | 13.000 |
| Idle, left out below | 1 | 7 | 50.000 |
| All selected | 4 | 13 | 63.000 |
| Over-exclusion check: idle entries with a lock-acquire frame | 0 | 0 | 0.000 |

The tables and stacks below cover busy time only. Idle intervals, waits for work, are left out: a frame of their stack matched one of the idle patterns preset:jvm-idle. `java -jar jonoffcpu-correlator.jar top --profile run.pb --idle-from preset:jvm-idle --canonical-names --by self --collapse-leaf-from preset:jvm-wait-machinery --limit 20 --format md` lists them. A nonzero over-exclusion check means idle patterns hid waits on a lock or monitor.

## Busy, by leaf

No application pattern was given, so each row is the stack's leaf after collapsing the wait machinery; pass --app to rank by application boundary.

| # | Key | s | Share | Intervals | Reason |
|---:|---|---:|---:|---:|---|
| 1 | `jdk.internal.misc.Unsafe.park` | 10.000 | 76.9 % | 3 | blocked |
| 2 | `java.util.concurrent.locks.ReentrantLock.lock` | 2.000 | 15.4 % | 2 | blocked |
| 3 | `java.util.concurrent.locks.StampedLock.readLock` | 1.000 | 7.7 % | 1 | blocked |

## Busy, by pool

| # | Pool | s | Share | Intervals | Reason |
|---:|---|---:|---:|---:|---|
| 1 | `web-#` | 10.000 | 76.9 % | 3 | blocked |
| 2 | `svc-#` | 3.000 | 23.1 % | 3 | blocked |

## Heaviest busy stacks

The busy slice, transformed, renders as 3 lines at a mean depth of 2.4 frames. Reproduce: `java -jar jonoffcpu-correlator.jar stacks --profile run.pb --exclude-from preset:jvm-idle --trim-root-from preset:jvm-infra --collapse-leaf-from preset:jvm-wait-machinery --canonical-names --package-names drop --output busy.collapsed`

| s | Stack |
|---:|---|
| 10.000 | `ReservedThreadExecutor$ReservedThread.waitForTask;Unsafe.park` |
| 2.000 | `Svc$$Lambda.run;Svc.lambda$go$0;Svc.work;ReentrantLock.lock` |
| 1.000 | `Svc.handle;Map.get;StampedLock.readLock` |

## How to reproduce

- Busy tables, and the idle waits left out: `java -jar jonoffcpu-correlator.jar top --profile run.pb --idle-from preset:jvm-idle --canonical-names --by self --collapse-leaf-from preset:jvm-wait-machinery --limit 20 --format md`
- Pool table: `java -jar jonoffcpu-correlator.jar top --profile run.pb --idle-from preset:jvm-idle --canonical-names --by pool --collapse-leaf-from preset:jvm-wait-machinery --limit 20 --format md`
- Heaviest stacks: `java -jar jonoffcpu-correlator.jar stacks --profile run.pb --exclude-from preset:jvm-idle --trim-root-from preset:jvm-infra --collapse-leaf-from preset:jvm-wait-machinery --canonical-names --package-names drop --output busy.collapsed`
- Any other question: `java -jar jonoffcpu-correlator.jar export --profile run.pb --format jsonl --output entries.jsonl`
