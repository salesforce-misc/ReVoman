package org.revcloud.revoman.input

import com.salesforce.vador.config.base.BaseValidationConfig.BaseValidationConfigBuilder
import org.immutables.value.Value
import org.immutables.value.Value.Style.ImplementationVisibility.PUBLIC
import org.revcloud.revoman.output.Rundown
import java.lang.reflect.Type
import java.util.function.Consumer

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

  @SkipNulls
  fun customDynamicVariables(): Map<String, (String) -> String>

  fun bearerTokenKey(): String?
  
  @SkipNulls
  fun haltOnAnyFailureExceptForSteps(): Set<String>
  
  @SkipNulls
  fun hooks(): Map<Pair<String, HookType>, Consumer<Rundown>>

  // ! FIXME 25/06/23 gopala.akshintala: Not in-use 
  @Value.Default
  fun validationStrategy(): ValidationStrategy = ValidationStrategy.FAIL_FAST

  @SkipNulls
  fun stepNameToSuccessConfig(): Map<String, SuccessConfig>
  
  @SkipNulls
  fun stepNameToErrorType(): Map<String, Type>

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
    fun successType(successType: Type): SuccessConfig = SuccessConfig(successType, null)

    @JvmStatic
    fun validateIfSuccess(successType: Type, validationConfig: BaseValidationConfigBuilder<out Any, out Any?, *, *>): SuccessConfig = SuccessConfig(successType, validationConfig)
  }
}

enum class HookType {
  PRE, POST, REQUEST_SUCCESS, REQUEST_FAILURE, TEST_SCRIPT_JS_FAILURE
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
