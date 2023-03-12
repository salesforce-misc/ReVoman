@file:JvmName("ReVoman")

package org.revcloud.revoman

import com.salesforce.vador.config.ValidationConfig
import com.salesforce.vador.config.base.BaseValidationConfig
import com.salesforce.vador.execution.Vador
import com.squareup.moshi.Moshi
import com.squareup.moshi.adapter
import com.squareup.moshi.rawType
import mu.KotlinLogging
import org.apache.commons.lang3.StringUtils
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.PolyglotException
import org.graalvm.polyglot.Source
import org.http4k.client.JavaHttpClient
import org.http4k.core.ContentType.Companion.APPLICATION_JSON
import org.http4k.core.Filter
import org.http4k.core.HttpHandler
import org.http4k.core.NoOp
import org.http4k.core.Response
import org.http4k.core.then
import org.http4k.filter.ClientFilters
import org.http4k.filter.DebuggingFilters
import org.revcloud.revoman.adapters.internal.RegexAdapterFactory
import org.revcloud.revoman.input.Kick
import org.revcloud.revoman.input.SuccessConfig
import org.revcloud.revoman.moshi.initMoshi
import org.revcloud.revoman.moshi.asA
import org.revcloud.revoman.output.Rundown
import org.revcloud.revoman.output.StepReport
import org.revcloud.revoman.postman.PostmanAPI
import org.revcloud.revoman.postman.state.Environment
import org.revcloud.revoman.postman.state.Item
import org.revcloud.revoman.postman.state.Request
import org.revcloud.revoman.postman.state.Steps
import java.io.File
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
    kick.typesInResponseToIgnore()
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
    typesInResponseToIgnore: Set<Class<out Any>>
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
        val httpClient: HttpHandler = prepareHttpClient(pm.environment[bearerTokenKey])
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

  // ! TODO gopala.akshintala 28/01/23: Use auth type from the collection
  private fun prepareHttpClient(bearerToken: String?) = DebuggingFilters.PrintRequestAndResponse()
    .then(if (bearerToken.isNullOrEmpty()) Filter.NoOp else ClientFilters.BearerAuth(bearerToken))
    .then(JavaHttpClient())

  private val pm = PostmanAPI()
  private val jsContext = buildJsContext(false).also {
    it.getBindings("js").putMember("pm", pm)
    it.getBindings("js").putMember("xml2Json", pm.xml2Json)
  }

  private fun buildJsContext(useCommonjsRequire: Boolean = true): Context {
    val options = buildMap {
      if (useCommonjsRequire) {
        put("js.commonjs-require", "true")
        put("js.commonjs-require-cwd", ".")
        put("js.commonjs-core-modules-replacements", "path:path-browserify")
      }
      put("js.esm-eval-returns-exports", "true")
      put("engine.WarnInterpreterOnly", "false")
    }
    return Context.newBuilder("js")
      .allowExperimentalOptions(true)
      .allowIO(true)
      .options(options)
      .allowHostAccess(HostAccess.ALL)
      .allowHostClassLookup { true }
      .build()
  }

  private fun executeTestScriptJs(
    step: Item,
    response: Response
  ) {
    // ! TODO 12/03/23 gopala.akshintala: Find a way to surface-up what happened in the script, like the Ids set etc 
    loadIntoPmEnvironment(step.request, response)
    val testScript = step.event?.find { it.listen == "test" }?.script?.exec?.joinToString("\n")
    if (!testScript.isNullOrBlank()) {
      val testSource = Source.newBuilder("js", testScript, "pmItemTestScript.js").build()
      jsContext.getBindings("js").putMember("responseBody", response.bodyString())
      // ! TODO gopala.akshintala 15/05/22: Keep a tab on jsContext mix-up from different steps
      jsContext.eval(testSource)
    }
  }

  @OptIn(ExperimentalStdlibApi::class)
  private fun initPmEnvironment(
    dynamicEnvironment: Map<String, String?>?,
    pmEnvironmentPath: String?
  ) {
    // ! TODO gopala.akshintala 19/05/22: Think about clashes between json environment variables and dynamic environment variables
    if (!dynamicEnvironment.isNullOrEmpty()) {
      pm.environment.putAll(dynamicEnvironment)
    }
    if (pmEnvironmentPath != null) {
      val envJsonAdapter = Moshi.Builder().build().adapter<Environment>()
      val environment: Environment? = envJsonAdapter.fromJson(readTextFromFile(pmEnvironmentPath))
      pm.environment.putAll(
        environment?.values?.filter { it.enabled }?.associate { it.key to it.value }
          ?: emptyMap()
      )
    }
  }

  private fun loadIntoPmEnvironment(stepRequest: Request, response: Response) {
    pm.request = stepRequest
    pm.response = org.revcloud.revoman.postman.Response(
      response.status.toString(),
      response.status.code.toString(),
      response.bodyString()
    )
  }

  private fun isContentTypeApplicationJson(response: Response) =
    response.bodyString().isNotBlank() && response.header("content-type")?.let {
      StringUtils.deleteWhitespace(it)
        .equals(StringUtils.deleteWhitespace(APPLICATION_JSON.toHeaderValue()), ignoreCase = true)
    } ?: false

  private fun readTextFromFile(filePath: String): String = File(filePath).readText()

  private fun List<*>.deepFlattenItems(): List<*> =
    this.asSequence().flatMap { item ->
      (item as Map<String, Any>)["item"]?.let { (it as List<*>).deepFlattenItems() } ?: listOf(item)
    }.toList()
  

}
