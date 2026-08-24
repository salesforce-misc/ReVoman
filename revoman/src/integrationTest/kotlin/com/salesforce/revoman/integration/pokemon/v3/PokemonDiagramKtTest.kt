/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.integration.pokemon.v3

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.ReVoman
import com.salesforce.revoman.input.config.Kick
import com.salesforce.revoman.output.log.DiagramRunLogSink
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PokemonDiagramKtTest {

  @Test
  fun `run produces a mermaid sequence diagram`(@TempDir logsDir: Path) {
    val startedAt = Instant.parse("2026-06-05T13:57:02Z")
    val sink = DiagramRunLogSink.open(logsDir, "PokemonDiagramKtTest.run", startedAt)
    ReVoman.revUp(
      Kick.configure()
        .templatePath(PM_COLLECTION_PATH)
        .environmentPath(PM_ENVIRONMENT_PATH)
        .nodeModulesPath("js")
        .dynamicEnvironment(mapOf("offset" to "0", "limit" to "1"))
        .runLogSink(sink)
        .off()
    )
    sink.close()

    val mmd = logsDir.resolve("PokemonDiagramKtTest.run").resolve("2026-06-05T13-57-02.mmd")
    val body = Files.readString(mmd)
    assertThat(body).startsWith("sequenceDiagram")
    assertThat(body).contains("participant h0 as pokeapi.co")
    assertThat(body).contains("User->>h0:")
  }

  companion object {
    private const val PM_COLLECTION_PATH = "pm-templates/v3/pokemon"
    private const val PM_ENVIRONMENT_PATH = "pm-templates/v3/pokemon/Pokemon.environment.yaml"
  }
}
