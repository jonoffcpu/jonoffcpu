import com.diffplug.gradle.spotless.SpotlessExtension
import com.diffplug.spotless.LineEnding

// The root project formats and groups checks: the modules apply the convention plugins in build-logic/, and every
// library and plugin version is in gradle/libs.versions.toml. Each module's `check` depends on this `spotlessCheck`.
plugins {
    alias(libs.plugins.spotless)
}

// Every check that needs only a JDK, on any operating system: no native bundle, no Docker. CI runs it once, and runs
// :jonoffcpu-agent:nativeTest once per architecture and C library. By path, so that no project configures another.
tasks.register("jvmCheck") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Runs the formatting check and every test that needs only a JDK."
    dependsOn(
        ":spotlessCheck",
        ":jonoffcpu-agent:test",
        ":jonoffcpu-capture-codec:check",
        ":jonoffcpu-correlator:check",
        ":jonoffcpu-jfr-converter:check",
    )
}

configure<SpotlessExtension> {
    // Every formatted file is LF. The default reads git attributes with JGit, whose file system probes would
    // invalidate the configuration cache on every run.
    lineEndings = LineEnding.UNIX
    java {
        target(
            "jonoffcpu-agent/src/**/*.java",
            "jonoffcpu-capture-codec/src/**/*.java",
            "jonoffcpu-correlator/src/**/*.java",
        )
        removeUnusedImports()
        palantirJavaFormat("2.98.0")
        formatAnnotations()
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle {
        target("*.gradle.kts", "*/build.gradle.kts", "build-logic/**/*.gradle.kts")
        targetExclude("**/build/**")
        ktlint()
    }
    kotlin {
        target("build-logic/**/*.kt")
        targetExclude("**/build/**")
        ktlint()
    }
    format("misc") {
        target("*.md", "docs/**/*.md", ".gitignore", "*.yml", "*.yaml", "gradle/*.toml")
        trimTrailingWhitespace()
        endWithNewline()
    }
}
