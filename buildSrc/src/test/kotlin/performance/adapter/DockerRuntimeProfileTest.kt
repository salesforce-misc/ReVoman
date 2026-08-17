/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.adapter

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.Comparator
import performance.support.FakeHost

private const val RUNTIME_REFERENCE =
  "docker.io/library/eclipse-temurin@sha256:6c7425db05efdcf0ba40d989898857b093f14ceaf9684c9c31a072c159f4590e"
private const val OCI_CONFIG =
  "sha256:ad6963934ee96838c09d99f3c4df6f991cd00ed70fa8a48f7045517d7ae8991c"
private const val JAVA_SHA256 =
  "1cedc51a4102638f1f06077acb3611b88f3061f9c7d76bd0a0df7f8607a9367b"

class DockerRuntimeProfileTest :
  FunSpec(
    {
      test("all checked-in runtime profiles satisfy the strict Draft 2020-12 schema") {
        val profiles = runtimeProfiles()

        profiles.schemaValidator().use { validator ->
          listOf(profiles.runtime, profiles.mac, profiles.github).forEach { profile ->
            validator.validate(profile).shouldBeEmpty()
          }
        }
      }

      test("the canonical runtime records the exact ARM child and complete Temurin identity") {
        val runtime = runtimeProfiles().runtime

        runtime shouldContain RUNTIME_REFERENCE
        runtime shouldContain "\"digestKind\": \"platform-manifest\""
        runtime shouldContain "\"os\": \"linux\""
        runtime shouldContain "\"architecture\": \"arm64\""
        runtime shouldContain "\"variant\": \"v8\""
        runtime shouldContain OCI_CONFIG
        runtime shouldContain "openjdk version \\\"21.0.11\\\" 2026-04-21 LTS"
        runtime shouldContain "OpenJDK Runtime Environment Temurin-21.0.11+10 (build 21.0.11+10-LTS)"
        runtime shouldContain
          "OpenJDK 64-Bit Server VM Temurin-21.0.11+10 (build 21.0.11+10-LTS, mixed mode, sharing)"
        runtime shouldContain "\"executable\": \"/opt/java/openjdk/bin/java\""
        runtime shouldContain JAVA_SHA256
        runtime shouldContain "\"path\": \"/usr/bin/sh\""
        runtime shouldContain "\"path\": \"/usr/bin/tar\""
        runtime shouldContain "tar (GNU tar) 1.35"
        runtime shouldContain "\"path\": \"/usr/bin/sha256sum\""
        runtime shouldContain "sha256sum (GNU coreutils) 9.4"
      }

      test("the Mac and GitHub substrate variants freeze identity security and evidence strength") {
        val profiles = runtimeProfiles()

        profiles.mac shouldContain "\"context\": \"desktop-linux\""
        profiles.mac shouldContain "\"claimEligibility\": \"canonical\""
        profiles.mac shouldContain "\"uid\": 10001"
        profiles.mac shouldContain "\"gid\": 10001"
        profiles.github shouldContain "\"context\": null"
        profiles.github shouldContain "\"claimEligibility\": \"diagnostic-only\""
        listOf(profiles.mac, profiles.github).forEach { profile ->
          profile shouldContain RUNTIME_REFERENCE
          profile shouldContain "\"cpusetCpus\": \"0-3\""
          profile shouldContain "\"memoryBytes\": 6442450944"
          profile shouldContain "\"memorySwapBytes\": 6442450944"
          profile shouldContain "\"pidsLimit\": 512"
          profile shouldContain "\"readOnlyRoot\": true"
          profile shouldContain "\"privileged\": false"
          profile shouldContain "\"capDrop\": [\n      \"ALL\"\n    ]"
          profile shouldContain "\"volumeInitializerCapAdd\": [\n      \"CHOWN\"\n    ]"
          profile shouldContain "\"no-new-privileges\""
          profile shouldContain "\"docker-socket\""
          profile shouldContain "\"writableMounts\""
          profile shouldContain "\"volumeInitializer\": ["
          profile shouldContain "\"preparation\": ["
          profile shouldContain "\"timed\": ["
          profile shouldContain "\"scrubber\": ["
          profile shouldContain "\"finalizer\": ["
        }
      }

      mapOf<String, (String) -> String>(
          "mutable tag" to { runtime ->
            runtime.replace(
              RUNTIME_REFERENCE,
              "docker.io/library/eclipse-temurin:21-jdk-noble",
            )
          },
          "index digest declaration" to { runtime ->
            runtime.replace("\"digestKind\": \"platform-manifest\"", "\"digestKind\": \"index\"")
          },
          "unresolved Java hash" to { runtime -> runtime.replace(JAVA_SHA256, "UNRESOLVED") },
          "amd64 platform" to { runtime ->
            runtime.replace("\"architecture\": \"arm64\"", "\"architecture\": \"amd64\"")
          },
          "missing config hash" to { runtime ->
            runtime.replace("    \"ociConfigDigest\": \"$OCI_CONFIG\",\n", "")
          },
          "unknown property" to { runtime ->
            runtime.replace("  \"profileKind\": \"runtime\",", "  \"profileKind\": \"runtime\",\n  \"unknown\": true,")
          },
        )
        .forEach { (condition, mutate) ->
          test("runtime schema rejects $condition") {
            val profiles = runtimeProfiles()
            val invalid = mutate(profiles.runtime)
            check(invalid != profiles.runtime) { "mutation did not change the runtime profile" }

            profiles.schemaValidator().use { validator ->
              validator.validate(invalid).shouldNotBeEmpty()
            }
          }
        }

      test("the live fixture delegates Docker context and identity selection to the host profile") {
        val sourceRoot = FakeHost().use { host -> host.sourceRoot }
        val artifactParent = Files.createTempDirectory("revoman-live-selection.")
        try {
          val process =
            ProcessBuilder(
                "/bin/bash",
                "-c",
                "source \"\$1\"; " +
                  "adapter_select_substrate() { return 73; }; " +
                  "adapter_docker() { return 0; }; " +
                  "adapter_live_volume_identity_fixture \"\$2\"",
                "runtime-profile-test",
                sourceRoot.resolve("scripts/performance/run").toString(),
                artifactParent.toString(),
              )
              .directory(sourceRoot.toFile())
              .redirectErrorStream(true)
              .start()

          process.inputStream.readAllBytes()
          process.waitFor() shouldBe 73
        } finally {
          Files.deleteIfExists(artifactParent)
        }
      }

      test("fresh named volume and host bind are writable only through declared non-root phases") {
        val sourceRoot = FakeHost().use { host -> host.sourceRoot }
        val artifactParent = Files.createTempDirectory("revoman-live-finalizer.")
        val ownerBefore = Files.getOwner(artifactParent)
        Files.setPosixFilePermissions(
          artifactParent,
          setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
          ),
        )
        try {
          val process =
            ProcessBuilder(
                "/bin/bash",
                "-c",
                "source \"\$1\"; adapter_live_volume_identity_fixture \"\$2\"",
                "runtime-profile-test",
                sourceRoot.resolve("scripts/performance/run").toString(),
                artifactParent.toString(),
              )
              .directory(sourceRoot.toFile())
              .redirectErrorStream(true)
              .start()
          val output = process.inputStream.readAllBytes().decodeToString()

          process.waitFor() shouldBe 0
          output shouldContain "LIVE_VOLUME_IDENTITY_OK"
          val expectedIdentity =
            if (System.getProperty("os.name") == "Mac OS X") "10001:10001" else "1001:1001"
          Files.readString(artifactParent.resolve("finalizer-identity.txt")).trim() shouldBe
            expectedIdentity
          Files.getOwner(artifactParent) shouldBe ownerBefore
          Files.getPosixFilePermissions(artifactParent) shouldBe
            setOf(
              PosixFilePermission.OWNER_READ,
              PosixFilePermission.OWNER_WRITE,
              PosixFilePermission.OWNER_EXECUTE,
            )
        } finally {
          Files.walk(artifactParent).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
          }
        }
      }
    },
  )

