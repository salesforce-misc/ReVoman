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
    $$"$timestamp" to { Clock.System.now().epochSeconds.toString() },
    $$"$isoTimestamp" to { Clock.System.now().toString() },
    $$"$randomUUID" to { UUID.randomUUID().toString() },
    // Text, numbers, and colors
    $$"$randomAlphaNumeric" to { randomAlphanumeric(1) },
    $$"$randomBoolean" to { nextBoolean().toString() },
    $$"$randomInt" to { nextInt(0, Int.MAX_VALUE).toString() },
    $$"$randomColor" to faker.color::name,
    $$"$randomHexColor" to { "#${getRandomHex()}${getRandomHex()}${getRandomHex()}" },
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
    // Date time
    $$"$currentDate" to { LocalDate.now().toString() },
    $$"$randomFutureDate" to
      {
        LocalDate.now().let { it.plusDays(nextLong(1, it.lengthOfYear().toLong())).toString() }
      },
  )

fun getRandomHex() = nextInt(255).toString(16).uppercase()

private val charPool = ('a'..'z') + ('A'..'Z') + ('0'..'9')

fun randomAlphanumeric(length: Int) =
  (1..length).map { charPool[nextInt(0, charPool.size)] }.joinToString("")

private val dynamicVariableGeneratorsWithPM: Map<String, (PostmanSDK) -> String> =
  mapOf($$"$currentRequestName" to { it.info.requestName })

internal fun dynamicVariableGenerator(key: String, pm: PostmanSDK): String? =
  dynamicVariableGenerators[key]?.invoke() ?: dynamicVariableGeneratorsWithPM[key]?.invoke(pm)
