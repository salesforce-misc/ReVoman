package com.salesforce.revoman.benchmark

import com.salesforce.revoman.ReVoman
import com.salesforce.revoman.input.config.Kick
import com.salesforce.revoman.input.config.Phase
import com.salesforce.revoman.input.config.Runbook
import com.salesforce.revoman.input.config.step
import com.salesforce.revoman.output.Rundown
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.Uri

private const val BENCHMARK_BASE_URL = "http://benchmark.invalid"
private const val IDENTIFIER_WIDTH = 4
private const val REQUEST_ORDER_INCREMENT = 1_000
private const val MAX_RECORDED_HANDLER_CALLS = 100

private enum class ScriptFixture {
  NONE,
  SCRIPTED,
  HANDOFF_PRODUCER,
  HANDOFF_CONSUMER,
  HANDOFF_OBSERVER,
}

internal data class HandlerCall(
  val method: Method,
  val path: String,
  val body: String,
  val uri: Uri,
)

internal class HandlerLedger {
  private val recordedCalls: ArrayDeque<HandlerCall> = ArrayDeque()
  private var recordedAssertAfterCount: Int = 0

  @Synchronized
  fun record(call: HandlerCall) {
    if (recordedCalls.size == MAX_RECORDED_HANDLER_CALLS) recordedCalls.removeFirst()
    recordedCalls.addLast(call)
  }

  @Synchronized fun calls(): List<HandlerCall> = recordedCalls.toList()

  @Synchronized fun recordAssertAfter() = recordedAssertAfterCount++

  @Synchronized fun assertAfterCount(): Int = recordedAssertAfterCount

  @Synchronized
  fun reset() {
    recordedCalls.clear()
    recordedAssertAfterCount = 0
  }
}

internal class PreparedConsumerJourneys(
  val postmanV2TenStep: Kick,
  val v3TenStep: Kick,
  val v3HundredStep: Kick,
  val v3TenStepScripted: Kick,
  val threeKicks: List<Kick>,
  val runbook: Runbook,
  val handlerLedger: HandlerLedger,
  private val fixtureRoot: Path,
) : AutoCloseable {
  override fun close() = fixtureRoot.deleteRecursively()
}

internal fun prepareConsumerJourneys(): PreparedConsumerJourneys =
  withTemporaryFixtureRoot("revoman-consumer-journeys-") { fixtureRoot ->
    val handlerLedger = HandlerLedger()
    val handler = deterministicHandler(handlerLedger)
    val postmanV2TenStep =
      fixtureRoot.resolve("postman-v2-ten-step.json").also {
        Files.writeString(it, postmanV2Collection(stepCount = 10))
      }
    val v3TenStep = writeV3Collection(fixtureRoot, "v3-ten-step", stepCount = 10)
    val v3HundredStep = writeV3Collection(fixtureRoot, "v3-hundred-step", stepCount = 100)
    val v3TenStepScripted =
      writeV3Collection(
        fixtureRoot,
        "v3-ten-step-scripted",
        stepCount = 10,
        scriptFixture = ScriptFixture.SCRIPTED,
      )
    val workflowCollections =
      listOf(
          ScriptFixture.HANDOFF_PRODUCER,
          ScriptFixture.HANDOFF_CONSUMER,
          ScriptFixture.HANDOFF_OBSERVER,
        )
        .mapIndexed { index, scriptFixture ->
          writeV3Collection(
            fixtureRoot,
            "workflow-${index + 1}",
            stepCount = 10,
            pathPrefix = "/workflow-${index + 1}",
            scriptFixture = scriptFixture,
          )
        }
    val v2Kick = kick(postmanV2TenStep, handler)
    val v3TenStepKick = kick(v3TenStep, handler)
    val workflowKicks = workflowCollections.map { kick(it, handler) }
    val runbook =
      Runbook("consumer journey") {
        step {
          intent = "produce mixed environment values"
          phase = Phase.SETUP
          kick = workflowKicks[0]
          produces("handoffCount", "handoffReady", "handoffTags")
        }
        step {
          intent = "consume mixed values and produce lookup key"
          phase = Phase.ACT
          kick = workflowKicks[1]
          consumes("handoffCount", "handoffReady", "handoffTags")
          produces("lookupKey" to "beta-7-true")
          underTest()
          assertAfter { _, environment ->
            handlerLedger.recordAssertAfter()
            when (val lookupKey = environment["lookupKey"]) {
              "beta-7-true" -> Unit
              else -> throw AssertionError("Expected lookupKey=beta-7-true, got $lookupKey")
            }
          }
        }
        step {
          intent = "observe the handed-off lookup key"
          phase = Phase.ASSERT
          kick = workflowKicks[2]
          consumes("lookupKey")
        }
      }

    PreparedConsumerJourneys(
      postmanV2TenStep = v2Kick,
      v3TenStep = v3TenStepKick,
      v3HundredStep = kick(v3HundredStep, handler),
      v3TenStepScripted = kick(v3TenStepScripted, handler),
      threeKicks = workflowKicks,
      runbook = runbook,
      handlerLedger = handlerLedger,
      fixtureRoot = fixtureRoot,
    )
  }

internal class PreparedVerboseRendering(
  val rundown: Rundown,
  private val fixtureRoot: Path,
) : AutoCloseable {
  override fun close() = fixtureRoot.deleteRecursively()
}

