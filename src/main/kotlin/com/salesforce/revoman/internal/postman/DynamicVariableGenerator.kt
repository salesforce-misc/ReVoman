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
import org.apache.commons.lang3.RandomStringUtils

private val faker = faker {}

/**
 * @see <a
 *   href="https://learning.postman.com/docs/writing-scripts/script-references/variables-list/">Postman
 *   Variables</a>
 */
private val dynamicVariableGenerators: Map<String, () -> String> =
  mapOf(
    // Common
    "\$guid" to { UUID.randomUUID().toString() },
    "\$timestamp" to { Clock.System.now().epochSeconds.toString() },
    "\$isoTimestamp" to { Clock.System.now().toString() },
    "\$randomUUID" to { UUID.randomUUID().toString() },
    // Text, numbers, and colors
    "\$randomAlphaNumeric" to { RandomStringUtils.randomAlphanumeric(1) },
    "\$randomBoolean" to { nextBoolean().toString() },
    "\$randomInt" to { nextInt(0, Int.MAX_VALUE).toString() },
    "\$randomColor" to faker.color::name,
    "\$randomHexColor" to { "#${getRandomHex()}${getRandomHex()}${getRandomHex()}" },
    // Internet and IP addresses
    "\$randomIP" to faker.internet::iPv4Address,
    "\$randomIPV6" to faker.internet::iPv6Address,
    "\$randomMACAddress" to { faker.internet.macAddress() },
    "\$randomPassword" to { RandomStringUtils.randomAlphanumeric(15) },
    // Names
    "\$randomFirstName" to faker.name::firstName,
    "\$randomLastName" to faker.name::lastName,
    "\$randomUserName" to { faker.name.firstName() + faker.name.lastName() },
    // Phone, address, and location
    "\$randomCity" to faker.address::city,
    // Grammar
    "\$randomAdjective" to faker.adjective::positive,
    // Business
    "\$randomCompanyName" to faker.company::name,
    "\$randomWord" to faker.lorem::words,
    // Domains, emails, and usernames
    "\$randomEmail" to { faker.internet.email() },
    // Date time
    "\$currentDate" to { LocalDate.now().toString() },
    "\$randomFutureDate" to
      {
        LocalDate.now().let { it.plusDays(nextLong(1, it.lengthOfYear().toLong())).toString() }
      },
  )

fun getRandomHex() = nextInt(255).toString(16).uppercase()

private val dynamicVariableGeneratorsWithPM: Map<String, (PostmanSDK) -> String> =
  mapOf("\$currentRequestName" to { it.info.requestName })

internal fun dynamicVariableGenerator(key: String, pm: PostmanSDK): String? =
  dynamicVariableGenerators[key]?.invoke() ?: dynamicVariableGeneratorsWithPM[key]?.invoke(pm)
