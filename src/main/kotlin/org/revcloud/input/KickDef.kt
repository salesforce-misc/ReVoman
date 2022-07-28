package org.revcloud.input

import org.immutables.value.Value
import org.jetbrains.annotations.Nullable
import org.revcloud.postman.DynamicEnvironmentKeys.BEARER_TOKEN_KEY

@Config
@Value.Immutable
internal interface KickDef {
  fun templatePath(): String

  @Nullable
  fun environmentPath(): String?

  @Value.Default
  fun bearerTokenKey(): String? = BEARER_TOKEN_KEY

  @SkipNulls
  fun itemNameToSuccessType(): Map<String, Class<out Any>>

  @SkipNulls
  fun itemNameToErrorType(): Map<String, Class<out Any>>

  @SkipNulls
  fun dynamicEnvironment(): Map<String, String>

  @SkipNulls
  fun customAdaptersForResponse(): List<Any>

  @SkipNulls
  fun typesInResponseToIgnore(): Set<Class<out Any>>

/*  @Value.Check
  fun check() = when {
    itemNameToSuccessType() == null ->
      throw IllegalArgumentException("itemNameToSuccessType is used to marshall success response of the item to the Success type provided. If provided, it should be a non-null")

    itemNameToErrorType() == null ->
      throw IllegalArgumentException("itemNameToErrorType is used to marshall error response of the item to the Error type provided. If provided, it should be a non-null")

    dynamicEnvironment() == null ->
      throw IllegalArgumentException("dynamicEnvironment is used to supply environment variables that are not part of static environment file. If provided, it should be a non-null")

    customAdaptersForResponse() == null ->
      throw IllegalArgumentException("customAdaptersForResponse is used to supply custom adapters to marshall response. If provided, it should be a non-null")

    typesInResponseToIgnore() == null ->
      throw IllegalArgumentException("typesInResponseToIgnore is used to supply types to ignore while marshalling response. If provided, it should be a non-null")

    else -> Unit
  }*/
  
}

private annotation class SkipNulls

