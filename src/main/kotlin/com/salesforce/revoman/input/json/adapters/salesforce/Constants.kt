/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.input.json.adapters.salesforce

internal val SUCCESSFUL = 200..299
internal val CLIENT_ERROR = 400..499
internal const val PROCESSING_HALTED = "PROCESSING_HALTED"
internal const val OPERATION_IN_TRANSACTION_FAILED_ERROR =
  "The transaction was rolled back since another operation in the same transaction failed."
