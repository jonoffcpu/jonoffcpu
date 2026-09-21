import com.diffplug.gradle.spotless.SpotlessExtension

plugins {
    id("com.diffplug.spotless") version "8.10.2"
}

repositories {
    mavenCentral()
}

configure<SpotlessExtension> {
    java {
        target(
            "jonoffcpu-agent/src/**/*.java",
            "jonoffcpu-correlator/src/**/*.java",
        )
        removeUnusedImports()
        palantirJavaFormat("2.98.0")
        formatAnnotations()
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle {
        ktlint()
    }
    format("misc") {
        target("*.md", ".gitignore", "*.yml", "*.yaml")
        trimTrailingWhitespace()
        endWithNewline()
    }
}

subprojects {
    pluginManager.withPlugin("java") {
        tasks.named("check") {
            dependsOn(rootProject.tasks.named("spotlessCheck"))
        }
    }
}
