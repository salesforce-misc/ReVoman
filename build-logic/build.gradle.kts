plugins { `kotlin-dsl` }

repositories {
  mavenCentral()
  gradlePluginPortal()
  maven("https://oss.sonatype.org/content/repositories/snapshots")
  val nexusUrl: String? = providers.gradleProperty("nexusGradlePluginsUrl").orNull
  val nexusUser: String? = providers.gradleProperty("nexusUsername").orNull
  val nexusPass: String? = providers.gradleProperty("nexusPassword").orNull
  if (nexusUrl != null && nexusUser != null && nexusPass != null) {
    maven {
      name = "nexusGradlePlugins"
      url = uri(nexusUrl)
      credentials {
        username = nexusUser
        password = nexusPass
      }
    }
  }
}

dependencies {
  implementation(libs.kotlin.gradle)
  implementation(libs.kotlin.allopen.gradle)
  implementation(libs.spotless.gradle)
  implementation(libs.detekt.gradle)
  implementation(libs.testLogger.gradle)
  implementation(libs.dataframe.gradle)
  implementation(libs.benchmark.gradle)
  testImplementation(gradleTestKit())
  testImplementation(libs.bundles.kotest)
}

val javaToolchains = extensions.getByType<JavaToolchainService>()
val optionalJava21Home = providers.systemProperty("consumerScorecardTest.java21Home")

tasks.withType<Test>().configureEach {
  useJUnitPlatform()
  javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
  optionalJava21Home.orNull?.let { java21Home ->
    systemProperty("consumerScorecardTest.java21Home", java21Home)
  }
}
