import com.adarshr.gradle.testlogger.theme.ThemeType.MOCHA_PARALLEL
import com.diffplug.spotless.extra.wtp.EclipseWtpFormatterStep.XML
import io.gitlab.arturbosch.detekt.Detekt
import org.jetbrains.kotlin.gradle.targets.js.npm.tasks.KotlinNpmInstallTask

plugins {
  kotlin("multiplatform")
  application
  id("dev.zacsweers.moshix") version "0.17.1"
  `maven-publish`
  id("io.gitlab.arturbosch.detekt") version "1.20.0"
  id("com.adarshr.test-logger") version "3.2.0"
  id("com.diffplug.spotless") version "6.4.2"
  id("org.barfuin.gradle.taskinfo") version "1.4.0"
}

group = "com.salesforce.ccspayments"
version = "0.1.3"

repositories {
  mavenCentral()
}

kotlin {
  jvm {
    val main by compilations.getting {
      kotlinOptions {
        jvmTarget = JavaVersion.VERSION_11.toString()
        freeCompilerArgs = listOf("-opt-in=kotlin.RequiresOptIn")
      }
    }
    val test by compilations.getting {
      kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
        freeCompilerArgs = listOf("-opt-in=kotlin.RequiresOptIn")
      }
    }
    withJava()
    testRuns["test"].executionTask.configure {
      useJUnitPlatform()
    }
  }
  js(IR) {
    nodejs()
    useCommonJs()
  }
  sourceSets {
    val jvmMain by getting {
      dependencies {
        val http4kVersion: String by project
        api("org.http4k:http4k-core:$http4kVersion")
        api("org.http4k:http4k-format-moshi:$http4kVersion")
        api("dev.zacsweers.moshix:moshi-adapters:0.17.1")
        api("org.slf4j:slf4j-api:1.7.36")
        val graalVersion = "22.1.0"
        api("org.graalvm.sdk:graal-sdk:$graalVersion")
        api("org.graalvm.js:js:$graalVersion")
        api("io.github.serpro69:kotlin-faker:1.10.0")

        runtimeOnly("org.apache.logging.log4j:log4j-slf4j18-impl:2.17.2")
      }
    }
    val jvmTest by getting {
      dependencies {
        implementation("org.junit.jupiter:junit-jupiter-api:5.8.2")
        runtimeOnly("org.junit.jupiter:junit-jupiter-engine:5.8.2")
        implementation("org.assertj:assertj-core:3.22.0")
        val kotestVersion = "5.3.0"
        implementation("io.kotest:kotest-runner-junit5:$kotestVersion")
        implementation("io.kotest:kotest-assertions-core:$kotestVersion")
      }
    }
    val commonMain by getting {
      dependencies {
        implementation(npm("moment", "^2.29.3"))
        implementation(npm("xml2js", "^0.4.23"))
        implementation(npm("path-browserify", "^1.0.1"))
        implementation(npm("events", "^3.3.0"))
        implementation(npm("timers", "^0.1.1"))
      }
    }
  }
}
tasks {
  val copyNodeModules = register<Copy>("copyNodeModules") {
    val kotlinNpmInstallTask = getByName<KotlinNpmInstallTask>(KotlinNpmInstallTask.NAME)
    dependsOn(kotlinNpmInstallTask)
    from(kotlinNpmInstallTask.nodeModulesDir.parent) {
      include("node_modules/**")
    }.into(projectDir)
  }
  getByName<KotlinNpmInstallTask>(KotlinNpmInstallTask.NAME).finalizedBy(copyNodeModules)
  getByName<Jar>("jvmJar") {
    val kotlinNpmInstallTask = getByName<KotlinNpmInstallTask>(KotlinNpmInstallTask.NAME)
    dependsOn(kotlinNpmInstallTask)
    from(kotlinNpmInstallTask.nodeModulesDir.parent) {
      include("node_modules/**")
    }
  }
  getByName<Zip>("distZip").enabled = false
  getByName<Tar>("distTar").enabled = false
  getByName<Task>("jsGenerateExternalsIntegrated").enabled = false
  testlogger {
    theme = MOCHA_PARALLEL
  }
  register<Detekt>("detektAll") {
    parallel = true
    ignoreFailures = false
    autoCorrect = false
    buildUponDefaultConfig = true
    basePath = projectDir.toString()
    setSource(subprojects.map { it.the<SourceSetContainer>()["main"].allSource.srcDirs })
    include("**/*.kt")
    include("**/*.kts")
    exclude("**/resources/**")
    exclude("**/build/**")
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
    baseline.set(File("$rootDir/config/baseline.xml"))
  }
  withType<Detekt>().configureEach {
    reports {
      xml.required.set(true)
    }
  }
  withType<PublishToMavenRepository>().configureEach {
    doLast {
      logger.lifecycle("Successfully uploaded ${publication.groupId}:${publication.artifactId}:${publication.version} to ${repository.name}")
    }
  }
  withType<PublishToMavenLocal>().configureEach {
    doLast {
      logger.lifecycle("Successfully uploaded ${publication.groupId}:${publication.artifactId}:${publication.version} to MavenLocal.")
    }
  }
}
moshi {
  enableSealed.set(true)
}
spotless {
  kotlin {
    target("src/main/java/**/*.kt", "src/test/java/**/*.kt")
    targetExclude("$buildDir/generated/**/*.*")
    ktlint().userData(mapOf("indent_size" to "2", "continuation_indent_size" to "2"))
  }
  kotlinGradle {
    target("*.gradle.kts")
    ktlint().userData(mapOf("indent_size" to "2", "continuation_indent_size" to "2"))
  }
  java {
    toggleOffOn()
    target("src/main/java/**/*.java", "src/test/java/**/*.java")
    targetExclude("$buildDir/generated/**/*.*")
    importOrder()
    removeUnusedImports()
    googleJavaFormat()
    trimTrailingWhitespace()
    indentWithSpaces(2)
    endWithNewline()
  }
  format("xml") {
    targetExclude("pom.xml")
    target("*.xml")
    eclipseWtp(XML)
  }
  format("documentation") {
    target("*.md", "*.adoc")
    trimTrailingWhitespace()
    indentWithSpaces(2)
    endWithNewline()
  }
}
publishing {
  publications.withType<MavenPublication> {
    if (name == "jvm") {
      artifactId = "revoman"
    }
  }
  publications.create<MavenPublication>("revoman") {
    pom {
      name.set("revoman")
      description.set(project.description)
      url.set("https://git.soma.salesforce.com/CCSPayments/ReVoman")
      licenses {
        license {
          name.set("The Apache License, Version 2.0")
          url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
        }
      }
      developers {
        developer {
          id.set("gopala.akshintala@salesforce.com")
          name.set("Gopal S Akshintala")
          email.set("gopala.akshintala@salesforce.com")
        }
      }
      scm {
        connection.set("scm:git:https://git.soma.salesforce.com/ccspayments/ReVoman")
        developerConnection.set("scm:git:git@git.soma.salesforce.com:ccspayments/ReVoman.git")
        url.set("https://git.soma.salesforce.com/ccspayments/revoman")
      }
    }
  }
  repositories {
    maven {
      name = "Nexus"
      val releasesRepoUrl =
        uri("https://nexus.soma.salesforce.com/nexus/content/repositories/releases")
      val snapshotsRepoUrl =
        uri("https://nexus.soma.salesforce.com/nexus/content/repositories/snapshots")
      url = if (version.toString().endsWith("SNAPSHOT")) snapshotsRepoUrl else releasesRepoUrl
      val nexusUsername: String by project
      val nexusPassword: String by project
      credentials {
        username = nexusUsername
        password = nexusPassword
      }
    }
  }
}
