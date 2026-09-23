plugins {
    id("jonoffcpu.shaded-jar-conventions")
    id("jonoffcpu.protobuf-conventions")
}

jonoffcpuPublication {
    displayName = "jonoffcpu correlator"
    description = "Offline correlation and analysis tools for jonoffcpu recordings."
}

val jmcWriterSources =
    configurations.create("jmcWriterSources") {
        isTransitive = false
        isCanBeConsumed = false
    }

dependencies {
    embeddedRuntime(libs.protobuf.javalite)
    embeddedRuntime(libs.gson)
    embeddedRuntime(libs.jmc.flightrecorder.writer)
    embeddedRuntime(libs.picocli)
    jmcWriterSources(variantOf(libs.jmc.flightrecorder.writer) { classifier("sources") })
}

tasks.verifyDependencyDigests {
    artifacts.from(jmcWriterSources)
    digests =
        mapOf(
            "protobuf-javalite-4.33.1.jar" to "a1a1cccbcfa861e988b7ccde58dbe95204156906dd6cd42786b9c8f74d5fe34e",
            "gson-2.14.0.jar" to "2cbd119bf1961c28788310963dc80ba65f58cdeec1dd139c8bdb1240faa2c36f",
            "picocli-4.7.7.jar" to "f86e30fffd10d2b13b8caa8d4b237a7ee61f2ffccf5b1941de718b765d235bf8",
            "flightrecorder.writer-9.1.2.jar" to "8313e66f798f31de144c65b257a0434afca07b1bce1b59f17e63aed38c0dc9c1",
            "flightrecorder.writer-9.1.2-sources.jar" to "7b7c7841028543ec3462a69755b333a6e21a9ca09b85a1845a425337a0821863",
        )
}

val rootDirectory = isolated.rootProject.projectDirectory
val asyncProfilerDir = rootDirectory.dir("async-profiler")
val asyncProfilerConverter = asyncProfilerDir.file("build/bin/jfrconv")

// The build version and the async-profiler fork commit, for --version.
val asyncProfilerCommit =
    providers
        .exec {
            // The pinned gitlink, which is what the build embeds whether or not the submodule is checked out.
            commandLine("git", "-C", rootDirectory.asFile.absolutePath, "rev-parse", "HEAD:async-profiler")
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
        resources.srcDir(generateVersionResource)
    }
}

val buildAsyncProfilerConverter =
    tasks.register<Exec>("buildAsyncProfilerConverter") {
        group = "native build"
        description = "Builds jfrconv for compatibility validation."
        workingDir(asyncProfilerDir)
        commandLine("make", "build/bin/jfrconv")
        inputs.files(fileTree(asyncProfilerDir.dir("src/converter")), asyncProfilerDir.file("src/launcher/launcher.sh"))
        outputs.file(asyncProfilerConverter)
    }

// Two classes of the JMC writer, patched for compatibility with JFR readers, compiled ahead of the writer's own.
val jmcPatchSourceDir = layout.buildDirectory.dir("generated/jmc-writer-patch/src")
val extractJmcWriterSources =
    tasks.register<Sync>("extractJmcWriterSources") {
        dependsOn(tasks.verifyDependencyDigests)
        from(provider { zipTree(jmcWriterSources.singleFile) }) {
            include("org/openjdk/jmc/flightrecorder/writer/ConstantPool.java")
            include("org/openjdk/jmc/flightrecorder/writer/TypesImpl.java")
        }
        into(jmcPatchSourceDir)
    }

val applyJmcWriterPatch =
    tasks.register<Exec>("applyJmcWriterPatch") {
        dependsOn(extractJmcWriterSources)
        val patch = layout.projectDirectory.file("third-party/jmc-flightrecorder-writer-9.1.2-compat.patch")
        workingDir(jmcPatchSourceDir)
        environment("JMC_WRITER_PATCH", patch.asFile)
        commandLine("bash", "-ceu", "patch --batch --forward -p1 < \"\$JMC_WRITER_PATCH\"; touch .applied")
        inputs.file(patch)
        outputs.file(jmcPatchSourceDir.map { it.file(".applied") })
    }

val compileJmcWriterPatch =
    tasks.register<JavaCompile>("compileJmcWriterPatch") {
        dependsOn(applyJmcWriterPatch)
        source(fileTree(jmcPatchSourceDir) { include("**/*.java") })
        classpath = files(configurations.named("embeddedRuntime"))
        destinationDirectory = layout.buildDirectory.dir("classes/jmc-writer-patch")
    }
