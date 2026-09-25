# Offline signal correlation

The Java correlator takes two finalized files: the JONOFFCPU correlation stream and the
original async-profiler JFR. The controller appends a `captureFinalized` footer
after closing the source writer and checking async-profiler finalization. That
footer binds the source prefix and JFR hashes, capture identity and stop counters.
No separate manifest or exporter subprocess is required. Stored artifact paths
are advisory, so recordings can be moved together or supplied from new locations.

The stream is defined by [`jonoffcpu-capture-codec/src/main/proto/jonoffcpu-capture.proto`](../jonoffcpu-capture-codec/src/main/proto/jonoffcpu-capture.proto):
a 12-byte header (format version 2), then length-delimited protobuf records. A
capture holds a `captureStart`, a `stack` record for each distinct native stack,
one `observation` per recorded off-CPU interval, a `captureEnd`, and the
`captureFinalized` footer. Every record is a typed message; there is one format
version and no older record shape is read. The correlator validates the control
records as messages: the `sampling`, `timeSplit` and `verifiedIdentity` copies in
`captureStart` and the footer's `analysisInputs` must be equal, and a missing or
unspecified value (an unset sampling policy or time split, an unknown enum
value) is refused rather than defaulted. Read a capture with
`java -jar jonoffcpu-correlator.jar dump --source <file>` (formerly `--dump --source`,
still accepted), which prints one record per line in the schema's proto3 JSON
mapping (`{"captureStart":{...}}`, `{"stack":{...}}`, `{"observation":{...}}`, …),
and a final `{"truncatedTailBytes":"N"}` line when the stream ends in a record
cut short.

Stacks are interned: an observation names its two stacks through
`kernelStackId`/`userStackId`, and a stack record always precedes the first
observation that references it. When the kernel could not produce a stack there
is no record and the observation carries `kernelStackError` or `userStackError`
instead. An unannounced reference, a duplicate `stackId` and an unexplained
negative id are all hard errors. The classified records re-expand both stacks,
so an audit row remains self-contained.

## Switch-out reasons

A capture records why the scheduler took each thread off the CPU.
`captureStart.sampling.reasons` lists the reasons the kernel kept, distinct and in
the canonical order `OFF_CPU_REASON_BLOCKED`, `OFF_CPU_REASON_RUNNABLE`,
`OFF_CPU_REASON_PREEMPTED`; an empty list is refused. Every observation carries
its `reason` together with the two raw `sched_switch` arguments it was derived
from, `prevTaskState` and `preempted`. The correlator recomputes the reason from
those arguments — `preempted` wins, then a zero task state is `runnable`, and any
other state is `blocked` — and marks a row whose reason disagrees, is
unspecified, or is not one the capture selected
`ROW_REASON_SOURCE_POLICY_OR_TARGET_MISMATCH`, exactly as it treats a
disagreeing admission threshold.

The reason describes the switch-out. `runnable` is how a user-space thread
preempted by the scheduler tick appears (it is switched out at an ordinary
`schedule()` on its return to user mode), and `preempted` is a preemption inside
the kernel; both are time spent waiting for a CPU. A `blocked` interval's duration
includes its run-queue delay between wakeup and switch-in; the next section splits
the two.

The report's `offCpuReasons` object lists the selected reasons, and in
`matched` one entry per selected reason with its matched intervals, observed
nanoseconds and their sleeping/run-queue split, then the kernel's per-reason
switch-out counts — taken before its reason filter, so a
blocked-only capture still shows how often its threads were preempted — and the
count and total duration of intervals the filter rejected. The population
estimate covers the selected reasons only, since the kernel's eligibility counters
are taken after the reason filter.

## Sleeping and run-queue time

A capture names where each interval's run-queue part comes from in
`captureStart.timeSplit` — `TIME_SPLIT_SOURCE_SCHED_INFO` or
`TIME_SPLIT_SOURCE_OFF` — and the same message in the manifest and in the
footer's `analysisInputs`, all compared as messages. It is a block of its own beside `sampling` because it
changes what is measured, not which intervals are kept. Under `schedInfo` every
observation carries `runqueueNanos`: the growth of the scheduler's
`task_struct.sched_info.run_delay` between switch-out and switch-in, which the
kernel adds to on every run-queue wait whether or not delay accounting or
schedstats are switched on. The collector refuses `schedInfo` at prepare on a
kernel whose BTF has no such field; there is no silent fallback to `off`. The
kernel drops a reading only when the counter went backwards
(`runqueueInversions` in the `captureEnd` kernel counters). A row carrying
`runqueueNanos` under `off` is invalid, and a capture without a `timeSplit`
source is rejected.

The correlator applies one rule, and the report, the profile and every slice
share it:

- a `blocked` interval sleeps for its duration minus `runqueueNanos` and then
  waits `runqueueNanos` for a CPU: the run-queue part is the tail
  `[end - runqueueNanos, end]`;
- a `runnable` or `preempted` interval never left the run queue and is run-queue
  time throughout, whatever the reading. The scheduler's clock can lag a few
  microseconds when a running task departs, so such a reading may slightly
  exceed the duration;
- an interval without a reading (`off`, or a dropped reading), or a `blocked`
  one whose reading exceeds its duration, is **unsplit**.
  Nothing is guessed or clamped.

Each part is clipped to the `--from-ns`/`--to-ns` window separately, so sleeping,
run-queue and unsplit time add up exactly to every interval's clipped duration.
Each `offCpuReasons.matched` entry gives `sleepingNanos`, `runqueueNanos` and
`unsplitNanos` for its reason, and `offCpuReasons.timeSplit` names the `source`, whether the split is
`available`, the rule, the unsplit intervals by cause (`withoutReading`,
`readingExceedsInterval`) and the kernel's `runqueueInversions`. The default
collapsed files and the audit files carry the whole interval
as before; the split reaches the stack profile and the `stacks --time` slices.

A record's length prefix is checked against the record limit before any bytes
are read, so a corrupt length cannot drive an allocation. A truncated final
record is the partial-mode tail: its bytes are reported as
`sourceParse.ignoredTrailingBytes` and the prefix before it is analyzed; in
complete mode it is an error.

```sh
java -jar jonoffcpu-correlator.jar \
  --source jonoffcpu-capture.pb --jfr jonoffcpu-capture.jfr --output analysis
```

