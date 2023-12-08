/**
 * ****************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or
 * https://opensource.org/licenses/BSD-3-Clause
 * ****************************************************************************
 */
package com.salesforce.revoman.internal

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

private val moshiBuilder =
  Moshi.Builder()
    .add(JsonString.Factory())
    .add(AdaptedBy.Factory())
    .add(Date::class.java, Rfc3339DateJsonAdapter())
    .addLast(EventAdapter)
    .addLast(ThrowableAdapter)
    .addLast(ListAdapter)
    .addLast(MapAdapter)
    .asConfigurable()
    .withStandardMappings()
    .done()

private lateinit var moshiReVoman: Moshi

@SuppressWarnings("kotlin:S3923")
@JvmOverloads
internal fun initMoshi(
  customAdaptersWithType: Map<Type, List<Either<JsonAdapter<Any>, Factory>>> = emptyMap(),
  customAdapters: List<Any> = emptyList(),
  typesToIgnore: Set<Class<out Any>> = emptySet()
): ConfigurableMoshi {
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
  moshiBuilder.add(CaseInsensitiveEnumAdapter.FACTORY)
  moshiReVoman = moshiBuilder.build()
  return object : ConfigurableMoshi(moshiBuilder) {}
}

// * NOTE 12/03/23 gopala.akshintala: http4k doesn't yet have this method in-built
internal fun <T : Any> ConfigurableMoshi.asA(input: String, target: Type): T =
  moshiReVoman.adapter<T>(target).fromJson(input)!!
