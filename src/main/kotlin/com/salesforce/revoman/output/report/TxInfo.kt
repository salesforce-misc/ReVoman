/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output.report

import com.salesforce.revoman.input.json.jsonToPojo
import com.squareup.moshi.JsonAdapter
import io.vavr.control.Either
import java.lang.reflect.Type
import org.http4k.core.HttpMessage
import org.http4k.core.Request
import org.http4k.core.Response

data class TxInfo<HttpMsgT : HttpMessage>(
  val txObjType: Type? = null,
  val txObj: Any? = null,
  val httpMsg: HttpMsgT
) {
  fun <T> getTypedTxObj(): T? = txObjType?.let { (it as Class<T>).cast(txObj) }

  @JvmOverloads
  fun <T : Any> getTypedTxObj(
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
        is Request -> "RequestInfo⬆️"
        is Response -> "ResponseInfo⬇️"
        else -> "TxInfo"
      }
    return "$prefix(Type=$txObjType, Obj=$txObj, $httpMsg)"
  }

  companion object {
    @JvmStatic fun TxInfo<Request>.getPath(): String = httpMsg.uri.path
  }
}
