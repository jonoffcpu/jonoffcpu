# jonoffcpu signal capture agent

The agent owns one source/async-profiler capture and writes two authoritative
artifacts: the original JFR and a self-contained correlation NDJSON file. The
last correlation row is written only after source drain, an identity-guarded AP
stop, clean public-JDK JFR parsing, and matching terminal counters.

Build with Amazon Corretto 25, Docker buildx, and the checked-out async-profiler
submodule:

```sh
./gradlew :jonoffcpu-agent:build
```

The build creates the Java 17 compatible
`io.github.lhotari:jonoffcpu-agent:1.0.0` artifact. It embeds its runtime
configuration dependencies and builds the native components with Corretto 25 in
the pinned Rust container for the current host architecture by default. To build
both Linux x86-64 and aarch64 bundles, use `-PnativeArchitectures=all`. If an
x86-64 Docker host has no arm64 binfmt handler, install one once before that
multi-architecture build:

```sh
docker run --privileged --rm tonistiigi/binfmt --install arm64
```

The resulting JAR contains `libjonoffcpu.so`, `libjonoffcpu_native.so`, and
`libasyncProfiler.so` for every selected architecture under `META-INF/native/`.
`META-INF/native/SHA256SUMS` records every native digest. `verifyRuntimeJar`
independently checks those digests and each ELF architecture, so an x86-64 binary
cannot be mislabeled as aarch64. At runtime, the agent extracts the matching
bundle into an owner-only temporary directory, verifies each digest, and loads
the colocated libraries. Nothing has to be installed outside the JAR.

The native Docker stages intentionally use the running kernel's BTF to compile
the CO-RE objects embedded in the collector. The architecture tasks are also
available separately as `buildLinuxX86_64Native` and
`buildLinuxAarch64Native`.

Use `-PnativeArchitectures=x86_64` or `-PnativeArchitectures=aarch64` to select
one bundle explicitly. `-PnativeArchitectures=all` selects both; release builds
use this setting. Host-native JNI tests run when the selection includes the
current host architecture.

The publishable artifact is
`build/libs/jonoffcpu-agent-1.0.0.jar`. Create a YAML configuration containing the
output path and the async-profiler options:

```yaml
correlationOutput: /data/run.correlation.ndjson
asyncProfilerOptions: event=cpu,alloc=2m,jfrsync=profile,file=/data/run.jfr
sampleProbability: "0.010"
minOffCpuMicros: 1000
```

The agent uses SnakeYAML's safe constructor, rejects duplicate and unknown keys,
and does not allow aliases. Quote `sampleProbability` when its exact decimal
spelling should be retained in capture metadata.

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
`io.github.lhotari.jonoffcpu.agent.SignalCaptureAgent.start(yamlOrConfigPath)` and later
`SignalCaptureAgent.stop()`. The same JAR supports `premain` and `agentmain`.

The older raw `-agentpath` entry point remains available for development. In
that mode, everything before `asprofpath` belongs to jonoffcpu and everything
after it is forwarded to async-profiler:

```sh
java -agentpath:/path/to/libjonoffcpu.so=jonoffcpuoutput=/data/run.correlation.ndjson,asprofpath=/path/to/libasyncProfiler.so,event=cpu,jfrsync=profile,file=/data/run.jfr ...
```

Options before `asprofpath` belong to JONOFFCPU:

- `jonoffcpuoutput` (required): exact correlation NDJSON path.
- `jonoffcpudelivery=queued|coalescing`: signal delivery policy; defaults to `queued`.
- `samplethreshold`: requested decimal probability in the inclusive range
  `0.0..1.0`, written without exponent notation. The controller multiplies the
  exact decimal by `2^32` and rounds down, so the effective probability never
  exceeds the request. It persists both `requestedSampleProbability` and the effective
  integer `sampleThreshold`; only the integer is sent to the native source.
  `1` admits every eligible interval. `0` selects profiler-only mode: the
  eBPF source is never prepared or enabled, async-profiler runs without
  `signalcookie`, and the correlation output holds a single `captureFinalized`
  row with `state: "profilerOnly"`.
