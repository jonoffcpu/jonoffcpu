![#jonoffcpu](docs/images/jonoffcpu-banner.jpg)

`jonoffcpu` is an off-CPU profiler for JVM applications on Linux. It measures how
long each thread was off-CPU, using the kernel scheduler as the source of truth,
and attributes that time to the Java stack that was waiting. The result is an
off-CPU flame graph whose widths are real durations, recorded alongside an
ordinary async-profiler JFR.

## Table of contents

- [What is off-CPU profiling?](#what-is-off-cpu-profiling)
- [The problem](#the-problem)
- [How it works](#how-it-works)
  - [Why two files?](#why-two-files)
  - [Files jonoffcpu writes](#files-jonoffcpu-writes)
  - [What the Java stack means](#what-the-java-stack-means)
- [Requirements](#requirements)
- [Quick start](#quick-start)
  - [1. Get the JARs](#1-get-the-jars)
  - [2. Record](#2-record)
  - [3. Correlate](#3-correlate)
  - [4. Render the flame graph](#4-render-the-flame-graph)
- [Configuration](#configuration)
  - [Agent options](#agent-options)
  - [Choosing what to sample](#choosing-what-to-sample)
  - [Overhead and the observer effect](#overhead-and-the-observer-effect)
  - [Turning jonoffcpu off without removing it](#turning-jonoffcpu-off-without-removing-it)
  - [Correlator options](#correlator-options)
  - [Using the artifacts as libraries](#using-the-artifacts-as-libraries)
- [Building from source](#building-from-source)
- [Repository layout](#repository-layout)
- [License](#license)

## What is off-CPU profiling?

A CPU executes one thread at a time, and the Linux scheduler decides which.
Every hand-over is a context switch, visible to the kernel as the
`sched_switch` tracepoint: the outgoing thread is *switched out*, the incoming
one *switched in*. A thread is **on-CPU** from a switch-in to its next
switch-out and **off-CPU** the rest of the time. An off-CPU interval holds up
to two scheduler states. The thread is **sleeping** while it is not runnable
and waits for a wakeup — a futex, data on a socket, a disk read, a timer — and
it is **runnable** from the `sched_wakeup` that ends the sleep until a CPU is
free to switch it in. A thread that is preempted skips the sleep: it stays
runnable and only waits in the run queue. Off-CPU profiling records, for each
such interval, its exact duration and the code path that was executing when
the thread was switched out. CPU profilers sample only the on-CPU state; a
wall-clock sampler notices at each tick that a thread is off-CPU, but neither
how long the interval lasted nor whether the thread was sleeping or merely
queued.

![Thread states seen by the scheduler](docs/images/offcpu-timeline.svg)

The kernel sees the *mechanism* of a wait, never its *reason*. A thread never
blocks "on the database": with a synchronous JDBC driver it sleeps in a socket
read; with an asynchronous client, a connection pool or any `Future.get()` it
parks on a monitor or condition variable, a `futex`, while another thread
does the I/O. The mechanism and the duration are what the kernel can prove;
the reason lives in the stack of the code that called into the wait.

| What the code is doing | Where the Java thread waits | What the kernel sees |
| --- | --- | --- |
| Synchronous JDBC query | `SocketInputStream.read` (`NioSocketImpl`) | `read`/`recv` or `poll` on the socket, sleeping until data arrives |
| Async client, `CompletableFuture.get()`, connection pool | `LockSupport.park` | `futex` wait; another thread performs the I/O |
| Contended `synchronized` or `ReentrantLock` | monitor enter / `park` | `futex` wait |
| `Thread.sleep`, timed `wait` | `park` with a timeout | `futex` wait armed with a timer |

That split is why an off-CPU profile of a JVM needs both stacks: the kernel
stack and the interval come from the scheduler, the Java stack supplies the
cause, and `jonoffcpu` exists to pair each kernel-measured interval with a
Java stack.

This matters because in most services request latency is not CPU time. A
request that takes 200 ms may burn 5 ms of CPU and spend the rest sleeping on
a socket for a query result, parked on a future, or queued behind a busy CPU.
A CPU flame graph shows those 5 ms in detail and nothing about the other
195 ms. An off-CPU profile inverts that: it attributes the off-CPU time to the
stack that was waiting, so the 195 ms show up under the code that issued the
query, took the lock, or called the remote service. Rendered as an
[off-CPU flame graph](https://www.brendangregg.com/FlameGraphs/offcpuflamegraphs.html),
frame widths are total off-CPU duration instead of sample counts, and the
widest towers are the waits worth investigating. CPU and off-CPU profiles
together account for a thread's whole lifetime, which is what Brendan Gregg's
[Thread State Analysis](https://www.brendangregg.com/tsamethod.html) method
asks for: explain latency by the states that dominate it, not by the one state
a CPU profiler happens to see.

Off-CPU time is measured, not sampled: the scheduler records the exact moment
a thread left the CPU and the exact moment it returned, so every interval is a
real duration and the flame graph's widths are microseconds of off-CPU time.
`jonoffcpu` measures the whole interval, from switch-out to switch-in, so
run-queue delay under CPU contention is included alongside sleeping.

Further reading:

- [Linux tracepoints](https://www.kernel.org/doc/html/latest/trace/events.html)
  and [`sched(7)`](https://man7.org/linux/man-pages/man7/sched.7.html): the
  `sched_switch` and `sched_wakeup` events this definition rests on, and the
  scheduler's view of task states.
- [Off-CPU Analysis](https://www.brendangregg.com/offcpuanalysis.html): the
  method, its overheads, and how it complements CPU profiling.
- [Off-CPU Flame Graphs](https://www.brendangregg.com/FlameGraphs/offcpuflamegraphs.html):
  reading and generating flame graphs whose widths are off-CPU durations.
- [The TSA Method](https://www.brendangregg.com/tsamethod.html): thread state
  analysis as a systematic way to account for all of a thread's time.

## The problem

CPU profilers show where a program burns cycles. They say nothing about the
time a thread spends *not* running: waiting on a lock, a socket, a disk, a
`park()`, or simply a busy run queue. In a typical service that waiting time,
not CPU time, is what shows up as latency.

Existing tools each see half of the picture:

- **Kernel tools** such as BCC's [`offcputime`](https://github.com/iovisor/bcc/blob/master/tools/offcputime.py)
  know exactly when a thread went
  off CPU and when it came back, and can capture the kernel stack. They cannot
  walk JIT-compiled Java frames, so the Java side of the stack is missing or
  guessed from symbol maps.
- **JVM profilers** such as [async-profiler](https://github.com/async-profiler/async-profiler)
  walk Java stacks accurately, but
  their wall-clock mode is a timer-driven sampler. It sees that a thread was
  off-CPU at each tick, not how long the interval actually lasted, and it
  cannot tell sleeping from being runnable but descheduled.

`jonoffcpu` combines both: the kernel measures the interval, async-profiler
captures the Java stack, and a 64-bit key ties each measurement to its stack.

## How it works

![jonoffcpu architecture](docs/images/architecture.svg)

1. A [CO-RE eBPF program](jonoffcpu-native/src/bpf/jonoffcpu_cookie.bpf.c)
   hooks `sched_switch` and `sched_exit_tp`. When a
   thread of the target JVM is switched out it records the timestamp; when the
   same thread is switched back in it has a complete off-CPU interval with its
   kernel and user native stacks.
2. Intervals that pass the configured duration bounds and admission policy
   are written to a ring buffer together with a fresh 64-bit correlation key.
   The kernel then sends the resumed thread a signal whose payload is only that
   key.
3. The signal handler, in the bundled
   [`lhotari/async-profiler`](https://github.com/lhotari/async-profiler/tree/jonoffcpu-dev)
   fork, records a
   `profiler.SignalSample` event with the Java stack, the thread, and the key,
   in the same JFR recording that holds ordinary CPU, allocation, lock, and
   JDK events.
4. A [native collector](jonoffcpu-native/src/collector.rs) in the JVM process
   drains the ring buffer, resolves the
   native stacks, and appends each observation to the correlation stream.
   The Java agent finalizes that stream with a footer that binds the JFR's size
   and SHA-256.
5. [`OffCpuCorrelator`](jonoffcpu-correlator/src/main/java/io/github/lhotari/jonoffcpu/offline/OffCpuCorrelator.java)
   runs offline. It joins each `SignalSample` to its
   observation by key, weights the Java stack by the kernel-measured duration,
   and writes a report, a collapsed-stack file, and a synthetic JFR.

### Why two files?

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

### Files jonoffcpu writes

Every file the agent or the correlator creates carries the `jonoffcpu` name,
so a capture is recognisable in a shared directory or a support bundle. The
capture files take their stem from `correlationOutput`; the analysis files are
named by the correlator.

Capture, written by the agent next to `correlationOutput` (the examples assume
`correlationOutput: /tmp/jonoffcpu-capture.pb`):

| File | Contents | Name comes from |
| --- | --- | --- |
| `jonoffcpu-capture.pb` | The correlation stream: `captureStart`, one `stack` per distinct native stack, one `observation` per recorded off-CPU interval referencing them by id, `captureEnd`, and the `captureFinalized` footer that binds the JFR's size and SHA-256. Length-delimited protobuf, defined by [`docs/schema/jonoffcpu-capture.proto`](docs/schema/jonoffcpu-capture.proto); `java -jar jonoffcpu-correlator.jar --dump --source <file>` prints it as NDJSON | `correlationOutput` |
| `jonoffcpu-capture.manifest.json` | Audit manifest: configuration, resolved sampling policy, artifact paths, lifecycle state, completion flag | the stem of `correlationOutput` + `.manifest.json` |
| `jonoffcpu-capture.jfr` | The combined async-profiler recording, including `profiler.SignalSample` events | the `file=` option in `asyncProfilerOptions`; defaults to the stem of `correlationOutput` + `.jfr` |

Analysis, written by the correlator into `--output`:

| File | Contents |
| --- | --- |
| `jonoffcpu-report.json` | Lifecycle, loss, classification, duration, delivery-delay accounting, and the optional population estimate |
| `jonoffcpu-offcpu-stacks.collapsed` | Java stacks weighted in microseconds of off-CPU time, for flame graphs |
| `jonoffcpu-offcpu-synthetic.jfr` | The same data as duration-quantized `jdk.ExecutionSample` events, for JFR viewers |
| `jonoffcpu-classified-records.jsonl` | Every source row and every JFR sample with its classification, for auditing |
| `jonoffcpu-matches.jsonl` | Every exact-cookie match with its clipped interval and delivery delay |
| `jonoffcpu-complete.json` | Written last, only after all inputs and outputs validate |

`--partial true` inspects an interrupted capture and writes a visibly different
set instead: `INCOMPLETE-jonoffcpu-report.json`,
`INCOMPLETE-jonoffcpu-classified-records.jsonl`, `INCOMPLETE-jonoffcpu-pairs.jsonl`,
optionally `INCOMPLETE-jonoffcpu-offcpu-stacks.collapsed`, and the marker
`jonoffcpu-partial.json`. It never writes `jonoffcpu-complete.json`.

### What the Java stack means

The key proves that a sample and an observation describe the same interval. It
does not mean the two stacks were captured at the same instant. The native
stack belongs to the moment the thread was scheduled back in; the Java stack is
captured slightly later, when the signal is delivered. The report keeps the
kernel duration, the Java stack, and the delivery delay as separate values, and
`--max-handler-delay-ns` can reject samples that arrived too late to trust.

## Requirements

- 64-bit Linux with BTF, eBPF task storage, the `tp_btf/sched_exit_tp`
  tracepoint, and the `bpf_send_signal_task` helper. The agent checks the
  running kernel and fails closed if any of these is missing.
- Privileges to load and attach the BPF programs.
- Java 17 or newer for the agent; Java 21 or newer for the correlator.
- On Java 24 and newer, add `--sun-misc-unsafe-memory-access=allow` to the JVM
  being profiled and to the correlator. The bundled protobuf codec that reads
  and writes the capture stream uses `sun.misc.Unsafe`, which the JDK reports
  once per JVM as a terminally deprecated call; the flag silences that warning
  and changes nothing else.

The agent JAR is self-contained. It embeds the JNI bridge, the native
collector, and the patched async-profiler for Linux x86-64 and arm64, each
linked against both glibc and musl (Alpine), verifies them against a SHA-256
manifest, and extracts them to a private temporary directory at startup.
Nothing needs to be installed on the host. The agent picks the glibc or musl
bundle from the C library mapped into the running JVM; on an unusual host,
`-Dio.github.lhotari.jonoffcpu.nativeLibc=glibc` or `=musl` selects it
explicitly. If the temporary directory is mounted `noexec`, point
`-Dio.github.lhotari.jonoffcpu.nativeWorkDir` at an executable location.

## Quick start

### 1. Get the JARs

Download the latest
[GitHub Release](https://github.com/lhotari/jonoffcpu/releases), which
contains the three JARs:

```sh
gh release download -p '*.jar' -R lhotari/jonoffcpu
```

Pass a tag such as `v0.1.0` after `download` to pick a specific release
instead of the latest one.

| JAR | What it is | When you use it |
| --- | --- | --- |
| `jonoffcpu-agent.jar` | The Java agent. Bundles the eBPF collector, the JNI bridge, and the patched async-profiler for Linux x86-64 and arm64, and drives the whole capture lifecycle. | Attached to the JVM being profiled with `-javaagent`. |
| `jonoffcpu-correlator.jar` | The offline correlator CLI. Joins the combined JFR with the correlation stream, verifies integrity, and writes derived outputs such as collapsed stacks and a synthetic JFR. | Run after the capture, on any machine with Java 21+. |
| `jfr-converter.jar` | async-profiler's [`jfrconv`](https://github.com/async-profiler/async-profiler/blob/master/docs/ConverterUsage.md), built from the pinned fork so that it understands the `profiler.Signal*` events and accepts `--units` to label the flame graph in microseconds. | Renders the correlator's collapsed stacks as an off-CPU flame graph whose widths are microseconds of off-CPU time. |

The examples below assume all three JARs are in the current directory.

### 2. Record

Create `jonoffcpu.yaml`:

```yaml
correlationOutput: /tmp/jonoffcpu-capture.pb
asyncProfilerOptions: event=cpu,alloc=2m,jfrsync=profile,file=/tmp/jonoffcpu-capture.jfr
sampling:
  minOffCpuMicros: 100
  admission:
    policy: proportional
    recordAllAboveMicros: 10000
```

`asyncProfilerOptions` is passed to async-profiler unchanged, so any of its
[usual events](https://github.com/async-profiler/async-profiler/blob/master/docs/ProfilingModes.md)
can be recorded alongside the off-CPU samples. `recordAllAboveMicros: 10000`
records every wait of 10 ms or longer and samples shorter ones in proportion
to their length, so long waits are never missed and the run-queue noise does
not swamp the capture; `minOffCpuMicros: 100` drops the sub-100 µs context
switches entirely. See [Choosing what to sample](#choosing-what-to-sample).
Start the application:

```sh
java -javaagent:jonoffcpu-agent.jar=jonoffcpu.yaml -jar application.jar
```

The capture finishes when the JVM exits normally, or earlier if the application
calls `io.github.lhotari.jonoffcpu.agent.SignalCaptureAgent.stop()`. Abrupt
termination leaves visibly incomplete artifacts rather than a plausible-looking
partial result.

### 3. Correlate

```sh
java -jar jonoffcpu-correlator.jar \
  --source /tmp/jonoffcpu-capture.pb \
  --jfr /tmp/jonoffcpu-capture.jfr \
  --output /tmp/jonoffcpu-analysis
```

The output directory then holds `jonoffcpu-report.json`,
`jonoffcpu-offcpu-stacks.collapsed`, `jonoffcpu-offcpu-synthetic.jfr`, the
row-level audit files, and `jonoffcpu-complete.json` as the last file written;
[Files jonoffcpu writes](#files-jonoffcpu-writes) describes each one.

### 4. Render the flame graph

```sh
java -jar jfr-converter.jar --title "Off-CPU time" --units µs \
  /tmp/jonoffcpu-analysis/jonoffcpu-offcpu-stacks.collapsed \
  /tmp/jonoffcpu-analysis/offcpu.html
```

Open `offcpu.html` in a browser. Frame widths are proportional to the total
off-CPU time observed under that Java stack. The collapsed weights are
microseconds of off-CPU time, and `--units µs` makes the flame graph say so
instead of counting "samples"; that option is a fork addition, so use the
provided `jfr-converter.jar` rather than a stock `jfrconv`. Add `--reverse` to see which
blocking calls dominate regardless of caller, or `-I`/`-X` regular expressions
to keep or drop stacks by frame. Any tool that reads the collapsed-stack
format, such as [`flamegraph.pl`](https://github.com/brendangregg/FlameGraph)
with `--countname=µs`, works on the same file.

The synthetic JFR opens directly in
[JDK Mission Control](https://jdk.java.net/jmc/) and other JFR viewers.

## Configuration

### Agent options

| Option | Meaning |
| --- | --- |
| `correlationOutput` | Required. Path of the correlation stream. Must not exist yet. Its stem names the sibling `.manifest.json` and, when `file=` is absent, the `.jfr`; see [Files jonoffcpu writes](#files-jonoffcpu-writes). |
| `asyncProfilerOptions` | Required. async-profiler options, including one absolute `file=` path for the JFR. |
| `sampling` | Required. Which off-CPU intervals are recorded; see [Choosing what to sample](#choosing-what-to-sample). |
| `sampling.minOffCpuMicros` | Optional strict lower bound on the off-CPU duration, in microseconds. |
| `sampling.maxOffCpuMicros` | Optional strict upper bound on the off-CPU duration, in microseconds. |
| `sampling.admission.policy` | Required. `proportional`, `uniform`, or `none`. |
| `sampling.admission.recordAllAboveMicros` | `proportional` only. Intervals at least this long are always recorded; shorter ones with probability `length / recordAllAboveMicros`. |
| `sampling.admission.probability` | `uniform` only. `"0.000"` through `"1.000"`; every eligible interval is recorded with this probability. `"0"` is the same as policy `none`. Quote the value to keep its exact spelling in the capture metadata. |
| `signalDelivery` | `queued` (default) uses a dedicated real-time signal and never merges notifications. `coalescing` uses a standard signal and may merge them, trading lost samples for a bounded pending-signal queue. |
| `nativeStopTimeoutMillis` | Budget for detaching the eBPF source and draining the ring buffer at stop. Default 30000. |
| `deliveryGraceMillis` | Time allowed after detach for already-requested signals to arrive. Default 100. |
| `shutdownTimeoutMillis` | How long the JVM shutdown hook waits for the capture to finalize. Default 10000. |

### Choosing what to sample

Every off-CPU interval costs the same to *measure* (the kernel does that
anyway), but *recording* one costs a signal to the thread and a Java stack
walk, and that cost lands on the thread being measured. Sampling exists to
keep that disturbance small enough that the profile still describes the
application rather than the profiler; see
[Overhead and the observer effect](#overhead-and-the-observer-effect). The
`sampling` block decides which measured intervals are worth recording. All
decisions are made in the kernel after the interval's duration is known, in
this order:

1. **`minOffCpuMicros` / `maxOffCpuMicros`** — strict bounds. Intervals outside
   them are never recorded and never counted.
2. **`admission.policy`** — which of the remaining intervals to record:
   - `proportional`: an interval of at least `recordAllAboveMicros` is always
     recorded; a shorter one is recorded with probability
     `length / recordAllAboveMicros`. With `10000`, a 1 ms wait has a 10 %
     chance and a 10 µs wait 0.1 %.
   - `uniform`: every interval is recorded with the same `probability`.
   - `none`: nothing is recorded and no eBPF program is loaded (see below).

Each policy has exactly one parameter; giving `probability` to `proportional`
or `recordAllAboveMicros` to `uniform` is a configuration error, as are bounds
with `none`. There is no default: the block is required so that a capture
without off-CPU data is always a deliberate choice.

**Which one to use?**

- *"Show me the slow waits."* `proportional` with `recordAllAboveMicros` set
  to the duration you never want to miss, say `10000` (10 ms). Every wait of
  10 ms or more is recorded; shorter waits still appear with the right total
  width but do not flood the capture. Below the reference the chance of
  recording an interval grows with its length, so a millisecond of off-CPU
  time yields the same expected number of samples whether it was one 1 ms
  wait or ten 100 µs waits: samples follow off-CPU *time*, which is what a
  duration-weighted flame graph wants, and the recording rate is bounded by
  the total off-CPU time divided by `recordAllAboveMicros` no matter how many
  short waits the application makes. The cost does scale with concurrency:
  when many threads wake from long waits at once, each of them signals.
- *"I only want the tail and nothing else."* Use `minOffCpuMicros` as the
  cutoff. Everything below it is invisible rather than under-sampled, and the
  report's totals describe only the intervals above the bound. A
  `minOffCpuMicros` at or above `recordAllAboveMicros` degenerates to
  "record every eligible interval".
- *"I want everything and can afford it."* `uniform` with `probability: "1"`.
  Expect the signal rate to track the context-switch rate.
- *"Uniform, cheap, statistical."* `uniform` with `probability: "0.01"` is a
  plain 1-in-100 sample of intervals. Long waits are missed 99 times out of
  100, so pair it with `minOffCpuMicros` to stop short intervals from
  dominating.

**Reading the results.** The collapsed stacks and flame graph always show the
durations that were actually observed, so a wait recorded under
`proportional` appears at its true length. Every observation row records the
exact admission threshold the kernel drew against, and the correlator's
`--estimate-population true` reweights the observed intervals by those
thresholds to estimate the total off-CPU time of all eligible intervals,
including the ones the sampler skipped: under `proportional`, each recorded
interval shorter than `recordAllAboveMicros` stands in for
`recordAllAboveMicros` worth of waiting and each longer one for itself. That
estimate is exact arithmetic on the recorded thresholds, not a heuristic, but
a single rare short wait that happened to be caught carries a large weight, so
treat per-stack estimates for rare stacks as noisy.

### Overhead and the observer effect

Scheduler events are frequent — a busy service switches threads tens of
thousands of times per second, in extreme cases millions — so, as Brendan
Gregg's
[Off-CPU Analysis](https://www.brendangregg.com/offcpuanalysis.html) warns, a
tracer that costs even a little per event, or that ships every event to user
space, quickly becomes the largest thing on the machine. `jonoffcpu` is built
so that the unavoidable per-switch cost stays in the kernel and everything
else is paid only for intervals that are actually recorded:

| Stage | Applies to | Cost | Where it lands |
| --- | --- | --- | --- |
| eBPF switch-out / switch-in hooks | every context switch of the target process's threads | a task-storage lookup, a timestamp, the bounds check and the admission draw | the switching thread, in the kernel; nothing leaves the kernel for intervals the bounds or the admission policy reject |
| Ring-buffer record + signal | each recorded interval | a 120-byte kernel record and a signal queued to the thread that just resumed | the resumed thread, when the signal is delivered |
| Java stack walk | each recorded interval | async-profiler's signal handler walks the Java stack and writes the `SignalSample` event | the resumed thread, before it continues its own work |
| Drain and write | each recorded interval | a protobuf record of roughly 120 bytes | the collector's own thread; records are buffered (256 KiB) and flushed after each drain batch, at most every 5 ms, and fsynced only at stop |
| Symbolize | each **distinct** native stack | one stack-map lookup and per-frame symbol resolution, written once as a `stack` record | the collector's own thread, on first sight of that stack |

The second and third stages are the observer effect: the signal and the stack
walk are on-CPU time and latency the application would not otherwise have,
and they can themselves cause context switches. Recording every interval of a
busy service would therefore change the very thing being measured. The
admission policy bounds the recording rate, and with `proportional` it bounds
it in proportion to off-CPU *time* rather than event *count*, so the intervals
that dominate the profile are always recorded while the short, numerous ones —
whose recording cost would exceed their information — are sampled. The
capture's `captureEnd` counters show what the kernel saw against what it
recorded: `switchOuts` is every switch of the process's threads,
`eligibleIntervals` the ones inside the bounds, and `selectedIntervals` the
ones recorded. If `selectedIntervals` is a large fraction of `switchOuts`,
raise `recordAllAboveMicros` or `minOffCpuMicros`.

Data volume follows the same rule. Stacks are interned: each distinct kernel
and user stack is symbolized and written once as a `stack` record, and every
observation references it by id, so a record costs about 120 bytes no matter
how deep the stack is. A thousand recorded intervals per second write about
0.12 MB/s of correlation stream, and the same knobs bound disk usage and
correlation time. Interning and the binary encoding together took a measured
smoke capture from 3.3 KB to 116 bytes per observation.

Feedback loops — the profiler observing its own waits — are closed in the
kernel: the collector's drain thread and the agent's controller thread report
their thread IDs at setup and the eBPF program never records their intervals.
async-profiler's own threads are ordinary threads of the process and do appear
when they wait; a `wall=` sampler, for example, shows up under
`libasyncProfiler.so` frames sleeping for its interval. Leave `wall=` out of
`asyncProfilerOptions` unless wall-clock samples are wanted alongside the
measured intervals.

### Turning jonoffcpu off without removing it

Set `sampling.admission.policy: none` to run plain async-profiler through the
same `-javaagent` line. The agent then loads no eBPF program, negotiates no signal,
and needs no BPF privileges; async-profiler is started with
`asyncProfilerOptions` exactly as given, so the JFR contains only its ordinary
events. The correlation path still receives a one-line stream whose
`captureFinalized` row has `state: "profilerOnly"`, so the correlator reports
that there is nothing to correlate instead of failing on a missing file. This
lets a deployment keep one configuration and flip off-CPU capture on or off.

The agent can also be started programmatically with
`SignalCaptureAgent.start(path)` or attached at runtime through its
`Agent-Class` entry point. See [jonoffcpu-agent/README.md](jonoffcpu-agent/README.md).

### Correlator options

| Option | Meaning |
| --- | --- |
| `--from`, `--to` | Select samples by JFR event time. Accepts ISO-8601 timestamps, epoch milliseconds, durations, or offsets from the recording start such as `30s` and `2m`. |
| `--max-handler-delay-ns` | Reject matches whose Java stack was captured more than this long after the interval ended. |
| `--from-ns`, `--to-ns` | Clip matched intervals to a window in the source monotonic clock. |
| `--format collapsed\|jfr` | Produce only one of the two derived outputs. |
| `--partial-jfr true` | Accept a JFR that another tool has cut. Source rows without a sample in the cut JFR are reported as expected omissions instead of loss. |
| `--partial true` | Inspect an interrupted capture. Writes `INCOMPLETE-jonoffcpu-*` files and a `jonoffcpu-partial.json` marker, exits with status 2, and never writes `jonoffcpu-complete.json` or the synthetic JFR. |

By default the correlator refuses a JFR whose size or SHA-256 differs from the
one recorded in the stream's footer. The full output, integrity, and weighting
contracts are in [jonoffcpu-correlator/OFFLINE.md](jonoffcpu-correlator/OFFLINE.md).

### Using the artifacts as libraries

```kotlin
dependencies {
    implementation("io.github.lhotari:jonoffcpu-agent:0.1.0")
    implementation("io.github.lhotari:jonoffcpu-correlator:0.1.0")
    implementation("io.github.lhotari:jonoffcpu-jfr-converter:0.1.0")
}
```

The correlator exposes
`OffCpuCorrelator.correlate(sourcePath, jfrPath, outputDirectory)` using JDK
types only. The published Gradle module metadata and POM point at the shaded,
self-contained JARs, so no further dependencies are needed.
`jonoffcpu-jfr-converter` is the converter from the pinned async-profiler fork
under the upstream `one.convert` and `one.jfr` packages, built for Java 21 and
licensed under Apache-2.0 like async-profiler itself.

## Building from source

Clone with the async-profiler submodule and build for the current host
architecture:

```sh
git clone --recurse-submodules https://github.com/lhotari/jonoffcpu.git
cd jonoffcpu
./gradlew :jonoffcpu-agent:check :jonoffcpu-correlator:check
```

The build needs [Amazon Corretto 25](https://aws.amazon.com/corretto/) and
Docker with [BuildKit](https://docs.docker.com/build/buildkit/); the native
libraries are compiled in a pinned container against the running kernel's BTF.
Pass `-PnativeArchitectures=all` to embed both Linux x86-64 and arm64 bundles,
or `x86_64` / `aarch64` to pick one. Each architecture has a glibc and a musl
flavour; `-PnativeLibcs` selects `musl` (the default), `glibc`, or `all`.
Releases embed all four bundles. The agent JAR lands in
`jonoffcpu-agent/build/libs/` and the runnable correlator JAR in
`jonoffcpu-correlator/build/libs/`.

The converter is built from the same fork's `src/converter` sources by the
`jonoffcpu-jfr-converter` module:

```sh
./gradlew :jonoffcpu-jfr-converter:check
```

Every [CI run](https://github.com/lhotari/jonoffcpu/actions) also publishes
the three JARs as a `jonoffcpu-runnable-jars` workflow artifact:

```sh
gh run download <run-id> --repo lhotari/jonoffcpu \
  --name jonoffcpu-runnable-jars --dir jonoffcpu-runnable-jars
```

Formatting is enforced with [Spotless](https://github.com/diffplug/spotless)
(`./gradlew spotlessApply`). Release and
publishing steps are in [RELEASING.md](RELEASING.md); contributor conventions
are in [AGENTS.md](AGENTS.md).

## Repository layout

| Path | Purpose |
| --- | --- |
| [`jonoffcpu-agent/`](jonoffcpu-agent/) | Java agent: capture controller, JNI bridge, and native integration tests ([README](jonoffcpu-agent/README.md)) |
| [`jonoffcpu-native/`](jonoffcpu-native/) | Rust/[libbpf-rs](https://github.com/libbpf/libbpf-rs) collector, CO-RE eBPF programs, and privileged kernel proof tools ([README](jonoffcpu-native/README.md)) |
| [`jonoffcpu-correlator/`](jonoffcpu-correlator/) | Offline correlator: JFR reader and derived-output writers ([OFFLINE.md](jonoffcpu-correlator/OFFLINE.md)) |
| [`async-profiler/`](async-profiler/) | Submodule tracking the [`jonoffcpu-dev`](https://github.com/lhotari/async-profiler/tree/jonoffcpu-dev) branch of [`lhotari/async-profiler`](https://github.com/lhotari/async-profiler) |

## License

`jonoffcpu` is derived from Yuto Kawamura's MIT-licensed
[`kawamuray/jbm`](https://github.com/kawamuray/jbm) and continues under the
[MIT License](LICENSE). Third-party components bundled in the agent JAR retain
their own license and notice files.
