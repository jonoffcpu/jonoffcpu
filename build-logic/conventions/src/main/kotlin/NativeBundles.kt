import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat
import java.util.zip.ZipFile

/** One embedded native bundle: a Linux architecture linked against one C library. */
data class NativePlatform(
    val name: String,
    val architecture: String,
    val libc: String,
    val elfMachine: Int,
    val dockerPlatform: String,
    val dockerfile: String,
    val taskSuffix: String,
) {
    companion object {
        val ALL: Map<String, NativePlatform> =
            listOf(
                NativePlatform("linux-x86_64", "x86_64", "glibc", 62, "linux/amd64", "Dockerfile.native-bundle", "LinuxX86_64"),
                NativePlatform("linux-aarch64", "aarch64", "glibc", 183, "linux/arm64", "Dockerfile.native-bundle", "LinuxAarch64"),
                NativePlatform(
                    "linux-musl-x86_64",
                    "x86_64",
                    "musl",
                    62,
                    "linux/amd64",
                    "Dockerfile.native-bundle-musl",
                    "LinuxMuslX86_64",
                ),
                NativePlatform(
                    "linux-musl-aarch64",
                    "aarch64",
                    "musl",
                    183,
                    "linux/arm64",
                    "Dockerfile.native-bundle-musl",
                    "LinuxMuslAarch64",
                ),
            ).associateBy { it.name }

        /** The three libraries of every bundle. */
        val FILE_NAMES = listOf("libjonoffcpu.so", "libjonoffcpu_native.so", "libasyncProfiler.so")

        fun architecture(osArch: String): String =
            when (osArch.lowercase()) {
                "amd64", "x86_64" -> "x86_64"
                "aarch64", "arm64" -> "aarch64"
                else -> throw GradleException("Unsupported build host architecture: $osArch")
            }

        fun hostPlatform(
            architecture: String,
            libc: String,
        ): String = if (libc == "musl") "linux-musl-$architecture" else "linux-$architecture"

        /** The platforms that -PnativeArchitectures and -PnativeLibcs select. */
        fun select(
            architectures: String,
            libcs: String,
            hostArchitecture: String,
            hostLibc: String,
        ): List<String> {
            val selectedArchitectures =
                when (architectures.trim().lowercase()) {
                    "current" -> setOf(hostArchitecture)

                    "all", "both" -> setOf("x86_64", "aarch64")

                    "x86_64", "amd64", "linux-x86_64" -> setOf("x86_64")

                    "aarch64", "arm64", "linux-aarch64" -> setOf("aarch64")

                    else -> throw GradleException(
                        "Unsupported nativeArchitectures value '$architectures'; expected current, all, x86_64, or aarch64",
                    )
                }
            val selectedLibcs =
                when (libcs.trim().lowercase()) {
                    "all", "both" -> setOf("glibc", "musl")

                    "glibc", "gnu" -> setOf("glibc")

                    "musl" -> setOf("musl")

                    "current", "host" -> setOf(hostLibc)

                    else -> throw GradleException(
                        "Unsupported nativeLibcs value '$libcs'; expected musl, glibc, all, or current",
                    )
                }
            return ALL.values.filter { it.architecture in selectedArchitectures && it.libc in selectedLibcs }.map { it.name }
        }
    }
}

/**
 * The C library of the JVM running the build, from its own mapped loader, as the agent detects it. A value source,
 * so that the configuration cache compares the result rather than the ever-changing /proc/self/maps.
 */
abstract class HostLibcValueSource : ValueSource<String, ValueSourceParameters.None> {
    override fun obtain(): String {
        val maps = File("/proc/self/maps")
        if (!maps.isFile) return "glibc"
        val musl =
            maps
                .readLines()
                .map { line -> line.substringAfter(" /", "").substringAfterLast('/') }
                .any { name -> name.startsWith("ld-musl-") || name.startsWith("libc.musl-") }
        return if (musl) "musl" else "glibc"
    }
}

/** An ELF file's e_machine, rejecting anything but a little-endian ELF64 file. */
fun elfMachine(bytes: ByteArray): Int {
    if (bytes.size < 20 || bytes[0] != 0x7f.toByte() || bytes[1] != 'E'.code.toByte() ||
        bytes[2] != 'L'.code.toByte() || bytes[3] != 'F'.code.toByte()
    ) {
        throw GradleException("Native artifact is not an ELF file")
    }
    if (bytes[4] != 2.toByte() || bytes[5] != 1.toByte()) {
        throw GradleException("Native artifact must be a little-endian ELF64 file")
    }
    return bytes[18].toUByte().toInt() or (bytes[19].toUByte().toInt() shl 8)
}

/**
 * Checks that a native library is linked against the expected C library. glibc-linked libraries carry GLIBC_2.x
 * symbol version needs; musl-linked libraries carry none.
 */
