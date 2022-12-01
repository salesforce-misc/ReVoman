import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
  kotlin("jvm")
  kotlin("kapt")
}

val libs: VersionCatalog =
  extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
  testImplementation(libs.kotestBundle)
}

tasks {
  withType<KotlinCompile> {
    kotlinOptions {
      jvmTarget = JavaVersion.VERSION_11.toString()
      freeCompilerArgs = listOf("-Xjvm-default=all", "-jvm-target=11")
    }
  }
}
