/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.finalize

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import performance.hash.Sha256
import performance.json.CanonicalJson
import performance.model.HostDocumentRef
import performance.model.QualificationEvidence
import performance.model.SubstrateIdentity
import performance.schema.EvidenceSchemaValidator
import performance.schema.SchemaKind

/** Reads the bounded host-qualification documents mounted for frozen finalization. */
internal object HostQualificationReader {
  fun read(
    root: Path,
    policy: Sha256,
    substrate: SubstrateIdentity,
    campaign: Boolean,
  ): QualificationEvidence {
    require(
      root.isAbsolute &&
        root == root.toAbsolutePath().normalize() &&
        Files.isDirectory(root, NOFOLLOW_LINKS) &&
        !Files.isSymbolicLink(root),
    )
    val preflight = hostRef(root, "preflight.json", SchemaKind.PREFLIGHT, policy)
    val watcher = hostRef(root, "watcher.json", SchemaKind.WATCHER, policy)
    val postflight = hostRef(root, "postflight.json", SchemaKind.POSTFLIGHT, policy)
    val restoration = hostRef(root, "restoration.json", SchemaKind.RESTORATION, policy)
    require(
      CanonicalJson.parseStrict(readCanonical(root.resolve(restoration.path)))
        .get("cleanupPassed")
        .asBoolean(),
    )
    return when (substrate) {
      is SubstrateIdentity.ControlledMac ->
        if (campaign) {
          QualificationEvidence.ControlledMacCampaign(
            policy,
            preflight,
            watcher,
            postflight,
            restoration,
            true,
          )
        } else {
          QualificationEvidence.ControlledMacBoundedDiagnostic(
            policy,
            preflight,
            watcher,
            postflight,
            restoration,
            "standaloneBoundedDiagnostic",
          )
        }
      is SubstrateIdentity.GithubHosted -> {
        require(!campaign)
        QualificationEvidence.GithubHosted(
          policy,
          preflight,
          restoration,
          "githubHosted",
        )
      }
    }
  }

  private fun hostRef(
    root: Path,
    name: String,
    schema: SchemaKind,
    policy: Sha256,
  ): HostDocumentRef {
    val bytes = readCanonical(root.resolve(name))
    require(EvidenceSchemaValidator().validate(schema, bytes).isEmpty())
    require(CanonicalJson.parseStrict(bytes).get("policySha256").asString() == policy.hex)
    return HostDocumentRef(name, Sha256.digest(bytes))
  }

  private fun readCanonical(path: Path): ByteArray {
    require(Files.isRegularFile(path, NOFOLLOW_LINKS) && !Files.isSymbolicLink(path))
    val bytes = Files.readAllBytes(path)
    require(CanonicalJson.encode(CanonicalJson.parseStrict(bytes)).contentEquals(bytes))
    return bytes
  }
}
