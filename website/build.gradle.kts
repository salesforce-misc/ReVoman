plugins { alias(libs.plugins.node.gradle) }

node {
  nodeProjectDir = file("${project.projectDir}")
  download = true
  version = "22.16.0"
}

tasks {
  register<com.github.gradle.node.npm.task.NpmTask>("websiteBuild") {
    dependsOn(npmInstall)
    args = listOf("run", "build")
    description = "Build the documentation website"
    group = "website"
  }
  register<com.github.gradle.node.npm.task.NpmTask>("websiteDev") {
    dependsOn(npmInstall)
    args = listOf("run", "dev")
    description = "Start documentation website dev server"
    group = "website"
  }
  register<com.github.gradle.node.npm.task.NpmTask>("websitePreview") {
    dependsOn(npmInstall)
    args = listOf("run", "preview")
    description = "Preview production documentation website build"
    group = "website"
  }
}
