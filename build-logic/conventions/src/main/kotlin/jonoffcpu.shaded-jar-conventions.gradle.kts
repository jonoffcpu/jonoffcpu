import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

// A self-contained module: its runtime libraries, declared in `embeddedRuntime`, are verified against pinned
// SHA-256 digests (`verifyDependencyDigests.digests`) when they are external, relocated into the module's own package
// by the module's shadowJar configuration, and embedded in the one JAR that is built and published. The module adds its relocations,
// manifest entries and any further contents.
plugins {
    id("jonoffcpu.publish-conventions")
    id("com.gradleup.shadow")
}

val embeddedRuntime =
    configurations.create("embeddedRuntime") {
        isTransitive = false
        isCanBeConsumed = false
    }
configurations.compileOnly {
    extendsFrom(embeddedRuntime)
}
// The plain variants describe the module's own, unrelocated classes to consumers inside the build, so they carry the
// unrelocated libraries those classes need. They are never published.
configurations.apiElements {
    extendsFrom(embeddedRuntime)
}
configurations.runtimeElements {
    extendsFrom(embeddedRuntime)
}

val verifyDependencyDigests =
    tasks.register<VerifyDependencyDigests>("verifyDependencyDigests") {
        group = "verification"
        description = "Checks the exact embedded artifacts against their pinned SHA-256 digests before embedding them."
        // Only external libraries are pinned; the build's own projects, such as the capture codec, are built here.
        artifacts.from(embeddedRuntime.incoming.artifactView { componentFilter { it is ModuleComponentIdentifier } }.files)
    }

// The plain JAR is what the plain variants hold, for consumers inside the build; it is never published. Its classifier
// keeps it apart from the shaded JAR, which is the only published artifact and takes the unclassified name.
tasks.named<Jar>("jar") {
    archiveClassifier = "plain"
}

tasks.named<ShadowJar>("shadowJar") {
    dependsOn(verifyDependencyDigests)
    archiveClassifier = ""
    configurations = listOf(embeddedRuntime)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    // The bundled libraries' Maven descriptors and ProGuard rules describe their original,
    // unrelocated coordinates and packages, so they are misleading inside the shaded JAR.
    exclude("META-INF/maven/**", "META-INF/proguard/**")
    manifest {
        attributes(
            "Implementation-Title" to project.name,
            "Implementation-Version" to project.version.toString(),
        )
    }
    from(isolated.rootProject.projectDirectory.file("LICENSE")) {
        into("META-INF")
    }
}

// The plain JAR neither embeds nor declares its relocated dependencies, so the shaded JAR is the only usable
// artifact. Publishing the Shadow plugin's component makes it the module's sole runtime variant: a consumer that
// asks for nothing in particular gets it, since Gradle accepts a shadowed variant when no external one exists.
// The module has no separate API, so no apiElements is published.
publishing {
    publications.register<MavenPublication>("maven") {
        from(components["shadow"])
        artifact(tasks.named("sourcesJar"))
        artifact(tasks.named("javadocJar"))
    }
}

// Integration tests tagged `packaged-jar` exercise the published JAR alone: their classpath has the JAR and the test
// libraries, but neither the module's classes nor its unrelocated dependencies, so they prove the relocation.
val integrationTestSourceSet = sourceSets.named("integrationTest")
val packagedJarTest =
    tasks.register<Test>("packagedJarTest") {
        group = "verification"
        description = "Runs the integration tests tagged packaged-jar against the shaded JAR alone."
        testClassesDirs = files(integrationTestSourceSet.map { it.output.classesDirs })
        classpath =
            files(
                integrationTestSourceSet.map { it.output },
                tasks.named<ShadowJar>("shadowJar").flatMap { it.archiveFile },
                configurations.named("packagedJarTestRuntime"),
            )
        useJUnitPlatform { includeTags("packaged-jar") }
        shouldRunAfter(tasks.named("test"))
    }
tasks.named<Test>("integrationTest") {
    (options as JUnitPlatformOptions).excludeTags("packaged-jar")
}
tasks.named("check") {
    dependsOn(packagedJarTest)
}
