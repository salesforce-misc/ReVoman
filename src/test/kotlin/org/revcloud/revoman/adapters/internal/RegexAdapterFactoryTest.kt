package org.revcloud.revoman.adapters.internal

import com.squareup.moshi.Moshi
import com.squareup.moshi.adapter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RegexAdapterFactoryTest {

  @OptIn(ExperimentalStdlibApi::class)
  @Test
  fun `Test regex replace in dynamic variables`() {
    val epoch = System.currentTimeMillis().toString()
    val dummyDynamicVariableGenerator = { r: String -> if (r == "\$epoch") epoch else null}
    val moshi = Moshi.Builder().add(RegexAdapterFactory(mapOf("key" to "value-{{\$epoch}}"), dummyDynamicVariableGenerator)).build().adapter<Any>()
    val result = moshi.fromJson("""
      {
        "epoch": "{{${"$"}epoch}}",
        "key": "{{key}}"
      }
      """.trimIndent())
    assertThat(result as Map<String, String>).containsExactlyEntriesOf(mapOf("epoch" to epoch, "key" to "value-$epoch"))
  }
}
