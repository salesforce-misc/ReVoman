/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output.report

import com.salesforce.revoman.output.ExeType

/**
 * The result of a single `pm.test(name, fn)` assertion block reported by the Postman sandbox.
 * Attached to [StepReport.pmTestAssertions]. A failing assertion is DATA here (not a thrown error):
 * [passed] is false and [error] carries the chai/AssertionError message. [skipped] is true for
 * `pm.test.skip(...)`. [exeType] records which script phase produced it ([ExeType.PRE_REQ_JS] for a
 * pre-request script, [ExeType.POST_RES_JS] for a test script).
 */
data class PmTestAssertion
@JvmOverloads
constructor(
  @JvmField val name: String,
  @JvmField val passed: Boolean,
  @JvmField val skipped: Boolean = false,
  @JvmField val error: String? = null,
  @JvmField val exeType: ExeType = ExeType.POST_RES_JS,
)
