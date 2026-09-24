# jonoffcpu signal capture agent

The agent owns one source/async-profiler capture and writes two authoritative
artifacts: the original JFR and a self-contained correlation stream. The
last correlation record, `captureFinalized`, is written only after source drain,
an identity-guarded AP stop, clean public-JDK JFR parsing, and matching terminal
counters.

Build with Amazon Corretto 25, Docker buildx, and the checked-out async-profiler
submodule:

```sh
./gradlew :jonoffcpu-agent:build
```

The build creates the Java 17 compatible
`io.github.jonoffcpu:jonoffcpu-agent:1.0.0` artifact. It embeds its runtime
configuration dependencies and builds the native components with Corretto 25 in
the pinned Rust container for the current host architecture by default. To build
both Linux x86-64 and aarch64 bundles, use `-PnativeArchitectures=all`. If an
x86-64 Docker host has no arm64 binfmt handler, install one once before that
multi-architecture build:

```sh
docker run --privileged --rm tonistiigi/binfmt --install arm64
```

The resulting JAR contains `libjonoffcpu.so`, `libjonoffcpu_native.so`, and
`libasyncProfiler.so` for every selected platform under `META-INF/native/`. A
platform is an architecture linked against one C library: `linux-x86_64` and
`linux-aarch64` are glibc bundles built in the Debian-based
`tools/Dockerfile.native-bundle`; `linux-musl-x86_64` and `linux-musl-aarch64`
are musl bundles for Alpine-based JVMs built in the Alpine-based
`tools/Dockerfile.native-bundle-musl`. Each library depends only on its C
library and bundle siblings; the musl Dockerfile enforces that with
`tools/check-musl-needed.sh`. `META-INF/native/SHA256SUMS` records every native
digest. `verifyRuntimeJar` independently checks those digests, each ELF
architecture, and each C-library flavour, so an x86-64 binary cannot be
mislabeled as aarch64 and a glibc binary cannot be mislabeled as musl.

At runtime, the agent detects the JVM's C library from the loader mapped in
`/proc/self/maps` (falling back to the presence of `/lib/ld-musl-<arch>.so.1`),
extracts the matching bundle into an owner-only temporary directory, verifies
each digest, and loads the colocated libraries. Nothing has to be installed
outside the JAR. If the JAR has no bundle for the detected platform, startup
fails with a message listing the embedded bundles rather than trying another
flavour. `-Dio.github.jonoffcpu.agent.nativeLibc=glibc` or `=musl` overrides
the detection.

The native Docker stages intentionally use the running kernel's BTF to compile
the CO-RE objects embedded in the collector. The platform tasks are also
available separately as `buildLinuxX86_64Native`, `buildLinuxAarch64Native`,
`buildLinuxMuslX86_64Native`, and `buildLinuxMuslAarch64Native`.

Use `-PnativeArchitectures=x86_64` or `-PnativeArchitectures=aarch64` to select
one architecture explicitly; `-PnativeArchitectures=all` selects both.
`-PnativeLibcs` selects the C-library flavour: `musl` (the default), `glibc`,
`all`, or `current` for the build JVM's own C library. Release builds use
`-PnativeArchitectures=all -PnativeLibcs=all`. Host-native JNI tests run when
the selection includes the current host's architecture and C library.

The publishable artifact is
`build/libs/jonoffcpu-agent-1.0.0.jar`. Create a YAML configuration containing the
output path and the async-profiler options:

```yaml
correlationOutput: /data/jonoffcpu-capture.pb
asyncProfilerOptions: event=cpu,alloc=2m,jfrsync=profile,file=/data/jonoffcpu-capture.jfr
sampling:
  minOffCpuMicros: 100
  admission:
    policy: proportional
    recordAllAboveMicros: 10000
```

The agent writes the manifest next to the stream as `<stem>.manifest.json`
(`/data/jonoffcpu-capture.manifest.json` above) and, when `file=` is omitted,
records the JFR as `<stem>.jfr`; the stem is `correlationOutput` without its
final extension. Naming the stream `jonoffcpu-capture.pb` therefore keeps
every capture file recognisable.

