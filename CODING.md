# Code and test conventions

How code and tests are written in jonoffcpu. The architecture contracts they serve are in
[`AGENTS.md`](AGENTS.md); the build and the workflow are in [`README.md`](README.md).

## Code

- **Match the surrounding code.** Naming, comment density and idiom follow the file you are editing. Comments say why,
  and state the contract a reader cannot see from the code; they do not narrate what it does.
- **Formatting is mechanical.** Java uses Palantir Java Format, Gradle Kotlin scripts and the build-logic Kotlin
  sources use ktlint, both through Spotless: run `./gradlew spotlessApply` after editing and let `check` verify it.
  Rust uses `cargo fmt`.
- **Bytecode levels are part of the contract.** The agent compiles for Java 17 and the correlator for Java 21, with
  Amazon Corretto 25 building both. Code in the agent's test and integration-test source sets compiles for Java 17
  too, so APIs such as `List.getFirst()` are not available there.
- **Name every output once.** Every file the agent or the correlator creates is named `jonoffcpu-…`, from the
  `OutputFiles` constants or `ManifestStore.sibling`, never spelled inline.
- **Bound every wait and buffer** in signal, shutdown and correlation paths. Signal handlers and eBPF programs do not
  allocate, block or call unsafe APIs.
- **Follow the [Gradle best practices](https://docs.gradle.org/current/userguide/best_practices_index.html)**
  — AI agents should read the
  [AsciiDoc source](https://github.com/gradle/gradle/blob/master/platforms/documentation/docs/src/docs/userguide/best-practices/best_practices_index.adoc),
  which is plain text and cheaper to parse than the rendered HTML.
- **The build stays configuration-cache clean.** Versions live once in `gradle/libs.versions.toml`; shared build logic
  lives in the convention plugins and typed tasks under `build-logic/`; no project configures another; task actions
  capture only providers and plain values. Check a build change by running it twice with
  `--configuration-cache-problems=warn`: the second run must reuse the configuration cache.

## Tests

### Layout

Each Java module has two JUnit Jupiter suites, and `check` runs both. What they share lives in a third source set,
the test fixtures of Gradle's [`java-test-fixtures`](https://docs.gradle.org/current/userguide/java_testing.html#sec:java_test_fixtures)
plugin:

| Source set | Task | Holds |
|---|---|---|
| `src/testFixtures` | `compileTestFixturesJava` | Everything a test uses but that is not a test: fixture builders, JFR event types, command-line and export helpers, and the workloads and checks that tests and proof tools launch. Both suites see it. |
| `src/test` | `test` | Unit tests. Pure Java: no native code, no packaged JAR, no Docker, no external tool, so they run on any platform with a JDK. |
| `src/integrationTest` | `integrationTest` and the tasks below | Tests that need the native bundle, a packaged JAR, a Linux kernel, Docker or an external tool. |

- **A test class holds tests.** A helper that a second test class needs moves to `src/testFixtures/java`, into a
  `…Fixture` class in the same package as the code it exercises, so package-private access keeps working. A test
  never calls into another test class, and a suite never sees another suite's classes. Helpers that only one suite's
  tests share, such as the integration tests' `AgentRuntime` and `CaptureChecks`, stay in that suite.
- **Fixtures may use AssertJ**, which `testFixturesImplementation` provides, to check what they build; JUnit,
  Awaitility and Testcontainers stay with the suites.
- **Native sources of fixtures** (the C helpers the proof tools compile) live beside them in `src/testFixtures/c`.
- **Fixtures are never published.** The convention plugin drops the test-fixtures variants from the `java`
  component, and the shaded modules publish only their shaded JAR.
- **Fixtures are not copied between modules.** A fixture that several modules need lives in the test fixtures of the
  module that owns what it builds, and the others depend on it with `testFixtures(project(…))`. The capture stream's
  `CaptureRecordFixture`, which encodes JSON rows as stream records, is in `jonoffcpu-capture-codec`, beside the
  codec it uses; the parts that need a module's package-private `CaptureStream`, such as its header and reader, stay
  in that module's `CaptureStreamFixture`.

Integration tests say what they need with a tag, and the build routes each tag:

| Tag | Needs | Runs in |
|---|---|---|
| `host-native` | The host architecture's native bundle loaded into the test JVM | `integrationTest` on a Linux host whose C library is selected; otherwise `containerIntegrationTest<Platform>`, which runs them with the JUnit Console Launcher in the pinned Corretto image of each selected C library (the default on macOS, `-PintegrationTestsInContainer=true` elsewhere) |
| `privileged-container` | A Linux host with Docker, BTF and tracefs | `integrationTest`, through Testcontainers, once per selected C library of the host's architecture; skipped without Linux or Docker |
| `packaged-jar` | Only the shaded JAR and the test libraries on the classpath | `packagedJarTest` |
| `scale` | A heap cap equal to the bound it proves | `scaleTest`, in a JVM of its own |

Containers always run at the host's own architecture; never test the other architecture under emulation. The
container runner copies every JAR flat into one directory named by a classpath wildcard (`java -cp 'lib/*'`), puts
class directories ahead of it as Gradle orders them, and mounts the project at its own path, so host paths mean the
same inside the container.

The main classes that the integration tests and the Python proof tools under `jonoffcpu-native/tools/` launch (the
`*Workload` and `*Check` classes and their helpers) are fixtures in `src/testFixtures`, compiled to
`build/classes/java/testFixtures`. They must not depend on JUnit, AssertJ or any other test library, because their
launchers put only the agent JAR and the fixture classes on the classpath.

### Time budget

The whole verification build finishes in under two minutes, so tests are fast by design:

- A unit test takes well under a second; a test class that builds a large fixture takes at most about 15 seconds,
  fixture included. The JUnit default timeout is 60 seconds per test (`junit.jupiter.execution.timeout.default`),
  and a test that legitimately needs longer says so with `@Timeout`.
- Size fixtures to the behaviour under test, not to production scale. Where a property only shows at scale, test it
  at the smallest size that still exercises it, derive the numbers from the code's own constants (for example the
  correlator's retention watermark every 65,536 rows), and put the full size behind a property, such as
  `-PscaleRows=2000000 -PscaleHeap=1g` for the scale test.
- Prefer shrinking a threshold to growing the input. The correlator's retention watermark, for example, is
  `Limits.watermarkRows` (the hidden `--watermark-rows` option on the command line), so the degradation ladder runs
  against 10,000 rows with a 1,024-row watermark instead of more than 131,072 rows at the default.
- Build a large fixture once per class (a static `@TempDir` and a memoized builder, as `ScaleFixture.input` does) and
  share it between the tests that only read it.
- Keep fixtures cheap to record and read: for example, commit JFR events from a thread of their own, so each stack
  holds the fixture's frames and not the test runner's.
- Gradle runs test classes in parallel JVMs, one fresh JVM per class (`forkEvery = 1`), so put independent heavy tests
  in separate classes. JUnit Jupiter's own parallel execution stays off, so the tests of one class run one at a time:
  JFR recordings in the same JVM would capture each other's events.
- Measure a test's time in a full `check`, not alone: under the load of every other fork and container a class can
  take two or three times as long.

### Deterministic fixtures

A test that asserts on sizes, counts or thresholds must get the same input every run, and a recorded JFR fixture is
not the same by default:

- JFR records each frame's execution type, so a method the JIT compiles partway through a recording splits one stack
  into several, differently from run to run and more so under load. The correlator's test JVMs exclude
  `ScaleFixture`'s methods from compilation (`-XX:CompileCommand=exclude,…`), so its frames are always interpreted;
  a warm-up only narrows the race.
- JFR's default stack depth of 64 frames truncates deeper stacks into one; a fixture that needs deeper distinct stacks
  raises it for its JVM (`-XX:FlightRecorderOptions:stackdepth=…`), as the scale test does.
- Under load, JFR can write a few events out of commit order. A test must not depend on where an exact row falls:
  choose thresholds with margin, and where the absolute value depends on the recording, measure it (as
  `DegradationLadderTest` measures the fixture's unconstrained peak and budgets a share of it) rather than pinning it.
- Check a new fixture's stability by running its tests several times, alone and inside a full `check`.

### Writing tests

- **JUnit Jupiter 6**, with the version from the JUnit BOM in the version catalog. A test class and its methods are
  package-private. One scenario is one `@Test`, named for the behaviour it proves, with a Javadoc sentence where the
  name cannot carry the reason.
- **Parameterize simple tables** with `@ParameterizedTest` (`@ValueSource`, `@CsvSource`, `@MethodSource`), and a whole
  class with `@ParameterizedClass`, as the agent's end-to-end tests are parameterized by C library. Use `DynamicTest`
  from a `@TestFactory` only for cases computed at run time, and a test-template invocation-context provider only
  when each invocation needs its own injected context.
- **Files go in `@TempDir`**, which JUnit deletes; never create and clean temporary directories by hand. A test never
  depends on another test having run first.
- **AssertJ, idiomatically.** Assert on the value, not on a boolean about it: `assertThat(report.matched())
  .isEqualTo(1)`, `.contains(…)`, `.hasSize(…)`, `.isRegularFile()`, `.hasSameTextualContentAs(…)`. Keep the domain
  reason with `.as("…")` when it adds information. Expected failures use `assertThatThrownBy`,
  `assertThatExceptionOfType` or `assertThatIOException()`, with `hasMessageContaining` when the message matters. Do
  not use Java `assert` or hand-thrown `AssertionError` in tests.
- **Awaitility for every wait**: `await().atMost(…).until(…)`, and `during(…)` to prove a condition holds for a while.
  No sleep-and-poll loops, no unbounded `CountDownLatch.await()`, `Thread.join()` or `Process.waitFor()`.
- **Never exit the JVM.** Call `OffCpuCorrelator.run(String[])` and assert its exit code; `main` calls `System.exit`
  on failure, which would kill the test worker.
- **Testcontainers for containers**, with the Testcontainers BOM and its JUnit Jupiter module:
  `@Testcontainers(disabledWithoutDocker = true)` skips a class cleanly without Docker. Pin images by digest, reusing
  the digests the Dockerfiles pin.
- **No mocks for kernel evidence.** A behaviour of the eBPF source, the signal path or async-profiler is proven
  against the real kernel in a `privileged-container` test or a proof tool, never with a mocked unit test.
