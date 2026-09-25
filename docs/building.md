# Building and testing

How to build jonoffcpu from source, which tests run where, and how continuous
integration is organized. The code and test conventions are in
[CODING.md](../CODING.md), the rules for contributors and agents in
[AGENTS.md](../AGENTS.md), and publishing in [RELEASING.md](../RELEASING.md).

- [Building from source](#building-from-source)
- [Test categories](#test-categories)
- [Continuous integration](#continuous-integration)
- [The converter](#the-converter)

## Building from source

Clone with the async-profiler submodule:

```sh
git clone --recurse-submodules https://github.com/jonoffcpu/jonoffcpu.git
cd jonoffcpu
```

The build needs [Amazon Corretto 25](https://aws.amazon.com/corretto/). Every
check that needs only a JDK — formatting, the unit tests of every module, and
the correlator's and converter's integration tests — runs on any operating
system:

```sh
./gradlew jvmCheck
```

The agent's native bundle and its integration tests need Linux, on x86-64 or
arm64 (`aarch64`), and Docker with
[BuildKit](https://docs.docker.com/build/buildkit/): the native libraries are
compiled in a pinned container, and the tests load them into a JVM, run them
against the host kernel with BTF and the eBPF features listed under
[Requirements](setup.md#requirements), and several need privileged Docker. The
full build for the current host architecture is:

```sh
./gradlew :jonoffcpu-agent:check :jonoffcpu-correlator:check
```

On macOS the containers run in a Linux VM whose kernel is not the one the
build detects, and the build's own C-library detection reads
`/proc/self/maps`; on Windows, work inside WSL2, which is a Linux VM and
behaves like one. Build only the host architecture locally: the other one runs
under QEMU emulation and is far slower than it is worth, and CI covers it on
native runners.

Pass `-PnativeArchitectures=all` to embed both Linux x86-64 and arm64 bundles,
or `x86_64` / `aarch64` to pick one; the default is `current`. Each
architecture has a glibc and a musl flavour; `-PnativeLibcs` selects `musl`
(the default), `glibc`, `current` or `all`. Releases embed all four bundles.
The agent JAR lands in `jonoffcpu-agent/build/libs/` and the runnable
correlator JAR in `jonoffcpu-correlator/build/libs/`.

The build runs with Gradle's
[configuration cache](https://docs.gradle.org/current/userguide/configuration_cache.html),
build cache,
configure-on-demand and parallel execution; the modules share their build
logic through the convention plugins in `build-logic/`, and every library and
plugin version is in `gradle/libs.versions.toml`. The native bundle's
Dockerfiles compile the collector's Cargo dependencies in a layer of their
own, so a collector change rebuilds only the collector. CI restores those
layers from the GitHub Actions cache with `-PdockerCache=gha`
(`-PdockerCacheWrite=true` also exports them, which CI does only on `main`).
Formatting is enforced with [Spotless](https://github.com/diffplug/spotless):
run `./gradlew spotlessApply` after editing.

## Test categories

The tests are JUnit Jupiter tests with AssertJ. `src/test` holds unit tests
that run on any platform with Java, `src/integrationTest` holds the tests that
need the native bundle, a packaged JAR, Docker or an external tool, and what
both share is in `src/testFixtures`, Gradle's
[test fixtures](https://docs.gradle.org/current/userguide/java_testing.html#sec:java_test_fixtures).
Integration tests declare what they need with a
[JUnit tag](https://docs.junit.org/current/writing-tests/tagging-and-filtering.html); [CODING.md](../CODING.md#layout) lists
the tags and how the build routes them. Two lifecycle tasks group the tests by
what they need, so CI and a developer run the same thing:

| Task | Runs | Needs |
| --- | --- | --- |
| `jvmCheck` | `spotlessCheck`; every module's unit tests; the correlator's whole `check` (its integration, scale, packaged-JAR and documentation tests); the converter's `check` | A JDK. DuckDB on the `PATH` for the export tests, which are skipped without it |
| `:jonoffcpu-agent:nativeTest` | The agent's integration tests for the selected platforms: `host-native` tests, on the host or in a container of their C library, and the `privileged-container` end-to-end tests | Linux, Docker, the native bundles of `-PnativeArchitectures` and `-PnativeLibcs` |

`check` still runs everything a module has. The agent's `packagedJarTest` and
`verifyRuntimeJar` read the agent JAR, which embeds every selected bundle, so
CI runs them once all four bundles exist.

The documentation is tested too: `:jonoffcpu-correlator:readmeTest` checks that
the options the README and the `docs/` pages name exist in the correlator's
parser, with the defaults they state, and that every relative link and anchor
in them resolves.

## Continuous integration

Every pull request and every push to `main` runs
[`ci.yml`](../.github/workflows/ci.yml), which calls the build shared with
releases, [`build-and-verify.yml`](../.github/workflows/build-and-verify.yml):

| Job | Runs on | Does |
| --- | --- | --- |
| Detect changes | x86-64 | Decides whether the change touches documentation, and whether it touches only documentation |
| Unit tests | x86-64 | `cargo fmt --check` and `./gradlew jvmCheck`: formatting, every test that needs only a JDK, with DuckDB installed and required |
| Build and verify, per platform | x86-64 and arm64 × glibc and musl, after the unit tests | Builds one native bundle and runs `:jonoffcpu-agent:nativeTest` against it, then uploads the bundle |
| Package combined artifacts | x86-64, after all four | Assembles the agent JAR from the four bundles, verifies it and the correlator JAR, runs the agent's packaged-JAR test, and uploads the three runnable JARs |
| Documentation check | x86-64, when documentation changed | `:jonoffcpu-correlator:readmeTest` |
| Lint workflows | x86-64 | actionlint and zizmor |

Documentation means Markdown files outside `src/` and anything under `docs/`,
as [`.github/changes-filter.yaml`](../.github/changes-filter.yaml) defines it.
A change that touches only documentation skips the build and verification
jobs, and the documentation check is its only test. A change without
documentation skips the documentation check; its unit tests run the same test
anyway. The required status check, *All checks passed*, accepts exactly those
skips and fails on any other job that did not succeed.

Every CI run publishes a [Build Scan](https://scans.gradle.com) for each Gradle
build, and the three JARs as a `jonoffcpu-runnable-jars` workflow artifact:

```sh
gh run download <run-id> --repo jonoffcpu/jonoffcpu \
  --name jonoffcpu-runnable-jars --dir jonoffcpu-runnable-jars
```

## The converter

The converter is built from the async-profiler fork's `src/converter` sources
by the `jonoffcpu-jfr-converter` module:

```sh
./gradlew :jonoffcpu-jfr-converter:check
```
