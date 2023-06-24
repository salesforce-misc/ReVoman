package org.revcloud.revoman.internal

import com.squareup.moshi.Moshi
import com.squareup.moshi.adapters.Rfc3339DateJsonAdapter
import dev.zacsweers.moshix.adapters.AdaptedBy
import dev.zacsweers.moshix.adapters.JsonString
import org.http4k.format.ConfigurableMoshi
import org.http4k.format.EventAdapter
import org.http4k.format.ListAdapter
import org.http4k.format.MapAdapter
import org.http4k.format.ThrowableAdapter
import org.http4k.format.asConfigurable
import org.http4k.format.withStandardMappings
import org.revcloud.revoman.internal.adapters.IgnoreUnknownFactory
import java.lang.reflect.Type
import java.util.Date

private val moshiBuilder = Moshi.Builder()
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

@JvmOverloads
internal fun initMoshi(
  customAdaptersForResponse: List<Any>? = emptyList(),
  typesToIgnore: Set<Class<out Any>>? = emptySet()
): ConfigurableMoshi {
  customAdaptersForResponse?.forEach { moshiBuilder.add(it) }
  if (!typesToIgnore.isNullOrEmpty()) {
    moshiBuilder.add(IgnoreUnknownFactory(typesToIgnore))
  }
  moshiReVoman = moshiBuilder.build()
  return object: ConfigurableMoshi(moshiBuilder) {}
}

// * NOTE 12/03/23 gopala.akshintala: http4k doesn't yet have this method in-built 
internal fun <T : Any> ConfigurableMoshi.asA(input: String, target: Type): T = moshiReVoman.adapter<T>(target).fromJson(input)!!
