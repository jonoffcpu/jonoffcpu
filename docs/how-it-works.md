# How jonoffcpu works

A capture has two halves that meet only offline. In the profiled JVM, an eBPF
program measures each off-CPU interval and a patched async-profiler records the
Java stack; after the run, the correlator joins the two by an exact key. This
page describes the pipeline, the two files it produces, and every file the
agent and the correlator write. The correlation contracts themselves are in
[OFFLINE.md](../jonoffcpu-correlator/OFFLINE.md).

- [Architecture](#architecture)
- [Why two files?](#why-two-files)
- [What the Java stack means](#what-the-java-stack-means)
- [Files jonoffcpu writes](#files-jonoffcpu-writes)

## Architecture

[![jonoffcpu architecture](images/architecture.svg)](https://raw.githubusercontent.com/jonoffcpu/jonoffcpu/main/docs/images/architecture.svg)

1. A [CO-RE eBPF program](../jonoffcpu-native/src/bpf/jonoffcpu_cookie.bpf.c)
   hooks `sched_switch` and `sched_exit_tp`. When a thread of the target JVM is
   switched out it records the timestamp and why the scheduler took it off the
   CPU; when the same thread is switched back in it has a complete off-CPU
   interval with its kernel and user native stacks.
2. Intervals of the selected switch-out reasons that pass the configured
   duration bounds and admission policy are written to a ring buffer together
   with a fresh 64-bit correlation key. The kernel then sends the resumed
   thread a signal whose payload is only that key.
3. The signal handler, in the bundled
   [`jonoffcpu/async-profiler`](https://github.com/jonoffcpu/async-profiler/tree/jonoffcpu-dev)
   fork, records a `profiler.SignalSample` event with the Java stack, the
   thread, and the key, in the same JFR recording that holds ordinary CPU,
   allocation, lock, and JDK events; see [The recording](recording.md).
4. A [native collector](../jonoffcpu-native/src/collector.rs) in the JVM
   process drains the ring buffer, resolves the native stacks, and appends each
   observation to the correlation stream. The Java agent finalizes that stream
   with a footer that binds the JFR's size and SHA-256.
5. [`OffCpuCorrelator`](../jonoffcpu-correlator/src/main/java/io/github/jonoffcpu/correlator/OffCpuCorrelator.java)
   runs offline. It joins each `SignalSample` to its observation by key,
   weights the Java stack by the kernel-measured duration, and writes a report,
   a collapsed-stack file, and a stack profile from which other slices can be
   rendered later.

Only the first two steps run for every context switch, and they run in the
kernel; everything else is paid only for intervals that are recorded. See
[Overhead and the observer effect](capture.md#overhead-and-the-observer-effect).

## Why two files?

A `profiler.SignalSample` says only that a sampled interval ended. Everything
that turns it into a measurement lives in the correlation stream:

| | JFR `profiler.SignalSample` | stream observation |
| --- | --- | --- |
| Correlation key | yes | yes |
| Java stack, captured after the thread resumed | yes | no |
| Interval start and end, i.e. the off-CPU duration | no | yes |
| Kernel and user native stacks at the scheduler endpoint | no | yes |
| Signal request result and kernel-side loss counters | no | yes |
| Footer binding the JFR's size and digest | no | yes |

Counting `SignalSample` events on their own would give a signal-frequency
profile, not an off-CPU profile: ten 1 ms parks and one 10 s socket read would
look identical. The JFR supplies *which Java code* was waiting; the stream
supplies *for how long* and *in which kernel path*. Observations that never
received a matching sample are kept and reported as loss, never dropped.

## What the Java stack means

The key proves that a sample and an observation describe the same interval. It
does not mean the two stacks were captured at the same instant. The native
stack belongs to the moment the thread was scheduled back in; the Java stack is
captured slightly later, when the signal is delivered. The report keeps the
kernel duration, the Java stack, and the delivery delay as separate values, and
`--max-handler-delay-ns` can reject samples that arrived too late to trust.

## Files jonoffcpu writes

Every file the agent or the correlator creates carries the `jonoffcpu` name,
so a capture is recognisable in a shared directory or a support bundle. The
capture files take their stem from `correlationOutput`; the analysis files are
named by the correlator.

Capture, written by the agent next to `correlationOutput` (the examples assume
`correlationOutput: /tmp/jonoffcpu-capture.pb`):

| File | Contents | Name comes from |
| --- | --- | --- |
| `jonoffcpu-capture.pb` | The correlation stream: `captureStart`, one `stack` per distinct native stack, one `observation` per recorded off-CPU interval referencing them by id, `captureEnd`, and the `captureFinalized` footer that binds the JFR's size and SHA-256. Length-delimited protobuf, defined by [`jonoffcpu-capture.proto`](../jonoffcpu-capture-codec/src/main/proto/jonoffcpu-capture.proto); `java -jar jonoffcpu-correlator.jar dump --source <file>` prints it as JSON Lines, one record per line | `correlationOutput` |
| `jonoffcpu-capture.manifest.json` | Audit manifest: configuration, resolved sampling policy, artifact paths, lifecycle state, completion flag, the native collector's replies, and the failure of an incomplete capture. The proto3 JSON of the `Manifest` message defined by [`jonoffcpu-manifest.proto`](../jonoffcpu-agent/src/main/proto/jonoffcpu-manifest.proto) | the stem of `correlationOutput` + `.manifest.json` |
| `jonoffcpu-capture.jfr` | The combined async-profiler recording, including `profiler.SignalSample` events | the `file=` option in `asyncProfilerOptions`; defaults to the stem of `correlationOutput` + `.jfr` |

Analysis, written by the correlator into `--output`:

| File | Contents |
| --- | --- |
| `jonoffcpu-report.json` | Lifecycle, loss, classification, duration, delivery-delay accounting, and the optional population estimate. Defined by `Report` in [`jonoffcpu-report.proto`](../jonoffcpu-correlator/src/main/proto/jonoffcpu-report.proto) |
| `jonoffcpu-offcpu-stacks.collapsed` | Java stacks weighted in microseconds of off-CPU time, for flame graphs. Every recorded interval; when the capture mixes switch-out reasons, each line starts with an `[offcpu: <reason>]` frame |
| `jonoffcpu-offcpu-stacks-<reason>.collapsed` | The same, one file per switch-out reason, written only when the capture mixes reasons |
| `jonoffcpu-offcpu-profile.pb` | The stack profile: every distinct Java, kernel and user stack once, with interval counts and observed and estimated durations per stack, reason and thread. Any other collapsed slice is rendered from it without re-correlating; see [Slice and filter with the stack profile](analysis.md#slice-and-filter-with-the-stack-profile). Defined by [`jonoffcpu-profile.proto`](../jonoffcpu-correlator/src/main/proto/jonoffcpu-profile.proto) |
| `jonoffcpu-summary.md`, `jonoffcpu-summary.json` | The analysis digest, for people and AI agents: when and on what machine and JVM the capture was recorded, then the blocked time ranked, with `--app` by the application method that waited, by application root and by application method, without it by leaf and by pool; where the time went, with shares; coverage and losses; the terms it uses; and each table's command as a shell block. Waits for work are left out (see `--waiting-from`). The Markdown is rendered from the JSON. `--summary-output false` skips it; a failure to write it is reported in the report and never fails the correlation |
| `jonoffcpu-classified-records.jsonl` | Every source row and every JFR sample with its classification, for auditing. Written only with `--audit full` |
| `jonoffcpu-matches.jsonl` | Every exact-cookie match with its clipped interval and delivery delay. Written by the default `--audit matches`, and by `--audit full`; `--audit none` writes neither audit file |
| `jonoffcpu-complete.json` | Written last, only after all inputs and outputs validate. Never written when the run narrowed its window (see `--on-limit` below) |

Every JSON file the correlator writes, and everything it prints as JSON, is a
protobuf message printed in the
[proto3 JSON mapping](https://protobuf.dev/programming-guides/json/): the report,
the audit rows, the markers and the partial-mode files are defined in
[`jonoffcpu-report.proto`](../jonoffcpu-correlator/src/main/proto/jonoffcpu-report.proto),
and the digest, `stacks --summary`, `top --format json` and `export --format
jsonl` in [`jonoffcpu-analysis.proto`](../jonoffcpu-correlator/src/main/proto/jonoffcpu-analysis.proto).
Names are lowerCamelCase, 64-bit integers are decimal strings, exact decimals
such as shares and probabilities are decimal strings, enums print their value
names (`OFF_CPU_REASON_BLOCKED`), and a field that is unset is left out. The
`.json` files are indented; the `.jsonl` files hold one message per line.

When the retained-bytes budget is reached, `--on-limit degrade` (the default)
trades away thinner outputs before it trades away coverage: it drops the audit
outputs, then thins the source with an exact inverse-probability reweighting,
and only as a last resort narrows the analysis window. Thinning still analyses
the whole requested window — with ordinary output names,
`jonoffcpu-complete.json`, and exit status 0 — because it is a stated estimator
over what was asked for. Narrowing the window instead analyses a shorter window
*completely*, and is labelled as visibly incomplete: `INCOMPLETE-jonoffcpu-*`
names, a `jonoffcpu-narrowed.json` marker instead of `jonoffcpu-complete.json`,
and exit status 2. See OFFLINE.md's
[Degradation](../jonoffcpu-correlator/OFFLINE.md#degradation) for the full
ladder and the report's `degradation` object.

`--partial true` inspects an interrupted capture and writes a visibly different
set instead: `INCOMPLETE-jonoffcpu-report.json`,
`INCOMPLETE-jonoffcpu-classified-records.jsonl`, `INCOMPLETE-jonoffcpu-pairs.jsonl`,
optionally `INCOMPLETE-jonoffcpu-offcpu-stacks.collapsed`, and the marker
`jonoffcpu-partial.json`. It never writes `jonoffcpu-complete.json`.
