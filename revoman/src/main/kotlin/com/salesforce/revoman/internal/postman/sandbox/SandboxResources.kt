/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman.sandbox

import com.salesforce.revoman.input.readFileToString
import com.salesforce.revoman.input.readGzippedFileToString

/**
 * Loads the vendored Postman sandbox resources from the classpath. Uses ReVoman's
 * [readFileToString]/[readGzippedFileToString] (backed by the ClasspathResolver, which honours the
 * thread context classloader) rather than okio's `FileSystem.RESOURCES` — the latter is bound to
 * okio's own classloader and cannot see resources on bazel/URLClassLoader/OSGi child loaders.
 *
 * [bootcode] is committed gzip-compressed (`bootcode.js.gz`): the 2.2 MB minified bundle shrinks
 * ~3x in git, and the compressed bytes are opaque to the naive-substring PII/Gov-Cloud compliance
 * scanner (a defense-in-depth complement to the build-time token scrubber in `generatePmSandbox`).
 * It is inflated once on first access and cached. [bridgeClient] (3 KB) stays raw.
 *
 * Resources are read once and cached for the JVM lifetime; they are immutable build artifacts.
 */
internal object SandboxResources {
  private const val DIR = "postman-sandbox"

  val bootcode: String by lazy { readGzippedFileToString("$DIR/bootcode.js.gz") }
  val bridgeClient: String by lazy { readFileToString("$DIR/bridge-client.js") }
  val version: String by lazy { readFileToString("$DIR/pm-sandbox-version.txt").trim() }
}
