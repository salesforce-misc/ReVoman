/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output.report

import com.salesforce.revoman.internal.json.MoshiReVoman
import com.squareup.moshi.JsonAdapter
import io.vavr.control.Either
import java.lang.reflect.Type
import java.util.Collections.indexOfSubList
import org.http4k.core.HttpMessage
import org.http4k.core.Request
import org.http4k.core.Response

data class TxnInfo<HttpMsgT : HttpMessage>
@JvmOverloads
constructor(
  @JvmField val txnObjType: Type = Any::class.java,
  @JvmField val txnObj: Any? = null,
  @JvmField val httpMsg: HttpMsgT,
  @JvmField val isJson: Boolean = true,
  private val moshiReVoman: MoshiReVoman,
) {
  @JvmOverloads
  fun <T : Any> getTypedTxnObj(
    txnObjType: Type = this.txnObjType,
    customAdapters: List<Any> = emptyList(),
    customAdaptersWithType: Map<Type, Either<JsonAdapter<out Any>, JsonAdapter.Factory>> =
      emptyMap(),
    typesToIgnore: Set<Class<out Any>> = emptySet(),
  ): T? {
    moshiReVoman.addAdapters(customAdapters, customAdaptersWithType, typesToIgnore)
    return moshiReVoman.fromJson(httpMsg.bodyString(), txnObjType)
  }

  @JvmOverloads
  inline fun <reified T : Any> getTxnObj(
    customAdapters: List<Any> = emptyList(),
    customAdaptersWithType: Map<Type, Either<JsonAdapter<out Any>, JsonAdapter.Factory>> =
      emptyMap(),
    typesToIgnore: Set<Class<out Any>> = emptySet(),
  ): T? = getTypedTxnObj(T::class.java, customAdapters, customAdaptersWithType, typesToIgnore)

  fun containsHeader(key: String): Boolean = httpMsg.headers.toMap().containsKey(key)

  fun containsHeader(key: String, value: String): Boolean = httpMsg.headers.contains(key to value)

  fun getHeaderValue(key: String): String? = httpMsg.headers.toMap()[key]

  override fun toString(): String {
    val prefix =
      when (httpMsg) {
        is Request -> "⬆️ Request Info ~~>"
        is Response -> "⬇️ Response Info <~~"
        else -> "TxnInfo"
      }
    return "\n$prefix\nType=$txnObjType\nObj=$txnObj\n$httpMsg"
  }

  companion object {
    @JvmStatic fun TxnInfo<Request>.getURIPath(): String = httpMsg.uri.path

    @JvmStatic
    fun TxnInfo<Request>.uriPathContains(path: String): Boolean =
      indexOfSubList(httpMsg.uri.path.trim('/').split("/"), path.trim('/').split("/")) != -1
  }
}
