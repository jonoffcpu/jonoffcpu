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
    id("com.vanniktech.maven.publish") version "0.37.0"
    id("com.gradleup.shadow") version "9.6.1"
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
    embeddedRuntime("com.google.code.gson:gson:2.14.0")
    embeddedRuntime("org.openjdk.jmc:flightrecorder.writer:9.1.2")
    jmcWriterSources("org.openjdk.jmc:flightrecorder.writer:9.1.2:sources@jar")
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
    "gson-2.14.0.jar" to "2cbd119bf1961c28788310963dc80ba65f58cdeec1dd139c8bdb1240faa2c36f",
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
    description = "Checks the exact Gson and patched JMC writer artifacts before embedding them."
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

tasks.named<Jar>("jar") {
    archiveClassifier = "plain"
}

val jar = tasks.named<ShadowJar>("shadowJar") {
    dependsOn(compileJmcWriterPatch, verifyDependencyDigests)
    archiveClassifier = ""
    configurations = listOf(embeddedRuntime)
    relocate("com.google.gson", "io.github.lhotari.jonoffcpu.correlator.internal.shaded.gson")
    relocate("org.openjdk.jmc", "io.github.lhotari.jonoffcpu.correlator.internal.shaded.jmc")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
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

// Consumers of the Java API resolve apiElements/runtimeElements by default. The plain
// JAR neither embeds nor declares its relocated dependencies, so only the shaded JAR
// is a usable variant; publish it as the default and drop the plain-JAR variants.
listOf(configurations.apiElements, configurations.runtimeElements).forEach { elements ->
    elements.configure {
        outgoing.artifacts.clear()
        outgoing.artifact(jar)
        attributes.attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.SHADOWED))
    }
}
components.named<AdhocComponentWithVariants>("java") {
    withVariantsFromConfiguration(configurations.named("shadowRuntimeElements").get()) { skip() }
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
                    it.name.startsWith("com/google/gson/") || it.name.startsWith("org/openjdk/jmc/")
                }) {
                throw GradleException("Correlator JAR contains unrelocated dependency packages")
            }
            listOf(
                "io/github/lhotari/jonoffcpu/correlator/internal/shaded/gson/Gson.class",
                "io/github/lhotari/jonoffcpu/correlator/internal/shaded/jmc/flightrecorder/writer/api/Recordings.class",
                "io/github/lhotari/jonoffcpu/correlator/internal/shaded/jmc/flightrecorder/writer/ConstantPool.class"
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
    "PartialCorrelator" to "io.github.lhotari.jonoffcpu.offline.PartialCorrelatorTest"
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
