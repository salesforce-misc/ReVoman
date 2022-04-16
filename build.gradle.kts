import com.adarshr.gradle.testlogger.theme.ThemeType.MOCHA_PARALLEL
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
  kotlin("jvm")
  id("com.adarshr.test-logger") version "3.1.0"
  application
  id("dev.zacsweers.moshix") version "0.17.1"
}

group = "com.salesforce.ccspayments"
version = "1.0-SNAPSHOT"

repositories {
  mavenCentral()
}

dependencies {
  val http4kVersion: String by project
  implementation("org.http4k:http4k-core:$http4kVersion")
  implementation("org.http4k:http4k-serverless-lambda:$http4kVersion")
  implementation("org.http4k:http4k-format-moshi:$http4kVersion")
  implementation("dev.zacsweers.moshix:moshi-adapters:0.17.1")
  implementation("org.slf4j:slf4j-api:1.7.36")
  val graalVersion = "22.0.0.2"
  implementation("org.graalvm.sdk:graal-sdk:$graalVersion")
  implementation("org.graalvm.js:js:$graalVersion")

  runtimeOnly("org.apache.logging.log4j:log4j-slf4j18-impl:2.17.1")

  testImplementation("org.mockito:mockito-inline:4.3.1")
  testImplementation(platform("org.junit:junit-bom:5.8.2"))
  testImplementation("org.junit.jupiter:junit-jupiter-api")
  testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
}

java.sourceCompatibility = JavaVersion.VERSION_17

tasks {
  test {
    useJUnitPlatform()
    ignoreFailures = true
  }
  withType<KotlinCompile> {
    kotlinOptions {
      jvmTarget = JavaVersion.VERSION_17.toString()
      freeCompilerArgs = listOf("-opt-in=kotlin.RequiresOptIn")
    }
  }
  testlogger {
    theme = MOCHA_PARALLEL
  }
}

moshi {
  enableSealed.set(true)
}
