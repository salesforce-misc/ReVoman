/**
 * ****************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or
 * https://opensource.org/licenses/BSD-3-Clause
 * ****************************************************************************
 */
package com.salesforce.revoman.internal

import com.salesforce.revoman.internal.adapters.BigDecimalAdapter
import com.salesforce.revoman.internal.adapters.EpochAdapter
import com.salesforce.revoman.internal.adapters.UUIDAdapter
import com.salesforce.revoman.internal.factories.CaseInsensitiveEnumAdapter
import com.salesforce.revoman.internal.factories.IgnoreUnknownFactory
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonAdapter.Factory
import com.squareup.moshi.Moshi
import com.squareup.moshi.adapters.Rfc3339DateJsonAdapter
import dev.zacsweers.moshix.adapters.AdaptedBy
import dev.zacsweers.moshix.adapters.JsonString
import io.vavr.control.Either
import java.lang.reflect.Type
import java.util.Date
import org.http4k.format.ConfigurableMoshi
import org.http4k.format.EventAdapter
import org.http4k.format.ListAdapter
import org.http4k.format.MapAdapter
import org.http4k.format.ThrowableAdapter
import org.http4k.format.asConfigurable
import org.http4k.format.withStandardMappings

internal val moshiBuilder: Moshi.Builder =
  Moshi.Builder()
    .add(JsonString.Factory())
    .add(AdaptedBy.Factory())
    .add(BigDecimalAdapter)
    .add(UUIDAdapter)
    .add(EpochAdapter)
    .add(Date::class.java, Rfc3339DateJsonAdapter())
    .addLast(CaseInsensitiveEnumAdapter.FACTORY)
    .addLast(EventAdapter)
    .addLast(ThrowableAdapter)
    .addLast(ListAdapter)
    .addLast(MapAdapter)
    .asConfigurable()
    .withStandardMappings()
    .done()

private lateinit var moshiReVoman: Moshi

@JvmOverloads
internal fun initMoshi(
  customAdapters: List<Any> = emptyList(),
  customAdaptersWithType: Map<Type, List<Either<JsonAdapter<Any>, Factory>>> = emptyMap(),
  typesToIgnore: Set<Class<out Any>> = emptySet()
): ConfigurableMoshi {
  buildMoshi(customAdapters, customAdaptersWithType, typesToIgnore)
  moshiReVoman = moshiBuilder.build()
  return object : ConfigurableMoshi(moshiBuilder) {}
}

@SuppressWarnings("kotlin:S3923")
internal fun buildMoshi(
  customAdapters: List<Any> = emptyList(),
  customAdaptersWithType: Map<Type, List<Either<JsonAdapter<Any>, Factory>>> = emptyMap(),
  typesToIgnore: Set<Class<out Any>> = emptySet()
) {
  customAdaptersWithType.forEach { (type, customAdapters) ->
    customAdapters.forEach { customAdapter ->
      customAdapter.fold({ moshiBuilder.add(type, it) }, { moshiBuilder.add(it) })
    }
  }
  for (adapter in customAdapters) {
    if (adapter is Factory) {
      moshiBuilder.add(adapter)
    } else {
      moshiBuilder.add(adapter)
    }
  }
  if (typesToIgnore.isNotEmpty()) {
    moshiBuilder.add(IgnoreUnknownFactory(typesToIgnore))
  }
}

// * NOTE 12/03/23 gopala.akshintala: http4k doesn't yet have this method in-built
internal fun <T : Any> ConfigurableMoshi.asA(input: String, target: Type): T =
  moshiReVoman.adapter<T>(target).fromJson(input)!!
