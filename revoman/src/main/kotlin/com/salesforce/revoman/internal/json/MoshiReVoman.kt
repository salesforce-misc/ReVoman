/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.json

import com.salesforce.revoman.internal.json.adapters.BigDecimalAdapter
import com.salesforce.revoman.internal.json.adapters.EpochAdapter
import com.salesforce.revoman.internal.json.adapters.TypeAdapter
import com.salesforce.revoman.internal.json.adapters.UUIDAdapter
import com.salesforce.revoman.internal.json.factories.AlwaysSerializeNullsFactory
import com.salesforce.revoman.internal.json.factories.CaseInsensitiveEnumAdapter
import com.salesforce.revoman.internal.json.factories.IgnoreTypesFactory
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonAdapter.Factory
import com.squareup.moshi.Moshi
import com.squareup.moshi.adapter
import com.squareup.moshi.adapters.Rfc3339DateJsonAdapter
import dev.zacsweers.moshix.adapters.AdaptedBy
import dev.zacsweers.moshix.adapters.JsonString
import io.vavr.control.Either
import java.lang.reflect.Type
import java.util.*
import org.http4k.format.ListAdapter
import org.http4k.format.MapAdapter
import org.http4k.format.ThrowableAdapter
import org.http4k.format.asConfigurable
import org.http4k.format.withStandardMappings

open class MoshiReVoman(builder: Moshi.Builder) {
  var moshi: Moshi = builder.build()

  @get:JvmName("internalMoshiCopy")
  val internalMoshiCopy: Moshi by lazy { moshi.newBuilder().build() }

  @Synchronized
  fun addAdapters(
    customAdapters: List<Any> = emptyList(),
    customAdaptersWithType: Map<Type, Either<JsonAdapter<out Any>, Factory>> = emptyMap(),
    typesToIgnore: Set<Type> = emptySet(),
  ) {
    if (customAdapters.isNotEmpty() || customAdaptersWithType.isNotEmpty()) {
      val builder = moshi.newBuilder()
      addAdapters(builder, customAdapters, customAdaptersWithType, typesToIgnore)
      moshi = builder.build()
    }
  }

  fun <PojoT : Any> adapter(targetType: Type): JsonAdapter<PojoT> = moshi.adapter<PojoT>(targetType)

  @OptIn(ExperimentalStdlibApi::class)
  inline fun <reified PojoT : Any> adapter(): JsonAdapter<PojoT> = moshi.adapter<PojoT>()

  fun <PojoT : Any> lenientAdapter(
    targetType: Type,
    serializeNulls: Boolean = false,
  ): JsonAdapter<PojoT> =
    (if (serializeNulls) adapter<PojoT>(targetType).serializeNulls()
      else adapter<PojoT>(targetType))
      .lenient()

  inline fun <reified PojoT : Any> lenientAdapter(
    serializeNulls: Boolean = false
  ): JsonAdapter<PojoT> =
    (if (serializeNulls) adapter<PojoT>().serializeNulls() else adapter<PojoT>()).lenient()

  fun <PojoT : Any> fromJson(input: String?, targetType: Type = Any::class.java): PojoT? =
    input?.let {
      lenientAdapter<PojoT>(targetType).fromJson(it)
    }

  inline fun <reified PojoT : Any> fromJson(input: String?): PojoT? = input?.let {
    lenientAdapter<PojoT>().fromJson(it)
  }

  // * NOTE: strict (non-lenient) parse probe. Gates the byte-for-byte JsonPretty render, which
  //   only understands STRICT JSON; a JSON5-lenient body (single-quoted strings / unquoted names)
  //   must instead go through the normalizing round-trip, else JsonPretty mangles structural chars
  //   inside a lenient string literal. `adapter<Any>()` (no `.lenient()`) rejects JSON5 and any
  //   unconsumed trailing content.
  fun isStrictJson(json: String): Boolean = runCatching { adapter<Any>().fromJson(json) }.isSuccess

  fun <PojoT : Any> toJson(
    input: PojoT?,
    serializeNulls: Boolean = false,
    sourceType: Type = input?.javaClass ?: Any::class.java,
  ): String = lenientAdapter<PojoT>(sourceType, serializeNulls).toJson(input)

  inline fun <reified PojoT : Any> toJson(input: PojoT?, serializeNulls: Boolean = false): String =
    lenientAdapter<PojoT>(serializeNulls).toJson(input)

  fun <PojoT : Any> toPrettyJson(
    input: PojoT?,
    sourceType: Type = input?.javaClass ?: Any::class.java,
    serializeNulls: Boolean = false,
    indent: String = "  ",
  ): String = lenientAdapter<PojoT>(sourceType, serializeNulls).indent(indent).toJson(input)

