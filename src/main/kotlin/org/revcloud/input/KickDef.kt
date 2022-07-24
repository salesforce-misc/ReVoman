package org.revcloud.input

import org.immutables.value.Value
import org.revcloud.postman.DynamicEnvironmentKeys.BEARER_TOKEN_KEY

@Config
@Value.Immutable
internal interface KickDef {
  fun templatePath(): String
  fun environmentPath(): String?
  @Value.Default
  fun bearerTokenKey(): String? = BEARER_TOKEN_KEY
  @Value.Default
  fun itemNameToOutputType(): Map<String, Class<out Any>>? = emptyMap()
  @Value.Default
  fun dynamicEnvironment(): Map<String, String>? = emptyMap()
  @Value.Default
  fun customAdaptersForResponse(): List<Any>? = emptyList()
  @Value.Default
  fun typesInResponseToIgnore(): Set<Class<out Any>>? = emptySet()
}
