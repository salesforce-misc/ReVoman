import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage

internal const val CONSUMER_SCORECARD_EXECUTABLE = "consumerScorecardExecutable"

internal fun Project.createConsumerScorecardExecutable(
    canBeConsumed: Boolean,
    canBeResolved: Boolean,
): Configuration =
    configurations.create(CONSUMER_SCORECARD_EXECUTABLE) {
        isCanBeConsumed = canBeConsumed
        isCanBeResolved = canBeResolved
        attributes {
            attribute(
                Category.CATEGORY_ATTRIBUTE,
                objects.named(Category::class.java, Category.LIBRARY),
            )
            attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
            attribute(
                LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
                objects.named(LibraryElements::class.java, LibraryElements.JAR),
            )
            attribute(
                Bundling.BUNDLING_ATTRIBUTE,
                objects.named(Bundling::class.java, Bundling.SHADOWED),
            )
        }
    }
