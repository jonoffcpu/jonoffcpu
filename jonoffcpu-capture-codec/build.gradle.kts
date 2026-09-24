// The capture stream codec, generated once from src/main/proto/jonoffcpu-capture.proto, the format's single
// definition, which the Rust collector compiles too. It is not published: the agent and the correlator embed it in their shaded JARs, with the protobuf runtime relocated into their own packages. Its
// test fixtures encode fixture rows as stream records for both modules' tests.
plugins {
    id("jonoffcpu.protobuf-conventions")
}

jonoffcpu {
    // The agent's release, the lowest of the modules that embed it.
    javaRelease = 17
}

dependencies {
    api(libs.protobuf.javalite)
    testFixturesImplementation(libs.gson)
}
