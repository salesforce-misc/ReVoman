/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman

import com.salesforce.revoman.input.PostExeHook
import com.salesforce.revoman.input.config.Kick
import com.salesforce.revoman.input.config.Runbook
import com.salesforce.revoman.internal.runtime.reVomanRuntime
import com.salesforce.revoman.output.RunbookRundown
import com.salesforce.revoman.output.Rundown

object ReVoman {
  @JvmStatic
  @JvmOverloads
  fun revUp(
    postExeHook: PostExeHook = PostExeHook { _, _ -> },
    dynamicEnvironment: Map<String, Any?> = emptyMap(),
    vararg kicks: Kick,
  ): List<Rundown> = reVomanRuntime().execute(kicks.toList(), postExeHook, dynamicEnvironment)

  @JvmStatic
  @JvmOverloads
  fun revUp(
    kicks: List<Kick>,
    postExeHook: PostExeHook = PostExeHook { _, _ -> },
    dynamicEnvironment: Map<String, Any?> = emptyMap(),
  ): List<Rundown> = reVomanRuntime().execute(kicks, postExeHook, dynamicEnvironment)

  /**
   * Execute a [Runbook] — the legible, narrated form of a multi-collection chain. Threads env
   * exactly like [revUp] over `List<Kick>`, adding per-step data-flow contract checks, per-step
   * assertions, and coarse grouped log events. Halts (throws [AssertionError]) at the first breach.
   */
  @JvmStatic
  @JvmOverloads
  fun revUp(runbook: Runbook, dynamicEnvironment: Map<String, Any?> = emptyMap()): RunbookRundown =
    reVomanRuntime().execute(runbook, dynamicEnvironment)

  @JvmStatic fun revUp(kick: Kick): Rundown = reVomanRuntime().execute(kick)
}
