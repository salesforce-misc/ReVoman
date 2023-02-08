package org.revcloud.revoman.input

import com.salesforce.vador.config.base.BaseValidationConfig.BaseValidationConfigBuilder
import org.immutables.value.Value
import org.jetbrains.annotations.Nullable
import org.revcloud.revoman.postman.DynamicEnvironmentKeys.BEARER_TOKEN_KEY
import java.lang.reflect.Type

@Config
@Value.Immutable
internal interface KickDef {
  fun templatePath(): String

  @Nullable
  fun environmentPath(): String?

  @SkipNulls
  fun dynamicEnvironment(): Map<String, String>

  @Value.Default
  fun bearerTokenKey(): String? = BEARER_TOKEN_KEY

  @SkipNulls
  fun stepNameToSuccessType(): Map<String, Type>

  @SkipNulls
  fun stepNameToErrorType(): Map<String, Type>

  @Value.Default
  fun validationStrategy(): ValidationStrategy = ValidationStrategy.FAIL_FAST

  @SkipNulls
  fun stepNameToValidationConfig(): Map<String, BaseValidationConfigBuilder<out Any, out Any?, *, *>>

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