val jmcWriterPatchClasses = compileJmcWriterPatch.flatMap { it.destinationDirectory }

tasks.shadowJar {
    relocate("com.google.protobuf", "io.github.lhotari.jonoffcpu.correlator.internal.shaded.protobuf")
    relocate("com.google.gson", "io.github.lhotari.jonoffcpu.correlator.internal.shaded.gson")
    relocate("org.openjdk.jmc", "io.github.lhotari.jonoffcpu.correlator.internal.shaded.jmc")
    relocate("picocli", "io.github.lhotari.jonoffcpu.correlator.internal.shaded.picocli")
    manifest {
        attributes("Main-Class" to "io.github.lhotari.jonoffcpu.offline.OffCpuCorrelator")
    }
    from(jmcWriterPatchClasses)
}
val correlatorJar = tasks.shadowJar.flatMap { it.archiveFile }

val verifyRuntimeJar =
    tasks.register<VerifyJarContents>("verifyRuntimeJar") {
        description = "Checks the self-contained offline JAR and retained dependency licenses."
        jar = correlatorJar
        label = "Correlator JAR"
        requiredEntries =
            listOf(
                "META-INF/LICENSE",
                "META-INF/licenses/org.openjdk.jmc-flightrecorder.writer-LICENSE.txt",
                "META-INF/licenses/org.openjdk.jmc-flightrecorder.writer-THIRD_PARTY_LICENSES.txt",
                "io/github/lhotari/jonoffcpu/offline/OffCpuCorrelator.class",
                "io/github/lhotari/jonoffcpu/jfr/SignalJfrExporter.class",
                "io/github/lhotari/jonoffcpu/correlator/internal/shaded/gson/Gson.class",
                "io/github/lhotari/jonoffcpu/correlator/internal/shaded/protobuf/CodedInputStream.class",
                "io/github/lhotari/jonoffcpu/correlator/internal/shaded/jmc/flightrecorder/writer/api/Recordings.class",
                "io/github/lhotari/jonoffcpu/correlator/internal/shaded/jmc/flightrecorder/writer/ConstantPool.class",
                "io/github/lhotari/jonoffcpu/correlator/internal/shaded/picocli/CommandLine.class",
            )
        // Unrelocated dependencies, and the agent's classes.
        forbiddenPrefixes =
            listOf("com/google/gson/", "org/openjdk/jmc/", "com/google/protobuf/", "picocli/", "io/github/lhotari/jonoffcpu/agent/")
    }
tasks.check {
    dependsOn(verifyRuntimeJar)
}

// The patched JMC writer classes come first, so that they replace the writer's own.
val fixtureClasspath = files(jmcWriterPatchClasses, sourceSets.test.map { it.runtimeClasspath }, correlatorJar)

mapOf(
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
    "FixtureAcceptance" to "io.github.lhotari.jonoffcpu.offline.FixtureAcceptanceTest",
).forEach { (name, className) ->
    tasks.register<FixtureExec>("test$name") {
        classpath = fixtureClasspath
        mainClass = className
    }
}

// The README's option tables are checked against the parser, so documentation and help cannot drift.
tasks.named<FixtureExec>("testCommandLine") {
    val readme = rootDirectory.file("README.md")
    inputs.file(readme)
    systemProperty("jonoffcpu.readme", readme.asFile.absolutePath)
}

// The specs' reference numbers, on recordings kept outside the repository: -PjonoffcpuFixtures=DIR runs them.
tasks.named<FixtureExec>("testFixtureAcceptance") {
    systemProperty("jonoffcpu.fixtures", providers.gradleProperty("jonoffcpuFixtures").getOrElse(""))
}

// Spec acceptance 4: the scale fixture's assertion is the heap cap itself, so it must run under
// exactly the bound it proves, not whatever heap the other fixtures happen to get.
tasks.named<FixtureExec>("testStreamingCorrelator") {
    maxHeapSize = "1g"
}

tasks.register<FixtureExec>("testCorrelatorPublicApi") {
    description = "Checks the published correlator JAR's dependency-free public API."
    classpath = files(sourceSets.test.map { it.output }, correlatorJar)
    mainClass = "io.github.lhotari.jonoffcpu.packaging.CorrelatorPublicApiTest"
}

tasks.register<FixtureExec>("testCompatibilityJfrWriter") {
    dependsOn(buildAsyncProfilerConverter)
    classpath = fixtureClasspath
    mainClass = "io.github.lhotari.jonoffcpu.offline.CompatibilityJfrWriterTest"
    args(asyncProfilerConverter.asFile.absolutePath)
}
