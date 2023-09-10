/**
 * ****************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or
 * https://opensource.org/licenses/BSD-3-Clause
 * ****************************************************************************
 */
package com.salesforce.revoman.internal.postman

import com.salesforce.revoman.internal.postman.state.Environment
import com.salesforce.revoman.internal.readFileToString
import com.squareup.moshi.Moshi
import com.squareup.moshi.adapter

// * NOTE 10/09/23 gopala.akshintala: This needs to be Global singleton for Graal JS to work
internal val pm = PostmanSDK()

internal fun initPmEnvironment(
  pmEnvironmentPath: String?,
  dynamicEnvironment: Map<String, String?>?,
  customDynamicVariables: Map<String, (String) -> String>
) {
  // * NOTE 10/09/23 gopala.akshintala: Clear env for each new run
  pm.environment.clear()
  // ! TODO gopala.akshintala 19/05/22: Should we highlight if there are clashes between dynamic env
  // and env path?
  // * NOTE 10/09/23 gopala.akshintala: Adding dynamic variables first, as they can be used to regex
  // replace in env path
  if (!dynamicEnvironment.isNullOrEmpty()) {
    pm.environment.putAll(dynamicEnvironment)
  }
  if (pmEnvironmentPath != null) {
    val environment: Environment? =
      regexReplace(pmEnvironmentPath, pm.environment, customDynamicVariables)
    pm.environment.putAll(
      environment?.values?.filter { it.enabled }?.associate { it.key to it.value } ?: emptyMap()
    )
  }
}

@OptIn(ExperimentalStdlibApi::class)
internal fun regexReplace(
  pmEnvironmentPath: String,
  pmEnvironment: MutableMap<String, Any?>,
  customDynamicVariables: Map<String, (String) -> String>,
  dynamicVariableGenerator: (String) -> String? = ::dynamicVariableGenerator
): Environment? {
  val env =
    Moshi.Builder().build().adapter<Environment>().fromJson(readFileToString(pmEnvironmentPath))
  return env?.let {
    RegexReplacer(pmEnvironment, customDynamicVariables, dynamicVariableGenerator).replaceRegex(it)
  }
}
