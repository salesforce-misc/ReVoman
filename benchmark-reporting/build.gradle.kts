import org.gradle.api.tasks.testing.Test

plugins { id("revoman.benchmark-reporting") }

tasks.withType<Test>().configureEach {
  systemProperty("revoman.projectRoot", rootProject.projectDir.absolutePath)
}
