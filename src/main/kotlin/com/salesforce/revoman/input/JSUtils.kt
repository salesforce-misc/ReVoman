/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
@file:JvmName("JSUtils")

package com.salesforce.revoman.input

import com.salesforce.revoman.internal.postman.pm
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.Value

/** Refer: https://www.graalvm.org/22.3/reference-manual/embed-languages/ */
private lateinit var jsContext: Context

private const val NODE_MODULES_PATH_KEY = "js.commonjs-require-cwd"
private var imports = ""

internal fun initJSContext(nodeModulesRelativePath: String?) {
  val options = buildMap {
    if (!nodeModulesRelativePath.isNullOrBlank()) {
      put("js.commonjs-require", "true")
      put(NODE_MODULES_PATH_KEY, javaClass.classLoader.getResource(nodeModulesRelativePath)?.path)
      imports = "var _ = require('lodash')\n"
    }
    put("js.esm-eval-returns-exports", "true")
    put("engine.WarnInterpreterOnly", "false")
  }
  jsContext =
    Context.newBuilder("js")
      .allowExperimentalOptions(true)
      .allowIO(true)
      .options(options)
      .allowHostAccess(HostAccess.ALL)
      .allowHostClassLookup { true }
      .build()
      .also {
        val contextBindings = it.getBindings("js")
        contextBindings.putMember("pm", pm)
        contextBindings.putMember("xml2Json", pm.xml2Json)
        contextBindings.putMember("xml2Json", pm.xml2Json)
      }
}

fun evaluateJS(js: String, bindings: Map<String, Any> = emptyMap()): Value? {
  val contextBindings = jsContext.getBindings("js")
  bindings.forEach { (key, value) -> contextBindings.putMember(key, value) }
  val jsSource = Source.newBuilder("js", imports + js, "script.js").build()
  // ! TODO gopala.akshintala 15/05/22: Keep a tab on jsContext mix-up from different steps
  return jsContext.eval(jsSource)
}
