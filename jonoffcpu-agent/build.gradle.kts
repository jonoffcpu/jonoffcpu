import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat
import java.util.zip.ZipFile
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.external.javadoc.StandardJavadocDocletOptions
import org.gradle.jvm.toolchain.JvmVendorSpec

plugins {
    `java-library`
    // The base plugin: the full plugin would publish components["java"], but only the shaded JAR is published.
    id("com.vanniktech.maven.publish.base")
    id("com.gradleup.shadow")
    id("com.google.protobuf")
}

group = "io.github.lhotari"

base {
    archivesName = "jonoffcpu-agent"
}

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
        vendor = JvmVendorSpec.AMAZON
    }
    targetCompatibility = JavaVersion.VERSION_17
    // Maven Central requires sources and javadoc JARs next to the shaded JAR.
    withSourcesJar()
    withJavadocJar()
}

val embeddedRuntime = configurations.create("embeddedRuntime") {
    isTransitive = false
}
configurations.compileOnly {
    extendsFrom(embeddedRuntime)
}
configurations.testImplementation {
    extendsFrom(embeddedRuntime)
}

val asyncProfilerDir = rootProject.layout.projectDirectory.dir("async-profiler")
val asyncProfilerConverter = asyncProfilerDir.file("build/bin/jfrconv")

dependencies {
    // The capture stream codec, generated from docs/schema/jonoffcpu-capture.proto.
    embeddedRuntime("com.google.protobuf:protobuf-javalite:4.33.1")
    embeddedRuntime("com.google.code.gson:gson:2.14.0")
    embeddedRuntime("org.yaml:snakeyaml:2.7")
}

sourceSets {
    main {
        proto.setSrcDirs(listOf(rootProject.layout.projectDirectory.dir("docs/schema")))
        // The stack profile is the correlator's derived artifact; the agent never reads or writes one.
        proto.exclude("jonoffcpu-profile.proto")
    }
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:4.33.1"
    }
    generateProtoTasks {
        all().configureEach {
            builtins {
                named("java") {
                    // The lite runtime has no descriptors or reflection, which is all the stream needs.
                    option("lite")
                }
            }
        }
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 17
    options.encoding = "UTF-8"
}

tasks.withType<Javadoc>().configureEach {
    options.encoding = "UTF-8"
    (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
}

val expectedDependencyDigests = mapOf(
    "protobuf-javalite-4.33.1.jar" to "a1a1cccbcfa861e988b7ccde58dbe95204156906dd6cd42786b9c8f74d5fe34e",
    "gson-2.14.0.jar" to "2cbd119bf1961c28788310963dc80ba65f58cdeec1dd139c8bdb1240faa2c36f",
    "snakeyaml-2.7.jar" to "2e194eba45a67dee19a4e272f4a04b18de8054e9f598b094382f6dae0b0e4b5e"
)

fun sha256(bytes: ByteArray): String =
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

fun sha256File(input: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    input.inputStream().buffered().use { stream ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            if (read > 0) digest.update(buffer, 0, read)
        }
    }
    return HexFormat.of().formatHex(digest.digest())
}

val verifyDependencyDigests = tasks.register("verifyDependencyDigests") {
    group = "verification"
    description = "Checks the exact protobuf, Gson and SnakeYAML artifacts before embedding them."
    inputs.files(embeddedRuntime)
    doLast {
        embeddedRuntime.files.forEach { artifact ->
            val expected = expectedDependencyDigests[artifact.name]
                ?: throw GradleException("No pinned digest for ${artifact.name}")
            val actual = sha256File(artifact)
            if (actual != expected) {
                throw GradleException("SHA-256 mismatch for ${artifact.name}: $actual")
            }
        }
    }
}

/** One embedded native bundle: a Linux architecture linked against one C library. */
data class NativePlatform(
    val name: String,
    val architecture: String,
    val libc: String,
    val elfMachine: Int,
    val dockerPlatform: String,
    val dockerfile: String,
    val taskSuffix: String
)

