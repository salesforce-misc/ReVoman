/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.perf

import com.salesforce.revoman.output.ExeType

/** Stable semantic operations used by both JFR recordings and benchmark reports. */
internal enum class OperationKind(@JvmField val frequencyValue: String) {
  MULTI_KICK("RUN"),
  POST_EXECUTION_HOOK("COLLECTION"),
  RUNBOOK("RUN"),
  RUNBOOK_STEP("RUNBOOK_STEP"),
  COLLECTION_DECODE_V2_PATH("COLLECTION"),
  COLLECTION_DECODE_V3_PATH("COLLECTION"),
  COLLECTION_DECODE_STREAM("COLLECTION"),
  COLLECTION_ADAPTER_PREPARE("COLLECTION"),
  COLLECTION_FLATTEN("COLLECTION"),
  DYNAMIC_VARIABLE_RUNTIME_PREPARE("COLLECTION"),
  JSON_RUNTIME_PREPARE("COLLECTION"),
  ENVIRONMENT_LOAD("COLLECTION"),
  ENVIRONMENT_SEED("COLLECTION"),
  SDK_RUNTIME_PREPARE("COLLECTION"),
  HTTP_CLIENT_PREPARE("COLLECTION"),
  SEQUENCE_EXECUTE("COLLECTION"),
  RUN_FINALIZE("COLLECTION"),
  ENVIRONMENT_REBUILD("STEP"),
  PRE_REQUEST_SCRIPT("STEP"),
  REQUEST_PREPARE("STEP"),
  PRE_STEP_HOOK("STEP"),
  REQUEST_BUILD("STEP"),
  HTTP_EXCHANGE("STEP"),
  POST_RESPONSE_SCRIPT("STEP"),
  RESPONSE_PREPARE("STEP"),
  POST_STEP_HOOK("STEP"),
  POLLING("STEP"),
  SANDBOX_BOOT("COLLECTION"),
  SANDBOX_CLOSE("COLLECTION");

  @JvmField val eventValue: String = name

  companion object {
    fun from(exeType: ExeType): OperationKind =
      when (exeType) {
        ExeType.PRE_REQ_JS -> PRE_REQUEST_SCRIPT
        ExeType.UNMARSHALL_REQUEST -> REQUEST_PREPARE
        ExeType.PRE_STEP_HOOK -> PRE_STEP_HOOK
        ExeType.HTTP_REQUEST -> HTTP_EXCHANGE
        ExeType.POST_RES_JS -> POST_RESPONSE_SCRIPT
        ExeType.UNMARSHALL_RESPONSE -> RESPONSE_PREPARE
        ExeType.POST_STEP_HOOK -> POST_STEP_HOOK
        ExeType.POLLING -> POLLING
        ExeType.HTTP_STATUS -> error("HTTP status validation is not a timed execution phase")
      }
  }
}
