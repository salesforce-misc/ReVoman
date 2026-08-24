/***************************************************************************************************
 *  Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier:
 *           Apache License Version 2.0
 *  For full license text, see the LICENSE file in the repo root or
 *  http://www.apache.org/licenses/LICENSE-2.0
 **************************************************************************************************/

package com.salesforce.revoman.input.config;

import static com.google.common.truth.Truth.assertThat;
import static com.salesforce.revoman.input.config.Phase.ACT;
import static com.salesforce.revoman.input.config.Phase.SETUP;

import java.util.Map;
import org.junit.jupiter.api.Test;

class RunbookJavaDslTest {
  private static Kick anyKick(final String name) {
    return Kick.configure().templatePath("pm-templates/v3/" + name).off();
  }

  @Test
  void javaBuilderBuildsOrderedSteps() {
    final var runbook =
        Runbook.configure()
            .name("wfs double book")
            .step("login as admin", SETUP, anyKick("cf-stop"), s -> s.produces("authToken"))
            .step(
                "schedule",
                ACT,
                anyKick("cf-loop"),
                s ->
                    s.underTest()
                        .consumes("authToken")
                        .produces(Map.of("schedulingStatus", "Success")))
            .off();
    assertThat(runbook.getName()).isEqualTo("wfs double book");
    assertThat(runbook.getSteps()).hasSize(2);
    assertThat(runbook.getSteps().get(1).getUnderTest()).isTrue();
    assertThat(runbook.getSteps().get(1).getProduces())
        .containsEntry("schedulingStatus", "Success");
  }
}
