/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman

import io.github.serpro69.kfaker.faker
import kotlinx.datetime.Clock
import org.apache.commons.lang3.RandomStringUtils
import java.time.LocalDate
import java.util.*
import kotlin.random.Random.Default.nextBoolean
import kotlin.random.Random.Default.nextInt
import kotlin.random.Random.Default.nextLong

private val faker = faker {}

/**
 * https://learning.postman.com/docs/writing-scripts/script-references/variables-list/
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
    // Names
    "\$randomFirstName" to faker.name::firstName,
    "\$randomLastName" to faker.name::lastName,
    "\$randomUserName" to { faker.name.firstName() + faker.name.lastName() },
    // Phone, address, and location
    "\$randomCity" to faker.address::city,
    // Grammar
    "\$randomWord" to faker.lorem::words,
    "\$randomCompanyName" to faker.company::name,
    "\$randomWord" to faker.lorem::words,
    "\$randomAdjective" to faker.adjective::positive,
    "\$randomEmail" to { faker.internet.email() },
    "\$currentDate" to { LocalDate.now().toString() },
    "\$randomFutureDate" to
      {
        LocalDate.now().let { it.plusDays(nextLong(1, it.lengthOfYear().toLong())).toString() }
      },
  )

private val dynamicVariableGeneratorsWithPM: Map<String, (PostmanSDK) -> String> =
  mapOf("\$currentRequestName" to { it.info.requestName })

internal fun dynamicVariableGenerator(key: String, pm: PostmanSDK): String? =
  dynamicVariableGenerators[key]?.invoke() ?: dynamicVariableGeneratorsWithPM[key]?.invoke(pm)
