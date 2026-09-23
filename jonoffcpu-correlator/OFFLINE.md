# Offline signal correlation

The Java correlator takes two finalized files: the JONOFFCPU correlation stream and the
original async-profiler JFR. The controller appends a `captureFinalized` footer
after closing the source writer and checking async-profiler finalization. That
footer binds the source prefix and JFR hashes, capture identity and stop counters.
No separate manifest or exporter subprocess is required. Stored artifact paths
are advisory, so recordings can be moved together or supplied from new locations.

The stream is defined by [`docs/schema/jonoffcpu-capture.proto`](../docs/schema/jonoffcpu-capture.proto):
a 12-byte header, then length-delimited protobuf records. A capture holds a
`captureStart`, a `stack` record for each distinct native stack, one
`observation` per recorded off-CPU interval, a `captureEnd`, and the
`captureFinalized` footer. The three control records carry the JSON object they
have always carried, at `schemaVersion` 4 (3 for captures recorded before the
sleeping/run-queue split, and 2 for captures recorded before switch-out reasons
were classified, both still read), and that JSON is still read with the strict
parser; observations and stacks are native protobuf.
All control records of one capture carry the same version. Read a capture with
`java -jar jonoffcpu-correlator.jar --dump --source <file>`, which prints one
JSON object per record with each observation's stacks expanded.

Stacks are interned: an observation names its two stacks through
`kernelStackId`/`userStackId`, and a stack record always precedes the first
observation that references it. When the kernel could not produce a stack there
is no record and the observation carries `kernelStackError` or `userStackError`
instead. An unannounced reference, a duplicate `stackId` and an unexplained
negative id are all hard errors. The classified records re-expand both stacks,
so an audit row remains self-contained.

## Switch-out reasons

A `schemaVersion` 3 capture records why the scheduler took each thread off the
CPU. `captureStart.sampling.reasons` lists the reasons the kernel kept, in the
canonical order `blocked`, `runnable`, `preempted`, and every observation carries
its `reason` together with the two raw `sched_switch` arguments it was derived
from, `prevTaskState` and `preempted`. The correlator recomputes the reason from
those arguments — `preempted` wins, then a zero task state is `runnable`, and any
other state is `blocked` — and marks a row whose reason disagrees, or is not one
the capture selected, `source-policy-or-target-mismatch`, exactly as it treats a
disagreeing admission threshold. A version 3 capture without `reasons`, a version
2 capture with them, and a version 2 observation that carries any of the three
fields are rejected the same way. Version 2 intervals read back as `unspecified`.

The reason describes the switch-out. `runnable` is how a user-space thread
preempted by the scheduler tick appears (it is switched out at an ordinary
`schedule()` on its return to user mode), and `preempted` is a preemption inside
the kernel; both are time spent waiting for a CPU. A `blocked` interval's duration
includes its run-queue delay between wakeup and switch-in; the next section splits
the two.

The report's `offCpuReasons` object, present for classified captures, lists the
selected reasons, the matched intervals, observed nanoseconds and their
sleeping/run-queue split for each, the kernel's per-reason switch-out counts — taken before its reason filter, so a
blocked-only capture still shows how often its threads were preempted — and the
count and total duration of intervals the filter rejected. The population
estimate covers the selected reasons only, since the kernel's eligibility counters
are taken after the reason filter.

## Sleeping and run-queue time

A `schemaVersion` 4 capture names where each interval's run-queue part comes from
in `captureStart.timeSplit` — `{"source": "schedInfo"}` or `{"source": "off"}` —
and the same object in the manifest and in the footer's `analysisInputs`, all
compared structurally. It is a block of its own beside `sampling` because it
changes what is measured, not which intervals are kept. Under `schedInfo` every
observation carries `runqueueNanos`: the growth of the scheduler's
`task_struct.sched_info.run_delay` between switch-out and switch-in, which the
kernel adds to on every run-queue wait whether or not delay accounting or
schedstats are switched on. The collector refuses `schedInfo` at prepare on a
kernel whose BTF has no such field; there is no silent fallback to `off`. The
kernel drops a reading only when the counter went backwards
(`runqueueInversions` in the `captureEnd` kernel counters). A row carrying
`runqueueNanos` under `off`, and a version 3 or older capture with a `timeSplit`
block or a version 4 one without it, are rejected.

