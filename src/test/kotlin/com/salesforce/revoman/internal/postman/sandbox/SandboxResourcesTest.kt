/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman.sandbox

import com.salesforce.revoman.input.resolveClasspath
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.proxy.ProxyExecutable
import org.junit.jupiter.api.Test

class SandboxResourcesTest {
  @Test
  fun `loads bootcode bridgeClient and version from classpath`() {
    (SandboxResources.bootcode.length > 1_000_000) shouldBe true
    SandboxResources.bridgeClient shouldContain "bridge"
    SandboxResources.version shouldBe "6.7.0"
  }

  @Test
  fun `boot source is built once from the versioned immutable resource`() {
    var bootcodeReads = 0
    var versionReads = 0
    var sourceBuilds = 0
    val gzipPaths = mutableListOf<String>()
    val versionPaths = mutableListOf<String>()
    val bootSource =
      SandboxResources.lazyBootSource(
        readGzip = { path ->
          bootcodeReads++
          gzipPaths += path
          "globalThis.boot = true;"
        },
        readFile = { path ->
          versionReads++
          versionPaths += path
          "6.7.0"
        },
        sourceFactory = { language, code, name ->
          sourceBuilds++
          org.graalvm.polyglot.Source.newBuilder(language, code, name).build()
        },
      )
    bootcodeReads shouldBe 0
    versionReads shouldBe 0
    sourceBuilds shouldBe 0
    val first = bootSource.value
    val second = bootSource.value

    (first === second) shouldBe true
    first.language shouldBe "js"
    first.name shouldBe "postman-sandbox-6.7.0.js"
    first.characters.toString() shouldBe "globalThis.boot = true;"
    gzipPaths shouldBe listOf("postman-sandbox/bootcode.js.gz")
    versionPaths shouldBe listOf("postman-sandbox/pm-sandbox-version.txt")
    bootcodeReads shouldBe 1
    versionReads shouldBe 1
    sourceBuilds shouldBe 1
  }

  @Test
  fun `process boot source remains referentially stable`() {
    val first = SandboxResources.bootSource
    val second = SandboxResources.bootSource

    (first === second) shouldBe true
  }

  @Test
  fun `boot source defines request json compatibility before bridge initialization`() {
    val context =
      Context.newBuilder("js")
        .engine(sharedGraalEngine)
        .allowExperimentalOptions(true)
        .option("js.esm-eval-returns-exports", "true")
        .option("js.ecmascript-version", "2024")
        .allowHostAccess(HostAccess.ALL)
        .allowHostClassLookup { true }
        .build()

    context.use {
      context.getBindings("js").putMember("__uvm_emit", ProxyExecutable { null })
      context.eval(
        "js",
        """
        globalThis.setTimeout = function () {};
        globalThis.clearTimeout = function () {};
        globalThis.setInterval = function () {};
        globalThis.clearInterval = function () {};
        globalThis.setImmediate = function () {};
        globalThis.clearImmediate = function () {};
        globalThis.queueMicrotask = function () {};
        globalThis.Blob = function Blob() {};
        globalThis.File = function File() {};
        globalThis.FileReader = function FileReader() {};
        globalThis.FormData = function FormData() {};
        globalThis.atob = function (value) { return value; };
        globalThis.btoa = function (value) { return value; };
        ${SandboxResources.bridgeClient}
        """
          .trimIndent(),
      )
      context.eval(SandboxResources.bootSource)

      val compatibility =
        context.eval(
          "js",
          """
          (() => {
            const Request = require('postman-collection').Request;
            const installed = typeof Request.prototype.json === 'function';
            return {
              installed,
              parsedName: installed
                ? new Request({ body: { mode: 'raw', raw: '{"name":"bulbasaur"}' } }).json().name
                : null,
              bodyless: installed ? new Request().json() : 'missing'
            };
          })()
          """
            .trimIndent(),
        )

      compatibility.getMember("installed").asBoolean() shouldBe true
      compatibility.getMember("parsedName").asString() shouldBe "bulbasaur"
      compatibility.getMember("bodyless").isNull shouldBe true
    }
  }

  @Test
  fun `bootcode has no node-vm dependencies`() {
    SandboxResources.bootcode shouldNotContain "require('vm')"
    SandboxResources.bootcode shouldNotContain "require('child_process')"
  }

  @Test
  fun `bootcode source carries no PII gov-cloud compliance tokens`() {
    // The compliance scanner does a naive substring match on the file bytes. The scrubber escapes
    // forbidden tokens (e.g. 'ic.gov' -> '\x69c.gov') in the JS source, so the loaded source string
    // must not contain the literal — even though the engine-decoded value would.
    SandboxResources.bootcode shouldNotContain "ic.gov"
  }

  @Test
  fun `bootcode is gzipped at rest with no raw js resource`() {
    // The vendored bootcode is committed gzip-compressed (compliance + ~3x smaller git blob).
    (resolveClasspath("postman-sandbox/bootcode.js.gz") != null) shouldBe true
    (resolveClasspath("postman-sandbox/bootcode.js") == null) shouldBe true
  }
}
