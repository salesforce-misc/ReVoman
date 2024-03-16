/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output.report

import com.salesforce.revoman.input.json.jsonToPojo
import com.salesforce.revoman.output.endsWith
import com.squareup.moshi.JsonAdapter
import io.vavr.control.Either
import java.lang.reflect.Type
import org.http4k.core.HttpMessage
import org.http4k.core.Request
import org.http4k.core.Response

data class TxnInfo<HttpMsgT : HttpMessage>(
  @JvmField val txnObjType: Type? = null,
  @JvmField val txnObj: Any? = null,
  @JvmField val httpMsg: HttpMsgT,
  @JvmField val isJson: Boolean = true
) {
  fun <T> getTypedTxnObj(): T? = (txnObjType as? Class<T>)?.cast(txnObj)

  @JvmOverloads
  fun <T : Any> getTypedTxnObj(
    txObjType: Type,
    customAdapters: List<Any> = emptyList(),
    customAdaptersWithType: Map<Type, List<Either<JsonAdapter<Any>, JsonAdapter.Factory>>> =
      emptyMap(),
    typesToIgnore: Set<Class<out Any>> = emptySet()
  ): T? =
    jsonToPojo(
      txObjType,
      httpMsg.bodyString(),
      customAdapters,
      customAdaptersWithType,
      typesToIgnore
    )

  fun containsHeader(key: String): Boolean = httpMsg.headers.toMap().containsKey(key)

  fun containsHeader(key: String, value: String): Boolean = httpMsg.headers.contains(key to value)

  override fun toString(): String {
    val prefix =
      when (httpMsg) {
        is Request -> "⬆️RequestInfo ~~>"
        is Response -> "⬇️ResponseInfo <~~"
        else -> "TxnInfo"
      }
    return "$prefix\nType=$txnObjType\nObj=$txnObj\n$httpMsg"
  }

  companion object {
    @JvmStatic fun TxnInfo<Request>.getURIPath(): String = httpMsg.uri.path

    @JvmStatic
    fun TxnInfo<Request>.uriPathEndsWith(path: String): Boolean =
      httpMsg.uri.path.trim('/').split("/").endsWith(path.trim('/').split("/"))
  }
}
