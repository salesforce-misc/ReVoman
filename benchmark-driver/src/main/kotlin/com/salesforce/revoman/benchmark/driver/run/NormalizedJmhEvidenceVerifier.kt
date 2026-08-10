/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.run

import com.salesforce.revoman.benchmark.driver.json.BenchmarkJson
import com.salesforce.revoman.benchmark.driver.model.HarnessIdentity
import com.salesforce.revoman.benchmark.driver.model.JmhBenchmarkResultV1
import com.salesforce.revoman.benchmark.driver.model.JmhRunConfiguration
import com.salesforce.revoman.benchmark.driver.model.JmhWorkloadIdentity
import com.salesforce.revoman.benchmark.driver.model.TargetIdentity
import java.nio.file.Files
import java.nio.file.Path

/** Identities a strict single-target JMH controller must bind before its raw rows can be paired. */
data class JmhEvidenceExpectation(
    val harness: HarnessIdentity,
    val target: TargetIdentity,
    val workload: JmhWorkloadIdentity,
    val configuration: JmhRunConfiguration,
) {
    companion object {
        fun from(result: JmhBenchmarkResultV1): JmhEvidenceExpectation =
            JmhEvidenceExpectation(
                harness = result.harness,
                target = result.target,
                workload = result.workload,
                configuration = result.configuration,
            )
    }
}

/** Schema-validates and decodes one immutable normalized JMH snapshot before identity comparison. */
object NormalizedJmhEvidenceVerifier {
    fun verify(path: Path, expectation: JmhEvidenceExpectation): JmhBenchmarkResultV1 {
        val canonical = path.toRealPath()
        require(canonical == path && Files.isRegularFile(canonical)) {
            "Normalized JMH evidence must be a canonical regular file: $path"
        }
        val bytes = Files.readAllBytes(canonical)
        BenchmarkJson.validateSchema(
            bytes,
            canonical.toString(),
            JMH_RESULT_SCHEMA_RESOURCE,
        )
        val result = BenchmarkJson.decode<JmhBenchmarkResultV1>(bytes, canonical.toString())
        require(result.harness == expectation.harness) {
            "Normalized JMH evidence does not match the scheduled harness identity"
        }
        require(result.target == expectation.target) {
            "Normalized JMH evidence does not match the scheduled target identity"
        }
        require(result.workload == expectation.workload) {
            "Normalized JMH evidence does not match the scheduled workload identity"
        }
        require(result.configuration == expectation.configuration) {
            "Normalized JMH evidence does not match the scheduled run configuration"
        }
        return result
    }
}

private const val JMH_RESULT_SCHEMA_RESOURCE: String =
    "/schema/revoman-benchmark-jmh-v1.schema.json"
