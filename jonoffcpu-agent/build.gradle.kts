plugins {
    id("jonoffcpu.shaded-jar-conventions")
    id("jonoffcpu.protobuf-conventions")
}

jonoffcpu {
    javaRelease = 17
}

jonoffcpuPublication {
    displayName = "jonoffcpu agent"
    description = "Self-contained Linux off-CPU profiling agent for the JVM."
}

dependencies {
    embeddedRuntime(libs.protobuf.javalite)
    embeddedRuntime(libs.gson)
    embeddedRuntime(libs.snakeyaml)
}

tasks.verifyDependencyDigests {
    digests =
        mapOf(
            "protobuf-javalite-4.33.1.jar" to "a1a1cccbcfa861e988b7ccde58dbe95204156906dd6cd42786b9c8f74d5fe34e",
            "gson-2.14.0.jar" to "2cbd119bf1961c28788310963dc80ba65f58cdeec1dd139c8bdb1240faa2c36f",
            "snakeyaml-2.7.jar" to "2e194eba45a67dee19a4e272f4a04b18de8054e9f598b094382f6dae0b0e4b5e",
        )
}

sourceSets {
    main {
        // The stack profile is the correlator's derived artifact; the agent never reads or writes one.
        proto.exclude("jonoffcpu-profile.proto")
    }
}

val rootDirectory = isolated.rootProject.projectDirectory
val asyncProfilerDir = rootDirectory.dir("async-profiler")

// Which native bundles the agent JAR embeds: -PnativeArchitectures (current, all, x86_64 or aarch64) and
// -PnativeLibcs (musl, glibc, all or current). -PprebuiltNative=true takes them from build/native instead of
// building them, as the release packaging does with the bundles the native runners built.
val hostArchitecture = NativePlatform.architecture(providers.systemProperty("os.arch").get())
val hostLibc = providers.of(HostLibcValueSource::class) {}.get()
val hostPlatform = NativePlatform.hostPlatform(hostArchitecture, hostLibc)
val nativePlatforms =
    NativePlatform.select(
        providers.gradleProperty("nativeArchitectures").getOrElse("current"),
        providers.gradleProperty("nativeLibcs").getOrElse("musl"),
        hostArchitecture,
        hostLibc,
    )
val hostPlatformSelected = hostPlatform in nativePlatforms
val prebuiltNative = providers.gradleProperty("prebuiltNative").map(String::toBoolean).getOrElse(false)
val nativeRoot = layout.buildDirectory.dir("native")
val nativeLibraries =
    files(nativePlatforms.flatMap { platform -> NativePlatform.FILE_NAMES.map { nativeRoot.map { it.file("$platform/$it") } } })

// Layer caching for the native bundle builds in GitHub Actions: -PdockerCache=gha restores every stage, the
// toolchains and the compiled dependencies included, from the Actions cache, and -PdockerCacheWrite=true also
// exports them (mode=max). CI writes only on the default branch, whose entries every branch can read. The cache
// needs a buildx builder with the docker-container driver (BUILDX_BUILDER) and the Actions runtime environment.
val dockerCache = providers.gradleProperty("dockerCache").getOrElse("none")
val dockerCacheWrite = providers.gradleProperty("dockerCacheWrite").map(String::toBoolean).getOrElse(false)

fun dockerCacheArguments(scope: String): List<String> =
    when (dockerCache) {
        "none" -> {
            emptyList()
        }

        "gha" -> {
            listOf("--cache-from", "type=gha,scope=$scope") +
                if (dockerCacheWrite) listOf("--cache-to", "type=gha,scope=$scope,mode=max,ignore-error=true") else emptyList()
        }

        else -> {
            throw GradleException("Unsupported dockerCache value '$dockerCache'; expected none or gha")
        }
    }

val nativeTasks =
    NativePlatform.ALL.mapValues { (platform, spec) ->
        tasks.register<Exec>("build${spec.taskSuffix}Native") {
            group = "native build"
            description = "Builds the agent, collector, and async-profiler libraries for $platform in Docker buildx."
            val output = nativeRoot.map { it.dir(platform) }
            val dockerfile = layout.projectDirectory.file("tools/${spec.dockerfile}")
            workingDir(rootDirectory)
            commandLine(
                listOf(
                    "docker",
                    "buildx",
                    "build",
                    "--progress=plain",
                    "--platform",
                    spec.dockerPlatform,
                    "--file",
                    dockerfile.asFile.absolutePath,
                    "--output",
                    "type=local,dest=${output.get().asFile.absolutePath}",
                ) + dockerCacheArguments("native-bundle-$platform") + rootDirectory.asFile.absolutePath,
            )
            inputs.file(dockerfile)
            inputs.file(layout.projectDirectory.file("tools/check-musl-needed.sh"))
            inputs.files(
                fileTree(layout.projectDirectory.dir("src/main/c")),
                fileTree(rootDirectory.dir("jonoffcpu-native/src")),
                rootDirectory.file("jonoffcpu-native/Cargo.toml"),
                rootDirectory.file("jonoffcpu-native/Cargo.lock"),
                fileTree(asyncProfilerDir.dir("src")),
                asyncProfilerDir.file("Makefile"),
            )
            outputs.files(NativePlatform.FILE_NAMES.map { name -> output.map { it.file(name) } })
            val asyncProfilerHeader = asyncProfilerDir.file("src/asprof.h").asFile
            doFirst {
                // An uninitialized submodule otherwise surfaces as an opaque Docker COPY failure.
                if (!asyncProfilerHeader.isFile) {
                    throw GradleException("The async-profiler submodule is not checked out; run 'git submodule update --init'")
                }
                output.get().asFile.deleteRecursively()
            }
        }
    }