val allNativePlatforms = listOf(
    NativePlatform("linux-x86_64", "x86_64", "glibc", 62, "linux/amd64", "Dockerfile.native-bundle", "LinuxX86_64"),
    NativePlatform("linux-aarch64", "aarch64", "glibc", 183, "linux/arm64", "Dockerfile.native-bundle", "LinuxAarch64"),
    NativePlatform(
        "linux-musl-x86_64", "x86_64", "musl", 62, "linux/amd64", "Dockerfile.native-bundle-musl", "LinuxMuslX86_64"
    ),
    NativePlatform(
        "linux-musl-aarch64", "aarch64", "musl", 183, "linux/arm64", "Dockerfile.native-bundle-musl", "LinuxMuslAarch64"
    )
).associateBy { it.name }
val hostArchitecture = providers.systemProperty("os.arch").map { architecture ->
    when (architecture.lowercase()) {
        "amd64", "x86_64" -> "x86_64"
        "aarch64", "arm64" -> "aarch64"
        else -> throw GradleException("Unsupported build host architecture: $architecture")
    }
}

/** Detects the C library of the JVM running this build from its own mapped loader, as the agent does. */
fun hostLibc(): String {
    val maps = File("/proc/self/maps")
    if (!maps.isFile) return "glibc"
    val muslLoaderMapped = maps.readLines()
        .map { line -> line.substringAfter(" /", "").substringAfterLast('/') }
        .any { name -> name.startsWith("ld-musl-") || name.startsWith("libc.musl-") }
    return if (muslLoaderMapped) "musl" else "glibc"
}
val hostPlatform = hostArchitecture.map { architecture ->
    val libc = hostLibc()
    if (libc == "musl") "linux-musl-$architecture" else "linux-$architecture"
}
val nativeArchitectureSelection = providers.gradleProperty("nativeArchitectures")
    .map { it.trim().lowercase() }
    .orElse("current")
val selectedArchitectures = when (val selection = nativeArchitectureSelection.get()) {
    "current" -> setOf(hostArchitecture.get())
    "all", "both" -> setOf("x86_64", "aarch64")
    "x86_64", "amd64", "linux-x86_64" -> setOf("x86_64")
    "aarch64", "arm64", "linux-aarch64" -> setOf("aarch64")
    else -> throw GradleException(
        "Unsupported nativeArchitectures value '$selection'; expected current, all, x86_64, or aarch64"
    )
}
val nativeLibcSelection = providers.gradleProperty("nativeLibcs")
    .map { it.trim().lowercase() }
    .orElse("musl")
val selectedLibcs = when (val selection = nativeLibcSelection.get()) {
    "all", "both" -> setOf("glibc", "musl")
    "glibc", "gnu" -> setOf("glibc")
    "musl" -> setOf("musl")
    "current", "host" -> setOf(hostLibc())
    else -> throw GradleException(
        "Unsupported nativeLibcs value '$selection'; expected musl, glibc, all, or current"
    )
}
val nativePlatforms = allNativePlatforms.filterValues {
    it.architecture in selectedArchitectures && it.libc in selectedLibcs
}
val nativeFileNames = listOf("libjonoffcpu.so", "libjonoffcpu_native.so", "libasyncProfiler.so")
val nativeRoot = layout.buildDirectory.dir("native")
val prebuiltNative = providers.gradleProperty("prebuiltNative").map(String::toBoolean).orElse(false)

val nativeTasks = allNativePlatforms.mapValues { (platform, spec) ->
    val dockerfile = layout.projectDirectory.file("tools/${spec.dockerfile}")
    val output = nativeRoot.map { it.dir(platform) }
    tasks.register<Exec>("build${spec.taskSuffix}Native") {
        group = "native build"
        description = "Builds the agent, collector, and async-profiler libraries for $platform in Docker buildx."
        workingDir(rootProject.layout.projectDirectory)
        commandLine(
            "docker", "buildx", "build", "--progress=plain",
            "--platform", spec.dockerPlatform,
            "--file", dockerfile.asFile.absolutePath,
            "--output", output.map { "type=local,dest=${it.asFile.absolutePath}" }.get(),
            rootProject.layout.projectDirectory.asFile.absolutePath
        )
        inputs.file(dockerfile)
        inputs.file(layout.projectDirectory.file("tools/check-musl-needed.sh"))
        inputs.files(
            fileTree(rootProject.file("jonoffcpu-agent/src/main/c")),
            fileTree(rootProject.file("jonoffcpu-native/src")),
            rootProject.file("jonoffcpu-native/Cargo.toml"),
            rootProject.file("jonoffcpu-native/Cargo.lock"),
            fileTree(rootProject.file("async-profiler/src")),
            rootProject.file("async-profiler/Makefile")
        )
        outputs.files(nativeFileNames.map { name -> output.map { it.file(name) } })
        val asyncProfilerHeader = rootProject.file("async-profiler/src/asprof.h")
        doFirst {
            // An uninitialized submodule otherwise surfaces as an opaque Docker COPY failure.
            if (!asyncProfilerHeader.isFile) {
                throw GradleException(
                    "The async-profiler submodule is not checked out; run 'git submodule update --init'"
                )
            }
            delete(output)
        }
    }
}

