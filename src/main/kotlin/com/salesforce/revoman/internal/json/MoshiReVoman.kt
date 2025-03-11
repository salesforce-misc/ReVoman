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
import com.salesforce.revoman.internal.json.factories.SkipTypesFactory
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonAdapter.Factory
import com.squareup.moshi.Moshi
import com.squareup.moshi.adapters.Rfc3339DateJsonAdapter
import dev.zacsweers.moshix.adapters.AdaptedBy
import dev.zacsweers.moshix.adapters.JsonString
import io.vavr.control.Either
import java.lang.reflect.Type
import java.util.*
import org.http4k.format.EventAdapter
import org.http4k.format.ListAdapter
import org.http4k.format.MapAdapter
import org.http4k.format.ThrowableAdapter
import org.http4k.format.asConfigurable
import org.http4k.format.withStandardMappings

open class MoshiReVoman(builder: Moshi.Builder) {
  private var moshi = builder.build()

  @get:JvmName("internalMoshiCopy")
  val internalMoshiCopy: Moshi by lazy { moshi.newBuilder().build() }

  @Synchronized
  fun addAdapters(
    customAdapters: List<Any> = emptyList(),
    customAdaptersWithType: Map<Type, Either<JsonAdapter<out Any>, Factory>> = emptyMap(),
    typesToIgnore: Set<Class<out Any>> = emptySet(),
  ) {
    val builder = moshi.newBuilder()
    addAdapters(builder, customAdapters, customAdaptersWithType, typesToIgnore)
    moshi = builder.build()
  }

  fun <PojoT : Any> adapter(targetType: Type): JsonAdapter<PojoT> = moshi.adapter<PojoT>(targetType)

  fun <PojoT : Any> lenientAdapter(targetType: Type): JsonAdapter<PojoT> =
    adapter<PojoT>(targetType).lenient()

  fun <PojoT : Any> fromJson(input: String?, targetType: Type = Any::class.java): PojoT? =
    input?.let { lenientAdapter<PojoT>(targetType).fromJson(it) }

  inline fun <reified PojoT : Any> fromJson(input: String?): PojoT? =
    fromJson(input, PojoT::class.java)

  fun <PojoT : Any> toJson(
    input: PojoT?,
    sourceType: Type = input?.javaClass ?: Any::class.java,
  ): String = lenientAdapter<PojoT>(sourceType).toJson(input)

  inline fun <reified PojoT : Any> toJson(input: PojoT?): String = toJson(input, PojoT::class.java)

  fun <PojoT : Any> toPrettyJson(
    input: PojoT?,
    sourceType: Type = input?.javaClass ?: Any::class.java,
    indent: String = "  ",
  ): String = lenientAdapter<PojoT>(sourceType).indent(indent).toJson(input)

  inline fun <reified PojoT : Any> toPrettyJson(input: PojoT?, indent: String = "  "): String =
    toPrettyJson(input, PojoT::class.java, indent)

  fun <PojoT : Any> objToJsonStrToObj(
    input: Any?,
    targetType: Type = input?.javaClass ?: Any::class.java,
  ): PojoT? = lenientAdapter<PojoT>(targetType).fromJson(toJson(input))

  companion object {
    @Synchronized
    @JvmOverloads
    internal fun initMoshi(
      customAdapters: List<Any> = emptyList(),
      customAdaptersWithType: Map<Type, Either<JsonAdapter<out Any>, Factory>> = emptyMap(),
      typesToIgnore: Set<Class<out Any>> = emptySet(),
    ): MoshiReVoman {
      val moshiBuilder = buildMoshi(customAdapters, customAdaptersWithType, typesToIgnore)
      return object : MoshiReVoman(moshiBuilder) {}
    }

    private fun buildMoshi(
      customAdapters: List<Any> = emptyList(),
      customAdaptersWithType: Map<Type, Either<JsonAdapter<out Any>, Factory>> = emptyMap(),
      typesToIgnore: Set<Class<out Any>> = emptySet(),
    ): Moshi.Builder {
      // * NOTE 08 May 2024 gopala.akshintala: This cannot be static singleton as adapters added
      // mutates
      // the singleton
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
          .addLast(EventAdapter)
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
      typesToIgnore: Set<Class<out Any>>,
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
        moshiBuilder.add(SkipTypesFactory(typesToIgnore))
      }
    }
  }
}

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class AlwaysSerializeNulls
