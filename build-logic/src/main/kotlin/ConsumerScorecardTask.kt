import org.gradle.api.GradleException
import org.gradle.api.provider.Provider
import org.gradle.api.provider.Property
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.concurrent.ParallelismConfiguration
import org.gradle.process.CommandLineArgumentProvider
import javax.inject.Inject

abstract class ConsumerScorecardTask : JavaExec() {
    @get:Inject
    protected abstract val parallelismConfiguration: ParallelismConfiguration

    @get:Input
    val gradleMaxWorkers: Int
        get() = parallelismConfiguration.maxWorkerCount

    @get:Input
    @get:Optional
    abstract val runtimeValidation: Property<String>

    @TaskAction
    override fun exec() {
        val launcherFeature = javaLauncher.get().metadata.languageVersion.asInt()
        if (launcherFeature != REQUIRED_JAVA_FEATURE) {
            throw GradleException(
                "Scorecard Java launcher feature $REQUIRED_JAVA_FEATURE is required; found $launcherFeature"
            )
        }

        val daemonFeature = Runtime.version().feature()
        if (daemonFeature != REQUIRED_JAVA_FEATURE) {
            throw GradleException(
                "Gradle daemon Java feature $REQUIRED_JAVA_FEATURE is required; found $daemonFeature"
            )
        }

        if (!runtimeValidation.isPresent) {
            throw GradleException(
                "-PscorecardRuntimeValidation=<path> is required to run runConsumerScorecard"
            )
        }

        args("--gradle-max-workers", gradleMaxWorkers.toString())
        super.exec()
    }

    private companion object {
        const val REQUIRED_JAVA_FEATURE = 25
    }
}

class ConsumerScorecardArguments(
    @get:Input val projectRoot: Provider<String>,
    @get:Input val benchmarkJar: Provider<String>,
    @get:Input val javaExecutable: Provider<String>,
    @get:Input val javaFeature: Provider<Int>,
    @get:Input val daemonJavaFeature: Provider<Int>,
    @get:Input val daemonRuntimeVersion: Provider<String>,
    @get:Input val daemonVendor: Provider<String>,
    @get:Input val daemonVmName: Provider<String>,
    @get:Input val libraryVersion: Provider<String>,
    @get:Input @get:Optional val runtimeValidation: Provider<String>,
    @get:Input val allowedDirtyPaths: Provider<List<String>>,
) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> =
        listOf(
            "--project-root",
            projectRoot.get(),
            "--benchmark-jar",
            benchmarkJar.get(),
            "--java-executable",
            javaExecutable.get(),
            "--java-feature",
            javaFeature.get().toString(),
            "--gradle-daemon-java-feature",
            daemonJavaFeature.get().toString(),
            "--gradle-daemon-runtime-version",
            daemonRuntimeVersion.get(),
            "--gradle-daemon-vendor",
            daemonVendor.get(),
            "--gradle-daemon-vm-name",
            daemonVmName.get(),
            "--library-version",
            libraryVersion.get(),
            "--runtime-validation",
            runtimeValidation.get(),
        ) +
            allowedDirtyPaths.get().flatMap { path ->
                listOf("--allowed-dirty-path", path)
            }
}
