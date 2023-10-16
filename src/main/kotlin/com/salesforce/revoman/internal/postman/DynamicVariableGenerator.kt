/**
 * ****************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or
 * https://opensource.org/licenses/BSD-3-Clause
 * ****************************************************************************
 */
package com.salesforce.revoman.internal.postman

import io.github.serpro69.kfaker.faker
import java.time.LocalDate
import java.util.*
import kotlin.random.Random.Default.nextInt
import kotlin.random.Random.Default.nextLong

private val faker = faker {}

private val dynamicVariableKeyToGenerator: Map<String, () -> String> =
  mapOf(
    "\$randomFirstName" to faker.name::firstName,
    "\$randomLastName" to faker.name::lastName,
    "\$randomUserName" to { faker.name.firstName() + faker.name.lastName() },
    "\$randomCity" to faker.address::city,
    "\$randomWord" to faker.lorem::words,
    "\$randomCompanyName" to faker.company::name,
    "\$randomColor" to faker.color::name,
    "\$randomWord" to faker.lorem::words,
    "\$randomInt" to { nextInt(0, Int.MAX_VALUE).toString() },
    "\$randomAdjective" to faker.adjective::positive,
    "\$randomUUID" to { UUID.randomUUID().toString() },
    "\$randomEmail" to { faker.internet.email() },
    "\$currentDate" to { LocalDate.now().toString() },
    "\$randomFutureDate" to { LocalDate.now().plusDays(nextLong(1, 366)).toString() },
    "\$epoch" to { System.currentTimeMillis().toString() },
  )

internal fun dynamicVariableGenerator(key: String): String? =
  dynamicVariableKeyToGenerator[key]?.invoke()
