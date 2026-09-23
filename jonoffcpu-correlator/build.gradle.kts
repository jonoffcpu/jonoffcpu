import java.security.MessageDigest
import java.util.HexFormat
import java.util.zip.ZipFile
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.tasks.Sync
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
    archivesName = "jonoffcpu-correlator"
}

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
        vendor = JvmVendorSpec.AMAZON
    }
    targetCompatibility = JavaVersion.VERSION_21
    // Maven Central requires sources and javadoc JARs next to the shaded JAR.
    withSourcesJar()
    withJavadocJar()
}

val embeddedRuntime = configurations.create("embeddedRuntime") {
    isTransitive = false
}
val jmcWriterSources = configurations.create("jmcWriterSources") {
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
    embeddedRuntime("org.openjdk.jmc:flightrecorder.writer:9.1.2")
    embeddedRuntime("info.picocli:picocli:4.7.7")
    jmcWriterSources("org.openjdk.jmc:flightrecorder.writer:9.1.2:sources@jar")
}

// The build version and the async-profiler fork commit, for --version.
val asyncProfilerCommit =
    providers
        .exec {
            // The pinned gitlink, which is what the build embeds whether or not the submodule is checked out.
            commandLine("git", "-C", rootProject.projectDir.absolutePath, "rev-parse", "HEAD:async-profiler")
            isIgnoreExitValue = true
        }.standardOutput.asText
        .map { it.trim().ifEmpty { "unknown" } }
val generateVersionResource =
    tasks.register("generateVersionResource") {
        val version = project.version.toString()
        val commit = asyncProfilerCommit
        val outputDir = layout.buildDirectory.dir("generated/version-resource")
        inputs.property("version", version)
        inputs.property("asyncProfilerCommit", commit)
        outputs.dir(outputDir)
        doLast {
            val file =
                outputDir
                    .get()
                    .file("io/github/lhotari/jonoffcpu/offline/version.properties")
                    .asFile
            file.parentFile.mkdirs()
            file.writeText("version=$version\nasyncProfilerCommit=${commit.get()}\n")
        }
    }