A stream whose only record is a `captureFinalized` footer with
`state: FINALIZED_STATE_PROFILER_ONLY` comes from an agent run with
`sampling.admission.policy: none`.
The correlator rejects it with an explicit message: no eBPF source ran, so the
JFR is an ordinary async-profiler recording with nothing to correlate.

`--from` and `--to` select `profiler.SignalSample` events by JFR event time
using a half-open interval. Values may be ISO-8601 timestamps, epoch
milliseconds, ISO-8601 durations, or recording-relative offsets such as `5s`.
This is distinct from `--from-ns` and `--to-ns`, which clip source intervals in
the source monotonic clock after joining.

By default, the JFR must match the size and SHA-256 digest stored by the
finalized capture. Use `--partial-jfr true` only for an intentionally cut JFR.
That mode permits missing capture-context and terminal-stat events, validates
each retained sample against the source capture epoch, and reports source rows
whose JFR sample was cut away as `ROW_REASON_SAMPLE_NOT_PRESENT_IN_SELECTED_JFR`.

The output directory must not exist. Every file is named `jonoffcpu-…` so it is
recognisable wherever the directory ends up. A successful run writes:

| File | Contents | Written when |
| --- | --- | --- |
| `jonoffcpu-report.json` | Capture counters, classifications, handler-delay percentiles, interpretation notes, the degradation ladder and the opt-in population estimate | always |
| `jonoffcpu-classified-records.jsonl` | Source and resolved JFR rows, including rejected and unmatched observations, with their classification | `--audit full` |
| `jonoffcpu-matches.jsonl` | Cookies, clipped intervals, delivery delays and whether the target-to-JFR thread mapping could be verified | `--audit matches` (default) or `full` |
| `jonoffcpu-offcpu-stacks.collapsed` | Root-first signal-delivery stacks weighted in integer microseconds instead of sample counts; exact nanosecond durations remain in the report; no inverse-probability scaling unless thinning applied (see **Degradation**). Every recorded interval, whatever its reason; when more than one reason contributes, each line starts with `[offcpu: <reason>]` (`--collapsed-reason-frame auto\|always\|never`) | always |
| `jonoffcpu-offcpu-stacks-<reason>.collapsed` | The same, restricted to one switch-out reason, without the reason frame | as above, and only when more than one reason contributes |
| `jonoffcpu-offcpu-profile.pb` | The stack profile; see **Stack profile** | `--profile-output true` (default) |
| `jonoffcpu-complete.json` | Completion marker, `{"state":"MARKER_STATE_COMPLETE","coverageComplete":true,...}` | last; a directory without it is not a complete analysis, and it is never written for a narrowed run (see **Degradation**) |

Every JSON output is a protobuf message printed in the proto3 JSON mapping by
protobuf's `JsonFormat`: the report, the audit rows, the partial-mode files and
the markers are defined in
[`src/main/proto/jonoffcpu-report.proto`](src/main/proto/jonoffcpu-report.proto)
(`Report`, `ClassifiedRecord`, `Pair`, `PartialReport`, `Marker`), the JFR rows in
[`src/main/proto/jonoffcpu-signals.proto`](src/main/proto/jonoffcpu-signals.proto),
and what the profile subcommands print in
[`src/main/proto/jonoffcpu-analysis.proto`](src/main/proto/jonoffcpu-analysis.proto).
Field names are lowerCamelCase; 64-bit integers are decimal strings; exact
decimals (probabilities, fractions, shares, seconds) are decimal strings; enums
print their value names (`OFF_CPU_REASON_BLOCKED`, `CLASSIFICATION_MATCHED`); a
field without presence is always printed, even at its default, and a message or
`optional` field that is unset is left out, never `null`. Values that echo a
command-line option (`audit`, `policy`, `stack`, `weights`, …) keep the option's
spelling. A reader should parse a file into its message rather than match text.

A classified record is `{"row":N,"classification":"CLASSIFICATION_…","reason":"ROW_REASON_…","source":{…}}`
for a capture row, whose `source` holds the `observation` exactly as the stream
recorded it with its `kernelFrames` and `userFrames` expanded from the interned
stacks, or `"jfr":{…}` with the `SignalSample` for a JFR row. `reason` is left
out for a match and for a plain unmatched or orphan row. A match row
(`jonoffcpu-matches.jsonl`) is a `Pair`: the `correlationId`, the clipped
`fromNanos`/`toNanos`/`durationNanos`, `handlerDelayNanos` and
`threadIdentityVerified`.

The original combined JFR is never rewritten. CPU, allocation, lock, wall and JVM
events in it remain available to other tools.

`--audit full|matches|none` controls how much per-row audit output is written.
The CLI defaults to `matches`: `jonoffcpu-matches.jsonl` is written but
`jonoffcpu-classified-records.jsonl` is **not**, which is a backward-incompatible
change from earlier releases that always wrote both. Anything that reads
`jonoffcpu-classified-records.jsonl` — including
the agent's `AsyncProfilerFirstStopTest`
and
[`jonoffcpu-native/tools/run-agent-signal-pressure.py`](../jonoffcpu-native/tools/run-agent-signal-pressure.py)
— must now pass `--audit full` explicitly. `--audit none` writes neither audit
file. The *library* API (`OffCpuCorrelator.correlate`, and `OfflineCorrelator`'s
`OutputOptions.defaults()`) keeps the old `full` default, so embedding the
correlator as a dependency is unaffected.

The audit outputs are produced by a second read of the same two files, so they
describe the rows the correlation actually kept, not every row in the inputs. Under
thinning or a narrowed window the re-read applies the same kept-row predicate the
streaming pass did: `--audit full` then documents the kept subsample, row for row
against the counters in the report, which is what an audit of a degraded run means.

Reading, selecting, and cutting existing events uses the public JDK
`RecordingFile` API.

The collapsed output uses rounded integer microsecond weights; the report retains exact nanosecond totals.
Render it with the `jfr-converter.jar` from the same release, whose `--units`
option labels the flame graph in those microseconds:

```sh
java -jar jfr-converter.jar --title "Off-CPU time" --units µs \
  jonoffcpu-offcpu-stacks.collapsed offcpu.html
```

