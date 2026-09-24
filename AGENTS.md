# Agent guide for jonoffcpu

`jonoffcpu` profiles completed Linux off-CPU intervals and correlates them with JVM
stacks captured by a patched async-profiler. Changes cross Java, JNI, Rust,
eBPF, JFR, and GitHub Actions, so preserve the contracts below and validate the
smallest relevant layer before running privileged end-to-end tests.

## Read the relevant documentation first

- [`README.md`](README.md): architecture, requirements, build, capture, and
  analysis workflow.
- [`jonoffcpu-agent/README.md`](jonoffcpu-agent/README.md): Java agent lifecycle,
  native bundle, configuration, and integration tests.
- [`jonoffcpu-correlator/OFFLINE.md`](jonoffcpu-correlator/OFFLINE.md): offline
  correlation, integrity, clipping, weighting, and partial-input contracts.
- [`jonoffcpu-native/README.md`](jonoffcpu-native/README.md): maintained libbpf-rs
  collector and its kernel proof tools.
- [`RELEASING.md`](RELEASING.md): publication, signing, tags, and release
  workflow.
- [`CODING.md`](CODING.md): code and test conventions, the test layout, and the
  time budget every test run keeps.

## Architecture contracts

- A capture has two authoritative inputs: the original combined JFR and the
  finalized correlation stream. Do not silently repair, guess, or join
  incomplete data in normal mode.
- Join records by capture identity and the exact 64-bit cookie. Timestamps are
  for clipping and delivery-delay analysis, never a heuristic join key.
- Protobuf is the single definition of every structured format. The
  capture stream and the agent/collector protocol are defined in
  `jonoffcpu-capture-codec/src/main/proto` (`jonoffcpu-capture.proto`,
  `jonoffcpu-collector.proto`); the collector generates its codec from them
  with protox (no protoc in the build containers), and
  `jonoffcpu-capture-codec` generates the one Java codec, with the full
  protobuf runtime, which the agent and the correlator both embed. The
  agent's manifest is defined in `jonoffcpu-agent/src/main/proto`, and the
  correlator's derived formats (the stack profile, the report, the analysis
  outputs and the JFR signal rows) in `jonoffcpu-correlator/src/main/proto`;
  only their owner generates them. Every stream record, control records
  included, is a typed message; do not add a second definition of any format.
  A default rendering of a profile must keep reproducing
  `jonoffcpu-offcpu-stacks.collapsed` byte for byte.
- The agent and the collector exchange encoded protobuf messages across JNI
  (collector ABI version 2): `prepare` and `enable` take a request message and
  every call returns a `CollectorReply`, whose state and typed error code the
  agent acts on. The C bridge only copies bytes; never put JSON or
  message-text classification back on that boundary.
- JSON is only a view of a message, for people and tools: every JSON file or
  line jonoffcpu writes or reads goes through `ProtoJson` (the proto3 JSON
  mapping: lowerCamelCase names, 64-bit integers as strings, enums by their
  prefixed UPPER_SNAKE value names, fields without presence always printed,
  unknown fields rejected). Never build or parse JSON by hand. The
  collector's proof tools print the same mapping through pbjson. The agent's
  configuration file is not a message dump: its reader maps its own
  spellings (`blocked`, `uniform`, `schedInfo`, ...) onto the enums.
- Native stacks are interned in the stream: one `stack` record per distinct BPF
  stack id, always written before the first observation that references it, and
  observations carry only the ids. Keep that ordering guarantee, keep the
  failure case on the observation (`kernelStackError`/`userStackError`, no
  record), and keep the classified records self-contained by re-expanding both
  stacks.
- The kernel interval is the actual off-CPU duration. The JVM stack is captured
  asynchronously after the task resumes. Keep source duration, signal delivery
  delay, and the observed JVM stack distinct in APIs and reports.
- Preserve strict lifecycle ordering: initialize outputs, start the profiler,
  verify signal/capture negotiation, enable sampling, then on stop detach the
  source, drain requested signals, stop the profiler, validate integrity, and
  publish completion last.
- `queued` and `coalescing` signal delivery have different loss behavior. Never
  fall back silently from one policy to the other.
- Sampling is one required `sampling` object: the switch-out `reasons` to keep
  (`blocked`, `runnable`, `preempted`; default `[blocked]`, serialized in that
  canonical order), optional strict `minOffCpuMicros`/`maxOffCpuMicros` bounds,
  applied in the kernel after the reason filter and before the admission
  policy, and `admission.policy` of `none`, `uniform`
  (`probability`) or `proportional` (`recordAllAboveMicros`). Each policy has
  exactly one parameter; `none` rejects bounds and reasons. The same resolved object is
  sent to the native source, echoed by it, and written into the manifest,
  `captureStart` and `analysisInputs`; consumers compare the copies as
  messages. The names here are the configuration file's spellings; the
  messages use the `Sampling` enums and `oneof admission`.
  Every observation row carries the exact `admissionThreshold` the kernel drew
  against, recomputable from the policy and the row's duration, so population
  estimates stay exact inverse-probability sums. Every observation likewise
  carries its switch-out `reason` beside the raw `sched_switch` arguments it
  was derived from (`prev_task_state`, `preempted`), and consumers recompute
  and check it. The switch-out hook is the raw `tp_btf/sched_switch`, whose
  `preempt` argument and pre-switch `prev_state` are authoritative; do not
  derive the reason from the trace event's encoded `prev_state` or from a
  re-read of `prev->__state`. Keep the reason (why the interval began) apart
  from the sleeping/run-queue split of its time. A future rate cap belongs in a
  separate `sampling.limit` block, orthogonal to `admission`, because its loss
  is not random and must be reported, not reweighted.
