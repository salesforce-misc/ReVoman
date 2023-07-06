dependencyResolutionManagement {
  versionCatalogs { create("libs") { from(files("../libs.versions.toml")) } }

  pluginManagement {
    repositories {
      mavenCentral()
      gradlePluginPortal()
      google()
      maven("https://oss.sonatype.org/content/repositories/snapshots")
    }
  }
}
