package org.revcloud.revoman

import com.salesforce.vador.config.ValidationConfig
import com.salesforce.vador.config.base.BaseValidationConfig
import com.salesforce.vador.execution.Vador
import com.squareup.moshi.Moshi
import com.squareup.moshi.adapter
import com.squareup.moshi.rawType
import java.lang.reflect.Type
import java.util.*
import java.util.function.Consumer
import mu.KotlinLogging
import org.http4k.core.HttpHandler
import org.http4k.core.Response
import org.revcloud.revoman.input.HookType
import org.revcloud.revoman.input.HookType.POST
import org.revcloud.revoman.input.HookType.PRE
import org.revcloud.revoman.input.Kick
import org.revcloud.revoman.input.SuccessConfig
import org.revcloud.revoman.internal.asA
import org.revcloud.revoman.internal.deepFlattenItems
import org.revcloud.revoman.internal.executeTestScriptJs
import org.revcloud.revoman.internal.filterStep
import org.revcloud.revoman.internal.forStepName
import org.revcloud.revoman.internal.getHookForStep
import org.revcloud.revoman.internal.initMoshi
import org.revcloud.revoman.internal.isContentTypeApplicationJson
import org.revcloud.revoman.internal.isStepNameInPassList
import org.revcloud.revoman.internal.isStepNamePresent
import org.revcloud.revoman.internal.postman.RegexReplacer
import org.revcloud.revoman.internal.postman.initPmEnvironment
import org.revcloud.revoman.internal.postman.pm
import org.revcloud.revoman.internal.postman.postManVariableRegex
import org.revcloud.revoman.internal.postman.state.Item
import org.revcloud.revoman.internal.postman.state.Template
import org.revcloud.revoman.internal.prepareHttpClient
import org.revcloud.revoman.internal.readTextFromFile
import org.revcloud.revoman.output.Rundown
import org.revcloud.revoman.output.StepReport

