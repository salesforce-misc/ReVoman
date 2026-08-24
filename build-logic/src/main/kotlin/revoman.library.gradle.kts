plugins {
  id("revoman.kotlin-jvm")
  kotlin("kapt")
  `java-library`
  `maven-publish`
  signing
}

kapt { useBuildCache = true }

val groupId = providers.gradleProperty("revoman.group")
val releaseVersion = providers.gradleProperty("revoman.version")
val artifactIdValue = providers.gradleProperty("revoman.artifactId")

group = groupId.get()
version = releaseVersion.get()
description = "ReVoman - A template-driven API automation tool for JVM (Java/Kotlin)"

base { archivesName.set(artifactIdValue) }

java {
  withJavadocJar()
  withSourcesJar()
}

// Preserve the published sources artifact's historical `main/...` entry layout and contents.
tasks.named<Jar>("sourcesJar") {
  include("**/*.kt")
  includeEmptyDirs = false
  eachFile { path = "main/$path" }
}

// The published Gradle metadata historically lists the same sources artifact twice. Retain that
// harmless duplicate so the migration leaves the module metadata byte-for-byte compatible.
configurations.named("sourcesElements") { outgoing.artifact(tasks.named("sourcesJar")) }

publishing {
  publications.create<MavenPublication>("revoman") {
    artifactId = artifactIdValue.get()
    from(components["java"])
    pom {
      name.set("revoman")
      description.set(project.description)
      url.set("https://github.com/salesforce-misc/ReVoman")
      inceptionYear.set("2023")
      licenses {
        license {
          name.set("The Apache License, Version 2.0")
          url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
        }
      }
      developers {
        developer {
          id.set("overfullstack")
          name.set("Gopal S Akshintala")
          email.set("gopalakshintala@gmail.com")
        }
      }
      scm {
        connection.set("scm:git:https://github.com/salesforce-misc/ReVoman")
        developerConnection.set("scm:git:git@github.com/salesforce-misc/ReVoman.git")
        url.set("https://github.com/salesforce-misc/ReVoman")
      }
    }
  }
}

signing { sign(publishing.publications["revoman"]) }

tasks.javadoc {
  isFailOnError = false
  options.encoding("UTF-8")
}
