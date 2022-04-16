pluginManagement {
  repositories {
    mavenCentral()
    gradlePluginPortal()
  }
  val kotlinVersion: String by settings
  plugins {
    kotlin("jvm") version kotlinVersion
  }
}

rootProject.name = "pokémon-root"

