/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.json.adapters

import com.salesforce.revoman.output.report.Step
import com.salesforce.revoman.output.report.StepJson
import com.squareup.moshi.FromJson
import com.squareup.moshi.ToJson
import java.util.UUID

object StepAdapter {
  @ToJson fun toJson(step: Step): StepJson = StepJson(step.index, step.name, step.isIgnoredForFailure)

  @FromJson fun fromJson(uuidStr: String): UUID =TODO()
}
