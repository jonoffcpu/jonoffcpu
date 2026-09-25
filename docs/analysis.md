# Analyzing a capture

Everything after the capture is offline and runs wherever Java 21 does: the
correlator joins the correlation stream with the JFR, writes the report, the
flame-graph input, the stack profile and the digest, and every later view is
a sub-second projection of that profile. This page walks from correlation to
"what to optimize". `java -jar jonoffcpu-correlator.jar help <command>` prints
every option of `correlate`, `stacks`, `top`, `summarize`, `merge`, `export`
and `dump` with its default; [OFFLINE.md](../jonoffcpu-correlator/OFFLINE.md)
holds the contracts behind them.

- [Correlate](#correlate)
- [Render the flame graph](#render-the-flame-graph)
- [Other views of the same recording](#other-views-of-the-same-recording)
- [Slice and filter with the stack profile](#slice-and-filter-with-the-stack-profile)
- [Transform stacks](#transform-stacks)
- [Merge and export profiles](#merge-and-export-profiles)
- [Find what to optimize](#find-what-to-optimize)
- [Compare runs](#compare-runs)
- [Example: Apache Pulsar](#example-apache-pulsar)
- [Correlator options](#correlator-options)

## Correlate

```sh
java -jar jonoffcpu-correlator.jar \
  --source /tmp/jonoffcpu-capture.pb \
  --jfr /tmp/jonoffcpu-capture.jfr \
  --output /tmp/jonoffcpu-analysis \
  --estimate-population true \
  --app '^com\.example\.'
```

`--app` names your application's frames, and roots the digest at them: its
tables name the application method that waited and what it blocked on, where
each thread entered your code, and the application methods the time passed
through, with executors and lambda bridges hidden first (`--hide-from`, by
default `preset:*`, the bundled `preset:jvm-dispatch`) and the blocked time
that has no application frame counted apart, by thread pool. Without it the
digest ranks the leaves of the stacks, which name the wait mechanism rather
than the code that waited.

The digest calls the off-CPU time it ranks *blocked*: a thread off the CPU
while it had work to do, on a lock, a monitor, I/O or a safepoint. A thread off
the CPU until work arrives, such as an event loop or a pool worker waiting for a
task, is *waiting*; `--waiting-from` (default `preset:*`, the bundled
`preset:jvm-waiting`) recognizes it, and the digest counts it in *Where the
time went* and leaves it out of its tables. The digest opens with when the
capture was recorded, the window analysed, and the JVM, OS and CPU from the
JFR's own events. The target's command line, system properties and
environment variables can hold secrets, so the report and the digest carry
them only with `--process-details true`.

`--estimate-population true` keeps the inverse-probability estimates valid,
which comparisons between runs need when sampling is proportional or uniform:
on an Apache Pulsar broker the blocked waits add up to 49.0 s observed but
101.4 s estimated, because proportional admission keeps short waits with a
lower probability.

The output directory then holds `jonoffcpu-report.json`,
`jonoffcpu-offcpu-stacks.collapsed`, `jonoffcpu-offcpu-profile.pb`, the digest
`jonoffcpu-summary.md`, the audit file `jonoffcpu-matches.jsonl`, and
`jonoffcpu-complete.json` as the last file written;
[Files jonoffcpu writes](how-it-works.md#files-jonoffcpu-writes) describes each
one.

## Render the flame graph

```sh
java -jar jfr-converter.jar --title "Off-CPU time" --units µs \
  /tmp/jonoffcpu-analysis/jonoffcpu-offcpu-stacks.collapsed \
  /tmp/jonoffcpu-analysis/offcpu.html
```

Open `offcpu.html` in a browser. Frame widths are proportional to the total
off-CPU time observed under that Java stack. The collapsed weights are
microseconds of off-CPU time, and `--units µs` makes the flame graph say so
instead of counting "samples"; that option is a fork addition, so use the
provided `jfr-converter.jar` rather than a stock `jfrconv`. Add `--reverse` to
see which blocking calls dominate regardless of caller. To keep or drop stacks
by frame, render the slice from the stack profile with `--include`/`--exclude`
([below](#slice-and-filter-with-the-stack-profile)), which also matches frames
the graph does not show. Any tool that reads the collapsed-stack format, such
as [`flamegraph.pl`](https://github.com/brendangregg/FlameGraph) with
`--countname=µs`, works on the same file.

## Other views of the same recording

The agent's JFR also holds whatever `asyncProfilerOptions` recorded, and the
same converter renders it. Render a view only for events that were
configured: `jfrsync` alone does not make an allocation or lock view
meaningful.

| `asyncProfilerOptions` contains | View | Converter |
| --- | --- | --- |
| `event=cpu` (or `itimer`, `ctimer`) | CPU | `--cpu` |
| `event=wall` or `wall=` | wall clock | `--wall` |
| `alloc=` | allocation | `--alloc --total` |
| `lock=` | Java lock contention | `--lock --total` |

```sh
java -jar jfr-converter.jar --cpu -o collapsed /tmp/jonoffcpu-capture.jfr cpu.collapsed
java -jar jonoffcpu-correlator.jar stacks --collapsed-input cpu.collapsed \
  --trim-root-from preset:jvm-infra --output cpu-trimmed.collapsed
java -jar jfr-converter.jar cpu-trimmed.collapsed cpu.html
```

Add `--threads` for a per-thread split and `-o collapsed` for
machine-readable output. The converter writes class names as
`org/example/Class` with `_[j]`-style markers; `stacks --collapsed-input` and
`top --collapsed-input` normalize them, so the transforms and the ranked tables
below apply to these views too.

## Slice and filter with the stack profile

`jonoffcpu-offcpu-profile.pb` holds every distinct stack once with its
counters, so any other collapsed file is a sub-second projection of it rather
than a new correlation (913 KB and 0.3 s for a capture that takes a minute to
correlate):

```sh
# Time spent waiting for a CPU, whatever the Java code was doing
java -jar jonoffcpu-correlator.jar stacks \
  --profile /tmp/jonoffcpu-analysis/jonoffcpu-offcpu-profile.pb \
  --reason runnable,preempted --output cpu-wait.collapsed

# Java stacks continued by the kernel stack, so the wait mechanism is visible
java -jar jonoffcpu-correlator.jar stacks \
  --profile /tmp/jonoffcpu-analysis/jonoffcpu-offcpu-profile.pb \
  --stack java+kernel --summary blocked.json --output blocked.collapsed

# ... without waits for work on a socket or an epoll loop
java -jar jonoffcpu-correlator.jar stacks \
  --profile /tmp/jonoffcpu-analysis/jonoffcpu-offcpu-profile.pb \
  --stack java+kernel --exclude '(ep_poll|sock_recvmsg|tcp_recvmsg)_\[k\]' \
  --summary blocked.json --output blocked.collapsed

# Java stacks only, without the Netty event loops' epoll waits for work
java -jar jonoffcpu-correlator.jar stacks \
  --profile /tmp/jonoffcpu-analysis/jonoffcpu-offcpu-profile.pb \
  --exclude 'io\.netty\.channel\.epoll\.Native\.epollWait0' --output app.collapsed

# A kept list of waits for work, one pattern per line
java -jar jonoffcpu-correlator.jar stacks \
  --profile /tmp/jonoffcpu-analysis/jonoffcpu-offcpu-profile.pb \
  --exclude-from waiting.txt --output blocked-java.collapsed

# Only the time threads spent waiting for a CPU after they were woken
java -jar jonoffcpu-correlator.jar stacks \
  --profile /tmp/jonoffcpu-analysis/jonoffcpu-offcpu-profile.pb \
  --time runqueue --output runqueue.collapsed

# Every interval, each stack ending in a [sleeping] or [runqueue] frame
java -jar jonoffcpu-correlator.jar stacks \
  --profile /tmp/jonoffcpu-analysis/jonoffcpu-offcpu-profile.pb \
  --time split --output split.collapsed

# Shorter frames: io.netty.channel.epoll.Native.epollWait0 becomes i.n.c.e.Native.epollWait0
java -jar jonoffcpu-correlator.jar stacks \
  --profile /tmp/jonoffcpu-analysis/jonoffcpu-offcpu-profile.pb \
  --package-names abbreviate --output short.collapsed
```

`--reason` takes `all` (the default) or a comma-separated list of `blocked`,
`runnable` and `preempted`; a slice with more than one reason starts each line
with its `[offcpu: <reason>]` frame unless `--reason-frame never` is given.
`--stack` is `java` (the default), `kernel`, `user`, `java+kernel` or
`java+user+kernel`; native frames are shown without their `+0x` offsets, kernel
frames carry the `_[k]` suffix, and the profiler's own tracing frames at the
leaf of a kernel stack are left out. `--weights estimated` renders the
inverse-probability estimate instead of the observed durations, when the
capture's population estimate is available. `--time` picks which part of each
interval's time is weighed: `total` (the default), `sleeping`, `runqueue`, or
`split`, which keeps the whole time and ends each line in a `[sleeping]`,
`[runqueue]` or `[unsplit]` frame so one flame graph shows both waits; every
mode but `total` needs a profile whose capture recorded the split.
`--package-names abbreviate` shortens each Java frame's package to its
initials (`i.n.c.e.Native.epollWait0`) and `--package-names drop` removes it
(`Native.epollWait0`); `full` is the default. Only Java frames change: the
native frames async-profiler records in the Java stack (HotSpot, JNI
libraries, libc, runtime stubs) keep their library and symbol as written.

| Frame | `abbreviate` | `drop` |
| --- | --- | --- |
| `io.netty.channel.epoll.Native.epollWait0` | `i.n.c.e.Native.epollWait0` | `Native.epollWait0` |
| `org.example.Cursor$$Lambda.0x0000000081a16ff8.run` | `o.e.Cursor$$Lambda.0x0000000081a16ff8.run` | `Cursor$$Lambda.0x0000000081a16ff8.run` |
| `libjvm.so.Unsafe_Park` | unchanged | unchanged |

Stacks that become identical merge into one line, and
`--include`/`--exclude` still match the full names. Rendered with its defaults, a
profile reproduces `jonoffcpu-offcpu-stacks.collapsed` byte for byte.

`--exclude REGEX` drops every interval with a frame matching the pattern, and
`--include REGEX` keeps only intervals with one; both can be repeated (any
pattern matches), and an exclusion wins. Filtering happens on the profile's
entries, before they are merged into collapsed lines, so it removes whole
intervals and matches every stack the profile holds, whichever `--stack`
renders: `--stack java --exclude 'ep_poll_\[k\]'` drops the epoll waits from a
Java-only graph. Patterns are searched for in each frame as it would be
rendered (Java names, offset-free native symbols, kernel symbols with `_[k]`,
and `[kernel stack unavailable]`/`[user stack unavailable]` for a missing
stack); anchor them with `^…$` for an exact frame. The `--summary` file
records the slice's interval count and total nanoseconds, the patterns, the
stacks they were matched against (`filterScope`), and under `filtered` what
the filters removed, so the kept and removed time add up to the unfiltered
slice. The converter's own `-I`/`-X` still work on a rendered file, but see
only the frames in its lines and cannot account for what they drop.

`--include-from FILE` and `--exclude-from FILE` read patterns from a file, one
per line, and add them to any given with `--include`/`--exclude`; both repeat.
Blank lines and lines starting with `#` are skipped (write `\#` for a pattern
that starts with `#`), and every other line is taken verbatim, spaces included.
A file with no patterns, or with an invalid one, is refused, naming the line.

## Transform stacks

Filters keep or drop whole intervals; transforms change the frames of the
intervals kept, and never a total, except `--root-at-unmatched hide`, which
moves the unmatched intervals to a total of their own:

```sh
# Blocked waits only, each stack from your first frame to the lock or monitor it waited on
java -jar jonoffcpu-correlator.jar stacks \
  --profile /tmp/jonoffcpu-analysis/jonoffcpu-offcpu-profile.pb \
  --exclude-from preset:jvm-waiting --root-at '^com\.example\.' \
  --collapse-leaf-from preset:jvm-wait-machinery --output blocked-app.collapsed

# The application-rooted flame graph: executors and lambda bridges hidden, so each stack
# starts at the work it ran, and blocked time without an application frame left out and reported
java -jar jonoffcpu-correlator.jar stacks \
  --profile /tmp/jonoffcpu-analysis/jonoffcpu-offcpu-profile.pb \
  --exclude-from preset:jvm-waiting --canonical-names --hide-from preset:jvm-dispatch \
  --root-at '^com\.example\.' --root-at-unmatched hide \
  --collapse-leaf-from preset:jvm-wait-machinery --output blocked-app-root.collapsed
```

- `--trim-root` strips the longest root-side run of matching frames (thread,
  executor and event-loop entry points).
- `--root-at` starts each stack at its root-most matching frame, so the same
  call reached through different threads or executors merges. Stacks without
  a match go under `[no application frame]`, are kept whole with
  `--root-at-unmatched keep`, or are left out with `hide` and reported in the
  summary's `rootAtUnmatchedHidden`.
- `--leaf-at` cuts below the leaf-most match.
- `--collapse-leaf` replaces the lock, park and monitor internals under a wait
  with the frame that entered them, or with a category such as `[lock]` with
  `--collapse-leaf-label category`.
- `--hide` removes matching frames anywhere.
- `--canonical-names` removes generated-class addresses, so two runs compare.
- `--thread-frame name|pool` starts each line with the thread or its pool.

Each has a `-from FILE` form, and every `-from` option, the filters' included,
also takes a bundled preset: `preset:jvm-infra` (thread and executor entry
points), `preset:jvm-wait-machinery` (lock, park and monitor internals),
`preset:jvm-waiting` (waits for work) or `preset:jvm-dispatch` (the lambda
bridges and executor adapters that only forward to a task);
`stacks --list-presets` prints them. `preset:*` stands for every bundled
preset meant for the option it is given to, as the preset's `# options:` line
says: `--hide-from 'preset:*' --hide-from my-hide.txt` keeps jonoffcpu's
patterns and adds yours, and picks up a preset a later release adds. An option
without presets (`--include-from`, `--root-at-from`, `--leaf-at-from`,
`--app-from`) refuses it. Quote it, so the shell does not glob it.

Filters always see the untransformed stack. On an Apache Pulsar broker's
blocked waits, `--root-at` with `--collapse-leaf` turns 164 lines at a mean
depth of 23 frames into 78 lines of about 4. `--collapsed-input FILE` applies
the same filters and transforms to any collapsed file, such as the converter's
CPU or allocation view. The order and every rule are in OFFLINE.md's
[Transforms](../jonoffcpu-correlator/OFFLINE.md#transforms).

## Merge and export profiles

```sh
java -jar jonoffcpu-correlator.jar merge --profiles run1.pb,run2.pb --output runs.pb
java -jar jonoffcpu-correlator.jar export --profile runs.pb --format csv --output entries.csv
duckdb -c "SELECT reason, java_stack, sum(observed_nanos) / 1e9 AS seconds
           FROM read_csv('entries.csv') GROUP BY ALL ORDER BY seconds DESC LIMIT 20"
```

A merged profile sums durations across its inputs: it shows what dominates
across the runs, not what fraction of any one run's time it took. Thinned
profiles cannot be merged, because each is rescaled by its own probability.
`export --format jsonl`, with the stacks as arrays, is the format for SQL and
AI agents; see [Analyzing with SQL](automation.md#analyzing-with-sql).

## Find what to optimize

A flame graph of every off-CPU interval is dominated by threads waiting for
work: event loops in `epoll_wait`, pool workers waiting for a task, the JVM's
own service threads. In an Apache Pulsar broker that is over 99 % of the time.
`top` ranks the same profile instead of drawing it: each interval is waiting
when a frame matches `--waiting`/`--waiting-from` and blocked otherwise, and
blocked time is attributed to the deepest frame of your code (`--app`) and the
lock, monitor or park below it:

```sh
java -jar jonoffcpu-correlator.jar top \
  --profile /tmp/jonoffcpu-analysis/jonoffcpu-offcpu-profile.pb \
  --app '^com\.example\.' --waiting-from 'preset:*' --format md
```

Each row is the deepest frame of your code in the stack (its *boundary*) with
the *blocker* below it — a monitor, a `ReentrantLock`, a park — and the time
and intervals spent there. Waits for work are listed in their own table, so you
can check that nothing important was classified as waiting; the over-exclusion
line counts waiting intervals that still waited on a lock. Add `--waiting` or
`--waiting-from` lines for your own queues' waits for work; the
[Pulsar example](#example-apache-pulsar) shows what the table looks like.
`summarize` writes the same tables into a digest, as correlation does by
default.

Then look at one row in context with a trimmed flame graph:

```sh
java -jar jonoffcpu-correlator.jar stacks \
  --profile /tmp/jonoffcpu-analysis/jonoffcpu-offcpu-profile.pb \
  --exclude-from preset:jvm-waiting \
  --root-at '^com\.example\.' --collapse-leaf-from preset:jvm-wait-machinery \
  --package-names drop --output blocked.collapsed
java -jar jfr-converter.jar --units µs blocked.collapsed blocked.html
```

`--root-at` and `--collapse-leaf` are the [transforms](#transform-stacks)
above: each stack starts at your first frame and ends at the blocker itself.
Finally, `--time split` shows whether a row's time was spent asleep or waiting
for a CPU after the wakeup.

The flame graph's other end is ranked too. With `--hide-from
preset:jvm-dispatch --root-at '^com\.example\.' --root-at-unmatched hide`,
`top --by root` lists where threads entered your code, and
`top --by app-method --app '^com\.example\.'` ranks every application method
across the stacks it is in, methods that are always called together grouped
into one call chain, with the time of the stacks that end in them as their
self time. With hiding, their shares and those of `--by boundary` are of the
blocked time that has an application frame.

## Compare runs

To compare two runs, give `top` the earlier profile as `--baseline` and each
run's work as units, for example millions of messages; it lists each
boundary's time per unit in both runs and warns when the runs are not
comparable:

```sh
java -jar jonoffcpu-correlator.jar top --profile new.pb --baseline old.pb \
  --units 5 --baseline-units 5 --weights estimated \
  --app '^com\.example\.' --waiting-from 'preset:*'
```

`--weights estimated` compares the population estimates, which need
`--estimate-population true` at correlation; see [Correlate](#correlate).

## Example: Apache Pulsar

jonoffcpu grew out of optimizing [Apache Pulsar](https://pulsar.apache.org/).
Its [performance scenarios](https://github.com/apache/pulsar/tree/master/tests/performance)
run a broker and its clients under a repeatable load and profile them. In an
IoT scenario (5 million messages from 500 producers to one topic) the broker
spent 8,519 s off-CPU across its threads, and after `preset:jvm-waiting` and
one BookKeeper queue line
(`--waiting '^org\.apache\.bookkeeper\.common\.collections\.[\w$]*BlockingQueue\.take(All)?$'`)
only 49.0 s of that was blocked. With `--app '^org\.apache\.'` the top of the
`top` table reads:

| Boundary | Blocker | s | Intervals |
| --- | --- | ---: | ---: |
| `…PersistentDispatcherMultipleConsumers.internalConsumerFlow` | `C2 Runtime complete_monitor_locking` | 11.982 | 4,245 |
| `…GrowableBatchedArrayBlockingQueue.offer` | `java.util.concurrent.locks.ReentrantLock.lock` | 4.946 | 1,565 |
| `…MessageDeduplication.isDuplicateNormal` | `C2 Runtime complete_monitor_locking` | 0.801 | 187 |

Two rows lead everything else: the dispatcher's `internalConsumerFlow`
waiting on a monitor, and the BookKeeper executor queue's `offer` waiting on a
`ReentrantLock`. The same tables on an Alpine (musl) image counted 2,019 s as
blocked instead of 49 s: on musl every native frame is
`/lib/ld-musl-x86_64.so.1`, so the JVM's own GC and compiler threads waiting
for work cannot be recognized, and a glibc image is needed for the blocked
total without an application frame to mean anything. The application rows
still compare, which is what `top --baseline` restricts itself to.

## Correlator options

The generated help is the reference. The most used correlation options:

| Option | Meaning |
| --- | --- |
| `--from`, `--to` | Select samples by JFR event time. Accepts ISO-8601 timestamps, epoch milliseconds, durations, or offsets from the recording start such as `30s` and `2m`. |
| `--max-handler-delay-ns` | Reject matches whose Java stack was captured more than this long after the interval ended. |
| `--from-ns`, `--to-ns` | Clip matched intervals to a window in the source monotonic clock. |
| `--estimate-population true` | Add a `populationEstimate` to the report: the total off-CPU time of every eligible interval, reweighted by each row's admission threshold. See [OFFLINE.md](../jonoffcpu-correlator/OFFLINE.md). |
| `--max-accounted-loss <f>` | Largest fraction of selected intervals that counted sequence contention may drop before the population estimate is refused (`accounted-loss-above-limit`). Below it the estimate is scaled for the loss and reports it in `accountedLoss`. Default `0.01`. |
| `--partial-jfr true` | Accept a JFR that another tool has cut. Source rows without a sample in the cut JFR are reported as expected omissions instead of loss. |
| `--partial true` | Inspect an interrupted capture. Writes `INCOMPLETE-jonoffcpu-*` files and a `jonoffcpu-partial.json` marker, exits with status 2, and never writes `jonoffcpu-complete.json`. |
| `--audit full\|matches\|none` | How much per-row audit output to write. Default `matches`: `jonoffcpu-matches.jsonl` but not `jonoffcpu-classified-records.jsonl`. |
| `--on-limit degrade\|fail\|truncate` | What to do when the retained-bytes budget is reached. Default `degrade`: drop audit outputs, thin and reweight, narrow the window — reporting each step. `fail` refuses at the limit. `truncate` skips thinning and narrows the window directly. |
| `--thinning <q>` | Keep each recorded interval with probability `q` and reweight by `1/q`. Deterministic in the cookie, so the result does not depend on order. Default: chosen automatically, and `1` whenever the input fits. |
| `--thinning-seed <n>` | Changes the deterministic draw `--thinning` uses. |
| `--collapsed-reason-frame auto\|always\|never` | Whether each line of `jonoffcpu-offcpu-stacks.collapsed` starts with its `[offcpu: <reason>]` frame. Default `auto`: only when the capture mixes reasons. |
| `--profile-output true\|false` | Whether to write `jonoffcpu-offcpu-profile.pb`. Default `true`. |
| `--summary-output true\|false` | Whether to write the digest, `jonoffcpu-summary.md` and `.json`. Default `true`. |
| `--waiting <regex>`, `--waiting-from <file>` | The waits for work that the digest leaves out of its tables and stacks: an interval with a frame matching one is waiting. Default `preset:*`, the bundled `preset:jvm-waiting`; add an application's own waits for work with a file of your own. The other outputs keep every interval. |
| `--app <regex>`, `--app-from <file>` | Your application's frames, for the digest: its tables are then rooted at the application, as the application-rooted flame graph is (see [Transform stacks](#transform-stacks)), and blocked time without an application frame is counted by pool instead. Recommended; without it the digest ranks stack leaves. |
| `--process-details true\|false` | Whether the report keeps, and the digest shows, the target's command line, system properties and environment variables from the JFR (`jdk.JVMInformation`, `jdk.InitialSystemProperty`, `jdk.InitialEnvironmentVariable`). They can hold secrets. Default `false`. |
| `--hide <regex>`, `--hide-from <file>` | With `--app`, the frames the digest removes before rooting each stack at the application, such as executors that only run a task. Default `preset:*`, the bundled `preset:jvm-dispatch`; give your own executors in a file of your own, next to it. |
| `--profile-group-by <list>` | Which optional dimensions the profile keeps besides the Java stack and the reason: any of `kernel`, `user`, `thread`, or `none`. Default all three. |
| `--max-profile-entries <n>` | Entry limit for the profile. Past it the thread, then the user stack, then the kernel stack are dropped from the grouping, which merges entries and changes no total; the report names what was dropped. Default 2,000,000. |

`--max-rows` and `--max-retained-bytes` bound admission; the default
`--max-retained-bytes` is sixty percent of the JVM's `-Xmx`, never below
256 MiB. Retention tracks distinct stacks, not capture length, so a long
capture with few distinct call paths costs little more than a short one. Plan
against at least 103 bytes per recorded interval for the columns alone, plus
one copy of each distinct stack and the profile's entries: a real Pulsar broker
capture held about 253 bytes per interval. OFFLINE.md's
[Interpretation](../jonoffcpu-correlator/OFFLINE.md#interpretation) has the
measurements.

`java -jar jonoffcpu-correlator.jar --version` prints the build and the
async-profiler fork commit. The correlator exits with status 0 for a complete
result, 2 for narrowed or partial output, and 64 for an invalid command line,
which it reports with the command's usage. By default it refuses a JFR whose
size or SHA-256 differs from the one recorded in the stream's footer.
