package org.revcloud.revoman.input

import com.salesforce.vador.config.base.BaseValidationConfig
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
  fun stepNameToErrorType(): Map<String, Type>

  @Value.Default
  fun validationStrategy(): ValidationStrategy = ValidationStrategy.FAIL_FAST

  // ! TODO 08/02/23 gopala.akshintala: Mandate passing type if not passed from stepNameToSuccessType 
  @SkipNulls
  fun stepNameToSuccessConfig(): Map<String, SuccessConfig>

  @SkipNulls
  fun customAdaptersForResponse(): List<Any>

  @SkipNulls
  fun typesInResponseToIgnore(): Set<Class<out Any>>
}

internal class SuccessConfig private constructor(successType: Type, validationConfig: BaseValidationConfigBuilder<out Any, out Any?, *, *>? = null) {
  val successType: Type = successType
  var validationConfig: BaseValidationConfigBuilder<out Any, out Any?, *, *>? = validationConfig
  companion object {
    @JvmStatic
    fun successType(successType: Type): SuccessConfig {
      return SuccessConfig(successType, null)
    }

    @JvmStatic
    fun validateIfSuccess(successType: Type, validationConfig: BaseValidationConfigBuilder<out Any, out Any?, *, *>): SuccessConfig {
      return SuccessConfig(successType, validationConfig)
    }
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
  depluralize = true,
  add = "",
  visibility = Value.Style.ImplementationVisibility.PUBLIC
)
private annotation class Config

private annotation class SkipNulls