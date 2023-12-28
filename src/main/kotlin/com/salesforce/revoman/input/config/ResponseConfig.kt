package com.salesforce.revoman.input.config

import com.salesforce.revoman.input.config.StepPick.PostTxnStepPick
import com.salesforce.vador.config.ValidationConfig
import com.squareup.moshi.JsonAdapter
import io.vavr.control.Either
import io.vavr.kotlin.left
import io.vavr.kotlin.right
import java.lang.reflect.Type

data class ResponseConfig
private constructor(
  val pick: Either<String, PostTxnStepPick>,
  val ifSuccess: Boolean,
  val responseType: Type,
  val customAdapter: Either<JsonAdapter<Any>, JsonAdapter.Factory>? = null,
  val validationConfig: ValidationConfig<*, *>? = null
) {
  companion object {
    @JvmStatic
    fun unmarshallSuccessResponse(
      stepName: String,
      successType: Type,
    ): Set<ResponseConfig> = setOf(ResponseConfig(left(stepName), true, successType))

    @JvmStatic
    fun unmarshallSuccessResponse(
      stepNames: Set<String>,
      successType: Type,
    ): Set<ResponseConfig> =
      stepNames.flatMap { unmarshallSuccessResponse(it, successType) }.toSet()

    @JvmStatic
    fun unmarshallSuccessResponse(
      postTxnStepPick: PostTxnStepPick,
      successType: Type,
    ): Set<ResponseConfig> = setOf(ResponseConfig(right(postTxnStepPick), true, successType))

    @JvmStatic
    fun unmarshallSuccessResponse(
      stepName: String,
      successType: Type,
      customAdapter: JsonAdapter<Any>
    ): Set<ResponseConfig> =
      setOf(ResponseConfig(left(stepName), true, successType, left(customAdapter)))

    @JvmStatic
    fun unmarshallSuccessResponse(
      stepNames: Set<String>,
      successType: Type,
      customAdapter: JsonAdapter<Any>
    ): Set<ResponseConfig> =
      stepNames.flatMap { unmarshallSuccessResponse(it, successType, customAdapter) }.toSet()

    @JvmStatic
    fun unmarshallSuccessResponse(
      postTxnStepPick: PostTxnStepPick,
      successType: Type,
      customAdapter: JsonAdapter<Any>
    ): Set<ResponseConfig> =
      setOf(ResponseConfig(right(postTxnStepPick), true, successType, left(customAdapter)))

    @JvmStatic
    fun unmarshallSuccessResponse(
      stepName: String,
      successType: Type,
      customAdapterFactory: JsonAdapter.Factory
    ): Set<ResponseConfig> =
      setOf(ResponseConfig(left(stepName), true, successType, right(customAdapterFactory)))

    @JvmStatic
    fun unmarshallSuccessResponse(
      stepNames: Set<String>,
      successType: Type,
      customAdapterFactory: JsonAdapter.Factory
    ): Set<ResponseConfig> =
      stepNames.flatMap { unmarshallSuccessResponse(it, successType, customAdapterFactory) }.toSet()

    @JvmStatic
    fun unmarshallSuccessResponse(
      postTxnStepPick: PostTxnStepPick,
      successType: Type,
      customAdapterFactory: JsonAdapter.Factory
    ): Set<ResponseConfig> =
      setOf(ResponseConfig(right(postTxnStepPick), true, successType, right(customAdapterFactory)))

    @JvmStatic
    fun unmarshallErrorResponse(
      stepName: String,
      successType: Type,
    ): Set<ResponseConfig> = setOf(ResponseConfig(left(stepName), false, successType))

    @JvmStatic
    fun unmarshallErrorResponse(
      stepNames: Set<String>,
      successType: Type,
    ): Set<ResponseConfig> = stepNames.flatMap { unmarshallErrorResponse(it, successType) }.toSet()

    @JvmStatic
    fun unmarshallErrorResponse(
      postTxnStepPick: PostTxnStepPick,
      successType: Type,
    ): Set<ResponseConfig> = setOf(ResponseConfig(right(postTxnStepPick), false, successType))

    @JvmStatic
    fun unmarshallErrorResponse(
      stepName: String,
      successType: Type,
      customAdapter: JsonAdapter<Any>
    ): Set<ResponseConfig> =
      setOf(ResponseConfig(left(stepName), false, successType, left(customAdapter)))

    @JvmStatic
    fun unmarshallErrorResponse(
      stepNames: Set<String>,
      successType: Type,
      customAdapter: JsonAdapter<Any>
    ): Set<ResponseConfig> =
      stepNames.flatMap { unmarshallErrorResponse(it, successType, customAdapter) }.toSet()

    @JvmStatic
    fun unmarshallErrorResponse(
      postTxnStepPick: PostTxnStepPick,
      successType: Type,
      customAdapter: JsonAdapter<Any>
    ): Set<ResponseConfig> =
      setOf(ResponseConfig(right(postTxnStepPick), false, successType, left(customAdapter)))

    @JvmStatic
    fun unmarshallErrorResponse(
      stepName: String,
      successType: Type,
      customAdapterFactory: JsonAdapter.Factory
    ): Set<ResponseConfig> =
      setOf(ResponseConfig(left(stepName), false, successType, right(customAdapterFactory)))

    @JvmStatic
    fun unmarshallErrorResponse(
      stepNames: Set<String>,
      successType: Type,
      customAdapterFactory: JsonAdapter.Factory
    ): Set<ResponseConfig> =
      stepNames.flatMap { unmarshallErrorResponse(it, successType, customAdapterFactory) }.toSet()

    @JvmStatic
    fun unmarshallErrorResponse(
      postTxnStepPick: PostTxnStepPick,
      successType: Type,
      customAdapterFactory: JsonAdapter.Factory
    ): Set<ResponseConfig> =
      setOf(ResponseConfig(right(postTxnStepPick), false, successType, right(customAdapterFactory)))

    @JvmStatic
    fun validateIfSuccess(
      stepName: String,
      successType: Type,
      validationConfig: ValidationConfig<*, *>,
    ): Set<ResponseConfig> =
      setOf(ResponseConfig(left(stepName), true, successType, null, validationConfig))

    @JvmStatic
    fun validateIfSuccess(
      stepNames: Set<String>,
      successType: Type,
      validationConfig: ValidationConfig<*, *>,
    ): Set<ResponseConfig> =
      stepNames.flatMap { validateIfSuccess(it, successType, validationConfig) }.toSet()

    @JvmStatic
    fun validateIfSuccess(
      postTxnStepPick: PostTxnStepPick,
      successType: Type,
      validationConfig: ValidationConfig<*, *>,
    ): Set<ResponseConfig> =
      setOf(ResponseConfig(right(postTxnStepPick), true, successType, null, validationConfig))

    @JvmStatic
    fun validateIfSuccess(
      stepName: String,
      successType: Type,
      validationConfig: ValidationConfig<*, *>,
      customAdapter: JsonAdapter<Any>
    ): Set<ResponseConfig> =
      setOf(
        ResponseConfig(left(stepName), true, successType, left(customAdapter), validationConfig)
      )

    @JvmStatic
    fun validateIfSuccess(
      stepNames: Set<String>,
      successType: Type,
      validationConfig: ValidationConfig<*, *>,
      customAdapter: JsonAdapter<Any>
    ): Set<ResponseConfig> =
      stepNames
        .flatMap { validateIfSuccess(it, successType, validationConfig, customAdapter) }
        .toSet()

    @JvmStatic
    fun validateIfSuccess(
      postTxnStepPick: PostTxnStepPick,
      successType: Type,
      validationConfig: ValidationConfig<*, *>,
      customAdapter: JsonAdapter<Any>
    ): Set<ResponseConfig> =
      setOf(
        ResponseConfig(
          right(postTxnStepPick),
          true,
          successType,
          left(customAdapter),
          validationConfig
        )
      )

    @JvmStatic
    fun validateIfSuccess(
      stepName: String,
      successType: Type,
      validationConfig: ValidationConfig<*, *>,
      customAdapterFactory: JsonAdapter.Factory
    ): Set<ResponseConfig> =
      setOf(
        ResponseConfig(
          left(stepName),
          true,
          successType,
          right(customAdapterFactory),
          validationConfig
        ),
      )

    @JvmStatic
    fun validateIfSuccess(
      stepNames: Set<String>,
      successType: Type,
      validationConfig: ValidationConfig<*, *>,
      customAdapterFactory: JsonAdapter.Factory
    ): Set<ResponseConfig> =
      stepNames
        .flatMap { validateIfSuccess(it, successType, validationConfig, customAdapterFactory) }
        .toSet()

    @JvmStatic
    fun validateIfSuccess(
      postTxnStepPick: PostTxnStepPick,
      successType: Type,
      validationConfig: ValidationConfig<*, *>,
      customAdapterFactory: JsonAdapter.Factory
    ): Set<ResponseConfig> =
      setOf(
        ResponseConfig(
          right(postTxnStepPick),
          true,
          successType,
          right(customAdapterFactory),
          validationConfig
        ),
      )

    @JvmStatic
    fun validateIfFailed(
      stepName: String,
      errorType: Type,
      validationConfig: ValidationConfig<*, *>,
    ): Set<ResponseConfig> =
      setOf(ResponseConfig(left(stepName), false, errorType, null, validationConfig))

    @JvmStatic
    fun validateIfFailed(
      stepNames: Set<String>,
      errorType: Type,
      validationConfig: ValidationConfig<*, *>,
    ): Set<ResponseConfig> =
      stepNames.flatMap { validateIfFailed(it, errorType, validationConfig) }.toSet()

    @JvmStatic
    fun validateIfFailed(
      postTxnStepPick: PostTxnStepPick,
      errorType: Type,
      validationConfig: ValidationConfig<*, *>,
    ): Set<ResponseConfig> =
      setOf(ResponseConfig(right(postTxnStepPick), false, errorType, null, validationConfig))

    @JvmStatic
    fun validateIfFailed(
      stepName: String,
      errorType: Type,
      validationConfig: ValidationConfig<*, *>,
      customAdapter: JsonAdapter<Any>
    ): Set<ResponseConfig> =
      setOf(ResponseConfig(left(stepName), false, errorType, left(customAdapter), validationConfig))

    @JvmStatic
    fun validateIfFailed(
      stepNames: Set<String>,
      errorType: Type,
      validationConfig: ValidationConfig<*, *>,
      customAdapter: JsonAdapter<Any>
    ): Set<ResponseConfig> =
      stepNames.flatMap { validateIfFailed(it, errorType, validationConfig, customAdapter) }.toSet()

    @JvmStatic
    fun validateIfFailed(
      postTxnStepPick: PostTxnStepPick,
      errorType: Type,
      validationConfig: ValidationConfig<*, *>,
      customAdapter: JsonAdapter<Any>
    ): Set<ResponseConfig> =
      setOf(
        ResponseConfig(
          right(postTxnStepPick),
          false,
          errorType,
          left(customAdapter),
          validationConfig
        )
      )

    @JvmStatic
    fun validateIfFailed(
      stepName: String,
      errorType: Type,
      validationConfig: ValidationConfig<*, *>,
      customAdapterFactory: JsonAdapter.Factory
    ): Set<ResponseConfig> =
      setOf(
        ResponseConfig(
          left(stepName),
          false,
          errorType,
          right(customAdapterFactory),
          validationConfig
        ),
      )

    @JvmStatic
    fun validateIfFailed(
      stepNames: Set<String>,
      errorType: Type,
      validationConfig: ValidationConfig<*, *>,
      customAdapterFactory: JsonAdapter.Factory
    ): Set<ResponseConfig> =
      stepNames
        .flatMap { validateIfFailed(it, errorType, validationConfig, customAdapterFactory) }
        .toSet()

    @JvmStatic
    fun validateIfFailed(
      postTxnStepPick: PostTxnStepPick,
      errorType: Type,
      validationConfig: ValidationConfig<*, *>,
      customAdapterFactory: JsonAdapter.Factory
    ): Set<ResponseConfig> =
      setOf(
        ResponseConfig(
          right(postTxnStepPick),
          false,
          errorType,
          right(customAdapterFactory),
          validationConfig
        ),
      )
  }
}
