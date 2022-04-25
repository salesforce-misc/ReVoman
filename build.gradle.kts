import com.adarshr.gradle.testlogger.theme.ThemeType.MOCHA_PARALLEL
import com.diffplug.spotless.extra.wtp.EclipseWtpFormatterStep.XML
import io.gitlab.arturbosch.detekt.Detekt

plugins {
  kotlin("multiplatform")
  application
  id("dev.zacsweers.moshix") version "0.17.1"
  `maven-publish`
  id("io.gitlab.arturbosch.detekt") version "1.20.0"
  id("com.adarshr.test-logger") version "3.2.0"
  id("com.diffplug.spotless") version "6.4.2"
}

group = "com.salesforce.ccspayments"
version = "0.1.1"

repositories {
  mavenCentral()
}

kotlin {
  jvm {
    compilations.all {
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
    binaries.executable()
    browser {
      commonWebpackConfig {
        cssSupport.enabled = true
      }
    }
  }
  sourceSets {
    val jvmMain by getting {
      dependencies {
        val http4kVersion: String by project
        api("org.http4k:http4k-core:$http4kVersion")
        api("org.http4k:http4k-format-moshi:$http4kVersion")
        api("dev.zacsweers.moshix:moshi-adapters:0.17.1")
        
        implementation("org.slf4j:slf4j-api:1.7.36")
        val graalVersion = "22.0.0.2"
        implementation("org.graalvm.sdk:graal-sdk:$graalVersion")
        implementation("org.graalvm.js:js:$graalVersion")

        runtimeOnly("org.apache.logging.log4j:log4j-slf4j18-impl:2.17.2")
      }
    }
    val jvmTest by getting {
      dependencies {
        implementation("org.mockito:mockito-inline:4.4.0")
        implementation("org.junit.jupiter:junit-jupiter-api:5.8.2")
        runtimeOnly("org.junit.jupiter:junit-jupiter-engine:5.8.2")
        implementation("org.assertj:assertj-core:3.22.0")
      }
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

tasks {
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
publishing {
  publications.create<MavenPublication>("mavenJava") {
    val subprojectJarName = tasks.jar.get().archiveBaseName.get()
    artifactId = if (subprojectJarName == "revoman-root") "revoman" else "revoman-$subprojectJarName"
    from(components["java"])
    pom {
      name.set(artifactId)
      description.set(project.description)
      url.set("https://git.soma.salesforce.com/CCSPayments/Pokemon")
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
        connection.set("scm:git:https://git.soma.salesforce.com/ccspayments/Pokemon")
        developerConnection.set("scm:git:git@git.soma.salesforce.com:ccspayments/Pokemon.git")
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
