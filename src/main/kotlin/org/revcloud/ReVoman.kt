@file:JvmName("ReVoman")

package org.revcloud

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.adapter
import com.squareup.moshi.adapters.Rfc3339DateJsonAdapter
import com.squareup.moshi.internal.Util
import dev.zacsweers.moshix.adapters.AdaptedBy
import dev.zacsweers.moshix.adapters.JsonString
import org.apache.commons.lang3.StringUtils
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.PolyglotException
import org.graalvm.polyglot.Source
import org.http4k.client.JavaHttpClient
import org.http4k.core.ContentType.Companion.APPLICATION_JSON
import org.http4k.core.ContentType.Companion.Text
import org.http4k.core.Filter
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.NoOp
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Uri
import org.http4k.core.queryParametersEncoded
import org.http4k.core.then
import org.http4k.core.with
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
import org.revcloud.output.Pokemon
import org.revcloud.postman.DynamicEnvironmentKeys.BEARER_TOKEN
import org.revcloud.postman.PostmanAPI
import org.revcloud.postman.dynamicVariables
import org.revcloud.postman.state.collection.Collection
import org.revcloud.postman.state.collection.Item
import org.revcloud.postman.state.environment.Environment
import java.io.File
import java.lang.reflect.Type
import java.util.Date

private val postManVariableRegex = "\\{\\{([^{}]*?)}}".toRegex()
private val pm = PostmanAPI()
private val jsContext = buildJsContext(false).also {
  it.getBindings("js").putMember("pm", pm)
  it.getBindings("js").putMember("xml2Json", pm.xml2Json)
}

// ! TODO gopala.akshintala 18/05/22: Refactor this method
@OptIn(ExperimentalStdlibApi::class)
@JvmOverloads
fun revUp(
  pmCollectionPath: String,
  pmEnvironmentPath: String? = null,
  bearerTokenKey: String? = BEARER_TOKEN,
  itemNameToOutputType: Map<String, Class<out Any>>? = emptyMap(),
  dynamicEnvironment: Map<String, String?>? = emptyMap(),
  customAdaptersForResponse: List<Any>? = emptyList(),
  typesInResponseToIgnore: Set<Class<out Any>>? = emptySet(),
): Pokemon {
  initPmEnvironment(dynamicEnvironment, pmEnvironmentPath)
  val configurableMoshi = configurableMoshi(typesInResponseToIgnore, customAdaptersForResponse)
  val itemJsonAdapter = Moshi.Builder().add(RegexAdapterFactory(pm.environment)).build().adapter<Item>()
  val pmCollection: Collection? = marshallPostmanCollection(pmCollectionPath)
  // Process each item in `pmCollection`
  val itemNameToResponseWithType = pmCollection?.item?.asSequence()?.map { itemData ->
    val item = itemJsonAdapter.fromJson(itemData.data)
    val itemRequest: org.revcloud.postman.state.collection.Request =
      item?.request ?: org.revcloud.postman.state.collection.Request()
    val httpClient: HttpHandler = prepareHttpClient(bearerTokenKey)
    val response: Response = httpClient(toHttpRequest(itemRequest))

    loadIntoPmEnvironment(itemRequest, response)

    // Test script
    val itemName = item?.name ?: ""
    executeTestScriptJs(item, response.bodyString())

    // Marshall response
    if (isContentTypeApplicationJson(response)) {
      val clazz = itemNameToOutputType?.get(itemName)?.kotlin ?: Map::class
      itemName to (configurableMoshi.asA(response.bodyString(), clazz) to clazz.java)
    } else {
      itemName to ("" to Nothing::class.java)
    }
  }?.toMap() ?: emptyMap()
  return Pokemon(itemNameToResponseWithType, pm.environment)
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
  responseBody: String) {
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

private class RegexAdapterFactory(val envMap: Map<String, String?>) : JsonAdapter.Factory {
  override fun create(type: Type, annotations: Set<Annotation?>, moshi: Moshi): JsonAdapter<*>? {
    if (type != String::class.java) {
      return null
    }
    val stringAdapter = moshi.nextAdapter<String>(this, String::class.java, Util.NO_ANNOTATIONS)
    return object : JsonAdapter<String>() {
      override fun fromJson(reader: JsonReader): String? {
        val s = stringAdapter.fromJson(reader)
        return s?.let {
          postManVariableRegex.replace(s) { matchResult ->
            val variableKey = matchResult.groupValues[1]
            dynamicVariables(variableKey) ?: envMap[variableKey] ?: ""
          }
        }
      }

      override fun toJson(writer: JsonWriter, value: String?) {
        stringAdapter.toJson(writer, value)
      }
    }
  }
}

private class IgnoreUnknownFactory(private val typesToIgnore: Set<Class<out Any>>) : JsonAdapter.Factory {
  override fun create(
    type: Type, annotations: Set<Annotation?>, moshi: Moshi
  ): JsonAdapter<*> {
    val rawType = Types.getRawType(type)
    return if (typesToIgnore.contains(rawType)) {
      object : JsonAdapter<Type>() {
        override fun fromJson(reader: JsonReader): Type? {
          return null
        }

        override fun toJson(writer: JsonWriter, value: Type?) {
          // do nothing
        }
      }
    } else moshi.nextAdapter<Any>(this, type, annotations)
  }
}

private fun configurableMoshi(
  typesToIgnore: Set<Class<out Any>>? = emptySet(),
  customAdaptersForResponse: List<Any>? = emptyList()
): ConfigurableMoshi {
  val moshi = Moshi.Builder()
  customAdaptersForResponse?.forEach { moshi.add(it) }
  if (!typesToIgnore.isNullOrEmpty()) {
    moshi.add(IgnoreUnknownFactory(typesToIgnore))
  }
  return object : ConfigurableMoshi(
    moshi
      .add(JsonString.Factory())
      .add(AdaptedBy.Factory())
      .add(Date::class.java, Rfc3339DateJsonAdapter())
      .addLast(EventAdapter)
      .addLast(ThrowableAdapter)
      .addLast(ListAdapter)
      .addLast(MapAdapter)
      .asConfigurable()
      .withStandardMappings()
      .done()
  ) {}
}
