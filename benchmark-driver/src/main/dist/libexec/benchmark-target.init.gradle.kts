import groovy.json.JsonOutput
import java.lang.management.ManagementFactory
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.HexFormat
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.jvm.tasks.Jar
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult

abstract class WriteBenchmarkTargetManifest : DefaultTask() {
    @get:Input abstract val targetId: Property<String>

    @get:Input abstract val gradleVersion: Property<String>

    @get:Internal abstract val repositoryRoot: DirectoryProperty

    @get:InputFile abstract val targetJar: RegularFileProperty

    @get:Input abstract val targetLogicalId: Property<String>

    @get:Classpath abstract val runtimeClasspath: ConfigurableFileCollection

    @get:Input abstract val runtimeLogicalIds: ListProperty<String>

    @get:InputFile abstract val wrapperProperties: RegularFileProperty

    @get:OutputFile abstract val manifestFile: RegularFileProperty

    @TaskAction
    fun writeManifest() {
        val id = targetId.get()
        if (id.isBlank()) throw GradleException("benchmark.targetId must not be blank")
        val repository = repositoryRoot.get().asFile.toPath().toRealPath()
        val output = manifestFile.get().asFile.toPath().toAbsolutePath().normalize()
        Files.createDirectories(
            output.parent
                ?: throw GradleException("benchmark.targetManifest needs a parent: $output")
        )

        val target = targetJar.get().asFile.toPath().toRealPath()
        val dependencies = runtimeClasspath.files.map { file -> file.toPath().toRealPath() }
        val dependencyLogicalIds = runtimeLogicalIds.get()
        if (dependencies.size != dependencyLogicalIds.size) {
            throw GradleException(
                "Resolved runtime artifact files and logical IDs differ in count: " +
                    "files=${dependencies.size}, ids=${dependencyLogicalIds.size}"
            )
        }
        val classpath = listOf(target) + dependencies
        if (classpath.distinct().size != classpath.size) {
            throw GradleException("Target runtime classpath contains duplicate files")
        }
        classpath.forEach { path ->
            if (!Files.isRegularFile(path) || !path.fileName.toString().endsWith(".jar")) {
                throw GradleException("Target runtime classpath entry is not a JAR file: $path")
            }
            if (path.fileName.toString().endsWith("-jmh.jar")) {
                throw GradleException("Target runtime classpath contains a JMH uber-JAR: $path")
            }
        }

        val wrapper = wrapperProperties.get().asFile.toPath().toRealPath()
        val logicalIds = listOf(targetLogicalId.get()) + dependencyLogicalIds
        if (logicalIds.any(String::isBlank) || logicalIds.distinct().size != logicalIds.size) {
            throw GradleException("Target runtime classpath contains duplicate or blank logical IDs: $logicalIds")
        }
        val artifacts =
            classpath.zip(logicalIds).map { (path, logicalId) ->
                linkedMapOf(
                    "logicalId" to logicalId,
                    "executionPath" to path.toString(),
                    "sizeBytes" to Files.size(path),
                    "sha256" to sha256(path),
                )
            }
        val manifest =
            linkedMapOf(
                "schema" to "revoman-target-manifest/v1",
                "targetId" to id,
                "gitCommit" to git(repository, "rev-parse", "HEAD"),
                "gitTree" to git(repository, "rev-parse", "HEAD^{tree}"),
                "dirty" to
                    git(repository, "status", "--porcelain", "--untracked-files=normal")
                        .isNotBlank(),
                "gradleVersion" to gradleVersion.get(),
                "wrapperSha256" to sha256(wrapper),
                "jdk" to
                    linkedMapOf(
                        "distribution" to System.getProperty("java.runtime.name"),
                        "vendor" to System.getProperty("java.vendor"),
                        "fullVersion" to System.getProperty("java.runtime.version"),
                        "javaHome" to
                            Path.of(System.getProperty("java.home"))
                                .toAbsolutePath()
                                .normalize()
                                .toString(),
                        "jvmFlags" to ManagementFactory.getRuntimeMXBean().inputArguments,
                    ),
                "classpath" to artifacts,
            )
        val encoded = JsonOutput.prettyPrint(JsonOutput.toJson(manifest)) + "\n"
        val temporary = Files.createTempFile(output.parent, ".${output.fileName}.", ".tmp")
        try {
            Files.writeString(temporary, encoded, UTF_8)
            Files.move(temporary, output, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun git(root: Path, vararg arguments: String): String {
        val process =
            ProcessBuilder(listOf("git", "-C", root.toString()) + arguments)
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (process.waitFor() != 0) {
            throw GradleException("Git target-identity command failed: $output")
        }
        return output.trim()
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return HexFormat.of().formatHex(digest.digest())
    }
}

gradle.projectsEvaluated {
    val root = gradle.rootProject
    if (root.tasks.findByName("writeBenchmarkTargetManifest") == null) {
        val id =
            root.providers.gradleProperty("benchmark.targetId").orNull
                ?: throw GradleException("benchmark.targetId is required")
        val output =
            root.providers.gradleProperty("benchmark.targetManifest").orNull
                ?: throw GradleException("benchmark.targetManifest is required")
        val jarTask = root.tasks.named("jar", Jar::class.java)
        val runtimeConfiguration = root.configurations.named("runtimeClasspath")
        val resolvedArtifacts =
            root.providers.provider {
                runtimeConfiguration.get().incoming.artifacts.artifacts.toList()
            }
        root.tasks.register<WriteBenchmarkTargetManifest>("writeBenchmarkTargetManifest") {
            group = "benchmark"
            description = "Exports the normal target JAR and ordered original runtime JARs"
            dependsOn(jarTask)
            targetId.set(id)
            gradleVersion.set(gradle.gradleVersion)
            repositoryRoot.set(root.layout.projectDirectory)
            targetJar.set(jarTask.flatMap(Jar::getArchiveFile))
            targetLogicalId.set("project:${root.name}:jar")
            runtimeClasspath.from(resolvedArtifacts.map { artifacts -> artifacts.map { it.file } })
            runtimeLogicalIds.set(
                resolvedArtifacts.map { artifacts -> artifacts.map(::stableLogicalId) }
            )
            wrapperProperties.set(
                root.layout.projectDirectory.file("gradle/wrapper/gradle-wrapper.properties")
            )
            manifestFile.set(root.file(output))
            outputs.upToDateWhen { false }
        }
    }
}

fun stableLogicalId(artifact: ResolvedArtifactResult): String =
    when (val component = artifact.id.componentIdentifier) {
        is ModuleComponentIdentifier ->
            "maven:${component.group}:${component.module}:${component.version}"
        is ProjectComponentIdentifier ->
            "project:${component.build.buildPath}:${component.projectPath}"
        else ->
            throw GradleException(
                "Target runtime classpath artifact has no stable Maven/project component identity: " +
                    "${artifact.id.displayName} (${component.javaClass.name})"
            )
    }