- The sleeping/run-queue split is one `timeSplit` object beside `sampling`, not
  inside it, because it changes what is measured rather than which intervals
  are kept: `source` is `schedInfo` (default) or `off`, resolved, echoed and
  compared exactly like `sampling`. `schedInfo` fails closed on a kernel whose
  BTF lacks `task_struct.sched_info.run_delay`; never fall back to `off`
  silently. Each observation carries the raw growth of `run_delay` across the
  interval (`runqueue_nanos`), dropped in the kernel only when the counter went
  backwards, and the consumers apply the one rule: a `blocked` interval's
  run-queue part is its tail, a `runnable` or `preempted` interval is run-queue
  time throughout, and anything else is reported unsplit, never guessed or
  clamped. Do not add a `sched_wakeup` hook for this: it fires for every wakeup
  on the host. Waker attribution, if wanted, belongs in a new source that
  hooks `sched_wakeup` (not `sched_waking`, which can fire before the
  switch-out it would close).
- Partial JFR and interrupted-capture modes must remain explicit and visibly
  different from a complete, integrity-verified result.

## Module boundaries

- `jonoffcpu-agent`: Java 17 bytecode, Java-agent/controller code, JNI bridge,
  packaged native libraries, and native integration fixtures. Keep JFR
  post-processing out of this module.
- `jonoffcpu-capture-codec`: Java 17 bytecode, the generated codecs of the
  capture stream and the collector protocol, the stream's framing
  (`CaptureFormat`) and the one JSON printer and parser (`ProtoJson`), plus
  the test fixtures both modules' tests share (`CaptureRecordFixture` to write
  and read streams, `CaptureFixtures` to build valid records). It is not
  published: the agent and the correlator embed it through `embeddedRuntime`
  and relocate its protobuf runtime.
- `jonoffcpu-correlator`: Java 21 bytecode, offline correlation library and CLI,
  including synthetic compatibility JFR output.
- `jonoffcpu-jfr-converter`: Java 21 build of async-profiler's converter
  straight from the submodule's `src/converter` sources, mirroring
  `async-profiler/pom-converter.xml`. It holds no sources of its own; converter
  changes go to the fork.
- `jonoffcpu-native`: maintained libbpf-rs CO-RE collector and privileged proof
  tools.
- `async-profiler`: pinned submodule containing the generic signal-cookie JFR
  support. Keep jonoffcpu lifecycle policy out of async-profiler. Commit changes
  to the `lhotari/async-profiler` fork separately, then update the submodule
  pointer here.

Do not edit generated build output. Treat generated BPF bindings and native
artifacts according to the scripts that own them rather than hand-editing them.

## Build and validation

Builds use Amazon Corretto 25. The capture agent targets Java 17, and the
correlator targets Java 21. The native collector uses Rust Edition 2024 and is
built in the pinned native-bundle container.

Ordinary local Java work builds only the current host architecture:

```sh
./gradlew :jonoffcpu-agent:check :jonoffcpu-correlator:check --no-daemon
```

Never build or test the other architecture's native bundle on a development
machine: an arm64 bundle on an x86-64 host (or the reverse) runs under QEMU
emulation and is far slower than it is worth. Build only the host architecture
locally, on Linux and on macOS alike, and let CI's native arm64 and x86-64
runners cover the other one. Windows development belongs in WSL2, which is a
Linux VM and follows the same rule.

Use `-PnativeArchitectures=all` only when both Linux architectures are
genuinely required and the machine can build both natively, or select `x86_64`
or `aarch64` explicitly. `-PnativeLibcs` selects
the C-library flavour and defaults to `musl`; CI and release packaging must
select `all` for both properties after placing all four prebuilt bundles in
the build directory. The musl bundle is built in
`jonoffcpu-agent/tools/Dockerfile.native-bundle-musl` and must keep depending
only on musl itself.

Java uses Palantir Java Format, and Gradle Kotlin scripts and the build-logic
Kotlin sources use ktlint, through Spotless. Run
`./gradlew spotlessApply` after editing Java and `./gradlew spotlessCheck` to
verify formatting; the Java modules' `check` tasks already depend on this
check.

For native Rust changes, run `cargo fmt` against `jonoffcpu-native/Cargo.toml` and
the relevant proof tools. CI checks formatting and builds the collector for both
supported architectures.

