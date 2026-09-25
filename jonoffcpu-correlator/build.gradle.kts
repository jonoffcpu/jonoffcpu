plugins {
    id("jonoffcpu.shaded-jar-conventions")
    id("jonoffcpu.protobuf-conventions")
}

jonoffcpuPublication {
    displayName = "jonoffcpu correlator"
    description = "Offline correlation and analysis tools for jonoffcpu recordings."
}

dependencies {
    embeddedRuntime(project(":jonoffcpu-capture-codec"))
    embeddedRuntime(libs.protobuf.java)
    embeddedRuntime(libs.protobuf.java.util)
    // Not used by the correlator's own code: protobuf-java-util's JsonFormat, which prints and parses every JSON
    // output, needs it at run time.
    embeddedRuntime(libs.gson)
    embeddedRuntime(libs.picocli)
    testFixturesApi(testFixtures(project(":jonoffcpu-capture-codec")))
}

val rootDirectory = isolated.rootProject.projectDirectory

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
                    .file("io/github/jonoffcpu/correlator/version.properties")
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

tasks.shadowJar {
    relocate("com.google.protobuf", "io.github.jonoffcpu.correlator.internal.shaded.protobuf")
    relocate("com.google.gson", "io.github.jonoffcpu.correlator.internal.shaded.gson")
    relocate("picocli", "io.github.jonoffcpu.correlator.internal.shaded.picocli")
    manifest {
        attributes("Main-Class" to "io.github.jonoffcpu.correlator.OffCpuCorrelator")
    }
}
val correlatorJar = tasks.shadowJar.flatMap { it.archiveFile }

val verifyRuntimeJar =
    tasks.register<VerifyJarContents>("verifyRuntimeJar") {
        description = "Checks the self-contained offline JAR and its license."
        jar = correlatorJar
        label = "Correlator JAR"
        requiredEntries =
            listOf(
                "META-INF/LICENSE",
                "io/github/jonoffcpu/correlator/OffCpuCorrelator.class",
                "io/github/jonoffcpu/correlator/SignalJfrExporter.class",
                "io/github/jonoffcpu/correlator/ReportProto.class",
                "io/github/jonoffcpu/codec/ProtoJson.class",
                "io/github/jonoffcpu/correlator/internal/shaded/protobuf/CodedInputStream.class",
                // Every JSON output is printed by JsonFormat, which parses with the relocated Gson.
                "io/github/jonoffcpu/correlator/internal/shaded/protobuf/util/JsonFormat.class",
                "io/github/jonoffcpu/correlator/internal/shaded/gson/JsonParser.class",
                "io/github/jonoffcpu/correlator/internal/shaded/picocli/CommandLine.class",
            )
        // Unrelocated dependencies, and the agent's classes.
        forbiddenPrefixes =
            listOf("com/google/gson/", "com/google/protobuf/", "picocli/", "io/github/jonoffcpu/agent/")
    }
tasks.check {
    dependsOn(verifyRuntimeJar)
}

// A recorded sample's frames carry their execution type, so a fixture method the JIT compiles partway through a
// recording splits one stack into several, differently from run to run, and the retention the degradation and scale
// tests measure grows with the distinct stacks. The fixture's own methods therefore always run interpreted.
tasks.withType<Test>().configureEach {
    jvmArgs(
        "-XX:CompileCommand=quiet",
        "-XX:CompileCommand=exclude,io.github.jonoffcpu.correlator.ScaleFixture::*",
        "-XX:CompileCommand=dontinline,io.github.jonoffcpu.correlator.ScaleFixture::*",
    )
}

testing.suites.named<JvmTestSuite>("test") {
    dependencies {
        implementation(libs.commonmark)
        implementation(libs.commonmark.ext.gfm.tables)
        implementation(libs.commonmark.ext.heading.anchor)
    }
}

tasks.test {
    maxHeapSize = "1g"
    (options as JUnitPlatformOptions).excludeTags("readme")
}

// The README's option tables are checked against the parser, so documentation and help cannot drift. The check is a
// target of its own of the unit test suite because the README is its input: editing the README reruns this one test,
// not the unit tests.
testing.suites.named<JvmTestSuite>("test") {
    targets.register("readmeTest") {
        testTask.configure {
            description = "Checks the README's correlator option tables against the command-line parser."
            (options as JUnitPlatformOptions).includeTags("readme")
            val readme = rootDirectory.file("README.md")
            inputs.file(readme).withPropertyName("readme").withPathSensitivity(PathSensitivity.NONE)
            systemProperty("jonoffcpu.readme", readme.asFile.absolutePath)
            shouldRunAfter(tasks.test)
        }
    }
}

tasks.named<Test>("integrationTest") {
    // The specs' reference numbers, on recordings kept outside the repository: -PjonoffcpuFixtures=DIR runs them.
    systemProperty("jonoffcpu.fixtures", providers.gradleProperty("jonoffcpuFixtures").getOrElse(""))
    (options as JUnitPlatformOptions).excludeTags("scale")
}

// Spec acceptance 4: the scale test's assertion is the heap cap itself, so it runs in a JVM of its own under exactly
// the bound it proves. At its full size it is -PscaleRows=2000000 -PscaleHeap=1g.
testing.suites.named<JvmTestSuite>("integrationTest") {
    targets.register("scaleTest") {
        testTask.configure {
            description = "Checks that correlation retention tracks distinct stacks, not intervals, under a capped heap."
            (options as JUnitPlatformOptions).includeTags("scale")
            systemProperty("jonoffcpu.scaleRows", providers.gradleProperty("scaleRows").getOrElse(""))
            maxHeapSize = providers.gradleProperty("scaleHeap").getOrElse("128m")
            // Deeper than the fixture's deepest recursion, so each depth is a stack of its own.
            jvmArgs("-XX:FlightRecorderOptions:stackdepth=256")
        }
    }
}

// JUnit loads every class it scans before reading its tags, and the other integration tests need classes this
// classpath leaves out on purpose, so the packaged-JAR tests are also named.
tasks.named<Test>("packagedJarTest") {
    filter { includeTestsMatching("io.github.jonoffcpu.correlator.packaging.*") }
}
