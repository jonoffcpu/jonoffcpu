// Publication to Maven Central under io.github.lhotari:<project name>. The module registers its publication and
// sets `jonoffcpuPublication.displayName` and `description`; the license is MIT unless it says otherwise. The release
// workflow signs with -PsignAllPublications=true.
plugins {
    id("jonoffcpu.java-conventions")
    // The base plugin: a module publishes a component of its choice, such as the shaded JAR.
    id("com.vanniktech.maven.publish.base")
}

val publication = extensions.create<JonoffcpuPublicationExtension>("jonoffcpuPublication")
publication.licenseName.convention("MIT License")
publication.licenseUrl.convention("https://opensource.org/license/mit")

mavenPublishing {
    publishToMavenCentral()
    if (providers.gradleProperty("signAllPublications").map(String::toBoolean).getOrElse(false)) {
        signAllPublications()
    }
    coordinates(project.group.toString(), project.name, project.version.toString())
    pom {
        name = publication.displayName
        description = publication.description
        url = "https://github.com/lhotari/jonoffcpu"
        licenses {
            license {
                name = publication.licenseName
                url = publication.licenseUrl
                distribution = "repo"
            }
        }
        developers {
            developer {
                id = "lhotari"
                name = "Lari Hotari"
                email = "lari+jonoffcpu@hotari.net"
                url = "https://github.com/lhotari"
            }
        }
        scm {
            connection = "scm:git:https://github.com/lhotari/jonoffcpu.git"
            developerConnection = "scm:git:ssh://git@github.com/lhotari/jonoffcpu.git"
            url = "https://github.com/lhotari/jonoffcpu"
        }
    }
}
