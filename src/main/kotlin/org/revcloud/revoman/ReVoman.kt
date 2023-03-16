@file:JvmName("ReVoman")

package org.revcloud.revoman

import com.salesforce.vador.config.ValidationConfig
import com.salesforce.vador.config.base.BaseValidationConfig
import com.salesforce.vador.execution.Vador
import com.squareup.moshi.Moshi
import com.squareup.moshi.adapter
import com.squareup.moshi.rawType
import mu.KotlinLogging
import org.http4k.core.HttpHandler
import org.http4k.core.Response
import org.revcloud.revoman.internal.adapters.RegexAdapterFactory
import org.revcloud.revoman.input.Kick
import org.revcloud.revoman.input.SuccessConfig
import org.revcloud.revoman.internal.asA
import org.revcloud.revoman.internal.deepFlattenItems
import org.revcloud.revoman.internal.executeTestScriptJs
import org.revcloud.revoman.internal.initMoshi
import org.revcloud.revoman.internal.postman.initPmEnvironment
import org.revcloud.revoman.internal.isContentTypeApplicationJson
import org.revcloud.revoman.internal.postman.pm
import org.revcloud.revoman.internal.prepareHttpClient
import org.revcloud.revoman.internal.readTextFromFile
import org.revcloud.revoman.output.Rundown
import org.revcloud.revoman.output.StepReport
import org.revcloud.revoman.internal.postman.state.Item
import org.revcloud.revoman.internal.postman.state.Steps
import java.lang.reflect.Type

object ReVoman {
  @JvmStatic
  fun revUp(kick: Kick): Rundown = revUp(
    kick.templatePath(),
    kick.environmentPath(),
    kick.dynamicEnvironment(),
    kick.bearerTokenKey(),
    kick.stepNameToSuccessConfig(),
    kick.stepNameToErrorType(),
    kick.customAdaptersForResponse(),
    kick.typesInResponseToIgnore(),
    kick.insecureHttp(),
  )

  private val logger = KotlinLogging.logger {}

  @OptIn(ExperimentalStdlibApi::class)
  private fun revUp(
    pmTemplatePath: String,
    environmentPath: String?,
    dynamicEnvironment: Map<String, String?>,
    bearerTokenKey: String?,
    stepNameToSuccessConfig: Map<String, SuccessConfig>,
    stepNameToErrorType: Map<String, Type>,
    customAdaptersForResponse: List<Any>,
    typesInResponseToIgnore: Set<Class<out Any>>,
    insecureHttp: Boolean?,
  ): Rundown {
    initPmEnvironment(dynamicEnvironment, environmentPath)
    val pmSteps: Steps? = Moshi.Builder().build().adapter<Steps>().fromJson(readTextFromFile(pmTemplatePath))
    val replaceRegexWithEnvAdapter = Moshi.Builder().add(RegexAdapterFactory(pm.environment)).build().adapter<Item>()
    val moshi = initMoshi(customAdaptersForResponse, typesInResponseToIgnore)
    val stepNameToReport = pmSteps?.item?.deepFlattenItems()?.asSequence()
      ?.map { stepWithRegex -> replaceRegexWithEnvAdapter.fromJsonValue(stepWithRegex) }
      ?.filterNotNull()
      ?.map { step ->
        // * NOTE gopala.akshintala 06/08/22: Preparing for each step, as there can be intermediate auths
        logger.info { "***** Processing Step: ${step.name} *****" }
        val httpClient: HttpHandler = prepareHttpClient(pm.environment[bearerTokenKey], insecureHttp)
        val request = step.request.toHttpRequest()
        val response: Response = httpClient(request)
        if (response.status.successful) {
          val testScriptJsResult = runCatching { executeTestScriptJs(step, response) }
          if (testScriptJsResult.isFailure) {
            logger.error(testScriptJsResult.exceptionOrNull()) { "Error while executing test script" }
          }
          if (isContentTypeApplicationJson(response)) {
            val successConfig = stepNameToSuccessConfig[step.name]
            val successType = successConfig?.successType ?: Any::class.java as Type
            val responseObj = moshi.asA<Any>(response.bodyString(), successType)
            val validationResult = successConfig?.validationConfig?.let { validate(responseObj, it.prepare()) }
            step.name to StepReport(responseObj, responseObj.javaClass, request, response, testScriptJsResult.exceptionOrNull(), validationError = validationResult)
          } else {
            // ! TODO gopala.akshintala 04/08/22: Support other content types apart from JSON
            step.name to StepReport(response.bodyString(), String::class.java, request, response, testScriptJsError = testScriptJsResult.exceptionOrNull())
          }
        } else {
          if (stepNameToSuccessConfig.containsKey(step.name)) {
            logger.error { "Unable to validate due to unsuccessful response status" }
            step.name to StepReport(response.bodyString(), String::class.java, request, response, 
              validationError = "Unable to validate due to unsuccessful response status")
          } 
          if (stepNameToErrorType.containsKey(step.name)) {
            val errorType = stepNameToErrorType[step.name]?.rawType?.kotlin ?: Any::class
            val errorResponseObj = moshi.asA(response.bodyString(), errorType)
            step.name to StepReport(errorResponseObj, errorResponseObj.javaClass, request, response)
          }
          step.name to StepReport(response.bodyString(), String::class.java, request, response)
        }
      }?.toMap() ?: emptyMap()
    return Rundown(stepNameToReport, pm.environment)
  }

  // ! TODO gopala.akshintala 03/08/22: Extend the validation for other configs and strategies
  private fun validate(responseObj: Any, validationConfig: BaseValidationConfig<out Any, out Any>?): Any? {
    if (validationConfig != null) {
      val result = Vador.validateAndFailFast(responseObj, validationConfig as ValidationConfig<Any, Any?>)
      return result.orElse(null)
    }
    return null
  }

}
