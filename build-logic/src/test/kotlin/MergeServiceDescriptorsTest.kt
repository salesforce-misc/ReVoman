import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.longs.shouldBeExactly
import io.kotest.matchers.paths.shouldNotExist
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.gradle.testfixtures.ProjectBuilder

class MergeServiceDescriptorsTest :
    StringSpec({
        "rejects a traversal service descriptor without writing outside its output" {
            withTemporaryDirectory("merge-services-traversal-") { root ->
                val escapedTarget = root.resolve("escaped-target")
                val maliciousJar = root.resolve("malicious.jar")
                val output = root.resolve("output/nested")
                writeJar(
                    maliciousJar,
                    mapOf("$SERVICES_PATH../../escaped-target" to "example.Provider\n"),
                )

                val failure = shouldThrow<IllegalArgumentException> {
                    mergeServices(root.resolve("project"), listOf(maliciousJar), output)
                }
                failure.message shouldContain "Invalid Java service descriptor name"

                escapedTarget.shouldNotExist()
                output.shouldContainNoRegularFiles()
            }
        }

        "rejects an absolute service descriptor without writing to its target" {
            withTemporaryDirectory("merge-services-absolute-") { root ->
                val escapedTarget = root.resolve("absolute-target").toAbsolutePath()
                val maliciousJar = root.resolve("malicious.jar")
                val output = root.resolve("output")
                writeJar(
                    maliciousJar,
                    mapOf("$SERVICES_PATH$escapedTarget" to "example.Provider\n"),
                )

                val failure = shouldThrow<IllegalArgumentException> {
                    mergeServices(root.resolve("project"), listOf(maliciousJar), output)
                }
                failure.message shouldContain "Invalid Java service descriptor name"

                escapedTarget.shouldNotExist()
                output.shouldContainNoRegularFiles()
            }
        }

        "preserves declared classpath precedence independently of dependency locations" {
            withTemporaryDirectory("merge-services-order-") { root ->
                val firstLayoutFirstJar =
                    writeServiceJar(root.resolve("layout-one/z/first.jar"), "example.FirstProvider")
                val firstLayoutSecondJar =
                    writeServiceJar(root.resolve("layout-one/a/second.jar"), "example.SecondProvider")
                val secondLayoutFirstJar =
                    writeServiceJar(root.resolve("layout-two/a/first.jar"), "example.FirstProvider")
                val secondLayoutSecondJar =
                    writeServiceJar(root.resolve("layout-two/z/second.jar"), "example.SecondProvider")

                val firstOutput =
                    mergeServices(
                            root.resolve("project-one"),
                            listOf(firstLayoutFirstJar, firstLayoutSecondJar),
                            root.resolve("output-one"),
                        )
                        .resolve(SERVICE_NAME)
                val secondOutput =
                    mergeServices(
                            root.resolve("project-two"),
                            listOf(secondLayoutFirstJar, secondLayoutSecondJar),
                            root.resolve("output-two"),
                        )
                        .resolve(SERVICE_NAME)

                Files.mismatch(firstOutput, secondOutput).shouldBeExactly(-1L)
                Files.readString(firstOutput, StandardCharsets.UTF_8) shouldBe
                    "example.FirstProvider\nexample.SecondProvider\n"
            }
        }
    })

private fun mergeServices(
    projectDirectory: Path,
    classpath: List<Path>,
    outputDirectory: Path,
): Path {
    Files.createDirectories(projectDirectory)
    val project = ProjectBuilder.builder().withProjectDir(projectDirectory.toFile()).build()
    val task = project.tasks.register("mergeServices", MergeServiceDescriptors::class.java).get()
    task.classpath.from(classpath.map(Path::toFile))
    task.outputDirectory.set(outputDirectory.toFile())
    task.merge()
    return outputDirectory
}

private fun writeServiceJar(path: Path, provider: String): Path =
    writeJar(path, mapOf("$SERVICES_PATH$SERVICE_NAME" to "$provider\n"))

private fun writeJar(path: Path, entries: Map<String, String>): Path =
    path.also {
        Files.createDirectories(path.parent)
        ZipOutputStream(Files.newOutputStream(path)).use { zip ->
            entries.forEach { (name, contents) ->
                zip.putNextEntry(ZipEntry(name).apply { time = 0L })
                zip.write(contents.toByteArray(StandardCharsets.UTF_8))
                zip.closeEntry()
            }
        }
    }

private fun Path.shouldContainNoRegularFiles() {
    if (!Files.exists(this)) return
    Files.walk(this).use { paths -> paths.noneMatch(Files::isRegularFile) shouldBe true }
}

private inline fun withTemporaryDirectory(prefix: String, block: (Path) -> Unit) {
    val root = Files.createTempDirectory(prefix)
    try {
        block(root)
    } finally {
        root.toFile().deleteRecursively()
    }
}

private const val SERVICES_PATH = "META-INF/services/"
private const val SERVICE_NAME = "com.example.Service"
