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
    "\$guid" to { UUID.randomUUID().toString() },
    "\$randomUUID" to { UUID.randomUUID().toString() },
    "\$randomEmail" to { faker.internet.email() },
    "\$currentDate" to { LocalDate.now().toString() },
    "\$randomFutureDate" to
      {
        LocalDate.now().let { it.plusDays(nextLong(1, it.lengthOfYear().toLong())).toString() }
      },
    "\$timestamp" to { System.currentTimeMillis().toString() },
    "\$currentRequestName" to { pm.info.requestName }
  )

internal fun dynamicVariableGenerator(key: String): String? =
  dynamicVariableKeyToGenerator[key]?.invoke()
