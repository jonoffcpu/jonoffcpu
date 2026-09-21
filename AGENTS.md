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

## Architecture contracts

- A capture has two authoritative inputs: the original combined JFR and the
  finalized correlation NDJSON stream. Do not silently repair, guess, or join
  incomplete data in normal mode.
- Join records by capture identity and the exact 64-bit cookie. Timestamps are
  for clipping and delivery-delay analysis, never a heuristic join key.
- The kernel interval is the actual off-CPU duration. The JVM stack is captured
  asynchronously after the task resumes. Keep source duration, signal delivery
  delay, and the observed JVM stack distinct in APIs and reports.
- Preserve strict lifecycle ordering: initialize outputs, start the profiler,
  verify signal/capture negotiation, enable sampling, then on stop detach the
  source, drain requested signals, stop the profiler, validate integrity, and
  publish completion last.
- `queued` and `coalescing` signal delivery have different loss behavior. Never
  fall back silently from one policy to the other.
- Probability and duration filters compose. `sampleProbability` is a decimal
  from `0.000` through `1.000`; `minOffCpuMicros` and `maxOffCpuMicros` are
  independent optional strict bounds.
- Partial JFR and interrupted-capture modes must remain explicit and visibly
  different from a complete, integrity-verified result.

## Module boundaries

- `jonoffcpu-agent`: Java 17 bytecode, Java-agent/controller code, JNI bridge,
  packaged native libraries, and native integration fixtures. Keep JFR
  post-processing out of this module.
- `jonoffcpu-correlator`: Java 21 bytecode, offline correlation library and CLI,
  including synthetic compatibility JFR output.
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

Use `-PnativeArchitectures=all` only when both Linux bundles are required, or
select `x86_64` or `aarch64` explicitly. CI and release packaging must select
`all` after placing both prebuilt bundles in the build directory.

Java uses Palantir Java Format and Gradle Kotlin scripts use ktlint through Spotless. Run
`./gradlew spotlessApply` after editing Java and `./gradlew spotlessCheck` to
verify formatting; the Java modules' `check` tasks already depend on this
check.

For native Rust changes, run `cargo fmt` against `jonoffcpu-native/Cargo.toml` and
the relevant proof tools. CI checks formatting and builds the collector for both
supported architectures.

Native eBPF tests require Linux, suitable BTF/kernel features, Docker, and
privileged access. Use the existing scripts under `jonoffcpu-native/tools/` and
`jonoffcpu-agent/tools/`; do not replace kernel-level evidence with mocked unit
tests. The packaged end-to-end entry point is:

```sh
python3 jonoffcpu-agent/tools/run-packaged-agent-smoke.py --help
```

CI builds and executes x86-64 and arm64 bundles on native runners. Do not add
QEMU-based arm64 verification to CI. The arm64 collector must retain the
`libgcc` link needed by outlined atomics, and the native-bundle build must keep
rejecting unresolved `__aarch64_*` helpers.

When changing capture, shutdown, or correlation behavior, exercise the nearest
focused fixture first, then the packaged smoke test when the host supports it.
Check loss counters, completion markers, capture identity, output finalization,
and exact-cookie matches rather than only checking process exit status.

## Packaging and compatibility

- Keep the agent and correlator as shaded executable JARs. Relocate bundled
  dependencies to avoid conflicts for users of their Java APIs.
- The agent JAR embeds Linux x86-64 and arm64 copies of the JNI bridge, native
  collector, and patched async-profiler, plus their checksum manifest.
- Keep public configuration, manifest, NDJSON, report, and CLI changes backward
  compatible unless a format/version migration is designed and documented.
- Use supported public JDK JFR APIs in the correlator. Do not depend on
  `jdk.jfr.internal.*` implementation classes.
- CI and releases share [`.github/workflows/build-and-verify.yml`](.github/workflows/build-and-verify.yml).
  Keep verification behavior in that reusable workflow instead of duplicating
  it in CI and release jobs.

The packaged Java-agent smoke starts async-profiler with `jfrsync=profile` and
checks CPU, allocation, wall-clock, lock, signal-cookie, ordinary JDK, and test
marker events in the combined JFR before checking native capture, shutdown, and
offline correlation. Keep those event assertions when changing profiler
initialization or packaging.

## Change hygiene

- Keep changes focused and update the relevant documentation when a contract,
  option, artifact, or workflow changes.
- New source must have provenance compatible with the repository license. Do
  not copy code from sources with unknown or incompatible licensing.
- Avoid unbounded buffers and waits in signal, shutdown, and correlation paths.
  Signal handlers and eBPF programs have stricter safety constraints than
  ordinary application code; do not allocate, block, or call unsafe APIs there.
- Do not publish releases or tags as part of routine verification.
- Before the project adopts normal multi-commit history, the repository uses a
  single amended root commit. Confirm the current history policy before
  committing or force-pushing, especially once external collaboration begins.