The manifest is an audit record for people and tools, rewritten atomically at
every lifecycle step (at most 1 MiB). Its single definition is the
`io.github.jonoffcpu.agent.v1.Manifest` message in
[`src/main/proto/jonoffcpu-manifest.proto`](src/main/proto/jonoffcpu-manifest.proto),
printed in protobuf's proto3 JSON mapping: lowerCamelCase field names, 64-bit
integers as decimal strings, enums by their value names (`"state":
"MANIFEST_STATE_COMPLETE"`), and every field without presence printed even at
its default. It records the lifecycle `state` and `complete` flag, the capture
`mode`, the resolved `sampling` and `timeSplit`, async-profiler's identity and
stop receipt, every reply of the native collector verbatim as a
`CollectorReply` (`nativePrepare`, `nativeEnable`, `nativeStop`, `nativeClose`,
and the cleanup replies after a failed start), the verified `sourceCaptureEnd`,
the `analysisInputs` the footer carries, and, while a capture is failed or
incomplete, a `failure` with its `FailureCode` and, when the collector reported
it, the collector's `CollectorError`. The correlator never reads the manifest.

The agent uses SnakeYAML's safe constructor, rejects duplicate and unknown keys,
and does not allow aliases. The configuration keeps its own spellings
(`reasons: [blocked, runnable]`, `policy: uniform`, `source: schedInfo`,
`signalDelivery: queued`); the agent maps them to the capture schema's messages
and enums, and rejects the schema's enum names (`OFF_CPU_REASON_BLOCKED`) there.
Integers must be YAML integers, not quoted or decimal. The `sampling` block is required; the top-level
README's [Choosing what to sample](../README.md#choosing-what-to-sample)
explains its policies. Quote a `uniform` policy's `probability` when its exact
decimal spelling should be retained in capture metadata.

Then start the application with the Java agent:

```sh
java -javaagent:/path/to/jonoffcpu-agent-1.0.0.jar=/path/to/jonoffcpu.yaml ...
```

The agent first loads the embedded async-profiler library through the JVM so
that async-profiler receives the active Java VM and can enable `jfrsync`. The
controller then forwards `asyncProfilerOptions` through async-profiler's native
C API and adds only its cookie session arguments. The JAR does not embed or call
`one.profiler.AsyncProfiler`. Combined CPU, allocation, wall, lock, `jfrsync`,
and jonoffcpu signal events therefore use the same original JFR.

Applications that already include the artifact as a runtime dependency may call
`io.github.jonoffcpu.agent.SignalCaptureAgent.start(yamlOrConfigPath)` and later
`SignalCaptureAgent.stop()`. The same JAR supports `premain` and `agentmain`.

The older raw `-agentpath` entry point remains available for development. In
that mode, everything before `asprofpath` belongs to jonoffcpu and everything
after it is forwarded to async-profiler:

```sh
java -agentpath:/path/to/libjonoffcpu.so=jonoffcpuoutput=/data/jonoffcpu-capture.pb,asprofpath=/path/to/libasyncProfiler.so,event=cpu,jfrsync=profile,file=/data/jonoffcpu-capture.jfr ...
```

Options before `asprofpath` belong to JONOFFCPU:

- `jonoffcpuoutput` (required): exact correlation stream path. The stream is
  length-delimited protobuf, defined by `jonoffcpu-capture-codec/src/main/proto/jonoffcpu-capture.proto`.
- `jonoffcpudelivery=queued|coalescing`: signal delivery policy; defaults to `queued`.
- `sampling-policy=none|uniform|proportional` (required): the admission policy,
  `sampling.admission.policy` in YAML.
- `sampling-reasons`: the switch-out reasons to record, joined with `+` because
  `,` separates options, for example `sampling-reasons=blocked+runnable`;
  `sampling.reasons` in YAML. Defaults to `blocked`.
- `sampling-probability`: the `uniform` policy's decimal probability in the
  inclusive range `0.0..1.0`, written without exponent notation. The controller
  multiplies the exact decimal by `2^32` and rounds down, so the effective
  probability never exceeds the request; it persists both the spelling as
  `probability` and the integer `probabilityThreshold`. `1` admits every
  eligible interval. Exactly `0` resolves to policy `none`; a positive value
  that rounds down to no draws is rejected.
- `record-all-above-micros`: the `proportional` policy's reference duration in
  microseconds.
