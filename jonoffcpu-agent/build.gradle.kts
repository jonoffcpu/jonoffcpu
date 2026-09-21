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
    id("com.vanniktech.maven.publish") version "0.37.0"
    id("com.gradleup.shadow") version "9.6.1"
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
    embeddedRuntime("com.google.code.gson:gson:2.14.0")
    embeddedRuntime("org.yaml:snakeyaml:2.7")
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
    description = "Checks the exact Gson and SnakeYAML artifacts before embedding them."
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

val allNativePlatforms = linkedMapOf("linux-x86_64" to 62, "linux-aarch64" to 183)
val hostPlatform = providers.systemProperty("os.arch").map { architecture ->
    when (architecture.lowercase()) {
        "amd64", "x86_64" -> "linux-x86_64"
        "aarch64", "arm64" -> "linux-aarch64"
        else -> throw GradleException("Unsupported build host architecture: $architecture")
    }
}
val nativeArchitectureSelection = providers.gradleProperty("nativeArchitectures")
    .map { it.trim().lowercase() }
    .orElse("current")
val selectedNativePlatformNames = when (val selection = nativeArchitectureSelection.get()) {
    "current" -> setOf(hostPlatform.get())
    "all", "both" -> allNativePlatforms.keys
    "x86_64", "amd64", "linux-x86_64" -> setOf("linux-x86_64")
    "aarch64", "arm64", "linux-aarch64" -> setOf("linux-aarch64")
    else -> throw GradleException(
        "Unsupported nativeArchitectures value '$selection'; expected current, all, x86_64, or aarch64"
    )
}
val nativePlatforms = allNativePlatforms.filterKeys(selectedNativePlatformNames::contains)
val nativeFileNames = listOf("libjonoffcpu.so", "libjonoffcpu_native.so", "libasyncProfiler.so")
val nativeRoot = layout.buildDirectory.dir("native")
val dockerfile = layout.projectDirectory.file("tools/Dockerfile.native-bundle")
val prebuiltNative = providers.gradleProperty("prebuiltNative").map(String::toBoolean).orElse(false)

val nativeTasks = allNativePlatforms.mapValues { (platform, _) ->
    val dockerPlatform = if (platform == "linux-x86_64") "linux/amd64" else "linux/arm64"
    val taskSuffix = if (platform == "linux-x86_64") "LinuxX86_64" else "LinuxAarch64"
    val output = nativeRoot.map { it.dir(platform) }
    tasks.register<Exec>("build${taskSuffix}Native") {
        group = "native build"
        description = "Builds the agent, collector, and async-profiler libraries for $platform in Docker buildx."
        workingDir(rootProject.layout.projectDirectory)
        commandLine(
            "docker", "buildx", "build", "--progress=plain",
            "--platform", dockerPlatform,
            "--file", dockerfile.asFile.absolutePath,
            "--output", output.map { "type=local,dest=${it.asFile.absolutePath}" }.get(),
            rootProject.layout.projectDirectory.asFile.absolutePath
        )
        inputs.file(dockerfile)
        inputs.files(
            fileTree(rootProject.file("jonoffcpu-agent/src/main/c")),
            fileTree(rootProject.file("jonoffcpu-native/src")),
            rootProject.file("jonoffcpu-native/Cargo.toml"),
            rootProject.file("jonoffcpu-native/Cargo.lock"),
            fileTree(rootProject.file("async-profiler/src")),
            rootProject.file("async-profiler/Makefile")
        )
        outputs.files(nativeFileNames.map { name -> output.map { it.file(name) } })
        doFirst {
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

val verifyNativeArchitectures = tasks.register("verifyNativeArchitectures") {
    group = "verification"
    description = "Rejects missing, malformed, or mislabeled native libraries for the selected Linux architectures."
    if (!prebuiltNative.get()) {
        dependsOn(nativePlatforms.keys.map(nativeTasks::getValue))
    }
    inputs.files(nativePlatforms.keys.map { platform -> nativeRoot.map { it.dir(platform) } })
    doLast {
        nativePlatforms.forEach { (platform, expectedMachine) ->
            nativeFileNames.forEach { name ->
                val artifact = nativeRoot.get().dir(platform).file(name).asFile
                if (!artifact.isFile) {
                    throw GradleException("Missing $platform/$name")
                }
                val actual = elfMachine(artifact.readBytes())
                if (actual != expectedMachine) {
                    throw GradleException("$platform/$name has ELF e_machine $actual, expected $expectedMachine")
                }
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

tasks.named<Jar>("jar") {
    archiveClassifier = "plain"
}

val jar = tasks.named<ShadowJar>("shadowJar") {
    dependsOn(verifyDependencyDigests, generateNativeChecksums)
    archiveClassifier = ""
    configurations = listOf(embeddedRuntime)
    relocate("com.google.gson", "io.github.lhotari.jonoffcpu.internal.shaded.gson")
    relocate("org.yaml.snakeyaml", "io.github.lhotari.jonoffcpu.internal.shaded.snakeyaml")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
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

val verifyRuntimeJar = tasks.register("verifyRuntimeJar") {
    group = "verification"
    description = "Checks bundled native entries, ELF architectures, licenses, and recorded SHA-256 digests."
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
                    it.name.startsWith("com/google/gson/") || it.name.startsWith("org/yaml/snakeyaml/")
                }) {
                throw GradleException("Agent JAR contains unrelocated dependency packages")
            }
            for (name in listOf(
                "io/github/lhotari/jonoffcpu/internal/shaded/gson/Gson.class",
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
                    val match = Regex("^([0-9a-f]{64})  (linux-(?:x86_64|aarch64)/[^/]+)\$").matchEntire(line)
                        ?: throw GradleException("Malformed native checksum line: $line")
                    recorded[match.groupValues[2]] = match.groupValues[1]
                }
            }
            nativePlatforms.forEach { (platform, expectedMachine) ->
                nativeFileNames.forEach { name ->
                    val relative = "$platform/$name"
                    val entry = zip.getEntry("META-INF/native/$relative")
                        ?: throw GradleException("Runtime JAR is missing $relative")
                    val bytes = zip.getInputStream(entry).use { it.readAllBytes() }
                    if (elfMachine(bytes) != expectedMachine) {
                        throw GradleException("Runtime JAR contains the wrong architecture at $relative")
                    }
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
    "SignalCaptureController" to "io.github.lhotari.jonoffcpu.agent.SignalCaptureControllerTest"
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
    onlyIf("selected native architectures include the current host") {
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
    onlyIf("selected native architectures include the current host") {
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
