package org.revcloud.revoman.adapters.internal

import com.squareup.moshi.Moshi
import com.squareup.moshi.adapter
import io.kotest.matchers.maps.shouldContainAll
import org.junit.jupiter.api.Test
import org.revcloud.revoman.internal.adapters.RegexAdapterFactory

class RegexAdapterFactoryTest {

  @OptIn(ExperimentalStdlibApi::class)
  @Test
  fun `regex replace with dynamic variables`() {
    val epoch = System.currentTimeMillis().toString()
    val dummyDynamicVariableGenerator = { key: String -> if (key == "\$epoch") epoch else null }
    val moshi = Moshi.Builder().add(RegexAdapterFactory(mapOf("key" to "value-{{\$epoch}}"), emptyMap(), dummyDynamicVariableGenerator)).build().adapter<Any>()
    val result = moshi.fromJson(
      """
      {
        "epoch": "{{${"$"}epoch}}",
        "key": "{{key}}"
      }
      """.trimIndent()
    ) as Map<String, String>
    result shouldContainAll mapOf("epoch" to epoch, "key" to "value-$epoch")
  }

  @OptIn(ExperimentalStdlibApi::class)
  @Test
  fun `regex replace with custom dynamic variables`() {
    val customEpoch = "Custom - ${System.currentTimeMillis()}"
    val customDynamicVariableGenerator = { _: String -> customEpoch }
    val moshi = Moshi.Builder().add(RegexAdapterFactory(mapOf("key" to "value-{{\$customEpoch}}"), mapOf("\$customEpoch" to customDynamicVariableGenerator))).build().adapter<Any>()
    val result = moshi.fromJson(
      """
      {
        "epoch": "{{${"$"}customEpoch}}",
        "key": "{{key}}"
      }
      """.trimIndent()
    ) as Map<String, String>
    result shouldContainAll mapOf("epoch" to customEpoch, "key" to "value-$customEpoch")
  }
}