object ReVoman {
  @JvmStatic
  fun revUp(kick: Kick): Rundown =
    revUp(
      kick.templatePath(),
      kick.runOnlySteps(),
      kick.skipSteps(),
      kick.environmentPath(),
      kick.dynamicEnvironment(),
      kick.customDynamicVariables(),
      kick.bearerTokenKey(),
      kick.haltOnAnyFailureExceptForSteps(),
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
    customDynamicVariables: Map<String, (String) -> String>,
    bearerTokenKeyFromConfig: String?,
    haltOnAnyFailureExceptForSteps: Set<String>,
    hooks: Map<Pair<String, HookType>, Consumer<Rundown>>,
    stepNameToSuccessConfig: Map<String, SuccessConfig>,
    stepNameToErrorType: Map<String, Type>,
    customAdaptersForResponse: List<Any>,
    typesInResponseToIgnore: Set<Class<out Any>>,
    insecureHttp: Boolean,
  ): Rundown {
    // ! TODO 18/06/23 gopala.akshintala: Add some more require conditions and Move to a separate
    // component Config validation
    // ! TODO 22/06/23 gopala.akshintala: Validate if validation config for a step is mentioned but
    // the stepName is not present
    require(Collections.disjoint(runOnlySteps, skipSteps)) {
      "runOnlySteps and skipSteps cannot be intersected"
    }
    initPmEnvironment(environmentPath, dynamicEnvironment, customDynamicVariables)
    val (pmSteps, auth) =
      Moshi.Builder().build().adapter<Template>().fromJson(readTextFromFile(pmTemplatePath))
        ?: return Rundown()
    val bearerTokenKey =
      bearerTokenKeyFromConfig
        ?: auth?.bearer?.firstOrNull()?.value?.let {
          postManVariableRegex.find(it)?.groups?.get("variableKey")?.value ?: ""
        }
    val moshiReVoman = initMoshi(customAdaptersForResponse, typesInResponseToIgnore)
    var noFailure = true
    // ! TODO 22/06/23 gopala.akshintala: Validate if steps with same name are used in config
    val stepNameToReport =
      pmSteps
        .deepFlattenItems()
        .asSequence()
        .takeWhile { noFailure }
        .filter { filterStep(runOnlySteps, skipSteps, it.name) }
        .fold<Item, Map<String, StepReport>>(mapOf()) { stepNameToReport, itemWithRegex ->
          val stepName = itemWithRegex.name
          logger.info { "***** Processing Step: $stepName *****" }
          getHookForStep(hooks, stepName, PRE)?.accept(Rundown(stepNameToReport, pm.environment))
          // * NOTE gopala.akshintala 06/08/22: Preparing httpClient for each step,
          // * as there can be intermediate auths
          val httpClient: HttpHandler =
            prepareHttpClient(pm.environment[bearerTokenKey], insecureHttp)
          val pmRequest =
            RegexReplacer(pm.environment, customDynamicVariables)
              .replaceRegex(itemWithRegex.request)
          val request = pmRequest.toHttpRequest()
          val response: Response =
            runCatching { httpClient(request) }
              .getOrElse { throwable ->
                noFailure = isStepNameInPassList(stepName, haltOnAnyFailureExceptForSteps)
                return@fold stepNameToReport +
                  (stepName to StepReport(request, httpFailure = throwable))
              }
          val stepReport: StepReport =
            when {
              response.status.successful -> {
                val testScriptJsResult = runCatching {
                  executeTestScriptJs(pmRequest, itemWithRegex.event, response)
                }
                if (testScriptJsResult.isFailure) {
                  logger.error(testScriptJsResult.exceptionOrNull()) {
                    "Error while executing test script"
                  }
                  noFailure = isStepNameInPassList(stepName, haltOnAnyFailureExceptForSteps)
                }
                when {
                  isContentTypeApplicationJson(response) -> {
                    val successConfig: SuccessConfig? =
                      stepNameToSuccessConfig.forStepName(stepName)
                    val successType = successConfig?.successType ?: Any::class.java as Type
                    val responseObj = moshiReVoman.asA<Any>(response.bodyString(), successType)
                    val validationResult =
                      successConfig?.validationConfig?.let { validate(responseObj, it.prepare()) }
                    StepReport(
                      request,
                      responseObj,
                      responseObj.javaClass,
                      response,
                      testScriptJsResult.exceptionOrNull(),
                      validationFailure = validationResult
                    )
                  }
                  else -> {
                    // ! TODO gopala.akshintala 04/08/22: Support other non-JSON content types
                    StepReport(
                      request,
                      response.bodyString(),
                      String::class.java,
                      response,
                      testScriptJsFailure = testScriptJsResult.exceptionOrNull()
                    )
                  }
                }
              }
              else -> {
                logger.error { "Request failed for step: $stepName" }
                noFailure = isStepNameInPassList(stepName, haltOnAnyFailureExceptForSteps)
                when {
                  stepNameToErrorType.isStepNamePresent(stepName) -> {
                    val errorType = stepNameToErrorType[stepName]?.rawType?.kotlin ?: Any::class
                    val errorResponseObj = moshiReVoman.asA(response.bodyString(), errorType)
                    StepReport(request, errorResponseObj, errorResponseObj.javaClass, response)
                  }
                  stepNameToSuccessConfig.isStepNamePresent(stepName) -> {
                    val errorMsg = "Unable to validate due to unsuccessful response: $response"
                    logger.error { errorMsg }
                    StepReport(
                      request,
                      response.bodyString(),
                      String::class.java,
                      response,
                      validationFailure = errorMsg
                    )
                  }
                  else -> StepReport(request, response.bodyString(), String::class.java, response)
                }
              }
            }
          (stepNameToReport + (stepName to stepReport)).also {
            getHookForStep(hooks, stepName, POST)?.accept(Rundown(it, pm.environment))
          }
        }
    return Rundown(stepNameToReport, pm.environment)
  }

  // ! TODO gopala.akshintala 03/08/22: Extend the validation for other configs and strategies
  private fun validate(
    responseObj: Any,
    validationConfig: BaseValidationConfig<out Any, out Any>?
  ): Any? =
    if (validationConfig != null) {
      val result =
        Vador.validateAndFailFast(responseObj, validationConfig as ValidationConfig<Any, Any?>)
      result.orElse(null)
    } else null
}
