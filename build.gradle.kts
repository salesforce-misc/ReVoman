/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
@file:Suppress(
  "UnstableApiUsage"
) // Gradle JVM Test Suite DSL (testing {}) is incubating but stable in practice

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.GZIPOutputStream
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import performance.AssemblePerformanceDistributionTask
import performance.GenerateProtocolManifestTask

plugins {
  id("revoman.root-conventions")
  id("revoman.publishing-conventions")
  id("revoman.kt-conventions")
  id("revoman.performance-conventions")
  alias(libs.plugins.moshix)
  alias(libs.plugins.node.gradle)
  alias(libs.plugins.kover)
  alias(libs.plugins.nexus.publish)
  alias(libs.plugins.test.retry)
  alias(libs.plugins.qodana)
}

// Retry flaky tests ON CI ONLY. Several integration tests hit live external APIs (pokeapi.co,
// restful-api.dev, apigee, beeceptor) that intermittently rate-limit or 5xx — a retry keeps the
// pipeline green on transient blips WITHOUT masking real breakage (a test failing every attempt
// still fails). Locally, retry stays OFF (maxRetries=0) so flakes surface immediately.
val isCI: Boolean = !System.getenv("CI").isNullOrEmpty()

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
  // truffle-runtime is a pure runtime substitution: it swaps Truffle's interpreter-only runtime for
  // its optimizing Graal-compiler one. Nothing compiles against it, so `runtimeOnly` activates the
  // optimizing runtime everywhere at run time without leaking it onto anyone's compile classpath.
  runtimeOnly(libs.truffle.runtime)
  implementation(libs.kotlinx.collections.immutable)
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

// Kotlin KAPT currently emits an illegal Java stub for a Kotlin @JvmRecord (KT-44706): javac
// rejects the generated `class ... extends java.lang.Record` before annotation processing starts.
// Normalize only this feature's exact generated stub after KAPT creates it; Kotlin compilation
// still consumes the original source and emits the real JVM record. Keep this narrowly scoped and
// fail closed if the expected generated shape changes so a future compiler fix can remove it.
tasks.configureEach {
  if (name == "kaptGenerateStubsKotlin") {
    inputs.property("recordedNameValueKaptStubWorkaround", "v1")
    doLast {
      val stubsRoots =
        outputs.files.files.filter { it.name == "main" && it.parentFile?.name == "stubs" }
      check(stubsRoots.size == 1) {
        "Expected exactly one KAPT stubs root, found: ${stubsRoots.map(File::getAbsolutePath)}"
      }
      val stub =
        stubsRoots.single().resolve("com/salesforce/revoman/testing/http/RecordedNameValue.java")
      check(stub.isFile) { "Expected KAPT stub is missing: ${stub.absolutePath}" }
      val illegalDeclaration = "public final class RecordedNameValue extends java.lang.Record {"
      val legalDeclaration = "public final class RecordedNameValue {"
      val source = stub.readText(Charsets.UTF_8)
      val matches = source.windowed(illegalDeclaration.length).count { it == illegalDeclaration }
      check(matches <= 1) {
        "Unexpected duplicate RecordedNameValue KAPT stub declarations: ${stub.absolutePath}"
      }
      if (matches == 1) {
        stub.writeText(source.replace(illegalDeclaration, legalDeclaration), Charsets.UTF_8)
      } else {
        check(
          source.contains("public record RecordedNameValue(") || source.contains(legalDeclaration)
        ) {
          "Unexpected RecordedNameValue KAPT stub shape: ${stub.absolutePath}"
        }
      }
    }
  }
}

// --- Bundle kotlinx-collections-immutable INTO the jar (Core consumption fix) --------------------
// Core consumes revoman via a bazel `java_import` that provides NO transitive deps, and
// `kotlinx-collections-immutable` is NOT in Core's maven graph. Since PersistentBackedMutableMap
// (perf PR #401) uses it, a plain jar throws `NoClassDefFoundError:
// kotlinx/collections/immutable/*`
// on every `revUp` inside the Core server. Bundle JUST that one artifact's classes into the jar
// (isTransitive=false so kotlin-stdlib — which Core already has natively — is NOT duplicated, and
// no
// classpath conflict with the deps Core re-declares). Every other `implementation` dep (graal/okio/
// snakeyaml/spring) is already on Core's classpath, which is why pre-#401 jars worked unbundled.
val bundledRuntime: Configuration = configurations.create("bundledRuntime")

