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
  
}

private annotation class SkipNulls

