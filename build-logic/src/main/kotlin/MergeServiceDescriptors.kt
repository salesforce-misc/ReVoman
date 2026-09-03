import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import javax.lang.model.SourceVersion
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

abstract class MergeServiceDescriptors : DefaultTask() {
    @get:Classpath abstract val classpath: ConfigurableFileCollection

    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @get:Inject protected abstract val fileSystemOperations: FileSystemOperations

    @TaskAction
    fun merge() {
        fileSystemOperations.delete { delete(outputDirectory) }
        val outputRoot = outputDirectory.get().asFile.toPath().toAbsolutePath().normalize()
        val providersByService = sortedMapOf<String, LinkedHashSet<String>>()

        classpath.forEach { entry ->
            when {
                entry.isDirectory -> mergeDirectory(entry.toPath(), providersByService)
                entry.extension.equals("jar", ignoreCase = true) ->
                    mergeJar(entry.toPath(), providersByService)
            }
        }

        val outputs =
            providersByService.map { (service, providers) ->
                val output = outputRoot.resolve(service).normalize()
                require(output.startsWith(outputRoot) && output.parent == outputRoot) {
                    "Service descriptor escapes its output directory: $service"
                }
                output to providers
            }
        Files.createDirectories(outputRoot)
        outputs.forEach { (output, providers) ->
            Files.createDirectories(output.parent)
            Files.writeString(
                output,
                providers.joinToString(separator = "\n", postfix = "\n"),
                StandardCharsets.UTF_8,
            )
        }
    }
}

private fun mergeDirectory(
    root: Path,
    providersByService: MutableMap<String, LinkedHashSet<String>>,
) {
    val servicesRoot = root.resolve(SERVICES_PATH)
    if (!Files.isDirectory(servicesRoot)) return

    Files.walk(servicesRoot).use { paths ->
        paths
            .filter { Files.isRegularFile(it) }
            .sorted()
            .forEach { descriptor ->
                mergeProviders(
                    servicesRoot.relativize(descriptor).toString(),
                    Files.readString(descriptor, StandardCharsets.UTF_8),
                    providersByService,
                )
            }
    }
}

private fun mergeJar(
    path: Path,
    providersByService: MutableMap<String, LinkedHashSet<String>>,
) {
    ZipFile(path.toFile()).use { archive ->
        archive
            .entries()
            .asSequence()
            .filter { entry -> !entry.isDirectory && entry.name.startsWith(SERVICES_PATH) }
            .sortedBy { it.name }
            .forEach { entry ->
                val contents =
                    archive
                        .getInputStream(entry)
                        .bufferedReader(StandardCharsets.UTF_8)
                        .use { it.readText() }
                mergeProviders(entry.name.removePrefix(SERVICES_PATH), contents, providersByService)
            }
    }
}

private fun mergeProviders(
    service: String,
    contents: String,
    providersByService: MutableMap<String, LinkedHashSet<String>>,
) {
    require(SourceVersion.isName(service)) { "Invalid Java service descriptor name: $service" }
    val providers = providersByService.getOrPut(service, ::linkedSetOf)
    contents
        .lineSequence()
        .map { line -> line.substringBefore('#').trim() }
        .filter(String::isNotEmpty)
        .forEach(providers::add)
}

private const val SERVICES_PATH = "META-INF/services/"
