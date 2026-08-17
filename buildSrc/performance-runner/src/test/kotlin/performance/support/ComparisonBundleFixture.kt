/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.support

import java.nio.file.Files
import java.nio.file.Path
import performance.compare.ComparisonComputation
import performance.hash.Sha256
import performance.json.CanonicalJson
import tools.jackson.databind.node.ObjectNode

/** A strict checksum-sealed comparison directory used to prove candidate calibration inputs. */
internal class ComparisonBundleFixture private constructor(val root: Path) {
  fun document(): ObjectNode =
    CanonicalJson.parseStrict(Files.readAllBytes(root.resolve(COMPARISON_JSON))).asObject()

  fun mutateDocument(mutation: (ObjectNode) -> Unit) {
    val document = document()
    mutation(document)
    Files.write(root.resolve(COMPARISON_JSON), CanonicalJson.encode(document))
    reseal()
  }

  fun writeRaw(relative: String, bytes: ByteArray) {
    Files.write(root.resolve(relative), bytes)
  }

  fun addAndReseal(relative: String, bytes: ByteArray) {
    Files.write(root.resolve(relative), bytes)
    reseal()
  }

  fun deleteAndReseal(relative: String) {
    Files.delete(root.resolve(relative))
    reseal()
  }

  fun reseal() {
    val names =
      Files.list(root).use { paths ->
        paths
          .filter(Files::isRegularFile)
          .map { it.fileName.toString() }
          .filter { it != CHECKSUMS }
          .sorted()
          .toList()
      }
    val manifest = names.joinToString(separator = "\n", postfix = "\n") { relative ->
      "${Sha256.digest(root.resolve(relative)).hex}  $relative"
    }
    Files.write(root.resolve(CHECKSUMS), manifest.encodeToByteArray())
  }

  fun close() {
    root.parent.toFile().deleteRecursively()
  }

  companion object {
    fun create(completed: ComparisonComputation.Completed): ComparisonBundleFixture {
      val root =
        Files.createTempDirectory("comparison-bundle-fixture-").toRealPath().resolve("comparison")
      Files.createDirectories(root)
      Files.write(root.resolve(COMPARISON_JSON), completed.jsonBytes)
      Files.write(root.resolve(COMPARISON_MARKDOWN), completed.markdownBytes)
      return ComparisonBundleFixture(root).apply { reseal() }
    }

    private const val COMPARISON_JSON = "comparison.json"
    private const val COMPARISON_MARKDOWN = "comparison.md"
    private const val CHECKSUMS = "checksums.sha256"
  }
}
