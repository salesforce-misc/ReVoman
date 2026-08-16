/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.model

import performance.hash.Sha256

/** Stable run identifiers shared by every capture-global record. */
data class CaptureIdentity(
  val captureId: String,
  val processRunId: String,
  val performanceSessionId: String,
  val sessionSequence: Int,
)

/** Descriptive alias used by evidence consumers outside the capture runner. */
typealias EvidenceIdentity = CaptureIdentity

/** Clean Git provenance for one distinct evidence-production role. */
data class GitProvenance(
  val gitSha: String,
  val treeClean: Boolean,
)

/** The four provenance roles that must never be collapsed into one checkout identity. */
data class ProvenanceRoles(
  val treatment: GitProvenance,
  val immutableHarness: GitProvenance,
  val distributionFreezer: GitProvenance,
  val captureRunner: GitProvenance,
)

/** A normalized relative artifact path bound to its bytes. */
data class ArtifactIdentity(
  val path: String,
  val sha256: Sha256,
)

/** A dependency coordinate bound to the exact artifact bytes used during capture. */
data class DependencyIdentity(
  val coordinate: String,
  val sha256: Sha256,
)

/** Claim-relevant artifact identities, retaining classpath order. */
data class CaptureArtifacts(
  val production: ArtifactIdentity,
  val benchmark: ArtifactIdentity,
  val distribution: ArtifactIdentity,
  val orderedClasspath: List<ArtifactIdentity>,
  val executingRunner: ArtifactIdentity,
  val orderedRunnerClasspath: List<ArtifactIdentity>,
  val dependencies: List<DependencyIdentity>,
  val rawJmhInputSha256: Sha256,
)

/** Frozen protocol hashes required to interpret or compare a capture. */
data class ProtocolIdentity(
  val benchmarkSourceSha256: Sha256,
  val benchmarkProtocolSha256: Sha256,
  val qualificationPolicySha256: Sha256,
  val workloadTreeSha256: Sha256,
  val hostAdapterSha256: Sha256,
  val schemaSha256: Sha256,
  val rendererSha256: Sha256,
  val comparatorSha256: Sha256,
)
