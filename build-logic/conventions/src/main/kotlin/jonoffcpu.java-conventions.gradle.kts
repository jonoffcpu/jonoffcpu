// Every jonoffcpu module: built by Amazon Corretto 25 for the release its `jonoffcpu.javaRelease` names (21 by
// default), with sources and javadoc JARs for Maven Central. The tests are main-based fixtures (FixtureExec), which
// `check` runs, together with the root project's formatting check.
plugins {
    `java-library`
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

tasks.named("test") {
    enabled = false
}

tasks.named("check") {
    dependsOn(tasks.withType<FixtureExec>())
    // By path, so that no project configures another.
    dependsOn(":spotlessCheck")
}
