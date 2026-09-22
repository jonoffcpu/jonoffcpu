# JONOFFCPU cookie transport proof

This crate contains the production libbpf-rs/CO-RE implementation used by the
Java agent.

Build the C CO-RE object and Rust/libbpf loader in the pinned container:

```sh
tools/build-in-docker.sh
```

Run the production scheduler-exit transport fixture:

```sh
tools/run-sched-exit-proof.sh
```

The production path records switch-out with `sched/sched_switch`, then observes
the resumed current task at `tp_btf/sched_exit_tp` when `is_switch=true`. This
BTF tracepoint runs after `finish_task_switch` and the runqueue unlock. It retains
the original current-task ID, comm, kernel-stack, and user-stack semantics while
using `BPF_PROG_TYPE_TRACING`, for which the kernel permits
`bpf_send_signal_task`.

The required proof on the tested 7.1.5 kernel delivers the full 64-bit cookie,
including bit 63, with `SI_KERNEL`, pidfd-backed process lifetime binding and
per-task interval state. The exact leader binding is checked again immediately
before the signal request.

The reusable collector exports the C ABI in `include/jonoffcpu_collector.h` from
`libjonoffcpu_native.so`. Its lifecycle is disabled `prepare`, configured `enable`,
quiesce/detach/drain `stop`, then `close`. Run the complete lifecycle and durable
NDJSON smoke fixture with:

```sh
tools/run-collector-smoke.sh
```

Run the exact process/thread task-storage lifetime fixture with:

```sh
tools/run-task-lifetime-proof.sh
```

An enabled collector polls its prepared pidfd in the same bounded worker loop as
ring consumption. Target exit immediately closes the BPF gate, detaches both
links, drains the ring and fsyncs an incomplete `captureEnd` with
`targetExited=true` and `incompleteReason=target_exited`; it does not wait for a
controller request or rediscover identity from a numeric PID. Later `stop` calls
return the cached terminal witness. Run the real child-target fixture with:

```sh
tools/run-target-exit-proof.sh
```

Terminal publication freezes one `captureEnd` before its first append and tracks
append, flush, and fsync separately. A transient flush or fsync failure resumes
only that durability step when `stop` or `close` arrives; an ambiguous append
failure is never retried. Automatic exit finalization does not spin after an I/O
failure. The process-level fault fixture injects one fsync `EIO` and verifies one
terminal row, a single explicit durability retry, and identical repeated `stop`
responses:

```sh
tools/run-target-exit-fsync-fault.sh
```

The private PID-namespace fixture proves that `targetPid=1` is not treated as a
host identity. The exact pidfd-bound BPF task supplies the host TGID before the
collector can enable signalling:

```sh
tools/run-collector-pidns-proof.sh
```

The collector requires zero `CLOCK_MONOTONIC` time-namespace offset because BPF
timestamps use the kernel base clock. This negative fixture creates a nonzero
offset and verifies that prepare fails closed:

```sh
tools/run-time-namespace-negative.sh
```

Both `captureStart` and `captureEnd` contain `wallClockCalibration`. The collector
takes nine consecutive `CLOCK_MONOTONIC`-before, `CLOCK_REALTIME`, and
`CLOCK_MONOTONIC`-after triples and retains the first sample with the narrowest
monotonic bracket. The object records all selected timestamps, the floor midpoint,
signed realtime-minus-midpoint offset, bracket width, and ceil-half midpoint
uncertainty as decimal nanosecond strings. The bracket bounds uncertainty about
where the realtime read occurred; it does not hide wall-clock adjustment between
the independent start and end calibrations. A clock error or reversed monotonic
bracket fails enable/stop instead of emitting an estimated value. End calibration
is cached across a bounded stop retry.

Duration bounds are independently optional and strict. Completed intervals must
be greater than the minimum and less than the maximum before probability
admission. Configuration accepts the signal number negotiated with the stack
capture backend; production code does not hardcode SIGPROF.

