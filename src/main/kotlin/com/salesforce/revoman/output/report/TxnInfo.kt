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
import com.squareup.moshi.rawType
import io.exoquery.pprint
import io.vavr.control.Either
import java.lang.reflect.Type
import java.util.Collections.indexOfSubList
import org.http4k.core.HttpMessage
import org.http4k.core.Request
import org.http4k.core.Response

data class TxnInfo<HttpMsgT : HttpMessage>
@JvmOverloads
constructor(
  @JvmField val isJson: Boolean = true,
  @JvmField val txnObjType: Type? = if (isJson) Any::class.java else null,
  @JvmField val txnObj: Any? = null,
  @JvmField val httpMsg: HttpMsgT,
  val moshiReVoman: MoshiReVoman,
) {
  @JvmOverloads
  fun <PojoT : Any> getTypedTxnObj(
    targetType: Type = this.txnObjType ?: Any::class.java,
    customAdapters: List<Any> = emptyList(),
    customAdaptersWithType: Map<Type, Either<JsonAdapter<out Any>, JsonAdapter.Factory>> =
      emptyMap(),
    typesToIgnore: Set<Type> = emptySet(),
  ): PojoT? =
    when {
      // ! TODO 15/10/23 gopala.akshintala: xml2Json
      txnObj == null -> null
      targetType.rawType.isInstance(txnObj) -> txnObj
      targetType == String::class.java -> txnObj
      !isJson ->
        throw IllegalCallerException("Non JSON (like XML) marshalling to POJO is not yet supported")
      else -> {
        moshiReVoman.addAdapters(customAdapters, customAdaptersWithType, typesToIgnore)
        moshiReVoman.fromJson(httpMsg.bodyString(), targetType)
      }
    }
      as PojoT?

  @JvmOverloads
  inline fun <reified PojoT : Any> getTxnObj(
    customAdapters: List<Any> = emptyList(),
    customAdaptersWithType: Map<Type, Either<JsonAdapter<out Any>, JsonAdapter.Factory>> =
      emptyMap(),
    typesToIgnore: Set<Type> = emptySet(),
  ): PojoT? =
    when {
      txnObj == null -> null
      txnObj is PojoT -> txnObj
      PojoT::class == String::class -> txnObj
      !isJson ->
        throw IllegalCallerException("Non JSON (like XML) marshalling to POJO is not yet supported")
      else -> {
        moshiReVoman.addAdapters(customAdapters, customAdaptersWithType, typesToIgnore)
        moshiReVoman.fromJson(httpMsg.bodyString())
      }
    }
      as PojoT?

  fun containsHeader(key: String): Boolean = httpMsg.headers.toMap().containsKey(key)

  fun containsHeader(key: String, value: String): Boolean = httpMsg.headers.contains(key to value)

  fun getHeaderValue(key: String): String? = httpMsg.header(key)

  override fun toString(): String {
    val prefix =
      when (httpMsg) {
        is Request -> "⬆️ Request Info ~~>"
        is Response -> "⬇️ Response Info <~~"
        else -> "TxnInfo"
      }
    return "\n${pprint(prefix)}\nType=$txnObjType\nObj=$txnObj\n${pprint(httpMsg)}"
  }

  companion object {
    @JvmStatic fun TxnInfo<Request>.getURIPath(): String = httpMsg.uri.path

    @JvmStatic
    fun TxnInfo<Request>.uriPathContains(path: String): Boolean =
      indexOfSubList(httpMsg.uri.path.trim('/').split("/"), path.trim('/').split("/")) != -1

    @JvmStatic
    fun TxnInfo<Request>.uriPathEndsWith(path: String): Boolean {
      val sourcePath = httpMsg.uri.path.trim('/').split("/")
      val targetPath = path.trim('/').split("/")
      val indexOfSubList = indexOfSubList(sourcePath, targetPath)
      return indexOfSubList != -1 && indexOfSubList + targetPath.lastIndex == sourcePath.lastIndex
    }
  }
}
