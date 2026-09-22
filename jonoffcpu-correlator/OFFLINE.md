# Offline signal correlation

The Java correlator takes two finalized files: the JONOFFCPU correlation stream and the
original async-profiler JFR. The controller appends a `captureFinalized` footer
after closing the source writer and checking async-profiler finalization. That
footer binds the source prefix and JFR hashes, capture identity and stop counters.
No separate manifest or exporter subprocess is required. Stored artifact paths
are advisory, so recordings can be moved together or supplied from new locations.

The stream is `schemaVersion` 2 NDJSON: a `captureStart` header, a `stack` record
for each distinct native stack, one `observation` per recorded off-CPU interval,
a `captureEnd`, and the footer. Stacks are interned: an observation names its two
stacks through `kernelStackId`/`userStackId`, and a stack record always precedes
the first observation that references it. When the kernel could not produce a
stack there is no record and the observation carries `kernelStackError` or
`userStackError` instead. An unannounced reference, a duplicate `stackId` and an
unexplained negative id are all hard errors. The classified records re-expand
both stacks, so an audit row remains self-contained.

```sh
java -jar jonoffcpu-correlator.jar \
  --source jonoffcpu-capture.ndjson --jfr jonoffcpu-capture.jfr --output analysis
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
| `jonoffcpu-report.json` | Capture counters, classifications, handler-delay percentiles, interpretation notes and the opt-in population estimate | always |
| `jonoffcpu-classified-records.jsonl` | Source and resolved JFR rows, including rejected and unmatched observations, with their classification | always |
| `jonoffcpu-matches.jsonl` | Cookies, clipped intervals, delivery delays and whether the target-to-JFR thread mapping could be verified | always |
| `jonoffcpu-offcpu-stacks.collapsed` | Root-first signal-delivery stacks weighted in integer microseconds instead of sample counts; exact nanosecond durations remain in the report; no inverse-probability scaling | `--format both` (default) or `collapsed` |
| `jonoffcpu-offcpu-synthetic.jfr` | An explicitly synthetic CPU-compatible view, using duration-quantized `jdk.ExecutionSample` events | `--format both` (default) or `jfr` |
| `jonoffcpu-complete.json` | Completion marker | last; a directory without it is not a complete analysis |

The original combined JFR is never rewritten. CPU, allocation, lock, wall and JVM
events in it remain available to other tools. Both derived formats are produced by default. Use `--format collapsed` or
`--format jfr` to select one. Reports and classified records are always retained.

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

Default admission limits are one million total source/JFR rows, 1 MiB per source
line, 4,096 frames per stack record and per JFR sample, and 256 MiB of
conservative decoded-object accounting.
Use `--max-rows` or `--max-retained-bytes` to change the corresponding limits.
These are admission budgets, not a hard JVM heap limit. Failures reject the
analysis rather than return a truncated successful result.

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
