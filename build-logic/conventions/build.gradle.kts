// The convention plugins (src/main/kotlin/*.gradle.kts) and the task types they and the module build scripts use.
// The third-party plugins they apply are dependencies here, so every module loads them from one class loader.
plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.maven.publish.plugin)
    implementation(libs.protobuf.plugin)
    implementation(libs.shadow.plugin)
}