fun elfMachine(bytes: ByteArray): Int {
    if (bytes.size < 20 || bytes[0] != 0x7f.toByte() || bytes[1] != 'E'.code.toByte()
        || bytes[2] != 'L'.code.toByte() || bytes[3] != 'F'.code.toByte()
    ) {
        throw GradleException("Native artifact is not an ELF file")
    }
    if (bytes[4] != 2.toByte() || bytes[5] != 1.toByte()) {
        throw GradleException("Native artifact must be a little-endian ELF64 file")
    }
    return bytes[18].toUByte().toInt() or (bytes[19].toUByte().toInt() shl 8)
}

/**
 * Checks that a native library is linked against the expected C library. glibc-linked
 * libraries carry GLIBC_2.x symbol version needs; musl-linked libraries carry none.
 */
fun verifyLibc(bytes: ByteArray, expectedLibc: String, label: String) {
    val glibcVersioned = String(bytes, StandardCharsets.ISO_8859_1).contains("GLIBC_2.")
    if (expectedLibc == "glibc" && !glibcVersioned) {
        throw GradleException("$label is labeled glibc but carries no glibc symbol versions")
    }
    if (expectedLibc == "musl" && glibcVersioned) {
        throw GradleException("$label is labeled musl but carries glibc symbol versions")
    }
}

val verifyNativeArchitectures = tasks.register("verifyNativeArchitectures") {
    group = "verification"
    description = "Rejects missing, malformed, or mislabeled native libraries for the selected Linux platforms."
    if (!prebuiltNative.get()) {
        dependsOn(nativePlatforms.keys.map(nativeTasks::getValue))
    }
    inputs.files(nativePlatforms.keys.map { platform -> nativeRoot.map { it.dir(platform) } })
    doLast {
        nativePlatforms.forEach { (platform, spec) ->
            nativeFileNames.forEach { name ->
                val artifact = nativeRoot.get().dir(platform).file(name).asFile
                if (!artifact.isFile) {
                    throw GradleException("Missing $platform/$name")
                }
                val bytes = artifact.readBytes()
                val actual = elfMachine(bytes)
                if (actual != spec.elfMachine) {
                    throw GradleException("$platform/$name has ELF e_machine $actual, expected ${spec.elfMachine}")
                }
                verifyLibc(bytes, spec.libc, "$platform/$name")
            }
        }
    }
}

val nativeChecksums = nativeRoot.map { it.file("SHA256SUMS") }
val generateNativeChecksums = tasks.register("generateNativeChecksums") {
    dependsOn(verifyNativeArchitectures)
    inputs.files(nativePlatforms.keys.map { platform -> nativeRoot.map { it.dir(platform) } })
    outputs.file(nativeChecksums)
    doLast {
        val lines = nativePlatforms.keys.sorted().flatMap { platform ->
            nativeFileNames.sorted().map { name ->
                val artifact = nativeRoot.get().dir(platform).file(name).asFile
                "${sha256File(artifact)}  $platform/$name"
            }
        }
        val output = nativeChecksums.get().asFile
        output.parentFile.mkdirs()
        output.writeText(lines.joinToString("\n", postfix = "\n"), StandardCharsets.UTF_8)
    }
}

// The plain JAR is never published or consumed; the shaded JAR below is the only artifact.
tasks.named<Jar>("jar") {
    enabled = false
}

