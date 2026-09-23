// The capture stream codec, generated from docs/schema, the format's single definition. The lite runtime has no
// descriptors or reflection, which is all the stream needs.
plugins {
    id("jonoffcpu.java-conventions")
    id("com.google.protobuf")
}

val protoc =
    versionCatalogs
        .named("libs")
        .findLibrary("protoc")
        .get()
        .get()

protobuf {
    protoc {
        artifact = "${protoc.module}:${protoc.versionConstraint.requiredVersion}"
    }
    generateProtoTasks {
        all().configureEach {
            builtins {
                named("java") {
                    option("lite")
                }
            }
        }
    }
}

sourceSets {
    main {
        proto.setSrcDirs(listOf(isolated.rootProject.projectDirectory.dir("docs/schema")))
    }
}