- `min-off-cpu-micros`: optional strict lower duration bound in microseconds.
- `max-off-cpu-micros`: optional strict upper duration bound in microseconds.
- `time-split=schedInfo|off`: where each interval's run-queue part comes from,
  `timeSplit.source` in YAML. Defaults to `schedInfo`; `off` is for a kernel
  without `CONFIG_SCHED_INFO`, where `schedInfo` fails at prepare.
- `nativestoptimeoutmillis`, `deliverygracemillis`, and
  `shutdowntimeoutmillis`: bounded lifecycle timeouts.

Policy `none` selects profiler-only mode: the eBPF source is never prepared or
enabled, async-profiler runs without `signalcookie`, and the correlation output
holds a single `captureFinalized` record with state
`FINALIZED_STATE_PROFILER_ONLY` and no source artifact in its `analysisInputs`.

The source applies the reason filter first: only intervals whose switch-out
reason is in `sampling.reasons` (`blocked` unless configured) are eligible, and
the others are counted by reason in `captureEnd` (`switchOutsBlocked`,
`switchOutsRunnable`, `switchOutsPreempted`, `reasonRejections`,
`reasonRejectedDurationMicros`). The resolved list is kept in the order
`blocked`, `runnable`, `preempted` whatever order it was given in. Each
observation record carries its `reason` next to the raw `sched_switch` arguments
it was derived from, `prevTaskState` and `preempted`; the agent recomputes the
reason and checks it was selected for every record at stop, as it does the
admission threshold.

The resolved `timeSplit` message (source `TIME_SPLIT_SOURCE_SCHED_INFO` unless
configured) is sent to the source, echoed by it and written into the manifest,
`captureStart` and `analysisInputs` like `sampling`. Under `schedInfo` each
observation record carries `runqueueNanos`, the growth of the scheduler's
`sched_info.run_delay` across the interval, from which the correlator splits the
interval into sleeping and run-queue time; `captureEnd` counts readings the
kernel dropped in `runqueueInversions`. The agent rejects a record that carries
a reading under `off`.

The agent talks to the native collector through its JNI bridge in encoded
`jonoffcpu-collector.proto` messages: `prepare` takes a `PrepareRequest`,
`enable` an `EnableRequest`, and every call returns a `CollectorReply` of ABI
version 2. The agent checks each reply as a message: the echoed `sampling`,
`timeSplit` and `verifiedIdentity` must equal what it sent or what `prepare`
returned, and the `captureEnd` the collector reports must equal the one in the
stream. An error reply with state `COLLECTOR_STATE_STOPPING` or
`COLLECTOR_STATE_CLOSING` keeps the handle owned for a retry.

The source applies the duration bounds before the admission policy. A duration
is eligible only when it is strictly greater than the configured minimum and
strictly less than the configured maximum; omitting either bound leaves that
side unbounded. The resolved `sampling` message is sent to the native source,
echoed back by it, and written unchanged into the manifest, the `captureStart`
record, and the `captureFinalized` footer's `analysisInputs`, so every consumer
compares the same message. Each distinct native stack is symbolized once and
written as its own `stack` record; observations reference it through
`kernelStackId` and `userStackId`, or carry `kernelStackError`/`userStackError`
when the kernel could not produce one. A stack record always precedes the first
observation that references it. Each observation record carries `admissionThreshold`,
the exact 32-bit-scaled threshold the kernel drew against for that interval
(`4294967296` means certain admission). Under `uniform` it is the policy's
`probabilityThreshold`; under `proportional` it is `2^32` for a duration at
or above `recordAllAboveMicros` and otherwise `duration * 2^32 / reference`
computed in 64-bit arithmetic with the nanosecond reference shifted right until
it fits in 32 bits (and the duration shifted by the same amount). The agent
recomputes it for every record at stop and rejects a stream where any record
disagrees.

`queued` requests a dedicated real-time signal from async-profiler. `coalescing`
explicitly requests a spare standard signal, trading missing observations for
avoiding a queue of individual pending signals. There is no automatic fallback
between policies. The selected policy and signal appear in the JFR capture
context, source header and final receipt. Use separate JVM runs to compare policies:
the profiler retains its reserved signal handler for safely handling late delivery.

Coalescing retains the first pending signal's cookie, not the newest observation.
Either policy can therefore produce a delayed signal-delivery stack. The offline
`--max-handler-delay-ns` filter is independent of the delivery choice; see
[offline correlation](../jonoffcpu-correlator/OFFLINE.md). Real-time queues also have limits. In the BPF
scheduler path, the helper can return success before a deferred send fails, so
request-success counters do not establish delivery. The correlator retains
unmatched observations instead of inventing a matching stack.

