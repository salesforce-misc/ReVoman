/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package org.example.revoman.consumer

import com.salesforce.revoman.input.config.Kick
import com.salesforce.revoman.input.config.PollingConfig
import com.salesforce.revoman.input.config.StepPick.PostTxnStepPick
import java.time.Duration
import org.http4k.core.Request

class KotlinPollingApiFixture {
  fun polling(fallbackRequest: Request): PollingConfig =
    PollingConfig.poll(PostTxnStepPick.afterStepName("create"))
      .request { report, environment ->
        report.step.name
        environment.getAsString("token")
        environment.set("consumer.pollRequest", report.step.name)
        fallbackRequest
      }
      .every(Duration.ofMillis(25))
      .timeout(Duration.ofSeconds(2))
      .until { response, environment ->
        environment.set("consumer.pollStatus", response.status.code)
        response.status.code in 200..299 && !environment.containsKey("cancel")
      }

  fun attach(builder: Kick.Builder, fallbackRequest: Request): Kick =
    builder.pollingConfig(polling(fallbackRequest)).off()
}