The correlator applies one rule, and the report, the profile and every slice
share it:

- a `blocked` interval sleeps for its duration minus `runqueueNanos` and then
  waits `runqueueNanos` for a CPU: the run-queue part is the tail
  `[end - runqueueNanos, end]`;
- a `runnable` or `preempted` interval never left the run queue and is run-queue
  time throughout, whatever the reading. The scheduler's clock can lag a few
  microseconds when a running task departs, so such a reading may slightly
  exceed the duration;
- an interval without a reading (a version 2 or 3 capture, `off`, or a dropped
  reading), or a `blocked` one whose reading exceeds its duration, is **unsplit**.
  Nothing is guessed or clamped.

Each part is clipped to the `--from-ns`/`--to-ns` window separately, so sleeping,
run-queue and unsplit time add up exactly to every interval's clipped duration.
`offCpuReasons.matched` gives `sleepingNanos`, `runqueueNanos` and `unsplitNanos`
per reason, and `offCpuReasons.timeSplit` names the `source`, whether the split is
`available`, the rule, the unsplit intervals by cause (`withoutReading`,
`readingExceedsInterval`) and the kernel's `runqueueInversions`. The default
collapsed files, the audit files and the synthetic JFR carry the whole interval
as before; the split reaches the stack profile and the `stacks --time` slices.

A record's length prefix is checked against the record limit before any bytes
are read, so a corrupt length cannot drive an allocation. A truncated final
record is the partial-mode tail: its bytes are reported as
`ignoredTrailingBytes` and the prefix before it is analyzed; in complete mode it
is an error.

```sh
java -jar jonoffcpu-correlator.jar \
  --source jonoffcpu-capture.pb --jfr jonoffcpu-capture.jfr --output analysis
```

A stream whose only row is a `captureFinalized` footer with
`state: "profilerOnly"` comes from an agent run with
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
whose JFR sample was cut away as `sample-not-present-in-selected-jfr`.

The output directory must not exist. Every file is named `jonoffcpu-…` so it is
recognisable wherever the directory ends up. A successful run writes:

| File | Contents | Written when |
| --- | --- | --- |
| `jonoffcpu-report.json` | Capture counters, classifications, handler-delay percentiles, interpretation notes, the degradation ladder and the opt-in population estimate | always |
| `jonoffcpu-classified-records.jsonl` | Source and resolved JFR rows, including rejected and unmatched observations, with their classification | `--audit full` |
| `jonoffcpu-matches.jsonl` | Cookies, clipped intervals, delivery delays and whether the target-to-JFR thread mapping could be verified | `--audit matches` (default) or `full` |
| `jonoffcpu-offcpu-stacks.collapsed` | Root-first signal-delivery stacks weighted in integer microseconds instead of sample counts; exact nanosecond durations remain in the report; no inverse-probability scaling unless thinning applied (see **Degradation**). Every recorded interval, whatever its reason; when more than one reason contributes, each line starts with `[offcpu: <reason>]` (`--collapsed-reason-frame auto\|always\|never`) | `--format both` (default) or `collapsed` |
| `jonoffcpu-offcpu-stacks-<reason>.collapsed` | The same, restricted to one switch-out reason, without the reason frame | as above, and only when more than one reason contributes |
| `jonoffcpu-offcpu-profile.pb` | The stack profile; see **Stack profile** | `--profile-output true` (default) |
| `jonoffcpu-offcpu-synthetic.jfr` | An explicitly synthetic CPU-compatible view, using duration-quantized `jdk.ExecutionSample` events | `--format both` (default) or `jfr` |
| `jonoffcpu-complete.json` | Completion marker | last; a directory without it is not a complete analysis, and it is never written for a narrowed run (see **Degradation**) |

The original combined JFR is never rewritten. CPU, allocation, lock, wall and JVM
events in it remain available to other tools. Both derived formats are produced by default. Use `--format collapsed` or
`--format jfr` to select one.

