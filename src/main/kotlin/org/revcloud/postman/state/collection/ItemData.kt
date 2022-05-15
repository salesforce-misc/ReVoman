package org.revcloud.postman.state.collection

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonClass
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import dev.zacsweers.moshix.adapters.AdaptedBy
import okio.BufferedSource

@JsonClass(generateAdapter = true)
@AdaptedBy(ItemDataAdapter::class)
data class ItemData(val data: String)

class ItemDataAdapter : JsonAdapter<ItemData>() {
  override fun fromJson(reader: JsonReader): ItemData {
    return ItemData(reader.nextSource().use(BufferedSource::readUtf8))
  }

  override fun toJson(writer: JsonWriter, itemData: ItemData?) {
    writer.valueSink().use { sink -> sink.writeUtf8(checkNotNull(itemData?.data)) }
  }
}
