@file:JvmName("ReVoman")

package org.revcloud.revoman

import com.salesforce.vador.config.ValidationConfig
import com.salesforce.vador.config.base.BaseValidationConfig
import com.salesforce.vador.config.base.BaseValidationConfig.BaseValidationConfigBuilder
import com.salesforce.vador.execution.Vador
import com.squareup.moshi.Moshi
import com.squareup.moshi.adapter
import com.squareup.moshi.adapters.Rfc3339DateJsonAdapter
import com.squareup.moshi.rawType
import dev.zacsweers.moshix.adapters.AdaptedBy
import dev.zacsweers.moshix.adapters.JsonString
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
import org.http4k.format.ConfigurableMoshi
import org.http4k.format.EventAdapter
import org.http4k.format.ListAdapter
import org.http4k.format.MapAdapter
import org.http4k.format.ThrowableAdapter
import org.http4k.format.asConfigurable
import org.http4k.format.withStandardMappings
import org.revcloud.revoman.adapters.ObjOrListAdapterFactory
import org.revcloud.revoman.adapters.internal.IgnoreUnknownFactory
import org.revcloud.revoman.adapters.internal.RegexAdapterFactory
import org.revcloud.revoman.input.Kick
import org.revcloud.revoman.output.Rundown
import org.revcloud.revoman.output.StepReport
import org.revcloud.revoman.postman.PostmanAPI
import org.revcloud.revoman.postman.state.Environment
import org.revcloud.revoman.postman.state.Item
import org.revcloud.revoman.postman.state.Request
import org.revcloud.revoman.postman.state.Steps
import java.io.File
import java.lang.reflect.Type
import java.util.Date

object ReVoman {
  @JvmStatic
  fun revUp(kick: Kick): Rundown = revUp(
    kick.templatePath(),
    kick.environmentPath(),
    kick.dynamicEnvironment(),
    kick.bearerTokenKey(),
    kick.stepNameToSuccessType(),
    kick.stepNameToValidationConfig(),
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
    stepNameToSuccessType: Map<String, Type>,
    stepNameToValidationConfig: Map<String, BaseValidationConfigBuilder<out Any, out Any?, *, *>>,
    stepNameToErrorType: Map<String, Type>,
    customAdaptersForResponse: List<Any>,
    typesInResponseToIgnore: Set<Class<out Any>>
  ): Rundown {
    initPmEnvironment(dynamicEnvironment, environmentPath)
    val pmSteps: Steps? = Moshi.Builder().build().adapter<Steps>().fromJson(readTextFromFile(pmTemplatePath))
    val replaceRegexWithEnvAdapter = Moshi.Builder().add(RegexAdapterFactory(pm.environment)).build().adapter<Item>()
    val moshi = initMoshi(typesInResponseToIgnore, customAdaptersForResponse)
    val stepNameToReport = pmSteps?.item?.deepFlattenItems()?.asSequence()
      ?.map { stepWithRegex -> replaceRegexWithEnvAdapter.fromJsonValue(stepWithRegex) }
      ?.filterNotNull()
      ?.map { step ->
        // * NOTE gopala.akshintala 06/08/22: Preparing for each step, as there can be intermediate auths
        val httpClient: HttpHandler = prepareHttpClient(pm.environment[bearerTokenKey])
        val request = step.request.toHttpRequest()
        val response: Response = httpClient(request)
        if (response.status.successful) {
          executeTestScriptJs(step, response)
          if (isContentTypeApplicationJson(response)) {
            val validationConfig = stepNameToValidationConfig[step.name]?.prepare()
            val successType =
              (stepNameToSuccessType[step.name]?.rawType ?: validationConfig?.validatableType?.rawType)?.kotlin
                ?: Any::class
            val responseObj = moshi.asA(response.bodyString(), successType)
            validate(responseObj, validationConfig)
            step.name to StepReport(responseObj, responseObj.javaClass, request, response)
          } else {
            // ! TODO gopala.akshintala 04/08/22: Support other content types apart from JSON
            step.name to StepReport(response.bodyString(), String::class.java, request, response)
          }
        } else {
          if (stepNameToValidationConfig.containsKey(step.name)) {
            throw RuntimeException("Unable to validate due to unsuccessful response status: ${response.status}")
          }
          val errorType = stepNameToErrorType[step.name]?.rawType?.kotlin ?: Any::class
          val errorResponseObj = moshi.asA(response.bodyString(), errorType)
          step.name to StepReport(errorResponseObj, errorResponseObj.javaClass, request, response)
        }
      }?.toMap() ?: emptyMap()
    return Rundown(stepNameToReport, pm.environment)
  }

  // ! TODO gopala.akshintala 03/08/22: Extend the validation for other configs and strategies
  private fun validate(responseObj: Any, validationConfig: BaseValidationConfig<out Any, out Any>?) {
    if (validationConfig != null) {
      val result = Vador.validateAndFailFast(responseObj, validationConfig as ValidationConfig<Any, Any?>)
      result.ifPresent { throw RuntimeException(it.toString()) }
      logger.info { "*** Validation passed for: $responseObj" }
    }
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
    loadIntoPmEnvironment(step.request, response)
    val testScript = step.event?.find { it.listen == "test" }?.script?.exec?.joinToString("\n")
    if (!testScript.isNullOrBlank()) {
      val testSource = Source.newBuilder("js", testScript, "pmItemTestScript.js").build()
      jsContext.getBindings("js").putMember("responseBody", response.bodyString())
      try {
        // ! TODO gopala.akshintala 15/05/22: Keep a tab on jsContext mix-up from different steps
        jsContext.eval(testSource)
      } catch (polyglotException: PolyglotException) {
        // ! TODO gopala.akshintala 04/05/22: Handle gracefully?
        throw polyglotException
      }
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

  private fun initMoshi(
    typesToIgnore: Set<Class<out Any>>? = emptySet(),
    customAdaptersForResponse: List<Any>? = emptyList()
  ): ConfigurableMoshi {
    val moshiBuilder = Moshi.Builder()
    customAdaptersForResponse?.forEach { moshiBuilder.add(it) }
    if (!typesToIgnore.isNullOrEmpty()) {
      moshiBuilder.add(IgnoreUnknownFactory(typesToIgnore))
    }
    return object : ConfigurableMoshi(
      moshiBuilder
        .add(JsonString.Factory())
        .add(AdaptedBy.Factory())
        .add(Date::class.java, Rfc3339DateJsonAdapter())
        .addLast(EventAdapter)
        .addLast(ThrowableAdapter)
        .addLast(ListAdapter)
        .addLast(MapAdapter)
        .addLast(ObjOrListAdapterFactory)
        .asConfigurable()
        .withStandardMappings()
        .done()
    ) {}
  }
}
