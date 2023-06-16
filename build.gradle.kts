plugins {
  id("revoman.root-conventions")
  id("revoman.sub-conventions")
  id("revoman.kt-conventions")
  alias(libs.plugins.moshix)
}
dependencies {
  api(libs.bundles.http4k)
  api(libs.moshix.adapters)
  implementation(libs.bundles.kotlin.logging)
  implementation(libs.apache.commons.lang3)
  implementation(libs.graal.sdk)
  implementation(libs.graal.js)
  implementation(libs.kotlin.faker)
  implementation(libs.underscore)
  api(libs.bundles.vador)
  kapt(libs.immutables.value)
  compileOnly(libs.immutables.builder)
  compileOnly(libs.immutables.value.annotations)
  compileOnly(libs.jetbrains.annotations)

  testImplementation(libs.assertj.core)
}
testing {
  suites {
    val test by getting(JvmTestSuite::class) {
      useJUnitJupiter(libs.versions.junit.get())
    }
    val integrationTest by registering(JvmTestSuite::class) {
      dependencies {
        implementation(project())
        implementation(libs.assertj.core)
      }
    }
  }
}
koverReport {
  defaults {
    xml {
      onCheck = true
    }
  }
}
moshi {
  enableSealed by true
  generateProguardRules by false
}