val jar = tasks.named<ShadowJar>("shadowJar") {
    dependsOn(verifyDependencyDigests, generateNativeChecksums)
    archiveClassifier = ""
    configurations = listOf(embeddedRuntime)
    relocate("com.google.protobuf", "io.github.lhotari.jonoffcpu.internal.shaded.protobuf")
    relocate("com.google.gson", "io.github.lhotari.jonoffcpu.internal.shaded.gson")
    relocate("org.yaml.snakeyaml", "io.github.lhotari.jonoffcpu.internal.shaded.snakeyaml")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    // The bundled libraries' Maven descriptors and ProGuard rules describe their original,
    // unrelocated coordinates and packages, so they are misleading inside the shaded JAR.
    exclude("META-INF/maven/**", "META-INF/proguard/**")
    manifest {
        attributes(
            "Premain-Class" to "io.github.lhotari.jonoffcpu.agent.SignalCaptureAgent",
            "Agent-Class" to "io.github.lhotari.jonoffcpu.agent.SignalCaptureAgent",
            "Can-Redefine-Classes" to "false",
            "Can-Retransform-Classes" to "false",
            "Implementation-Title" to "jonoffcpu-agent",
            "Implementation-Version" to project.version
        )
    }
    from(nativeRoot) {
        into("META-INF/native")
        include("SHA256SUMS")
        nativePlatforms.keys.forEach { include("$it/**") }
    }
    from(asyncProfilerDir.file("LICENSE")) {
        into("META-INF/licenses")
        rename { "async-profiler-LICENSE" }
    }
    from(rootProject.file("LICENSE")) {
        into("META-INF")
    }
}

// The plain JAR neither embeds nor declares its relocated dependencies, so the shaded JAR is the only
// usable artifact. Publishing the Shadow plugin's component makes it the module's sole runtime
// variant: a consumer that asks for nothing in particular gets it, since Gradle accepts a shadowed
// variant when no external one exists. The agent has no separate API, so no apiElements is published.
publishing {
    publications.register<MavenPublication>("maven") {
        from(components["shadow"])
        artifact(tasks.named("sourcesJar"))
        artifact(tasks.named("javadocJar"))
    }
}

val verifyRuntimeJar = tasks.register("verifyRuntimeJar") {
    group = "verification"
    description = "Checks bundled native entries, ELF architectures, C libraries, licenses, and recorded SHA-256 digests."
    dependsOn(jar)
    inputs.file(jar.flatMap { it.archiveFile })
    doLast {
        val archive = jar.get().archiveFile.get().asFile
        ZipFile(archive).use { zip ->
            val checksumEntry = zip.getEntry("META-INF/native/SHA256SUMS")
                ?: throw GradleException("Runtime JAR has no native checksum manifest")
            if (zip.entries().asSequence().any { it.name.startsWith("one/profiler/") }) {
                throw GradleException("Runtime JAR must not embed the async-profiler Java API")
            }
            if (zip.entries().asSequence().any {
                    it.name.startsWith("com/google/gson/")
                            || it.name.startsWith("org/yaml/snakeyaml/")
                            || it.name.startsWith("com/google/protobuf/")
                }) {
                throw GradleException("Agent JAR contains unrelocated dependency packages")
            }
            for (name in listOf(
                "io/github/lhotari/jonoffcpu/internal/shaded/gson/Gson.class",
                "io/github/lhotari/jonoffcpu/internal/shaded/protobuf/CodedInputStream.class",
                "io/github/lhotari/jonoffcpu/internal/shaded/snakeyaml/Yaml.class"
            )) {
                if (zip.getEntry(name) == null) throw GradleException("Agent JAR is missing relocated class $name")
            }
            if (zip.entries().asSequence().any {
                    it.name.startsWith("io/github/lhotari/jonoffcpu/offline/")
                        || it.name.startsWith("io/github/lhotari/jonoffcpu/jfr/")
                        || it.name.startsWith("org/openjdk/jmc/")
                }) {
                throw GradleException("Agent JAR must not embed offline correlator or JMC writer classes")
            }
            val recorded = mutableMapOf<String, String>()
            zip.getInputStream(checksumEntry).bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                lines.filter { it.isNotBlank() }.forEach { line ->
                    val match = Regex("^([0-9a-f]{64})  (linux-(?:musl-)?(?:x86_64|aarch64)/[^/]+)\$").matchEntire(line)
                        ?: throw GradleException("Malformed native checksum line: $line")
                    recorded[match.groupValues[2]] = match.groupValues[1]
                }
            }
            nativePlatforms.forEach { (platform, spec) ->
                nativeFileNames.forEach { name ->
                    val relative = "$platform/$name"
                    val entry = zip.getEntry("META-INF/native/$relative")
                        ?: throw GradleException("Runtime JAR is missing $relative")
                    val bytes = zip.getInputStream(entry).use { it.readAllBytes() }
                    if (elfMachine(bytes) != spec.elfMachine) {
                        throw GradleException("Runtime JAR contains the wrong architecture at $relative")
                    }
                    verifyLibc(bytes, spec.libc, "Runtime JAR entry $relative")
                    if (recorded[relative] != sha256(bytes)) {
                        throw GradleException("Runtime JAR checksum mismatch for $relative")
                    }
                }
            }
            val expectedEntries = nativePlatforms.keys.flatMap { platform ->
                nativeFileNames.map { "$platform/$it" }
            }.toSet()
            if (recorded.keys != expectedEntries) {
                throw GradleException("Native checksum manifest has missing or unexpected entries")
            }
            listOf(
                "META-INF/LICENSE",
                "META-INF/licenses/async-profiler-LICENSE"
            ).forEach { name ->
                if (zip.getEntry(name) == null) {
                    throw GradleException("Runtime JAR is missing $name")
                }
            }
        }
    }
}

