import org.gradle.api.provider.Property

/** What a module's Java convention varies: the bytecode level its classes target. */
abstract class JonoffcpuJavaExtension {
    /** The `--release` the module compiles for; its published variants declare the same JVM version. */
    abstract val javaRelease: Property<Int>
}

/** What a module's published POM says about it; the rest is the same for every jonoffcpu artifact. */
abstract class JonoffcpuPublicationExtension {
    abstract val displayName: Property<String>

    abstract val description: Property<String>

    abstract val licenseName: Property<String>

    abstract val licenseUrl: Property<String>
}
