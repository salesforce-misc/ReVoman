/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package org.example.revoman.consumer

import com.salesforce.revoman.output.postman.PostmanEnvironment
import com.salesforce.revoman.output.report.TxnInfo
import org.http4k.core.Request

class KotlinEnvironmentAndTxnApiFixture {
  fun environment(): PostmanEnvironment<Any?> =
    PostmanEnvironment<Any?>().apply {
      set("token", "value")
      this["count"] = 1
      getAsString("token")
      getInt("count")
      immutableEnv
      postmanEnvJSONFormat
      mutableEnvCopyWithValuesOfType(String::class.java)
      mutableEnvCopyWithKeysStartingWith(String::class.java, "tok")
      mutableEnvCopyWithKeysEndingWith(String::class.java, "ken")
      mutableEnvCopyWithKeysMatching(String::class.java, "to.*")
      valuesForKeysStartingWith(String::class.java, "tok")
      getTypedObj<String>("token", String::class.java)
      unset("count")
    }

  fun typedTransaction(info: TxnInfo<Request>): Pair<String?, String> =
    info.getTypedTxnObj<String>(String::class.java) to info.httpMsg.uri.path
}