val fixtureMains = mapOf(
    "SignalCaptureController" to "io.github.lhotari.jonoffcpu.agent.SignalCaptureControllerTest",
    "NativeLibc" to "io.github.lhotari.jonoffcpu.agent.NativeLibcTest"
)
val fixtureTasks = fixtureMains.map { (taskName, className) ->
    tasks.register<JavaExec>("test$taskName") {
        group = "verification"
        dependsOn(jar, tasks.named("testClasses"))
        classpath = files(sourceSets.test.get().runtimeClasspath, jar.flatMap { it.archiveFile })
        mainClass = className
        jvmArgs("-ea")
    }
}

val testShadedAgentJar = tasks.register<JavaExec>("testShadedAgentJar") {
    group = "verification"
    description = "Exercises relocated YAML parsing from the published agent JAR."
    dependsOn(jar, tasks.named("testClasses"))
    classpath = files(sourceSets.test.get().output, jar.flatMap { it.archiveFile })
    mainClass = "io.github.lhotari.jonoffcpu.agent.ShadedAgentJarTest"
    jvmArgs("-ea")
}

val testNativeCollectorJni = tasks.register<JavaExec>("testNativeCollectorJni") {
    group = "verification"
    dependsOn(jar, tasks.named("testClasses"), verifyNativeArchitectures)
    classpath = files(sourceSets.test.get().runtimeClasspath, jar.flatMap { it.archiveFile })
    mainClass = "io.github.lhotari.jonoffcpu.agent.NativeCollectorJniTest"
    jvmArgs("-ea")
    onlyIf("selected native platforms include the current host") {
        nativePlatforms.containsKey(hostPlatform.get())
    }
    doFirst {
        setArgs(listOf(nativeRoot.get().dir(hostPlatform.get()).file("libjonoffcpu.so").asFile.absolutePath))
    }
}

val testNativeBundleLoader = tasks.register<JavaExec>("testNativeBundleLoader") {
    group = "verification"
    description = "Extracts the host bundle from the JAR and exercises async-profiler through the native C API."
    dependsOn(jar, tasks.named("testClasses"))
    classpath = files(sourceSets.test.get().runtimeClasspath, jar.flatMap { it.archiveFile })
    mainClass = "io.github.lhotari.jonoffcpu.agent.NativeBundleLoaderTest"
    jvmArgs("-ea")
    onlyIf("selected native platforms include the current host") {
        nativePlatforms.containsKey(hostPlatform.get())
    }
}

tasks.named("test") {
    enabled = false
}
tasks.named("check") {
    dependsOn(
        fixtureTasks,
        testShadedAgentJar,
        testNativeCollectorJni,
        testNativeBundleLoader,
        verifyRuntimeJar
    )
}

mavenPublishing {
    publishToMavenCentral()
    // The base plugin leaves this property switch, which the release workflow sets, to the build script.
    if (providers.gradleProperty("signAllPublications").map(String::toBoolean).getOrElse(false)) {
        signAllPublications()
    }
    coordinates(project.group.toString(), "jonoffcpu-agent", project.version.toString())
    pom {
        name.set("jonoffcpu agent")
        description.set("Self-contained Linux off-CPU profiling agent for the JVM.")
        url.set("https://github.com/lhotari/jonoffcpu")
        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/license/mit")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("lhotari")
                name.set("Lari Hotari")
                email.set("lari+jonoffcpu@hotari.net")
                url.set("https://github.com/lhotari")
            }
        }
        scm {
            connection.set("scm:git:https://github.com/lhotari/jonoffcpu.git")
            developerConnection.set("scm:git:ssh://git@github.com/lhotari/jonoffcpu.git")
            url.set("https://github.com/lhotari/jonoffcpu")
        }
    }
}
