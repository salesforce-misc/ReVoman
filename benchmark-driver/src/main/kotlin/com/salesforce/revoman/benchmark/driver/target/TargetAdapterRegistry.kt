/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.target

import com.salesforce.revoman.benchmark.driver.target.baseline.Baseline083f3cd70Adapter
import com.salesforce.revoman.benchmark.driver.target.major.MajorV1Adapter

/** Resolves only exact, source-hashed target adapter identities. */
object TargetAdapterRegistry {
    fun require(id: String): TargetAdapter =
        when (id) {
            "baseline-83f3cd70" -> Baseline083f3cd70Adapter
            "major-v1" -> MajorV1Adapter
            else -> error("Unknown target adapter: $id")
        }
}
