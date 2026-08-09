plugins {
  id("revoman.kt-conventions")
  application
  alias(libs.plugins.moshix)
  alias(libs.plugins.jmh)
}

dependencies {
  implementation(libs.moshix.adapters)
  implementation(libs.jmh.core)
  implementation(libs.commons.math3)
  implementation(libs.json.schema.validator)
  testImplementation(libs.truth)
  testImplementation(libs.mockk)
}

application {
  mainClass.set("com.salesforce.revoman.benchmark.driver.BenchmarkDriverMainKt")
}

testing {
  suites {
    getByName<JvmTestSuite>("test") {
      useJUnitJupiter(libs.versions.junit.get())
    }
    register<JvmTestSuite>("integrationTest") {
      useJUnitJupiter(libs.versions.junit.get())
      dependencies {
        implementation(project())
        libs.bundles.kotest.get().forEach { implementation(it) }
        implementation(libs.truth)
        implementation(libs.mockk)
      }
    }
  }
}

val benchmarkTargetManifest = providers.gradleProperty("benchmark.targetManifest")
val benchmarkAdapter = providers.gradleProperty("benchmark.adapter")

tasks.withType<Test>().configureEach {
  benchmarkTargetManifest.orNull?.let {
    systemProperty("revoman.benchmark.targetManifest", it)
  }
  benchmarkAdapter.orNull?.let {
    systemProperty("revoman.benchmark.adapter", it)
  }
}

kotlin.target.compilations.named("integrationTest") {
  associateWith(kotlin.target.compilations.getByName("main"))
}
