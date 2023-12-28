/**
 * ****************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ****************************************************************************
 */
package com.salesforce.revoman.input.config

import com.salesforce.revoman.input.config.HookConfig.Hook.PostHook
import com.salesforce.revoman.input.config.HookConfig.Hook.PreHook
import com.salesforce.revoman.input.config.HookConfig.HookType.POST
import com.salesforce.revoman.input.config.HookConfig.HookType.PRE
import com.salesforce.revoman.input.config.StepPick.PostTxnStepPick
import com.salesforce.revoman.input.config.StepPick.PreTxnStepPick
import com.salesforce.revoman.internal.exe.fqStepNameToStepName
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonAdapter.Factory
import io.vavr.control.Either
import java.lang.reflect.Type
import java.util.*
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

  fun runOnlySteps(): Set<String>

  fun skipSteps(): Set<String>

  fun haltOnAnyFailureExceptForSteps(): Set<String>

  @Value.Default fun haltOnAnyFailure(): Boolean = false

  fun hooks(): Set<Set<HookConfig>>

  @Value.Derived
  fun preHooksWithStepNamesFlattened(): Map<String, List<PreHook>> =
    hooks()
      .flatten()
      .filter { it.pick.isLeft && it.pick.left.first == PRE }
      .groupBy({ it.pick.left.second }, { it.hook as PreHook })

  @Value.Derived
  fun postHooksWithStepNamesFlattened(): Map<String, List<PostHook>> =
    hooks()
      .flatten()
      .asSequence()
      .filter { it.pick.isLeft && it.pick.left.first == POST }
      .groupBy({ it.pick.left.second }, { it.hook as PostHook })

  @Value.Derived
  fun preHooksWithPicksFlattened(): List<Pair<PreTxnStepPick, PreHook>> =
    hooks()
      .flatten()
      .filter { it.pick.isRight && it.pick.get() is PreTxnStepPick }
      .map { it.pick.get() as PreTxnStepPick to it.hook as PreHook }

  @Value.Derived
  fun postHooksWithPicksFlattened(): List<Pair<PostTxnStepPick, PostHook>> =
    hooks()
      .flatten()
      .filter { it.pick.isRight && it.pick.get() is PostTxnStepPick }
      .map { it.pick.get() as PostTxnStepPick to it.hook as PostHook }

  // * NOTE 29/10/23 gopala.akshintala: requestConfig/responseConfig are decoupled from pre- /
  // post-hook to allow setting up unmarshalling to strong types, on the final rundown,
  // agnostic of whether the step has any hook

  fun requestConfig(): Set<Set<RequestConfig>>

  @Value.Derived
  fun stepNameToRequestConfig(): Map<String, RequestConfig> =
    requestConfig().flatten().filter { it.pick.isLeft }.associateBy { it.pick.left }

  @Value.Derived
  fun pickToRequestConfig(): List<Pair<PreTxnStepPick, RequestConfig>> =
    requestConfig().flatten().filter { it.pick.isRight }.map { it.pick.get() to it }

  @Value.Derived
  fun customAdaptersFromRequestConfig(): Map<Type, List<Either<JsonAdapter<Any>, Factory>>> =
    requestConfig()
      .flatten()
      .filter { it.customAdapter != null }
      .groupBy({ it.requestType }, { it.customAdapter!! })

  fun responseConfig(): Set<Set<ResponseConfig>>

  @Value.Derived
  fun stepNameToResponseConfig(): Map<Pair<Boolean, String>, ResponseConfig> =
    responseConfig()
      .flatten()
      .filter { it.pick.isLeft }
      .associateBy { it.ifSuccess to it.pick.left }

  @Value.Derived
  fun pickToResponseConfig(): Map<Boolean, List<Pair<PostTxnStepPick, ResponseConfig>>> =
    responseConfig()
      .flatten()
      .filter { it.pick.isRight }
      .map { it.pick.get() to it }
      .groupBy { it.second.ifSuccess }

  @Value.Derived
  fun customAdaptersFromResponseConfig(): Map<Type, List<Either<JsonAdapter<Any>, Factory>>> =
    responseConfig()
      .flatten()
      .filter { it.customAdapter != null }
      .groupBy({ it.responseType }, { it.customAdapter!! })

  fun customAdaptersForMarshalling(): List<Any>

  fun typesToIgnoreForMarshalling(): Set<Class<out Any>>

  @Value.Default fun insecureHttp(): Boolean = false

  @Value.Check
  fun validateConfig() {
    require(
      !haltOnAnyFailure() || (haltOnAnyFailure() && haltOnAnyFailureExceptForSteps().isEmpty()),
    ) {
      "`haltOnAnyFailureExceptForSteps` should be empty when `haltOnAnyFailure` is set to True"
    }
    require(Collections.disjoint(runOnlySteps(), skipSteps())) {
      "`runOnlySteps` and `skipSteps` cannot contain same step names"
    }

    val stepNamesWithMultipleRequestConfigs =
      stepNameToRequestConfig()
        .keys
        // ! TODO 27/12/23 gopala.akshintala: This might not work if there is a mix of fullStepName
        // and StepName, but it's a corner case as two steps with same name may have same
        // requestConfig
        .groupingBy { fqStepNameToStepName(it) }
        .eachCount()
        .filterValues { it > 1 }
        .keys
    require(stepNamesWithMultipleRequestConfigs.isEmpty()) {
      "Duplicate RequestConfigs detected for stepNames: ${stepNamesWithMultipleRequestConfigs.joinToString(", ")}"
    }

    val stepNamesWithMultipleResponseConfigs =
      stepNameToResponseConfig()
        .keys
        .groupingBy { fqStepNameToStepName(it.second) }
        .eachCount()
        .filterValues { it > 1 }
        .keys
    require(stepNamesWithMultipleResponseConfigs.isEmpty()) {
      "Duplicate ResponseConfigs detected for stepNames: ${stepNamesWithMultipleResponseConfigs.joinToString(", ")}"
    }
  }
  // ! TODO 22/06/23 gopala.akshintala: Validate if validation config for a step is mentioned but
  // the stepName is not present
  // ! TODO 22/06/23 gopala.akshintala: Validate if steps with the same name are used in config
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
