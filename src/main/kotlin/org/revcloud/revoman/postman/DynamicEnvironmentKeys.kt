package org.revcloud.revoman.postman

import io.github.serpro69.kfaker.faker
import java.time.LocalDate
import java.util.Random

object DynamicEnvironmentKeys {
  const val BEARER_TOKEN_KEY = "bearerToken"
  const val BASE_URL_KEY = "baseUrl"
}

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
)

internal fun dynamicVariables(key: String): String? = dynamicVariableKeyToGenerator[key]?.invoke()