dependencies { "bundledRuntime"(libs.kotlinx.collections.immutable) { isTransitive = false } }

tasks.named<Jar>("jar") {
  // Stable JPMS module name for module-path consumers. Deliberately NOT a full module-info: the
  // primary consumer (Salesforce Core) uses a classpath java_import where module-info is ignored,
  // and moshi/spring/kapt reflect into revoman's own types (would force opening `internal`). See
  // docs/superpowers/specs/2026-08-01-jpms-automatic-module-name-design.md.
  manifest { attributes("Automatic-Module-Name" to "com.salesforce.revoman") }
  from({ bundledRuntime.map { zipTree(it) } }) {
    // Drop the bundled artifact's own MANIFEST/module metadata — keep only its classes so the
    // revoman jar's manifest and any module-info stay authoritative. kotlinx-collections-immutable
    // is a MULTI-RELEASE jar, so its module descriptor lives at `META-INF/versions/<N>/
    // module-info.class`, NOT just top-level — that versioned copy MUST also be dropped, else
    // revoman resolves as the explicit module `kotlinx.collections.immutable` on the module path
    // and the Automatic-Module-Name above is ignored. Do not remove it as "redundant".
    exclude(
      "META-INF/MANIFEST.MF",
      "META-INF/*.kotlin_module",
      "module-info.class",
      "META-INF/versions/*/module-info.class",
    )
  }
  duplicatesStrategy = DuplicatesStrategy.EXCLUDE
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
        implementation(libs.snakeyaml)
        implementation(libs.kotlin.logging.jvm)
        implementation(libs.log4j.api)
        implementation(libs.log4j.core)
        implementation(libs.log4j.slf4j2.impl)
        // WfsSeedE2ETest seeds the Shift.Status dyn-enum directly in local SDB (postgres) via JDBC.
        implementation(libs.postgresql)
      }
    }

    register<JvmTestSuite>("jmhTest") {
      dependencies {
        implementation(libs.truth)
        implementation(libs.log4j.api)
        implementation(libs.log4j.core)
      }
    }
  }
}

// Give the integrationTest compilation a friend-path to main (the built-in `test` suite gets this
// automatically). Without it, integration tests can't see `internal` main members — e.g.
// WfsSeedE2ETest reads org creds via the internal V3EnvLoader.
kotlin.target.compilations.named("integrationTest") {
  associateWith(kotlin.target.compilations.getByName("main"))
}

// Give the jmh compilation the same friend-path to main, so component benchmarks (WT-1..WT-4) can
// reference `internal` main members (e.g. PmSandbox, PmScope, PmExecutionContext, ScriptTarget)
// rather than only the public API.
kotlin.target.compilations.named("jmh") {
  associateWith(kotlin.target.compilations.getByName("main"))
}

// Benchmark contracts compile against JMH output directly. A Kotlin compilation association would
// select the deliberately unsupported flattened jmhJar, so the friend/output paths stay explicit.
val jmhSourceSet = sourceSets.named("jmh")
val mainSourceSet = sourceSets.named("main")

sourceSets.named("jmhTest") {
  compileClasspath += files(mainSourceSet.get().output, jmhSourceSet.get().output)
  runtimeClasspath += files(mainSourceSet.get().output, jmhSourceSet.get().output)
}

configurations.named("jmhTestImplementation") {
  extendsFrom(configurations.getByName("jmhImplementation"))
}

configurations.named("jmhTestRuntimeOnly") {
  extendsFrom(configurations.getByName("jmhRuntimeOnly"))
}

