package org.revcloud.output

data class Pokemon(
  @JvmField
  val itemNameToResponseWithType: Map<String, Pair<Any, Class<out Any>>>,
  @JvmField
  val environment: Map<String, String?>
)
