# Off-CPU profiling

This page explains what an off-CPU profile measures, why a JVM needs both a
kernel view and a Java view of each wait, and how `jonoffcpu` classifies and
splits every interval it records. [How jonoffcpu works](how-it-works.md)
describes the pieces that do the measuring.

- [What is off-CPU profiling?](#what-is-off-cpu-profiling)
- [Why existing tools see half of it](#why-existing-tools-see-half-of-it)
- [Why the thread left the CPU](#why-the-thread-left-the-cpu)
- [Sleeping and run-queue time](#sleeping-and-run-queue-time)
- [Further reading](#further-reading)

## What is off-CPU profiling?

A CPU executes one thread at a time, and the Linux
[scheduler](https://en.wikipedia.org/wiki/Scheduling_(computing)) decides
which. Every hand-over is a
[context switch](https://en.wikipedia.org/wiki/Context_switch), visible to the
kernel as the `sched_switch` tracepoint: the outgoing thread is *switched
out*, the incoming one *switched in*. A thread is **on-CPU** from a switch-in
to its next switch-out and **off-CPU** the rest of the time. An off-CPU
interval holds up to two of the scheduler's
[process states](https://en.wikipedia.org/wiki/Process_state). The thread is
**sleeping**, [blocked](https://en.wikipedia.org/wiki/Process_state#Blocked),
while it is not runnable and waits for a wakeup — a
[futex](https://man7.org/linux/man-pages/man2/futex.2.html), data on a
socket, a disk read, a timer — and it is **runnable**,
[ready](https://en.wikipedia.org/wiki/Process_state#Ready), from the
`sched_wakeup` that ends the sleep until a CPU is free to switch it in. A
thread that is [preempted](https://en.wikipedia.org/wiki/Preemption_(computing))
skips the sleep: it stays runnable and only waits in the
[run queue](https://en.wikipedia.org/wiki/Run_queue). Off-CPU profiling records, for each
such interval, its exact duration and the code path that was executing when
the thread was switched out. CPU profilers sample only the on-CPU state; a
wall-clock sampler notices at each tick that a thread is off-CPU, but neither
how long the interval lasted nor whether the thread was sleeping or merely
queued.

[![Thread states seen by the scheduler](images/offcpu-timeline.svg)](https://raw.githubusercontent.com/jonoffcpu/jonoffcpu/main/docs/images/offcpu-timeline.svg)

From the Java side, these states split further: a thread runs Java code or
native code, waits for a CPU in either, or is blocked in native code or at the
Java level, on a [monitor](https://en.wikipedia.org/wiki/Monitor_(synchronization))
or a [`park`](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/locks/LockSupport.html).
Java's own
[`Thread.State`](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/Thread.State.html)
does not line up with the kernel's view: a thread in a blocking socket read is
`RUNNABLE` to Java but blocked to the kernel, and `BLOCKED`, `WAITING` and
`TIMED_WAITING` (a monitor, a `park`, a timed wait) are all a futex wait
underneath. The kernel sees only running, runnable and blocked, and for a
blocked thread the
*mechanism* of the wait, never its *reason*. A thread never blocks "on the
storage layer": with a synchronous client it sleeps in a socket read; with an
asynchronous client, a pool or any `Future.get()` it parks on a condition, a
`futex`, while another thread does the I/O. The mechanism and the duration are
what the kernel can prove; the reason lives in the stack of the code that
called into the wait.

| What the code is doing | Where the Java thread waits | What the kernel sees |
| --- | --- | --- |
| A blocking call to another service | `SocketInputStream.read` (`NioSocketImpl`) | `read`/`recv` or `poll` on the socket, sleeping until data arrives |
| Waiting for an asynchronous result: `CompletableFuture.get()`, a pool, a queue's `take` | `LockSupport.park` | `futex` wait; another thread performs the work |
| A contended `synchronized` block or `ReentrantLock` | monitor enter / `park` | `futex` wait |
| An event loop waiting for I/O, such as Netty's | `epollWait` (native) | `epoll_wait`, sleeping until a socket is ready |
| `Thread.sleep`, a timed `wait` | `park` with a timeout | `futex` wait armed with a timer |

That split is why an off-CPU profile of a JVM needs both stacks: the kernel
stack and the interval come from the scheduler, the Java stack supplies the
cause, and `jonoffcpu` exists to pair each kernel-measured interval with a
Java stack.

This matters because in most services latency is not CPU time. A request
that takes 200 ms may burn 5 ms of CPU and spend the rest waiting: for a reply
from another service, for a lock, for a write to be acknowledged, or for a CPU.
A CPU flame graph shows those 5 ms in detail and nothing about the other
195 ms. An off-CPU profile inverts that: it attributes the off-CPU time to the
stack that was waiting, so the 195 ms show up under the code that made the
call, took the lock, or waited for the write. Rendered as an
[off-CPU flame graph](https://www.brendangregg.com/FlameGraphs/offcpuflamegraphs.html),
frame widths are total off-CPU duration instead of sample counts, and the
widest towers are the waits worth investigating. CPU and off-CPU profiles
together account for a thread's whole lifetime, which is what Brendan Gregg's
[Thread State Analysis](https://www.brendangregg.com/tsamethod.html) method
asks for: explain latency by the states that dominate it, not by the one state
a CPU profiler happens to see.

Each off-CPU interval is measured, not inferred from periodic samples: the
scheduler records the exact moment a thread left the CPU and the exact moment
it returned, so every recorded interval is a real duration and the flame
graph's widths are microseconds of off-CPU time. Which intervals are recorded
is a separate choice. Recording one costs the thread a signal and a stack
walk, so a capture usually records a sample weighted by duration, and the
thresholds recorded with it turn it into unbiased estimates of the totals; see
[Choosing what to sample](capture.md#choosing-what-to-sample).
`jonoffcpu` measures the whole interval, from switch-out to switch-in, so
run-queue delay under CPU contention is included alongside sleeping. It also
records *why* the thread left the CPU — it blocked, or it was still runnable —
and by default records only the blocked intervals; see
[Why the thread left the CPU](#why-the-thread-left-the-cpu). And it splits each
interval's time into the two states, sleeping and runnable on a run queue; see
[Sleeping and run-queue time](#sleeping-and-run-queue-time).

## Why existing tools see half of it

CPU profilers show where a program burns cycles. They say nothing about the
time a thread spends *not* running: waiting on a lock, a socket, a disk, a
`park()`, or simply a busy run queue. In a typical service that waiting time,
not CPU time, is what shows up as latency.

Existing tools each see half of the picture:

- **Kernel tools** such as BCC's [`offcputime`](https://github.com/iovisor/bcc/blob/master/tools/offcputime.py)
  know exactly when a thread went off CPU and when it came back, and can
  capture the kernel stack. They cannot walk
  [JIT-compiled](https://openjdk.org/groups/hotspot/docs/HotSpotGlossary.html#JITCompilers)
  Java frames, so the
  Java side of the stack is missing or guessed from symbol maps.
- **JVM profilers** such as [async-profiler](https://github.com/async-profiler/async-profiler)
  walk Java stacks accurately, but their wall-clock mode is a timer-driven
  sampler. It sees that a thread was off-CPU at each tick, not how long the
  interval actually lasted, and it cannot tell sleeping from being runnable but
  descheduled. Its lock profiling measures contended Java locks precisely, but
  only those; the README compares the approaches in
  [Complementing async-profiler and JFR](../README.md#complementing-async-profiler-and-jfr).

`jonoffcpu` combines both: the kernel measures the interval, async-profiler
captures the Java stack, and a 64-bit key ties each measurement to its stack.

## Why the thread left the CPU

`sched_switch` tells the kernel why the outgoing thread is leaving the CPU,
and `jonoffcpu` records it on every interval:

| Reason | What the scheduler saw | Typical cause |
| --- | --- | --- |
| `blocked` | The thread left in a waiting state (`TASK_INTERRUPTIBLE`, `TASK_UNINTERRUPTIBLE`, …) | A futex (lock, `park`, `Future.get()`), a socket or `epoll` wait, a timer, disk I/O, a [page fault](https://en.wikipedia.org/wiki/Page_fault) |
| `runnable` | The thread left at an ordinary scheduling point while still `TASK_RUNNING` | **Preemption of running Java code**: a thread preempted by the scheduler tick is switched out on its return to user mode, where the kernel sees an ordinary `schedule()` — and `sched_yield` |
| `preempted` | The kernel preempted the thread at a preemption point inside the kernel | Preemption while the thread was in a system call or a page fault |

`blocked` covers three situations that the kernel cannot tell apart:

- **Waiting for work:** an idle [event loop](https://en.wikipedia.org/wiki/Event_loop)
  in [`epoll_wait`](https://man7.org/linux/man-pages/man7/epoll.7.html), or a
  [thread pool](https://en.wikipedia.org/wiki/Thread_pool) worker waiting for
  its next task.
- **Blocked while it had work to do:** a contended
  [lock](https://en.wikipedia.org/wiki/Lock_(computer_science)) or monitor, a
  reply from another service, a disk write, a future.
- **Blocked by the OS or the JVM:** a page fault that reads from disk, or a JVM
  [safepoint](https://openjdk.org/groups/hotspot/docs/HotSpotGlossary.html#safepoint)
  such as a
  [stop-the-world](https://en.wikipedia.org/wiki/Tracing_garbage_collection#Stop-the-world_vs._incremental_vs._concurrent)
  GC pause.

The Java stack tells them apart. The analysis recognizes waits for work by
their frames (`--waiting-from`, by default the bundled `preset:jvm-waiting`),
sets them apart as *waiting*, and ranks the rest as *blocked*; see
[Correlate](analysis.md#correlate).

`runnable` and `preempted` are both time spent *waiting for a CPU*: a stack
that is wide under them is where execution stopped, not what the thread was
waiting for, and the investigation belongs to CPU saturation,
[cgroup CPU throttling](https://docs.kernel.org/scheduler/sched-bwc.html),
thread-pool sizing or IRQ load rather than to that code. Measured
on a 16-CPU 7.1 kernel, more spinning threads than CPUs came back 2,861
`runnable` against 2 `preempted`, so read the two together.

The kernel keeps the original value (`prev_task_state`) and the `preempt` flag
next to the reason, and the agent and the correlator recompute the reason from
them for every row. The kernel also counts every switch-out by reason before
filtering, so even a blocked-only capture reports how often its threads were
denied the CPU; the report's `offCpuReasons` object carries those counts next
to the matched intervals of each selected reason. Which reasons a capture
records is set by `sampling.reasons`; see
[Choosing what to sample](capture.md#choosing-what-to-sample).

## Sleeping and run-queue time

The reason describes the *switch-out*. A `blocked` interval runs until the
thread is switched back in, so it holds two different waits: the time the
thread slept until it was woken, and then the time it waited on a run queue for
a CPU. `jonoffcpu` splits the two:

| Part | What it is | Where it comes from |
| --- | --- | --- |
| **sleeping** | A `blocked` interval up to its wakeup (sometimes called blocking time) | `duration − runqueue` |
| **runqueue** | Time runnable but waiting for a CPU: a `blocked` interval after its wakeup, and `runnable` and `preempted` intervals throughout | The growth of the scheduler's own `sched_info.run_delay` across the interval |

The kernel already accounts for every task's run-queue wait in
`task_struct.sched_info.run_delay` (the second field of
`/proc/<pid>/schedstat`, as the kernel's
[scheduler statistics](https://docs.kernel.org/scheduler/sched-stats.html)
document). The switch-out hook saves it and the switch-in hook
reads it again, so the split costs two field reads in hooks that run anyway,
with nothing new firing system-wide. A slow wakeup shows up as run-queue time:
a sleeper at nice 19 sharing a CPU with three busy threads waited 1.4 ms for
the CPU after each 1 ms sleep, against 0.1 µs when it had a CPU to itself.

It needs a kernel built with `CONFIG_SCHED_INFO`, which mainstream
distribution kernels enable through `CONFIG_TASK_DELAY_ACCT`
([delay accounting](https://docs.kernel.org/accounting/delay-accounting.html))
or `CONFIG_SCHEDSTATS`; the accounting runs whether or not delay accounting or
schedstats are switched on at runtime. On a kernel without it, the agent fails
to start rather than recording without the split; set `timeSplit.source: off`
to capture there anyway (see [Agent options](capture.md#agent-options)).

An interval is left **unsplit**, never guessed, when there is no reading: with
`timeSplit.source: off`, when the counter went backwards, or for a `blocked`
interval whose reading exceeds its duration. The scheduler's clock can be a few
microseconds stale when a running thread is switched out, so a `runnable` or
`preempted` interval's reading may slightly exceed its duration; those
intervals are run-queue time throughout either way. For every interval,
sleeping + runqueue + unsplit is exactly its duration, and the report's
`offCpuReasons` gives the three per reason, with a `timeSplit` object naming
the source and why any interval was left unsplit. `stacks --time` renders
either part, or both side by side; see
[Slice and filter with the stack profile](analysis.md#slice-and-filter-with-the-stack-profile).

## Further reading

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
- [async-profiler's profiling modes](https://github.com/async-profiler/async-profiler/blob/master/docs/ProfilingModes.md):
  the CPU, wall-clock, allocation and lock modes the agent can record alongside.
