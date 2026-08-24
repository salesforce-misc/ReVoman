pluginManagement {
  repositories {
    mavenCentral()
    gradlePluginPortal()
    google()
    maven("https://s01.oss.sonatype.org/content/repositories/snapshots")
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
}

dependencyResolutionManagement {
  versionCatalogs { create("libs") { from(files("../gradle/libs.versions.toml")) } }
}

rootProject.name = "build-logic"
