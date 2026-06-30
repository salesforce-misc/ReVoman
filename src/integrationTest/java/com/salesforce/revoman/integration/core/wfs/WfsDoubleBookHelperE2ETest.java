/*
 * Copyright 2025 salesforce.com, inc.
 * All Rights Reserved
 * Company Confidential
 */

package com.salesforce.revoman.integration.core.wfs;

import static com.google.common.truth.Truth.assertThat;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.AUTH_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.AVAILABILITY_OP_HOURS_POLICY_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.DOUBLE_BOOK_FIXTURE_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.DOUBLE_BOOK_NON_REQUIRED_SCHEDULE_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.DOUBLE_BOOK_REQUIRED_CONFLICT_SCHEDULE_CONFIG;

import com.salesforce.revoman.ReVoman;
import kotlin.collections.CollectionsKt;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * WFS Decision-1 Scenario A (DOUBLE-BOOK / availability half): proves a NON-required "helper"
 * resource is NOT availability-checked on the Schedule write path.
 *
 * <p>The A/B flips ONLY {@code isRequiredResource} on resourceB over the SAME fixture:
 *
 * <ul>
 *   <li>NON-required act: resourceA (required+primary, clean, available 10-14) + resourceB
 *       (NON-required, BUSY at 11:00 — its member-OH+Shift are 12-14) → {@code
 *       schedulingStatus=Success} (a non-required helper is never availability-checked, so it may
 *       double-book).
 *   <li>REQUIRED control: same fixture, resourceB flipped to {@code isRequiredResource=true} → a
 *       required resource IS availability-checked → ScheduleError / not-available.
 * </ul>
 *
 * <p>The per-appointment verdict is asserted inside the collection step `test` scripts (which also
 * stash {@code doubleBookNonRequiredSchedulingStatus} / {@code
 * doubleBookRequiredControlSchedulingStatus} into the env); this test additionally asserts no
 * un-ignored step failed and that the env carries the two expected verdicts.
 */
@Disabled(
    "needs a WFS workspace org: multi-resource pref (WorkforceSchdMulResSchdPref) + InBusinessScheduling enabled"
        + " + Shift.Status DynEnum seeded + the Availability rule's ShiftUsage param. See ReVomanConfigForWfs.")
class WfsDoubleBookHelperE2ETest {

  @Test
  void testDoubleBookNonRequiredHelperE2E() {
    final var doubleBookRundown =
        ReVoman.revUp(
            (rundown, ignore) ->
                assertThat(rundown.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
            AUTH_CONFIG,
            AVAILABILITY_OP_HOURS_POLICY_CONFIG,
            DOUBLE_BOOK_FIXTURE_CONFIG,
            DOUBLE_BOOK_NON_REQUIRED_SCHEDULE_CONFIG,
            DOUBLE_BOOK_REQUIRED_CONFLICT_SCHEDULE_CONFIG);
    final var env = CollectionsKt.last(doubleBookRundown).mutableEnv;
    // Looser half: the NON-required helper double-books even though it is busy at the window.
    assertThat(env).containsEntry("doubleBookNonRequiredSchedulingStatus", "Success");
    // Busy-proof control: flipping ONLY isRequiredResource=true makes the SAME resourceB get
    // availability-checked → not-available (anything other than Success).
    assertThat(env.getAsString("doubleBookRequiredControlSchedulingStatus"))
        .isNotEqualTo("Success");
  }
}
