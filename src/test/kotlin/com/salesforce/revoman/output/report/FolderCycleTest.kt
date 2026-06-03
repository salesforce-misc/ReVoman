/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.output.report

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.internal.exe.deepFlattenItems
import com.salesforce.revoman.internal.postman.template.Item
import org.junit.jupiter.api.Test

/**
 * Regression: a nested folder is linked bidirectionally (each [Folder] holds its `parent` AND the
 * parent holds the child in `subFolders`). If `subFolders` participates in the generated
 * `hashCode`/`equals`, the parent<->child cycle recurses forever — fatal the moment a nested [Step]
 * (whose `parentFolder` is such a [Folder]) is used as a HashMap/HashSet key, which the ledger's
 * per-step produced/consumed capture does on every `pm.environment.set` / `{{var}}` resolve.
 */
class FolderCycleTest {
  /** Build the same bidirectional parent<->subFolders graph `deepFlattenItems` builds for `a/b`. */
  private fun nestedFolder(): Folder {
    val parent = Folder("a")
    val child = Folder("b", parent)
    parent.subFolders.add(child) // the down-link that closes the cycle
    return child
  }

  @Test
  fun `nested Folder hashCode terminates (no parent-subFolders cycle)`() {
    nestedFolder().hashCode() // must not StackOverflow
  }

  @Test
  fun `a nested Step is usable as a HashMap key (ledger capture does this)`() {
    val step = Step(index = "1", rawPMStep = Item(name = "color"), parentFolder = nestedFolder())
    val map = HashMap<Step, String>()
    map[step] = "v" // hashes Step -> parentFolder -> Folder.hashCode; must terminate
    assertThat(map[step]).isEqualTo("v")
  }

  @Test
  fun `deepFlattened nested steps are hashable (real shape from a folder tree)`() {
    // Two folders deep: root -> outer -> inner -> step, mirroring pokemon|>props|>color.
    val inner = Item(name = "inner", item = listOf(Item(name = "leaf")))
    val outer = Item(name = "outer", item = listOf(inner))
    val steps = deepFlattenItems(listOf(outer))
    assertThat(steps).isNotEmpty()
    val byStep = steps.associateWith { it.name } // hashes each nested Step; must terminate
    assertThat(byStep).hasSize(steps.size)
  }
}
