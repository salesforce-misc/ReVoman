/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman

import io.github.serpro69.kfaker.faker
import java.time.LocalDate
import java.util.*
import kotlin.random.Random.Default.nextBoolean
import kotlin.random.Random.Default.nextDouble
import kotlin.random.Random.Default.nextInt
import kotlin.random.Random.Default.nextLong
import kotlinx.datetime.Clock

private val faker = faker {}

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
    $$"$uuid" to { UUID.randomUUID().toString() },
    $$"$random.uuid" to { UUID.randomUUID().toString() },
    $$"$timestamp" to { Clock.System.now().epochSeconds.toString() },
    $$"$isoTimestamp" to { Clock.System.now().toString() },
    $$"$randomUUID" to { UUID.randomUUID().toString() },
    // Text, numbers, and colors
    $$"$randomAlphaNumeric" to { randomAlphanumeric(1) },
    $$"$random.alphanumeric" to { randomAlphanumeric(1) },
    $$"$random.alphabetic" to { randomAlphabetic(1) },
    $$"$randomBoolean" to { nextBoolean().toString() },
    $$"$random.bool" to { nextBoolean().toString() },
    $$"$randomInt" to { nextInt(0, Int.MAX_VALUE).toString() },
    $$"$randomColor" to faker.color::name,
    $$"$randomHexColor" to { "#${getRandomHex()}${getRandomHex()}${getRandomHex()}" },
    $$"$random.hexadecimal" to { randomHexadecimal(1) },
    // Internet and IP addresses
    $$"$randomIP" to faker.internet::iPv4Address,
    $$"$randomIPV6" to faker.internet::iPv6Address,
    $$"$randomMACAddress" to { faker.internet.macAddress() },
    $$"$randomPassword" to { randomAlphanumeric(15) },
    // Names
    $$"$randomFirstName" to faker.name::firstName,
    $$"$randomLastName" to faker.name::lastName,
    $$"$randomUserName" to { faker.name.firstName() + faker.name.lastName() },
    // Phone, address, and location
    $$"$randomCity" to faker.address::city,
    // Grammar
    $$"$randomAdjective" to faker.adjective::positive,
    $$"$randomWord" to faker.lorem::words,
    // Business
    $$"$randomCompanyName" to faker.company::name,
    $$"$randomProduct" to faker.industrySegments::sector,
    // Domains, emails, and usernames
    $$"$randomEmail" to { faker.internet.email() },
    $$"$random.email" to { faker.internet.email() },
    // Date time
    $$"$currentDate" to { LocalDate.now().toString() },
    $$"$randomFutureDate" to
      {
        LocalDate.now().let { it.plusDays(nextLong(1, it.lengthOfYear().toLong())).toString() }
      },
  )

fun getRandomHex() = nextInt(255).toString(16).uppercase()

private val charPool = ('a'..'z') + ('A'..'Z') + ('0'..'9')
private val alphaPool = ('a'..'z') + ('A'..'Z')

fun randomAlphanumeric(length: Int) =
  (1..length).map { charPool[nextInt(0, charPool.size)] }.joinToString("")

fun randomAlphabetic(length: Int) =
  (1..length).map { alphaPool[nextInt(0, alphaPool.size)] }.joinToString("")

fun randomHexadecimal(length: Int) =
  (1..length).map { nextInt(0, 16).toString(16) }.joinToString("")

private val dynamicVariableGeneratorsWithPM: Map<String, (PostmanSDK) -> String> =
  mapOf($$"$currentRequestName" to { it.info.requestName })

private val randomIntegerPattern =
  Regex("""^\${'$'}random\.integer\(\s*(-?\d+)\s*,\s*(-?\d+)\s*\)$""")
private val randomFloatPattern =
  Regex("""^\${'$'}random\.float\(\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)\s*\)$""")
private val randomAlphaPattern =
  Regex("""^\${'$'}random\.alphabetic\(\s*(\d+)\s*\)$""")
private val randomAlnumPattern =
  Regex("""^\${'$'}random\.alphanumeric\(\s*(\d+)\s*\)$""")
private val randomHexPattern =
  Regex("""^\${'$'}random\.hexadecimal\(\s*(\d+)\s*\)$""")

internal fun dynamicVariableGenerator(key: String, pm: PostmanSDK): String? {
  if (key.startsWith($$"$env.")) {
    return System.getenv(key.removePrefix($$"$env."))
  }
  randomIntegerPattern.matchEntire(key)?.let { match ->
    val from = match.groupValues[1].toInt()
    val to = match.groupValues[2].toInt()
    if (from >= to) return from.toString()
    return nextInt(from, to).toString()
  }
  randomFloatPattern.matchEntire(key)?.let { match ->
    val from = match.groupValues[1].toDouble()
    val to = match.groupValues[2].toDouble()
    if (from >= to) return from.toString()
    return nextDouble(from, to).toString()
  }
  randomAlphaPattern.matchEntire(key)?.let { match ->
    val length = match.groupValues[1].toInt()
    return randomAlphabetic(length.coerceAtLeast(1))
  }
  randomAlnumPattern.matchEntire(key)?.let { match ->
    val length = match.groupValues[1].toInt()
    return randomAlphanumeric(length.coerceAtLeast(1))
  }
  randomHexPattern.matchEntire(key)?.let { match ->
    val length = match.groupValues[1].toInt()
    return randomHexadecimal(length.coerceAtLeast(1))
  }
  return dynamicVariableGenerators[key]?.invoke() ?: dynamicVariableGeneratorsWithPM[key]?.invoke(pm)
}
