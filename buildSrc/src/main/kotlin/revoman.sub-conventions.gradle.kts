import com.adarshr.gradle.testlogger.theme.ThemeType.MOCHA
import org.gradle.kotlin.dsl.kotlin
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
  java
  `maven-publish`
  signing
  id("com.adarshr.test-logger")
}
repositories {
  mavenCentral()
}
java {
  toolchain {
    languageVersion.set(JavaLanguageVersion.of(11))
  }
}
tasks {
  test.get().useJUnitPlatform()
  testlogger.theme = MOCHA
  withType<Jar> { duplicatesStrategy = DuplicatesStrategy.EXCLUDE }
  withType<PublishToMavenRepository>().configureEach {
    doLast {
      logger.lifecycle("Successfully uploaded ${publication.groupId}:${publication.artifactId}:${publication.version} to ${repository.name}")
    }
  }
  withType<PublishToMavenLocal>().configureEach {
    doLast {
      logger.lifecycle("Successfully created ${publication.groupId}:${publication.artifactId}:${publication.version} in MavenLocal")
    }
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
}
