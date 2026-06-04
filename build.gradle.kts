/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
plugins {
  id("revoman.root-conventions")
  id("revoman.publishing-conventions")
  id("revoman.kt-conventions")
  alias(libs.plugins.moshix)
  alias(libs.plugins.node.gradle)
  alias(libs.plugins.kover)
  alias(libs.plugins.nexus.publish)
}

val mockitoAgent = configurations.create("mockitoAgent")

dependencies {
  api(platform(libs.http4k.bom))
  api(libs.bundles.http4k)
  api(libs.moshix.adapters)
  api(libs.java.vavr)
  api(libs.kotlin.vavr)
  api(libs.arrow.core)
  api(libs.kotlinx.datetime)
  implementation(libs.bundles.kotlin.logging)
  implementation(libs.pprint)
  implementation(libs.graal.js)
  implementation(libs.datafaker)
  implementation(libs.underscore)
  implementation(libs.okio.jvm)
  implementation(libs.spring.beans)
  implementation(libs.snakeyaml)
  kapt(libs.immutables.value)
  compileOnly(libs.immutables.builder)
  compileOnly(libs.immutables.value.annotations)
  compileOnly(libs.jetbrains.annotations)
  testImplementation(libs.truth)
  testImplementation(libs.json.assert)
  mockitoAgent(libs.mockito.core) { isTransitive = false }
  testImplementation(libs.mockk)
}

testing {
  suites {
    getByName<JvmTestSuite>("test") { useJUnitJupiter(libs.versions.junit.get()) }

    register<JvmTestSuite>("integrationTest") {
      dependencies {
        implementation(project(":"))
        implementation(libs.truth)
        implementation(libs.mockito.core)
        implementation(libs.spring.beans)
        implementation(libs.json.assert)
        implementation(libs.assertj.vavr)
      }
    }
  }
}

node {
  nodeProjectDir = file("${project.projectDir}/js")
  download = true
}

// Regenerates the vendored Postman sandbox resources from a pinned postman-sandbox version.
// These resources ARE committed (so consumers need no Node at runtime — JVM-first). To upgrade:
// bump pmSandboxVersion, run `./gradlew generatePmSandbox`, commit the changed resources.
val pmSandboxVersion = "6.7.0"
tasks.register<Exec>("generatePmSandbox") {
    group = "postman"
    description = "Regenerate vendored postman-sandbox bootcode resources (pinned $pmSandboxVersion)"
    val outDir = layout.projectDirectory.dir("src/main/resources/postman-sandbox")
    workingDir = layout.buildDirectory.dir("pm-sandbox-gen").get().asFile
    doFirst { workingDir.mkdirs() }
    commandLine(
        "bash", "-c",
        """
        set -e
        npm init -y >/dev/null 2>&1 || true
        npm install postman-sandbox@$pmSandboxVersion postman-collection >/dev/null 2>&1
        mkdir -p "${'$'}{OUT}"
        node -e "require('./node_modules/postman-sandbox/.cache/bootcode.browser.js')((e,c)=>{if(e)throw e;require('fs').writeFileSync(process.env.OUT+'/bootcode.js',c)})"
        node -e "require('fs').writeFileSync(process.env.OUT+'/bridge-client.js', require('./node_modules/uvm/lib/bridge-client')())"
        node -e "require('fs').writeFileSync(process.env.OUT+'/pm-sandbox-version.txt', require('./node_modules/postman-sandbox/package.json').version)"
        """.trimIndent()
    )
    environment("OUT", outDir.asFile.absolutePath)
}

tasks {
  check { dependsOn(npmInstall) }
  test {
    dependsOn(npmInstall)
    jvmArgs("-javaagent:${mockitoAgent.singleFile.absolutePath}")
  }
  named<Test>("integrationTest") { jvmArgs("-javaagent:${mockitoAgent.singleFile.absolutePath}") }
}

kover { reports { total { html { onCheck = true } } } }

moshi { enableSealed = true }

nexusPublishing {
  this.repositories {
    sonatype {
      stagingProfileId = STAGING_PROFILE_ID
      nexusUrl = uri("https://ossrh-staging-api.central.sonatype.com/service/local/")
      snapshotRepositoryUrl = uri("https://central.sonatype.com/repository/maven-snapshots/")
    }
  }
}
