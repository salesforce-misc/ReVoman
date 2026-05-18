/***************************************************************************************************
 *  Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier:
 *           Apache License Version 2.0
 *  For full license text, see the LICENSE file in the repo root or
 *  http://www.apache.org/licenses/LICENSE-2.0
 **************************************************************************************************/

package com.salesforce.revoman.integration.restfulapidev.v3;

import static com.google.common.truth.Truth.assertThat;

import com.salesforce.revoman.ReVoman;
import com.salesforce.revoman.input.config.Kick;
import com.salesforce.revoman.output.Rundown;
import org.junit.jupiter.api.Test;

class RestfulAPIDevV3Test {
  private static final String PM_COLLECTION_PATH = "pm-templates/v3/restful-api.dev";
  private static final String PM_ENVIRONMENT_PATH =
      "pm-templates/v3/restful-api.dev/restful-api.dev.environment.yaml";

  @Test
  void executeRestfulApiDevV3CollectionFromJava() {
    final Rundown rundown =
        ReVoman.revUp(
            Kick.configure()
                .templatePath(PM_COLLECTION_PATH)
                .environmentPath(PM_ENVIRONMENT_PATH)
                .nodeModulesPath("js")
                .off());
    assertThat(rundown.firstUnsuccessfulStepReport()).isNull();
    assertThat(rundown.stepReports.size()).isEqualTo(4);
  }
}
