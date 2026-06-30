/*
 * Copyright 2025 salesforce.com, inc.
 * All Rights Reserved
 * Company Confidential
 */

package com.salesforce.revoman.integration.core.wfs;

import static com.google.common.truth.Truth.assertThat;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.AUTH_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.GET_RESOURCES_LIMIT_POSITIVE_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.GET_RESOURCES_LIMIT_ZERO_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.REQUIRED_NON_REQUIRED_FIXTURE_CONFIG;

import com.salesforce.revoman.ReVoman;
import kotlin.collections.CollectionsKt;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * WFS read↔write parity read-path characterization (live 262; 264 contrast in each method's javadoc).
 *
 * <p>Decisions covered: 8 (resourceLimitApptDistribution cap — load-balancing read), 9 (Shift
 * sharing-mode split — user-mode SystemMode.NONE shift read vs SFDC_FULL sibling reads). Read-path
 * enforce contract: a rule violation / cap returns EMPTY slots/resources, HTTP 200, NO 400/exception.
 */
@Disabled(
    "needs a WFS workspace org: see ReVomanConfigForWfs. Decision 9 additionally needs Shift OWD=Private"
        + " and a manager persona without sharing on admin-owned Shift rows.")
class WfsReadPathParityE2ETest {

  /**
   * Decision 8 — resourceLimitApptDistribution caps the load-balancing resource list AFTER the
   * per-resource rules filter candidates (read-only presentation cap, no write-path counterpart). The
   * cap only runs because the workspace default OnSite policy carries a LoadBalancing
   * SchedulingObjective ({@code DefaultOnSiteSchdPlcy_LoadBalancing}); both acts omit {@code
   * schedulingPolicyName} so GetAvailableResources falls back to that default policy and the
   * load-balancing path executes.
   *
   * <p>262 (asserted): limit=0 → EMPTY resource list ({@code
   * AppointmentDistributionService.findLeastUtilizedResources}: limit=0 → {@code Stream.limit(0)} →
   * empty); an explicit positive limit (50) above the seeded count → resources returned. Proves 0 is a
   * literal cap-of-0, not "no limit" (the default {@code resourceLimitApptDistribution} is 10 when
   * omitted — see the "260 Unified Scheduling Appointment Distribution" design doc).
   * <p>264 contrast: if product picks option A ("no cap"), 0/negative → all eligible resources (the
   * surprising empty list on the default policy goes away).
   *
   * <p>One revUp (both read acts share the {@code required-non-required} fixture — read-only, so no
   * ServiceResource collision). Reference {@code
   * AppointmentDistributionService.findLeastUtilizedResources}.
   */
  @Test
  void testResourceLimitApptDistributionCapE2E() {
    final var rundown =
        ReVoman.revUp(
            (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
            AUTH_CONFIG,
            REQUIRED_NON_REQUIRED_FIXTURE_CONFIG,
            GET_RESOURCES_LIMIT_ZERO_CONFIG,
            GET_RESOURCES_LIMIT_POSITIVE_CONFIG);
    final var env = CollectionsKt.last(rundown).mutableEnv;
    assertThat(env.getAsString("limitZeroResourceCount")).isEqualTo("0");
    assertThat(Integer.parseInt(env.getAsString("limitPositiveResourceCount"))).isGreaterThan(0);
  }
}
