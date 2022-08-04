@file:JvmName("ReVoman")
@file:Suppress("ktlint:filename")

package org.revcloud

import com.squareup.moshi.Moshi
import com.squareup.moshi.adapter
import com.squareup.moshi.adapters.Rfc3339DateJsonAdapter
import dev.zacsweers.moshix.adapters.AdaptedBy
import dev.zacsweers.moshix.adapters.JsonString
import mu.KotlinLogging
import org.apache.commons.lang3.StringUtils
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.PolyglotException
import org.graalvm.polyglot.Source
import org.http4k.client.JavaHttpClient
import org.http4k.core.*
import org.http4k.core.ContentType.Companion.APPLICATION_JSON
import org.http4k.core.ContentType.Companion.Text
import org.http4k.filter.ClientFilters
import org.http4k.filter.DebuggingFilters
import org.http4k.format.ConfigurableMoshi
import org.http4k.format.EventAdapter
import org.http4k.format.ListAdapter
import org.http4k.format.MapAdapter
import org.http4k.format.ThrowableAdapter
import org.http4k.format.asConfigurable
import org.http4k.format.withStandardMappings
import org.http4k.lens.Header.CONTENT_TYPE
import org.revcloud.adapters.ObjOrListAdapterFactory
import org.revcloud.adapters.internal.IgnoreUnknownFactory
import org.revcloud.adapters.internal.RegexAdapterFactory
import org.revcloud.input.Kick
import org.revcloud.output.Rundown
import org.revcloud.postman.PostmanAPI
import org.revcloud.postman.state.collection.Collection
import org.revcloud.postman.state.collection.Item
import org.revcloud.postman.state.environment.Environment
import org.revcloud.vader.runner.Vader
import org.revcloud.vader.runner.config.BaseValidationConfig
import org.revcloud.vader.runner.config.ValidationConfig
import java.io.File
import java.util.Date

private val logger = KotlinLogging.logger {}
private val pm = PostmanAPI()
private val jsContext = buildJsContext(false).also {
  it.getBindings("js").putMember("pm", pm)
  it.getBindings("js").putMember("xml2Json", pm.xml2Json)
}

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

@OptIn(ExperimentalStdlibApi::class)
private fun revUp(
  templatePath: String,
  environmentPath: String?,
  dynamicEnvironment: Map<String, String?>,
  bearerTokenKey: String?,
  stepNameToSuccessType: Map<String, Class<out Any>>,
  stepNameToValidationConfig: Map<String, BaseValidationConfig<out Any, out Any?>>,
  stepNameToErrorType: Map<String, Class<out Any>>,
  customAdaptersForResponse: List<Any>,
  typesInResponseToIgnore: Set<Class<out Any>>
): Rundown {
  initPmEnvironment(dynamicEnvironment, environmentPath)
  val pmCollection: Collection? = marshallPostmanCollection(templatePath)
  val replaceRegexWithEnvAdapter = Moshi.Builder().add(RegexAdapterFactory(pm.environment)).build().adapter<Item>()
  val moshi = initMoshi(typesInResponseToIgnore, customAdaptersForResponse)
  val stepNameToResponseWithType = pmCollection?.item?.asSequence()
    ?.map { stepWithRegex -> replaceRegexWithEnvAdapter.fromJson(stepWithRegex.data) }
    ?.filterNotNull()
    ?.map { step ->
      val httpClient: HttpHandler = prepareHttpClient(bearerTokenKey)
      val response: Response = httpClient(toHttpRequest(step.request))
      if (response.status.successful) {
        if (isContentTypeApplicationJson(response)) {
          val successType = stepNameToSuccessType[step.name]?.kotlin ?: Any::class
          val responseObj = moshi.asA(response.bodyString(), successType)
          validate(responseObj, stepNameToValidationConfig[step.name])
          loadIntoPmEnvironment(step.request, response)
          executeTestScriptJs(step, response.bodyString())
          step.name to (responseObj to successType.java)
        } else {
          // ! TODO gopala.akshintala 04/08/22: Support other content types apart from JSON
          step.name to (response.bodyString() to Any::class.java)
        }
      } else {
        val errorType = stepNameToErrorType[step.name]?.kotlin ?: Any::class
        val errorResponseObj = moshi.asA(response.bodyString(), errorType)
        step.name to (errorResponseObj to errorType.java)
      }
    }?.toMap() ?: emptyMap()
  return Rundown(stepNameToResponseWithType, pm.environment)
}

// ! TODO gopala.akshintala 03/08/22: Enhance the validation execution
private fun validate(responseObj: Any, validationConfig: BaseValidationConfig<out Any, out Any>?) {
  if (validationConfig != null) {
    val result = Vader.validateAndFailFast(responseObj, validationConfig as ValidationConfig<Any, Any?>)
    result.ifPresent { throw RuntimeException(it.toString()) }
    logger.info { "*** Validation passed for: $responseObj" }
  }
}

private fun loadIntoPmEnvironment(stepRequest: org.revcloud.postman.state.collection.Request, response: Response) {
  pm.request = stepRequest
  pm.response = org.revcloud.postman.state.collection.Response(
    response.status.toString(),
    response.status.code.toString(),
    response.bodyString()
  )
}

private fun prepareHttpClient(bearerTokenKey: String?) = DebuggingFilters.PrintRequestAndResponse()
  .then(pm.environment[bearerTokenKey]?.let { ClientFilters.BearerAuth(it) } ?: Filter.NoOp)
  .then(JavaHttpClient())

private fun toHttpRequest(stepRequest: org.revcloud.postman.state.collection.Request): Request {
  val contentType = stepRequest.header.firstOrNull { it.key.equals(CONTENT_TYPE.meta.name, ignoreCase = true) }
    ?.value?.let { Text(it) } ?: APPLICATION_JSON
  val uri = Uri.of(stepRequest.url.raw).queryParametersEncoded()
  return Request(Method.valueOf(stepRequest.method), uri)
    .with(CONTENT_TYPE of contentType)
    .headers(stepRequest.header.map { it.key to it.value })
    .body(stepRequest.body?.raw ?: "")
}

private fun executeTestScriptJs(
  step: Item?,
  responseBody: String
) {
  val testScript = step?.event?.find { it.listen == "test" }?.script?.exec?.joinToString("\n")
  if (!testScript.isNullOrBlank()) {
    val testSource = Source.newBuilder("js", testScript, "pmItemTestScript.js").build()
    jsContext.getBindings("js").putMember("responseBody", responseBody)
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
private fun marshallPostmanCollection(pmCollectionPath: String): Collection? {
  val collectionJsonAdapter = Moshi.Builder()
    .add(AdaptedBy.Factory()).build()
    .adapter<Collection>()
  return collectionJsonAdapter.fromJson(readTextFromFile(pmCollectionPath))
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
    pm.environment.putAll(environment?.values?.filter { it.enabled }?.associate { it.key to it.value } ?: emptyMap())
  }
}

private fun isContentTypeApplicationJson(response: Response) =
  response.bodyString().isNotBlank() && response.header("content-type")?.let {
    StringUtils.deleteWhitespace(it)
      .equals(StringUtils.deleteWhitespace(APPLICATION_JSON.toHeaderValue()), ignoreCase = true)
  } ?: false

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

private fun readTextFromFile(filePath: String): String = File(filePath).readText()

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
