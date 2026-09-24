// Every jonoffcpu module: built by Amazon Corretto 25 for the release its `jonoffcpu.javaRelease` names (21 by
// default), with sources and javadoc JARs for Maven Central. Tests are JUnit Jupiter with AssertJ and Awaitility, in
// two suites: `test` holds unit tests that run on any platform with Java, and `integrationTest` holds the tests that
// need the native bundle, a packaged JAR or an external tool. `check` runs both, with the root project's formatting
// check. What both suites share, fixture builders and the workloads the tests launch, is in `testFixtures`.
plugins {
    `java-library`
    `java-test-fixtures`
    `jvm-test-suite`
}

val jonoffcpu = extensions.create<JonoffcpuJavaExtension>("jonoffcpu")
jonoffcpu.javaRelease.convention(21)

group = "io.github.lhotari"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
        vendor = JvmVendorSpec.AMAZON
    }
    withSourcesJar()
    withJavadocJar()
}

tasks.withType<JavaCompile>().configureEach {
    // The release also sets the JVM version the published variants declare.
    options.release = jonoffcpu.javaRelease
    options.encoding = "UTF-8"
}

tasks.withType<Javadoc>().configureEach {
    options.encoding = "UTF-8"
    (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
}

// Precompiled script plugins get no `libs` accessor.
val libs = versionCatalogs.named("libs")

fun library(alias: String) = libs.findLibrary(alias).get()

testing {
    suites {
        withType<JvmTestSuite>().configureEach {
            useJUnitJupiter(libs.findVersion("junit").get().requiredVersion)
            dependencies {
                implementation(platform(library("junit-bom")))
                implementation(platform(library("testcontainers-bom")))
                implementation(library("assertj-core"))
                implementation(library("awaitility"))
            }
        }
        register<JvmTestSuite>("integrationTest") {
            dependencies {
                implementation(project())
                // The classes also as directories: a container run puts directories ahead of every JAR, so the
                // module's own classes win over their relocated copies in its shaded JAR.
                implementation(sourceSets.main.get().output)
                // The fixtures the unit tests see, shared rather than copied.
                implementation(testFixtures(project()))
            }
            targets.configureEach {
                testTask.configure { shouldRunAfter(tasks.test) }
            }
        }
    }
}

// The fixtures are the tests' own and are never published, whichever component a module publishes.
val javaComponent = components["java"] as AdhocComponentWithVariants
configurations
    .matching { it.name.startsWith("testFixtures") && it.name.endsWith("Elements") }
    .configureEach { javaComponent.withVariantsFromConfiguration(this) { skip() } }

// Fixtures may check what they build with AssertJ; the rest of the test libraries stay with the suites.
dependencies {
    "testFixturesImplementation"(library("assertj-core"))
}

// Integration tests see what the unit tests see: the libraries a module adds to its unit tests, and their runtime
// libraries.
configurations.named("integrationTestImplementation") { extendsFrom(configurations.testImplementation.get()) }
configurations.named("integrationTestRuntimeOnly") { extendsFrom(configurations.testRuntimeOnly.get()) }

// Test libraries without the module's classes or its unrelocated dependencies, for tests that exercise a packaged JAR.
val packagedJarTestRuntime =
    configurations.register("packagedJarTestRuntime") {
        isCanBeConsumed = false
    }
dependencies {
    packagedJarTestRuntime(platform(library("junit-bom")))
    packagedJarTestRuntime("org.junit.jupiter:junit-jupiter")
    packagedJarTestRuntime("org.junit.platform:junit-platform-launcher")
    packagedJarTestRuntime(library("assertj-core"))
}

tasks.withType<Test>().configureEach {
    testLogging {
        events("started", "passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = false
    }
    // No test may take more than a minute; the few that need longer say so with @Timeout.
    systemProperty("junit.jupiter.execution.timeout.default", "60 s")
    // Parallel across JVMs only: JFR recordings in one JVM would capture each other's events. Each test class gets a
    // fresh JVM, so what an earlier class compiled or recorded cannot change how a later one's fixtures come out.
    maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)
    forkEvery = 1
}

tasks.named("check") {
    dependsOn(tasks.named("integrationTest"))
    // By path, so that no project configures another.
    dependsOn(":spotlessCheck")
}
