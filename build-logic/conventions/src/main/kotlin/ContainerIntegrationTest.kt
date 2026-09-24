import com.sun.security.auth.module.UnixSystem
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import javax.inject.Inject

/**
 * The Corretto 25 images the native bundle Dockerfiles pin (jonoffcpu-agent/tools/Dockerfile.native-bundle and
 * Dockerfile.native-bundle-musl); change them together. The digests name multi-architecture indexes, so Docker picks
 * the host's own architecture.
 */
object ContainerImages {
    const val GLIBC = "amazoncorretto:25@sha256:ec395950366b60da545925171be5e8f457f7b33d034ee12b3a0110657da285fc"
    const val MUSL = "amazoncorretto:25-alpine@sha256:4955796538972099d9c7de6e31c6a259b1de65393a58b7e0996b7cc50d7d20a7"

    fun forLibc(libc: String): String =
        when (libc) {
            "glibc" -> GLIBC
            "musl" -> MUSL
            else -> throw GradleException("Unsupported C library for integration tests: $libc")
        }
}

/**
 * Runs JUnit tests inside a Linux container with the JUnit Platform Console Launcher, so that tests which need Linux
 * and a native bundle run on any host with Docker, and a glibc host can test the musl bundle.
 *
 * Every JAR of the tests' classpath, the launcher's included, is copied flat into one directory that a classpath
 * wildcard names; class directories stay where they are. The project directory is mounted at its own path, so every
 * host path, those in [systemProperties] included, means the same inside the container, and the launcher's arguments
 * go in an argument file there. The container runs at the host's own architecture, never under emulation, without a
 * network, and as the invoking user, so the JUnit XML reports it writes to [reportsDirectory] stay the user's.
 */
@DisableCachingByDefault(because = "Runs tests and produces only reports")
abstract class ContainerIntegrationTest
    @Inject
    constructor(
        private val execOperations: ExecOperations,
    ) : DefaultTask() {
        /** The tests' full runtime classpath, the directories to scan included. */
        @get:Classpath
        abstract val testClasspath: ConfigurableFileCollection

        /** The classpath roots the launcher scans for tests; each must also be on [testClasspath]. */
        @get:Classpath
        abstract val testClassesDirs: ConfigurableFileCollection

        /** junit-platform-console and its dependencies. */
        @get:Classpath
        abstract val consoleLauncher: ConfigurableFileCollection

        @get:Input
        abstract val image: Property<String>

        @get:Input
        abstract val includeTags: ListProperty<String>

        @get:Input
        abstract val excludeTags: ListProperty<String>

        /** System properties for the test JVM. */
        @get:Input
        abstract val systemProperties: MapProperty<String, String>

        /** The directory mounted at its own path: everything the tests read or write lives under it. */
        @get:Internal
        abstract val projectDirectory: DirectoryProperty

        @get:OutputDirectory
        abstract val reportsDirectory: DirectoryProperty

        init {
            group = "verification"
        }

        @TaskAction
        fun run() {
            val root = projectDirectory.get().asFile
            val reports = reportsDirectory.get().asFile
            reports.deleteRecursively()
            reports.mkdirs()
            val scanned = testClassesDirs.files.filter { it.exists() }
            if (scanned.isEmpty()) throw GradleException("No test classes to run in the container")
            val lib = temporaryDir.resolve("lib")
            lib.deleteRecursively()
            lib.mkdirs()
            val classDirectories = mutableListOf<String>()
            for (file in (consoleLauncher.files + testClasspath.files).distinct()) {
                if (file.isDirectory) {
                    if (!file.startsWith(root)) throw GradleException("Class directory $file is outside $root")
                    classDirectories += file.absolutePath
                } else if (file.isFile) {
                    val name = if (lib.resolve(file.name).exists()) "${lib.list()!!.size}-${file.name}" else file.name
                    file.copyTo(lib.resolve(name))
                }
            }
            // Class directories first, as Gradle orders them: a JAR in the directory, such as a shaded copy of the
            // module's own classes, must not shadow them.
            val arguments =
                systemProperties.get().map { (key, value) -> "-D$key=$value" } +
                    listOf("-cp", (classDirectories + "${lib.absolutePath}/*").joinToString(":")) +
                    listOf("org.junit.platform.console.ConsoleLauncher", "execute", "--disable-banner", "--details=tree") +
                    listOf("--fail-if-no-tests", "--reports-dir", reports.absolutePath) +
                    listOf("--config", "junit.jupiter.execution.timeout.default=60 s") +
                    scanned.flatMap { listOf("--scan-class-path", it.absolutePath) } +
                    includeTags.get().flatMap { listOf("--include-tag", it) } +
                    excludeTags.get().flatMap { listOf("--exclude-tag", it) }
            val argumentFile = temporaryDir.resolve("java.args")
            argumentFile.writeText(arguments.joinToString("\n") { quote(it) } + "\n")
            val unix = UnixSystem()
            val command =
                listOf("docker", "run", "--rm", "--network", "none", "--user", "${unix.uid}:${unix.gid}") +
                    listOf("--env", "HOME=/tmp", "--volume", "${root.absolutePath}:${root.absolutePath}") +
                    listOf("--workdir", root.absolutePath, image.get(), "java", "@${argumentFile.absolutePath}")
            logger.lifecycle("Running tests in ${image.get().substringBefore('@')}")
            val result =
                execOperations.exec {
                    commandLine(command)
                    isIgnoreExitValue = true
                }
            if (result.exitValue != 0) {
                throw GradleException("Container tests failed (exit ${result.exitValue}); JUnit XML reports are in $reports")
            }
        }

        /** One argument of a `java` argument file, quoted so that spaces and backslashes survive. */
        private fun quote(argument: String): String = "\"" + argument.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
