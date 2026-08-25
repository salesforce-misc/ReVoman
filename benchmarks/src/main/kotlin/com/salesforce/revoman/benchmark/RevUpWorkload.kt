package com.salesforce.revoman.benchmark

import com.salesforce.revoman.ReVoman
import com.salesforce.revoman.input.config.Kick
import com.salesforce.revoman.output.Rundown
import java.io.ByteArrayInputStream
import org.http4k.core.Response
import org.http4k.core.Status

private const val GENERATED_IDENTIFIER_WIDTH = 4

internal fun prepareCollection(
  stepCount: Int,
  placeholdersPerRequest: Int = 0,
  includeScript: Boolean = false,
): PreparedCollection {
  require(stepCount > 0)
  require(placeholdersPerRequest >= 0)
  val items =
    (1..stepCount).joinToString(",") { index ->
      val placeholders =
        List(placeholdersPerRequest) {
            "{{${environmentKey(it)}}}"
          }
          .joinToString(separator = "/", prefix = "/")
      val events =
        if (includeScript) {
          ",\"event\":[{\"listen\":\"test\",\"script\":{\"exec\":[\"pm.test('status is 200', function () { pm.expect(pm.response.code).to.equal(200); });\"]}}]"
        } else {
          ""
        }
      "{\"name\":\"step-${index.toString().padStart(GENERATED_IDENTIFIER_WIDTH, '0')}\",\"request\":{\"method\":\"GET\",\"url\":{\"raw\":\"http://benchmark.invalid$placeholders/step-$index\"}}$events}"
    }
  return PreparedCollection("{\"item\":[$items],\"auth\":null}".encodeToByteArray(), stepCount)
}

internal fun prepareEnvironment(size: Int): Map<String, Any?> {
  require(size >= 0)
  return (0 until size).associate { index -> environmentKey(index) to "value-$index" }
}

internal fun revUp(
  collection: PreparedCollection,
  environment: Map<String, Any?> = emptyMap(),
): Rundown =
  ReVoman.revUp(
    Kick.configure()
      .templateInputStream(ByteArrayInputStream(collection.bytes))
      .dynamicEnvironment(environment)
      .httpClient { Response(Status.OK).body("{\"ok\":true}") }
      .off()
  )

internal fun revUpPath(
  templatePath: String,
  environment: Map<String, Any?>,
): Rundown =
  ReVoman.revUp(
    Kick.configure()
      .templatePath(templatePath)
      .dynamicEnvironment(environment)
      .httpClient { Response(Status.OK).body("{\"ok\":true}") }
      .off()
  )

internal fun Rundown.validate(expectedStepCount: Int) {
  check(stepReports.size == expectedStepCount) {
    "Expected $expectedStepCount reports, got ${stepReports.size}"
  }
  check(areAllStepsSuccessful) { "Benchmark validation run failed: $this" }
}

private fun environmentKey(index: Int): String =
  "env${index.toString().padStart(GENERATED_IDENTIFIER_WIDTH, '0')}"
