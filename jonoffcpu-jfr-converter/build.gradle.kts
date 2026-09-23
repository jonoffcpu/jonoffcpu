// Builds async-profiler's jfr-converter from the pinned fork's sources, mirroring
// async-profiler/pom-converter.xml, so that the converter that understands the
// profiler.Signal* events is published next to the agent and correlator.
plugins {
    id("jonoffcpu.publish-conventions")
}

jonoffcpuPublication {
    displayName = "jonoffcpu jfr-converter"
    description =
        "async-profiler's jfr-converter built from the jonoffcpu fork, which understands the " +
        "signal-cookie JFR events that jonoffcpu records."
    // The converter is unmodified-license async-profiler code; only the packaging is jonoffcpu's.
    licenseName = "Apache License Version 2.0"
    licenseUrl = "https://www.apache.org/licenses/LICENSE-2.0"
}

val asyncProfilerDir = isolated.rootProject.projectDirectory.dir("async-profiler")
val converterSourceDir = asyncProfilerDir.dir("src/converter")

// The same layout as pom-converter.xml: sources under src/converter, the flame graph
// and heat map templates from src/res, and the native-image metadata from src/converter.
sourceSets {
    main {
        java.setSrcDirs(listOf(converterSourceDir))
        resources.setSrcDirs(listOf(asyncProfilerDir.dir("src/res")))
    }
}
// A resource include filter would apply to every resource directory, so the metadata is copied separately.
tasks.processResources {
    from(converterSourceDir) {
        include("META-INF/**")
    }
}

// The converter has no dependencies, so the plain JAR is self-contained and executable.
tasks.jar {
    // An uninitialized submodule leaves no sources, which would otherwise yield an empty JAR.
    val converterMain = converterSourceDir.file("one/convert/Main.java").asFile
    doFirst {
        if (!converterMain.isFile) {
            throw GradleException("The async-profiler submodule is not checked out; run 'git submodule update --init'")
        }
    }
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    manifest {
        attributes(
            "Main-Class" to "one.convert.Main",
            "Implementation-Title" to project.name,
            "Implementation-Version" to project.version.toString(),
        )
    }
    from(asyncProfilerDir.file("LICENSE")) {
        into("META-INF")
    }
}
val converterJar = tasks.jar.flatMap { it.archiveFile }

val verifyRuntimeJar =
    tasks.register<VerifyJarContents>("verifyRuntimeJar") {
        description = "Checks the executable converter JAR's contents."
        jar = converterJar
        label = "Converter JAR"
        requiredEntries =
            listOf(
                "META-INF/LICENSE",
                "META-INF/native-image/tools.profiler/jfr-converter/reachability-metadata.json",
                "flame.html",
                "heatmap.html",
                "one/convert/Main.class",
                "one/jfr/JfrReader.class",
            )
    }
tasks.check {
    dependsOn(verifyRuntimeJar)
}

tasks.register<FixtureExec>("testConverterRendersCollapsed") {
    description = "Checks that the converter JAR renders a collapsed off-CPU profile as a flame graph."
    val checkDir = layout.buildDirectory.dir("converter-check")
    val collapsed = checkDir.map { it.file("converter-check.collapsed") }
    val html = checkDir.map { it.file("converter-check.html") }
    outputs.file(html)
    classpath = files(converterJar)
    mainClass = "one.convert.Main"
    argumentProviders.add(
        CommandLineArgumentProvider {
            listOf("--title", "Off-CPU time", "--units", "µs", collapsed.get().asFile.path, html.get().asFile.path)
        },
    )
    doFirst {
        collapsed.get().asFile.apply {
            parentFile.mkdirs()
            writeText("a;b;c 1500\na;b;d 500\n")
        }
    }
    doLast {
        if (!html
                .get()
                .asFile
                .readText()
                .contains("Off-CPU time")
        ) {
            throw GradleException("Converter output does not contain the requested title")
        }
    }
}

publishing {
    publications.register<MavenPublication>("maven") {
        from(components["java"])
    }
}