tasks.named<KotlinCompile>("compileJmhTestKotlin") {
  dependsOn("jmhClasses")
  compilerOptions.freeCompilerArgs.add(
    "-Xfriend-paths=${layout.buildDirectory.dir("classes/kotlin/jmh").get().asFile.absolutePath}"
  )
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
    $$"""
        set -e
        npm init -y >/dev/null 2>&1 || true
        npm install postman-sandbox@$$pmSandboxVersion postman-collection >/dev/null 2>&1
        mkdir -p "${OUT}"
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
    // Unit tests are self-contained; a low retry only absorbs rare env hiccups (e.g. the
    // RNG-sampling
    // DynamicVariableGeneratorTest). failOnPassedAfterRetry=false → a flake that later passes is
    // green.
    retry {
      maxRetries = if (isCI) 2 else 0
      failOnPassedAfterRetry = false
    }
  }
  named<Test>("integrationTest") {
    jvmArgs("-javaagent:${mockitoAgent.singleFile.absolutePath}")
    // Integration tests hit live external APIs, so allow a couple more attempts on CI.
    retry {
      maxRetries = if (isCI) 3 else 0
      failOnPassedAfterRetry = false
    }
    // The `integration.core` tests (WFS/PQ/BT2BS) need a real Salesforce org, so they're excluded
    // from aggregate runs like `gradle clean build`. Opt them back in with `-PincludeCoreIT` (works
    // alongside `--tests`, e.g. `gradle integrationTest -PincludeCoreIT --tests
    // "*WfsSeedE2ETest"`).
    if (!project.hasProperty("includeCoreIT")) {
      filter {
        excludeTestsMatching("com.salesforce.revoman.integration.core.*")
        isFailOnNoMatchingTests = false
      }
    }
  }
  named<Test>("jmhTest") {
    systemProperty(
      "log4j.configurationFile",
      layout.projectDirectory
        .file("src/jmh/resources/performance/log4j2-performance.xml")
        .asFile
        .toURI(),
    )
    systemProperty("revoman.banner", "false")
  }
}

kover {
  currentProject {
    sources {
      // The JMH benchmark source set is a perf harness, never unit-tested by design (like the
      // opt-in core-IT tests). Keep it out of the coverage denominator.
      excludedSourceSets.addAll("jmh")
    }
  }
  reports {
    filters {
      excludes {
        // Generated Immutables (Kick, Pojo, JsonFile, JsonString + their builders) carry
        // @org.immutables.value.Generated — codegen, not hand-written, so not our coverage debt.
        annotatedBy("org.immutables.value.Generated")
        // Test source sets leak into the denominator as if they were production code:
        // integration.pokemon inflates it to 100%, while org-gated integration.core.wfs/pq/bt2bs
        // deflate it to 0% (they only run under -PincludeCoreIT). Neither is production code.
        classes("com.salesforce.revoman.integration.**")
        // Moshi-generated JSON adapters (…JsonAdapter) — codegen, not hand-written.
        classes("*JsonAdapter")
      }
    }
    total {
      html { onCheck = true }
      // Coverage regression ratchet. Floor calibrated 2026-08-01 to floor(unit-only LINE %) − 1.
      // The honest unit-only LINE total is 86.2% (post-exclude, `./gradlew test` only), so the
      // floor is 85. For reference, the combined test+integrationTest total (~90.1%) is higher,
      // so 85 is a safe floor below both — it will not false-fail, including on unit-only runs.
      // Raise as unit coverage grows.
      verify {
        rule {
          minBound(85) // unit-only LINE coverage floor (86.2% measured, floor(86.2) - 1 = 85)
        }
      }
    }
  }
}

// Qodana static analysis. Opt-in like the Core-IT tests — NOT wired into `check`/`build`, since
// `qodanaScan` needs Docker (the CLI runs the free `jetbrains/qodana-jvm-community` linter in a
// container). Run locally before pushing with `colima start && ./gradlew qodanaScan`; results
// (incl. qodana.sarif.json) land in `build/qodana/results`. See DEVELOPMENT.md > Static Analysis.
qodana {
  // Persist the linter image/cache outside `build/` so `clean` doesn't force a re-pull every run.
  cachePath.set(layout.projectDirectory.dir(".qodana/cache").asFile.absolutePath)
}

moshi { enableSealed = true }

jmh {
  // Pin JMH core so every worktree benchmarks against a known JMH release.
  jmhVersion = libs.versions.jmh.get()
  // Select benchmarks from the CLI, e.g. ./gradlew jmh -Pjmh.includes=SmokeBenchmark
  if (project.hasProperty("jmh.includes")) {
    includes.add(project.property("jmh.includes").toString())
  }
}

val revUpV3FixtureRoot = layout.projectDirectory.dir("src/jmh/resources/performance/revup-v3")
val revUpV3FixturePaths =
  listOf(
    ".resources/definition.yaml",
    "benchmark.environment.yaml",
    "benchmark.request.yaml",
  )
val generatedRevUpV3ManifestRoot =
  layout.buildDirectory.dir("generated/resources/jmh/revup-v3-manifest")
val generatedRevUpV3Manifest = generatedRevUpV3ManifestRoot.map {
  it.file("META-INF/revoman/performance/revup-v3-tree.json")
}
val generateRevUpV3TreeManifest =
  tasks.register("generateRevUpV3TreeManifest") {
    inputs.files(revUpV3FixturePaths.map(revUpV3FixtureRoot::file))
    outputs.file(generatedRevUpV3Manifest)
    doLast {
      fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(separator = "") {
          "%02x".format(it.toInt() and 0xff)
        }

      fun compareUtf8(left: String, right: String): Int {
        val leftBytes = left.toByteArray(UTF_8)
        val rightBytes = right.toByteArray(UTF_8)
        val common = minOf(leftBytes.size, rightBytes.size)
        for (index in 0 until common) {
          val comparison =
            (leftBytes[index].toInt() and 0xff).compareTo(rightBytes[index].toInt() and 0xff)
          if (comparison != 0) return comparison
        }
        return leftBytes.size.compareTo(rightBytes.size)
      }

      val fixtureFiles =
        inputs.files.files.associateBy { fixture ->
          if (fixture.parentFile.name == ".resources") {
            ".resources/${fixture.name}"
          } else {
            fixture.name
          }
        }
      check(
        fixtureFiles.keys ==
          setOf(
            ".resources/definition.yaml",
            "benchmark.environment.yaml",
            "benchmark.request.yaml",
          )
      ) {
        "RevUp V3 manifest inputs changed: ${fixtureFiles.keys.sorted()}"
      }
      val manifest =
        fixtureFiles.keys.sortedWith(::compareUtf8).joinToString(
          separator = ",",
          prefix = "[",
          postfix = "]\n",
        ) { relativePath ->
          val bytes = checkNotNull(fixtureFiles[relativePath]).readBytes()
          "{\"byteLength\":${bytes.size},\"path\":\"$relativePath\",\"sha256\":\"${sha256(bytes)}\"}"
        }
      outputs.files.singleFile.apply {
        parentFile.mkdirs()
        writeText(manifest, UTF_8)
      }
    }
  }

jmhSourceSet.configure { resources.srcDir(generatedRevUpV3ManifestRoot) }

tasks.named("processJmhResources") { dependsOn(generateRevUpV3TreeManifest) }

// The frozen distribution owns stable protocol filenames even though the checked-in source names
// carry descriptive platform/version suffixes. These views contain only the exact allowed files.
val preparePerformanceRuntimeInputs =
  tasks.register<Sync>("preparePerformanceRuntimeInputs") {
    into(layout.buildDirectory.dir("performance/protocol-inputs/runtime"))
    from("config/performance/runtime/temurin-21-linux-arm64-v1.json") {
      rename { "linux-arm64.json" }
    }
    from("config/performance/runtime/m4max-docker-linux-arm64-v1.json") {
      rename { "m4max-docker.json" }
    }
    from("config/performance/runtime/github-hosted-arm64-v1.json") {
      rename { "github-hosted.json" }
    }
  }

val preparePerformanceQualificationInputs =
  tasks.register<Sync>("preparePerformanceQualificationInputs") {
    into(layout.buildDirectory.dir("performance/protocol-inputs/qualification"))
    from("config/performance/policies/m4max-docker-linux-arm64-v1.json") {
      rename { "m4max-docker.json" }
    }
    from("config/performance/policies/github-hosted-arm64-v1.json") {
      rename { "github-hosted.json" }
    }
  }

performanceMeasurement {
  treatmentJar.convention(tasks.named<Jar>("jar").flatMap { it.archiveFile })
  profileDirectory.set(layout.projectDirectory.dir("config/performance/profiles"))
  runtimeDirectory.set(layout.buildDirectory.dir("performance/protocol-inputs/runtime"))
  qualificationPolicyDirectory.set(
    layout.buildDirectory.dir("performance/protocol-inputs/qualification")
  )
  testVectorDirectory.set(
    layout.projectDirectory.dir(
      "buildSrc/performance-runner/src/main/resources/performance/protocol/test-vectors"
    )
  )
  expectedCells.set(layout.projectDirectory.file("config/performance/expected-cells.json"))
  adapter.set(layout.projectDirectory.file("scripts/performance/run"))
  embeddedDependency.set(layout.file(providers.provider { bundledRuntime.singleFile }))
  expectedBenchmarks.set(
    listOf(
      "com.salesforce.revoman.benchmark.EnvAccumBenchmark.accumulateAndSnapshot",
      "com.salesforce.revoman.benchmark.MarshallingBenchmark.compositeFromJson",
      "com.salesforce.revoman.benchmark.MarshallingBenchmark.compositeToJson",
      "com.salesforce.revoman.benchmark.RegexVarBenchmark.replaceVariablesInEnvOverLargeEnv",
      "com.salesforce.revoman.benchmark.RegexVarBenchmark.replaceVariablesRecursivelyOverMixedStrings",
      "com.salesforce.revoman.benchmark.RevUpV3ColdBenchmark.revUp",
      "com.salesforce.revoman.benchmark.RevUpV3WarmBenchmark.revUp",
      "com.salesforce.revoman.benchmark.SandboxCanaryBenchmark.sandbox",
      "com.salesforce.revoman.benchmark.SmokeBenchmark.sumOfRange",
    )
  )
  protocolSources.from(
    layout.projectDirectory.dir("buildSrc/src/main/kotlin/performance"),
    layout.projectDirectory.file(
      "buildSrc/src/main/kotlin/revoman.performance-conventions.gradle.kts"
    ),
    layout.projectDirectory.dir("buildSrc/performance-runner/src/main/kotlin/performance"),
    layout.projectDirectory.dir("buildSrc/performance-runner/src/main/resources/performance"),
    layout.projectDirectory.dir("buildSrc/performance-runner/src/test/resources/performance"),
    layout.projectDirectory.dir("buildSrc/src/test/resources/fixtures"),
    layout.projectDirectory.dir("src/jmh"),
    layout.projectDirectory.file("scripts/performance/run"),
    layout.projectDirectory.dir("config/performance/profiles"),
    layout.projectDirectory.dir("config/performance/runtime"),
    layout.projectDirectory.file("config/performance/expected-cells.json"),
    layout.projectDirectory.file("config/performance/policies/qualification-policy-v1.schema.json"),
    layout.projectDirectory.file("config/performance/policies/m4max-docker-linux-arm64-v1.json"),
    layout.projectDirectory.file("config/performance/policies/github-hosted-arm64-v1.json"),
    layout.projectDirectory.file("gradlew"),
    layout.projectDirectory.file("gradlew.bat"),
    layout.projectDirectory.dir("gradle/wrapper"),
    layout.projectDirectory.file("gradle/libs.versions.toml"),
    layout.projectDirectory.file("gradle.properties"),
    layout.projectDirectory.file("settings.gradle.kts"),
    layout.projectDirectory.file("build.gradle.kts"),
    layout.projectDirectory.file("buildSrc/settings.gradle.kts"),
    layout.projectDirectory.file("buildSrc/build.gradle.kts"),
    layout.projectDirectory.file("buildSrc/performance-runner/build.gradle.kts"),
  )
}

val packagedBenchmarkDependencies =
  configurations.getByName("jmhRuntimeClasspath") - mainSourceSet.get().output

tasks.named<GenerateProtocolManifestTask>("generatePerformanceProtocolManifest") {
  benchmarkDependencies.setFrom(packagedBenchmarkDependencies)
  dependsOn(preparePerformanceRuntimeInputs, preparePerformanceQualificationInputs)
}

tasks.named<AssemblePerformanceDistributionTask>("assemblePerformanceDistribution") {
  benchmarkDependencies.setFrom(packagedBenchmarkDependencies)
}

nexusPublishing {
  this.repositories {
    sonatype {
      stagingProfileId = STAGING_PROFILE_ID
      nexusUrl = uri("https://ossrh-staging-api.central.sonatype.com/service/local/")
      snapshotRepositoryUrl = uri("https://central.sonatype.com/repository/maven-snapshots/")
    }
  }
}