  inline fun <reified PojoT : Any> toPrettyJson(
    input: PojoT?,
    serializeNulls: Boolean = false,
    indent: String = "  ",
  ): String = lenientAdapter<PojoT>(serializeNulls).indent(indent).toJson(input)

  // * NOTE: bridge source->target through a Moshi value-tree instead of a JSON-string round-trip;
  //   avoids one full serialize+parse pass. Verified behaviour-equivalent to the old string path
  //   (see ObjToJsonStrToObjTest): for concrete target types both paths are identical, and for
  //   UNTYPED (Any/raw Map) targets both coerce numbers to Double alike (Moshi's untyped adapter
  //   reads every number token via nextDouble() on both the string reader and the value-tree
  //   reader), so there is NO behaviour delta. See PostmanEnvironment.getObj/getTypedObj consumers.
  fun <PojoT : Any> objToJsonStrToObj(
    input: Any?,
    targetType: Type = input?.javaClass ?: Any::class.java,
  ): PojoT? =
    lenientAdapter<PojoT>(targetType)
      .fromJsonValue(lenientAdapter<Any>(input?.javaClass ?: Any::class.java).toJsonValue(input))

  inline fun <reified PojoT : Any> objToJsonStrToObj(input: Any?): PojoT? =
    lenientAdapter<PojoT>()
      .fromJsonValue(lenientAdapter<Any>(input?.javaClass ?: Any::class.java).toJsonValue(input))

  fun jsonToObjToPrettyJson(input: String?, serializeNulls: Boolean = false): String? = input?.let {
    toPrettyJson(fromJson(it), serializeNulls)
  }

  fun anyToString(value: Any?): String =
    when (value) {
      is String -> value
      // * NOTE 08 Mar 2025 gopala.akshintala: To be consistent with Postman app behavior
      null -> "null"
      else -> toJson(value)
    }

  companion object {
    @Synchronized
    @JvmOverloads
    internal fun initMoshi(
      customAdapters: List<Any> = emptyList(),
      customAdaptersWithType: Map<Type, Either<JsonAdapter<out Any>, Factory>> = emptyMap(),
      typesToIgnore: Set<Type> = emptySet(),
    ): MoshiReVoman {
      val moshiBuilder = buildMoshi(customAdapters, customAdaptersWithType, typesToIgnore)
      return object : MoshiReVoman(moshiBuilder) {}
    }

    private fun buildMoshi(
      customAdapters: List<Any> = emptyList(),
      customAdaptersWithType: Map<Type, Either<JsonAdapter<out Any>, Factory>> = emptyMap(),
      typesToIgnore: Set<Type> = emptySet(),
    ): Moshi.Builder {
      // * NOTE 08 May 2024 gopala.akshintala: This cannot be static singleton as adapters added
      // mutates the singleton.
      val moshiBuilder =
        Moshi.Builder()
          .add(JsonString.Factory())
          .add(AdaptedBy.Factory())
          .add(TypeAdapter)
          .add(BigDecimalAdapter)
          .add(UUIDAdapter)
          .add(EpochAdapter)
          .add(Date::class.java, Rfc3339DateJsonAdapter())
          .addLast(CaseInsensitiveEnumAdapter.FACTORY)
          .addLast(AlwaysSerializeNullsFactory())
          .addLast(ThrowableAdapter)
          .addLast(ListAdapter)
          .addLast(MapAdapter)
          .asConfigurable()
          .withStandardMappings()
          .done()
      addAdapters(moshiBuilder, customAdapters, customAdaptersWithType, typesToIgnore)
      return moshiBuilder
    }

    @SuppressWarnings("kotlin:S3923")
    private fun addAdapters(
      moshiBuilder: Moshi.Builder,
      customAdapters: List<Any>,
      customAdaptersWithType: Map<Type, Either<JsonAdapter<out Any>, Factory>>,
      typesToIgnore: Set<Type>,
    ) {
      customAdaptersWithType.forEach { (type, customAdapter) ->
        customAdapter.fold({ moshiBuilder.add(type, it) }, { moshiBuilder.add(it) })
      }
      for (adapter in customAdapters) {
        if (adapter is Factory) {
          moshiBuilder.add(adapter)
        } else {
          moshiBuilder.add(adapter)
        }
      }
      if (typesToIgnore.isNotEmpty()) {
        moshiBuilder.add(IgnoreTypesFactory(typesToIgnore))
      }
    }
  }
}

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class AlwaysSerializeNulls
