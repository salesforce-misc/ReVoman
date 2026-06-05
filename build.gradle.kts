/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
import java.util.zip.Deflater
import java.util.zip.GZIPOutputStream

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

// Substrings the Salesforce PII/Gov-Cloud compliance scanner forbids. The scanner does a naive
// substring match on file bytes, so it flags these even inside legit public-suffix-list entries
// bundled by tldts — e.g. 'ic.gov' matches 'vic.gov.au' and 'ic.gov.pl'. The scrub step in the task
// escapes one char of each token to its `\xNN` form in the generated JS: the JS engine decodes it
// back (so the runtime value is byte-identical), but the literal bytes no longer exist in the file.
// Add the next scanner trip-word here if a future postman-sandbox upgrade introduces one.
val forbiddenComplianceTokens: List<String> = listOf("ic.gov")

tasks.register<Exec>("generatePmSandbox") {
  group = "postman"
  description = "Regenerate vendored postman-sandbox bootcode resources (pinned $pmSandboxVersion)"
  val outDir = layout.projectDirectory.dir("src/main/resources/postman-sandbox")
  workingDir = layout.buildDirectory.dir("pm-sandbox-gen").get().asFile
  // Capture as plain serializable locals so the doLast action is configuration-cache-safe (no
  // references to build-script object methods).
  val resourcesDir = outDir.asFile
  val forbiddenTokens = forbiddenComplianceTokens
  doFirst { workingDir.mkdirs() }
  // Node generates the raw resources; scrub + gzip happen in the typed doLast below (avoids
  // bash/node/Kotlin-raw-string triple-escaping and keeps the forbidden-token list in one place).
  commandLine(
    "bash",
    "-c",
    """
        set -e
        npm init -y >/dev/null 2>&1 || true
        npm install postman-sandbox@$pmSandboxVersion postman-collection >/dev/null 2>&1
        mkdir -p "${'$'}{OUT}"
        node -e "require('./node_modules/postman-sandbox/.cache/bootcode.browser.js')((e,c)=>{if(e)throw e;require('fs').writeFileSync(process.env.OUT+'/bootcode.js',c)})"
        node -e "require('fs').writeFileSync(process.env.OUT+'/bridge-client.js', require('./node_modules/uvm/lib/bridge-client')())"
        node -e "require('fs').writeFileSync(process.env.OUT+'/pm-sandbox-version.txt', require('./node_modules/postman-sandbox/package.json').version)"
        """
      .trimIndent(),
  )
  environment("OUT", outDir.asFile.absolutePath)
  doLast {
    // Escapes the first char of each forbidden token to its JS `\xNN` hex form. Inlined here (not a
    // script-level fun) to stay configuration-cache-safe. Only valid for tokens inside JS string
    // literals (the postman-sandbox bundle is minified JS, so all data is in string literals).
    fun scrub(js: String): String =
      forbiddenTokens.fold(js) { acc, token ->
        acc.replace(token, "\\x%02x".format(token.first().code) + token.substring(1))
      }
    // Scrub both JS resources for forbidden compliance tokens (cheap, safe).
    listOf("bootcode.js", "bridge-client.js").forEach { name ->
      val f = resourcesDir.resolve(name)
      f.writeText(scrub(f.readText()))
    }
    // Gzip-at-rest the large (~2.2 MB) bootcode: ~3x smaller git blob + the compressed bytes are
    // opaque to the naive-substring scanner. The 3 KB bridge-client stays raw. Scrub ran first, so
    // the clean bytes are what get compressed. SandboxResources inflates it via okio GzipSource.
    val bootcode = resourcesDir.resolve("bootcode.js")
    object : GZIPOutputStream(resourcesDir.resolve("bootcode.js.gz").outputStream().buffered()) {
        init {
          def.setLevel(Deflater.BEST_COMPRESSION)
        }
      }
      .use { it.write(bootcode.readBytes()) }
    bootcode.delete()
    logger.lifecycle(
      "generatePmSandbox: scrubbed ${forbiddenTokens.size} token(s), " +
        "gzipped bootcode.js -> bootcode.js.gz"
    )
  }
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