fun verifyLibc(
    bytes: ByteArray,
    expectedLibc: String,
    label: String,
) {
    val glibcVersioned = String(bytes, StandardCharsets.ISO_8859_1).contains("GLIBC_2.")
    if (expectedLibc == "glibc" && !glibcVersioned) {
        throw GradleException("$label is labeled glibc but carries no glibc symbol versions")
    }
    if (expectedLibc == "musl" && glibcVersioned) {
        throw GradleException("$label is labeled musl but carries glibc symbol versions")
    }
}

/** Rejects missing, malformed, or mislabeled native libraries for the selected platforms. */
@DisableCachingByDefault(because = "A verification without outputs")
abstract class VerifyNativeLibraries : DefaultTask() {
    /** Where the platform directories are; their libraries are the inputs. */
    @get:Internal
    abstract val nativeRoot: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val libraries: ConfigurableFileCollection

    @get:Input
    abstract val platforms: ListProperty<String>

    @TaskAction
    fun verify() {
        platforms.get().forEach { platform ->
            val spec = NativePlatform.ALL.getValue(platform)
            NativePlatform.FILE_NAMES.forEach { name ->
                val artifact =
                    nativeRoot
                        .get()
                        .dir(platform)
                        .file(name)
                        .asFile
                if (!artifact.isFile) throw GradleException("Missing $platform/$name")
                val bytes = artifact.readBytes()
                val actual = elfMachine(bytes)
                if (actual != spec.elfMachine) {
                    throw GradleException("$platform/$name has ELF e_machine $actual, expected ${spec.elfMachine}")
                }
                verifyLibc(bytes, spec.libc, "$platform/$name")
            }
        }
    }
}

/** Writes the SHA256SUMS manifest the agent checks its extracted native libraries against. */
@DisableCachingByDefault(because = "Hashing a few files is faster than a cache round trip")
abstract class GenerateNativeChecksums : DefaultTask() {
    /** Where the platform directories are; their libraries are the inputs. */
    @get:Internal
    abstract val nativeRoot: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val libraries: ConfigurableFileCollection

    @get:Input
    abstract val platforms: ListProperty<String>

    @get:OutputFile
    abstract val checksums: RegularFileProperty

    @TaskAction
    fun generate() {
        val lines =
            platforms.get().sorted().flatMap { platform ->
                NativePlatform.FILE_NAMES.sorted().map { name ->
                    "${sha256(
                        nativeRoot
                            .get()
                            .dir(platform)
                            .file(name)
                            .asFile,
                    )}  $platform/$name"
                }
            }
        checksums.get().asFile.writeText(lines.joinToString("\n", postfix = "\n"), StandardCharsets.UTF_8)
    }
}

/**
 * Checks the agent JAR: besides its entries, that every selected platform's libraries are embedded with the right
 * architecture and C library, and that the checksum manifest records exactly them with their digests.
 */
@DisableCachingByDefault(because = "A verification without outputs")
abstract class VerifyAgentJar : VerifyJarContents() {
    @get:Input
    abstract val platforms: ListProperty<String>

    override fun verifyMore(zip: ZipFile) {
        val checksumEntry =
            zip.getEntry("META-INF/native/SHA256SUMS") ?: throw GradleException("Runtime JAR has no native checksum manifest")
        val recorded = mutableMapOf<String, String>()
        val line = Regex("^([0-9a-f]{64})  (linux-(?:musl-)?(?:x86_64|aarch64)/[^/]+)$")
        zip.getInputStream(checksumEntry).bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
            lines.filter { it.isNotBlank() }.forEach { text ->
                val match = line.matchEntire(text) ?: throw GradleException("Malformed native checksum line: $text")
                recorded[match.groupValues[2]] = match.groupValues[1]
            }
        }
        val selected = platforms.get()
        selected.forEach { platform ->
            val spec = NativePlatform.ALL.getValue(platform)
            NativePlatform.FILE_NAMES.forEach { name ->
                val relative = "$platform/$name"
                val entry = zip.getEntry("META-INF/native/$relative") ?: throw GradleException("Runtime JAR is missing $relative")
                val bytes = zip.getInputStream(entry).use { it.readAllBytes() }
                if (elfMachine(bytes) != spec.elfMachine) {
                    throw GradleException("Runtime JAR contains the wrong architecture at $relative")
                }
                verifyLibc(bytes, spec.libc, "Runtime JAR entry $relative")
                if (recorded[relative] != sha256(bytes)) throw GradleException("Runtime JAR checksum mismatch for $relative")
            }
        }
        val expected = selected.flatMap { platform -> NativePlatform.FILE_NAMES.map { "$platform/$it" } }.toSet()
        if (recorded.keys != expected) throw GradleException("Native checksum manifest has missing or unexpected entries")
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
