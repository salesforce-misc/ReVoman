package com.salesforce.revoman.benchmark

import com.salesforce.revoman.ReVoman
import com.salesforce.revoman.input.config.Kick
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Blackhole
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import org.http4k.core.Response
import org.http4k.core.Status

@State(Scope.Benchmark)
open class ScriptFreeRevUpBenchmark {
  private lateinit var templatePath: String

  @Setup
  fun setUp() {
    templatePath = "pm-templates/v3/single-ok"
    checkNotNull(
      Thread.currentThread()
        .contextClassLoader
        .getResource("$templatePath/.resources/definition.yaml")
    )
    System.setProperty("revoman.banner", "off")
  }

  @Benchmark
  fun scriptFreeRevUp(blackhole: Blackhole) {
    val result =
      ReVoman.revUp(
        Kick.configure()
          .templatePath(templatePath)
          .dynamicEnvironment("baseUrl", "http://benchmark.invalid")
          .httpClient { Response(Status.OK).body("""{"ok":true}""") }
          .off()
      )
    blackhole.consume(result)
  }
}
