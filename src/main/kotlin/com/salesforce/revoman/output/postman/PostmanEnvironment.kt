/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output.postman

import com.salesforce.revoman.internal.json.MoshiReVoman
import com.salesforce.revoman.internal.json.MoshiReVoman.Companion.initMoshi
import com.salesforce.revoman.internal.postman.template.Environment.Companion.fromMap
import com.salesforce.revoman.output.report.Step
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.rawType
import io.exoquery.pprint
import io.github.oshai.kotlinlogging.KotlinLogging
import io.vavr.control.Either
import java.lang.reflect.Type
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentMap

/** This is a Wrapper on `mutableEnv` map, providing some useful utilities */
data class PostmanEnvironment<ValueT>
@JvmOverloads
constructor(
  val mutableEnv: MutableMap<String, ValueT> = mutableMapOf(),
  @get:JvmName("moshiReVoman") val moshiReVoman: MoshiReVoman = initMoshi(),
) : MutableMap<String, ValueT> by mutableEnv {

  internal lateinit var currentStep: Step

  // --- Ledger capture: per-step produced/consumed env keys. Keyed by `step.path` (String) so a
  //     reused-thread env can carry multiple steps' deltas; read out when StepReport is built.
  //     NOTE: keyed by `step.path`, NOT the whole Step — Step's data-class hashCode/equals recurse
  //     through rawPMStep (the full request body + every JS script), so a Step-keyed map hashes the
  //     entire step on every op. `path` is the step's unique identity already used everywhere else
  //     by the ledger (learnedLedger keying, `ledger.steps` lookup, iterationByPath). The same Step
  //     instance (stable `path`) is reused across iterations, so path-keying yields the IDENTICAL
  //     union accumulation as the prior Step-keying.
  //     NOTE: NOT reset between iterations — a step executing twice (control-flow loop) accumulates
  //     the UNION of all iterations' keys. Benign for ledger learning and invisible when iterations
  //     produce the same keys. ---
  private val producedKeysByStepPath: MutableMap<String, MutableSet<String>> = mutableMapOf()
  private val consumedKeysByStepPath: MutableMap<String, MutableSet<String>> = mutableMapOf()

  fun producedKeysFor(step: Step): Set<String> =
    producedKeysByStepPath[step.path]?.toSet() ?: emptySet()

  fun consumedKeysFor(step: Step): Set<String> =
    consumedKeysByStepPath[step.path]?.toSet() ?: emptySet()

  /** Record a READ of [key] during the current step (regex `{{key}}` resolved against env). */
  fun recordConsumed(key: String) {
    if (::currentStep.isInitialized) {
      consumedKeysByStepPath.getOrPut(currentStep.path) { mutableSetOf() }.add(key)
    }
  }

  @get:JvmName("immutableEnv") val immutableEnv: Map<String, ValueT> by lazy { mutableEnv.toMap() }

  @get:JvmName("postmanEnvJSONFormat")
  val postmanEnvJSONFormat: String by lazy {
    moshiReVoman.toPrettyJson(fromMap(mutableEnv, moshiReVoman))
  }

  /**
   * A point-in-time snapshot of this environment. When the backing is a
   * [PersistentBackedMutableMap] this is O(1) — the snapshot shares the current immutable
   * persistent map and later writes to this (live) env install a new map, leaving the snapshot
   * untouched. Falls back to a defensive copy for a plain map backing (e.g.
   * collectionVariables/globals or test-constructed envs), preserving the historical
   * `copy(mutableEnv = mutableEnv.toMutableMap())` semantics.
   */
  fun o1Snapshot(): PostmanEnvironment<ValueT> =
    copy(
      mutableEnv =
        (mutableEnv as? PersistentBackedMutableMap<ValueT>)?.snapshotView()
          ?: mutableEnv.toMutableMap()
    )

  fun set(key: String, value: ValueT) {
    mutableEnv[key] = value
    // `currentStep` is a lateinit set only on the step-bound `environment` instance; stores like
    // `collectionVariables` never set it. Read it null-safely so the ledger capture AND the log
    // line
    // both no-op on an unstepped instance — interpolating an uninitialized lateinit would throw.
    val step: Step? = if (::currentStep.isInitialized) currentStep else null
    if (step != null) producedKeysByStepPath.getOrPut(step.path) { mutableSetOf() }.add(key)
    logger.info {
      "pm environment variable set in Step: $step - key: $key, value: ${pprint(value)}"
    }
  }

  @Suppress("unused")
  fun unset(key: String) {
    mutableEnv.remove(key)
    val step: Step? = if (::currentStep.isInitialized) currentStep else null
    if (step != null) producedKeysByStepPath[step.path]?.remove(key)
    logger.info { "pm environment variable unset through JS in Step: $step - key: $key" }
  }

  // ! TODO 13/09/23 gopala.akshintala: Refactor code to remove duplication

  fun <T> mutableEnvCopyWithValuesOfType(type: Class<T>): PostmanEnvironment<T> =
    PostmanEnvironment(
      mutableEnv
        .filter { type.isInstance(it.value) }
        .mapValues { type.cast(it.value) }
        .toMutableMap(),
      moshiReVoman,
    )

  inline fun <reified T> mutableEnvCopyWithValuesOfType(): PostmanEnvironment<T> =
    mutableEnvCopyWithValuesOfType(T::class.java)

  fun <T> mutableEnvCopyWithKeysStartingWith(
    type: Class<T>,
    vararg prefixes: String,
  ): PostmanEnvironment<T> =
    PostmanEnvironment(
      mutableEnv
        .filter {
          type.isInstance(it.value) && prefixes.any { prefix -> it.key.startsWith(prefix) }
        }
        .mapValues { type.cast(it.value) }
        .toMutableMap(),
      moshiReVoman,
    )

  inline fun <reified T> mutableEnvCopyWithKeysStartingWith(
    vararg prefixes: String
  ): PostmanEnvironment<T> = mutableEnvCopyWithKeysStartingWith(T::class.java, *prefixes)

  fun <T> mutableEnvCopyExcludingKeys(
    type: Class<T>,
    whiteListKeys: Set<String>,
  ): PostmanEnvironment<T> =
    PostmanEnvironment(
      mutableEnv
        .filter { type.isInstance(it.value) && !whiteListKeys.contains(it.key) }
        .mapValues { type.cast(it.value) }
        .toMutableMap(),
      moshiReVoman,
    )

  fun <T> mutableEnvCopyWithKeysNotStartingWith(
    type: Class<T>,
    vararg prefixes: String,
  ): PostmanEnvironment<T> =
    PostmanEnvironment(
      mutableEnv
        .filter {
          type.isInstance(it.value) && prefixes.all { suffix -> !it.key.startsWith(suffix) }
        }
        .mapValues { type.cast(it.value) }
        .toMutableMap(),
      moshiReVoman,
    )

  fun getAsString(key: String) = moshiReVoman.anyToString(mutableEnv[key])

  fun getInt(key: String) = mutableEnv[key] as? Int

  @JvmOverloads
  fun <PojoT : Any> getTypedObj(
    key: String,
    targetType: Type,
    customAdapters: List<Any> = emptyList(),
    customAdaptersWithType: Map<Type, Either<JsonAdapter<out Any>, JsonAdapter.Factory>> =
      emptyMap(),
    typesToIgnore: Set<Type> = emptySet(),
  ): PojoT? {
    val value = mutableEnv[key]
    return when {
      value == null -> null
      targetType.rawType.isPrimitive && targetType.rawType.isInstance(value) -> value
      targetType == String::class.java -> value
      else -> {
        moshiReVoman.addAdapters(customAdapters, customAdaptersWithType, typesToIgnore)
        when (value) {
          is String -> moshiReVoman.fromJson(value, targetType)
          else -> moshiReVoman.objToJsonStrToObj(value, targetType)
        }
      }
    }
      as PojoT?
  }

  @JvmOverloads
  inline fun <reified PojoT : Any> getObj(
    key: String,
    customAdapters: List<Any> = emptyList(),
    customAdaptersWithType: Map<Type, Either<JsonAdapter<out Any>, JsonAdapter.Factory>> =
      emptyMap(),
    typesToIgnore: Set<Type> = emptySet(),
  ): PojoT? {
    val value = mutableEnv[key]
    return when {
      value == null -> null
      value is PojoT -> value
      PojoT::class == String::class -> value as PojoT
      else -> {
        moshiReVoman.addAdapters(customAdapters, customAdaptersWithType, typesToIgnore)
        when (value) {
          is String -> moshiReVoman.fromJson(value)
          else -> moshiReVoman.objToJsonStrToObj(value)
        }
      }
    }
  }

  fun <T> mutableEnvCopyWithKeysEndingWith(
    type: Class<T>,
    vararg suffixes: String,
  ): PostmanEnvironment<T> =
    PostmanEnvironment(
      mutableEnv
        .filter { type.isInstance(it.value) && suffixes.any { suffix -> it.key.endsWith(suffix) } }
        .mapValues { type.cast(it.value) }
        .toMutableMap(),
      moshiReVoman,
    )

  fun <T> mutableEnvCopyWithKeysNotEndingWith(
    type: Class<T>,
    vararg suffixes: String,
  ): PostmanEnvironment<T> =
    PostmanEnvironment(
      mutableEnv
        .filter { type.isInstance(it.value) && suffixes.all { suffix -> !it.key.endsWith(suffix) } }
        .mapValues { type.cast(it.value) }
        .toMutableMap(),
      moshiReVoman,
    )

  /**
   * Copy of [mutableEnv] retaining entries whose (typed) value is a [type] instance AND whose key
   * matches ANY of [keyPatterns]. Patterns are compiled ONCE per call and matched via
   * [Regex.containsMatchIn] (partial): a bare `"saId"` matches anywhere in the key, so anchor with
   * `^`/`$` for prefix/suffix/exact matching. An empty [keyPatterns] retains nothing.
   */
  fun <T> mutableEnvCopyWithKeysMatching(
    type: Class<T>,
    vararg keyPatterns: String,
  ): PostmanEnvironment<T> {
    val regexes: List<Regex> = keyPatterns.map(::Regex)
    return PostmanEnvironment(
      mutableEnv
        .filter {
          type.isInstance(it.value) && regexes.any { regex -> regex.containsMatchIn(it.key) }
        }
        .mapValues { type.cast(it.value) }
        .toMutableMap(),
      moshiReVoman,
    )
  }

  /**
   * Copy of [mutableEnv] retaining entries whose (typed) value is a [type] instance AND whose key
   * matches NONE of [keyPatterns]. Patterns are compiled ONCE per call and matched via
   * [Regex.containsMatchIn] (partial). An empty [keyPatterns] retains all [type]-typed entries.
   */
  fun <T> mutableEnvCopyWithKeysNotMatching(
    type: Class<T>,
    vararg keyPatterns: String,
  ): PostmanEnvironment<T> {
    val regexes: List<Regex> = keyPatterns.map(::Regex)
    return PostmanEnvironment(
      mutableEnv
        .filter {
          type.isInstance(it.value) && regexes.none { regex -> regex.containsMatchIn(it.key) }
        }
        .mapValues { type.cast(it.value) }
        .toMutableMap(),
      moshiReVoman,
    )
  }

  fun <T> valuesForKeysStartingWith(type: Class<T>, prefix: String): Set<T> =
    valuesForKeysStartingWith(type, *arrayOf(prefix))

  fun <T> valuesForKeysStartingWith(type: Class<T>, vararg prefixes: String): Set<T> =
    mutableEnv.entries
      .asSequence()
      .filter { type.isInstance(it.value) && prefixes.any { suffix -> it.key.startsWith(suffix) } }
      .mapNotNull { type.cast(it.value) }
      .toSet()

  fun <T> valuesForKeysNotStartingWith(type: Class<T>, vararg prefixes: String): Set<T?> =
    mutableEnv.entries
      .asSequence()
      .filter { type.isInstance(it.value) && prefixes.all { suffix -> !it.key.startsWith(suffix) } }
      .mapNotNull { type.cast(it.value) }
      .toSet()

  fun <T> valuesForKeysEndingWith(type: Class<T>, suffix: String): Set<T?> =
    mutableEnv.entries
      .asSequence()
      .filter { type.isInstance(it.value) && it.key.endsWith(suffix) }
      .mapNotNull { type.cast(it.value) }
      .toSet()

  fun <T> valuesForKeysEndingWith(type: Class<T>, vararg suffixes: String): Set<T?> =
    mutableEnv.entries
      .asSequence()
      .filter { type.isInstance(it.value) && suffixes.any { suffix -> it.key.endsWith(suffix) } }
      .mapNotNull { type.cast(it.value) }
      .toSet()

  fun <T> valuesForKeysNotEndingWith(type: Class<T>, vararg suffixes: String): Set<T?> =
    mutableEnv.entries
      .asSequence()
      .filter { type.isInstance(it.value) && suffixes.all { suffix -> !it.key.endsWith(suffix) } }
      .mapNotNull { type.cast(it.value) }
      .toSet()

  /**
   * Values of entries whose (typed) value is a [type] instance AND whose key matches ANY of
   * [keyPatterns]. Patterns are compiled ONCE per call and matched via [Regex.containsMatchIn]
   * (partial): anchor with `^`/`$` for prefix/suffix/exact matching. An empty [keyPatterns] yields
   * an empty set.
   */
  fun <T> valuesForKeysMatching(type: Class<T>, vararg keyPatterns: String): Set<T> {
    val regexes: List<Regex> = keyPatterns.map(::Regex)
    return mutableEnv.entries
      .asSequence()
      .filter {
        type.isInstance(it.value) && regexes.any { regex -> regex.containsMatchIn(it.key) }
      }
      .mapNotNull { type.cast(it.value) }
      .toSet()
  }

  /**
   * Values of entries whose (typed) value is a [type] instance AND whose key matches NONE of
   * [keyPatterns]. Patterns are compiled ONCE per call and matched via [Regex.containsMatchIn]
   * (partial). An empty [keyPatterns] yields all [type]-typed values.
   */
  fun <T> valuesForKeysNotMatching(type: Class<T>, vararg keyPatterns: String): Set<T> {
    val regexes: List<Regex> = keyPatterns.map(::Regex)
    return mutableEnv.entries
      .asSequence()
      .filter {
        type.isInstance(it.value) && regexes.none { regex -> regex.containsMatchIn(it.key) }
      }
      .mapNotNull { type.cast(it.value) }
      .toSet()
  }
}

