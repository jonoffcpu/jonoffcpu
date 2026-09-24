import com.diffplug.gradle.spotless.SpotlessExtension
import com.diffplug.spotless.LineEnding

// The root project only formats: the modules apply the convention plugins in build-logic/, and every library and
// plugin version is in gradle/libs.versions.toml. Each module's `check` depends on this `spotlessCheck`.
plugins {
    alias(libs.plugins.spotless)
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
        target("*.md", ".gitignore", "*.yml", "*.yaml", "gradle/*.toml")
        trimTrailingWhitespace()
        endWithNewline()
    }
}
