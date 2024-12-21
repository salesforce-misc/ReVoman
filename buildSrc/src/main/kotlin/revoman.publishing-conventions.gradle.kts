/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */

plugins {
  `maven-publish`
  signing
  `java-library`
}

group = GROUP_ID

version = VERSION

description = "ReVoman - A template-driven API automation tool for JVM (Java/Kotlin)"

repositories { mavenCentral() }

java {
  withJavadocJar()
  withSourcesJar()
}

publishing {
  publications.create<MavenPublication>("revoman") {
    artifactId = ARTIFACT_ID
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

tasks {
  javadoc {
    // TODO 22/05/21 gopala.akshintala: Turn this on after writing all javadocs
    isFailOnError = false
    options.encoding("UTF-8")
  }
}
