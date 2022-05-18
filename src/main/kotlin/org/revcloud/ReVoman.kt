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
import org.revcloud.output.Pokemon
import org.revcloud.postman.DynamicEnvironmentKeys.BEARER_TOKEN
import org.revcloud.postman.PostmanAPI
import org.revcloud.postman.dynamicVariables
import org.revcloud.postman.state.collection.Collection
import org.revcloud.postman.state.collection.Item
import org.revcloud.postman.state.environment.Environment
import java.io.File
import java.lang.reflect.Type
import java.net.URLDecoder
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
  dynamicEnvironment: Map<String, String?> = emptyMap(),
  customAdaptersForResponse: List<Any> = emptyList(),
  typesInResponseToIgnore: Set<Class<out Any>> = emptySet(),
): Pokemon {
  // Load environment
  pm.environment.putAll(dynamicEnvironment)
  if (pmEnvironmentPath != null) {
    val envJsonAdapter = Moshi.Builder().build().adapter<Environment>()
    val environment: Environment? = envJsonAdapter.fromJson(readTextFromFile(pmEnvironmentPath))
    pm.environment.putAll(environment?.values?.filter { it.enabled }?.associate { it.key to it.value } ?: emptyMap())
  }

  // Marshall postman collection
  val collectionJsonAdapter = Moshi.Builder()
    .add(AdaptedBy.Factory()).build()
    .adapter<Collection>()
  val pmCollection: Collection? = collectionJsonAdapter.fromJson(readTextFromFile(pmCollectionPath))

  val configurableMoshi = configurableMoshi(typesInResponseToIgnore, customAdaptersForResponse)
  val itemJsonAdapter = Moshi.Builder().add(RegexAdapterFactory(pm.environment)).build().adapter<Item>()
  // Fire requests
  val itemNameToResponseWithType = pmCollection?.item?.asSequence()?.map { itemData ->
    val item = itemJsonAdapter.fromJson(itemData.data)
    val itemRequest: org.revcloud.postman.state.collection.Request =
      item?.request ?: org.revcloud.postman.state.collection.Request()
    val contentType = itemRequest.header.firstOrNull {
      it.key.equals(
        CONTENT_TYPE.meta.name,
        ignoreCase = true
      )
    }?.value?.let { Text(it) } ?: APPLICATION_JSON
    val uri = encodeUri(itemRequest.url.raw)
    val httpRequest = Request(Method.valueOf(itemRequest.method), uri)
      .with(CONTENT_TYPE of contentType)
      .headers(itemRequest.header.map { it.key to it.value })
      .body(itemRequest.body?.raw ?: "")

    // Prepare httpClient
    val httpClient: HttpHandler = DebuggingFilters.PrintRequestAndResponse()
      .then(pm.environment[bearerTokenKey]?.let { ClientFilters.BearerAuth(it) } ?: Filter.NoOp)
      .then(JavaHttpClient())
    val response: Response = httpClient(httpRequest)

    // Init pm environment
    val pmResponse = org.revcloud.postman.state.collection.Response(
      response.status.toString(),
      response.status.code.toString(),
      response.bodyString()
    )
    pm.request = itemRequest
    pm.response = pmResponse

    // Test script
    val itemName = item?.name ?: ""
    val testScript = item?.event?.find { it.listen == "test" }?.script?.exec?.joinToString("\n")
    if (!testScript.isNullOrBlank()) { // ! TODO gopala.akshintala 04/05/22: Catch and handle exceptions
      val testSource = Source.newBuilder("js", testScript, "pmItemTestScript.js").build()
      jsContext.getBindings("js").putMember("responseBody", response.bodyString())
      try {
        // ! TODO gopala.akshintala 15/05/22: Keep a tab on context mixing from different Items
        jsContext.eval(testSource)
      } catch (polyglotException: PolyglotException) {
        println(itemName)
        throw polyglotException
      }
    }

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

private fun isContentTypeApplicationJson(response: Response) =
  response.bodyString().isNotBlank() && response.header("content-type")?.let { StringUtils.deleteWhitespace(it)
    .equals(StringUtils.deleteWhitespace(APPLICATION_JSON.toHeaderValue()), ignoreCase = true) } ?: false

internal fun encodeUri(url: String): Uri {
  val uri = Uri.of(url)
  return uri.copy(query = uri.query.toParameters().toUrlFormEncoded())
}

private fun String.toParameters() = if (isNotEmpty()) split("&").map(String::toParameter) else listOf()
private fun String.toParameter(): Pair<String, String?> =
  split("=", limit = 2).map(String::fromFormEncoded).let { l -> l.elementAt(0) to l.elementAtOrNull(1) }

private fun String.fromFormEncoded() = URLDecoder.decode(this, "UTF-8")

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
  typesToIgnore: Set<Class<out Any>> = emptySet(),
  customAdaptersForResponse: List<Any> = emptyList()
): ConfigurableMoshi {
  val moshi = Moshi.Builder()
  customAdaptersForResponse.forEach { moshi.add(it) }
  return object : ConfigurableMoshi(
    moshi
      .add(JsonString.Factory())
      .add(AdaptedBy.Factory())
      .add(Date::class.java, Rfc3339DateJsonAdapter())
      .add(IgnoreUnknownFactory(typesToIgnore))
      .addLast(EventAdapter)
      .addLast(ThrowableAdapter)
      .addLast(ListAdapter)
      .addLast(MapAdapter)
      .asConfigurable()
      .withStandardMappings()
      .done()
  ) {}
}
