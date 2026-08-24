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
