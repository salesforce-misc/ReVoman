@file:JvmName("ReVoman")

package org.revcloud

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import com.squareup.moshi.adapter
import com.squareup.moshi.internal.Util
import dev.zacsweers.moshix.adapters.AdaptedBy
import dev.zacsweers.moshix.adapters.JsonString
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.Source
import org.http4k.client.JavaHttpClient
import org.http4k.core.ContentType.Companion.APPLICATION_JSON
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.then
import org.http4k.core.with
import org.http4k.filter.ClientFilters
import org.http4k.format.ConfigurableMoshi
import org.http4k.format.EventAdapter
import org.http4k.format.ListAdapter
import org.http4k.format.MapAdapter
import org.http4k.format.ThrowableAdapter
import org.http4k.format.asConfigurable
import org.http4k.format.withStandardMappings
import org.http4k.lens.Header.CONTENT_TYPE
import org.revcloud.state.PostmanAPI
import org.revcloud.state.collection.Collection
import org.revcloud.state.collection.Item
import org.revcloud.state.environment.Environment
import java.io.File
import java.lang.reflect.Type

private val postManVariableRegex = "\\{\\{([^}]+)}}".toRegex()

@OptIn(ExperimentalStdlibApi::class)
@JvmOverloads
fun revUp(
  pmCollectionPath: String,
  pmEnvironmentPath: String,
  itemNameToOutputType: Map<String, Class<out Any>>?,
  dynamicEnvironment: Map<String, String?> = emptyMap()): org.revcloud.output.Pokemon {
  
  // Load environment
  val envJsonAdapter = Moshi.Builder().build().adapter<Environment>()
  val environment: Environment? = envJsonAdapter.fromJson(readTextFromFile(pmEnvironmentPath))
  val pm = PostmanAPI()
  pm.environment.putAll(environment?.values?.filter { it.enabled }?.associate { it.key to it.value } ?: emptyMap())
  pm.environment.putAll(dynamicEnvironment)

  // Load collection
  val collectionJsonAdapter = Moshi.Builder()
    .add(AdaptedBy.Factory()).build()
    .adapter<Collection>()
  val pmCollection: Collection? = collectionJsonAdapter.fromJson(readTextFromFile(pmCollectionPath))

  val itemJsonAdapter = Moshi.Builder().add(RegexAdapterFactory(pm.environment)).build().adapter<Item>()
  val itemNameToResponseWithType = pmCollection?.item?.asSequence()?.map { itemData ->
    val item = itemJsonAdapter.fromJson(itemData.data)
    val itemRequest: org.revcloud.state.collection.Request = item?.request ?: org.revcloud.state.collection.Request()
    val httpClient = ClientFilters.BearerAuth(dynamicEnvironment["bearer_token"] ?: "").then(JavaHttpClient())
    val httpRequest = Request(Method.valueOf(itemRequest.method), itemRequest.url.raw)
      .with(CONTENT_TYPE of APPLICATION_JSON)
      .body(itemRequest.body?.raw ?: "")
    val response: Response = httpClient(httpRequest)

    // Post request
    val pmResponse = org.revcloud.state.collection.Response(
      response.status.toString(),
      response.status.code.toString(),
      response.bodyString()
    )
    pm.request = itemRequest
    pm.response = pmResponse
    
    // Test script
    val testScript = item?.event?.find { it.listen == "test" }?.script?.exec?.joinToString("\n") ?: ""
    val testSource = Source.newBuilder("js", testScript, "pmItemTestScript.js").build()
    val context = buildJsContext(useCommonJs = false)
    context.getBindings("js").putMember("pm", pm)
    context.getBindings("js").putMember("responseBody", response.bodyString())
    context.eval(testSource)

    val name = item?.name ?: ""
    val clazz = itemNameToOutputType?.get(name)?.kotlin ?: Any::class
    
    name to (MyMoshi.asA(response.bodyString(), clazz) to clazz.java)
  }?.toMap() ?: emptyMap()
  return org.revcloud.output.Pokemon(itemNameToResponseWithType, pm.environment)
}

fun buildJsContext(useCommonJs: Boolean = true): Context {
  val options = buildMap<String, String> {
    if (useCommonJs) {
      "js.commonjs-require" to "true"
      "js.commonjs-require-cwd" to "graal-js"
      "js.commonjs-core-modules-replacements" to "buffer:buffer/, path:path-browserify"
    }
    "js.esm-eval-returns-exports" to "true"
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
        return s?.let { postManVariableRegex.replace(s) { matchResult -> envMap[matchResult.groupValues[1]] ?: "" } }
      }
      override fun toJson(writer: JsonWriter, value: String?) {
        stringAdapter.toJson(writer, value)
      }
    }
  }
}

object MyMoshi : ConfigurableMoshi(
  Moshi.Builder()
    .add(JsonString.Factory())
    .add(AdaptedBy.Factory())
    .addLast(EventAdapter)
    .addLast(ThrowableAdapter)
    .addLast(ListAdapter)
    .addLast(MapAdapter)
    .asConfigurable()
    .withStandardMappings()
    .done()
)
