import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.security.MessageDigest
import java.util.HexFormat

/** Checks every artifact against the SHA-256 pinned for its file name, before the artifacts are embedded. */
@DisableCachingByDefault(because = "A verification without outputs")
abstract class VerifyDependencyDigests : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val artifacts: ConfigurableFileCollection

    /** Artifact file name to its expected SHA-256, in lowercase hex. */
    @get:Input
    abstract val digests: MapProperty<String, String>

    @TaskAction
    fun verify() {
        val expected = digests.get()
        artifacts.files.forEach { artifact ->
            val digest = expected[artifact.name] ?: throw GradleException("No pinned digest for ${artifact.name}")
            val actual = sha256(artifact)
            if (actual != digest) throw GradleException("SHA-256 mismatch for ${artifact.name}: $actual")
        }
    }
}

fun sha256(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { stream ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return HexFormat.of().formatHex(digest.digest())
}
