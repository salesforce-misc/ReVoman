/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.distribution

import com.networknt.schema.Schema
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import performance.hash.Sha256
import performance.json.CanonicalJson
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode

/** The selected Java executable and the runtime feature used for multi-release resolution. */
data class JavaRuntimeIdentity(
  val executable: Path,
  val featureVersion: Int,
  val sha256: Sha256,
)

/** One exact entry in an ordered runtime classpath. */
data class DistributionClasspathEntry(
  val coordinate: String,
  val order: Int,
  val path: String,
  val sha256: Sha256,
)

/** One dependency whose bytes are deliberately embedded in the production jar. */
data class EmbeddedDependency(
  val coordinate: String,
  val placement: String,
  val sha256: Sha256,
)

/** Java identity declared by the frozen distribution. */
data class DeclaredJavaRuntime(
  val executable: Path,
  val executableSha256: Sha256,
  val featureVersion: Int,
)

/** Strict versioned classpath metadata. */
data class DistributionClasspathManifest(
  val schemaVersion: String,
  val javaRuntime: DeclaredJavaRuntime,
  val benchmarkClasspath: List<DistributionClasspathEntry>,
  val runnerClasspath: List<DistributionClasspathEntry>,
  val embeddedDependencies: List<EmbeddedDependency>,
  val expectedBenchmarks: List<String>,
)

/** A clean full Git source identity for one provenance role. */
data class DistributionGitIdentity(
  val gitSha: String,
  val treeClean: Boolean,
)

/** Strict versioned treatment, immutable-harness, and freezer provenance. */
data class DistributionProvenanceManifest(
  val schemaVersion: String,
  val treatment: DistributionGitIdentity,
  val immutableHarness: DistributionGitIdentity,
  val distributionFreezer: DistributionGitIdentity,
)

/** One protocol path bound to its exact frozen bytes. */
data class DistributionArtifactBinding(
  val path: String,
  val sha256: Sha256,
)

/** Strict versioned measurement-protocol bindings embedded in the distribution. */
data class DistributionProtocolManifest(
  val schemaVersion: String,
  val protocolSha256: Sha256,
  val runner: DistributionArtifactBinding,
  val adapter: DistributionArtifactBinding,
  val launchers: List<DistributionArtifactBinding>,
  val schemas: List<DistributionArtifactBinding>,
  val profiles: List<DistributionArtifactBinding>,
  val runtimeDeclarations: List<DistributionArtifactBinding>,
  val qualificationPolicies: List<DistributionArtifactBinding>,
  val expectedCells: DistributionArtifactBinding,
  val testVectors: List<DistributionArtifactBinding>,
) {
  internal fun bindings(): List<DistributionArtifactBinding> =
    immutableList(
      listOf(runner, adapter) +
        launchers +
        schemas +
        profiles +
        runtimeDeclarations +
        qualificationPolicies +
        expectedCells +
        testVectors,
    )
}

/** All three validated distribution metadata documents. */
data class DistributionMetadata(
  val classpath: DistributionClasspathManifest,
  val provenance: DistributionProvenanceManifest,
  val protocol: DistributionProtocolManifest,
)

internal sealed interface DistributionManifestRead {
  data class Valid(val metadata: DistributionMetadata) : DistributionManifestRead

  data class Invalid(val problems: List<DistributionProblem>) : DistributionManifestRead
}

internal class DistributionManifestReader {
  private val schemas: Map<DistributionMetadataKind, Schema> = loadSchemas()

  fun read(root: Path): DistributionManifestRead {
    val documents =
      DistributionMetadataKind.entries.map { kind -> kind to readDocument(root, kind) }
    val problems =
      documents.mapNotNull { (_, result) -> (result as? MetadataDocumentRead.Invalid)?.problem }
    if (problems.isNotEmpty()) {
      return DistributionManifestRead.Invalid(immutableList(problems.distinct()))
    }

    return runCatching {
        val validDocuments =
          documents.associate { (kind, result) ->
            kind to (result as MetadataDocumentRead.Valid).document
          }
        DistributionMetadata(
          classpath = parseClasspath(validDocuments.getValue(DistributionMetadataKind.CLASSPATH)),
          provenance =
            parseProvenance(validDocuments.getValue(DistributionMetadataKind.PROVENANCE)),
          protocol = parseProtocol(validDocuments.getValue(DistributionMetadataKind.PROTOCOL)),
        )
      }
      .fold(
        onSuccess = DistributionManifestRead::Valid,
        onFailure = {
          DistributionManifestRead.Invalid(
            listOf(DistributionProblem.METADATA_SCHEMA_INVALID),
          )
        },
      )
  }

  private fun readDocument(root: Path, kind: DistributionMetadataKind): MetadataDocumentRead {
    val bytes =
      runCatching {
          val path = root.resolve(kind.relativePath)
          check(Files.size(path) <= MAX_METADATA_BYTES)
          Files.readAllBytes(path)
        }
        .getOrElse {
          return MetadataDocumentRead.Invalid(DistributionProblem.METADATA_MISSING)
        }
    val parsed =
      runCatching { CanonicalJson.parseStrict(bytes) }
        .getOrElse {
          return MetadataDocumentRead.Invalid(DistributionProblem.METADATA_INVALID_JSON)
        }
    if (!CanonicalJson.encode(parsed).contentEquals(bytes)) {
      return MetadataDocumentRead.Invalid(DistributionProblem.METADATA_NOT_CANONICAL)
    }
    if (schemas.getValue(kind).validate(parsed).isNotEmpty()) {
      return MetadataDocumentRead.Invalid(DistributionProblem.METADATA_SCHEMA_INVALID)
    }
    return MetadataDocumentRead.Valid(parsed as ObjectNode)
  }

