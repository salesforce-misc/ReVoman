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
import org.revcloud.revoman.input.Kick
import org.revcloud.revoman.input.SuccessConfig
import org.revcloud.revoman.internal.adapters.RegexAdapterFactory
import org.revcloud.revoman.internal.asA
import org.revcloud.revoman.internal.deepFlattenItems
import org.revcloud.revoman.internal.executeTestScriptJs
import org.revcloud.revoman.internal.initMoshi
import org.revcloud.revoman.internal.isContentTypeApplicationJson
import org.revcloud.revoman.internal.postman.initPmEnvironment
import org.revcloud.revoman.internal.postman.pm
import org.revcloud.revoman.internal.postman.state.Item
import org.revcloud.revoman.internal.postman.state.Steps
import org.revcloud.revoman.internal.prepareHttpClient
import org.revcloud.revoman.internal.readTextFromFile
import org.revcloud.revoman.output.Rundown
import org.revcloud.revoman.output.StepReport
import java.lang.reflect.Type
import java.util.Collections

object ReVoman {
  @JvmStatic
  fun revUp(kick: Kick): Rundown = revUp(
    kick.templatePath(),
    kick.runOnlySteps(),
    kick.skipSteps(),
    kick.environmentPath(),
    kick.dynamicEnvironment(),
    kick.bearerTokenKey(),
    kick.haltOnAnyFailure(),
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
    runOnlySteps: Set<String>,
    skipSteps: Set<String>,
    environmentPath: String?,
    dynamicEnvironment: Map<String, String?>,
    bearerTokenKey: String?,
    haltOnAnyFailure: Boolean?,
    stepNameToSuccessConfig: Map<String, SuccessConfig>,
    stepNameToErrorType: Map<String, Type>,
    customAdaptersForResponse: List<Any>,
    typesInResponseToIgnore: Set<Class<out Any>>,
    insecureHttp: Boolean,
  ): Rundown {
    // ! TODO 18/06/23 gopala.akshintala: Add some more require conditions and Move to a separate component Config validation 
    require(Collections.disjoint(runOnlySteps, skipSteps)) { "runOnlySteps and skipSteps cannot be intersected" }
    initPmEnvironment(dynamicEnvironment, environmentPath)
    val pmSteps: Steps? = Moshi.Builder().build().adapter<Steps>().fromJson(readTextFromFile(pmTemplatePath))
    val replaceRegexWithEnvAdapter = Moshi.Builder().add(RegexAdapterFactory(pm.environment)).build().adapter<Item>()
    val moshi = initMoshi(customAdaptersForResponse, typesInResponseToIgnore)
    var noFailure = true
    val stepNameToReport = pmSteps?.item?.deepFlattenItems()?.asSequence()
      ?.takeWhile { haltOnAnyFailure?.let { it && noFailure } ?: true }
      ?.map { stepWithRegex -> replaceRegexWithEnvAdapter.fromJsonValue(stepWithRegex) }
      ?.filterNotNull()
      ?.filter { (runOnlySteps.isEmpty() && skipSteps.isEmpty()) || (runOnlySteps.isNotEmpty() && runOnlySteps.contains(it.name)) || (skipSteps.isNotEmpty() && !skipSteps.contains(it.name)) }
      ?.map { step ->
        // * NOTE gopala.akshintala 06/08/22: Preparing for each step, as there can be intermediate auths
        logger.info { "***** Processing Step: ${step.name} *****" }
        val httpClient: HttpHandler = prepareHttpClient(pm.environment[bearerTokenKey], insecureHttp)
        val request = step.request.toHttpRequest()
        val response: Response = runCatching { httpClient(request) }.getOrElse {
          noFailure = false
          return@map step.name to StepReport(request, httpError = it)
        }
        if (response.status.successful) {
          val testScriptJsResult = runCatching { executeTestScriptJs(step, response) }
          if (testScriptJsResult.isFailure) {
            noFailure = false
            logger.error(testScriptJsResult.exceptionOrNull()) { "Error while executing test script" }
          }
          if (isContentTypeApplicationJson(response)) {
            val successConfig = stepNameToSuccessConfig[step.name]
            val successType = successConfig?.successType ?: Any::class.java as Type
            val responseObj = moshi.asA<Any>(response.bodyString(), successType)
            val validationResult = successConfig?.validationConfig?.let { validate(responseObj, it.prepare()) }
            step.name to StepReport(request, responseObj, responseObj.javaClass, response, testScriptJsResult.exceptionOrNull(), validationError = validationResult)
          } else {
            // ! TODO gopala.akshintala 04/08/22: Support other content types apart from JSON
            step.name to StepReport(request, response.bodyString(), String::class.java, response, testScriptJsError = testScriptJsResult.exceptionOrNull())
          }
        } else {
          noFailure = false
          logger.error { "Request failed for step: ${step.name}" }
          if (stepNameToSuccessConfig.containsKey(step.name)) {
            val errorMsg = "Unable to validate due to unsuccessful response: $response"
            logger.error { errorMsg }
            step.name to StepReport(request, response.bodyString(), String::class.java, response, validationError = errorMsg)
          }
          if (stepNameToErrorType.containsKey(step.name)) {
            val errorType = stepNameToErrorType[step.name]?.rawType?.kotlin ?: Any::class
            val errorResponseObj = moshi.asA(response.bodyString(), errorType)
            step.name to StepReport(request, errorResponseObj, errorResponseObj.javaClass, response)
          }
          step.name to StepReport(request, response.bodyString(), String::class.java, response)
        }
      }?.toMap() ?: emptyMap()
    return Rundown(stepNameToReport, pm.environment)
  }

  // ! TODO gopala.akshintala 03/08/22: Extend the validation for other configs and strategies
  private fun validate(responseObj: Any, validationConfig: BaseValidationConfig<out Any, out Any>?): Any? =
    if (validationConfig != null) {
      val result = Vador.validateAndFailFast(responseObj, validationConfig as ValidationConfig<Any, Any?>)
      result.orElse(null)
    } else null

}
