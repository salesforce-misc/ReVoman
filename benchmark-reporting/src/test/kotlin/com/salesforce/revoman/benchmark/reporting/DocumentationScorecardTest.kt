package com.salesforce.revoman.benchmark.reporting

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import kotlin.io.path.readBytes
import kotlin.io.path.readText

class DocumentationScorecardTest :
  StringSpec({
    "published performance scorecard is byte-identical to accepted evidence" {
      val projectRoot =
        Path.of(
          requireNotNull(System.getProperty(PROJECT_ROOT_PROPERTY)) {
            "$PROJECT_ROOT_PROPERTY must identify the repository root"
          }
        )
      val partial = projectRoot.resolve(DOCUMENTATION_PARTIAL)
      val partialText = partial.readText()
      val accepted =
        projectRoot
          .resolve("benchmark-results")
          .resolve(partialText.attribute("scorecard-study-id"))
          .resolve(partialText.attribute("scorecard-run-id"))
          .resolve("performance-scorecard.adoc")

      partial.readBytes().contentEquals(accepted.readBytes()) shouldBe true
    }

    "performance page includes the generated scorecard exactly once" {
      val projectRoot =
        Path.of(
          requireNotNull(System.getProperty(PROJECT_ROOT_PROPERTY)) {
            "$PROJECT_ROOT_PROPERTY must identify the repository root"
          }
        )
      val includes =
        projectRoot.resolve(PERFORMANCE_PAGE).readText().lineSequence().count { line ->
          line.trim() == SCORECARD_INCLUDE
        }

      includes shouldBe 1
    }
  })

private fun String.attribute(name: String): String =
  lineSequence().single { line -> line.startsWith(":$name:") }.removePrefix(":$name:").trim()

private const val PROJECT_ROOT_PROPERTY = "revoman.projectRoot"
private const val DOCUMENTATION_PARTIAL = "docs/modules/ROOT/partials/performance-scorecard.adoc"
private const val PERFORMANCE_PAGE = "docs/modules/ROOT/pages/performance.adoc"
private const val SCORECARD_INCLUDE = "include::partial\$performance-scorecard.adoc[]"
