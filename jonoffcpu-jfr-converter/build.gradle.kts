import java.util.zip.ZipFile
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.external.javadoc.StandardJavadocDocletOptions
import org.gradle.jvm.toolchain.JvmVendorSpec

// Builds async-profiler's jfr-converter from the pinned fork's sources, mirroring
// async-profiler/pom-converter.xml, so that the converter that understands the
// profiler.Signal* events is published next to the agent and correlator.
plugins {
    `java-library`
    // The base plugin: the build script registers the publication itself, as in the other modules.
    id("com.vanniktech.maven.publish.base")
}

group = "io.github.lhotari"

base {
    archivesName = "jonoffcpu-jfr-converter"
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
    // Maven Central requires sources and javadoc JARs next to the JAR.
    withSourcesJar()
    withJavadocJar()
}

val asyncProfilerDir = rootProject.layout.projectDirectory.dir("async-profiler")
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
tasks.named<ProcessResources>("processResources") {
    from(converterSourceDir) {
        include("META-INF/**")
    }
}

// The converter has no dependencies, so the plain JAR is self-contained and executable.
tasks.withType<JavaCompile>().configureEach {
    options.release = 21
    options.encoding = "UTF-8"
}

tasks.withType<Javadoc>().configureEach {
    options.encoding = "UTF-8"
    (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
}

val jar = tasks.named<Jar>("jar") {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    manifest {
        attributes(
            "Main-Class" to "one.convert.Main",
            "Implementation-Title" to "jonoffcpu-jfr-converter",
            "Implementation-Version" to project.version
        )
    }
    from(asyncProfilerDir.file("LICENSE")) {
        into("META-INF")
    }
}

val verifyRuntimeJar = tasks.register("verifyRuntimeJar") {
    group = "verification"
    description = "Checks the executable converter JAR's contents."
    dependsOn(jar)
    inputs.file(jar.flatMap { it.archiveFile })
    doLast {
        ZipFile(jar.get().archiveFile.get().asFile).use { zip ->
            listOf(
                "META-INF/LICENSE",
                "META-INF/native-image/tools.profiler/jfr-converter/reachability-metadata.json",
                "flame.html",
                "heatmap.html",
                "one/convert/Main.class",
                "one/jfr/JfrReader.class"
            ).forEach { name ->
                if (zip.getEntry(name) == null) throw GradleException("Converter JAR is missing $name")
            }
        }
    }
}

val converterCheckDir = layout.buildDirectory.dir("converter-check")
val testConverterRendersCollapsed = tasks.register<JavaExec>("testConverterRendersCollapsed") {
    group = "verification"
    description = "Checks that the converter JAR renders a collapsed off-CPU profile as a flame graph."
    dependsOn(jar)
    val collapsed = converterCheckDir.map { it.file("converter-check.collapsed") }
    val html = converterCheckDir.map { it.file("converter-check.html") }
    inputs.file(jar.flatMap { it.archiveFile })
    outputs.file(html)
    classpath = files(jar.flatMap { it.archiveFile })
    mainClass = "one.convert.Main"
    argumentProviders.add(
        CommandLineArgumentProvider {
            listOf("--title", "Off-CPU time", "--units", "µs", collapsed.get().asFile.path, html.get().asFile.path)
        }
    )
    doFirst {
        collapsed.get().asFile.apply {
            parentFile.mkdirs()
            writeText("a;b;c 1500\na;b;d 500\n")
        }
    }
    doLast {
        val rendered = html.get().asFile.readText()
        if (!rendered.contains("Off-CPU time")) {
            throw GradleException("Converter output does not contain the requested title")
        }
    }
}

tasks.named("test") {
    enabled = false
}
tasks.named("check") {
    dependsOn(verifyRuntimeJar, testConverterRendersCollapsed)
}

publishing {
    publications.register<MavenPublication>("maven") {
        from(components["java"])
    }
}

mavenPublishing {
    publishToMavenCentral()
    // The base plugin leaves this property switch, which the release workflow sets, to the build script.
    if (providers.gradleProperty("signAllPublications").map(String::toBoolean).getOrElse(false)) {
        signAllPublications()
    }
    coordinates(project.group.toString(), "jonoffcpu-jfr-converter", project.version.toString())
    pom {
        name.set("jonoffcpu jfr-converter")
        description.set(
            "async-profiler's jfr-converter built from the jonoffcpu fork, which understands the " +
                "signal-cookie JFR events that jonoffcpu records."
        )
        url.set("https://github.com/lhotari/jonoffcpu")
        // The converter is unmodified-license async-profiler code; only the packaging is jonoffcpu's.
        licenses {
            license {
                name.set("Apache License Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0")
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
