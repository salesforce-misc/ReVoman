/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman

import com.salesforce.revoman.input.bufferFileInResources
import com.salesforce.revoman.internal.postman.template.Environment
import com.squareup.moshi.Moshi
import com.squareup.moshi.adapter

// * NOTE 10/09/23 gopala.akshintala: This needs to be Global singleton for Graal JS to work
internal val pm = PostmanSDK()

@OptIn(ExperimentalStdlibApi::class)
internal fun mergeEnvs(
  pmEnvironmentPaths: Set<String>,
  dynamicEnvironment: Map<String, String?>
): Map<String, String?> {
  // ! TODO gopala.akshintala 19/05/22: Should we highlight if there are clashes between dynamic env
  // and env path?
  // * NOTE 10/09/23 gopala.akshintala: Adding dynamic variables first, as they can be used to regex
  // replace in env path
  val envAdapter = Moshi.Builder().build().adapter<Environment>()
  // ! TODO 05/10/23 gopala.akshintala: Consider values from env file being parsed to replace
  val envFromEnvFiles =
    pmEnvironmentPaths
      .flatMap { envWithRegex ->
        envAdapter.fromJson(bufferFileInResources(envWithRegex))?.values?.filter { it.enabled }
          ?: emptyList()
      }
      .associate { it.key to it.value }
  return dynamicEnvironment + envFromEnvFiles
}
