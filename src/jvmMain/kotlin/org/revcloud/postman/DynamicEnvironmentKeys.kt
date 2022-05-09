package org.revcloud.postman

import io.github.serpro69.kfaker.faker

object DynamicEnvironmentKeys {
  const val BEARER_TOKEN = "bearerToken"
  const val BASE_URL = "baseUrl"
}

val faker = faker { }

val dynamicVariableKeyToGenerator = mapOf(
  "\$randomFirstName" to faker.name::firstName,
  "\$randomLastName" to faker.name::lastName,
  "\$randomUserName" to faker.name::neutralFirstName,
  "\$randomCompanyName" to faker.company::name,
)

fun dynamicVariables(key: String): String? = dynamicVariableKeyToGenerator[key]?.invoke()