  private fun parseClasspath(document: ObjectNode): DistributionClasspathManifest =
    DistributionClasspathManifest(
      schemaVersion = document.text("schemaVersion"),
      javaRuntime =
        document.objectNode("javaRuntime").let { java ->
          DeclaredJavaRuntime(
            executable = Path.of(java.text("executable")),
            executableSha256 = Sha256.parse(java.text("executableSha256")),
            featureVersion = java.get("featureVersion").asInt(),
          )
        },
      benchmarkClasspath = parseClasspathEntries(document.arrayNode("benchmarkClasspath")),
      runnerClasspath = parseClasspathEntries(document.arrayNode("runnerClasspath")),
      embeddedDependencies =
        immutableList(
          document.arrayNode("embeddedDependencies").values().asSequence().map { value ->
            val dependency = value as ObjectNode
            EmbeddedDependency(
              coordinate = dependency.text("coordinate"),
              placement = dependency.text("placement"),
              sha256 = Sha256.parse(dependency.text("sha256")),
            )
          },
        ),
      expectedBenchmarks =
        immutableList(
          document.arrayNode("expectedBenchmarks").values().asSequence().map(JsonNode::asString),
        ),
    )

  private fun parseClasspathEntries(array: ArrayNode): List<DistributionClasspathEntry> =
    immutableList(
      array.values().asSequence().map { value ->
        val entry = value as ObjectNode
        DistributionClasspathEntry(
          coordinate = entry.text("coordinate"),
          order = entry.get("order").asInt(),
          path = entry.text("path"),
          sha256 = Sha256.parse(entry.text("sha256")),
        )
      },
    )

  private fun parseProvenance(document: ObjectNode): DistributionProvenanceManifest =
    DistributionProvenanceManifest(
      schemaVersion = document.text("schemaVersion"),
      treatment = parseGitIdentity(document.objectNode("treatment")),
      immutableHarness = parseGitIdentity(document.objectNode("immutableHarness")),
      distributionFreezer = parseGitIdentity(document.objectNode("distributionFreezer")),
    )

  private fun parseGitIdentity(document: ObjectNode): DistributionGitIdentity =
    DistributionGitIdentity(
      gitSha = document.text("gitSha"),
      treeClean = document.get("treeClean").asBoolean(),
    )

  private fun parseProtocol(document: ObjectNode): DistributionProtocolManifest =
    DistributionProtocolManifest(
      schemaVersion = document.text("schemaVersion"),
      protocolSha256 = Sha256.parse(document.text("protocolSha256")),
      runner = parseArtifact(document.objectNode("runner")),
      adapter = parseArtifact(document.objectNode("adapter")),
      launchers = parseArtifacts(document.arrayNode("launchers")),
      schemas = parseArtifacts(document.arrayNode("schemas")),
      profiles = parseArtifacts(document.arrayNode("profiles")),
      runtimeDeclarations = parseArtifacts(document.arrayNode("runtimeDeclarations")),
      qualificationPolicies = parseArtifacts(document.arrayNode("qualificationPolicies")),
      expectedCells = parseArtifact(document.objectNode("expectedCells")),
      testVectors = parseArtifacts(document.arrayNode("testVectors")),
    )

  private fun parseArtifacts(array: ArrayNode): List<DistributionArtifactBinding> =
    immutableList(array.values().asSequence().map { parseArtifact(it as ObjectNode) })

  private fun parseArtifact(document: ObjectNode): DistributionArtifactBinding =
    DistributionArtifactBinding(
      path = document.text("path"),
      sha256 = Sha256.parse(document.text("sha256")),
    )

  private fun loadSchemas(): Map<DistributionMetadataKind, Schema> {
    val resourcesById =
      DistributionMetadataKind.entries.associate { kind -> kind.schemaId to readSchema(kind) }
    val registry =
      SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12) { builder ->
        builder.schemas(resourcesById)
      }
    return DistributionMetadataKind.entries.associateWith { kind ->
      registry.getSchema(SchemaLocation.of(kind.schemaId)).also(Schema::initializeValidators)
    }
  }

  private fun readSchema(kind: DistributionMetadataKind): String =
    checkNotNull(
        DistributionManifestReader::class.java.getResourceAsStream(kind.resourcePath),
      ) {
        "missing embedded distribution metadata schema"
      }
      .use { stream -> stream.readAllBytes().decodeToString() }

  private sealed interface MetadataDocumentRead {
    data class Valid(val document: ObjectNode) : MetadataDocumentRead

    data class Invalid(val problem: DistributionProblem) : MetadataDocumentRead
  }

  private enum class DistributionMetadataKind(
    val relativePath: String,
    val fileName: String,
  ) {
    CLASSPATH("metadata/classpath.json", "distribution-classpath-v1.schema.json"),
    PROVENANCE("metadata/provenance.json", "distribution-provenance-v1.schema.json"),
    PROTOCOL("metadata/protocol.json", "distribution-protocol-v1.schema.json"),
    ;

    val resourcePath: String = "/performance/protocol/schemas/$fileName"
    val schemaId: String = "https://revoman.dev/performance/protocol/schemas/$fileName"
  }

  private companion object {
    const val MAX_METADATA_BYTES = 1024L * 1024L
  }
}

private fun ObjectNode.text(name: String): String = get(name).asString()

private fun ObjectNode.objectNode(name: String): ObjectNode = get(name) as ObjectNode

private fun ObjectNode.arrayNode(name: String): ArrayNode = get(name) as ArrayNode

internal fun <T> immutableList(values: Iterable<T>): List<T> =
  Collections.unmodifiableList(values.toList())

internal fun <T> immutableList(values: Sequence<T>): List<T> = immutableList(values.asIterable())
