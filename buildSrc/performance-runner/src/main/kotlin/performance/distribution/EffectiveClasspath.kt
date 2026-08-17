/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.distribution

internal object EffectiveClasspath {
  fun validate(inspections: List<JarInspection>): List<DistributionProblem> {
    val problems = inspections.flatMap(JarInspection::problems).toMutableList()
    val classOwners = mutableMapOf<String, Int>()
    inspections.forEachIndexed { jarIndex, inspection ->
      inspection.effectiveClasses.forEach { identity ->
        val previousOwner = classOwners.putIfAbsent(identity, jarIndex)
        if (previousOwner != null && previousOwner != jarIndex) {
          problems += DistributionProblem.DUPLICATE_EFFECTIVE_CLASS
        }
      }
    }

    val effectiveClasses = inspections.flatMap(JarInspection::effectiveClasses).toSet()
    if (
      inspections
        .asSequence()
        .flatMap { it.serviceProviders.asSequence() }
        .any { provider -> provider !in effectiveClasses }
    ) {
      problems += DistributionProblem.SERVICE_PROVIDER_MISSING
    }
    if (inspections.any { inspection -> inspection.allClasses.any(::isTestClass) }) {
      problems += DistributionProblem.TEST_CONTENT_PRESENT
    }
    return immutableList(problems.distinct())
  }

  private fun isTestClass(identity: String): Boolean {
    val simpleName = identity.substringAfterLast('.').substringBefore('$')
    return identity.startsWith("org.junit.") ||
      identity.startsWith("org.junit.jupiter.") ||
      identity.startsWith("io.kotest.") ||
      identity.startsWith("io.mockk.") ||
      identity.startsWith("net.bytebuddy.") ||
      simpleName.endsWith("Test") ||
      simpleName.endsWith("Tests") ||
      simpleName.endsWith("Spec")
  }
}
