package org.revcloud.revoman.internal.postman

import com.squareup.moshi.Moshi
import com.squareup.moshi.adapter
import org.revcloud.revoman.internal.postman.state.Environment
import org.revcloud.revoman.internal.readTextFromFile

internal val pm = PostmanAPI()

@OptIn(ExperimentalStdlibApi::class)
internal fun initPmEnvironment(
  dynamicEnvironment: Map<String, String?>?,
  pmEnvironmentPath: String?
) {
  // ! TODO gopala.akshintala 19/05/22: Think about clashes between json environment variables and dynamic environment variables
  if (!dynamicEnvironment.isNullOrEmpty()) {
    pm.environment.putAll(dynamicEnvironment)
  }
  if (pmEnvironmentPath != null) {
    val envJsonAdapter = Moshi.Builder().build().adapter<Environment>()
    val environment: Environment? = envJsonAdapter.fromJson(readTextFromFile(pmEnvironmentPath))
    pm.environment.putAll(environment?.values?.filter { it.enabled }?.associate { it.key to it.value } ?: emptyMap())
  }
  
}
