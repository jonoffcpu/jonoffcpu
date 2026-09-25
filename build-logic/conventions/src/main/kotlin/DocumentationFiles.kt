import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.IgnoreEmptyDirectories
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.process.CommandLineArgumentProvider
import java.io.File

/**
 * The Markdown files a documentation test reads, passed to the test JVM as `jonoffcpu.docs.root`, the directory they
 * are relative to, and `jonoffcpu.docs`, their relative paths. Only the files' contents and their paths relative to
 * [root] are inputs, so a result is reused from a checkout anywhere.
 */
abstract class DocumentationFiles : CommandLineArgumentProvider {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:IgnoreEmptyDirectories
    abstract val files: ConfigurableFileCollection

    @get:Internal
    abstract val root: DirectoryProperty

    override fun asArguments(): Iterable<String> {
        val base = root.get().asFile
        val relative = files.files.map { it.relativeTo(base).invariantSeparatorsPath }.sorted()
        return listOf(
            "-Djonoffcpu.docs.root=${base.absolutePath}",
            "-Djonoffcpu.docs=${relative.joinToString(File.pathSeparator)}",
        )
    }
}
