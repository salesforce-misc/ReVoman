package org.revcloud.revoman.input

import com.salesforce.vador.config.base.BaseValidationConfig.BaseValidationConfigBuilder
import org.immutables.value.Value
import org.revcloud.revoman.input.DynamicEnvironmentKeys.BEARER_TOKEN_KEY
import java.lang.reflect.Type

@Config
@Value.Immutable
internal interface KickDef {
  fun templatePath(): String

  @SkipNulls
  fun runOnlySteps(): Set<String>

  @SkipNulls
  fun skipSteps(): Set<String>

  fun environmentPath(): String?

  @SkipNulls
  fun dynamicEnvironment(): Map<String, String>

  @Value.Default
  fun bearerTokenKey(): String? = BEARER_TOKEN_KEY

  fun haltOnAnyFailure(): Boolean?

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
  
  @Value.Default
  fun insecureHttp(): Boolean = false
}

class SuccessConfig private constructor(val successType: Type, val validationConfig: BaseValidationConfigBuilder<out Any, out Any?, *, *>? = null) {
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
