import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Runs one main-based test fixture with assertions enabled. The fixtures are not JUnit tests, so Gradle's test
 * logging does not apply: the task reports the fixture class as it starts and how long it took, and the fixture
 * reports each of its scenarios itself (FixtureSteps). `check` depends on every fixture of its project.
 */
@DisableCachingByDefault(because = "A fixture checks behaviour and produces no outputs")
abstract class FixtureExec : JavaExec() {
    init {
        group = "verification"
        jvmArgs("-ea")
    }

    @TaskAction
    override fun exec() {
        val fixture = mainClass.get()
        logger.lifecycle("$fixture STARTED")
        val started = System.nanoTime()
        super.exec()
        logger.lifecycle("$fixture PASSED (${(System.nanoTime() - started) / 1_000_000 / 1000.0} s)")
    }
}
