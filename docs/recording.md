# The recording

A jonoffcpu capture records everything in one run. The JDK's own Flight
Recorder, async-profiler and jonoffcpu's eBPF program all observe the same
execution of the application, and their events land in one JFR file. The
off-CPU measurements go to a correlation stream beside it. This page describes
what the JFR file holds, how to choose what goes into it, and how to read it
with standard tools. [Analyzing a capture](analysis.md) covers what the
correlator makes of it.

- [One run, every source](#one-run-every-source)
- [What the JFR file holds](#what-the-jfr-file-holds)
- [Choosing what to record](#choosing-what-to-record)
- [JDK Mission Control](#jdk-mission-control)
- [Flame graphs of the other events](#flame-graphs-of-the-other-events)
- [The jfr command-line tool](#the-jfr-command-line-tool)
- [The correlation stream](#the-correlation-stream)

## One run, every source

The agent starts async-profiler with `asyncProfilerOptions` as given, adding
only the options of its own signal capture. async-profiler's
[`jfrsync`](https://github.com/async-profiler/async-profiler/blob/master/docs/ProfilerOptions.md)
option starts the JDK's Flight Recorder along with it, with a JFR
configuration such as `profile`. The file it writes includes the JDK's events,
except where async-profiler records the same kind of event itself: then the
JDK's version is turned off. With `event=cpu`, async-profiler's CPU samples
replace the JDK's execution samples. With `alloc=`, its allocation samples
replace the JDK's allocation events. With `lock=`, its lock samples replace the
JDK's monitor-enter and park events. Every event in the file describes the same
run, on the same timeline: a GC pause, the CPU samples around it, and the
off-CPU intervals it caused can be read side by side.

Without `jfrsync`, the file holds only async-profiler's events and jonoffcpu's
signal samples. That is enough for the off-CPU profile, but leaves out GC,
safepoints, the container configuration and the JDK's other events.

## What the JFR file holds

| Source | Events | Recorded when |
| --- | --- | --- |
| The JDK's Flight Recorder | Garbage collections and their phases (`jdk.GarbageCollection`, `jdk.GCPhasePause`), safepoints, JIT compilations, heap and CPU load, the JVM, OS, CPU and container details (`jdk.JVMInformation`, `jdk.OSInformation`, `jdk.CPUInformation`, `jdk.ContainerConfiguration`), initial system properties and environment variables, and waits above a threshold: sleep, socket and file I/O, and monitor enter and park unless `lock=` replaces them | `jfrsync`, with the events and thresholds its configuration enables |
| async-profiler | CPU samples (`jdk.ExecutionSample`), allocations (`jdk.ObjectAllocationInNewTLAB`, `jdk.ObjectAllocationOutsideTLAB`), lock contention (`jdk.JavaMonitorEnter`, `jdk.ThreadPark`), wall-clock samples (`profiler.WallClockSample`), native locks (`profiler.NativeLock`) | The matching `event=`, `alloc=`, `lock=`, `wall=` or `nativelock=` option |
| jonoffcpu | `profiler.SignalSample`: the Java stack of each recorded off-CPU interval, with its correlation key. `profiler.SignalCapture` and `profiler.SignalCaptureStats`: the capture's identity and signal counters | Always, unless the admission policy is `none` |

async-profiler also writes the JVM, OS and CPU information events itself, so
the digest's *Process* and *System* lines work without `jfrsync`. The container
configuration, and the environment variables that `--process-details true`
shows, come only from the JDK's events.

JFR's thresholded wait events overlap with the off-CPU profile, but differ from
it. They cover the Java-level waits the JDK instruments, and only those longer
than the threshold: in JDK 25's `profile` configuration, 10 ms for monitors and
parks, and 1 ms for socket and file I/O, throttled. async-profiler's `lock=`
samples contended locks by accumulated wait time instead, and names the lock's
class. The off-CPU profile covers every wait the scheduler sees, whatever
caused it, with its exact duration.

## Choosing what to record

`asyncProfilerOptions` is async-profiler's option list, so any of its
[profiling modes](https://github.com/async-profiler/async-profiler/blob/master/docs/ProfilingModes.md)
can be combined with the off-CPU capture:

```yaml
asyncProfilerOptions: event=cpu,alloc=2m,lock=10ms,jfrsync=profile,file=/tmp/jonoffcpu-capture.jfr
```

- `event=cpu` samples CPU time; `itimer` and `ctimer` are the alternatives
  where perf events are unavailable.
- `alloc=2m` samples an allocation every 2 MB allocated.
- `lock=10ms` samples contended locks, about once per 10 ms of total wait time.
  Shorter waits are sampled, not dropped.
- `jfrsync=profile` adds the JDK's events with the JDK's `profile`
  configuration. `default` records fewer events at a lower overhead, a path
  names a custom `.jfc` file, and a list of `+`-prefixed event names, such as
  `jfrsync=+jdk.GarbageCollection+jdk.SafepointBegin`, records only those
  events.
- `wall=` adds wall-clock samples. The wall-clock sampler's own thread then
  shows up in the off-CPU profile, sleeping for its interval, so leave it out
  unless the wall-clock view is wanted; see
  [Overhead and the observer effect](capture.md#overhead-and-the-observer-effect).

Every event source adds its own overhead, independent of jonoffcpu's.
[Configuring the capture](capture.md) covers the off-CPU sampling itself.

## JDK Mission Control

[JDK Mission Control](https://adoptium.net/jmc) opens the recording as it
would any JFR file. async-profiler's events use the JDK's event types, so JMC's
usual pages show them:

- **Method Profiling:** the CPU samples.
- **Memory** and **TLAB Allocations:** the allocation samples.
- **Lock Instances:** the lock contention.
- **Garbage Collections**, **Compilations** and **Environment:** the JDK's own
  events, with `jfrsync`.

The off-CPU samples appear in JMC's Event Browser as `profiler.SignalSample`
events, with their stacks but without durations: the durations are in the
correlation stream, and only the correlator joins the two. Some of JMC's pages
stay empty for an async-profiler recording, as
[async-profiler's documentation](https://github.com/async-profiler/async-profiler/blob/master/docs/JfrVisualization.md)
notes. IntelliJ IDEA's profiler opens the recording too.

## Flame graphs of the other events

`jfr-converter.jar`, async-profiler's converter built from the fork, renders
the recording's other events as flame graphs. Render a view only for events
that were configured: `jfrsync` alone does not make an allocation or lock view
meaningful.

| `asyncProfilerOptions` contains | View | Converter |
| --- | --- | --- |
| `event=cpu` (or `itimer`, `ctimer`) | CPU | `--cpu` |
| `event=wall` or `wall=` | wall clock | `--wall` |
| `alloc=` | allocation | `--alloc --total` |
| `lock=` | Java lock contention | `--lock --total` |

```sh
java -jar jfr-converter.jar --cpu /tmp/jonoffcpu-capture.jfr cpu.html
java -jar jfr-converter.jar --lock --total /tmp/jonoffcpu-capture.jfr lock.html
```

Add `--threads` for a per-thread split, and `-o collapsed` for machine-readable
output. The converter leaves the signal samples out of its flame graphs; the
off-CPU flame graph is rendered from the correlator's output instead, see
[Render the flame graph](analysis.md#render-the-flame-graph).

The correlator's filters and transforms apply to these views too. The
converter writes class names as `org/example/Class` with `_[j]`-style markers,
which `stacks --collapsed-input` normalizes:

```sh
java -jar jfr-converter.jar --cpu -o collapsed /tmp/jonoffcpu-capture.jfr cpu.collapsed
java -jar jonoffcpu-correlator.jar stacks --collapsed-input cpu.collapsed \
  --trim-root-from preset:jvm-infra --output cpu-trimmed.collapsed
java -jar jfr-converter.jar cpu-trimmed.collapsed cpu-trimmed.html
```

## The jfr command-line tool

The JDK's [`jfr`](https://docs.oracle.com/en/java/javase/25/docs/specs/man/jfr.html)
tool summarizes and prints any event of the recording:

```sh
jfr summary /tmp/jonoffcpu-capture.jfr
jfr print --events jdk.GarbageCollection /tmp/jonoffcpu-capture.jfr
jfr print --events profiler.SignalSample /tmp/jonoffcpu-capture.jfr | head
```

## The correlation stream

The second file, `jonoffcpu-capture.pb`, holds what the JFR cannot: each
recorded interval's duration and why it began, its kernel and user native
stacks, the kernel's counters, and a footer that binds the JFR's size and
SHA-256. `java -jar jonoffcpu-correlator.jar dump --source
/tmp/jonoffcpu-capture.pb` prints it as JSON Lines.
[How jonoffcpu works](how-it-works.md#why-two-files) explains why the
measurement is split between the two files.
