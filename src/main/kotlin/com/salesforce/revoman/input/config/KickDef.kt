/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.input.config

import com.salesforce.revoman.input.config.StepPick.ExeStepPick
import com.salesforce.revoman.input.config.StepPick.PostTxnStepPick
import com.salesforce.revoman.input.config.StepPick.PreTxnStepPick
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonAdapter.Factory
import io.vavr.control.Either
import java.lang.reflect.Type
import java.util.Collections.disjoint
import org.immutables.value.Value
import org.immutables.value.Value.Style.ImplementationVisibility.PUBLIC

@Config
@Value.Immutable
internal interface KickDef {
  // * NOTE 29/10/23 gopala.akshintala: `List` coz it allows adding a template twice
  fun templatePaths(): List<String>

  fun environmentPaths(): Set<String>

  fun dynamicEnvironments(): List<Map<String, String>>

  @Value.Derived
  fun dynamicEnvironmentsFlattened(): Map<String, String> =
    if (dynamicEnvironments().isEmpty()) emptyMap()
    else dynamicEnvironments().reduce { acc, map -> acc + map }

  fun customDynamicVariables(): Map<String, (String) -> String>

  @Value.Default fun haltOnAnyFailure(): Boolean = false

  fun haltOnAnyFailureExcept(): PostTxnStepPick?

  fun runOnlySteps(): List<ExeStepPick>

  fun skipSteps(): List<ExeStepPick>

  fun hooks(): Set<HookConfig>

  @Value.Derived fun preHooks(): List<HookConfig> = hooks().filter { it.pick is PreTxnStepPick }

  @Value.Derived fun postHooks(): List<HookConfig> = hooks().filter { it.pick is PostTxnStepPick }

  // * NOTE 29/10/23 gopala.akshintala: requestConfig/responseConfig are decoupled from pre- /
  // post-hook to allow setting up unmarshalling to strong types, on the final rundown,
  // agnostic of whether the step has any hook

  fun requestConfig(): Set<RequestConfig>

  @Value.Derived
  fun customAdaptersFromRequestConfig(): Map<Type, List<Either<JsonAdapter<Any>, Factory>>> =
    requestConfig()
      .filter { it.customAdapter != null }
      .groupBy({ it.requestType }, { it.customAdapter!! })

  fun responseConfig(): Set<ResponseConfig>

  @Value.Derived
  fun pickToResponseConfig(): Map<Boolean, List<ResponseConfig>> =
    responseConfig().groupBy { it.ifSuccess }

  @Value.Derived
  fun customAdaptersFromResponseConfig(): Map<Type, List<Either<JsonAdapter<Any>, Factory>>> =
    responseConfig()
      .filter { it.customAdapter != null }
      .groupBy({ it.responseType }, { it.customAdapter!! })

  fun customAdaptersForMarshalling(): List<Any>

  fun typesToIgnoreForMarshalling(): Set<Class<out Any>>

  @Value.Default fun insecureHttp(): Boolean = false

  @Value.Check
  fun validateConfig() {
    require(
      !haltOnAnyFailure() || (haltOnAnyFailure() && haltOnAnyFailureExcept() == null),
    ) {
      "`haltOnAnyFailureExcept` should NOT be set when `haltOnAnyFailure` is set to `True`"
    }
    require(disjoint(runOnlySteps(), skipSteps())) {
      "`runOnlySteps` and `skipSteps` cannot contain same step names"
    }
  }
}

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
@Value.Style(
  typeImmutable = "*",
  typeAbstract = ["*Def"],
  builder = "configure",
  build = "off",
  depluralize = true,
  add = "*",
  put = "*",
  with = "override*",
  visibility = PUBLIC,
)
private annotation class Config
