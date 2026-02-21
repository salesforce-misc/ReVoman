/*
 * Copyright 2025 salesforce.com, inc.
 * All Rights Reserved
 * Company Confidential
 */

package com.salesforce.revoman.integration.core.bt2bs;

import static com.google.common.truth.Truth.assertThat;
import static com.salesforce.revoman.integration.core.bt2bs.ReVomanConfigForBT2BS.MILESTONE_CONFIG;
import static com.salesforce.revoman.integration.core.bt2bs.ReVomanConfigForBT2BS.MILESTONE_SETUP_CONFIG;
import static com.salesforce.revoman.integration.core.bt2bs.ReVomanConfigForBT2BS.PERSONA_CREATION_AND_SETUP_CONFIG;

import com.salesforce.revoman.ReVoman;
import java.util.Map;
import kotlin.collections.CollectionsKt;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

@Disabled("This needs local core server")
class MilestoneBillingE2ETest {

  @Test
  void testMilestoneBillingE2E() {
    final var mbRundown =
        ReVoman.revUp(
            (rundown, ignore) ->
                assertThat(rundown.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
            PERSONA_CREATION_AND_SETUP_CONFIG,
            MILESTONE_SETUP_CONFIG,
            MILESTONE_CONFIG);
    assertThat(CollectionsKt.last(mbRundown).mutableEnv)
        .containsAtLeastEntriesIn(
            Map.of(
                "billingMilestonePlan1Status", "Completely Billed",
                "billingMilestonePlanItem1Status", "Invoiced",
                "billingSchedule1Status", "CompletelyBilled",
                "invoice1Status", "Posted",
                "invoice2Status", "Posted"));
  }
}
