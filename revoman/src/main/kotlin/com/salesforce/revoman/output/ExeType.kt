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
  PRE_STEP_HOOK("pre-step-hook"),
  PRE_REQ_JS("pre-req-js"),
  HTTP_REQUEST("http-request"),
  HTTP_STATUS("http-status"),
  POST_RES_JS("post-res-js"),
  UNMARSHALL_RESPONSE("unmarshall-response"),
  POST_STEP_HOOK("post-step-hook"),
  POLLING("polling");

  override fun toString(): String {
    return exeName
  }
}
