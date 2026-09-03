package com.salesforce.revoman.benchmark

import com.salesforce.revoman.ReVoman
import com.salesforce.revoman.output.Verbosity
import com.salesforce.revoman.output.toJson
import com.squareup.moshi.Moshi
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path
import org.http4k.core.Method

class ConsumerJourneyFixturesTest :
  FunSpec({
    test("V2 and V3 collections execute the same deterministic requests") {
      prepareConsumerJourneys().use { prepared ->
        val expectedPaths = (1..10).map { "/step-${it.toString().padStart(4, '0')}" }

        listOf(prepared.postmanV2TenStep, prepared.v3TenStep).forEach { kick ->
          prepared.handlerLedger.reset()

          ReVoman.revUp(kick).validate(expectedStepCount = 10)

          prepared.handlerLedger.calls().map { it.method } shouldContainExactly
            List(10) { Method.GET }
          prepared.handlerLedger.calls().map { it.path } shouldContainExactly expectedPaths
          prepared.handlerLedger.calls().map { it.body } shouldContainExactly List(10) { "" }
        }

        prepared.handlerLedger.reset()

        ReVoman.revUp(prepared.v3HundredStep).validate(expectedStepCount = 100)

        prepared.handlerLedger.calls().size shouldBe 100
        prepared.handlerLedger.calls().all { it.method == Method.GET } shouldBe true
        prepared.handlerLedger.calls().all { it.path.startsWith("/step-") } shouldBe true
        prepared.handlerLedger.calls().all { it.body.isEmpty() } shouldBe true
        prepared.handlerLedger.calls().all { it.uri.host == "benchmark.invalid" } shouldBe true
        prepared.handlerLedger.calls().none {
          it.uri.host == "localhost" || it.uri.host == "127.0.0.1" || it.uri.host == "::1"
        } shouldBe true
      }
    }

    test("script-bearing V3 collection executes its Postman script") {
      prepareConsumerJourneys().use { prepared ->
        val rundown = ReVoman.revUp(prepared.v3TenStepScripted)

        rundown.validate(expectedStepCount = 10)
        rundown.mutableEnv["scriptedMarker"] shouldBe "after-response-ran"
        prepared.handlerLedger.calls().size shouldBe 10
      }
    }

    test("script-bearing V2 collection executes its Postman script") {
      prepareConsumerJourneys().use { prepared ->
        val rundown = ReVoman.revUp(prepared.postmanV2TenStepScripted)

        rundown.validate(expectedStepCount = 10)
        rundown.mutableEnv["scriptedMarker"] shouldBe "after-response-ran"
        prepared.handlerLedger.calls().size shouldBe 10
      }
    }

    test("three kicks hand off mixed environment value types through Postman scripts") {
      prepareConsumerJourneys().use { prepared ->
        val handoffKeys = setOf("handoffCount", "handoffReady", "handoffTags", "lookupKey")
        prepared.threeKicks.drop(1).forEach { kick ->
          kick.dynamicEnvironment().keys.intersect(handoffKeys).shouldBeEmpty()
        }

        val rundowns = ReVoman.revUp(prepared.threeKicks)

        rundowns.size shouldBe 3
        rundowns.forEach { it.validate(expectedStepCount = 10) }
        prepared.handlerLedger.calls().size shouldBe 30
        rundowns[1].immutableEnv.filterKeys { it in handoffKeys } shouldContainExactly
          mapOf(
            "handoffCount" to 7,
            "handoffReady" to true,
            "handoffTags" to listOf("alpha", "beta"),
            "lookupKey" to "beta-7-true",
          )
        prepared.handlerLedger.calls()[20].uri.query shouldContain "tags="
        prepared.handlerLedger.calls()[20].uri.query shouldContain "alpha"
        prepared.handlerLedger.calls()[20].uri.query shouldContain "beta"
        rundowns[2].mutableEnv["lookupObserved"] shouldBe "beta-7-true"
      }
    }

    test("runbook applies contracts around the same three environment handoff kicks") {
      prepareConsumerJourneys().use { prepared ->
        prepared.runbook.steps.map { it.kick } shouldContainExactly prepared.threeKicks
        prepared.runbook.steps[0].produces.keys shouldBe
          setOf("handoffCount", "handoffReady", "handoffTags")
        prepared.runbook.steps[1].consumes shouldBe
          setOf("handoffCount", "handoffReady", "handoffTags")
        prepared.runbook.steps[1].produces shouldContainExactly mapOf("lookupKey" to "beta-7-true")
        prepared.runbook.steps[1].underTest shouldBe true
        prepared.runbook.steps[1].assertAfter.shouldNotBeNull()
        prepared.runbook.steps[2].consumes shouldBe setOf("lookupKey")

        val rundown = ReVoman.revUp(prepared.runbook)

        rundown.size shouldBe 3
        rundown.forEach { it.validate(expectedStepCount = 10) }
        prepared.handlerLedger.calls().size shouldBe 30
        prepared.handlerLedger.assertAfterCount() shouldBe 1
        rundown[2].mutableEnv["lookupObserved"] shouldBe "beta-7-true"
      }
    }

    test("consumer journey preparation executes no request") {
      prepareConsumerJourneys().use { prepared -> prepared.handlerLedger.calls().shouldBeEmpty() }
    }

    test("verbose rendering preparation stores one validated hundred-step rundown") {
      prepareVerboseRendering().use { prepared ->
        prepared.setupRequestCount shouldBe 100
        prepared.rundown.validate(expectedStepCount = 100)

        val document =
          Moshi.Builder()
            .build()
            .adapter(Any::class.java)
            .fromJson(prepared.rundown.toJson(Verbosity.VERBOSE)) as Map<*, *>
        val stepReports = document["stepReports"] as List<*>
        val firstStep = stepReports.first() as Map<*, *>

        stepReports.size shouldBe 100
        document["areAllStepsSuccessful"] shouldBe true
        (document["environment"] as Map<*, *>)["renderingSeed"] shouldBe "verbose"
        (firstStep["request"] as Map<*, *>)["method"] shouldBe "GET"
        (firstStep["request"] as Map<*, *>).containsKey("body") shouldBe true
        (firstStep["response"] as Map<*, *>)["statusCode"] shouldBe 200.0
        (firstStep["response"] as Map<*, *>).containsKey("body") shouldBe true
        (firstStep["envSnapshot"] as Map<*, *>)["renderingSeed"] shouldBe "verbose"
      }
    }

    test("prepared verbose rendering removes its owned fixture root") {
      val prepared = prepareVerboseRendering()
      val fixtureRoot = prepared.fixtureRoot

      Files.exists(fixtureRoot) shouldBe true

      prepared.close()

      Files.notExists(fixtureRoot) shouldBe true
    }

    test("prepared consumer journeys remove only their owned fixture root") {
      val prepared = prepareConsumerJourneys()
      val fixtureRoot = Path.of(prepared.postmanV2TenStep.templatePaths().single()).parent

      Files.exists(fixtureRoot) shouldBe true

      prepared.close()

      Files.notExists(fixtureRoot) shouldBe true
    }

    test("failed fixture preparation removes its temporary root") {
      lateinit var failedFixtureRoot: Path

      shouldThrow<IllegalStateException> {
        withTemporaryFixtureRoot("revoman-consumer-failed-preparation-") { fixtureRoot ->
          failedFixtureRoot = fixtureRoot
          error("fixture preparation failed")
        }
      }

      Files.notExists(failedFixtureRoot) shouldBe true
    }
  })
