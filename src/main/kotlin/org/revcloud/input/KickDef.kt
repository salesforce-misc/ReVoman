package org.revcloud.input

import org.immutables.value.Value
import org.jetbrains.annotations.Nullable
import org.revcloud.postman.DynamicEnvironmentKeys.BEARER_TOKEN_KEY
import org.revcloud.vader.runner.config.BaseValidationConfig

@Config
@Value.Immutable
internal interface KickDef {
  fun templatePath(): String

  @Nullable
  fun environmentPath(): String?

  @Value.Default
  fun bearerTokenKey(): String? = BEARER_TOKEN_KEY

  @Value.Default
  fun validationStrategy(): ValidationStrategy = ValidationStrategy.FAIL_FAST

  @SkipNulls
  fun itemNameToSuccessType(): Map<String, Pair<Class<out Any>, BaseValidationConfig<out Any, out Any?>?>>

  @SkipNulls
  fun itemNameToErrorType(): Map<String, Class<out Any>>

  @SkipNulls
  fun dynamicEnvironment(): Map<String, String>

  @SkipNulls
  fun customAdaptersForResponse(): List<Any>

  @SkipNulls
  fun typesInResponseToIgnore(): Set<Class<out Any>>
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
  depluralize = true,
  add = "",
  visibility = Value.Style.ImplementationVisibility.PUBLIC
)
private annotation class Config

private annotation class SkipNulls