private data class RuntimeProfiles(
  val schema: Path,
  val runtime: String,
  val mac: String,
  val github: String,
) {
  fun schemaValidator(): ReflectiveSchemaValidator = ReflectiveSchemaValidator(schema)
}

private fun runtimeProfiles(): RuntimeProfiles {
  val sourceRoot = FakeHost().use { host -> host.sourceRoot }
  val runtimeRoot = sourceRoot.resolve("config/performance/runtime")
  return RuntimeProfiles(
    schema = runtimeRoot.resolve("runtime-profile-v1.schema.json"),
    runtime = Files.readString(runtimeRoot.resolve("temurin-21-linux-arm64-v1.json")),
    mac = Files.readString(runtimeRoot.resolve("m4max-docker-linux-arm64-v1.json")),
    github = Files.readString(runtimeRoot.resolve("github-hosted-arm64-v1.json")),
  )
}

private class ReflectiveSchemaValidator(schemaPath: Path) : AutoCloseable {
  private val schemaInput: InputStream = Files.newInputStream(schemaPath)
  private val inputFormatClass = Class.forName("com.networknt.schema.InputFormat")
  private val schemaClass = Class.forName("com.networknt.schema.Schema")
  private val schema: Any
  private val jsonInput: Any

  init {
    val specificationClass = Class.forName("com.networknt.schema.SpecificationVersion")
    val draft = specificationClass.getField("DRAFT_2020_12").get(null)
    val registryClass = Class.forName("com.networknt.schema.SchemaRegistry")
    val registry =
      registryClass.getMethod("withDefaultDialect", specificationClass).invoke(null, draft)
    schema = registryClass.getMethod("getSchema", InputStream::class.java).invoke(registry, schemaInput)
    jsonInput = inputFormatClass.getField("JSON").get(null)
  }

  fun validate(document: String): List<*> =
    schemaClass
      .getMethod("validate", String::class.java, inputFormatClass)
      .invoke(schema, document, jsonInput) as List<*>

  override fun close() = schemaInput.close()
}
