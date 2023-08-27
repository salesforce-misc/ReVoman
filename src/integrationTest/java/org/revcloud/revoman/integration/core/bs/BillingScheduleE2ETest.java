/*******************************************************************************
 * Copyright (c) 2023, Salesforce, Inc.
 *  All rights reserved.
 *  SPDX-License-Identifier: BSD-3-Clause
 *  For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 ******************************************************************************/

package org.revcloud.revoman.integration.core.bs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.revcloud.revoman.input.ResponseConfig.validateIfSuccess;

import com.salesforce.vador.config.ValidationConfig;
import com.squareup.moshi.Types;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.revcloud.revoman.ReVoman;
import org.revcloud.revoman.input.Kick;
import org.revcloud.revoman.output.StepReport;

class BillingScheduleE2ETest {

  @Test
  void revUp() {
    final var pmCollectionPath = "pm-templates/revoman/bs.postman_collection.json";
    final var pmEnvironmentPath = "pm-templates/revoman/bs.postman_environment.json";
    final var orderItem2BSIASuccessType =
        Types.newParameterizedType(List.class, OrderItemToBSIAResponse.class);
    final var orderItem2BSIAValidationConfig =
        ValidationConfig.<List<OrderItemToBSIAResponse>, String>toValidate()
            .withValidator(
                (resp ->
                    Boolean.TRUE.equals(resp.get(0).isSuccess())
                        ? "success"
                        : " OrderItem2BS IA failed"),
                "success");
    final var rundown =
        ReVoman.revUp(
            Kick.configure()
                .templatePath(pmCollectionPath)
                .environmentPath(pmEnvironmentPath)
                .responseConfig(
                        validateIfSuccess(
                            "OrderItem2BS IA",
                            orderItem2BSIASuccessType, orderItem2BSIAValidationConfig))
                .off());
    assertThat(rundown.stepNameToReport.values()).allMatch(StepReport::isSuccessful);
  }
}
