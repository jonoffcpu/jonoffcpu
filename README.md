![#jonoffcpu](docs/images/jonoffcpu-banner.jpg)

**jonoffcpu** shows where JVM applications wait, and for how long. It
is a profiling toolset for Java on Linux that runs
[async-profiler](https://github.com/async-profiler/async-profiler) and extends
it with off-CPU profiling measured by the kernel. The Linux scheduler, through
[eBPF](https://en.wikipedia.org/wiki/EBPF), times every interval a thread
spends off the CPU. async-profiler captures
the Java stack that waited, and an offline correlator joins the two into flame
graphs and ranked reports whose numbers are real durations.

- **Measured in the kernel, sampled by duration.** jonoffcpu's eBPF program
  times each off-CPU interval at the scheduler's own switch points. Recording
  an interval costs the thread a signal and a stack walk, so a capture usually
  records a sample: every wait longer than a threshold you choose, and shorter
  ones with a probability proportional to their duration
  ([probability proportional to size](https://en.wikipedia.org/wiki/Probability-proportional-to-size_sampling)).
  Each recorded wait carries its exact duration, why the thread left the CPU,
  how much of it was spent asleep versus queued for a CPU, and the threshold
  it was drawn against.
  [Flame graphs](https://www.brendangregg.com/flamegraphs.html) weigh stacks
  by measured microseconds, not by sample counts, and the thresholds turn the
  sample into unbiased estimates of the total off-CPU time
  ([Horvitz–Thompson](https://en.wikipedia.org/wiki/Horvitz%E2%80%93Thompson_estimator)).
- **One recording, every source.** One `-javaagent` starts a current
  async-profiler. [Its fork](https://github.com/jonoffcpu/async-profiler/tree/jonoffcpu-dev)
  follows upstream master, with its patches rebased periodically. A single run
  records the JDK's own [Flight Recorder](https://openjdk.org/jeps/328)
  events, async-profiler's CPU,
  allocation and lock profiles, and the off-CPU samples into one JFR file. That
  file opens in JDK Mission Control and async-profiler's converter as usual.
- **A small [observer effect](https://en.wikipedia.org/wiki/Observer_effect_(information_technology)).**
  The per-[context-switch](https://en.wikipedia.org/wiki/Context_switch) work
  stays in the kernel, which measures each interval before deciding whether to
  record it. Sampling by duration keeps the recording rate in proportion to
  off-CPU time rather than to the number of context switches, and nothing
  leaves the kernel for an interval that is not recorded. A recorded interval
  costs about 120 bytes. Correlation runs offline, on any machine.
- **Your code, not the plumbing.** Threads *waiting for work*, such as idle
  event loops and pool workers, are kept apart from threads *blocked* while
  they had work to do. Filters and transforms root each stack at your
  application, hide executors and lambda bridges, and collapse lock internals
  into the call that blocked. The flame graph and the ranked tables then show
  where your code waited, and on what.
- **Made for automation.** A bounded Markdown and JSON digest carries the
  command that reproduces each of its tables. The structured outputs are
  documented protobuf messages with a JSON view, the export is ready for
  DuckDB, and runs compare per unit of work, so an AI agent or a CI job can
  act on the results.
- **Runs where your JVM runs.** One self-contained agent JAR covers Linux
  x86-64 and arm64, [glibc](https://en.wikipedia.org/wiki/Glibc) and
  [musl](https://en.wikipedia.org/wiki/Musl) (Alpine). It profiles a container from
  inside it, without `--pid=host`, and it fails closed rather than recording
  degraded data.

**[Quick start](#quick-start)** ·
**[Documentation](#documentation)** ·
**[Releases](https://github.com/jonoffcpu/jonoffcpu/releases)**

## What is off-CPU profiling

At any moment, a Java thread is in one of the
[process states](https://en.wikipedia.org/wiki/Process_state) the scheduler
keeps: [running](https://en.wikipedia.org/wiki/Process_state#Running) on a
CPU, [ready](https://en.wikipedia.org/wiki/Process_state#Ready) and waiting
for one, or [blocked](https://en.wikipedia.org/wiki/Process_state#Blocked):

| The thread is | For example | jonoffcpu records it as |
| --- | --- | --- |
| **Running on a CPU** | Java code, the JVM itself, a [JNI](https://openjdk.org/groups/hotspot/docs/HotSpotGlossary.html#JNI) library, or the kernel working on the thread's behalf | Not off-CPU time; async-profiler's `event=cpu` samples it |
| **Waiting for a CPU** | [Preempted](https://en.wikipedia.org/wiki/Preemption_(computing)) by the kernel while running, or woken up while every CPU it may use is busy | [Run-queue](https://en.wikipedia.org/wiki/Run_queue) time: an interval of reason `runnable` or `preempted`, or a `blocked` interval after its wakeup |
| **Blocked, waiting for work** | An idle [event loop](https://en.wikipedia.org/wiki/Event_loop) in `epoll_wait`, or a [thread pool](https://en.wikipedia.org/wiki/Thread_pool) worker waiting for its next task | Reason `blocked`, set apart as *waiting* by the analysis |
| **Blocked while it has work to do** | A contended [lock](https://en.wikipedia.org/wiki/Lock_(computer_science)) or [monitor](https://en.wikipedia.org/wiki/Monitor_(synchronization)), a reply from another service, a disk write, a future | Reason `blocked`, ranked as *blocked* by the analysis |
| **Blocked by the OS or the JVM** | A [page fault](https://en.wikipedia.org/wiki/Page_fault) that reads from disk, or a JVM [safepoint](https://openjdk.org/groups/hotspot/docs/HotSpotGlossary.html#safepoint) such as a [stop-the-world](https://en.wikipedia.org/wiki/Tracing_garbage_collection#Stop-the-world_vs._incremental_vs._concurrent) GC pause | Reason `blocked`, ranked as *blocked* by the analysis |

[![Thread states seen by the scheduler](docs/images/offcpu-timeline.svg)](https://raw.githubusercontent.com/jonoffcpu/jonoffcpu/main/docs/images/offcpu-timeline.svg)

A CPU profiler samples only the first row. Everything else is *off-CPU time*,
and in most services it is where the latency goes. The Linux scheduler sees
every transition, and why the thread left the CPU: it blocked, or it was still
runnable and another thread took its place. It cannot see what a blocked
thread waited for: a contended lock, a future and a safepoint all look like a
sleep on a [futex](https://en.wikipedia.org/wiki/Futex). That is in the Java
stack.
jonoffcpu takes each interval's duration and reason from the scheduler
through eBPF, and the Java stack from async-profiler. Kernel tools cannot walk
[JIT-compiled](https://openjdk.org/groups/hotspot/docs/HotSpotGlossary.html#JITCompilers)
Java frames, and a JVM's wall-clock sampler cannot tell how long
a wait lasted; jonoffcpu joins the two.
[Off-CPU profiling](docs/off-cpu-profiling.md) explains the states and the
reasons in depth.

## Why jonoffcpu

In a small service, a CPU profile and a few thread dumps often find the
problem. In a system like [Apache Pulsar](https://pulsar.apache.org/) they
don't. There, millions of events per second flow through roughly 50 to 200
threads, depending on the CPU count: event loops, executor pools and storage
clients size their thread pools from
`Runtime.getRuntime().availableProcessors()`, often twice that, and the JVM
adds threads of its own. Nearly all of their off-CPU time is spent waiting for
work. The waits that limit
throughput or add latency are a small fraction of that time, invisible in an
unfiltered flame graph.

As an example from Apache Pulsar profiling: in a broker under a many-producer
load, almost all of the threads' off-CPU time was waiting for work. Once that
was set apart, two waits led everything else: a contended monitor in the
message dispatcher, and a lock in the queue of the storage client's executor.

Such bottlenecks are also different in every usage scenario. Many producers
to one topic, many topics, large messages, slow consumers and geo-replication
each stress a different part of the system. A change that removes one
bottleneck can move the load onto another, or trade latency in one scenario
for throughput in another.

Finding a bottleneck takes several steps. Waiting for work has to be separated
from blocking. The waits have to be attributed to the application code, not to
the lock internals under it. After every change, the new run has to be
compared with the previous one under the same load, per unit of work, and
that has to be repeated for every scenario the change affects. Without
automated performance analysis, profiling, optimizing and weighing trade-offs
across many scenarios does not scale. jonoffcpu is built so that a script or
an AI agent can do it: the correlator reduces each capture to a digest and
ranked tables that can be read, drilled into and compared automatically.

## Where jonoffcpu is going

jonoffcpu started as an experiment in automating performance optimization and
tuning of Apache Pulsar, with its end-to-end
[performance scenarios](https://github.com/apache/pulsar/tree/master/tests/performance),
and its features are currently shaped by that work. Solving the problem
described above is the core of the vision: automated performance analysis that
finds the bottleneck of each scenario, checks whether a change helped, and
shows the trade-offs across scenarios, for Apache Pulsar and for any JVM
system like it. The steps toward that:

- **Extract what matters from one run.** A recording holds the JDK's Flight
  Recorder events, async-profiler's profiles and jonoffcpu's eBPF
  measurements. jonoffcpu will extract the information relevant to "what
  limits this system, and did the change help?" from all three.
- **Normalize stacks automatically.** Stack traces will be normalized, and the
  boundaries between the application, its libraries and the JDK detected from
  the data itself, rather than from patterns given by hand. That keeps a vast
  amount of stack data readable, and comparable across runs, scenarios and
  versions.
- **Compare across runs and scenarios.** jonoffcpu is built to support this.
  `top --baseline` compares two runs per unit of work and warns when they are
  not comparable. `export --run-label` and `--run-metadata` put any number of
  runs side by side in SQL. What drives the runs lives outside jonoffcpu today,
  in Apache Pulsar's performance scenarios. Its generic parts, and
  contributions from the jonoffcpu community, can become subprojects of the
  [jonoffcpu organization](https://github.com/jonoffcpu). Likely candidates are
  integrations with load generators, and test-report generators that show a
  change's effect across scenarios.
- **Complement existing tooling, and plug into it.** jonoffcpu does not try to
  compete with JFR tooling or with the tooling emerging for AI agents. It
  complements them, and may integrate with them through their plugin
  mechanisms.
  - Its recording is an ordinary JFR file, which
    [JDK Mission Control](https://adoptium.net/jmc) and JFR libraries read. For example, [Jafar](https://github.com/btraceio/jafar) is
    a fast JFR parser with an MCP server that lets AI agents analyze JFR
    recordings.
  - Its derived outputs are documented protobuf messages with a JSON view, so
    other tools can consume them without a parser of their own.
  - For coding agents, the next step is skills and plugins that know the
    workflow: capture, correlate, read the digest, drill down with `top` and
    `stacks`, and compare with a baseline.
    [jafar-perf-box](https://github.com/btraceio/jafar-perf-box) shows the
    pattern: it packages a performance-analysis methodology for AI agents as a
    plugin on top of Jafar.

Ideas and contributions toward any of these are welcome; open an
[issue](https://github.com/jonoffcpu/jonoffcpu/issues) to discuss them.

## How a recording works

One run records everything, into two files:

- **The JFR recording.** The agent starts async-profiler with the options you
  give it. With `jfrsync=profile`, async-profiler also starts the JDK's own
  Flight Recorder and writes one JFR file holding three kinds of events:
  - the JDK's events: garbage collections, safepoints, JIT compilation, the
    JVM, OS, CPU and container details, and JFR's own events for waits above
    a threshold;
  - async-profiler's samples, as configured: CPU, allocation, lock contention
    and wall clock, each in place of the JDK's own events of that kind;
  - a `profiler.SignalSample` event with the Java stack of every off-CPU
    interval jonoffcpu recorded.
- **The correlation stream.** The eBPF program measures each off-CPU interval,
  and the collector writes its duration, reason, native stacks and
  correlation key to this file.

[![jonoffcpu architecture](docs/images/architecture.svg)](https://raw.githubusercontent.com/jonoffcpu/jonoffcpu/main/docs/images/architecture.svg)

The JFR file is an ordinary recording. Open it in
[JDK Mission Control](https://adoptium.net/jmc) for its GC, CPU, allocation
and lock analysis, or convert it to flame graphs with async-profiler's
converter. The correlator joins the correlation stream with the recording's
`SignalSample` events offline, into the off-CPU profile.
[The recording](docs/recording.md) describes what it contains and how to read
it, and [How jonoffcpu works](docs/how-it-works.md) the pipeline and its files.

## Complementing async-profiler and JFR

async-profiler and JFR can already answer some of the questions an off-CPU
profile answers. The approaches are complementary, not exclusive, and one
jonoffcpu recording holds them all.

- **async-profiler's lock profiling** (`lock=`) records contended
  `synchronized` blocks and waits on `ReentrantLock`, `ReentrantReadWriteLock`
  and `Semaphore`, with the wait time and the class of the lock. It needs no
  eBPF and no privileges, and it is the most direct answer to "which Java lock
  is contended". `nativelock=` does the same for pthread mutexes and
  read-write locks.
- **JFR's own events** record sleep, socket and file waits that last longer
  than a threshold, and monitor and park waits too when `lock=` does not
  replace them. In JDK 25's `profile` configuration, the threshold is 10 ms for
  locks and 1 ms for I/O.
- **Wall-clock profiling** (`wall=`) samples every thread periodically,
  whatever its state. That shows where threads spend their time, but not how
  long each wait lasted.
- **jonoffcpu's off-CPU profiling** covers every kind of wait, whatever caused
  it, and records each sampled wait with its exact duration. That includes socket and disk I/O, `epoll`,
  `Condition.await`, queue `take`, `CompletableFuture.get`, sleeps, futexes in
  native code, page faults, and the time a runnable thread queued for a CPU. It
  cannot name the lock object, because the kernel sees only the futex.

| Question | Best answered by |
| --- | --- |
| Which Java lock is contended, and by whom? | async-profiler `lock=` |
| Where is CPU time spent? | async-profiler `event=cpu` |
| How do GC pauses and safepoints affect the application? | JFR's events, in JDK Mission Control |
| Where do threads wait, on anything, and for exactly how long? | jonoffcpu's off-CPU profile |
| Are threads delayed by CPU saturation or throttling? | jonoffcpu's run-queue split and `runnable`/`preempted` intervals |

## Quick start

**Before you start**, you need:
- 64-bit Linux on x86-64 or arm64, whose kernel has BTF and the eBPF features
  the agent checks at startup. This prints a non-zero count on a suitable
  kernel:

  ```sh
  test -r /sys/kernel/btf/vmlinux && grep -ac btf_trace_sched_exit_tp /sys/kernel/btf/vmlinux
  ```

- `CAP_BPF` and `CAP_PERFMON`, or root, for the profiled JVM.
- Java 17 or newer for the agent, and Java 21 or newer for the correlator.

async-profiler, which runs inside the JVM, needs these kernel settings to see
kernel frames and deep native stacks:

```sh
sudo sysctl -w kernel.perf_event_paranoid=1 kernel.kptr_restrict=0 \
  kernel.perf_event_max_stack=1024 kernel.perf_event_mlock_kb=2048
```

[Setting up a host](docs/setup.md) explains each setting, and covers
containers (`--cap-add BPF --cap-add PERFMON`) and Docker Desktop.

### 1. Get the JARs

Download the three JARs of the latest
[GitHub Release](https://github.com/jonoffcpu/jonoffcpu/releases):

```sh
gh release download -p '*.jar' -R jonoffcpu/jonoffcpu
```

Pass a tag such as `v0.7.0` after `download` to pick a specific release
instead of the latest one.

| JAR | What it is |
| --- | --- |
| `jonoffcpu-agent.jar` | The Java agent. It bundles the eBPF collector, the JNI bridge and the patched async-profiler for every supported platform, and drives the capture. |
| `jonoffcpu-correlator.jar` | The offline correlator. It joins the JFR with the correlation stream, verifies their integrity, and writes the flame-graph input, the stack profile and the digest. |
| `jfr-converter.jar` | async-profiler's [`jfrconv`](https://github.com/async-profiler/async-profiler/blob/master/docs/ConverterUsage.md), built from the fork so that it labels flame graphs in microseconds. |

The commands below assume all three JARs are in the current directory.

### 2. Record

Create `jonoffcpu.yaml`:

```yaml
correlationOutput: /tmp/jonoffcpu-capture.pb
asyncProfilerOptions: event=cpu,alloc=2m,lock=10ms,jfrsync=profile,file=/tmp/jonoffcpu-capture.jfr
sampling:
  minOffCpuMicros: 100
  admission:
    policy: proportional
    recordAllAboveMicros: 10000
```

This records every wait of 10 ms or longer and samples shorter ones in
proportion to their length. It drops context switches shorter than 100 µs
entirely. Into the same JFR file it records async-profiler's CPU, allocation
and lock profiles, and, through `jfrsync=profile`, the JDK's own Flight
Recorder events. Start the application with the agent:

```sh
java -javaagent:jonoffcpu-agent.jar=jonoffcpu.yaml -jar application.jar
```

The capture finishes when the JVM exits normally. See
[Configuring the capture](docs/capture.md) for every option, how to choose a
sampling policy, and what a capture costs.

### 3. Correlate

```sh
java -jar jonoffcpu-correlator.jar \
  --source /tmp/jonoffcpu-capture.pb \
  --jfr /tmp/jonoffcpu-capture.jfr \
  --output /tmp/jonoffcpu-analysis \
  --estimate-population true \
  --app '^com\.example\.'
```

`--app` names your application's frames, so that the analysis ranks your code
rather than the lock and park internals under it. `--estimate-population
true` keeps the estimates valid for comparing runs.

### 4. Read the results

Start with `/tmp/jonoffcpu-analysis/jonoffcpu-summary.md`, the digest. It
states when and on what machine and JVM the capture was recorded. It ranks the
blocked time by the application method that waited and what it blocked on,
shows where the time went, and gives the command that reproduces each table.
Then render the flame graph:

```sh
java -jar jfr-converter.jar --title "Off-CPU time" --units µs \
  /tmp/jonoffcpu-analysis/jonoffcpu-offcpu-stacks.collapsed \
  /tmp/jonoffcpu-analysis/offcpu.html
```

Frame widths in `offcpu.html` are microseconds of off-CPU time. The recording
itself, `/tmp/jonoffcpu-capture.jfr`, opens in
[JDK Mission Control](https://adoptium.net/jmc) for GC, CPU, allocation and
lock analysis.

**Next steps:**
- [Find what to optimize](docs/analysis.md#find-what-to-optimize) with `top`
  and the application-rooted flame graph.
- [Slice and filter](docs/analysis.md#slice-and-filter-with-the-stack-profile)
  the stack profile without correlating again.
- [Compare runs](docs/analysis.md#compare-runs) per unit of work.
- Render the recording's [CPU, allocation and lock views](docs/recording.md#flame-graphs-of-the-other-events)
  as flame graphs.
- Hand the results to an [AI agent or to SQL](docs/automation.md).

## Documentation

| Guide | What it covers |
| --- | --- |
| [Off-CPU profiling](docs/off-cpu-profiling.md) | What off-CPU time is, why a JVM needs both a kernel and a Java view, why threads leave the CPU, sleeping and run-queue time |
| [How jonoffcpu works](docs/how-it-works.md) | The capture pipeline, the two capture files, what the Java stack means, every file jonoffcpu writes |
| [The recording](docs/recording.md) | What one JFR recording holds from the JDK, async-profiler and jonoffcpu, and how to read it with JDK Mission Control, the converter and the `jfr` tool |
| [Setting up a host](docs/setup.md) | Kernel requirements and settings, the bundled native libraries, Docker and Docker Desktop |
| [Configuring the capture](docs/capture.md) | Agent options, choosing what to sample, overhead and the observer effect |
| [Analyzing a capture](docs/analysis.md) | Correlating, flame graphs, other views, slicing, filtering and transforms, finding what to optimize, comparing runs, correlator options |
| [AI agents and SQL](docs/automation.md) | The digest as an agent's input, and DuckDB queries over the export |
| [Building and testing](docs/building.md) | Building from source, test categories, continuous integration |
| [Offline correlation](jonoffcpu-correlator/OFFLINE.md) | The correlator's contracts: integrity, clipping, weighting, the stack profile, degradation |
| [The Java agent](jonoffcpu-agent/README.md) | Agent lifecycle, native bundle, programmatic start, the raw `-agentpath` form |
| [The native collector](jonoffcpu-native/README.md) | The libbpf-rs collector, its eBPF programs and kernel proof tools |

## Project status

jonoffcpu is under active development and has not reached 1.0. Until the
1.0.0 release, formats and options change without backward compatibility; the
[release notes](https://github.com/jonoffcpu/jonoffcpu/releases) list what
changed. From 1.0.0 on, the configuration, the capture stream, the report, the
analysis outputs and the command line stay backward compatible unless a
migration is documented.

## Using the artifacts as libraries

The artifacts are published to Maven Central under the group id
[`io.github.jonoffcpu`](https://central.sonatype.com/namespace/io.github.jonoffcpu).

```kotlin
dependencies {
    implementation("io.github.jonoffcpu:jonoffcpu-agent:0.7.0")
    implementation("io.github.jonoffcpu:jonoffcpu-correlator:0.7.0")
    implementation("io.github.jonoffcpu:jonoffcpu-jfr-converter:0.7.0")
}
```

```xml
<properties>
  <jonoffcpu.version>0.7.0</jonoffcpu.version>
</properties>

<dependencies>
  <dependency>
    <groupId>io.github.jonoffcpu</groupId>
    <artifactId>jonoffcpu-agent</artifactId>
    <version>${jonoffcpu.version}</version>
  </dependency>
  <dependency>
    <groupId>io.github.jonoffcpu</groupId>
    <artifactId>jonoffcpu-correlator</artifactId>
    <version>${jonoffcpu.version}</version>
  </dependency>
  <dependency>
    <groupId>io.github.jonoffcpu</groupId>
    <artifactId>jonoffcpu-jfr-converter</artifactId>
    <version>${jonoffcpu.version}</version>
  </dependency>
</dependencies>
```

The correlator exposes
`OffCpuCorrelator.correlate(sourcePath, jfrPath, outputDirectory)` using JDK
types only. The published Gradle module metadata and POM point at the shaded,
self-contained JARs, so no further dependencies are needed.
`jonoffcpu-jfr-converter` is the converter from the pinned async-profiler fork
under the upstream `one.convert` and `one.jfr` packages, built for Java 21 and
licensed under Apache-2.0 like async-profiler itself.

## Building from source

```sh
git clone --recurse-submodules https://github.com/jonoffcpu/jonoffcpu.git
cd jonoffcpu
./gradlew jvmCheck                                              # any OS: formatting and the JVM tests
./gradlew :jonoffcpu-agent:check :jonoffcpu-correlator:check    # Linux: also the native bundle and its tests
```

The build needs Amazon Corretto 25, and the native part needs Docker.
[Building and testing](docs/building.md) covers the options, the test
categories and CI. [CODING.md](CODING.md) has the code conventions, and
[AGENTS.md](AGENTS.md) the rules for contributors and coding agents.

## Repository layout

| Path | Purpose |
| --- | --- |
| [`jonoffcpu-agent/`](jonoffcpu-agent/) | Java agent: capture controller, JNI bridge, and native integration tests ([README](jonoffcpu-agent/README.md)) |
| [`jonoffcpu-native/`](jonoffcpu-native/) | Rust/[libbpf-rs](https://github.com/libbpf/libbpf-rs) collector, CO-RE eBPF programs, and privileged kernel proof tools ([README](jonoffcpu-native/README.md)) |
| [`jonoffcpu-capture-codec/`](jonoffcpu-capture-codec/) | The capture stream schema and its Java codec, embedded in the agent and the correlator |
| [`jonoffcpu-correlator/`](jonoffcpu-correlator/) | Offline correlator: JFR reader and derived-output writers ([OFFLINE.md](jonoffcpu-correlator/OFFLINE.md)) |
| [`jonoffcpu-jfr-converter/`](jonoffcpu-jfr-converter/) | async-profiler's jfr-converter, built from the submodule's sources |
| [`build-logic/`](build-logic/) | Gradle convention plugins and task types the modules share |
| [`async-profiler/`](https://github.com/jonoffcpu/async-profiler/tree/jonoffcpu-dev) | Submodule tracking the `jonoffcpu-dev` branch of the [`jonoffcpu/async-profiler`](https://github.com/jonoffcpu/async-profiler) fork, which follows upstream master with jonoffcpu's patches rebased onto it |
| [`docs/`](docs/) | The guides linked above, and the diagrams' d2 sources and rendered images |

## License

`jonoffcpu` is derived from Yuto Kawamura's MIT-licensed
[`kawamuray/jbm`](https://github.com/kawamuray/jbm) and continues under the
[MIT License](LICENSE). Third-party components bundled in the agent JAR retain
their own license and notice files.
