/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.harness.eval

import org.yaml.snakeyaml.Yaml

/** Loads the labeled router eval set from a classpath YAML resource. */
object EvalSet {
  fun load(resource: String = "evals/router-eval.yaml"): List<EvalCase> {
    val text =
      javaClass.classLoader.getResourceAsStream(resource)?.bufferedReader()?.readText()
        ?: error("Eval set not found on classpath: $resource")
    @Suppress("UNCHECKED_CAST") val root = Yaml().load<Map<String, Any?>>(text)
    @Suppress("UNCHECKED_CAST")
    val cases = (root["cases"] as? List<Map<String, Any?>>).orEmpty()
    return cases.map { EvalCase(it["utterance"].toString(), it["expected"].toString()) }
  }
}
