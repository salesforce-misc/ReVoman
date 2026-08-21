/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.distribution

import java.nio.file.Path

/**
 * Immutable proof that a frozen distribution passed every structural, identity, and JAR check.
 *
 * This sealed interface has no public implementation or factory. The validator's private proof
 * implementation is the only value later process-launch code can receive.
 */
sealed interface VerifiedDistribution {
  val root: Path
  val metadata: DistributionMetadata
  val benchmarkClasspath: List<Path>
  val runnerClasspath: List<Path>
}
