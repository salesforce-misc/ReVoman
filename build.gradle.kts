import com.adarshr.gradle.testlogger.theme.ThemeType.MOCHA_PARALLEL
import com.diffplug.spotless.extra.wtp.EclipseWtpFormatterStep.XML
import io.gitlab.arturbosch.detekt.Detekt
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
  kotlin("jvm")
  kotlin("kapt")
  application
  id("dev.zacsweers.moshix")
  `maven-publish`
  id("io.gitlab.arturbosch.detekt") version "1.21.0"
  id("com.adarshr.test-logger") version "3.2.0"
  id("com.diffplug.spotless") version "6.8.0"
}

allprojects {
  group = "com.salesforce.ccspayments"
  version = "0.2.1"
  repositories {
    mavenCentral()
  }
  apply(plugin = "org.jetbrains.kotlin.jvm")
  apply(plugin = "com.diffplug.spotless")
  spotless {
    kotlin {
      target("src/main/java/**/*.kt", "src/test/java/**/*.kt")
      targetExclude("$buildDir/generated/**/*.*")
      ktlint()
        .setUseExperimental(true)
        .editorConfigOverride(mapOf("indent_size" to "2", "continuation_indent_size" to "2"))
    }
    kotlinGradle {
      target("*.gradle.kts")
      ktlint()
        .setUseExperimental(true)
        .editorConfigOverride(mapOf("indent_size" to "2", "continuation_indent_size" to "2"))
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
    withType<Test> {
      useJUnitPlatform()
    }
    withType<KotlinCompile> {
      kotlinOptions {
        jvmTarget = JavaVersion.VERSION_11.toString()
        freeCompilerArgs = listOf("-Xjvm-default=all")
      }
    }
    java {
      sourceCompatibility = JavaVersion.VERSION_11
    }
  }
  dependencies {
    val testImplementation by configurations
    testImplementation(project(":"))
    testImplementation(platform("org.junit:junit-bom:5.8.2"))
    testImplementation("org.junit.jupiter:junit-jupiter-api")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    testImplementation("org.assertj:assertj-core:3.23.1")
  }
}

dependencies {
  implementation("org.jetbrains:annotations:23.0.0")
  val http4kVersion: String by project
  implementation("org.http4k:http4k-core:$http4kVersion")
  implementation("org.http4k:http4k-format-moshi:$http4kVersion")
  val moshiXVersion: String by project
  implementation("dev.zacsweers.moshix:moshi-adapters:$moshiXVersion")
  implementation("org.slf4j:slf4j-api:1.7.36")
  implementation("org.apache.commons:commons-lang3:3.12.0")
  val graalVersion: String by project
  implementation("org.graalvm.sdk:graal-sdk:$graalVersion")
  implementation("org.graalvm.js:js:$graalVersion")
  implementation("io.github.serpro69:kotlin-faker:1.11.0")
  implementation("com.github.javadev:underscore:1.78")
  val immutablesVersion: String by project
  kapt("org.immutables:value:$immutablesVersion")
  compileOnly("org.immutables:builder:$immutablesVersion")
  compileOnly("org.immutables:value-annotations:$immutablesVersion")
  runtimeOnly("org.apache.logging.log4j:log4j-slf4j18-impl:2.17.2")
  testImplementation("org.junit.jupiter:junit-jupiter-api:5.8.2")
  runtimeOnly("org.junit.jupiter:junit-jupiter-engine:5.8.2")
  testImplementation("org.assertj:assertj-core:3.23.1")
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
moshi {
  enableSealed.set(true)
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
