/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.schema

import com.networknt.schema.Schema
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import performance.json.CanonicalJson

private const val SCHEMA_ID_ROOT = "https://revoman.dev/performance/protocol/schemas"

/** Every versioned evidence document schema embedded in the frozen protocol. */
enum class SchemaKind(
  val fileName: String,
) {
  CAPTURE("capture-v1.schema.json"),
  CAPTURE_PROVISIONAL("capture-provisional-v1.schema.json"),
  CALIBRATION_PROVISIONAL("calibration-provisional-v1.schema.json"),
  COMPARISON("comparison-v1.schema.json"),
  REGRESSION_POLICY("regression-policy-v1.schema.json"),
  CAPTURE_PROFILE_FAMILY("capture-profile-family-v1.schema.json"),
  EXPECTED_CELLS("expected-cells-v1.schema.json"),
  PREFLIGHT("preflight-v1.schema.json"),
  WATCHER("watcher-v1.schema.json"),
  POSTFLIGHT("postflight-v1.schema.json"),
  RESTORATION("restoration-v1.schema.json"),
  PROFILER_SUMMARY("profiler-summary-v1.schema.json"),
  ;

  val id: String = "$SCHEMA_ID_ROOT/$fileName"
}

/** One privacy-safe schema failure, without echoing the rejected value. */
data class SchemaViolation(
  val path: String,
  val keyword: String,
  val message: String,
)

/** Deep validation module for canonical evidence bytes and all frozen Draft 2020-12 schemas. */
class EvidenceSchemaValidator {
  private val schemas: Map<SchemaKind, Schema> = loadSchemas()

  /** Validates canonical bytes without exposing rejected document values in errors. */
  fun validate(schema: SchemaKind, canonicalJson: ByteArray): List<SchemaViolation> {
    val parsed =
      runCatching { CanonicalJson.parseStrict(canonicalJson) }
        .getOrElse {
          return listOf(
            SchemaViolation(
              path = "$",
              keyword = "syntax",
              message = "input is not strict JSON",
            ),
          )
        }

    if (!CanonicalJson.encode(parsed).contentEquals(canonicalJson)) {
      return listOf(
        SchemaViolation(
          path = "$",
          keyword = "canonical",
          message = "input is not canonical JSON",
        ),
      )
    }

    return schemas
      .getValue(schema)
      .validate(parsed) { executionContext ->
        executionContext.executionConfig { config -> config.formatAssertionsEnabled(true) }
      }
      .map { error ->
        SchemaViolation(
          path = "$",
          keyword = error.keyword,
          message = "schema constraint failed: ${error.keyword}",
        )
      }
      .sortedWith(compareBy(SchemaViolation::path, SchemaViolation::keyword))
  }

  private fun loadSchemas(): Map<SchemaKind, Schema> {
    val resourcesById = SchemaKind.entries.associate { kind -> kind.id to readSchema(kind) }
    val registry =
      SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12) { builder ->
        builder.schemas(resourcesById)
      }
    return SchemaKind.entries.associateWith { kind ->
      registry.getSchema(SchemaLocation.of(kind.id)).also { schema -> schema.initializeValidators() }
    }
  }

  private fun readSchema(kind: SchemaKind): String =
    checkNotNull(
        EvidenceSchemaValidator::class.java.getResourceAsStream(
          "/performance/protocol/schemas/${kind.fileName}",
        ),
      ) {
        "missing embedded schema ${kind.fileName}"
      }
      .use { stream -> stream.readAllBytes().decodeToString() }
}
