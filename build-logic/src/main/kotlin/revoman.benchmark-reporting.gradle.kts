plugins {
  id("revoman.kotlin-jvm")
  application
  id("org.jetbrains.kotlin.plugin.dataframe")
}

val libs: VersionCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
  implementation(libs.findLibrary("dataframe").get())
  implementation(libs.findLibrary("kotlinx-serialization-json").get())
}

application { mainClass.set("com.salesforce.revoman.benchmark.reporting.MainKt") }

val consumerScorecardExecutable =
  createConsumerScorecardExecutable(canBeConsumed = false, canBeResolved = true)

dependencies {
  add(
    consumerScorecardExecutable.name,
    project(path = ":benchmarks", configuration = CONSUMER_SCORECARD_EXECUTABLE),
  )
}

val javaToolchainService = extensions.getByType<JavaToolchainService>()
val java25Launcher =
  javaToolchainService.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
val scorecardProjectRoot = providers.provider { layout.settingsDirectory.asFile.absolutePath }
val scorecardBenchmarkJar =
  consumerScorecardExecutable.elements.map { artifacts ->
    require(artifacts.size == 1) {
      "consumerScorecardExecutable must resolve exactly one artifact; found ${artifacts.size}"
    }
    artifacts.single().asFile
  }
val scorecardJavaExecutable =
  java25Launcher.map { launcher -> launcher.executablePath.asFile.absolutePath }
val scorecardJavaFeature =
  java25Launcher.map { launcher -> launcher.metadata.languageVersion.asInt() }
val scorecardDaemonJavaFeature = providers.provider { Runtime.version().feature() }
val scorecardDaemonRuntimeVersion = providers.systemProperty("java.runtime.version")
val scorecardDaemonVendor = providers.systemProperty("java.vendor")
val scorecardDaemonVmName = providers.systemProperty("java.vm.name")
val scorecardLibraryVersion = providers.gradleProperty("revoman.version")
val scorecardRuntimeValidation = providers.gradleProperty("scorecardRuntimeValidation")
val scorecardAllowedDirtyPaths =
  providers
    .gradleProperty("scorecardAllowedDirty")
    .map { value -> value.split(',').asSequence().map(String::trim).filter(String::isNotEmpty).toList() }
    .orElse(emptyList())

tasks.register<ConsumerScorecardTask>("runConsumerScorecard") {
  group = "benchmark"
  description = "Runs the JDK 25 consumer performance scorecard"
  javaLauncher = java25Launcher
  mainClass = "com.salesforce.revoman.benchmark.reporting.ConsumerScorecardMainKt"
  classpath(sourceSets.named("main").map { it.runtimeClasspath })
  workingDir(layout.settingsDirectory)
  runtimeValidation.set(scorecardRuntimeValidation)

  inputs.files(consumerScorecardExecutable).withPropertyName("scorecard.benchmarkJarContents")
  inputs.property("scorecard.projectRoot", scorecardProjectRoot)
  inputs.property("scorecard.benchmarkJar", scorecardBenchmarkJar.map { it.absolutePath })
  inputs.property("scorecard.javaExecutable", scorecardJavaExecutable)
  inputs.property("scorecard.javaFeature", scorecardJavaFeature)
  inputs.property("scorecard.gradleDaemonJavaFeature", scorecardDaemonJavaFeature)
  inputs.property("scorecard.gradleDaemonRuntimeVersion", scorecardDaemonRuntimeVersion)
  inputs.property("scorecard.gradleDaemonVendor", scorecardDaemonVendor)
  inputs.property("scorecard.gradleDaemonVmName", scorecardDaemonVmName)
  inputs.property("scorecard.libraryVersion", scorecardLibraryVersion)
  inputs.property("scorecard.runtimeValidation", scorecardRuntimeValidation.orElse(""))
  inputs.property("scorecard.allowedDirtyPaths", scorecardAllowedDirtyPaths)

  argumentProviders.add(
    ConsumerScorecardArguments(
      projectRoot = scorecardProjectRoot,
      benchmarkJar = scorecardBenchmarkJar.map { it.absolutePath },
      javaExecutable = scorecardJavaExecutable,
      javaFeature = scorecardJavaFeature,
      daemonJavaFeature = scorecardDaemonJavaFeature,
      daemonRuntimeVersion = scorecardDaemonRuntimeVersion,
      daemonVendor = scorecardDaemonVendor,
      daemonVmName = scorecardDaemonVmName,
      libraryVersion = scorecardLibraryVersion,
      runtimeValidation = scorecardRuntimeValidation,
      allowedDirtyPaths = scorecardAllowedDirtyPaths,
    )
  )
}
