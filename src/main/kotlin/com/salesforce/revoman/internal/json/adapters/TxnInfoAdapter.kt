/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.json.adapters

import com.salesforce.revoman.internal.json.MoshiReVoman
import com.salesforce.revoman.output.report.TxnInfo
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import com.squareup.moshi.rawType
import java.lang.reflect.Type
import org.http4k.core.HttpMessage
import org.http4k.core.Request

class TxnInfoAdapter<HttpMsgT : HttpMessage>(moshi: Moshi) : JsonAdapter<TxnInfo<HttpMsgT>>() {
  private val anyAdapter: JsonAdapter<Any> = moshi.adapter(Any::class.java)
  private val typeAdapter: JsonAdapter<Type> = moshi.adapter(Type::class.java)

  override fun fromJson(reader: JsonReader): TxnInfo<HttpMsgT>? {
    var isJson: Boolean = true
    var txnObjType: Type? = null
    var txnObj: Any? = null
    val httpMsg: HttpMsgT? = null
    val moshiReVoman: MoshiReVoman? = null

    reader.beginObject()
    while (reader.hasNext()) {
      when (reader.nextName()) {
        "isJson" -> isJson = reader.nextBoolean()
        "txnObjType" -> txnObjType = typeAdapter.fromJson(reader)
        "txnObj" -> txnObj = anyAdapter.fromJson(reader)
        else -> reader.skipValue()
      }
    }
    reader.endObject()

    if (httpMsg == null || moshiReVoman == null) {
      return null
    }

    return TxnInfo(
      isJson = isJson,
      txnObjType = txnObjType,
      txnObj = txnObj,
      httpMsg = httpMsg,
      moshiReVoman = moshiReVoman,
    )
  }

  override fun toJson(writer: JsonWriter, value: TxnInfo<HttpMsgT>?) {
    if (value == null) {
      writer.nullValue()
      return
    }
    writer.beginObject()
    writer.name("isJson").value(value.isJson)
    if (value.txnObjType != null) {
      writer.name("txnObjType")
      typeAdapter.toJson(writer, value.txnObjType)
    }
    if (value.txnObj != null) {
      writer.name("txnObj")
      anyAdapter.toJson(writer, value.txnObj)
    }
    if (value.httpMsg is Request) {
      writer.name("request")
      writer.beginObject()
      writer.name("method").value(value.httpMsg.method.toString())
      writer.name("uri").value(value.httpMsg.uri.toString())
    } else {
      writer.name("response")
      writer.beginObject()
    }
    writer.name("headers")
    writer.beginArray()
    value.httpMsg.headers.toMap().forEach { (key, value) ->
      writer.beginObject()
      writer.name("key").value(key)
      writer.name("value").value(value)
      writer.endObject()
    }
    writer.endArray()
    writer.name("version").value(value.httpMsg.version)
    writer.name("body").value(value.httpMsg.bodyString())
    writer.endObject()
    writer.endObject()
  }

  companion object {
    @JvmStatic
    fun <HttpMsgT : HttpMessage> factory(): Factory =
      object : Factory {
        override fun create(
          type: Type,
          annotations: Set<Annotation>,
          moshi: Moshi,
        ): JsonAdapter<*>? {
          if (type.rawType != TxnInfo::class.java || annotations.isNotEmpty()) {
            return null
          }
          return TxnInfoAdapter<HttpMsgT>(moshi)
        }
      }
  }
}
