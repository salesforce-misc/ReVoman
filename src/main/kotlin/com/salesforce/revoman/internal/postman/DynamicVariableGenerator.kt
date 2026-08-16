/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman

import com.salesforce.revoman.internal.runtime.LegacyRundownProgress
import java.time.LocalDate
import java.util.*
import kotlin.random.Random
import kotlin.random.Random.Default.nextBoolean
import kotlin.random.Random.Default.nextInt
import kotlin.random.Random.Default.nextLong
import kotlin.time.Clock.System
import net.datafaker.Faker

private val faker = Faker()

/**
 * @see <a
 *   href="https://learning.postman.com/docs/writing-scripts/script-references/variables-list/">Postman
 *   Variables</a>
 *
 *   This may not be an exhaustive list of all dynamic variables supported by Postman. We keep
 *   adding on the need-basis so it will grow over time. If what is need is not present here, You
 *   may either contribute or use @see <a
 *   href="https://github.com/salesforce-misc/ReVoman#custom-dynamic-variables/">Custom Dynamic
 *   Variables</a>
 */
private val dynamicVariableGenerators: Map<String, () -> String> =
  mapOf(
    // Common
    $$"$guid" to { UUID.randomUUID().toString() },
    $$"$timestamp" to { System.now().epochSeconds.toString() },
    $$"$isoTimestamp" to { System.now().toString() },
    $$"$randomUUID" to { UUID.randomUUID().toString() },
    // Text, numbers, and colors
    $$"$randomAlphaNumeric" to { randomAlphanumeric(1) },
    $$"$randomBoolean" to { nextBoolean().toString() },
    $$"$randomInt" to { nextInt(0, Int.MAX_VALUE).toString() },
    $$"$randomColor" to { faker.color().name() },
    $$"$randomHexColor" to { "#${getRandomHex()}${getRandomHex()}${getRandomHex()}" },
    // Internet and IP addresses
    $$"$randomIP" to { faker.internet().ipV4Address() },
    $$"$randomIPV6" to { faker.internet().ipV6Address() },
    $$"$randomMACAddress" to { faker.internet().macAddress() },
    $$"$randomPassword" to { randomAlphanumeric(15) },
    // Names
    $$"$randomFirstName" to { faker.name().firstName() },
    $$"$randomLastName" to { faker.name().lastName() },
    $$"$randomUserName" to { faker.name().firstName() + faker.name().lastName() },
    // Phone, address, and location
    $$"$randomCity" to { faker.address().city() },
    // Domains, emails, and usernames
    $$"$randomEmail" to { faker.internet().emailAddress() },
    // Date time
    $$"$currentDate" to { LocalDate.now().toString() },
    $$"$randomFutureDate" to
      {
        LocalDate.now().let { it.plusDays(nextLong(1, it.lengthOfYear().toLong())).toString() }
      },
  )

private val upperHexFormat = HexFormat.of().withUpperCase()

// `random` defaults to the global source (production behaviour unchanged); tests inject a seeded
// Random to make coverage of the full 00..FF byte range deterministic instead of probabilistic.
fun getRandomHex(random: Random = Random.Default): String =
  upperHexFormat.toHexDigits(random.nextInt(256).toByte())

private val charPool = ('a'..'z') + ('A'..'Z') + ('0'..'9')

fun randomAlphanumeric(length: Int): String =
  CharArray(length) { charPool[nextInt(0, charPool.size)] }.concatToString()

private val dynamicVariableGeneratorsWithProgress: Map<String, (LegacyRundownProgress) -> String> =
  mapOf($$"$currentRequestName" to { it.currentRequestName })

@JvmSynthetic
internal fun dynamicVariableGenerator(key: String, progress: LegacyRundownProgress): String? =
  dynamicVariableGenerators[key]?.invoke()
    ?: dynamicVariableGeneratorsWithProgress[key]?.invoke(progress)
