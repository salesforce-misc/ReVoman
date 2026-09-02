plugins { id("revoman.benchmarks") }

val consumerScorecardExecutable =
  configurations.create("consumerScorecardExecutable") {
    isCanBeConsumed = true
    isCanBeResolved = false
    attributes {
      attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
      attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
      attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
      attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.SHADOWED))
    }
  }

afterEvaluate {
  artifacts { add(consumerScorecardExecutable.name, tasks.named("mainBenchmarkJar")) }
}

dependencies {
  implementation(projects.revoman)
  implementation(libs.kotlinx.benchmark.runtime)
}
