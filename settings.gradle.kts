pluginManagement {
    // The convention plugins every module applies; see build-logic/.
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    // Repositories are declared here, once; a module that declares its own is an error.
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

rootProject.name = "jonoffcpu"

include("jonoffcpu-agent", "jonoffcpu-correlator", "jonoffcpu-jfr-converter")