`--audit full|matches|none` controls how much per-row audit output is written.
The CLI defaults to `matches`: `jonoffcpu-matches.jsonl` is written but
`jonoffcpu-classified-records.jsonl` is **not**, which is a backward-incompatible
change from earlier releases that always wrote both. Anything that reads
`jonoffcpu-classified-records.jsonl` — including
[`jonoffcpu-agent/tools/run-native-agent-interruptions.py`](../jonoffcpu-agent/tools/run-native-agent-interruptions.py)
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
`RecordingFile` API. The synthetic compatibility view has a different requirement:
it creates new historical `jdk.ExecutionSample` events with explicit thread and
stack constant-pool values. `RecordingFile.write` can only retain events from an
existing recording, and the public `EventFactory` API emits custom events from the
current JVM. The synthetic writer therefore remains a separate, patched and pinned
JMC writer implementation.

The synthetic JFR defaults to a 1 ms quantum (`--quantum-ns 1000000`) and caps
expansion at ten million events (`--max-synthetic-events`). Each event represents
one quantum of selected observed off-CPU time. Integer remainder carries forward
per resolved stack, so rendering timestamps are approximate and the final omitted
remainder is less than one quantum per stack. The report and a metadata event
record exact duration, represented duration and quantization error. This is a
compatibility view for CPU flamegraph tooling, not a recording of CPU execution.
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
`source-policy-or-target-mismatch`, so the weights are the policy's, never the
producer's word alone. The sum is accumulated in exact fixed-point arithmetic and
reported as `estimatedDurationNanos`, truncated to whole nanoseconds (at most one
nanosecond low); nothing passes through floating point, and the estimate never
scales the collapsed stacks or synthetic JFR. `admissionPolicy` names the policy
in effect. The report also keeps the durable source duration and the smaller
stack-matched duration separate, because missing or delayed Java stack delivery
does not erase a valid source interval.

The estimate is marked `available` only when source rows and the kernel/userspace
selection, receipt and write counters prove complete coverage and the capture did
not report source-loss conditions. Otherwise the observed source duration remains
visible, `estimatedDurationNanos` is null and `unavailableReasons` says which
coverage proof failed. This is an inverse-probability estimate under the recorded
random admission policy; it is not a confidence interval or an adjustment for
missing Java stacks. Under `proportional`, a rare short interval that was admitted
carries a weight of up to the full reference duration, so per-stack estimates for
rare stacks are noisy even when the total is unbiased.

## Stack profile

`jonoffcpu-offcpu-profile.pb` is a derived artifact from which any collapsed-stack
slice can be rendered again without re-reading the two inputs. Its format is
defined by [`docs/schema/jonoffcpu-profile.proto`](../docs/schema/jonoffcpu-profile.proto):
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
same. `JFR_NATIVE` arrived with `schema_version` 2; a schema 1 profile tags every
Java-stack frame `JAVA` and still reads, and a reader that predates schema 2
refuses a newer profile with `Unsupported stack profile schema` rather than
render it.

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
what was dropped. The profile embeds the run's report and the label frames its
collapsed lines start with. Partial mode writes no profile.

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
java -jar jonoffcpu-correlator.jar merge --profiles A,B,... --output M
java -jar jonoffcpu-correlator.jar export --profile P --format csv|jsonl --output E
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
a `sleeping` or `runqueue` slice leaves out.

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
`export` writes one row per entry with expanded stacks, the six split columns and,
last, `java_stack_kinds` (`javaStackKinds` in JSON Lines): each Java-stack
frame's kind, `java` or `native`, joined with `;` like `java_stack`. It is for
tools such as DuckDB.

## Degradation

A profile is not an audit log. When a capture does not fit the retained-bytes budget,
`--on-limit degrade` (the default) walks a ladder rather than refusing, and records
every step in the report's `degradation` object:

1. **Coarsen the synthetic quantum.** The event count for a quantum is the sum of
   per-stack floors and is known before the first event is written, so the quantum is
   raised until it fits. A synthetic JFR renders totals the report states exactly, so
   this costs granularity, not time. `requestedQuantumNanos` and
   `quantumRaisedForEventLimit` appear in the report's `syntheticJfr` block.
