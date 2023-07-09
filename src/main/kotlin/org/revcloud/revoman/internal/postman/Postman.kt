package org.revcloud.revoman.internal.postman

import com.squareup.moshi.Moshi
import com.squareup.moshi.adapter
import org.revcloud.revoman.internal.adapters.RegexAdapter
import org.revcloud.revoman.internal.postman.state.Environment
import org.revcloud.revoman.internal.readTextFromFile

internal val pm = PostmanAPI()

internal fun initPmEnvironment(
  pmEnvironmentPath: String?,
  dynamicEnvironment: Map<String, String?>?,
  customDynamicVariables: Map<String, (String) -> String>
) {
  // ! TODO gopala.akshintala 19/05/22: Think about clashes between json environment variables and
  // dynamic environment variables
  if (!dynamicEnvironment.isNullOrEmpty()) {
    pm.environment.putAll(dynamicEnvironment)
  }
  if (pmEnvironmentPath != null) {
    val environment: Environment? =
      unmarshallEnvFile(pmEnvironmentPath, pm.environment, customDynamicVariables)
    pm.environment.putAll(
      environment?.values?.filter { it.enabled }?.associate { it.key to it.value } ?: emptyMap()
    )
  }
}

@OptIn(ExperimentalStdlibApi::class)
internal fun unmarshallEnvFile(
  pmEnvironmentPath: String,
  pmEnvironment: MutableMap<String, String?>,
  customDynamicVariables: Map<String, (String) -> String>,
  dynamicVariableGenerator: (String) -> String? = ::dynamicVariableGenerator
): Environment? =
  Moshi.Builder()
    .add(RegexAdapter(pmEnvironment, customDynamicVariables, dynamicVariableGenerator))
    .build()
    .adapter<Environment>()
    .fromJson(readTextFromFile(pmEnvironmentPath))
