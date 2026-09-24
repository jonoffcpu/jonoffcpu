// A codec generated from the module's src/main/proto for the full protobuf runtime, whose descriptors let JsonFormat
// print and parse every message in the proto3 JSON mapping.
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
}
