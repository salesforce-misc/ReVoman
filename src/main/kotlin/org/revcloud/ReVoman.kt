@file:JvmName("ReVoman")

package org.revcloud

import com.squareup.moshi.Moshi
import com.squareup.moshi.adapter
import com.squareup.moshi.adapters.Rfc3339DateJsonAdapter
import dev.zacsweers.moshix.adapters.AdaptedBy
import dev.zacsweers.moshix.adapters.JsonString
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
import java.io.File
import java.util.Date

private val pm = PostmanAPI()
private val jsContext = buildJsContext(false).also {
  it.getBindings("js").putMember("pm", pm)
  it.getBindings("js").putMember("xml2Json", pm.xml2Json)
}

fun revUp(kick: Kick): Rundown = revUp(
  kick.templatePath(),
  kick.environmentPath(),
  kick.bearerTokenKey(),
  kick.itemNameToSuccessType(),
  kick.itemNameToErrorType(),
  kick.dynamicEnvironment(),
  kick.customAdaptersForResponse(),
  kick.typesInResponseToIgnore()
)

@OptIn(ExperimentalStdlibApi::class)
private fun revUp(
  templatePath: String,
  environmentPath: String?,
  bearerTokenKey: String?,
  itemNameToSuccessType: Map<String, Class<out Any>>,
  itemNameToErrorType: Map<String, Class<out Any>>,
  dynamicEnvironment: Map<String, String?>,
  customAdaptersForResponse: List<Any>,
  typesInResponseToIgnore: Set<Class<out Any>>,
): Rundown {
  initPmEnvironment(dynamicEnvironment, environmentPath)
  val pmCollection: Collection? = marshallPostmanCollection(templatePath)
  val replaceRegexWithEnvAdapter = Moshi.Builder().add(RegexAdapterFactory(pm.environment)).build().adapter<Item>()
  val moshi = initMoshi(typesInResponseToIgnore, customAdaptersForResponse)
  val itemNameToResponseWithType = pmCollection?.item?.asSequence()
    ?.map { itemWithRegex -> replaceRegexWithEnvAdapter.fromJson(itemWithRegex.data) }
    ?.filterNotNull()
    ?.map { item ->
      val httpClient: HttpHandler = prepareHttpClient(bearerTokenKey)
      val response: Response = httpClient(toHttpRequest(item.request))
      loadIntoPmEnvironment(item.request, response)
      executeTestScriptJs(item, response.bodyString())
      marshallResponse(response, itemNameToSuccessType, itemNameToErrorType, item.name, moshi)
    }?.toMap() ?: emptyMap()
  return Rundown(itemNameToResponseWithType, pm.environment)
}

private fun marshallResponse(
  response: Response,
  itemNameToOutputType: Map<String, Class<out Any>>,
  itemNameToErrorType: Map<String, Class<out Any>>,
  itemName: String,
  moshi: ConfigurableMoshi
) = when {
  isContentTypeApplicationJson(response) -> {
    val clazz = (if (response.status.successful) itemNameToOutputType[itemName] else itemNameToErrorType[itemName])?.kotlin ?: Any::class
    val responseObj = moshi.asA(response.bodyString(), clazz)
    itemName to (responseObj to responseObj.javaClass)
  }
  // ! TODO gopala.akshintala 26/07/22: Add support for other content types
  else -> itemName to ("" to Nothing::class.java)
}

private fun loadIntoPmEnvironment(itemRequest: org.revcloud.postman.state.collection.Request, response: Response) {
  pm.request = itemRequest
  pm.response = org.revcloud.postman.state.collection.Response(
    response.status.toString(),
    response.status.code.toString(),
    response.bodyString()
  )
}

private fun prepareHttpClient(bearerTokenKey: String?) = DebuggingFilters.PrintRequestAndResponse()
  .then(pm.environment[bearerTokenKey]?.let { ClientFilters.BearerAuth(it) } ?: Filter.NoOp)
  .then(JavaHttpClient())

private fun toHttpRequest(itemRequest: org.revcloud.postman.state.collection.Request): Request {
  val contentType = itemRequest.header.firstOrNull { it.key.equals(CONTENT_TYPE.meta.name, ignoreCase = true) }
    ?.value?.let { Text(it) } ?: APPLICATION_JSON
  val uri = Uri.of(itemRequest.url.raw).queryParametersEncoded()
  return Request(Method.valueOf(itemRequest.method), uri)
    .with(CONTENT_TYPE of contentType)
    .headers(itemRequest.header.map { it.key to it.value })
    .body(itemRequest.body?.raw ?: "")
}

private fun executeTestScriptJs(
  item: Item?,
  responseBody: String
) {
  val testScript = item?.event?.find { it.listen == "test" }?.script?.exec?.joinToString("\n")
  if (!testScript.isNullOrBlank()) { // ! TODO gopala.akshintala 04/05/22: Catch and handle exceptions
    val testSource = Source.newBuilder("js", testScript, "pmItemTestScript.js").build()
    jsContext.getBindings("js").putMember("responseBody", responseBody)
    try {
      // ! TODO gopala.akshintala 15/05/22: Keep a tab on context mixing from different Items
      jsContext.eval(testSource)
    } catch (polyglotException: PolyglotException) {
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
