import io.github.gradlenexus.publishplugin.NexusPublishExtension

plugins {
  base
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.kapt) apply false
  alias(libs.plugins.spotless) apply false
  alias(libs.plugins.nexus.publish) apply false
}

tasks.assemble { dependsOn(":revoman:assemble", ":benchmarks:assemble", ":benchmark-reporting:assemble") }

tasks.check { dependsOn(":revoman:check", ":benchmarks:check", ":benchmark-reporting:check") }

if (providers.gradleProperty("revoman.releaseMode").orNull == "true") {
  group = providers.gradleProperty("revoman.group").get()
  version = providers.gradleProperty("revoman.version").get()
  apply(plugin = "io.github.gradle-nexus.publish-plugin")
  configure<NexusPublishExtension> {
    repositories {
      sonatype {
        stagingProfileId.set(providers.gradleProperty("revoman.stagingProfileId"))
        nexusUrl.set(uri("https://ossrh-staging-api.central.sonatype.com/service/local/"))
        snapshotRepositoryUrl.set(uri("https://central.sonatype.com/repository/maven-snapshots/"))
      }
    }
  }
  logger.lifecycle("ReVoman legacy release mode: root coordinates $group:$version")
}
