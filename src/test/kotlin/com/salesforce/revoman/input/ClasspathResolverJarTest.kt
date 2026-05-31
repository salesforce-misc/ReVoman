/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.input

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Regression guard for the consumer failure: a jar entry whose name contains raw brackets and a
 * percent-encoded space (e.g. `[sm]%20persona-creation-and-setup-pq.postman_collection.json`). Some
 * classloader URL stream handlers (Spring Boot nested-jar, bazel runfiles) leave `[`/`]` raw in the
 * jar URL but encode the space as `%20`. The previous decode via `URI.create(entry).path` was
 * strict RFC-2396 and rejected `[`/`]` as illegal path characters, throwing
 * `IllegalArgumentException: Illegal character in path at index 15`.
 */
class ClasspathResolverJarTest {
  @Test
  fun testDecodeJarEntryWithRawBracketsAndEncodedSpace() {
    assertThat(
        decodeJarEntryPath(
          "/revoman/sm-pq/[sm]%20persona-creation-and-setup-pq.postman_collection.json"
        )
      )
      .isEqualTo("/revoman/sm-pq/[sm] persona-creation-and-setup-pq.postman_collection.json")
  }

  @Test
  fun testDecodeJarEntryDecodesEncodedBrackets() {
    assertThat(decodeJarEntryPath("/a/%5Bsm%5D%20x.json")).isEqualTo("/a/[sm] x.json")
  }

  @Test
  fun testDecodeJarEntryPreservesLiteralPlus() {
    // `+` is a literal path char in jar entries, NOT a space. Must not be decoded to space.
    assertThat(decodeJarEntryPath("/a/b+c.json")).isEqualTo("/a/b+c.json")
  }

  @Test
  fun testDecodeJarEntryDecodesMultibyteUtf8() {
    // `%C3%A9` -> `é` (UTF-8). Folder names can carry accented characters.
    assertThat(decodeJarEntryPath("/a/caf%C3%A9.json")).isEqualTo("/a/café.json")
  }
}
