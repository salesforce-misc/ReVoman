package org.revcloud.revoman.internal.postman

import com.squareup.moshi.Moshi
import com.squareup.moshi.adapter
import io.kotest.matchers.maps.shouldContainAll
import org.junit.jupiter.api.Test

class RegexReplacerTest {

  @OptIn(ExperimentalStdlibApi::class)
  @Test
  fun `regex replace with dynamic variables`() {
    val epoch = System.currentTimeMillis().toString()
    val dummyDynamicVariableGenerator = { key: String -> if (key == "\$epoch") epoch else null }
    val regexReplacer =
      RegexReplacer(
        mutableMapOf("key" to "value-{{\$epoch}}"),
        emptyMap(),
        dummyDynamicVariableGenerator
      )
    val jsonStr =
      """
      {
        "epoch": "{{${"$"}epoch}}",
        "key": "{{key}}"
      }
      """
        .trimIndent()
    val resultStr = regexReplacer.replaceRegexRecursively(jsonStr)!!
    val result = Moshi.Builder().build().adapter<Map<String, String>>().fromJson(resultStr)!!
    result shouldContainAll mapOf("epoch" to epoch, "key" to "value-$epoch")
  }

  @OptIn(ExperimentalStdlibApi::class)
  @Test
  fun `regex replace with custom dynamic variables`() {
    val customEpoch = "Custom - ${System.currentTimeMillis()}"
    val customDynamicVariableGenerator = { _: String -> customEpoch }
    val regexReplacer =
      RegexReplacer(
        mutableMapOf("key" to "value-{{\$customEpoch}}"),
        mapOf("\$customEpoch" to customDynamicVariableGenerator)
      )
    val jsonStr =
      """
      {
        "epoch": "{{${"$"}customEpoch}}",
        "key": "{{key}}"
      }
      """
        .trimIndent()
    val resultStr = regexReplacer.replaceRegexRecursively(jsonStr)!!
    val result = Moshi.Builder().build().adapter<Map<String, String>>().fromJson(resultStr)!!
    result shouldContainAll mapOf("epoch" to customEpoch, "key" to "value-$customEpoch")
  }
}