val verifyNativeArchitectures =
    tasks.register<VerifyNativeLibraries>("verifyNativeArchitectures") {
        group = "verification"
        description = "Rejects missing, malformed, or mislabeled native libraries for the selected Linux platforms."
        if (!prebuiltNative) dependsOn(nativePlatforms.map(nativeTasks::getValue))
        nativeRoot = layout.buildDirectory.dir("native")
        libraries.from(nativeLibraries)
        platforms = nativePlatforms
    }

val generateNativeChecksums =
    tasks.register<GenerateNativeChecksums>("generateNativeChecksums") {
        dependsOn(verifyNativeArchitectures)
        nativeRoot = layout.buildDirectory.dir("native")
        libraries.from(nativeLibraries)
        platforms = nativePlatforms
        checksums = layout.buildDirectory.file("native/SHA256SUMS")
    }

tasks.shadowJar {
    dependsOn(generateNativeChecksums)
    relocate("com.google.protobuf", "io.github.lhotari.jonoffcpu.internal.shaded.protobuf")
    relocate("com.google.gson", "io.github.lhotari.jonoffcpu.internal.shaded.gson")
    relocate("org.yaml.snakeyaml", "io.github.lhotari.jonoffcpu.internal.shaded.snakeyaml")
    manifest {
        attributes(
            "Premain-Class" to "io.github.lhotari.jonoffcpu.agent.SignalCaptureAgent",
            "Agent-Class" to "io.github.lhotari.jonoffcpu.agent.SignalCaptureAgent",
            "Can-Redefine-Classes" to "false",
            "Can-Retransform-Classes" to "false",
        )
    }
    from(nativeRoot) {
        into("META-INF/native")
        include("SHA256SUMS")
        nativePlatforms.forEach { include("$it/**") }
    }
    from(asyncProfilerDir.file("LICENSE")) {
        into("META-INF/licenses")
        rename { "async-profiler-LICENSE" }
    }
}
val agentJar = tasks.shadowJar.flatMap { it.archiveFile }

val verifyRuntimeJar =
    tasks.register<VerifyAgentJar>("verifyRuntimeJar") {
        description = "Checks bundled native entries, ELF architectures, C libraries, licenses, and recorded SHA-256 digests."
        jar = agentJar
        label = "Agent JAR"
        platforms = nativePlatforms
        requiredEntries =
            listOf(
                "META-INF/LICENSE",
                "META-INF/licenses/async-profiler-LICENSE",
                "io/github/lhotari/jonoffcpu/internal/shaded/gson/Gson.class",
                "io/github/lhotari/jonoffcpu/internal/shaded/protobuf/CodedInputStream.class",
                "io/github/lhotari/jonoffcpu/internal/shaded/snakeyaml/Yaml.class",
            )
        forbiddenPrefixes =
            listOf(
                // Unrelocated dependencies, the async-profiler Java API, and the offline correlator's classes.
                "com/google/gson/",
                "org/yaml/snakeyaml/",
                "com/google/protobuf/",
                "one/profiler/",
                "io/github/lhotari/jonoffcpu/offline/",
                "io/github/lhotari/jonoffcpu/jfr/",
                "org/openjdk/jmc/",
            )
    }
tasks.check {
    dependsOn(verifyRuntimeJar)
}

val fixtureClasspath = files(sourceSets.test.map { it.runtimeClasspath }, agentJar)

tasks.register<FixtureExec>("testSignalCaptureController") {
    classpath = fixtureClasspath
    mainClass = "io.github.lhotari.jonoffcpu.agent.SignalCaptureControllerTest"
}

tasks.register<FixtureExec>("testNativeLibc") {
    classpath = fixtureClasspath
    mainClass = "io.github.lhotari.jonoffcpu.agent.NativeLibcTest"
}

tasks.register<FixtureExec>("testShadedAgentJar") {
    description = "Exercises relocated YAML parsing from the published agent JAR."
    classpath = files(sourceSets.test.map { it.output }, agentJar)
    mainClass = "io.github.lhotari.jonoffcpu.agent.ShadedAgentJarTest"
}

tasks.register<FixtureExec>("testNativeCollectorJni") {
    dependsOn(verifyNativeArchitectures)
    classpath = fixtureClasspath
    mainClass = "io.github.lhotari.jonoffcpu.agent.NativeCollectorJniTest"
    args(
        nativeRoot
            .get()
            .file("$hostPlatform/libjonoffcpu.so")
            .asFile.absolutePath,
    )
    // A local copy: a task action must not capture the build script itself.
    val selected = hostPlatformSelected
    onlyIf("selected native platforms include the current host") { selected }
}

tasks.register<FixtureExec>("testNativeBundleLoader") {
    description = "Extracts the host bundle from the JAR and exercises async-profiler through the native C API."
    classpath = fixtureClasspath
    mainClass = "io.github.lhotari.jonoffcpu.agent.NativeBundleLoaderTest"
    // A local copy: a task action must not capture the build script itself.
    val selected = hostPlatformSelected
    onlyIf("selected native platforms include the current host") { selected }
}