Use `--estimate-population true` to add a separate, opt-in
`populationEstimate` object to `jonoffcpu-report.json`. It estimates the total duration of
the completed, duration-eligible source interval population by weighting each
valid source row with `duration * 2^32 / admissionThreshold`, where
`admissionThreshold` is the exact threshold the kernel drew against for that row
(the `uniform` policy's fixed `probabilityThreshold`, or under `proportional`
`2^32` at and above `recordAllAboveMicros` and `duration * 2^32 / reference`
below it). The correlator recomputes that threshold from the `captureStart`
`sampling` object and the row's own duration and marks a disagreeing row
`ROW_REASON_SOURCE_POLICY_OR_TARGET_MISMATCH`, so the weights are the policy's, never the
producer's word alone. The sum is accumulated in exact fixed-point arithmetic and
reported as `estimatedDurationNanos`, truncated to whole nanoseconds (at most one
nanosecond low); nothing passes through floating point, and the estimate never
scales the collapsed stacks. `status` is
`ESTIMATE_STATUS_AVAILABLE` or `ESTIMATE_STATUS_UNAVAILABLE`, and `admissionPolicy` names the policy
in effect. The report also keeps the durable source duration and the smaller
stack-matched duration separate, because missing or delayed Java stack delivery
does not erase a valid source interval.

The estimate is marked available only when source rows and the kernel/userspace
selection, receipt and write counters prove complete coverage and the capture did
not report source-loss conditions. Otherwise the observed source duration remains
visible, `estimatedDurationNanos` is left out and `unavailableReasons` says which
coverage proof failed.

One loss is accounted for instead of disqualifying: sequence contention. When
the kernel selects an interval but cannot allocate its correlation sequence, it
drops the interval and counts it in `sequenceContentions`. If
`selectedIntervals - sequenceContentions` equals the source rows and the
received and written counts, every other failure counter is zero and nothing
else fails, the estimate is available with an `accountedLoss` object
(`intervals`, `fraction` of `selectedIntervals` to six significant digits as a
decimal string, `reason: "sequence-contention"`),
`sourceCoverageComplete` is false, and the sum is scaled by
`selectedIntervals / receivedObservations` in the same exact fixed-point
arithmetic. That scaling is unbiased only if contention is independent of an
interval's stack and duration, which `assumptions` states. The stack profile's
per-entry estimates take the same scale, so `stacks --weights estimated` stays
consistent with the total. A loss above `--max-accounted-loss` (a fraction of the
selected intervals, default `0.01`, at least 0 and below 1) is refused with
`accounted-loss-above-limit`, `accountedLoss` still reported. A gap the
contention count does not explain, or one beside any other nonzero failure
counter, keeps `nonzero-sequenceContentions` and
`selected-source-row-count-mismatch` as before.

This is an inverse-probability estimate under the recorded
random admission policy; it is not a confidence interval or an adjustment for
missing Java stacks. Under `proportional`, a rare short interval that was admitted
carries a weight of up to the full reference duration, so per-stack estimates for
rare stacks are noisy even when the total is unbiased.

## Stack profile

`jonoffcpu-offcpu-profile.pb` is a derived artifact from which any collapsed-stack
slice can be rendered again without re-reading the two inputs. Its format is
defined by [`jonoffcpu-correlator/src/main/proto/jonoffcpu-profile.proto`](src/main/proto/jonoffcpu-profile.proto):
a 12-byte header, then length-delimited records — `profile_start`, a string
constant pool, frames, a prefix-shared stack-node tree, one `entry` per distinct
grouping key, and `profile_end` with the totals. Every string, frame and node is
written before its first use and ids are dense from 1, so 0 always means absent.
Entries are written in one canonical order, so the same analysis gives the same
bytes whatever order its intervals matched in.

Each frame has a kind. Native stacks' frames are `USER` or `KERNEL`. A Java
stack's frame is `JAVA` when async-profiler recorded it as Java code (frame type
`Interpreted`, `JIT compiled`, `C1 compiled` or `Inlined`), and `JFR_NATIVE`
otherwise (`Native`, `C++`, `Kernel`, or no type): a frame such as
`libjvm.so.Unsafe_Park`, the library then the symbol, which only looks like a
package-qualified Java name. Stacks that differ only in a frame's type share a
collapsed key, so a frame position is `JAVA` only when every such stack agrees.
The kind changes no name, so the collapsed key and every rendered line stay the
same.

`profile_start` carries `schema_version` 3, and the reader refuses any other
schema with `Unsupported stack profile schema` rather than render it: each
source's `Provenance` holds the capture's `sampling` and `time_split` as the
capture messages, and `report` is the producing run's `Report`, the message
`jonoffcpu-report.json` prints (unset for a merged profile).

An entry is keyed by the Java stack (at the collapsed file's class-and-method
granularity) and the switch-out reason with its raw task state, and by default
also by the kernel stack, the user stack and the thread name
(`--profile-group-by`, any of `kernel`, `user`, `thread`, or `none`). It carries
the matched interval count, the selected observed nanoseconds (clipped to the
analysis window and before any thinning reweight), and the same intervals'
inverse-probability weight, floored to whole nanoseconds; `estimate_available`
repeats the population estimate's verdict on whether that weight may be used.
When `time_split_available` is set, an entry also splits its observed and
estimated nanoseconds into sleeping, run-queue and unsplit parts that add up
exactly to the totals; the estimated parts are floored cumulatively (sleeping,
then sleeping plus run queue, then the whole), so a part with no weight stays
zero. A profile without the flag, including one written before the split existed,
reads as all unsplit.
Past `--max-profile-entries` (default 2,000,000) the thread, then the user stack,
then the kernel stack are dropped from the key; that merges entries without
changing any total, and `profile_end` and the report's `stackProfile` object name
what was dropped. The profile embeds the run's report as a typed message and the
label frames its collapsed lines start with. Partial mode writes no profile.

The reader validates the header, the reference order and the end record's totals,
and refuses a truncated or foreign file. Three subcommands use it:

```sh
java -jar jonoffcpu-correlator.jar stacks --profile P --output F
    [--reason all|blocked,runnable,preempted,unspecified]
    [--stack java|kernel|user|java+kernel|java+user+kernel]
    [--weights observed|estimated] [--reason-frame auto|always|never]
    [--time total|sleeping|runqueue|split] [--package-names full|abbreviate|drop]
    [--summary S]
    [--include REGEX]... [--exclude REGEX]...
    [--include-from FILE]... [--exclude-from FILE]...
    [--canonical-names] [--hide REGEX]... [--trim-root REGEX]...
    [--root-at REGEX]... [--root-at-unmatched bucket|keep|hide] [--leaf-at REGEX]...
    [--collapse-leaf REGEX]... [--collapse-leaf-label frame|category]
    [--thread-frame none|name|pool]    (each REGEX option also has a -from FILE form)
java -jar jonoffcpu-correlator.jar stacks --collapsed-input C --output F [filters and transforms]
java -jar jonoffcpu-correlator.jar stacks --list-presets
java -jar jonoffcpu-correlator.jar merge --profiles A,B,... --output M
java -jar jonoffcpu-correlator.jar export --profile P --format csv|jsonl --output E
    [--run-label TEXT] [--run-metadata R]
```