internal fun prepareVerboseRendering(): PreparedVerboseRendering =
  withTemporaryFixtureRoot("revoman-verbose-rendering-") { fixtureRoot ->
    val handlerLedger = HandlerLedger()
    val fixture = writeV3Collection(fixtureRoot, "v3-hundred-step", stepCount = 100)
    val renderingKick =
      Kick.configure()
        .templatePath(fixture.toString())
        .dynamicEnvironment("renderingSeed", "verbose")
        .httpClient(deterministicHandler(handlerLedger))
        .off()
    val rundown = ReVoman.revUp(renderingKick)
    rundown.validate(expectedStepCount = 100)
    check(handlerLedger.calls().size == 100) {
      "Expected 100 rendering setup requests, got ${handlerLedger.calls().size}"
    }
    handlerLedger.reset()
    PreparedVerboseRendering(rundown = rundown, fixtureRoot = fixtureRoot)
  }

private fun postmanV2Collection(stepCount: Int): String =
  (1..stepCount).joinToString(",", prefix = "{\"item\":[", postfix = "],\"auth\":null}") { index ->
    val stepName = stepName(index)
    "{\"name\":\"$stepName\",\"request\":{\"method\":\"GET\",\"url\":{\"raw\":\"$BENCHMARK_BASE_URL/$stepName\"}}}"
  }

private fun writeV3Collection(
  fixtureRoot: Path,
  directoryName: String,
  stepCount: Int,
  pathPrefix: String = "",
  scriptFixture: ScriptFixture = ScriptFixture.NONE,
): Path {
  val collectionRoot = Files.createDirectory(fixtureRoot.resolve(directoryName))
  val resources = Files.createDirectory(collectionRoot.resolve(".resources"))
  Files.writeString(resources.resolve("definition.yaml"), "\$kind: collection\n")
  (1..stepCount).forEach { index ->
    val name = stepName(index)
    val requestYaml =
      listOf(
          """
          ${'$'}kind: http-request
          name: $name
          url: ${scriptFixture.urlFor(pathPrefix, name, index)}
          method: GET
          """
            .trimIndent(),
          scriptFixture.yamlFor(index),
          "order: ${index * REQUEST_ORDER_INCREMENT}",
        )
        .filter { it.isNotEmpty() }
        .joinToString(separator = "\n", postfix = "\n")
    Files.writeString(
      collectionRoot.resolve("$name.request.yaml"),
      requestYaml,
    )
  }
  return collectionRoot
}

private fun kick(fixture: Path, handler: HttpHandler): Kick =
  Kick.configure().templatePath(fixture.toString()).httpClient(handler).off()

private fun deterministicHandler(handlerLedger: HandlerLedger): HttpHandler = { request ->
  handlerLedger.record(
    HandlerCall(
      method = request.method,
      path = request.uri.path,
      body = request.bodyString(),
      uri = request.uri,
    )
  )
  val body =
    when (request.uri.path) {
      "/workflow-1/step-0001" -> "{\"count\":7,\"ready\":true,\"tags\":[\"alpha\",\"beta\"]}"
      else -> "{\"path\":\"${request.uri.path}\"}"
    }
  Response(Status.OK).body(body)
}

private fun ScriptFixture.yamlFor(index: Int): String {
  if (index != 1) return ""
  val script =
    when (this) {
      ScriptFixture.NONE -> return ""
      ScriptFixture.SCRIPTED -> "pm.environment.set('scriptedMarker', 'after-response-ran');"
      ScriptFixture.HANDOFF_PRODUCER ->
        """
        const payload = pm.response.json();
        pm.environment.set('handoffCount', payload.count);
        pm.environment.set('handoffReady', payload.ready);
        pm.environment.set('handoffTags', payload.tags);
        """
          .trimIndent()
      ScriptFixture.HANDOFF_CONSUMER ->
        """
        const count = pm.environment.get('handoffCount');
        const ready = pm.environment.get('handoffReady');
        pm.environment.set('lookupKey', 'beta-' + count + '-' + ready);
        """
          .trimIndent()
      ScriptFixture.HANDOFF_OBSERVER ->
        "pm.environment.set('lookupObserved', pm.environment.get('lookupKey'));"
    }
  val type =
    when (this) {
      ScriptFixture.SCRIPTED,
      ScriptFixture.HANDOFF_PRODUCER -> "afterResponse"
      ScriptFixture.HANDOFF_CONSUMER,
      ScriptFixture.HANDOFF_OBSERVER -> "beforeRequest"
      ScriptFixture.NONE -> error("No script type for a script-free fixture")
    }
  return buildString {
    appendLine("scripts:")
    appendLine("  - type: $type")
    appendLine("    code: |-")
    appendLine(script.prependIndent("      "))
    append("    language: text/javascript")
  }
}

private fun ScriptFixture.urlFor(pathPrefix: String, name: String, index: Int): String {
  val query =
    when {
      this == ScriptFixture.HANDOFF_CONSUMER && index == 1 ->
        "?count={{handoffCount}}&ready={{handoffReady}}"
      this == ScriptFixture.HANDOFF_OBSERVER && index == 1 ->
        "?lookup={{lookupKey}}&tags={{handoffTags}}"
      else -> ""
    }
  return "$BENCHMARK_BASE_URL$pathPrefix/$name$query"
}

private fun stepName(index: Int): String =
  "step-${index.toString().padStart(IDENTIFIER_WIDTH, '0')}"

internal inline fun <T> withTemporaryFixtureRoot(prefix: String, block: (Path) -> T): T {
  val fixtureRoot = Files.createTempDirectory(prefix)
  return runCatching { block(fixtureRoot) }
    .getOrElse { failure ->
      fixtureRoot.deleteRecursively()
      throw failure
    }
}

private fun Path.deleteRecursively() {
  if (!Files.exists(this)) return
  Files.walk(this).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
}
