/**
 * ****************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or
 * https://opensource.org/licenses/BSD-3-Clause
 * ****************************************************************************
 */
@file:JvmName("ConfigUtils")

package com.salesforce.revoman.input

import com.salesforce.revoman.output.Rundown
import com.salesforce.vador.config.base.BaseValidationConfig.BaseValidationConfigBuilder
import io.vavr.CheckedConsumer
import java.lang.reflect.Type
import org.immutables.value.Value
import org.immutables.value.Value.Style.ImplementationVisibility.PUBLIC

@Config
@Value.Immutable
internal interface KickDef {
  fun templatePath(): String

  @SkipNulls fun runOnlySteps(): Set<String>

  @SkipNulls fun skipSteps(): Set<String>

  fun environmentPath(): String?

  @SkipNulls fun dynamicEnvironment(): Map<String, String>

  @SkipNulls fun customDynamicVariables(): Map<String, (String) -> String>

  fun bearerTokenKey(): String?

  @SkipNulls fun haltOnAnyFailureExceptForSteps(): Set<String>

  @SkipNulls fun hooks(): Set<Set<HookConfig>>

  // ! FIXME 25/06/23 gopala.akshintala: Not in-use
  @Value.Default fun validationStrategy(): ValidationStrategy = ValidationStrategy.FAIL_FAST

  // ! TODO 26/08/23 gopala.akshintala: Validate for duplicate stepNames
  @SkipNulls fun responseConfig(): Set<Set<ResponseConfig>>

  @SkipNulls fun customAdaptersForResponse(): List<Any>

  @SkipNulls fun typesInResponseToIgnore(): Set<Class<out Any>>

  @Value.Default fun insecureHttp(): Boolean = false
}

data class ResponseConfig
private constructor(
  val stepName: String,
  val successType: Type? = null,
  val errorType: Type? = null,
  val validationConfig: BaseValidationConfigBuilder<out Any, out Any?, *, *>? = null
) {
  companion object {
    @JvmStatic
    fun unmarshallSuccessResponse(stepName: String, successType: Type): Set<ResponseConfig> =
      setOf(ResponseConfig(stepName, successType))

    @JvmStatic
    fun unmarshallSuccessResponse(stepNames: Set<String>, successType: Type): Set<ResponseConfig> =
      stepNames.flatMap { unmarshallSuccessResponse(it, successType) }.toSet()

    @JvmStatic
    fun unmarshallResponse(
      stepName: String,
      successType: Type,
      errorType: Type
    ): Set<ResponseConfig> = setOf(ResponseConfig(stepName, successType, errorType))

    @JvmStatic
    fun unmarshallResponse(
      stepNames: Set<String>,
      successType: Type,
      errorType: Type
    ): Set<ResponseConfig> =
      stepNames.flatMap { unmarshallResponse(it, successType, errorType) }.toSet()

    @JvmStatic
    fun validateIfSuccess(
      stepName: String,
      successType: Type,
      validationConfig: BaseValidationConfigBuilder<out Any, out Any?, *, *>
    ): Set<ResponseConfig> =
      setOf(ResponseConfig(stepName, successType, validationConfig = validationConfig))

    @JvmStatic
    fun validateIfSuccess(
      stepNames: Set<String>,
      successType: Type,
      validationConfig: BaseValidationConfigBuilder<out Any, out Any?, *, *>
    ): Set<ResponseConfig> =
      stepNames.flatMap { validateIfSuccess(it, successType, validationConfig) }.toSet()

    @JvmStatic
    fun validateIfFailed(
      stepName: String,
      errorType: Type,
      validationConfig: BaseValidationConfigBuilder<out Any, out Any?, *, *>
    ): Set<ResponseConfig> =
      setOf(ResponseConfig(stepName, errorType = errorType, validationConfig = validationConfig))

    @JvmStatic
    fun validateIfFailed(
      stepNames: Set<String>,
      errorType: Type,
      validationConfig: BaseValidationConfigBuilder<out Any, out Any?, *, *>
    ): Set<ResponseConfig> =
      stepNames.flatMap { validateIfFailed(it, errorType, validationConfig) }.toSet()
  }
}

enum class HookType {
  PRE,
  POST,
  // ! TODO 08/23 gopala.akshintala: Support other Hook types
  REQUEST_SUCCESS,
  REQUEST_FAILURE,
  TEST_SCRIPT_JS_FAILURE
}

data class HookConfig
private constructor(
  val stepName: String,
  val hookType: HookType,
  val hook: CheckedConsumer<Rundown>
) {
  companion object {
    @JvmStatic
    fun pre(stepName: String, hook: CheckedConsumer<Rundown>): Set<HookConfig> =
      setOf(HookConfig(stepName, HookType.PRE, hook))

    @JvmStatic
    fun pre(stepNames: Set<String>, hook: CheckedConsumer<Rundown>): Set<HookConfig> =
      stepNames.flatMap { pre(it, hook) }.toSet()

    @JvmStatic
    fun post(stepName: String, hook: CheckedConsumer<Rundown>): Set<HookConfig> =
      setOf(HookConfig(stepName, HookType.POST, hook))

    @JvmStatic
    fun post(stepNames: Set<String>, hook: CheckedConsumer<Rundown>): Set<HookConfig> =
      stepNames.flatMap { post(it, hook) }.toSet()
  }
}

enum class ValidationStrategy {
  FAIL_FAST,
}

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
@Value.Style(
  typeImmutable = "*",
  typeAbstract = ["*Def"],
  builder = "configure",
  build = "off",
  put = "*",
  add = "*",
  depluralize = true,
  visibility = PUBLIC
)
private annotation class Config

private annotation class SkipNulls