`stacks` with its defaults reproduces `jonoffcpu-offcpu-stacks.collapsed` byte for
byte, including the thinning label and reweighting. Native frames are rendered
without their `+0x` offsets, kernel frames with an `_[k]` suffix, and a kernel
stack stops before the tracing frames that captured it (`__traceiter_*`,
`__bpf_trace_*`, `bpf_trace_run*`, `bpf_prog_*`); the profile keeps them.

`--time` picks which part of each entry's time a slice weighs: `total` (the
default), `sleeping`, `runqueue`, or `split`, which keeps the whole time and ends
each line in a `[sleeping]`, `[runqueue]` or `[unsplit]` frame. Every mode but
`total` refuses a profile without the split. `--reason-frame auto` counts a
reason only when it contributes to the selected part. The `--summary` file names
the `time` part and carries `unsplitNanos`, the kept entries' unsplit time, which
a `sleeping` or `runqueue` slice leaves out. The summary is a `SliceSummary` message (a
`CollapsedSliceSummary` for `--collapsed-input`), with the reasons as
`OFF_CPU_REASON_…` values.

### Transforms

Filters decide which entries a slice keeps; transforms change what a kept
entry's Java stack looks like. They run per entry after the filters, which
therefore always match the full, untransformed stacks, in this order:

1. `--canonical-names` removes generated-class addresses (`$$Lambda.0x…`,
   `$$Lambda$14/0x…`, `LambdaForm$MH/0x…`, `LambdaForm$DMH/0x…`), so runs compare.
2. `--hide` removes every matching frame anywhere; a stack of nothing else
   keeps its leaf.
3. `--trim-root` removes the longest root-side run of matching frames; the
   run stops at the first frame that does not match, so deeper matches stay,
   and a stack that matches throughout keeps its leaf.
4. `--root-at` starts the stack at its root-most matching frame. A stack
   without one becomes the single frame `[no application frame]`, stays as
   it is with `--root-at-unmatched keep`, or with `--root-at-unmatched hide` is
   left out of the lines, as described below. `hide` without `--root-at` is
   refused, since nothing can be unmatched.
5. `--leaf-at` cuts the callees of the leaf-most matching frame and keeps the
   match; a stack without one is unchanged.
6. `--collapse-leaf` replaces the longest leaf-side run of matching frames by
   the run's root-most frame, or with `--collapse-leaf-label category` by the
   category of that frame: `[kernel]`, `[monitor]`, `[park]`, `[wait]`,
   `[sleep]`, `[lock]` or `[native]`, and the frame itself when none applies.

`--package-names` and `--thread-frame` (the thread's name, or its pool with
every digit run replaced by `#`, as a frame after any reason frame) are then
applied as display. With `--stack java+kernel` or `java+user+kernel` the native
stacks are appended unchanged after the transformed Java stack. Lines that read
the same after transforming merge, adding their intervals and nanoseconds, so
no transform changes a total, with one exception: `--root-at-unmatched hide`
leaves an entry without a `--root-at` match out of the lines and out of the
slice's totals, and the `--summary` file reports it as
`rootAtUnmatchedHidden` (`intervals` and `totalNanos`, or `inputLines` and
`totalWeight` for a collapsed input), so the kept and hidden totals add up to
the slice rendered without hiding; its `noApplicationFrame` is then the hidden
weight, and its share of that sum. Hiding is decided per entry after the
filters and before `--reason-frame auto`. Each `REGEX` option repeats, and has a
`-from FILE` form that reads patterns as `--exclude-from` does. Every `-from`
option, the filters' included, also accepts `preset:NAME`, a list bundled with
the correlator: `jvm-infra` (thread, executor and Netty entry points, for
`--trim-root` or `--hide`), `jvm-wait-machinery` (lock, park and monitor
internals down to libc and the kernel, for `--collapse-leaf`), `jvm-idle`
(waits for work, for `--exclude-from`) and `jvm-dispatch` (generated lambda
classes' methods, `Executors$RunnableAdapter.call`, `FutureTask.run` and
`runAndReset`, `CompletableFuture`'s async tasks and Guava's listenable future
tasks: frames that only forward to the code a task runs, for `--hide`, so that
`--root-at` lands on the work; hidden anywhere in the stack, since they carry
no information mid-stack either); `stacks --list-presets` prints them with
their caveats. When any transform is in effect the `--summary` file gains a
`transforms` object: each option with its patterns and their source (`inline`, a
file, or `preset:NAME`), `linesBefore`/`linesAfter`, the weight-averaged depth
`framesBefore`/`framesAfter`, and for `--root-at` the `noApplicationFrame`
weight and share.

`--collapsed-input FILE` takes any collapsed file instead of a profile — a CPU,
wall, allocation or lock view the converter wrote, or a jonoffcpu collapsed
file — through the same filters, transforms and `--package-names`. The
converter's Java frames lose their compilation marker (`_[j]`, `_[i]`, `_[0]`,
`_[1]`) and the `/` of their class names; every other frame is kept verbatim.
In a file with such markers every unmarked frame is native; in one without them
a frame is native when its name contains `::`, `.so.` or a space, starts with
`/` or `[`, or ends with `_[k]`. Weights keep the file's unit, integer or
decimal, and the summary says `input: collapsed`. `--reason`, `--stack`,
`--weights`, `--time`, `--reason-frame` and `--thread-frame` need a profile and
are refused.

`--package-names abbreviate` cuts each package segment of a Java frame to its
first letter (`io.netty.channel.epoll.Native.epollWait0` becomes
`i.n.c.e.Native.epollWait0`), and `drop` shows `Class.method`; a hidden class's
`.0x…` suffix (`Cursor$$Lambda.0x0000000081a16ff8`) stays part of the class. Only
`JAVA` frames change: a `JFR_NATIVE` frame keeps its library and symbol
(`libjvm.so.Unsafe_Park`). As a fallback for schema 1 profiles, and as a second
line of defence, a name that reads as native is also left alone: one with a C++
`::`, a shared-library segment (`.so.`, `.so.6.`), a leading `/` or `[`, or a
space. That rule can only prevent a rewrite, and a frame that is not a
package-qualified `Class.method` is left unchanged too. It only changes the
rendered text: lines that become identical are summed, filters match the full
names, and `--summary` records the mode as `packageNames`.

