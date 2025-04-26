/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.integration

import com.salesforce.revoman.ReVoman
import com.salesforce.revoman.input.config.Kick
import org.junit.jupiter.api.Test

/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
class ReVomanTest {
  @Test
  fun `build dep graph restful-api dev pm collection`() {
    val depGraph =
      ReVoman.buildDepGraph(
        Kick.configure()
          .templatePaths(
            "pm-templates/core/milestone/persona-creation-and-setup.postman_collection.json",
            "pm-templates/core/milestone/milestone-setup.postman_collection.json",
            "pm-templates/core/milestone/bmp-create-runtime.postman_collection.json",
          )
          .environmentPath("pm-templates/core/milestone/env.postman_environment.json")
          .off()
      )
    println(depGraph.toJson())
  }

  @Test
  fun `query exe chain milestone pm collection`() {
    val template =
      ReVoman.queryChainForVariable(
        "orderId",
        Kick.configure()
          .templatePaths(
            "pm-templates/core/milestone/persona-creation-and-setup.postman_collection.json",
            "pm-templates/core/milestone/milestone-setup.postman_collection.json",
            "pm-templates/core/milestone/bmp-create-runtime.postman_collection.json",
          )
          .nodeModulesPath("js")
          .environmentPath("pm-templates/core/milestone/env.postman_environment.json")
          .off(),
      )
    println(template.toJson())
  }

  @Test
  fun `exe chain milestone pm collection`() {
    val rundown =
      ReVoman.exeChainForVariable(
        "orderId",
        Kick.configure()
          .templatePaths(
            "pm-templates/core/milestone/place-order.postman_collection.json",
          )
          .nodeModulesPath("js")
          .environmentPath("pm-templates/core/milestone/env.postman_environment.json")
          .off(),
      )
    println(rundown.toJson())
  }

  @Test
  fun `resume chain milestone pm collection`() {
    val rundown =
      ReVoman.diffExeChainForVariable(
        "oneTimePriceBookEntryId",
        "orderId",
        Kick.configure()
          .templatePaths(
            "pm-templates/core/milestone/place-order.postman_collection.json",
          )
          .nodeModulesPath("js")
          .environmentPath("pm-templates/core/milestone/env.postman_environment.json")
          .off(),
      )
    println(rundown.toJson())
  }
}
