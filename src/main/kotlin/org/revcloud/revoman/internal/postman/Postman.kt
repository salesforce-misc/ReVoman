package org.revcloud.revoman.internal.postman

import com.squareup.moshi.Moshi
import com.squareup.moshi.adapter
import org.revcloud.revoman.internal.adapters.RegexAdapterFactory
import org.revcloud.revoman.internal.postman.state.Environment
import org.revcloud.revoman.internal.readTextFromFile

internal val pm = PostmanAPI()

internal fun initPmEnvironment(
  dynamicEnvironment: Map<String, String?>?,
  pmEnvironmentPath: String?
) {
  // ! TODO gopala.akshintala 19/05/22: Think about clashes between json environment variables and dynamic environment variables
  if (!dynamicEnvironment.isNullOrEmpty()) {
    pm.environment.putAll(dynamicEnvironment)
  }
  if (pmEnvironmentPath != null) {
    val environment: Environment? = unmarshallEnvFile(pmEnvironmentPath, pm.environment)
    pm.environment.putAll(environment?.values?.filter { it.enabled }
      ?.associate { it.key to it.value } ?: emptyMap())
  }
}

@OptIn(ExperimentalStdlibApi::class)
internal fun unmarshallEnvFile(
  pmEnvironmentPath: String,
  pmEnvironment: Map<String, String?>,
  dynamicVariableGenerator: (String) -> String? = ::dynamicVariableGenerator
): Environment? =
  Moshi.Builder().add(RegexAdapterFactory(pmEnvironment, dynamicVariableGenerator)).build().adapter<Environment>()
    .fromJson(readTextFromFile(pmEnvironmentPath))
