package org.revcloud.postman

import io.github.serpro69.kfaker.faker

object DynamicEnvironmentKeys {
  const val BEARER_TOKEN_KEY = "bearerToken"
  const val BASE_URL_KEY = "baseUrl"
}

private val faker = faker { }

private val dynamicVariableKeyToGenerator: Map<String, () -> String> = mapOf(
  "\$randomFirstName" to faker.name::firstName,
  "\$randomLastName" to faker.name::lastName,
  "\$randomUserName" to faker.name::firstName,
  "\$randomCompanyName" to faker.company::name,
  "\$randomEmail" to { faker.internet.email() }
)

internal fun dynamicVariables(key: String): String? = dynamicVariableKeyToGenerator[key]?.invoke()
