/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package performance.capture

import performance.json.CanonicalJson
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode

/** Removes host- and secret-bearing text without altering benchmark observation semantics. */
class PrivacyFilter {
  fun sanitize(value: String): String {
    var safe = value
    safe = COMMAND.replace(safe) { match -> "${match.groupValues[1]}[redacted-command]" }
    safe = SENSITIVE_ENVIRONMENT.replace(safe, "[redacted-environment]")
    safe = SECRET.replace(safe, "[redacted-secret]")
    safe = USER.replace(safe) { match -> "${match.groupValues[1]}[redacted-user]" }
    safe = HOST.replace(safe) { match -> "${match.groupValues[1]}[redacted-host]" }
    safe = IPV4.replace(safe, "[redacted-ip]")
    safe = IPV6.replace(safe, "[redacted-ip]")
    safe = PATH.replace(safe, "[redacted-path]")
    safe = WINDOWS_PATH.replace(safe, "[redacted-path]")
    return safe
  }

  fun sanitizeJson(bytes: ByteArray): ByteArray =
    CanonicalJson.encode(sanitizeNode(CanonicalJson.parseStrict(bytes), null))

  private fun sanitizeNode(node: JsonNode, fieldName: String?): JsonNode =
    when {
      node.isObject ->
        JsonNodeFactory.instance.objectNode().also { target ->
          node.asObject().properties().forEach { (name, value) ->
            target.set(name, sanitizeNode(value, name))
          }
        }
      node.isArray ->
        JsonNodeFactory.instance.arrayNode().also { target ->
          node.asArray().forEach { value -> target.add(sanitizeNode(value, fieldName)) }
        }
      node.isTextual && fieldName in PROTECTED_SEMANTIC_FIELDS -> node
      node.isTextual -> JsonNodeFactory.instance.textNode(sanitizeForField(fieldName, node.asString()))
      else -> node
    }

  private fun sanitizeForField(fieldName: String?, value: String): String =
    when {
      fieldName?.contains("command", ignoreCase = true) == true -> "[redacted-command]"
      fieldName?.contains("hostname", ignoreCase = true) == true || fieldName == "host" ->
        "[redacted-host]"
      fieldName?.contains("username", ignoreCase = true) == true || fieldName == "user" ->
        "[redacted-user]"
      fieldName?.contains("path", ignoreCase = true) == true -> "[redacted-path]"
      fieldName?.contains("environment", ignoreCase = true) == true -> "[redacted-environment]"
      else -> sanitize(value)
    }

  private companion object {
    val COMMAND =
      Regex(
        "(?im)(\\b(?:command|cmd|exec|executing|argv)\\s*[=:]\\s*)(?:/[^\\r\\n]+|[^\\r\\n]+)",
      )
    val SENSITIVE_ENVIRONMENT =
      Regex(
        "(?m)\\b[A-Z][A-Z0-9_]{1,63}=[^\\s]+",
      )
    val SECRET =
      Regex(
        "(?i)(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|(?:AKIA|ASIA)[0-9A-Z]{16}|bearer\\s+[A-Za-z0-9._-]{12,})",
      )
    val USER = Regex("(?i)(\\buser(?:name)?\\s*[=:]\\s*)[A-Za-z0-9._-]+")
    val HOST =
      Regex("(?i)(\\bhost(?:name)?\\s*[=:]\\s*)[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)+")
    val IPV4 = Regex("(?<![A-Za-z0-9])(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?![A-Za-z0-9])")
    val IPV6 =
      Regex("(?i)(?<![A-Za-z0-9])(?:[0-9a-f]{1,4}:){2,7}[0-9a-f]{0,4}(?![A-Za-z0-9])")
    val PATH =
      Regex("(?<![A-Za-z0-9_.-])/(?:Users|home|private|var|tmp|opt|Volumes)/[^\\s'\";,)}\\]]+")
    val WINDOWS_PATH = Regex("(?i)(?<![A-Za-z0-9])[A-Z]:\\\\[^\\s'\";,)}\\]]+")
    val PROTECTED_SEMANTIC_FIELDS =
      setOf("benchmark", "mode", "scoreUnit", "unit", "scenario", "transport")
  }
}
