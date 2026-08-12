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
import com.salesforce.revoman.internal.exe.executeRunbook
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
  ): List<Rundown> = revUp(kicks.toList(), postExeHook, dynamicEnvironment)

  @JvmStatic
  @JvmOverloads
  fun revUp(
    kicks: List<Kick>,
    postExeHook: PostExeHook = PostExeHook { _, _ -> },
    dynamicEnvironment: Map<String, Any?> = emptyMap(),
  ): List<Rundown> =
    kicks
      .fold(dynamicEnvironment to listOf<Rundown>()) { (accumulatedMutableEnv, rundowns), kick ->
        val rundown =
          revUp(kick.overrideDynamicEnvironment(kick.dynamicEnvironment() + accumulatedMutableEnv))
        val accumulatedRundowns = rundowns + rundown
        postExeHook.accept(rundown, accumulatedRundowns)
        // Thread the FULL env into the next kick — every value type, not just String.
        // `immutableEnv`
        // is an all-types snapshot (`mutableEnv.toMap()`); an earlier `<String>`-only copy silently
        // dropped Int/POJO/List values a prior kick produced. See
        // docs/superpowers/specs/2026-07-01-multi-kick-env-all-types-design.md
        rundown.mutableEnv.immutableEnv to accumulatedRundowns
      }
      .second

  /**
   * Execute a [Runbook] — the legible, narrated form of a multi-collection chain. Threads env
   * exactly like [revUp] over `List<Kick>`, adding per-step data-flow contract checks, per-step
   * assertions, and coarse grouped log events. Halts (throws [AssertionError]) at the first breach.
   */
  @JvmStatic
  @JvmOverloads
  fun revUp(runbook: Runbook, dynamicEnvironment: Map<String, Any?> = emptyMap()): RunbookRundown =
    executeRunbook(runbook, dynamicEnvironment)

  @JvmStatic fun revUp(kick: Kick): Rundown = reVomanRuntime().execute(kick)
}
