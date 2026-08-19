/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.schema

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain as shouldNotContainText
import performance.hash.Sha256
import performance.json.CanonicalJson
import performance.model.HostSnapshot
import performance.model.MemoryPressureState
import performance.model.PowerState
import performance.model.ProvisionalCaptureDocument
import performance.model.ThermalState
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode

class CaptureSchemaContractTest :
  FunSpec(
    {
      val validator = EvidenceSchemaValidator()

      test("golden capture is canonical and satisfies the final capture schema") {
        val goldenBytes = goldenCaptureBytes()

        CanonicalJson.encode(CanonicalJson.parseStrict(goldenBytes)) shouldBe goldenBytes
        Sha256.digest(goldenBytes).hex shouldBe GOLDEN_CAPTURE_SHA256
        validator.validate(SchemaKind.CAPTURE, goldenBytes).shouldBeEmpty()
      }

      test("canonical cold capture records the frozen SingleShotTime sample geometry") {
        val capture = goldenCapture()
        val profile = capture.objectNode("profile")
        val cell = capture.firstCell()

        profile.get("forks").asInt() shouldBe 10
        profile.get("warmupIterations").asInt() shouldBe 0
        profile.get("measurementIterations").asInt() shouldBe 1
        cell.get("mode").asString() shouldBe "ss"
        cell.get("unit").asString() shouldBe "ms/op"
        cell.get("threads").asInt() shouldBe 1
        cell.get("batchSize").asInt() shouldBe 1
        cell.objectNode("primaryMetric").get("name").asString() shouldBe "score"
        cell.objectNode("primaryMetric").get("direction").asString() shouldBe "lowerIsBetter"
        cell.objectNode("sampleDimensions").get("forks").asInt() shouldBe 10
        cell.objectNode("sampleDimensions").get("measurementIterations").asInt() shouldBe 1
        cell.objectNode("sampleDimensions").get("samplesPerFork").asInt() shouldBe 1
        cell.arrayNode("derivedForkSummaries").size() shouldBe 10
        cell
          .arrayNode("derivedForkSummaries")
          .values()
          .map { it.get("sampleCount").asInt() } shouldBe List(10) { 1 }
      }

      mapOf<String, (ObjectNode) -> Unit>(
          "AverageTime mode" to { cell -> cell.put("mode", "avgt") },
          "non-millisecond unit" to { cell -> cell.put("unit", "ns/op") },
          "multiple threads" to { cell -> cell.put("threads", 2) },
          "multi-operation batch" to { cell -> cell.put("batchSize", 2) },
          "non-score primary metric" to { cell ->
            cell.objectNode("primaryMetric").put("name", "secondary")
          },
          "higher-is-better direction" to { cell ->
            cell.objectNode("primaryMetric").put("direction", "higherIsBetter")
          },
        )
        .forEach { (condition, mutate) ->
          test("V1 capture cells reject $condition") {
            val capture = goldenCapture()
            mutate(capture.firstCell())

            validateCapture(capture).shouldNotBeEmpty()
          }
        }

      test("golden profiler summary is canonical and satisfies its strict schema") {
        val goldenBytes = goldenResourceBytes(GOLDEN_PROFILER_SUMMARY)

        CanonicalJson.encode(CanonicalJson.parseStrict(goldenBytes)) shouldBe goldenBytes
        Sha256.digest(goldenBytes).hex shouldBe GOLDEN_PROFILER_SUMMARY_SHA256
        validator.validate(SchemaKind.PROFILER_SUMMARY, goldenBytes).shouldBeEmpty()
      }

      test("all protocol document schemas accept their strict golden shape") {
        validProtocolDocuments().forEach { (schema, document) ->
          validator.validate(schema, canonical(document)).shouldBeEmpty()
        }
      }

      mapOf(
          SchemaKind.PREFLIGHT to SNAPSHOT_FIELDS,
          SchemaKind.POSTFLIGHT to SNAPSHOT_FIELDS,
        )
        .forEach { (schema, fields) ->
          fields.forEach { field ->
            test("$schema requires host snapshot observation $field") {
              val document = validProtocolDocument(schema)
              document.objectNode("snapshot").remove(field)

              validator.validate(schema, CanonicalJson.encode(document)).shouldNotBeEmpty()
            }
          }
        }

      test("preflight requires the host snapshot") {
        val document = validProtocolDocument(SchemaKind.PREFLIGHT).apply { remove("snapshot") }

        validator
          .validate(SchemaKind.PREFLIGHT, CanonicalJson.encode(document))
          .shouldNotBeEmpty()
      }

      test("postflight requires the host snapshot") {
        val document = validProtocolDocument(SchemaKind.POSTFLIGHT).apply { remove("snapshot") }

        validator
          .validate(SchemaKind.POSTFLIGHT, CanonicalJson.encode(document))
          .shouldNotBeEmpty()
      }

      test("preflight requires the measured user-idle duration") {
        val document =
          validProtocolDocument(SchemaKind.PREFLIGHT).apply { remove("userIdleMillis") }

        validator
          .validate(SchemaKind.PREFLIGHT, CanonicalJson.encode(document))
          .shouldNotBeEmpty()
      }

      WATCHER_ADDED_FIELDS.forEach { field ->
        test("watcher observations require $field") {
          val document = validProtocolDocument(SchemaKind.WATCHER)
          document.arrayNode("observations").first().asObject().remove(field)

          validator.validate(SchemaKind.WATCHER, CanonicalJson.encode(document)).shouldNotBeEmpty()
        }
      }

      mapOf(
          SchemaKind.PREFLIGHT to listOf("containers", "runtime", "swapPage"),
          SchemaKind.POSTFLIGHT to listOf("containers", "cpuIdle", "swapPage"),
        )
        .forEach { (schema, checks) ->
          checks.forEach { check ->
            test("$schema requires qualification check $check") {
              val document = validProtocolDocument(schema)
              document.objectNode("checks").remove(check)

              validator.validate(schema, CanonicalJson.encode(document)).shouldNotBeEmpty()
            }
          }
        }

      test("host observation models carry typed normative snapshots") {
        val fingerprint = Sha256.parse(SHA)
        val snapshot =
          HostSnapshot(
            cpuLoadPercent = 12.5,
            cpuIdlePercent = 87.5,
            memoryPressure = MemoryPressureState.NORMAL,
            swapBytes = 0,
            pageOuts = 0,
            thermalState = ThermalState.NOMINAL,
            powerState = PowerState.AC,
            containerFingerprintSha256 = fingerprint,
            runtimeFingerprintSha256 = fingerprint,
          )

        snapshot.containerFingerprintSha256 shouldBe fingerprint
        snapshot.runtimeFingerprintSha256 shouldBe fingerprint
        fieldTypes("performance.model.HostSnapshot") shouldBe
          mapOf(
            // Kotlin value classes intentionally erase to their underlying JVM representation.
            "containerFingerprintSha256" to "java.lang.String",
            "cpuIdlePercent" to "double",
            "cpuLoadPercent" to "double",
            "memoryPressure" to "performance.model.MemoryPressureState",
            "pageOuts" to "long",
            "powerState" to "performance.model.PowerState",
            "runtimeFingerprintSha256" to "java.lang.String",
            "swapBytes" to "long",
            "thermalState" to "performance.model.ThermalState",
          )
        fieldTypes("performance.model.PreflightDocument") shouldBe
          mapOf(
            "adapterSha256" to "java.lang.String",
            "architecture" to "java.lang.String",
            "checks" to "java.util.Map",
            "lockAcquired" to "boolean",
            "observedAtUtc" to "java.lang.String",
            "operationId" to "java.lang.String",
            "policySha256" to "java.lang.String",
            "snapshot" to "performance.model.HostSnapshot",
            "userIdleMillis" to "long",
          )
        fieldTypes("performance.model.PostflightDocument") shouldBe
          mapOf(
            "checks" to "java.util.Map",
            "observedAtUtc" to "java.lang.String",
            "policySha256" to "java.lang.String",
            "processExit" to "int",
            "snapshot" to "performance.model.HostSnapshot",
          )
        fieldTypes("performance.model.WatcherObservation") shouldBe
          mapOf(
            "containerFingerprintSha256" to "java.lang.String",
            "cpuLoadPercent" to "double",
            "event" to "java.lang.String",
            "memoryPressure" to "performance.model.MemoryPressureState",
            "observedAtUtc" to "java.lang.String",
            "pageOuts" to "long",
            "powerState" to "performance.model.PowerState",
            "runtimeFingerprintSha256" to "java.lang.String",
            "swapBytes" to "long",
            "thermalState" to "performance.model.ThermalState",
          )
      }

      test("unknown properties are rejected") {
        val capture = goldenCapture().apply { put("unexpected", true) }

        validateCapture(capture).shouldNotBeEmpty()
      }

      test("every required capture section is enforced") {
        val capture = goldenCapture().apply { remove("protocol") }

        validateCapture(capture).shouldNotBeEmpty()
      }

      test("SHA values must be lowercase 64-hex") {
        val capture = goldenCapture()
        capture.objectNode("protocol").put("schemaSha256", "A".repeat(64))

        validateCapture(capture).shouldNotBeEmpty()
      }

      test("artifact references must be normalized relative paths") {
        val capture = goldenCapture()
        capture.objectNode("artifacts").objectNode("production").put("path", "/Users/alice/revoman.jar")

        validateCapture(capture).shouldNotBeEmpty()
      }

      test("frozen two-part dependency coordinates remain valid capture evidence") {
        mapOf(
            SchemaKind.CAPTURE to goldenCapture(),
            SchemaKind.CAPTURE_PROVISIONAL to validProvisionalCapture(),
          )
          .forEach { (schema, capture) ->
            capture
              .objectNode("artifacts")
              .arrayNode("dependencies")
              .first()
              .asObject()
              .put("coordinate", "resolved:http4k-core-6.57.2.0")

            validator.validate(schema, CanonicalJson.encode(capture)).shouldBeEmpty()
          }
      }

      listOf(
          "",
          "resolved",
          "resolved:",
          "resolved:http4k-core:6+57",
          "resolved:http4k-core:6.57.2.0:unexpected",
        )
        .forEach { coordinate ->
          test("malformed dependency coordinate $coordinate remains rejected") {
            mapOf(
                SchemaKind.CAPTURE to goldenCapture(),
                SchemaKind.CAPTURE_PROVISIONAL to validProvisionalCapture(),
              )
              .forEach { (schema, capture) ->
                capture
                  .objectNode("artifacts")
                  .arrayNode("dependencies")
                  .first()
                  .asObject()
                  .put("coordinate", coordinate)

                validator.validate(schema, CanonicalJson.encode(capture)).shouldNotBeEmpty()
              }
          }
        }

      listOf(
          "-DapiKey=redacted-value",
          "-Dauth.token=redacted-value",
          "-Dpassword=redacted-value",
          "-DclientSecret=redacted-value",
        )
        .forEach { argument ->
          test("secret-shaped JVM argument ${argument.substringBefore('=')} is rejected") {
            val capture = goldenCapture()
            capture
              .objectNode("runtime")
              .objectNode("jdk")
              .set("jvmArguments", stringArray(argument))

            validateCapture(capture).shouldNotBeEmpty()
          }
        }

      mapOf(
          "GitHub token signature behind a generic key" to
            "-Dfoo=ghp_0123456789abcdefghijklmnopqrstuvwxyz",
          "AWS access-key signature behind a generic key" to "-Dbuild.id=AKIA0123456789ABCDEF",
        )
        .forEach { (condition, argument) ->
          test("$condition is rejected from JVM arguments") {
            val capture = goldenCapture()
            capture
              .objectNode("runtime")
              .objectNode("jdk")
              .set("jvmArguments", stringArray(argument))

            validateCapture(capture).shouldNotBeEmpty()
          }
        }

      SECRET_TOKEN_SAMPLES.forEach { (tokenKind, token) ->
        test("capture safeId rejects $tokenKind without echo") {
          val capture = goldenCapture()
          capture.objectNode("identity").put("captureId", token)

          assertRejectedWithoutEcho(validateCapture(capture), token)
        }

        test("capture safeText rejects $tokenKind without echo") {
          val capture = goldenCapture()
          capture.firstCell().objectNode("parameters").put("scenario", token)

          assertRejectedWithoutEcho(validateCapture(capture), token)
        }

        test("capture JVM arguments reject $tokenKind without echo") {
          val capture = goldenCapture()
          capture
            .objectNode("runtime")
            .objectNode("jdk")
            .set("jvmArguments", stringArray("-Dbuild.id=$token"))

          assertRejectedWithoutEcho(validateCapture(capture), token)
        }

        test("preflight operationId rejects $tokenKind without echo") {
          val document = validProtocolDocument(SchemaKind.PREFLIGHT)
          document.put("operationId", token)

          assertRejectedWithoutEcho(
            validator.validate(SchemaKind.PREFLIGHT, CanonicalJson.encode(document)),
            token,
          )
        }

        test("profiler summary captureId rejects $tokenKind without echo") {
          val document = validProtocolDocument(SchemaKind.PROFILER_SUMMARY)
          document.put("captureId", token)

          assertRejectedWithoutEcho(
            validator.validate(SchemaKind.PROFILER_SUMMARY, CanonicalJson.encode(document)),
            token,
          )
        }
      }

      mapOf(
          "IPv4 address" to "-Dendpoint=192.0.2.10",
          "IPv6 address" to "-Dendpoint=2001:db8::1",
        )
        .forEach { (condition, argument) ->
          test("$condition is rejected from JVM arguments") {
            val capture = goldenCapture()
            capture
              .objectNode("runtime")
              .objectNode("jdk")
              .set("jvmArguments", stringArray(argument))

            validateCapture(capture).shouldNotBeEmpty()
          }
        }

      mapOf(
          "user name property" to "-Duser.name=alice",
          "user home property" to "-Duser.home=alice",
          "user directory property" to "-Duser.dir=private.tmp",
          "host name property" to "-Dhost.name=build-host.internal",
          "hostname property" to "-Dhostname=build-host.internal",
          "username embedded in property" to "-Dusername=alice",
          "user embedded in nested property" to "-Dbuild.currentuser.id=alice",
          "host embedded in nested property" to "-Dbuild.targethost.id=controlled",
          "hostname embedded in JDK property" to "-Djava.rmi.server.hostname=loopback",
        )
        .forEach { (condition, argument) ->
          test("identifying $condition is rejected from JVM arguments") {
            val capture = goldenCapture()
            capture
              .objectNode("runtime")
              .objectNode("jdk")
              .set("jvmArguments", stringArray(argument))

            validateCapture(capture).shouldNotBeEmpty()
          }
        }

      mapOf(
          "absolute path" to "-Doutput=/private/tmp/value",
          "home-relative path" to "-Doutput=~/private/value",
          "workspace path" to "-Doutput=workspace/value",
          "parent-traversal path" to "-Doutput=../private/value",
          "path-identifying property" to "-Doutput.path=private.tmp",
          "home-identifying property" to "-Dhome=alice",
          "workspace-identifying property" to "-Dworkspace=revoman",
          "path embedded in property" to "-Dbuild.classpathmode=stable",
          "home embedded in property" to "-Dbuild.homepage=stable",
          "workspace embedded in property" to "-Dbuild.projectworkspaceid=stable",
          "parent-traversal value" to "-Doutput=..",
          "non-approved user home path" to "-Duser.home=/operation/state",
          "non-approved classpath resource" to
            "-Dlog4j.configurationFile=classpath:other/log4j2-performance.xml",
        )
        .forEach { (condition, argument) ->
          test("$condition is rejected from JVM arguments") {
            val capture = goldenCapture()
            capture
              .objectNode("runtime")
              .objectNode("jdk")
              .set("jvmArguments", stringArray(argument))

            validateCapture(capture).shouldNotBeEmpty()
          }
        }

      test("approved JVM argument forms remain valid") {
        val capture = goldenCapture()
        capture
          .objectNode("runtime")
          .objectNode("jdk")
          .set(
            "jvmArguments",
            stringArray(
              "-Xms2g",
              "-Xmx2g",
              "-XX:+UseG1GC",
              "-Dfile.encoding=UTF-8",
              "-Duser.timezone=UTC",
              "-Duser.home=/operation/tmp",
              "-Dlog4j.configurationFile=classpath:performance/log4j2-performance.xml",
              "-Djava.io.tmpdir=tmp",
              "-Drevoman.banner=off",
            ),
          )

        validateCapture(capture).shouldBeEmpty()
      }

      test("JVM argument violations never echo rejected content") {
        val rejectedArgument = "-Dfoo=ghp_0123456789abcdefghijklmnopqrstuvwxyz"
        val rejectedValue = rejectedArgument.substringAfter('=')
        val capture = goldenCapture()
        capture
          .objectNode("runtime")
          .objectNode("jdk")
          .set("jvmArguments", stringArray(rejectedArgument))

        val violations = validateCapture(capture)
        val rendered =
          violations.joinToString(separator = "|") { violation ->
            "${violation.path}|${violation.keyword}|${violation.message}"
          }

        violations.shouldNotBeEmpty()
        rendered shouldNotContainText rejectedArgument
        rendered shouldNotContainText rejectedValue
      }

      listOf("apiKey", "access_token", "authorization", "client-secret", "credential").forEach {
        parameterName ->
        test("secret-shaped parameter key $parameterName is rejected") {
          val capture = goldenCapture()
          capture.firstCell().objectNode("parameters").put(parameterName, "redacted-value")

          validateCapture(capture).shouldNotBeEmpty()
        }
      }

      listOf(
          "token-redacted-value",
          "credential-redacted-value",
          "privateKey-redacted-value",
          "ghp_0123456789abcdefghijklmnopqrstuvwxyz",
        )
        .forEach { parameterValue ->
          test("secret-shaped parameter value is rejected") {
            val capture = goldenCapture()
            capture.firstCell().objectNode("parameters").put("scenario", parameterValue)

            validateCapture(capture).shouldNotBeEmpty()
          }
        }

      listOf(
          "/private/tmp/value",
          "~/private/value",
          "workspace/value",
          "../private/value",
          "C:/private/value",
          "\\\\server\\share",
        )
        .forEach { path ->
          test("path-shaped parameter value is rejected") {
            val capture = goldenCapture()
            capture.firstCell().objectNode("parameters").put("scenario", path)

            validateCapture(capture).shouldNotBeEmpty()
          }
        }

      listOf("192.0.2.10", "2001:db8::1", "[2001:db8::1]").forEach { address ->
        test("IP-shaped parameter value is rejected") {
          val capture = goldenCapture()
          capture.firstCell().objectNode("parameters").put("scenario", address)

          validateCapture(capture).shouldNotBeEmpty()
        }
      }

      listOf("build-host.internal", "alice", "controlled-mac-01").forEach { hostId ->
        test("unapproved or identifying host ID $hostId is rejected") {
          val capture = goldenCapture()
          capture.objectNode("runtime").put("hostId", hostId)

          validateCapture(capture).shouldNotBeEmpty()
        }
      }

      test("controlled Mac campaign accepts only its canonical host ID") {
        val capture = goldenCapture()
        capture.objectNode("runtime").put("hostId", CONTROLLED_MAC_CAMPAIGN_HOST_ID)

        validateCapture(capture).shouldBeEmpty()
      }

      listOf(CONTROLLED_MAC_CANARY_HOST_ID, GITHUB_HOST_ID).forEach { hostId ->
        test("controlled Mac campaign rejects host ID $hostId") {
          val capture = goldenCapture()
          capture.objectNode("runtime").put("hostId", hostId)

          validateCapture(capture).shouldNotBeEmpty()
        }
      }

      test("GitHub qualification accepts only its hosted host ID") {
        val capture = githubHostedCapture(GITHUB_HOST_ID)

        validateCapture(capture).shouldBeEmpty()
      }

      listOf(CONTROLLED_MAC_CANARY_HOST_ID, CONTROLLED_MAC_CAMPAIGN_HOST_ID).forEach { hostId ->
        test("GitHub qualification rejects controlled Mac host ID $hostId") {
          val capture = githubHostedCapture(hostId)

          validateCapture(capture).shouldNotBeEmpty()
        }
      }

      listOf(CONTROLLED_MAC_CANARY_HOST_ID, CONTROLLED_MAC_CAMPAIGN_HOST_ID).forEach { hostId ->
        test("bounded controlled Mac qualification accepts host ID $hostId") {
          val capture = boundedDiagnosticCapture(hostId)

          validateCapture(capture).shouldBeEmpty()
        }
      }

      test("bounded controlled Mac qualification rejects the GitHub host ID") {
        val capture = boundedDiagnosticCapture(GITHUB_HOST_ID)

        validateCapture(capture).shouldNotBeEmpty()
      }

      test("identity tokens retain their strict non-whitespace format") {
        val capture = goldenCapture()
        capture.objectNode("identity").put("captureId", "capture id")

        validateCapture(capture).shouldNotBeEmpty()
      }

      test("schema violations never echo unsafe property names or values") {
        val unsafeProperty = "clientSecret"
        val unsafeValue = "Bearer-redacted-private-value"
        val capture = goldenCapture()
        capture.firstCell().objectNode("parameters").put(unsafeProperty, unsafeValue)

        val violations = validateCapture(capture)
        val rendered =
          violations.joinToString(separator = "|") { violation ->
            "${violation.path}|${violation.keyword}|${violation.message}"
          }

        violations.shouldNotBeEmpty()
        rendered shouldNotContainText unsafeProperty
        rendered shouldNotContainText unsafeValue
        rendered shouldNotContainText "Bearer"
      }

      mapOf(
          "hostname" to "build-host.internal",
          "username" to "alice",
          "ipAddress" to "192.0.2.10",
        )
        .forEach { (field, value) ->
          test("privacy field $field is rejected") {
            val capture = goldenCapture()
            capture.objectNode("runtime").objectNode("substrate").put(field, value)

            validateCapture(capture).shouldNotBeEmpty()
          }
        }

      test("qualification requires its exact discriminator") {
        val capture = goldenCapture()
        capture.objectNode("qualification").remove("kind")

        validateCapture(capture).shouldNotBeEmpty()
      }

      test("GitHub-hosted captures can never be canonical") {
        val capture = goldenCapture()
        capture.set("qualification", parseObject(GITHUB_QUALIFICATION))
        capture.objectNode("runtime").set("substrate", parseObject(GITHUB_SUBSTRATE))

        validateCapture(capture).shouldNotBeEmpty()
      }

      mapOf<String, (ObjectNode) -> Unit>(
          "invalid status" to { capture ->
            capture.objectNode("outcome").put("status", "invalid")
          },
          "nonzero process exit" to { capture ->
            capture.objectNode("outcome").put("processExit", 1)
          },
          "bounded qualification" to { capture ->
            capture.set("qualification", parseObject(BOUNDED_QUALIFICATION))
          },
          "hosted substrate" to { capture ->
            capture.objectNode("runtime").set("substrate", parseObject(GITHUB_SUBSTRATE))
          },
          "diagnostic profiler" to { capture ->
            capture.objectNode("profile").put("profiler", "jfr")
            capture.set("profilerSummary", parseObject(PROFILER_SUMMARY_REF))
          },
          "missing campaign-qualified reason" to { capture ->
            capture.objectNode("outcome").set("claimEligibilityReasons", stringArray("boundedDiagnostic"))
          },
        )
        .forEach { (condition, mutate) ->
          test("canonical captures reject $condition") {
            val capture = goldenCapture().also(mutate)

            validateCapture(capture).shouldNotBeEmpty()
          }
        }

      listOf(
          "boundedDiagnostic",
          "githubHosted",
          "invalidMeasurement",
          "profilerDiagnostic",
          "qualificationFailed",
          "structuralCanary",
        )
        .forEach { otherReason ->
          test("canonical campaign eligibility is exclusive of $otherReason") {
            val capture = goldenCapture()
            capture
              .objectNode("outcome")
              .set(
                "claimEligibilityReasons",
                stringArray("controlledMacCampaignQualified", otherReason),
              )

            validateCapture(capture).shouldNotBeEmpty()
          }
        }

      test("final campaign qualification rejects failed cleanup") {
        val capture = goldenCapture()
        capture.objectNode("qualification").put("cleanupPassed", false)

        validateCapture(capture).shouldNotBeEmpty()
      }

      test("inapplicability reasons are enumerated") {
        val capture = goldenCapture()
        capture.objectNode("outcome").put("strength", "diagnostic")
        capture.set("qualification", parseObject(GITHUB_QUALIFICATION))
        capture.objectNode("runtime").set("substrate", parseObject(GITHUB_SUBSTRATE))
        capture
          .objectNode("qualification")
          .put("macFieldsInapplicableReason", "operator supplied prose")

        validateCapture(capture).shouldNotBeEmpty()
      }

      listOf("sourcePath", "threadName").forEach { forbiddenField ->
        test("profiler summaries reject $forbiddenField") {
          val summary = parseObject(PROFILER_SUMMARY)
          summary.arrayNode("aggregates").first().asObject().put(forbiddenField, "private-value")

          validator
            .validate(SchemaKind.PROFILER_SUMMARY, CanonicalJson.encode(summary))
            .shouldNotBeEmpty()
        }
      }

      PROFILER_CLASSES.forEach { (category, className) ->
        test("profiler category $category accepts only its allowlisted namespace") {
          val summary = profilerSummary(category, className)

          validator
            .validate(SchemaKind.PROFILER_SUMMARY, CanonicalJson.encode(summary))
            .shouldBeEmpty()
        }

        test("profiler category $category rejects a class from another allowed category") {
          val wrongClass =
            when (category) {
              "application" -> PROFILER_CLASSES.getValue("graal")
              else -> PROFILER_CLASSES.getValue("application")
            }
          val summary = profilerSummary(category, wrongClass)

          validator
            .validate(SchemaKind.PROFILER_SUMMARY, CanonicalJson.encode(summary))
            .shouldNotBeEmpty()
        }

        listOf("com.example.Worker", "java.lang.String", "kotlin.String").forEach {
          unallowlistedClass ->
          test("profiler category $category rejects unallowlisted class $unallowlistedClass") {
            val summary = profilerSummary(category, unallowlistedClass)

            validator
              .validate(SchemaKind.PROFILER_SUMMARY, CanonicalJson.encode(summary))
              .shouldNotBeEmpty()
          }
        }
      }

      test("profiler graal category accepts the JDK Graal compiler namespace") {
        val summary = profilerSummary("graal", "jdk.graal.compiler.nodes.StructuredGraph")

        validator
          .validate(SchemaKind.PROFILER_SUMMARY, CanonicalJson.encode(summary))
          .shouldBeEmpty()
      }

      listOf("", "run()", "run/path", "service.run").forEach { methodName ->
        test("profiler method name remains a strict JVM identifier") {
          val summary =
            profilerSummary(
              category = "application",
              className = PROFILER_CLASSES.getValue("application"),
              methodName = methodName,
            )

          validator
            .validate(SchemaKind.PROFILER_SUMMARY, CanonicalJson.encode(summary))
            .shouldNotBeEmpty()
        }
      }

      listOf("gc", "jfr").forEach { profiler ->
        test("final $profiler diagnostic requires and accepts a profiler summary") {
          val capture = finalProfilerCapture(profiler)

          validateCapture(capture).shouldBeEmpty()
        }

        test("final $profiler diagnostic rejects a missing profiler summary") {
          val capture = finalProfilerCapture(profiler).apply { remove("profilerSummary") }

          validateCapture(capture).shouldNotBeEmpty()
        }
      }

      test("final unprofiled capture forbids a profiler summary") {
        val capture = goldenCapture()
        capture.set("profilerSummary", parseObject(PROFILER_SUMMARY_REF))

        validateCapture(capture).shouldNotBeEmpty()
      }

      test("provisional captures cannot claim canonical strength") {
        val provisional = validProvisionalCapture()
        provisional.objectNode("outcome").put("strength", "canonical")

        validator
          .validate(SchemaKind.CAPTURE_PROVISIONAL, CanonicalJson.encode(provisional))
          .shouldNotBeEmpty()
      }

      test("provisional JFR capture records only the immutable raw profiler input hash") {
        val provisional = provisionalProfilerCapture("jfr")
        provisional.put("rawProfilerInputSha256", SHA)

        validator
          .validate(SchemaKind.CAPTURE_PROVISIONAL, CanonicalJson.encode(provisional))
          .shouldBeEmpty()
      }

      test("provisional JFR capture requires its raw profiler input hash") {
        val provisional = provisionalProfilerCapture("jfr")

        validator
          .validate(SchemaKind.CAPTURE_PROVISIONAL, CanonicalJson.encode(provisional))
          .shouldNotBeEmpty()
      }

      test("provisional GC capture is diagnostic without a raw profiler input hash") {
        val provisional = provisionalProfilerCapture("gc")

        validator
          .validate(SchemaKind.CAPTURE_PROVISIONAL, CanonicalJson.encode(provisional))
          .shouldBeEmpty()
      }

      listOf("none", "gc").forEach { profiler ->
        test("provisional $profiler capture forbids a raw profiler input hash") {
          val provisional = provisionalProfilerCapture(profiler)
          provisional.put("rawProfilerInputSha256", SHA)

          validator
            .validate(SchemaKind.CAPTURE_PROVISIONAL, CanonicalJson.encode(provisional))
            .shouldNotBeEmpty()
        }
      }

      test("provisional captures never contain the later profiler summary") {
        val provisional = provisionalProfilerCapture("gc")
        provisional.set("profilerSummary", parseObject(PROFILER_SUMMARY_REF))

        validator
          .validate(SchemaKind.CAPTURE_PROVISIONAL, CanonicalJson.encode(provisional))
          .shouldNotBeEmpty()
      }

      test("provisional captures cannot represent campaign-qualified evidence") {
        val provisional = validProvisionalCapture()
        provisional.set("qualification", goldenCapture().objectNode("qualification"))

        validator
          .validate(SchemaKind.CAPTURE_PROVISIONAL, CanonicalJson.encode(provisional))
          .shouldNotBeEmpty()
      }

      test("provisional captures accept only nonclaim canary and diagnostic reasons") {
        val provisional = validProvisionalCapture()
        provisional
          .objectNode("outcome")
          .set("reasons", stringArray("controlledMacCampaignQualified"))

        validator
          .validate(SchemaKind.CAPTURE_PROVISIONAL, CanonicalJson.encode(provisional))
          .shouldNotBeEmpty()
      }

      test("provisional captures cannot name a public bundle checksum") {
        val provisional = validProvisionalCapture()
        provisional.put("bundleSha256", "f".repeat(64))

        validator
          .validate(SchemaKind.CAPTURE_PROVISIONAL, CanonicalJson.encode(provisional))
          .shouldNotBeEmpty()
      }

      test("provisional Kotlin types cannot carry final qualification or claim state") {
        val fields = ProvisionalCaptureDocument::class.java.declaredFields.associateBy { it.name }
        val strengthValues = enumConstantNames("performance.model.ProvisionalEvidenceStrength")
        val reasonValues = enumConstantNames("performance.model.ProvisionalOutcomeReason")

        fields.keys.shouldNotContain("qualification")
        fields.keys.shouldNotContain("profilerSummary")
        fields.getValue("rawProfilerInputSha256").type.name shouldBe "java.lang.String"
        fields.getValue("outcome").type.name shouldBe "performance.model.ProvisionalCaptureOutcome"
        strengthValues shouldBe listOf("CANARY", "DIAGNOSTIC")
        reasonValues shouldBe
          listOf(
            "BOUNDED_DIAGNOSTIC",
            "GITHUB_HOSTED",
            "INVALID_MEASUREMENT",
            "PROFILER_DIAGNOSTIC",
            "QUALIFICATION_FAILED",
            "STRUCTURAL_CANARY",
          )
      }

      test("bootstrap adapter failures are not protocol documents") {
        SchemaKind.entries.map { it.name } shouldNotContain "ADAPTER_FAILURE"
        enumConstantNames("performance.model.AdapterFailureCode") shouldBe null
        CaptureSchemaContractTest::class.java.getResource(
          "/performance/protocol/schemas/adapter-failure-v1.schema.json"
        ) shouldBe null
      }
    },
  ) {
    companion object {
      private const val GOLDEN_CAPTURE = "/performance/golden/capture/valid-capture.json"
      private const val GOLDEN_PROFILER_SUMMARY =
        "/performance/golden/capture/valid-profiler-summary.json"
      private const val GOLDEN_CAPTURE_SHA256 =
        "5f7fc416e525fa123bb7a0551896ffc6dba3b3cdf950262bbfee9c25f168aab6"
      private const val GOLDEN_PROFILER_SUMMARY_SHA256 =
        "1c3673b106275b249b15df35db1a8f530ee4b0c3d4cac5d69f1a58fe94dbabd6"
      private const val CONTROLLED_MAC_CANARY_HOST_ID = "m4max-docker-canary-v1"
      private const val CONTROLLED_MAC_CAMPAIGN_HOST_ID = "m4max-docker-linux-arm64-v1"
      private const val GITHUB_HOST_ID = "github-hosted-arm64-canary-v1"
      private val SHA = "a".repeat(64)
      private val SECRET_TOKEN_SAMPLES =
        mapOf(
          "classic ghp token" to "ghp_0123456789abcdefghijklmnopqrstuvwxyz",
          "classic gho token" to "gho_0123456789abcdefghijklmnopqrstuvwxyz",
          "classic ghu token" to "ghu_0123456789abcdefghijklmnopqrstuvwxyz",
          "classic ghs token" to "ghs_0123456789abcdefghijklmnopqrstuvwxyz",
          "classic ghr token" to "ghr_0123456789abcdefghijklmnopqrstuvwxyz",
          "fine-grained GitHub token" to
            "github_pat_11AA0abcdefghijklmnopqrstuvwxyz0123456789",
          "AKIA AWS access-key ID" to "AKIA0123456789ABCDEF",
          "ASIA AWS access-key ID" to "ASIA0123456789ABCDEF",
        )
      private val HOST_SNAPSHOT =
        """{"containerFingerprintSha256":"$SHA","cpuIdlePercent":87.5,"cpuLoadPercent":12.5,"memoryPressure":"normal","pageOuts":0,"powerState":"ac","runtimeFingerprintSha256":"$SHA","swapBytes":0,"thermalState":"nominal"}"""
      private val SNAPSHOT_FIELDS =
        listOf(
          "containerFingerprintSha256",
          "cpuIdlePercent",
          "cpuLoadPercent",
          "memoryPressure",
          "pageOuts",
          "powerState",
          "runtimeFingerprintSha256",
          "swapBytes",
          "thermalState",
        )
      private val WATCHER_ADDED_FIELDS =
        listOf(
          "containerFingerprintSha256",
          "pageOuts",
          "powerState",
          "runtimeFingerprintSha256",
        )
      private val PROFILER_CLASSES =
        mapOf(
          "application" to "com.salesforce.revoman.ReVoman",
          "graal" to "org.graalvm.polyglot.Context",
          "http4k" to "org.http4k.core.Request",
          "moshi" to "com.squareup.moshi.Moshi",
          "okio" to "okio.Buffer",
          "truffle" to "com.oracle.truffle.api.CallTarget",
        )

      private val GITHUB_QUALIFICATION =
        """{"cleanup":{"path":"host/cleanup.json","sha256":"$SHA"},"kind":"githubHosted","macFieldsInapplicableReason":"githubHosted","policyHash":"$SHA","setup":{"path":"host/setup.json","sha256":"$SHA"}}"""

      private val BOUNDED_QUALIFICATION =
        """{"campaignFieldsInapplicableReason":"standaloneBoundedDiagnostic","kind":"controlledMacBoundedDiagnostic","policyHash":"$SHA","postflight":{"path":"host/postflight.json","sha256":"$SHA"},"preflight":{"path":"host/preflight.json","sha256":"$SHA"},"restoration":{"path":"host/restoration.json","sha256":"$SHA"},"watcher":{"path":"host/watcher.json","sha256":"$SHA"}}"""

      private val GITHUB_SUBSTRATE =
        """{"advertisedResources":{"cpus":4,"memoryBytes":17179869184},"dockerEngineVersion":"28.3.3","kernel":"6.11.0","kind":"githubHosted","runnerImageVersion":"20260810.1","runnerLabel":"ubuntu-24.04-arm"}"""

      private val PROFILER_SUMMARY: String
        get() = goldenResourceBytes(GOLDEN_PROFILER_SUMMARY).decodeToString()

      private val PROFILER_SUMMARY_REF =
        """{"path":"profiler-summary.json","rawInputSha256":"$SHA","sha256":"$SHA","variantSha256":"$SHA"}"""

      private fun validProtocolDocuments(): Map<SchemaKind, String> =
        mapOf(
          SchemaKind.CAPTURE_PROVISIONAL to
            CanonicalJson.encode(validProvisionalCapture()).decodeToString(),
          SchemaKind.PREFLIGHT to
            """{"adapterSha256":"$SHA","architecture":"arm64","checks":{"containers":"pass","context":"pass","cpuIdle":"pass","image":"pass","interference":"pass","memoryPressure":"pass","power":"pass","runtime":"pass","swapPage":"pass","thermal":"pass","userIdle":"pass"},"kind":"preflight","lockAcquired":true,"observedAtUtc":"2026-08-16T00:00:00Z","operationId":"operation-0001","policySha256":"$SHA","schemaVersion":"preflight-v1","snapshot":$HOST_SNAPSHOT,"userIdleMillis":600000}""",
          SchemaKind.WATCHER to
            """{"cadenceMillis":1000,"completedAtUtc":"2026-08-16T00:01:00Z","expectedSamples":60,"kind":"watcher","observations":[{"containerFingerprintSha256":"$SHA","cpuLoadPercent":12.5,"event":"none","memoryPressure":"normal","observedAtUtc":"2026-08-16T00:00:01Z","pageOuts":0,"powerState":"ac","runtimeFingerprintSha256":"$SHA","swapBytes":0,"thermalState":"nominal"}],"observedSamples":60,"policySha256":"$SHA","schemaVersion":"watcher-v1","startedAtUtc":"2026-08-16T00:00:00Z","terminalState":"completed"}""",
          SchemaKind.POSTFLIGHT to
            """{"checks":{"cleanup":"pass","containers":"pass","cpuIdle":"pass","interference":"pass","memoryPressure":"pass","power":"pass","runtime":"pass","swapPage":"pass","thermal":"pass"},"kind":"postflight","observedAtUtc":"2026-08-16T00:02:00Z","policySha256":"$SHA","processExit":0,"schemaVersion":"postflight-v1","snapshot":$HOST_SNAPSHOT}""",
          SchemaKind.RESTORATION to
            """{"cleanupPassed":true,"kind":"restoration","lockReleaseReady":true,"observedAtUtc":"2026-08-16T00:03:00Z","policySha256":"$SHA","restoredState":"passed","schemaVersion":"restoration-v1"}""",
          SchemaKind.PROFILER_SUMMARY to PROFILER_SUMMARY,
        )

      private fun validateCapture(capture: JsonNode): List<SchemaViolation> =
        EvidenceSchemaValidator().validate(SchemaKind.CAPTURE, CanonicalJson.encode(capture))

      private fun githubHostedCapture(hostId: String): ObjectNode =
        goldenCapture().apply {
          objectNode("outcome").apply {
            put("strength", "diagnostic")
            set("claimEligibilityReasons", stringArray("githubHosted"))
          }
          set("qualification", parseObject(GITHUB_QUALIFICATION))
          objectNode("runtime").apply {
            put("hostId", hostId)
            set("substrate", parseObject(GITHUB_SUBSTRATE))
          }
        }

      private fun boundedDiagnosticCapture(hostId: String): ObjectNode =
        goldenCapture().apply {
          objectNode("outcome").apply {
            put("strength", "diagnostic")
            set("claimEligibilityReasons", stringArray("boundedDiagnostic"))
          }
          set("qualification", parseObject(BOUNDED_QUALIFICATION))
          objectNode("runtime").put("hostId", hostId)
        }

      private fun finalProfilerCapture(profiler: String): ObjectNode =
        boundedDiagnosticCapture(CONTROLLED_MAC_CAMPAIGN_HOST_ID).apply {
          objectNode("outcome").set("claimEligibilityReasons", stringArray("profilerDiagnostic"))
          objectNode("profile").put("profiler", profiler)
          set("profilerSummary", parseObject(PROFILER_SUMMARY_REF))
        }

      private fun provisionalProfilerCapture(profiler: String): ObjectNode =
        validProvisionalCapture().apply {
          objectNode("profile").put("profiler", profiler)
          if (profiler != "none") {
            objectNode("outcome").set("reasons", stringArray("profilerDiagnostic"))
          }
        }

      private fun assertRejectedWithoutEcho(
        violations: List<SchemaViolation>,
        rejectedContent: String,
      ) {
        violations.shouldNotBeEmpty()
        violations
          .joinToString(separator = "|") { violation ->
            "${violation.path}|${violation.keyword}|${violation.message}"
          }
          .shouldNotContainText(rejectedContent)
      }

      private fun validProtocolDocument(schema: SchemaKind): ObjectNode =
        parseObject(validProtocolDocuments().getValue(schema))

      private fun canonical(document: String): ByteArray =
        CanonicalJson.encode(CanonicalJson.parseStrict(document.encodeToByteArray()))

      private fun goldenCapture(): ObjectNode =
        CanonicalJson.parseStrict(goldenCaptureBytes()).asObject()

      private fun validProvisionalCapture(): ObjectNode =
        goldenCapture().apply {
          put("schemaVersion", "capture-provisional-v1")
          remove("qualification")
          set(
            "outcome",
            parseObject(
              """{"completedAtUtc":"2026-08-16T00:10:00Z","processExit":0,"reasons":["boundedDiagnostic"],"startedAtUtc":"2026-08-16T00:00:00Z","status":"valid","strength":"diagnostic"}""",
            ),
          )
        }

      private fun goldenCaptureBytes(): ByteArray = goldenResourceBytes(GOLDEN_CAPTURE)

      private fun goldenResourceBytes(path: String): ByteArray =
        checkNotNull(CaptureSchemaContractTest::class.java.getResourceAsStream(path)) {
            "missing golden resource $path"
          }
          .use { it.readAllBytes() }

      private fun parseObject(document: String): ObjectNode =
        CanonicalJson.parseStrict(document.encodeToByteArray()).asObject()

      private fun profilerSummary(
        category: String,
        className: String,
        methodName: String = "run",
      ): ObjectNode =
        parseObject(PROFILER_SUMMARY).apply {
          arrayNode("aggregates").first().asObject().apply {
            put("category", category)
            put("className", className)
            put("methodName", methodName)
          }
        }

      private fun stringArray(vararg values: String) =
        CanonicalJson.parseStrict(
          values.joinToString(prefix = "[\"", separator = "\",\"", postfix = "\"]").encodeToByteArray(),
        )

      private fun enumConstantNames(className: String): List<String>? =
        runCatching { Class.forName(className).enumConstants.map { it.toString() } }.getOrNull()

      private fun fieldTypes(className: String): Map<String, String> =
        runCatching {
            Class.forName(className)
              .declaredFields
              .filterNot { it.isSynthetic }
              .associate { field -> field.name to field.type.name }
          }
          .getOrDefault(emptyMap())

      private fun JsonNode.asObject(): ObjectNode = this as ObjectNode

      private fun ObjectNode.objectNode(property: String): ObjectNode = get(property).asObject()

      private fun ObjectNode.firstCell(): ObjectNode = get("cells").get(0).asObject()

      private fun ObjectNode.arrayNode(property: String) = get(property)
    }
  }