`--include`/`--exclude` filter whole profile entries before they are merged into
lines, and before `--reason-frame auto` decides whether the slice mixes reasons.
An entry is dropped when any frame of any stack the profile is grouped by
matches an exclude pattern, and otherwise kept when there are no include
patterns or a frame matches one; each option repeats, meaning any of its
patterns. `--include-from`/`--exclude-from` add the patterns of a file, one per
line after the inline ones; blank lines and lines starting with `#` are skipped
(`\#` escapes a leading `#`), other lines are taken verbatim, and a file without
patterns is refused, since an empty include list would keep everything. The
summary lists the patterns read, not the file names. The frames matched are the ones a collapsed line would carry — Java
names, offset-free native symbols, kernel symbols with `_[k]` and without the
tracing frames, and the `[kernel stack unavailable]`/`[user stack unavailable]`
placeholders — for every grouped stack, whichever `--stack` selects; patterns
are searched for (`Matcher.find`), not matched whole. This is what
`jfr-converter -I/-X` on a rendered file cannot do: it sees only the rendered
frames, and a line merged from several entries can no longer be split. The
`--summary` file records the slice's interval count and total, the patterns,
`filterScope` (the stack kinds searched; a dropped dimension is not), and
`filtered`, the intervals and nanoseconds removed. Kept plus filtered equals the
unfiltered slice exactly, thinning included, because the unfiltered slice is
rendered to compute it. `merge`
sums identical entries and keeps every input's provenance; it refuses profiles
with different grouping, and thinned profiles, whose weights have no common scale.
It sums the split parts too, so an input without the split contributes its time
as unsplit and the merged profile has the split when any input has it. Java
stacks with the same frame names merge whatever kinds their inputs gave them, and
a frame any input calls `JFR_NATIVE` stays `JFR_NATIVE`, so a schema 1 input
cannot make a native frame rewritable.
`export` writes one row per entry with expanded stacks, the six split columns and
`java_stack_kinds` (`javaStackKinds` in JSON Lines): each Java-stack frame's
kind, `java` or `native`, joined with `;` like `java_stack`. It is for tools such
as DuckDB. A JSON Lines row is an `ExportRow` message. These columns are followed,
in this order, by:

| JSON Lines | CSV | Contents |
| --- | --- | --- |
| `javaFrames`, `javaFrameKinds`, `kernelFrames`, `userFrames` | — | The stacks and the Java frames' kinds as arrays, root first, as the joined columns render them; empty for an absent stack, whose joined column is left out. CSV stays flat. |
| `canonicalJavaStack` | `canonical_java_stack` | `javaStack` without generated-class addresses, the rule of `stacks --canonical-names`, so stacks of two runs join |
| `threadPool` | `thread_pool` | The thread name with every digit run replaced by `#` |
| `run` | `run` | `--run-label`, by default the profile's label, else its first source's session id |
| `estimateAvailable` | `estimate_available` | Whether the estimated columns may be used, from the profile header |

In JSON Lines `reason` is the enum value name (`OFF_CPU_REASON_BLOCKED`, where
the CSV has `blocked`), `taskState` is a number, and the 64-bit counters
(`intervals` and every `…Nanos` column) are decimal strings, as the proto3 JSON
mapping writes every 64-bit integer, so no counter loses precision in a double.
CSV counters are unsigned decimals. DuckDB reads the string counters as
`VARCHAR`, the frame arrays as `VARCHAR[]`, `estimateAvailable` as `BOOLEAN` and
`taskState` as a number; cast the counters once in a view:

```sql
CREATE VIEW entries AS SELECT * REPLACE (intervals::UBIGINT AS intervals,
  observedNanos::UBIGINT AS observedNanos, estimatedNanos::UBIGINT AS estimatedNanos)
FROM read_json('entries.jsonl', format = 'newline_delimited');
```

The other `…Nanos` columns cast the same way.

`--run-metadata FILE` also writes one `RunMetadata` message describing the
profile, to load into its own table and join to the rows on `run`:

```json
{"run": "...", "label": "...",
 "sources": [{"sessionId": "...", "captureEpoch": "1", "sourceSha256": "...", "originalJfrSha256": "...",
              "thinningProbability": "1", "thinningSeed": "0", "windowFromNanos": "0", "windowToNanos": "0",
              "sampling": {"reasons": ["OFF_CPU_REASON_BLOCKED"], "proportional": {"recordAllAboveMicros": "10000"}},
              "timeSplit": {"source": "TIME_SPLIT_SOURCE_SCHED_INFO"}}],
 "dimensions": ["reason", "kernel", "user", "thread"], "estimateAvailable": false,
 "timeSplitAvailable": true, "entries": "2791", "intervals": "308777", "observedNanos": "8519334220784"}
```

## Ranked tables and the digest

`top` ranks where a profile's (or, with `--collapsed-input`, any collapsed
file's) time went, with the selection, filter and transform options of
`stacks`:

```sh
java -jar jonoffcpu-correlator.jar top (--profile P | --collapsed-input F)
    [--by boundary|root|app-method|self|method|class|package|pool] [--app REGEX]... [--app-from FILE]...
    [--idle REGEX]... [--idle-from FILE]... [--machinery-from FILE]...
    [--weights observed|estimated] [--limit N] [--format md|json|csv] [--output FILE]
    [--baseline P2 [--units X --baseline-units Y]]
java -jar jonoffcpu-correlator.jar summarize --profile P [--report R] [--app REGEX]...
    [--hide-from FILE]... [--idle-from FILE]... [--limit N] [--output-dir D]
```

- **Idle or busy.** An entry is idle when any frame of any of its stacks
  matches an idle pattern, matched as `--exclude` matches. Idle entries are not
  dropped: they get their own table. `--exclude` still removes entries.
