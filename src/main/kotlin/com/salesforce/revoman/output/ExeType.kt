/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output

enum class ExeType(private val exeName: String) {
  UNMARSHALL_REQUEST("unmarshall-request"),
  PRE_HOOK("pre-hook"),
  PRE_REQUEST_JS("pre-request-js"),
  HTTP_REQUEST("http-request"),
  HTTP_STATUS("http-status"),
  TESTS_JS("tests-js"),
  UNMARSHALL_RESPONSE("unmarshall-response"),
  POST_HOOK("post-hook");

  override fun toString(): String {
    return exeName
  }
}
