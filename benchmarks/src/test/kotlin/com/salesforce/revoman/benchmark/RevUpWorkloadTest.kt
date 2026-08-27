package com.salesforce.revoman.benchmark

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.security.MessageDigest
import java.util.HexFormat

class RevUpWorkloadTest :
  FunSpec({
    test("generated collection fixtures retain their exact bytes") {
      val fixtures =
        linkedMapOf(
          "steps-1-placeholders-0-script-free" to prepareCollection(stepCount = 1),
          "steps-10-placeholders-0-script-free" to prepareCollection(stepCount = 10),
          "steps-100-placeholders-0-script-free" to prepareCollection(stepCount = 100),
          "steps-500-placeholders-0-script-free" to prepareCollection(stepCount = 500),
          "steps-10-placeholders-1-script-free" to
            prepareCollection(stepCount = 10, placeholdersPerRequest = 1),
          "steps-10-placeholders-10-script-free" to
            prepareCollection(stepCount = 10, placeholdersPerRequest = 10),
          "steps-1-placeholders-0-script-bearing" to
            prepareCollection(stepCount = 1, includeScript = true),
          "steps-10-placeholders-0-script-bearing" to
            prepareCollection(stepCount = 10, includeScript = true),
        )

      fixtures.mapValues { (_, collection) -> collection.bytes.sha256() } shouldContainExactly
        mapOf(
          "steps-1-placeholders-0-script-free" to
            "4187b58262ef874db534ecf3e3f4f87059879a6dff18b8fa571743d0ee6c35f6",
          "steps-10-placeholders-0-script-free" to
            "f7e9e69f25cadceeb8fda5deaec3f1bae10c859f0e5ecb8179c1f1f729c211eb",
          "steps-100-placeholders-0-script-free" to
            "b628e450c990515f1ac30fc77de9edd5959e687093950e4f7079e68508fd75dc",
          "steps-500-placeholders-0-script-free" to
            "c2706b0ca3f9def94411f2636acc368f0d78b73ac581cb091b9ca79038ed0f51",
          "steps-10-placeholders-1-script-free" to
            "8f65d2096c5235a55a8ab1092903da7e208737bb4b76d2dfd5a86ac12186383e",
          "steps-10-placeholders-10-script-free" to
            "8c786b8e8add97857bebd6bbb148a1ae768213ab50dd163b2122113c35a046de",
          "steps-1-placeholders-0-script-bearing" to
            "79ad1975d0cc7de1a49a004d23754f29134a7b21a6b66ce663b71c5ec7f990a8",
          "steps-10-placeholders-0-script-bearing" to
            "bd1ff86cb7e319f0d3ac968c624cefe3b1978eb985f33770905e7d0ef64c5504",
        )

      fixtures.getValue("steps-1-placeholders-0-script-free").bytes.decodeToString().run {
        this shouldContain "\"raw\":\"http://benchmark.invalid//step-1\""
        this shouldNotContain "http://benchmark.invalid/step-1"
      }
    }
  })

private fun ByteArray.sha256(): String =
  HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(this))
