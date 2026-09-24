// The capture stream and collector protocol codecs, generated once from src/main/proto, the formats' single
// definitions, which the Rust collector compiles too, and the stream's framing. It is not published: the agent and
// the correlator embed it in their shaded JARs, with the protobuf runtime relocated into their own packages. Its test
// fixtures write and read streams of records for both modules' tests.
plugins {
    id("jonoffcpu.protobuf-conventions")
}

jonoffcpu {
    // The agent's release, the lowest of the modules that embed it.
    javaRelease = 17
}

dependencies {
    api(libs.protobuf.java)
    api(libs.protobuf.java.util)
}
