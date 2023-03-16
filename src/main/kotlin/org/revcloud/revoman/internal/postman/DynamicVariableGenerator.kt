package org.revcloud.revoman.internal.postman

import io.github.serpro69.kfaker.faker
import java.time.LocalDate
import java.util.Random

private val faker = faker { }

private val random = Random()

private val dynamicVariableKeyToGenerator: Map<String, () -> String> = mapOf(
  "\$randomFirstName" to faker.name::firstName,
  "\$randomLastName" to faker.name::lastName,
  "\$randomUserName" to { faker.name.firstName() + faker.name.lastName() },
  "\$randomCompanyName" to faker.company::name,
  "\$randomEmail" to { faker.internet.email() },
  "\$currentDate" to { LocalDate.now().toString() },
  "\$randomFutureDate" to { LocalDate.now().plusDays(random.longs(1, 365).findFirst().orElse(1)).toString() },
  "\$epoch" to { System.currentTimeMillis().toString() },
)

internal fun dynamicVariableGenerator(key: String): String? = dynamicVariableKeyToGenerator[key]?.invoke()
