# Releasing jonoffcpu

Releases are built from immutable `v<version>` tags. For example, tag `v1.2.3`
publishes the agent and correlator Maven artifacts at version `1.2.3`. The
release workflow rejects tags whose value after the leading `v` is not a
Maven-style version beginning with three numeric components.

## Prerequisites

Create a Sonatype Central Portal account, register the `io.github.lhotari`
namespace, generate a Central Portal user token, and distribute the public half
of the GPG signing key. The
[Vanniktech Central Portal guide](https://vanniktech.github.io/gradle-maven-publish-plugin/central/)
describes those one-time steps.

Configure these GitHub Actions repository secrets:

| Secret | Purpose |
| --- | --- |
| `MAVEN_CENTRAL_USERNAME` | Central Portal user-token username |
| `MAVEN_CENTRAL_PASSWORD` | Central Portal user-token password |
| `SIGNING_IN_MEMORY_KEY` | Complete ASCII-armored private signing key |
| `SIGNING_IN_MEMORY_KEY_ID` | Optional signing-key ID |
| `SIGNING_IN_MEMORY_KEY_PASSWORD` | Optional signing-key password |

The release workflow exposes the secrets only to the publication step, adding
the `ORG_GRADLE_PROJECT_` prefix required to pass them to Gradle as project
properties. No publishing or signing credentials are stored in the repository.
The validated tag version is passed through the reusable build workflow and the
publication job as `ORG_GRADLE_PROJECT_version`, so both phases use Gradle's
standard `version` project property.

## Validate locally

Run the complete build before tagging:

```sh
./gradlew :jonoffcpu-agent:check :jonoffcpu-correlator:check -PnativeArchitectures=all
```

The release-equivalent build creates both Linux native bundles. A CI job that has already
downloaded them to `jonoffcpu-agent/build/native/linux-x86_64/` and
`jonoffcpu-agent/build/native/linux-aarch64/` uses:

```sh
./gradlew :jonoffcpu-agent:check :jonoffcpu-correlator:check \
  -PprebuiltNative=true -PnativeArchitectures=all
```

The switch skips only the Docker native-build tasks. ELF architecture, checksum,
packaging, JNI, and runtime checks still run.

During local development, omit `nativeArchitectures` to build only the current
host architecture. Either architecture can also be selected explicitly:

```sh
./gradlew :jonoffcpu-agent:check :jonoffcpu-correlator:check -PnativeArchitectures=x86_64
./gradlew :jonoffcpu-agent:check :jonoffcpu-correlator:check -PnativeArchitectures=aarch64
```

An explicit cross-build skips host-native JNI tests when the selected JAR does
not contain a bundle for the current host.

Inspect a local Maven publication when needed:

```sh
./gradlew publishToMavenLocal
```

The local publication is unsigned. The release workflow sets the Vanniktech
plugin's `ORG_GRADLE_PROJECT_signAllPublications=true` switch alongside the
in-memory key variables, so every Central Portal artifact is signed.

## Publish

Create and push an annotated tag from the commit to release:

```sh
git tag -a v1.2.3 -m "jonoffcpu 1.2.3"
git push origin v1.2.3
```

The [release workflow](.github/workflows/release.yml) then:

1. builds and verifies x86-64 on `ubuntu-26.04`;
2. builds and verifies arm64 on `ubuntu-26.04-arm`;
3. combines both verified bundles into the universal Java agent;
4. signs and publishes the artifacts with
   `publishAndReleaseToMavenCentral`, waiting for Central Portal validation; and
5. copies the executable artifacts to stable `jonoffcpu-agent.jar` and
   `jonoffcpu-correlator.jar` names and adds `jfr-converter.jar`, the converter
   that the reusable workflow built from the pinned async-profiler fork and
   checked against a collapsed off-CPU profile; and
6. creates the GitHub Release with generated notes and all three JAR downloads
   only after publication succeeds.

Generated release-note categories are configured in
[`.github/release.yml`](.github/release.yml).
