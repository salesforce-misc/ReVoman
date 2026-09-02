plugins { id("revoman.benchmarks") }

dependencies {
  implementation(projects.revoman)
  implementation(libs.kotlinx.benchmark.runtime)
}

tasks.test { dependsOn("mainBenchmarkJar") }
