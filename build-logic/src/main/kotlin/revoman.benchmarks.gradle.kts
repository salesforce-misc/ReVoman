import kotlinx.benchmark.gradle.BenchmarkConfiguration

plugins {
  id("revoman.kotlin-jvm")
  id("org.jetbrains.kotlin.plugin.allopen")
  id("org.jetbrains.kotlinx.benchmark")
}

allOpen { annotation("kotlinx.benchmark.State") }

benchmark {
  targets { register("main") }
  configurations {
    register("smoke") {
      commonProfile(iterationCount = 2, warmupCount = 2, iterationMillis = 250, forkCount = 1)
    }
    register("final") {
      commonProfile(iterationCount = 20, warmupCount = 10, iterationMillis = 1000, forkCount = 5)
    }
    register("consumerScorecard") {
      commonProfile(iterationCount = 20, warmupCount = 10, iterationMillis = 1000, forkCount = 5)
      include("com.salesforce.revoman.benchmark.ConsumerJourneyBenchmark.*")
    }
    register("collectionScaleFinal") {
      commonProfile(iterationCount = 20, warmupCount = 10, iterationMillis = 1000, forkCount = 5)
      include(
        "com.salesforce.revoman.benchmark.CollectionScaleRevUpBenchmark.revUpByStepCount"
      )
      param("stepCount", "100", "500")
    }
  }
}

fun BenchmarkConfiguration.commonProfile(
  iterationCount: Int,
  warmupCount: Int,
  iterationMillis: Int,
  forkCount: Int,
) {
  mode = "avgt"
  outputTimeUnit = "ms"
  reportFormat = "csv"
  iterations = iterationCount
  warmups = warmupCount
  iterationTime = iterationMillis.toLong()
  iterationTimeUnit = "ms"
  advanced("jvmForks", forkCount)
}