- **Attribution** is computed on the Java stack after `--canonical-names` and
  `--hide`. `boundary` (the default, which needs `--app`) keys a row by the
  deepest application frame and its **blocker**: the entry frame of the longest
  leaf-side run below the boundary that matches the wait machinery
  (`--machinery-from`, default `preset:jvm-wait-machinery`), or the leaf when
  there is none. Busy entries without an application frame go to a table by
  thread pool (the name with digit runs as `#`). `root` is the first frame of
  the stack after every transform, the root box of the flame graph rendered
  with the same options; without `--root-at` or `--trim-root` it is a thread's
  entry point, and the output warns. `app-method` (which needs `--app`) takes
  the distinct application frames of each transformed stack, hidden frames
  being gone by then, and adds the stack's time to each once (inclusive, so a
  recursive method counts once); methods that occur in exactly the same
  distinct transformed stacks are one call chain and one row, keyed
  `A → … (n) → Z` for `n` methods, root-most first, with every method in
  `methods`. Its busy entries without an application frame go to the pool
  table, as for `boundary`. `self` is the leaf after the
  transforms, `method`, `class` and `package` count every distinct one in the
  stack once per entry (inclusive), and `pool` is the thread's pool.
- **Hidden entries.** With `--root-at-unmatched hide` a busy entry without a
  `--root-at` match is left out of every mode's rows; `totals` reports it as
  `busyRootAtUnmatchedHidden` (Markdown: "Busy without an application frame,
  hidden by --root-at") and the pool table lists it by pool.
- **Columns**: rank, key (and blocker), seconds to three decimals, share of the
  busy total (of the idle total in the idle table), intervals, estimated
  seconds when the profile's estimate is available, sleeping and run-queue
  seconds when it has the split, the dominant reason, and for boundary rows the
  heaviest root-most application frame (the caller, after `--trim-root` and
  `--root-at`). Root rows add `heaviestStack`, the heaviest transformed line
  under the root with abbreviated package names. Application-method rows add
  `self` (the time of the stacks whose deepest application frame is in the
  chain, the same boundary as `--by boundary`, so self times add up to the
  rows' total) and `stacks` (the distinct transformed stacks), and name no
  reason; they sort by inclusive time, then self time, then the root-most
  method. Other rows sort by weight, then key. A share is of the busy total,
  except that `app-method` shares are of the busy time with an application
  frame (and add up to more than 100 %), and with `--root-at-unmatched hide`
  every share is of the busy time the rows cover.
- **Totals** add up: busy and idle make the selection, and the rows (their
  `busyApplication` total for `boundary` and `app-method`) and the pool table
  make the busy total. The over-exclusion check counts idle
  entries with a `java.util.concurrent.locks.*.lock*`/`acquire*` or
  `complete_monitor_locking` frame, the waits an idle list may hide by mistake.
- **Weights.** `observed` by default; `estimated` needs the profile's
  estimate. Under proportional or uniform admission observed time
  under-weights short waits, and the output warns.
- **`--baseline`** keys both runs by application boundary and gives each row's
  seconds, or seconds per unit of work with `--units`/`--baseline-units`, and
  its share of that run's busy application time. Rows present in one run only
  have the other side empty; rows sort by the larger value, then by the
  absolute delta. It refuses observed weights when both runs have estimates and
  either was sampled, warns when neither has estimates, and warns when the runs'
  unresolved native frames (`/lib/…` paths) differ by more than 10 points of
  busy time, since their time without an application frame is then not
  comparable.
- **Formats.** `json` is the source, a `TopResult` message: `command` (the
  reproduce command), `by`, `unit`, `selection` (every option, with the
  patterns and their sources), `totals`, `rows`, `noApplicationFrame`, `idle`
  (or `comparisonTotals` and `comparison`), and `warnings`. A row's `key` is
  the boundary, the pool or the `--by` key; seconds and shares are decimal
  strings, and `reason` an `OFF_CPU_REASON_…` value. `md` and `csv` (one row
  per table row, with a `table` column, and `heaviest_stack`, `self`, `stacks`
  and `methods` appended after `caller`) are rendered from it; the Markdown
  lists each chain of three or more methods in a `<details>` block below its
  table.

Correlation writes the **digest**, `jonoffcpu-summary.json` and
`jonoffcpu-summary.md`, next to the report unless `--summary-output false` is
given, with the idle patterns of `--idle` and `--idle-from` (default
`preset:jvm-idle`), the application patterns of `--app` and `--app-from`, the
hidden frames of `--hide` and `--hide-from` (default, with `--app`,
`preset:jvm-dispatch`; refused without `--app`), `preset:jvm-wait-machinery`
and canonical names. Like the idle options they are refused with
`--summary-output false` and in partial mode. `summarize` rewrites it with
other patterns, taking the capture section from the report the profile
carries, or from `--report FILE`, which is parsed strictly as a `Report`.

Without `--app` the digest ranks busy time by the stack's leaf after
collapsing the wait machinery, and by pool. With `--app` every table is
computed from one set of transforms, those of the application-rooted flame
graph: `--exclude-from <idle>` as the filter, then `--canonical-names
--hide-from <hide> --root-at-from <app> --root-at-unmatched hide
--collapse-leaf-from preset:jvm-wait-machinery`. The Markdown opens with the
busy time with an application frame, its share of the busy time and the time
left out, then, in order: busy time by the application method that waited
(`top --by boundary`), by application root (`top --by root`), the ten
heaviest application stacks (abbreviated package names; the reproduce
command is the `stacks` command of the flame graph), by application method
(`top --by app-method`), where the time went, busy time without an
application frame by pool, the capture, and one reproduce command per table.
The boundary and root tables each sum to the busy time with an application
frame. When more than half of the busy time has no application frame, the
opening and the pool section say that the idle patterns probably miss some
waits for work. Input paths are named as they were given, so a digest written
with relative paths keeps working when its directory moves.

The digest is about busy time. An interval with a frame matching an idle
pattern is a wait for work, such as an event loop in `epoll` or a pool worker
waiting for a task, and would otherwise dominate every table: the digest counts
idle intervals in `whereTheTimeWent` and leaves them out of every table and
stack. The idle patterns only shape the digest; the collapsed stacks, the
profile and the report keep every interval. An application's own idle waits,
such as a task queue of its own, belong in an `--idle-from` file of its own,
next to `preset:jvm-idle`. The report's `digest` object names the files, or
holds the `error` when the digest could not be written, which never fails the
correlation. The JSON is a `Digest` message:

| Field | Contents |
| --- | --- |
| `schemaVersion` | `2`: 1, when absent, is the layout before the application-rooted tables |
| `profile`, `run`, `estimateAvailable`, `timeSplitAvailable` | What was summarised |
| `capture` | From the report: session, sampling, source rows, matched, rows outside the selected JFR window, orphan and invalid counts, collector loss counters, handler delay p50/p99/max, reasons and kernel switch-outs, the population estimate's status and accounted loss when present, and degradation steps |
| `selection` | As in `top --format json` |
| `whereTheTimeWent` | `top`'s totals: selected, idle, busy, busy with and without an application frame, over-exclusion |
| `busy` | `by` (`boundary`, the application method that waited, with `--app`, else `self` after collapsing the wait machinery), the reproducing `command`, which also lists the idle waits, and the busy `rows` of `top` |
| `busyByRoot`, `busyByApplicationMethod` | With `--app`: the rows of `top --by root` and `top --by app-method`, each with its `command`; a method row carries its chain's `methods` |
| `busyNoApplicationFrameByPool` | The pool table (with `--app`), or busy time by pool (without) |
| `busyWithoutApplicationFrame` | With `--app`: the busy time the tables leave out, also in `whereTheTimeWent` |
| `heaviestStacks` | Without `--app`: the busy slice with `--trim-root-from preset:jvm-infra` and `--collapse-leaf`, dropped package names: its `lines`, `meanDepth`, the ten heaviest lines (`top`), and the `command` |
| `heaviestApplicationStacks` | With `--app`: the same for the application-rooted slice, abbreviated package names |
| `warnings` | As in `top` |

Every table is limited to `--limit` rows (default 20), which keeps the Markdown
of an Apache Pulsar broker's digest under 16 KB without `--app` and under 40 KB
with it. The Markdown is rendered from
the JSON, so the two cannot disagree, and the same inputs give the same bytes.

## Degradation

A profile is not an audit log. When a capture does not fit the retained-bytes budget,
`--on-limit degrade` (the default) walks a ladder rather than refusing, and records
every step in the report's `degradation` object (a `DegradationReport`):

1. **Drop the audit outputs.** `--audit matches`, then `--audit none`. They cost the
   most and contribute nothing to the flame graph.
2. **Thin the source and reweight.** Each recorded interval is kept with probability
   `q`, decided by hashing its cookie, and the duration it contributes is scaled by the
   exact reciprocal of the realised probability. The result is an unbiased estimate of
   the same per-stack totals over the whole requested window. Because the cookie is the
   join key, an observation and its JFR sample are dropped together, so every count in
   the report describes one coherent subsample. `--thinning <q>` pins it and
   `--thinning-seed` changes the draw; `q = 1` is the default whenever the input fits.
3. **Narrow the window.** Analyse `[from, effectiveTo)` completely rather than the whole
   window approximately. Because both inputs are ordered on the delivery clock, a prefix
   is a complete analysis of a shorter window.
4. **Fail**, naming the limit, the steps already tried and the flag that would allow the
   next one.

`--on-limit fail` restores the old behaviour. `--on-limit truncate` skips thinning and
goes straight to narrowing.

The two degradations are labelled differently because they differ:

- **Thinning keeps the ordinary output names**, writes `jonoffcpu-complete.json`, and
  exits 0. The capture is already a sample of the intervals by design; a second sampling
  stage with a stated estimator covers the window that was asked for. The report carries
  `sourceThinning` with `q`, the seed, the exact realised probability and the estimator,
  and every collapsed line carries a `[thinned q=…; inverse-probability estimate]` root
  label so a rendered flame graph cannot be quoted without its caveat.
- **Narrowing takes the `INCOMPLETE-jonoffcpu-*` names**, exits 2, writes
  `jonoffcpu-narrowed.json` instead of `jonoffcpu-complete.json`, and labels its
  collapsed lines with the effective window, because the result no longer answers the
  question that was asked. A reader that only checks the exit status and the presence of
  `jonoffcpu-complete.json` can already tell a statistically valid whole-window estimate
  (thinning, exit 0) apart from a result describing less than the window it asked for
  (narrowing, exit 2, `jonoffcpu-narrowed.json`).

Under thinning the collapsed weights are estimates rather than observed durations, so
`--estimate-population`, which is an inverse-probability estimator over the kernel's own
admission thresholds, is rejected together with an explicit `--thinning`, and is marked
`unavailable` with `correlation-time-thinned-source` when the ladder applies thinning by
itself. That asymmetry is deliberate. Asking for both on the command line is asking for
two things that cannot both be honoured, and the CLI says so up front rather than
handing back an estimate that is not the one requested. A ladder-chosen thinning is not
a request: the run was asked to fit a budget, it thinned to fit, and the population
estimate that is no longer computable is reported as `unavailable` with that reason
instead of failing an analysis that otherwise succeeded.

The report's top-level `degradation` object is always present, even when nothing needed
to degrade, so a consumer can see that degradation was considered and declined. It
carries `policy`, `requestedAudit`, `audit`, `retainedBytesLimit`,
`estimatedRetainedBytes` (from `RetentionEstimate`, computed from the input file sizes
before decoding), `peakRetainedBytes` (what the run actually held at its high-water
mark), `attempts`, the top-level `narrowedToNanos` (left out unless the window was
narrowed, mirroring `peakRetainedBytes` so a consumer that reads only the top-level
object need not scan `stepsApplied` for the `narrowWindow` entries), and `stepsApplied`:
one entry per ladder step actually taken, with its `reason` and the step as the
one key that names it — `thinSource`, `dropAuditOutputs` or `narrowWindow` — holding
the step's details.

## Interpretation

The cookie provides exact event association, **not simultaneous stack capture**.
eBPF captures native stacks at scheduler exit and requests a signal. Async-profiler
captures its stack when that signal is delivered. It is therefore a
**signal-delivery stack**, not a proven stack at the beginning of the off-CPU
interval. Signal delay can change the observed stack. Both original native stacks
are retained in the classified source rows, expanded from the interned stack
records.

`--max-handler-delay-ns N` can reject delayed pairs, but a small delay does not
prove stack equivalence. Delay is the handler's monotonic timestamp minus the
source interval end and the verified clock offset. Timestamps never choose the
matching sample. Duplicate cookies invalidate every copy and its counterpart.
The capture's `signalDelivery` policy (`queued` or `coalescing`) must match the
source header, final receipt and JFR context. The delay filter applies to both
policies. Coalescing does not guarantee freshness: the first pending cookie can
still be old when its handler runs.
Unknown thread mappings are reported explicitly; known thread mismatches are
rejected. Failed signal requests, missing JFR samples and orphan JFR samples are
not silently converted into matches.

`--from-ns N` and `--to-ns N` optionally clip durations to an interval in the
**source monotonic clock**, after joining the complete capture. These are not
relative seconds or epoch timestamps. Either bound can be omitted. A matching
handler event outside the interval still identifies an overlapping source interval.
`--from-ns`, `--to-ns` and `--max-handler-delay-ns` are held as signed `long`
nanoseconds in the engine's columns, so each is rejected with `Time boundary
outside signed 64-bit nanoseconds` when it is negative or at or above `2^63`;
values in `[2^63, 2^64)` that an earlier release accepted now fail fast instead of
wrapping.

Default admission limits are a hundred million total source/JFR records, 1 MiB per
source record, 4,096 frames per stack record and per JFR sample, and a retained-bytes
budget of sixty percent of `Runtime.maxMemory()`, never below 256 MiB. Use `--max-rows`
or `--max-retained-bytes` to change them.

`--max-retained-bytes` charges what the correlator actually holds: the primitive
columns, the cookie index, the interned JFR stacks and the few retained control
objects. Retention is proportional to the number of *distinct stacks*, not to the
number of recorded intervals — a capture of 1.12 million samples carrying 10,631
distinct stacks holds one copy of each — so the guard is a usable steering signal
rather than a proxy for input size. Roughly 103 bytes of retention per recorded
interval, plus the interned stacks and the stack profile's entries, is the figure
to plan a capture against — it is the load-bearing number here. A source column
slot is 59 bytes: the switch-out reason, the task state, both native stack ids and
the run-queue reading are kept for the stack profile. The scale fixture
(`StreamingCorrelatorTest.scale`, 2,000,000 observations and a matching 2,000,000
JFR samples) asserts a bound of 400 MiB on peak retained bytes and has measured
comfortably inside it; the exact figure moves with the engine's structures and is
not a number to plan against.

A real Pulsar broker capture (1,121,421 source rows, 890,086 matched, 10,631
distinct Java stacks) measured 271 MiB (284,167,413 bytes) of peak retained
bytes — about 253 bytes per recorded interval, roughly 2.5x the 103-byte
column-only figure above, against 260 MiB (272,115,381 bytes) for the same
capture before the run-queue reading was kept and 203 MiB before the native
stacks and the stack profile were. Real JVM
stacks are far deeper than the synthetic scale fixture's, so interned stacks
account for the difference; treat 103 bytes/interval as a lower bound for the column storage
alone, not the full per-interval budget, when planning against real captures.
Its stack profile is 913 KB for 12,914 entries, against a 6.3 MB collapsed file,
and renders any slice in about a third of a second.

Integrity failures still reject the analysis: a JFR whose size or digest differs from
the footer, an observation referencing an unannounced stack, an async-profiler counter
inconsistency, a duplicate cookie, a JFR/source target mismatch. These mean the two
files do not describe the same capture, so no weight computed from them means
anything. Volume limits behave differently; see **Degradation**.

## Library API

The public Java API uses JDK types only. It performs strict correlation with the
default limits, writes all derived outputs, and returns a compact summary:

```java
OffCpuCorrelator.Summary summary = OffCpuCorrelator.correlate(
    sourcePath, jfrPath, outputDirectory);
```

The output directory must not exist. Input files must already be closed and
stable. Digests are checked before analysis and rechecked before returning.
Advanced selection and recovery options are available through
`OffCpuCorrelator.run(args)`, which returns the CLI status without terminating
the calling JVM. `SignalJfrExporter.export(Path, Writer)` prints a JFR's signal
events as JSON Lines, one `SignalRecord` per line. Protobuf (with the Gson
parser its `JsonFormat` uses) and picocli are relocated
implementation details and do not appear in public method signatures.

## Explicit incomplete diagnostics

Strict correlation still requires both finalized artifacts. To inspect a closed,
possibly truncated pair after abrupt termination, select the separate partial path:

```sh
java -jar jonoffcpu-correlator.jar \
  --source interrupted.pb --jfr interrupted.jfr --output incomplete-analysis \
  --partial true
```

A successfully written partial diagnostic run exits with **status 2**. Complete
analysis exits with status 0; an invalid command line exits with status 64 after
printing the problem and the command's usage, and other errors exit with status 1.
Partial mode never promotes
its result to complete, even if the supplied inputs happen to be finalized.
Its new output directory holds a visibly different file set:

| File | Contents | Written when |
| --- | --- | --- |
| `INCOMPLETE-jonoffcpu-report.json` | Diagnostics for the observed prefix, a `PartialReport` | always |
| `INCOMPLETE-jonoffcpu-classified-records.jsonl` | Classified source rows and JFR samples from the prefix | always |
| `INCOMPLETE-jonoffcpu-pairs.jsonl` | Exact-cookie pairs found in the prefix, as `Pair` rows without `handlerDelayNanos` where the clock could not be verified | always |
| `INCOMPLETE-jonoffcpu-offcpu-stacks.collapsed` | Prefix stacks, each under an explicit incomplete root label that survives ordinary flame graph rendering | `--format collapsed` |
| `jonoffcpu-partial.json` | Marker with `state: MARKER_STATE_INCOMPLETE`, `coverageComplete: false` and the `incompleteReasons` | last |

There is never a `jonoffcpu-complete.json` in partial mode.

Only fully decoded records are retained. A final record cut short is discarded
and its byte count reported; a malformed complete record remains an error. Missing source end/footer or AP terminal stats, and a JFR decoding failure
at the unread tail, are reported explicitly. An unread suffix could contain a late
duplicate, so even coherent exact-cookie pairs are **provisional within the
recovered records**. Recovered duplicates invalidate every received copy. Counts
of missing/orphan samples describe the recovered prefix, not final delivery loss.

An unknown format version, conflicting capture contexts, inconsistent final counters,
resource limits, same-size hash mismatches and changing input files remain hard
errors. A JFR shorter than an observed footer declares may be inspected, but its
full-file hash is not marked verified. The report retains actual input byte counts,
digests, observed terminal evidence and its verification state.

A missing footer does not establish a shared clock or a zero offset. Such results
leave out handler delays and reject `--max-handler-delay-ns`; a missing AP final
submitted count is left out, not zero. Explicit `--from-ns`/`--to-ns` can still clip
observed source intervals in their own monotonic clock. No wall-time mapping is
inferred. Population estimates require the complete path and are rejected in partial
mode.

The JFR prefix reader behind partial mode reports its parser evidence in the
report's `jfrParse` object and never emits a successful end row.
