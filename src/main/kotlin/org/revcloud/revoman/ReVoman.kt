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
import org.revcloud.revoman.input.HookType
import org.revcloud.revoman.input.HookType.POST
import org.revcloud.revoman.input.HookType.PRE
import org.revcloud.revoman.input.Kick
import org.revcloud.revoman.input.SuccessConfig
import org.revcloud.revoman.internal.adapters.RegexAdapterFactory
import org.revcloud.revoman.internal.asA
import org.revcloud.revoman.internal.deepFlattenItems
import org.revcloud.revoman.internal.executeTestScriptJs
import org.revcloud.revoman.internal.getHookForStep
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
import java.util.function.Consumer

private const val FOLDER_DELIMITER = "|>"

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
    kick.hooks(),
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
    hooks: Map<Pair<String, HookType>, Consumer<Rundown>>,
    stepNameToSuccessConfig: Map<String, SuccessConfig>,
    stepNameToErrorType: Map<String, Type>,
    customAdaptersForResponse: List<Any>,
    typesInResponseToIgnore: Set<Class<out Any>>,
    insecureHttp: Boolean,
  ): Rundown {
    // ! TODO 18/06/23 gopala.akshintala: Add some more require conditions and Move to a separate component Config validation
    // ! TODO 22/06/23 gopala.akshintala: Validate if validation config for a step is mentioned but the stepName is not present
    require(Collections.disjoint(runOnlySteps, skipSteps)) { "runOnlySteps and skipSteps cannot be intersected" }
    initPmEnvironment(dynamicEnvironment, environmentPath)
    val pmSteps: Steps? = Moshi.Builder().build().adapter<Steps>().fromJson(readTextFromFile(pmTemplatePath))
    val replaceRegexWithEnvAdapter = Moshi.Builder().add(RegexAdapterFactory(pm.environment)).build().adapter<Item>()
    val moshi = initMoshi(customAdaptersForResponse, typesInResponseToIgnore)
    var noFailure = true
    val allItems = pmSteps?.item?.deepFlattenItems() ?: return Rundown()
    // ! TODO 22/06/23 gopala.akshintala: Validate if steps with same name are used in config
    val stepNameToReport = allItems.asSequence()
      .takeWhile { haltOnAnyFailure?.let { it && noFailure } ?: true }
      .filter { (runOnlySteps.isEmpty() && skipSteps.isEmpty()) || (runOnlySteps.isNotEmpty() && runOnlySteps.contains(it["name"])) || (skipSteps.isNotEmpty() && !skipSteps.contains(it["name"])) }
      .fold<Map<String, Any>, Map<String, StepReport>>(mapOf()) { stepNameToReport, itemWithRegex ->
        getHookForStep(hooks, itemWithRegex["name"] as String, PRE)?.accept(Rundown(stepNameToReport, pm.environment))
        // ! TODO 22/06/23 gopala.akshintala: Improve Perf for this regex replacement
        val step = replaceRegexWithEnvAdapter.fromJsonValue(itemWithRegex) ?: return@fold stepNameToReport
        logger.info { "***** Processing Step: ${step.name} *****" }
        // * NOTE gopala.akshintala 06/08/22: Preparing httpClient for each step, as there can be intermediate auths
        val httpClient: HttpHandler = prepareHttpClient(pm.environment[bearerTokenKey], insecureHttp)
        val request = step.request.toHttpRequest()
        val response: Response = runCatching { httpClient(request) }.getOrElse { throwable ->
          noFailure = false
          return@fold stepNameToReport + (step.name to StepReport(request, httpFailure = throwable))
        }
        val stepReport = if (response.status.successful) {
          val testScriptJsResult = runCatching { executeTestScriptJs(step, response) }
          if (testScriptJsResult.isFailure) {
            noFailure = false
            logger.error(testScriptJsResult.exceptionOrNull()) { "Error while executing test script" }
          }
          if (isContentTypeApplicationJson(response)) {
            val successConfig = stepNameToSuccessConfig[step.name] ?: stepNameToSuccessConfig[step.name.substringAfterLast(FOLDER_DELIMITER)]
            val successType = successConfig?.successType ?: Any::class.java as Type
            val responseObj = moshi.asA<Any>(response.bodyString(), successType)
            val validationResult = successConfig?.validationConfig?.let { validate(responseObj, it.prepare()) }
            StepReport(request, responseObj, responseObj.javaClass, response, testScriptJsResult.exceptionOrNull(), validationFailure = validationResult)
          } else {
            // ! TODO gopala.akshintala 04/08/22: Support other content types apart from JSON
            StepReport(request, response.bodyString(), String::class.java, response, testScriptJsFailure = testScriptJsResult.exceptionOrNull())
          }
        } else {
          noFailure = false
          logger.error { "Request failed for step: ${step.name}" }
          if (stepNameToErrorType.containsKey(step.name) || stepNameToErrorType.containsKey(step.name.substringAfterLast(FOLDER_DELIMITER))) {
            val errorType = stepNameToErrorType[step.name]?.rawType?.kotlin ?: Any::class
            val errorResponseObj = moshi.asA(response.bodyString(), errorType)
            StepReport(request, errorResponseObj, errorResponseObj.javaClass, response)
          } else if (stepNameToSuccessConfig.containsKey(step.name) || stepNameToSuccessConfig.containsKey(step.name.substringAfterLast(FOLDER_DELIMITER))) {
            val errorMsg = "Unable to validate due to unsuccessful response: $response"
            logger.error { errorMsg }
            StepReport(request, response.bodyString(), String::class.java, response, validationFailure = errorMsg)
          } else {
            StepReport(request, response.bodyString(), String::class.java, response)
          }
        }
        (stepNameToReport + (step.name to stepReport)).also { getHookForStep(hooks, step.name, POST)?.accept(Rundown(it, pm.environment)) }
      }
    return Rundown(stepNameToReport, pm.environment)
  }

  // ! TODO gopala.akshintala 03/08/22: Extend the validation for other configs and strategies
  private fun validate(responseObj: Any, validationConfig: BaseValidationConfig<out Any, out Any>?): Any? =
    if (validationConfig != null) {
      val result = Vador.validateAndFailFast(responseObj, validationConfig as ValidationConfig<Any, Any?>)
      result.orElse(null)
    } else null

}
