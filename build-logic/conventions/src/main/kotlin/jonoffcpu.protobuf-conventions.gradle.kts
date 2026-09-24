// A codec generated from the module's src/main/proto, in the lite runtime: it has no descriptors or reflection, which
// the formats do not need.
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
