import com.diffplug.gradle.spotless.SpotlessExtension

// Plugin versions live here, and the publish, shadow and protobuf plugins are loaded in the root
// scope with `apply false` so that every subproject shares one instance of each. Declaring them in
// the subprojects instead gives a module whose plugin set differs from its siblings' a class loader
// of its own, and the publish plugin's shared build service then fails to cross that boundary.
plugins {
    id("com.diffplug.spotless") version "8.10.2"
    id("com.vanniktech.maven.publish.base") version "0.37.0" apply false
    id("com.gradleup.shadow") version "9.6.1" apply false
    id("com.google.protobuf") version "0.10.0" apply false
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
