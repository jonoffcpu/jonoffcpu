import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.util.zip.ZipFile

/** Checks a published JAR: entries it must contain, such as licenses and relocated classes, and prefixes it must not. */
@DisableCachingByDefault(because = "A verification without outputs")
abstract class VerifyJarContents : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val jar: RegularFileProperty

    /** What the JAR is called in failure messages. */
    @get:Input
    abstract val label: Property<String>

    @get:Input
    abstract val requiredEntries: ListProperty<String>

    /** Entry name prefixes that must not occur, such as unrelocated dependency packages. */
    @get:Input
    abstract val forbiddenPrefixes: ListProperty<String>

    init {
        group = "verification"
        forbiddenPrefixes.convention(emptyList())
    }

    @TaskAction
    fun verify() {
        ZipFile(jar.get().asFile).use { zip ->
            requiredEntries.get().forEach { name ->
                if (zip.getEntry(name) == null) throw GradleException("${label.get()} is missing $name")
            }
            val forbidden = forbiddenPrefixes.get()
            zip.entries().asSequence().firstOrNull { entry -> forbidden.any { entry.name.startsWith(it) } }?.let {
                throw GradleException("${label.get()} must not contain ${it.name}")
            }
            verifyMore(zip)
        }
    }

    /** Further checks of a subclass, with the JAR open. */
    protected open fun verifyMore(zip: ZipFile) {}
}
