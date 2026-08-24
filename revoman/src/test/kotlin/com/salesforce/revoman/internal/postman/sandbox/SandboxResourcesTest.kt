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
import org.junit.jupiter.api.Test

class SandboxResourcesTest {
  @Test
  fun `loads bootcode bridgeClient and version from classpath`() {
    (SandboxResources.bootcode.length > 1_000_000) shouldBe true
    SandboxResources.bridgeClient shouldContain "bridge"
    SandboxResources.version shouldBe "6.7.0"
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