2. **Drop the audit outputs.** `--audit matches`, then `--audit none`. They cost the
   most and contribute nothing to the flame graph.
3. **Thin the source and reweight.** Each recorded interval is kept with probability
   `q`, decided by hashing its cookie, and the duration it contributes is scaled by the
   exact reciprocal of the realised probability. The result is an unbiased estimate of
   the same per-stack totals over the whole requested window. Because the cookie is the
   join key, an observation and its JFR sample are dropped together, so every count in
   the report describes one coherent subsample. `--thinning <q>` pins it and
   `--thinning-seed` changes the draw; `q = 1` is the default whenever the input fits.
4. **Narrow the window.** Analyse `[from, effectiveTo)` completely rather than the whole
   window approximately. Because both inputs are ordered on the delivery clock, a prefix
   is a complete analysis of a shorter window.
5. **Fail**, naming the limit, the steps already tried and the flag that would allow the
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
mark), `attempts`, the top-level `narrowedToNanos` (`null` unless the window was
narrowed, mirroring `peakRetainedBytes` so a consumer that reads only the top-level
object need not scan `stepsApplied` for the `narrow-window` entries), and `stepsApplied`:
one object per ladder step actually taken, each with a `step` name and the reason.

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
the calling JVM. Gson and the JMC writer are relocated implementation details and
do not appear in public method signatures.

## Explicit incomplete diagnostics

Strict correlation still requires both finalized artifacts. To inspect a closed,
possibly truncated pair after abrupt termination, select the separate partial path:

```sh
java -jar jonoffcpu-correlator.jar \
  --source interrupted.jsonl --jfr interrupted.jfr --output incomplete-analysis \
  --partial true
```

A successfully written partial diagnostic run exits with **status 2**. Complete
analysis exits with status 0; errors exit with status 1. Partial mode never promotes
its result to complete, even if the supplied inputs happen to be finalized.
Its new output directory holds a visibly different file set:

| File | Contents | Written when |
| --- | --- | --- |
| `INCOMPLETE-jonoffcpu-report.json` | Diagnostics for the observed prefix | always |
| `INCOMPLETE-jonoffcpu-classified-records.jsonl` | Classified source rows and JFR samples from the prefix | always |
| `INCOMPLETE-jonoffcpu-pairs.jsonl` | Exact-cookie pairs found in the prefix, with null delivery delays where the clock could not be verified | always |
| `INCOMPLETE-jonoffcpu-offcpu-stacks.collapsed` | Prefix stacks, each under an explicit incomplete root label that survives ordinary flame graph rendering | `--format collapsed` |
| `jonoffcpu-partial.json` | Marker with `state: incomplete` and `coverageComplete: false` | last |

There is never a `jonoffcpu-complete.json` or a synthetic JFR in partial mode.

Only fully decoded records are retained. A final source row without its newline
is discarded and its byte count reported; a malformed complete row remains an
error. Missing source end/footer or AP terminal stats, and a JFR decoding failure
at the unread tail, are reported explicitly. An unread suffix could contain a late
duplicate, so even coherent exact-cookie pairs are **provisional within the
recovered records**. Recovered duplicates invalidate every received copy. Counts
of missing/orphan samples describe the recovered prefix, not final delivery loss.

Unknown schemas, conflicting capture contexts, inconsistent final counters,
resource limits, same-size hash mismatches and changing input files remain hard
errors. A JFR shorter than an observed footer declares may be inspected, but its
full-file hash is not marked verified. The report retains actual input byte counts,
digests, observed terminal evidence and its verification state.

A missing footer does not establish a shared clock or a zero offset. Such results
have null handler delays and reject `--max-handler-delay-ns`; missing AP final
submitted counts are null, not zero. Explicit `--from-ns`/`--to-ns` can still clip
observed source intervals in their own monotonic clock. No wall-time mapping is
inferred. Population estimates and synthetic JFR output require the complete path
and are rejected in partial mode.

`SignalJfrExporter.visitPrefix` remains a lower-level, JDK-type-only parser API
for recovery tooling. It exposes parser terminal evidence without emitting a
successful end row; its strict `visit` and `export` methods are unchanged.
