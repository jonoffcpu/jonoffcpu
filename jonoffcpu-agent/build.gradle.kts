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
    embeddedRuntime(project(":jonoffcpu-capture-codec"))
    embeddedRuntime(libs.protobuf.java)
    embeddedRuntime(libs.protobuf.java.util)
    // Not used by the agent's own code: protobuf-java-util's JsonFormat, which prints the manifest, needs it at run time.
    embeddedRuntime(libs.gson)
    embeddedRuntime(libs.snakeyaml)
}

tasks.verifyDependencyDigests {
    digests =
        mapOf(
            "protobuf-java-4.33.1.jar" to "fd5cf3d55bc2c3ddb2a8640c9d4c69daa9a5b326fb6e05bae0e56b3f4f85e0f7",
            "protobuf-java-util-4.33.1.jar" to "f8788f87658d46f8ddb864455eaa046aa218e419c98c93326643ae465aa5c843",
            "gson-2.14.0.jar" to "2cbd119bf1961c28788310963dc80ba65f58cdeec1dd139c8bdb1240faa2c36f",
            "snakeyaml-2.7.jar" to "2e194eba45a67dee19a4e272f4a04b18de8054e9f598b094382f6dae0b0e4b5e",
        )
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
                rootDirectory.dir("jonoffcpu-capture-codec/src/main/proto"),
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
    relocate("com.google.protobuf", "io.github.jonoffcpu.agent.internal.shaded.protobuf")
    relocate("com.google.gson", "io.github.jonoffcpu.agent.internal.shaded.gson")
    relocate("org.yaml.snakeyaml", "io.github.jonoffcpu.agent.internal.shaded.snakeyaml")
    manifest {
        attributes(
            "Premain-Class" to "io.github.jonoffcpu.agent.SignalCaptureAgent",
            "Agent-Class" to "io.github.jonoffcpu.agent.SignalCaptureAgent",
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
                "io/github/jonoffcpu/agent/internal/shaded/gson/Gson.class",
                "io/github/jonoffcpu/agent/internal/shaded/protobuf/CodedInputStream.class",
                "io/github/jonoffcpu/agent/internal/shaded/protobuf/util/JsonFormat.class",
                "io/github/jonoffcpu/agent/internal/shaded/snakeyaml/Yaml.class",
            )
        forbiddenPrefixes =
            listOf(
                // Unrelocated dependencies, the async-profiler Java API, and the offline correlator's classes.
                "com/google/gson/",
                "org/yaml/snakeyaml/",
                "com/google/protobuf/",
                "one/profiler/",
                "io/github/jonoffcpu/correlator/",
                "io/github/jonoffcpu/jfr/",
                "org/openjdk/jmc/",
            )
    }
tasks.check {
    dependsOn(verifyRuntimeJar)
}

// The integration tests come in two kinds, by tag:
// - `host-native` tests load the native bundle into the test JVM, so they need Linux and the host's own bundle. They
//   run on the host when it runs Linux with a selected C library, or in a Linux container through the JUnit Console
//   Launcher (-PintegrationTestsInContainer, the default on any other host), once for each selected C library of the
//   host's architecture.
// - `privileged-container` tests run the packaged agent end to end in privileged Testcontainers, once for each
//   selected C library of the host's architecture, and check the capture with the packaged correlator. They need a
//   Linux host with Docker, BTF and tracefs, and run from the host test JVM.
val integrationTestsInContainer =
    providers
        .gradleProperty("integrationTestsInContainer")
        .map(String::toBoolean)
        .getOrElse(!providers.systemProperty("os.name").get().startsWith("Linux"))
val linuxHost = providers.systemProperty("os.name").get().startsWith("Linux")
val hostArchitecturePlatforms = nativePlatforms.map(NativePlatform.ALL::getValue).filter { it.architecture == hostArchitecture }

// The correlator's shaded JAR, which the end-to-end tests run as a user would.
val correlatorJar =
    configurations.create("correlatorJar") {
        isCanBeConsumed = false
        isTransitive = false
        attributes {
            attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
            attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
            attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.SHADOWED))
        }
    }
dependencies {
    testFixturesApi(testFixtures(project(":jonoffcpu-capture-codec")))
    correlatorJar(project(":jonoffcpu-correlator"))
    "integrationTestImplementation"(libs.testcontainers)
    "integrationTestImplementation"(libs.testcontainers.junit.jupiter)
}

tasks.named<Test>("integrationTest") {
    dependsOn(verifyNativeArchitectures)
    classpath += files(agentJar)
    val options = options as JUnitPlatformOptions
    if (integrationTestsInContainer || !hostPlatformSelected) options.excludeTags("host-native")
    if (!linuxHost) options.excludeTags("privileged-container")
    // Whatever this host cannot run is excluded above, which may leave nothing.
    failOnNoDiscoveredTests = false

    // Local copies: a task action must not capture the build script itself.
    val agent = agentJar
    val correlator = files(correlatorJar)
    val workloads = files(sourceSets.testFixtures.map { it.output.classesDirs })
    inputs.files(correlator).withPropertyName("correlatorJar").withNormalizer(ClasspathNormalizer::class)
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Djonoffcpu.agentJar=${agent.get().asFile.absolutePath}",
                "-Djonoffcpu.correlatorJar=${correlator.singleFile.absolutePath}",
                "-Djonoffcpu.workloadClasses=${workloads.files.first { it.path.contains("/java/") }.absolutePath}",
            )
        },
    )
    systemProperty(
        "jonoffcpu.runtimeImages",
        hostArchitecturePlatforms.joinToString(",") { "${it.libc}=${ContainerImages.forLibc(it.libc)}" },
    )
}

val junitConsole =
    configurations.create("junitConsole") {
        isCanBeConsumed = false
    }
dependencies {
    junitConsole(platform(libs.junit.bom))
    junitConsole(libs.junit.platform.console)
}

val integrationTestSourceSet = sourceSets.named("integrationTest")
val containerIntegrationTests =
    hostArchitecturePlatforms
        .map { spec ->
            val platform = spec.name
            tasks.register<ContainerIntegrationTest>("containerIntegrationTest${spec.taskSuffix}") {
                description = "Runs the host-native integration tests against the $platform bundle in a Linux container."
                dependsOn(verifyNativeArchitectures)
                testClasspath.from(integrationTestSourceSet.map { it.runtimeClasspath }, agentJar)
                testClassesDirs.from(integrationTestSourceSet.map { it.output.classesDirs })
                consoleLauncher.from(junitConsole)
                image = ContainerImages.forLibc(spec.libc)
                includeTags.add("host-native")
                projectDirectory = rootDirectory
                reportsDirectory = layout.buildDirectory.dir("test-results/containerIntegrationTest${spec.taskSuffix}")
                shouldRunAfter(tasks.named("test"))
            }
        }
if (integrationTestsInContainer) {
    tasks.check {
        dependsOn(containerIntegrationTests)
    }
}

// JUnit loads every class it scans before reading its tags, and the other integration tests need classes this
// classpath leaves out on purpose, so the packaged-JAR tests are also named.
tasks.named<Test>("packagedJarTest") {
    filter { includeTestsMatching("io.github.jonoffcpu.agent.ShadedAgentJarTest") }
}
