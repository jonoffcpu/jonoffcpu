# Configuring the capture

The agent reads one YAML configuration, given as the `-javaagent` argument:
where to write the capture, which async-profiler events to record alongside,
and which off-CPU intervals are worth recording. This page lists the options,
explains how to choose a sampling policy, and what a capture costs the
application being measured. The agent's lifecycle, its native bundle and its
raw `-agentpath` form are in [the agent's README](../jonoffcpu-agent/README.md).

- [Agent options](#agent-options)
- [Choosing what to sample](#choosing-what-to-sample)
- [Overhead and the observer effect](#overhead-and-the-observer-effect)
- [Turning jonoffcpu off without removing it](#turning-jonoffcpu-off-without-removing-it)
- [Starting and stopping the capture](#starting-and-stopping-the-capture)

## Agent options

| Option | Meaning |
| --- | --- |
| `correlationOutput` | Required. Path of the correlation stream. Must not exist yet, and its directory must. Its stem names the sibling `.manifest.json` and, when `file=` is absent, the `.jfr`; see [Files jonoffcpu writes](how-it-works.md#files-jonoffcpu-writes). |
| `asyncProfilerOptions` | Required. async-profiler's options, passed on unchanged, such as `event=cpu,alloc=2m,lock=10ms,jfrsync=profile`. `file=` names the JFR, which must not exist yet; without it the JFR is written next to the correlation stream. The agent owns the profiler's actions and its signal options, so `start`, `stop`, `signalcookie` and the like are refused, as are `file=` patterns with `%`. |
| `sampling` | Required. Which off-CPU intervals are recorded; see [Choosing what to sample](#choosing-what-to-sample). |
| `sampling.reasons` | Optional list of switch-out reasons to record: `blocked`, `runnable`, `preempted`. Default `[blocked]`; see [Why the thread left the CPU](off-cpu-profiling.md#why-the-thread-left-the-cpu). |
| `sampling.minOffCpuMicros` | Optional strict lower bound on the off-CPU duration, in microseconds. |
| `sampling.maxOffCpuMicros` | Optional strict upper bound on the off-CPU duration, in microseconds. |
| `sampling.admission.policy` | Required. `proportional`, `uniform`, or `none`. |
| `sampling.admission.recordAllAboveMicros` | `proportional` only. Intervals at least this long are always recorded; shorter ones with probability `length / recordAllAboveMicros`. |
| `sampling.admission.probability` | `uniform` only. `"0.000"` through `"1.000"`; every eligible interval is recorded with this probability. `"0"` is the same as policy `none`. Quote the value to keep its exact spelling in the capture metadata. |
| `timeSplit.source` | `schedInfo` (default) records each interval's run-queue part from the scheduler's `sched_info.run_delay`, splitting its time into sleeping and run-queue time; `"off"` (quoted, since YAML reads a bare `off` as a boolean) reads nothing, for a kernel without `CONFIG_SCHED_INFO`. See [Sleeping and run-queue time](off-cpu-profiling.md#sleeping-and-run-queue-time). |
| `signalDelivery` | `queued` (default) uses a dedicated real-time signal and never merges notifications. `coalescing` uses a standard signal and may merge them, trading lost samples for a bounded pending-signal queue. |
| `nativeStopTimeoutMillis` | Budget for detaching the eBPF source and draining the ring buffer at stop. Default 30000. |
| `deliveryGraceMillis` | Time allowed after detach for already-requested signals to arrive. Default 100. |
| `shutdownTimeoutMillis` | How long the JVM shutdown hook waits for the capture to finalize. Default 10000. |
| `asyncProfilerLibrary`, `nativeCollectorLibrary` | Optional, both or neither. Libraries to load instead of the bundle embedded in the agent JAR, such as a development build of the async-profiler fork. |
| `jfrOutput` | Optional. The JFR's path; when given, `asyncProfilerOptions` must name the same file with `file=`. |
| `targetPid` | Optional. The process to profile, which can only be the JVM the agent runs in. |

Relative paths are resolved against the JVM's working directory. The agent
rejects unknown and duplicate keys, and the policies' parameters are checked as
described below.

## Choosing what to sample

Every off-CPU interval costs the same to *measure* (the kernel does that
anyway), but *recording* one costs a signal to the thread and a Java stack
walk, and that cost lands on the thread being measured. Sampling exists to
keep that disturbance small enough that the profile still describes the
application rather than the profiler; see
[Overhead and the observer effect](#overhead-and-the-observer-effect). The
`sampling` block decides which measured intervals are worth recording. All
decisions are made in the kernel after the interval's duration is known, in
this order:

1. **`reasons`** — which switch-out reasons are recorded, `[blocked]` unless
   given. Intervals of other reasons are counted by reason and dropped. Adding
   `runnable` and `preempted` records every preemption of a busy thread, which
   can multiply the recording rate: pair it with `minOffCpuMicros` or a low
   `uniform` probability. `reasons: [blocked, runnable, preempted]` records
   every interval whatever its reason.
2. **`minOffCpuMicros` / `maxOffCpuMicros`** — strict bounds. Intervals outside
   them are never recorded and never counted.
3. **`admission.policy`** — which of the remaining intervals to record:
   - `proportional`: an interval of at least `recordAllAboveMicros` is always
     recorded; a shorter one is recorded with probability
     `length / recordAllAboveMicros`. With `10000`, a 1 ms wait has a 10 %
     chance and a 10 µs wait 0.1 %.
   - `uniform`: every interval is recorded with the same `probability`.
   - `none`: nothing is recorded and no eBPF program is loaded (see
     [Turning jonoffcpu off](#turning-jonoffcpu-off-without-removing-it)).

Each policy has exactly one parameter; giving `probability` to `proportional`
or `recordAllAboveMicros` to `uniform` is a configuration error, as are bounds
or reasons with `none`. There is no default: the block is required so that a
capture without off-CPU data is always a deliberate choice.

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

## Overhead and the observer effect

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
| eBPF switch-out / switch-in hooks | every context switch of the target process's threads | a task-storage lookup, a timestamp, the reason, a read of the scheduler's run delay, the bounds check and the admission draw | the switching thread, in the kernel; nothing leaves the kernel for intervals the bounds or the admission policy reject |
| Ring-buffer record + signal | each recorded interval | a 136-byte kernel record and a signal queued to the thread that just resumed | the resumed thread, when the signal is delivered |
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
smoke capture from 3.3 KB to 116 bytes per observation. The correlation itself
runs offline, after the capture and on any machine, so its memory and CPU are
never taken from the profiled process.

Feedback loops — the profiler observing its own waits — are closed in the
kernel: the collector's drain thread and the agent's controller thread report
their thread IDs at setup and the eBPF program never records their intervals.
async-profiler's own threads are ordinary threads of the process and do appear
when they wait; a `wall=` sampler, for example, shows up under
`libasyncProfiler.so` frames sleeping for its interval. Leave `wall=` out of
`asyncProfilerOptions` unless wall-clock samples are wanted alongside the
measured intervals.

## Turning jonoffcpu off without removing it

Set `sampling.admission.policy: none` to run plain async-profiler through the
same `-javaagent` line. The agent then loads no eBPF program, negotiates no
signal, and needs no BPF privileges; async-profiler is started with
`asyncProfilerOptions` exactly as given, so the JFR contains only its ordinary
events. The correlation path still receives a one-record stream whose
`captureFinalized` record has state `FINALIZED_STATE_PROFILER_ONLY`, so the
correlator reports that there is nothing to correlate instead of failing on a
missing file. This lets a deployment keep one configuration and flip off-CPU
capture on or off.

## Starting and stopping the capture

The capture finishes when the JVM exits normally, or earlier if the
application calls `io.github.jonoffcpu.agent.SignalCaptureAgent.stop()`.
Abrupt termination leaves visibly incomplete artifacts rather than a
plausible-looking partial result. The agent can also be started
programmatically with `SignalCaptureAgent.start(path)` or attached at runtime
through its `Agent-Class` entry point; see
[the agent's README](../jonoffcpu-agent/README.md).