sourceSets {
    main {
        proto.setSrcDirs(listOf(rootProject.layout.projectDirectory.dir("docs/schema")))
        resources.srcDir(generateVersionResource)
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
    options.release = 21
    options.encoding = "UTF-8"
}

tasks.withType<Javadoc>().configureEach {
    options.encoding = "UTF-8"
    (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
}

val buildAsyncProfilerConverter = tasks.register<Exec>("buildAsyncProfilerConverter") {
    group = "native build"
    description = "Builds jfrconv for compatibility validation."
    workingDir(asyncProfilerDir)
    commandLine("make", "build/bin/jfrconv")
    inputs.files(fileTree(asyncProfilerDir.dir("src/converter")), asyncProfilerDir.file("src/launcher/launcher.sh"))
    outputs.file(asyncProfilerConverter)
}

val expectedDependencyDigests = mapOf(
    "protobuf-javalite-4.33.1.jar" to "a1a1cccbcfa861e988b7ccde58dbe95204156906dd6cd42786b9c8f74d5fe34e",
    "gson-2.14.0.jar" to "2cbd119bf1961c28788310963dc80ba65f58cdeec1dd139c8bdb1240faa2c36f",
    "picocli-4.7.7.jar" to "f86e30fffd10d2b13b8caa8d4b237a7ee61f2ffccf5b1941de718b765d235bf8",
    "flightrecorder.writer-9.1.2.jar" to
        "8313e66f798f31de144c65b257a0434afca07b1bce1b59f17e63aed38c0dc9c1",
    "flightrecorder.writer-9.1.2-sources.jar" to
        "7b7c7841028543ec3462a69755b333a6e21a9ca09b85a1845a425337a0821863"
)

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
    description = "Checks the exact protobuf, Gson, picocli and patched JMC writer artifacts before embedding them."
    inputs.files(embeddedRuntime, jmcWriterSources)
    doLast {
        (embeddedRuntime.files + jmcWriterSources.files).forEach { artifact ->
            val expected = expectedDependencyDigests[artifact.name]
                ?: throw GradleException("No pinned digest for ${artifact.name}")
            val actual = sha256File(artifact)
            if (actual != expected) {
                throw GradleException("SHA-256 mismatch for ${artifact.name}: $actual")
            }
        }
    }
}

val jmcPatchSourceDir = layout.buildDirectory.dir("generated/jmc-writer-patch/src")
val extractJmcWriterSources = tasks.register<Sync>("extractJmcWriterSources") {
    dependsOn(verifyDependencyDigests)
    from(provider { zipTree(jmcWriterSources.singleFile) }) {
        include("org/openjdk/jmc/flightrecorder/writer/ConstantPool.java")
        include("org/openjdk/jmc/flightrecorder/writer/TypesImpl.java")
    }
    into(jmcPatchSourceDir)
}

val applyJmcWriterPatch = tasks.register<Exec>("applyJmcWriterPatch") {
    dependsOn(extractJmcWriterSources)
    workingDir(jmcPatchSourceDir)
    environment(
        "JMC_WRITER_PATCH",
        layout.projectDirectory.file("third-party/jmc-flightrecorder-writer-9.1.2-compat.patch").asFile
    )
    commandLine("bash", "-ceu", "patch --batch --forward -p1 < \"\$JMC_WRITER_PATCH\"; touch .applied")
    inputs.file("third-party/jmc-flightrecorder-writer-9.1.2-compat.patch")
    outputs.file(jmcPatchSourceDir.map { it.file(".applied") })
}

val compileJmcWriterPatch = tasks.register<JavaCompile>("compileJmcWriterPatch") {
    dependsOn(applyJmcWriterPatch)
    source(fileTree(jmcPatchSourceDir) { include("**/*.java") })
    classpath = embeddedRuntime
    destinationDirectory = layout.buildDirectory.dir("classes/jmc-writer-patch")
    options.release = 21
    options.encoding = "UTF-8"
}

// The plain JAR is never published or consumed; the shaded JAR below is the only artifact.
tasks.named<Jar>("jar") {
    enabled = false
}

val jar = tasks.named<ShadowJar>("shadowJar") {
    dependsOn(compileJmcWriterPatch, verifyDependencyDigests)
    archiveClassifier = ""
    configurations = listOf(embeddedRuntime)
    relocate("com.google.protobuf", "io.github.lhotari.jonoffcpu.correlator.internal.shaded.protobuf")
    relocate("com.google.gson", "io.github.lhotari.jonoffcpu.correlator.internal.shaded.gson")
    relocate("org.openjdk.jmc", "io.github.lhotari.jonoffcpu.correlator.internal.shaded.jmc")
    relocate("picocli", "io.github.lhotari.jonoffcpu.correlator.internal.shaded.picocli")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    // The bundled libraries' Maven descriptors and ProGuard rules describe their original,
    // unrelocated coordinates and packages, so they are misleading inside the shaded JAR.
    exclude("META-INF/maven/**", "META-INF/proguard/**")
    manifest {
        attributes(
            "Main-Class" to "io.github.lhotari.jonoffcpu.offline.OffCpuCorrelator",
            "Implementation-Title" to "jonoffcpu-correlator",
            "Implementation-Version" to project.version
        )
    }
    from(compileJmcWriterPatch.flatMap { it.destinationDirectory })
    from(rootProject.file("LICENSE")) {
        into("META-INF")
    }
}

// The plain JAR neither embeds nor declares its relocated dependencies, so the shaded JAR is the only
// usable artifact. Publishing the Shadow plugin's component makes it the module's sole runtime
// variant: a consumer that asks for nothing in particular gets it, since Gradle accepts a shadowed
// variant when no external one exists. The correlator has no separate API, so no apiElements is published.
publishing {
    publications.register<MavenPublication>("maven") {
        from(components["shadow"])
        artifact(tasks.named("sourcesJar"))
        artifact(tasks.named("javadocJar"))
    }
}

val verifyRuntimeJar = tasks.register("verifyRuntimeJar") {
    group = "verification"
    description = "Checks the self-contained offline JAR and retained dependency licenses."
    dependsOn(jar)
    inputs.file(jar.flatMap { it.archiveFile })
    doLast {
        ZipFile(jar.get().archiveFile.get().asFile).use { zip ->
            listOf(
                "META-INF/LICENSE",
                "META-INF/licenses/org.openjdk.jmc-flightrecorder.writer-LICENSE.txt",
                "META-INF/licenses/org.openjdk.jmc-flightrecorder.writer-THIRD_PARTY_LICENSES.txt",
                "io/github/lhotari/jonoffcpu/offline/OffCpuCorrelator.class",
                "io/github/lhotari/jonoffcpu/jfr/SignalJfrExporter.class"
            ).forEach { name ->
                if (zip.getEntry(name) == null) throw GradleException("Offline JAR is missing $name")
            }
            if (zip.entries().asSequence().any {
                    it.name.startsWith("com/google/gson/")
                            || it.name.startsWith("org/openjdk/jmc/")
                            || it.name.startsWith("com/google/protobuf/")
                            || it.name.startsWith("picocli/")
                }) {
                throw GradleException("Correlator JAR contains unrelocated dependency packages")
            }
            listOf(
                "io/github/lhotari/jonoffcpu/correlator/internal/shaded/gson/Gson.class",
                "io/github/lhotari/jonoffcpu/correlator/internal/shaded/protobuf/CodedInputStream.class",
                "io/github/lhotari/jonoffcpu/correlator/internal/shaded/jmc/flightrecorder/writer/api/Recordings.class",
                "io/github/lhotari/jonoffcpu/correlator/internal/shaded/jmc/flightrecorder/writer/ConstantPool.class",
                "io/github/lhotari/jonoffcpu/correlator/internal/shaded/picocli/CommandLine.class"
            ).forEach { name ->
                if (zip.getEntry(name) == null) throw GradleException("Correlator JAR is missing relocated class $name")
            }
            if (zip.entries().asSequence().any { it.name.startsWith("io/github/lhotari/jonoffcpu/agent/") }) {
                throw GradleException("Correlator JAR must not embed agent classes")
            }
        }
    }
}

val fixtureMains = mapOf(
    "SignalJfrExporter" to "io.github.lhotari.jonoffcpu.jfr.SignalJfrExporterTest",
    "OfflineCorrelator" to "io.github.lhotari.jonoffcpu.offline.OfflineCorrelatorTest",
    "PartialCorrelator" to "io.github.lhotari.jonoffcpu.offline.PartialCorrelatorTest",
    "PrimitiveStructures" to "io.github.lhotari.jonoffcpu.offline.PrimitiveStructuresTest",
    "StreamingCorrelator" to "io.github.lhotari.jonoffcpu.offline.StreamingCorrelatorTest",
    "StackProfile" to "io.github.lhotari.jonoffcpu.offline.StackProfileTest",
    "CommandLine" to "io.github.lhotari.jonoffcpu.offline.CommandLineTest",
    "StackTransforms" to "io.github.lhotari.jonoffcpu.offline.StackTransformsTest",
    "Export" to "io.github.lhotari.jonoffcpu.offline.ExportTest",
    "Top" to "io.github.lhotari.jonoffcpu.offline.TopTest",
    "FixtureAcceptance" to "io.github.lhotari.jonoffcpu.offline.FixtureAcceptanceTest"
)
val fixtureTasks = fixtureMains.map { (taskName, className) ->
    tasks.register<JavaExec>("test$taskName") {
        group = "verification"
        dependsOn(jar, tasks.named("testClasses"))
        classpath = files(compileJmcWriterPatch.flatMap { it.destinationDirectory },
            sourceSets.test.get().runtimeClasspath, jar.flatMap { it.archiveFile })
        mainClass = className
        jvmArgs("-ea")
    }
}

// Spec acceptance 4: the scale fixture's assertion is the heap cap itself, so it must run under
// exactly the bound it proves, not whatever heap the other fixtures happen to get.
// The README's option tables are checked against the parser, so documentation and help cannot drift.
tasks.named<JavaExec>("testCommandLine") {
    inputs.file(rootProject.file("README.md"))
    systemProperty("jonoffcpu.readme", rootProject.file("README.md").absolutePath)
}

// The specs' reference numbers, on recordings kept outside the repository: -PjonoffcpuFixtures=DIR runs them.
tasks.named<JavaExec>("testFixtureAcceptance") {
    val fixtures = providers.gradleProperty("jonoffcpuFixtures").orElse("")
    inputs.property("fixtures", fixtures)
    systemProperty("jonoffcpu.fixtures", fixtures.get())
}

tasks.named<JavaExec>("testStreamingCorrelator") {
    jvmArgs("-ea", "-Xmx1g")
}

val testCorrelatorPublicApi = tasks.register<JavaExec>("testCorrelatorPublicApi") {
    group = "verification"
    description = "Checks the published correlator JAR's dependency-free public API."
    dependsOn(jar, tasks.named("testClasses"))
    classpath = files(sourceSets.test.get().output, jar.flatMap { it.archiveFile })
    mainClass = "io.github.lhotari.jonoffcpu.packaging.CorrelatorPublicApiTest"
    jvmArgs("-ea")
}

val testCompatibilityJfrWriter = tasks.register<JavaExec>("testCompatibilityJfrWriter") {
    group = "verification"
    dependsOn(jar, tasks.named("testClasses"), buildAsyncProfilerConverter)
    classpath = files(compileJmcWriterPatch.flatMap { it.destinationDirectory },
            sourceSets.test.get().runtimeClasspath, jar.flatMap { it.archiveFile })
    mainClass = "io.github.lhotari.jonoffcpu.offline.CompatibilityJfrWriterTest"
    jvmArgs("-ea")
    args(asyncProfilerConverter.asFile.absolutePath)
}

tasks.named("test") {
    enabled = false
}
tasks.named("check") {
    dependsOn(fixtureTasks, testCompatibilityJfrWriter, testCorrelatorPublicApi, verifyRuntimeJar)
}

mavenPublishing {
    publishToMavenCentral()
    // The base plugin leaves this property switch, which the release workflow sets, to the build script.
    if (providers.gradleProperty("signAllPublications").map(String::toBoolean).getOrElse(false)) {
        signAllPublications()
    }
    coordinates(project.group.toString(), "jonoffcpu-correlator", project.version.toString())
    pom {
        name.set("jonoffcpu correlator")
        description.set("Offline correlation and analysis tools for jonoffcpu recordings.")
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
