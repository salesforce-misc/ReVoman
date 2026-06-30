/*
 * Copyright 2025 salesforce.com, inc.
 * All Rights Reserved
 * Company Confidential
 */

package com.salesforce.revoman.integration.core.wfs;

import static com.google.common.truth.Truth.assertThat;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.AUTH_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.AUTH_PERSONAS_DEC9_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.GET_RESOURCES_LIMIT_POSITIVE_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.GET_RESOURCES_LIMIT_ZERO_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.GET_SLOTS_SHARING_SPLIT_AS_CASEWORKER_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.GET_SLOTS_SHARING_SPLIT_AS_MANAGER_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.REQUIRED_NON_REQUIRED_FIXTURE_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.SHARING_SPLIT_FIXTURE_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.SHARING_SPLIT_POLICY_CONFIG;

import com.salesforce.revoman.ReVoman;
import kotlin.collections.CollectionsKt;
import org.junit.jupiter.api.Test;

/**
 * WFS read↔write parity read-path characterization (live 262; 264 contrast in each method's javadoc).
 *
 * <p>Decisions covered: 8 (resourceLimitApptDistribution cap — load-balancing read), 9 (Shift
 * sharing-mode split — user-mode SystemMode.NONE shift read vs SFDC_FULL sibling reads). Read-path
 * enforce contract: a rule violation / cap returns EMPTY slots/resources, HTTP 200, NO 400/exception.
 */
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
    // The empty list is the CAP (Stream.limit(0)), NOT an error response — assert no errorCode so the
    // empty result can't be a false positive from a swallowed 4xx/5xx (both acts are ignore-HTTP-status).
    assertThat(env.getAsString("limitZeroErrorCode")).isAnyOf(null, "null");
    assertThat(env.getAsString("limitZeroResourceCount")).isEqualTo("0");
    assertThat(env.getAsString("limitPositiveErrorCode")).isAnyOf(null, "null");
    assertThat(Integer.parseInt(env.getAsString("limitPositiveResourceCount"))).isGreaterThan(0);
  }

  /**
   * Decision 9 — the SHIFT availability read runs in user mode ({@code SystemMode.NONE}, respects caller
   * sharing) while the sibling resource/STM/absence/event reads run {@code SystemMode.SFDC_FULL}. A
   * booking user who lacks sharing on a resource's Shift rows silently gets empty availability / no slots,
   * with NO error — the asymmetry this decision records (commit {@code b02beb2af29b} / W-22502139).
   *
   * <p>Cross-persona repro (all real personas with their OWN SOAP sessions; adminToken only mints them):
   * the MANAGER persona creates and OWNS the policy + fixture + the Confirmed Shift rows (Shift OWD is
   * Private on the org). The CASE-WORKER persona is NOT shared those Shift rows.
   *
   * <p>262 (asserted): the SAME GetSlots (same fixture/policy/window) returns slots for the manager (the
   * shift owner) but ZERO slots, HTTP 200, no error for the case-worker — its user-mode shift read
   * ({@code UnavailabilityService.loadFullShiftsBulk}) sees an empty shift list, so the slot calculator
   * ({@code InBusinessAppointmentSlotCalculator}) emits no intervals. The resource is still admitted (the
   * SFDC_FULL STM/resource reads see it), so the empty result is purely the shift sharing-gate.
   * <p>264 contrast: option A documents the gate as a contract (optionally surface a reason instead of a
   * silent empty); option B aligns the modes so the case-worker would also see slots. Reference {@code
   * UnavailabilityService.loadFullShiftsBulk}/{@code loadShiftIntervalsBulk} ({@code SystemMode.NONE}) vs
   * {@code loadResourceUnavailabilitySourcesBulk} ({@code SystemMode.SFDC_FULL}).
   *
   * <p>One revUp: AUTH (adminToken) → persona-mint (manager + case-worker own sessions) → manager-owned
   * policy + fixture → manager read (control) → case-worker read (probe).
   */
  @Test
  void testShiftSharingModeSplitE2E() {
    final var rundown =
        ReVoman.revUp(
            (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
            AUTH_CONFIG,
            AUTH_PERSONAS_DEC9_CONFIG,
            SHARING_SPLIT_POLICY_CONFIG,
            SHARING_SPLIT_FIXTURE_CONFIG,
            GET_SLOTS_SHARING_SPLIT_AS_MANAGER_CONFIG,
            GET_SLOTS_SHARING_SPLIT_AS_CASEWORKER_CONFIG);
    final var env = CollectionsKt.last(rundown).mutableEnv;
    // Manager (shift owner) sees availability; case-worker (no sharing on the Private shifts) sees none.
    assertThat(Integer.parseInt(env.getAsString("dec9ManagerSlotCount"))).isGreaterThan(0);
    assertThat(env.getAsString("dec9CaseWorkerSlotCount")).isEqualTo("0");
  }
}
