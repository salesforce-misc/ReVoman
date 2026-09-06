import java.io.File
import java.io.Serializable
import org.gradle.api.file.FileTreeElement
import org.gradle.api.specs.Spec

internal class UnmergedServiceDescriptorSpec(mergedServicesDirectory: String) :
    Spec<FileTreeElement>, Serializable {
    private val mergedServicesPrefix = "$mergedServicesDirectory${File.separator}"

    override fun isSatisfiedBy(element: FileTreeElement): Boolean =
        element.path.startsWith(SERVICES_PATH) &&
            !element.file.absolutePath.startsWith(mergedServicesPrefix)
}

private const val SERVICES_PATH = "META-INF/services/"