Native eBPF tests require Linux, suitable BTF/kernel features, Docker, and
privileged access. The agent's end-to-end tests are its `privileged-container`
integration tests, which run the packaged agent and correlator in Testcontainers;
lower-level proofs remain the scripts under `jonoffcpu-native/tools/`. Do not
replace kernel-level evidence with mocked unit tests. The end-to-end entry point is:

```sh
./gradlew :jonoffcpu-agent:integrationTest --no-daemon
```

CI builds and executes x86-64 and arm64 bundles on native runners, running the
integration tests once per C-library flavour (the glibc and Alpine musl Corretto
images). Do not add QEMU-based arm64 verification to CI. The arm64 collector must retain the
`libgcc` link needed by outlined atomics, and the native-bundle build must keep
rejecting unresolved `__aarch64_*` helpers.

When changing capture, shutdown, or correlation behavior, exercise the nearest
focused unit test first, then the end-to-end integration tests when the host supports them.
Check loss counters, completion markers, capture identity, output finalization,
and exact-cookie matches rather than only checking process exit status.

## Packaging and compatibility

- Keep the agent and correlator as shaded, self-contained JARs: the agent is a
  `-javaagent` JAR, the correlator a runnable one. Relocate bundled
  dependencies to avoid conflicts for users of their Java APIs. Gson stays
  embedded only because protobuf-java-util's `JsonFormat` needs it at run
  time; jonoffcpu code does not use it. The
  jfr-converter has no dependencies and keeps its upstream `one.*` packages and
  Apache-2.0 license.
- The agent JAR embeds Linux x86-64 and arm64 copies of the JNI bridge, native
  collector, and patched async-profiler, each in a glibc and a musl flavour,
  plus their checksum manifest. The agent selects the flavour from the C
  library mapped into the running JVM and never falls back to the other one.
- The profiler never records its own waits: the native collector's drain
  thread and the agent's controller thread (the thread that calls `prepare`
  with `excludeCallingThread`) are excluded by TID in the eBPF program,
  compared inside the target's PID namespace. Do not add profiler-owned
  threads whose sleeps would be captured without extending that exclusion.
- Every file the agent or the correlator creates is named `jonoffcpu-…`: the
  correlator's names are the `OutputFiles` constants, and the agent derives
  its manifest and default JFR from the stem of `correlationOutput` via
  `ManifestStore.sibling`. Never spell an output name inline.
- Declare every library and plugin version once, in
  `gradle/libs.versions.toml`. Modules apply the convention plugins in
  `build-logic/` (`jonoffcpu.java-conventions`, `publish-conventions`,
  `shaded-jar-conventions`, `protobuf-conventions`), which bring the
  third-party plugins as dependencies, so every module loads them from one
  class loader; the publish plugin's shared build service cannot cross class
  loaders. Put logic shared by modules there, and task logic in typed tasks
  under `build-logic/conventions/src/main/kotlin`.
- The build runs with the configuration cache, configure-on-demand, the build
  cache and parallel execution (`gradle.properties`). No project configures
  another (depend on another project's task by path), task actions capture
  only providers and plain values, never the build script, and configuration
  reads inputs through providers or value sources. Check a build change with
  `--configuration-cache-problems=warn` and by running it twice: the second run
  must say `Reusing configuration cache`.
- Keep public configuration, manifest, capture stream, report, and CLI changes backward
  compatible unless a format/version migration is designed and documented.
- Use supported public JDK JFR APIs in the correlator. Do not depend on
  `jdk.jfr.internal.*` implementation classes.
- CI and releases share [`.github/workflows/build-and-verify.yml`](.github/workflows/build-and-verify.yml).
  Keep verification behavior in that reusable workflow instead of duplicating
  it in CI and release jobs.

The packaged agent smoke (`PackagedAgentSmokeTest`) starts async-profiler with `jfrsync=profile` and
checks CPU, allocation, wall-clock, lock, signal-cookie, ordinary JDK, and test
marker events in the combined JFR before checking native capture, shutdown, and
offline correlation. Keep those event assertions when changing profiler
initialization or packaging.

## Change hygiene

- Keep changes focused and update the relevant documentation when a contract,
  option, artifact, or workflow changes.
- Diagram sources are d2 files in `docs/diagrams/`; the README embeds the
  rendered SVGs from `docs/images/`. After editing a source, run
  `docs/diagrams/render.sh` (renders changed sources; `--watch NAME` for live
  editing) and commit the source and the SVG together.
- New source must have provenance compatible with the repository license. Do
  not copy code from sources with unknown or incompatible licensing.
- Avoid unbounded buffers and waits in signal, shutdown, and correlation paths.
  Signal handlers and eBPF programs have stricter safety constraints than
  ordinary application code; do not allocate, block, or call unsafe APIs there.
- Do not publish releases or tags as part of routine verification.
- Before the project adopts normal multi-commit history, the repository uses a
  single amended root commit. Confirm the current history policy before
  committing or force-pushing, especially once external collaboration begins.
