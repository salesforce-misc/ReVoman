import com.adarshr.gradle.testlogger.theme.ThemeType.MOCHA_PARALLEL
import com.diffplug.spotless.extra.wtp.EclipseWtpFormatterStep.XML
import io.gitlab.arturbosch.detekt.Detekt
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
  kotlin("jvm")
  application
  id("dev.zacsweers.moshix") version "0.17.1"
  `maven-publish`
  id("io.gitlab.arturbosch.detekt") version "1.20.0"
  id("com.adarshr.test-logger") version "3.2.0"
  id("com.diffplug.spotless") version "6.4.2"
  id("org.barfuin.gradle.taskinfo") version "1.4.0"
}

group = "com.salesforce.ccspayments"
version = "0.1.6"

repositories {
  mavenCentral()
}

dependencies {
  val http4kVersion: String by project
  implementation("org.http4k:http4k-core:$http4kVersion")
  implementation("org.http4k:http4k-format-moshi:$http4kVersion")
  implementation("dev.zacsweers.moshix:moshi-adapters:0.17.1")
  implementation("org.slf4j:slf4j-api:1.7.36")
  implementation("org.apache.commons:commons-lang3:3.12.0")
  val graalVersion = "22.1.0"
  implementation("org.graalvm.sdk:graal-sdk:$graalVersion")
  implementation("org.graalvm.js:js:$graalVersion")
  implementation("io.github.serpro69:kotlin-faker:1.10.0")
  implementation("com.github.javadev:underscore:1.77")
  runtimeOnly("org.apache.logging.log4j:log4j-slf4j18-impl:2.17.2")
  testImplementation("org.junit.jupiter:junit-jupiter-api:5.8.2")
  runtimeOnly("org.junit.jupiter:junit-jupiter-engine:5.8.2")
  testImplementation("org.assertj:assertj-core:3.22.0")
  val kotestVersion = "5.3.0"
  testImplementation("io.kotest:kotest-runner-junit5:$kotestVersion")
  testImplementation("io.kotest:kotest-assertions-core:$kotestVersion")
}

tasks {
  test.get().useJUnitPlatform()
  withType<KotlinCompile> {
    kotlinOptions {
      jvmTarget = JavaVersion.VERSION_11.toString()
      freeCompilerArgs = listOf("-opt-in=kotlin.RequiresOptIn")
    }
  }
  compileTestJava {
    sourceCompatibility = JavaVersion.VERSION_17.toString()
    targetCompatibility = JavaVersion.VERSION_17.toString()
  }
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
  publications.create<MavenPublication>("revoman") {
    val subprojectJarName = tasks.jar.get().archiveBaseName.get()
    artifactId = if (subprojectJarName == "revoman-root") "revoman" else "revoman-$subprojectJarName"
    from(components["java"])
    pom {
      name.set(artifactId)
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