private val logger = KotlinLogging.logger {}

/**
 * A [MutableMap] backed by an immutable [PersistentMap] that is SWAPPED on every write. Because the
 * backing is immutable, [snapshotView] captures the current reference in O(1) and later writes to
 * this instance (which install a NEW persistent map) never mutate that captured view — the
 * point-in-time invariant [com.salesforce.revoman.output.report.StepReport] relies on.
 * Reads/keys/entries/values are non-copying read-through views over the current backing (mutators
 * on those views throw; nothing in ReVoman mutates through them), so the per-step
 * `pm.environment.keys` access stays O(1).
 */
// The function count comes from implementing the full MutableMap surface (get/put/remove/putAll/
// clear/containsKey/containsValue/isEmpty) plus snapshotView + the equals/hashCode/toString Map
// contract — an interface obligation, not decomposable, so TooManyFunctions is suppressed here.
@Suppress("TooManyFunctions")
internal class PersistentBackedMutableMap<V>
private constructor(private var current: PersistentMap<String, V>) : MutableMap<String, V> {

  constructor() : this(persistentMapOf())

  constructor(seed: Map<String, V>) : this(seed.toPersistentMap())

  /** O(1): a NEW instance sharing this instance's current immutable backing. */
  fun snapshotView(): PersistentBackedMutableMap<V> = PersistentBackedMutableMap(current)

  override val size: Int
    get() = current.size

  override fun isEmpty(): Boolean = current.isEmpty()

  override fun containsKey(key: String): Boolean = current.containsKey(key)

  override fun containsValue(value: V): Boolean = current.containsValue(value)

  override fun get(key: String): V? = current[key]

  override fun put(key: String, value: V): V? {
    val prev = current[key]
    current = current.put(key, value)
    return prev
  }

  override fun remove(key: String): V? {
    val prev = current[key]
    current = current.remove(key)
    return prev
  }

  override fun putAll(from: Map<out String, V>) {
    current = current.putAll(from)
  }

  override fun clear() {
    current = persistentMapOf()
  }

  // Read-through views over the current backing. kotlinx immutable views are Set/Collection; wrap
  // them so the MutableMap type is satisfied. Mutators throw — no ReVoman code path mutates
  // through env.keys/entries/values (grep-verified); the swap-on-write API above is the only
  // supported mutation surface.
  override val keys: MutableSet<String>
    get() =
      object : AbstractMutableSet<String>() {
        override val size: Int
          get() = current.size

        // Delegate membership to the backing's O(1) containsKey. Without this, AbstractCollection's
        // default contains/containsAll do a linear iterator scan — and the ledger warm path calls
        // `pm.environment.keys.containsAll(produces)` per step (ReVoman.kt), which would be O(E)
        // per
        // key (the O(M*E) the LinkedHashMap keySet avoided and E2 must preserve).
        override fun contains(element: String): Boolean = current.containsKey(element)

        override fun iterator(): MutableIterator<String> =
          current.keys.iterator().asReadOnlyMutable()

        override fun add(element: String): Boolean = throw UnsupportedOperationException()
      }

  override val values: MutableCollection<V>
    get() =
      object : AbstractMutableCollection<V>() {
        override val size: Int
          get() = current.size

        override fun contains(element: V): Boolean = current.containsValue(element)

        override fun iterator(): MutableIterator<V> = current.values.iterator().asReadOnlyMutable()

        override fun add(element: V): Boolean = throw UnsupportedOperationException()
      }

  override val entries: MutableSet<MutableMap.MutableEntry<String, V>>
    get() =
      object : AbstractMutableSet<MutableMap.MutableEntry<String, V>>() {
        override val size: Int
          get() = current.size

        // Non-copying: lazily wrap each read-only kotlinx `Map.Entry` in a read-only
        // `MutableEntry` (setValue throws) as iteration advances — kotlinx `MapEntry` does
        // NOT implement `MutableMap.MutableEntry`, so an unchecked cast would throw
        // ClassCastException at runtime. No eager collection is materialized.
        override fun iterator(): MutableIterator<MutableMap.MutableEntry<String, V>> =
          current.entries
            .asSequence()
            .map { it.asReadOnlyMutableEntry() }
            .iterator()
            .asReadOnlyMutable()

        override fun add(element: MutableMap.MutableEntry<String, V>): Boolean =
          throw UnsupportedOperationException()
      }

  override fun equals(other: Any?): Boolean = current == other

  override fun hashCode(): Int = current.hashCode()

  override fun toString(): String = current.toString()
}

private fun <T> Iterator<T>.asReadOnlyMutable(): MutableIterator<T> =
  object : MutableIterator<T> {
    override fun hasNext(): Boolean = this@asReadOnlyMutable.hasNext()

    override fun next(): T = this@asReadOnlyMutable.next()

    override fun remove(): Unit = throw UnsupportedOperationException()
  }

private fun <K, V> Map.Entry<K, V>.asReadOnlyMutableEntry(): MutableMap.MutableEntry<K, V> =
  object : MutableMap.MutableEntry<K, V> {
    override val key: K
      get() = this@asReadOnlyMutableEntry.key

    override val value: V
      get() = this@asReadOnlyMutableEntry.value

    override fun setValue(newValue: V): V = throw UnsupportedOperationException()

    override fun equals(other: Any?): Boolean =
      other is Map.Entry<*, *> && other.key == key && other.value == value

    override fun hashCode(): Int = (key?.hashCode() ?: 0) xor (value?.hashCode() ?: 0)

    override fun toString(): String = "$key=$value"
  }
