package org.revcloud.loki.common.json.adapters

import com.squareup.moshi.FromJson
import com.squareup.moshi.ToJson
import java.util.UUID

object UUIDAdapter {
  @ToJson fun toJson(uuid: UUID): String = uuid.toString()

  @FromJson fun fromJson(uuidStr: String): UUID = UUID.fromString(uuidStr)
}
