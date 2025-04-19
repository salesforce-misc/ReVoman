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
        // <1>
        Kick.configure()
          .templatePath(PM_COLLECTION_PATH) // <2>
          .environmentPath(PM_ENVIRONMENT_PATH) // <3>
          .off()
      )
    println(depGraph.toJson())
  }

  companion object {
    private const val PM_COLLECTION_PATH =
      "pm-templates/restfulapidev/restful-api.dev.postman_collection.json"
    private const val PM_ENVIRONMENT_PATH =
      "pm-templates/restfulapidev/restful-api.dev.postman_environment.json"
  }
}
