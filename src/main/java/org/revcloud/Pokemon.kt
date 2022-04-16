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
fun main() {
  val postManCollection = readFileFromResource("Pokemon.postman_collection.json")
  val envJsonAdapter = Moshi.Builder().build().adapter<Environment>()
  val pm = PostmanAPI()
  // Load environment
  val environment: Environment? = envJsonAdapter.fromJson(readFileFromResource("Pokemon.postman_environment.json"))
  environment?.values?.filter { it.enabled }?.forEach { pm.environment[it.key] = it.value }

  val collectionJsonAdapter = Moshi.Builder()
    .add(AdaptedBy.Factory()).build()
    .adapter<Collection>()
  val postmanCollection: Collection? = collectionJsonAdapter.fromJson(postManCollection)
  val itemJsonAdapter = Moshi.Builder().add(RegexAdapterFactory(pm.environment)).build().adapter<Item>()
  postmanCollection?.item?.asSequence()?.map { itemData ->
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
    val testScript = item?.event?.find { it.listen == "test" }?.script?.exec?.joinToString("\n") ?: ""
    val testSource = Source.newBuilder("js", testScript, "myScript.js").build()
    val context = buildJsContext()
    context.getBindings("js").putMember("pm", pm)
    context.getBindings("js").putMember("responseBody", response.bodyString())
    context.eval(testSource)

    val name = item?.name
    val clazz = input[name]?.kotlin ?: Any::class
    name to (clazz to asA(response.bodyString(), clazz))
  }?.toMap()
  println("Environment:: -> ")
  pm.environment.entries.forEach(::println)
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

private fun readFileFromResource(fileRelativePath: String): String =
  File("src/main/resources/$fileRelativePath").readText()

internal class RegexAdapterFactory(val envMap: Map<String, String>) : JsonAdapter.Factory {
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

private val input = mapOf(
  "All Pokemon" to Results::class.java,
  "Pokemon" to Abilities::class.java,
)

data class Pokemon(val name: String)
data class Results(val results: List<Pokemon>)

private data class Ability(val name: String)

private data class AbilityWrapper(val ability: Ability)
private data class Abilities(val abilities: List<AbilityWrapper>)
