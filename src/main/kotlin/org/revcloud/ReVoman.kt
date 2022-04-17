@file:JvmName("ReVoman")

package org.revcloud

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import com.squareup.moshi.adapter
import com.squareup.moshi.internal.Util
import dev.zacsweers.moshix.adapters.AdaptedBy
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.Source
import org.http4k.client.JavaHttpClient
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.format.Moshi.asA
import org.revcloud.state.PostmanAPI
import org.revcloud.state.collection.Collection
import org.revcloud.state.collection.Item
import org.revcloud.state.environment.Environment
import java.io.File
import java.io.IOException
import java.lang.reflect.Type

private val postManVariableRegex = "\\{\\{([^}]+)}}".toRegex()

@OptIn(ExperimentalStdlibApi::class)
@JvmOverloads
fun lasso(
  pmCollectionPath: String,
  pmEnvironmentPath: String,
  itemNameToOutputType: Map<String, Class<out Any>>,
  dynamicEnvironment: Map<String, String> = emptyMap()): org.revcloud.output.Pokemon {
  
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
    val request: org.revcloud.state.collection.Request = item?.request ?: org.revcloud.state.collection.Request()
    val uri = request.url.raw
    val response: Response = JavaHttpClient()(Request(Method.valueOf(request.method), uri))

    // Post request
    val pmResponse = org.revcloud.state.collection.Response(
      response.status.toString(),
      response.status.code.toString(),
      response.bodyString()
    )
    pm.request = request
    pm.response = pmResponse
    
    // Test script
    val testScript = item?.event?.find { it.listen == "test" }?.script?.exec?.joinToString("\n") ?: ""
    val testSource = Source.newBuilder("js", testScript, "myScript.js").build()
    val context = buildJsContext()
    context.getBindings("js").putMember("pm", pm)
    context.getBindings("js").putMember("responseBody", response.bodyString())
    context.eval(testSource)

    val name = item?.name ?: ""
    val clazz = itemNameToOutputType[name]?.kotlin ?: Any::class
    name to (asA(response.bodyString(), clazz) to clazz.java)
  }?.toMap() ?: emptyMap()
  return org.revcloud.output.Pokemon(itemNameToResponseWithType, pm.environment)
}

private fun buildJsContext(): Context {
  val options: MutableMap<String, String> = mutableMapOf()
  options["js.commonjs-require"] = "true"
  options["js.commonjs-require-cwd"] = "."
  options["js.commonjs-core-modules-replacements"] =
    "buffer:buffer/, path:path-browserify"
  return Context.newBuilder("js")
    .allowExperimentalOptions(true)
    .allowIO(true)
    .options(options)
    .allowHostAccess(HostAccess.ALL)
    .allowHostClassLookup { true }
    .build()
}

private fun readTextFromFile(filePath: String): String = File(filePath).readText()

private class RegexAdapterFactory(val envMap: Map<String, String>) : JsonAdapter.Factory {
  override fun create(type: Type, annotations: Set<Annotation?>, moshi: Moshi): JsonAdapter<*>? {
    if (type != String::class.java) {
      return null
    }
    val stringAdapter = moshi.nextAdapter<String>(this, String::class.java, Util.NO_ANNOTATIONS)
    return object : JsonAdapter<String>() {
      @Throws(IOException::class)
      override fun fromJson(reader: JsonReader): String? {
        val s = stringAdapter.fromJson(reader)
        return s?.let { postManVariableRegex.replace(s) { matchResult -> envMap.getOrDefault(matchResult.groupValues[1], "") } }
      }

      @Throws(IOException::class)
      override fun toJson(writer: JsonWriter, value: String?) {
        stringAdapter.toJson(writer, value)
      }
    }
  }
}