For the Java-agent configuration, choose coalescing cookie delivery with
`signalDelivery: coalescing`. For example, the equivalent raw native-agent
form is:

```sh
java -agentpath:/path/to/build/lib/libjonoffcpu.so=jonoffcpuoutput=/data/jonoffcpu-capture.pb,jonoffcpudelivery=coalescing,asprofpath=/path/to/libasyncProfiler.so,event=cpu,jfrsync=profile,file=/data/jonoffcpu-capture.jfr ...
```

A `cookiesignal` option in the async-profiler options may
select a specific available signal within the chosen policy's allowed pool;
async-profiler validates ownership and collision constraints.

The companion Java controller runs on one daemon worker. A JVM shutdown hook
submits the same serialized stop operation and waits only for its configured
budget; it never cancels a native stop or frees a handle still owned by a worker.
Applications may call `io.github.jonoffcpu.agent.SignalCaptureAgent.stop()` for reliable
explicit finalization.

JFR correlation and derived output generation live in the separate Java 21
`jonoffcpu-correlator` artifact. The capture agent uses only the public JDK JFR reader
to validate its finalized recording and has no dependency on that artifact.

## Maven publication

The Vanniktech Gradle Maven Publish Plugin configures the Central Portal
publication, signing, runtime JAR, sources, Javadoc, and POM metadata. Validate
the publication locally with:

```sh
./gradlew :jonoffcpu-agent:publishToMavenLocal
```

See the repository's [release guide](../RELEASING.md) for the tag-driven release
flow, Central Portal setup, and signing configuration.

## Tests

`src/test` holds unit tests, which run on any platform with Java and need no native code. `src/integrationTest`
holds the tests that need the native bundle, the packaged JARs or a Linux kernel. `src/testFixtures` holds what both
share: the workloads the end-to-end tests launch. Capture streams are written and read with the capture codec's
`CaptureRecordFixture`. `check` runs both suites:

```sh
./gradlew :jonoffcpu-agent:check
```

The integration tests come in three kinds, by JUnit tag:

- `host-native` tests load the bundle for the host's architecture into the test JVM: JNI collector replies,
  extraction and the async-profiler C API, and C-library detection. On a Linux host whose C library is selected
  (`-PnativeLibcs=glibc` on a glibc host) they run in the test JVM. With `-PintegrationTestsInContainer=true`, the
  default on any other host, including macOS, they run inside a Corretto container of each selected C library
  through the JUnit Console Launcher (`containerIntegrationTest<Platform>`), so a glibc host can also test the musl
  bundle. The container always runs at the host's own architecture, never under emulation.
- `privileged-container` tests run the packaged agent end to end with Testcontainers, once for each selected C
  library of the host's architecture, in the glibc or musl Corretto image the build pins, privileged, against the
  host kernel's BTF and tracefs. `PackagedAgentSmokeTest` records a finite mixed workload with
  `jfrsync=profile`, checks the CPU, allocation, wall-clock, lock, signal-cookie, JDK and marker events of the
  combined recording, and correlates it with the packaged correlator JAR into identity-verified matches whose
  switch-out reasons and sleeping/run-queue split account for every matched interval. `AgentShutdownTest`
  finalizes from JVM shutdown alone (return, `System.exit`, SIGTERM). `AgentAbruptExitTest` checks that a
  halted or SIGKILLed JVM leaves only explicitly incomplete artifacts the correlator refuses, and
  `AsyncProfilerFirstStopTest` that when async-profiler stops first (its timeout, or the JFR master recording
  stopping) every interval after its cutoff is explicitly unmatched. They need a Linux host with Docker; elsewhere they are skipped.
- `packaged-jar` tests (`packagedJarTest`) run against the shaded JAR alone, without the module's classes or its
  unrelocated dependencies, to prove the relocation.

The musl flavour's lower-level proof tools remain under `../jonoffcpu-native/tools/`:

```sh
python3 ../jonoffcpu-native/tools/run-agent-smoke-musl.py \
  --ap-dir /path/to/async-profiler \
  --output /path/to/new-musl-directory
```

All of these are functional and lifecycle checks, not overhead benchmarks.