- `min-off-cpu-micros`: optional strict lower duration bound in microseconds.
- `max-off-cpu-micros`: optional strict upper duration bound in microseconds.
- `nativestoptimeoutmillis`, `deliverygracemillis`, and
  `shutdowntimeoutmillis`: bounded lifecycle timeouts.

The source applies duration filters and probability together. A duration is
eligible only when it is strictly greater than the configured minimum and
strictly less than the configured maximum. Omitting either bound leaves that
side unbounded.

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
java -agentpath:/path/to/build/lib/libjonoffcpu.so=jonoffcpuoutput=/data/run.correlation.ndjson,jonoffcpudelivery=coalescing,asprofpath=/path/to/libasyncProfiler.so,event=cpu,jfrsync=profile,file=/data/run.jfr ...
```

A `cookiesignal` option in the async-profiler options may
select a specific available signal within the chosen policy's allowed pool;
async-profiler validates ownership and collision constraints.

The companion Java controller runs on one daemon worker. A JVM shutdown hook
submits the same serialized stop operation and waits only for its configured
budget; it never cancels a native stop or frees a handle still owned by a worker.
Applications may call `io.github.lhotari.jonoffcpu.agent.SignalCaptureAgent.stop()` for reliable
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

## Native integration smoke

CI runs a packaged end-to-end smoke test on native x86-64 and arm64 runners.
To run the same check against already-built shaded JARs, use:

```sh
python3 tools/run-packaged-agent-smoke.py \
  --agent-jar build/libs/jonoffcpu-agent-1.0.0.jar \
  --correlator-jar ../jonoffcpu-correlator/build/libs/jonoffcpu-correlator-1.0.0.jar \
  --test-classes build/classes/java/test \
  --java-home "$JAVA_HOME" \
  --output /path/to/new-smoke-directory
```

The check starts the shaded agent JAR in privileged Docker, records a finite
mixed workload through the native eBPF source and async-profiler, finalizes the
capture, and invokes the shaded correlator JAR. It requires at least one valid,
identity-verified off-CPU match.

An opt-in smoke test exercises the actual native launcher, BPF source, mixed
CPU/allocation/wall/lock/JVM recording and both offline output formats. It uses
privileged Docker and a mounted glibc JDK, and requires a built matching
async-profiler branch with cookie support:

```sh
python3 tools/run-native-agent-smoke.py \
  --ap-dir /path/to/async-profiler \
  --java-home /path/to/jdk \
  --output /path/to/new-smoke-directory \
  --delivery queued
```

Run `--delivery coalescing` into a separate new directory for the other policy.
The test checks target thread identity in a private PID namespace, mixed event
categories through `RecordingFile`, and synthetic JFR conversion with `jfrconv`.
It retains commands, logs, original inputs and derived views in the output
directory. It is a functional integration test, not a throughput benchmark.

The Alpine/musl variant builds the native dependencies and matching profiler in
an isolated container, runs the same checks, and retains a small runtime bundle
with dependency and checksum manifests:

```sh
python3 ../jonoffcpu-native/tools/run-agent-smoke-musl.py \
  --ap-dir /path/to/async-profiler \
  --output /path/to/new-musl-directory
```

After building the glibc agent and running its smoke once (which prepares the
runtime image), validate automatic finalization without application stop calls:

```sh
python3 tools/run-native-agent-shutdown.py \
  --ap-dir /path/to/async-profiler --java-home /path/to/jdk \
  --output /path/to/new-shutdown-directory
```

This checks normal return, `System.exit(0)` and SIGTERM. The separate
`tools/run-native-agent-interruptions.py` checks abrupt termination and an
async-profiler timeout or external synchronized-recording stop before the native
collector stops. Abrupt termination must remain incomplete; observations after
the profiler admission cutoff must never contribute duration to matched stacks.
All of these are functional/lifecycle checks, not overhead benchmarks.