`captureStart` records a bounded signal-environment snapshot immediately before
the gate is armed, and `captureEnd` records another after both BPF links detach.
The snapshots include the selected signal/class and runtime real-time range,
target `RLIMIT_SIGPENDING`, shared-user `SigQ`, and a maximum 4096-thread
`SigBlk` audit with at most 32 blocked and unknown examples. They identify TIDs
as names from the collector's procfs PID namespace, record the target PID
namespace inode, and retain truncation or unavailable reasons. The collector
does not raise limits or modify another thread's mask. Status reads are capped
at 64 KiB, the whole snapshot gets at most 100 ms, and stop reserves three
quarters of its remaining deadline for ring drain. Pidfd checks bracket numeric
PID reads; target exit discards those observations instead of attributing a
reused PID.

The private PID-namespace fixture has an intentionally blocked thread and may
also exercise a real-time signal:

```sh
tools/run-collector-pidns-proof.sh
JONOFFCPU_SMOKE_SIGNAL=42 \
  JONOFFCPU_SMOKE_OUTPUT=/evidence/jonoffcpu-native-signal-environment-rt-source.ndjson \
  tools/run-collector-pidns-proof.sh
```

Ring callbacks consume at most 1024 records per cycle and materialize them
before accepting another batch. Kernel ring reservation loss and userspace
poll/write loss make `captureEnd.state` incomplete. Stop responses are cached
for safe retries, and a close timeout retains the native registry entry and
worker ownership.

The packaged agent JAR embeds musl bundles built by
`jonoffcpu-agent/tools/Dockerfile.native-bundle-musl`; the tools below are the
standalone musl proofs for the collector itself. Build and run the musl library
and collector inside the pinned Alpine image:

```sh
tools/build-musl-in-docker.sh
tools/run-collector-smoke-musl.sh
```

For the full OpenJDK 17 native-agent integration, including an isolated musl
async-profiler build, mixed CPU/allocation/wall/lock/JVM recording, private PID
namespace correlation, synthetic JFR generation and `jfrconv`, run:

```sh
tools/run-agent-smoke-musl.py \
  --ap-dir /path/to/async-profiler-cookie-source \
  --output /path/to/new-evidence-directory \
  --delivery queued
```

The runner copies both source trees into disposable container directories and
uses a separate Cargo target, so it does not modify either source tree's build
outputs. The result records source revisions, compiler/build logs, dynamic
dependencies, the original recording and correlation source, both offline
views, and a checked summary. A successful run also retains a checksum manifest
and the small deployable JONOFFCPU/async-profiler musl artifacts under
`distribution/`; disposable compiler outputs remain inside the removed
container.

Measure exact-cookie delivery to a minimal native signal handler with:

```sh
tools/run-signal-latency.sh
```

The handler takes an async-signal-safe `CLOCK_MONOTONIC` timestamp at entry and
stores it with the cookie in a fixed preallocated shared buffer. The report joins
cookies to source interval ends, so the latency includes BPF stack capture and
signal-request work after that end timestamp. It describes native signal delivery
only and does not include async-profiler or Java stack walking.

The same runner can verify real-time queueing, blocked-thread behavior and queue
exhaustion with `JONOFFCPU_LATENCY_SIGNAL`, `JONOFFCPU_LATENCY_BLOCKED_SLEEPS`,
`JONOFFCPU_LATENCY_PENDING_LIMIT`, `JONOFFCPU_LATENCY_SAMPLES` and
`JONOFFCPU_LATENCY_SOURCE`. Signal numbers in production must come from the profiler
handshake; a numeric real-time signal used by a fixture is not a portable default.

The historical KPROBE gate can be rerun separately (nonzero is expected):

```sh
tools/run-kprobe-gate.sh
```

`jonoffcpu_cookie.bpf.c` defaults to the original
`kprobe/finish_task_switch` experiment. Loading reaches
the resolved `bpf_send_signal_task` call and fails because the kernel does not
register that kfunc for `BPF_PROG_TYPE_KPROBE`.

The direct `fentry/finish_task_switch` experiment also remains reproducible with
`tools/run-finish-fentry-proof.sh`; this kernel cannot resolve the BTF function
name to its live compiler-localized `.isra.0` address. The clock-nanosleep
fentry fixture remains labelled `CONTROL_ONLY`. See the external implementation
`transport.md` and retained logs for the exact results.
