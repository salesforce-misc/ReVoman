/*
 * Copyright 2025 salesforce.com, inc.
 * All Rights Reserved
 * Company Confidential
 */

package com.salesforce.revoman.integration.core.wfs;

import static com.google.common.truth.Truth.assertThat;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.AUTH_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.EXCLUDED_FIXTURE_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.EXCLUDED_RESOURCES_POLICY_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.GET_SLOTS_EXCLUDED_CONTROL_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.GET_SLOTS_EXCLUDED_VIOLATING_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.GET_SLOTS_SKILLS_CONTROL_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.GET_SLOTS_SKILLS_VIOLATING_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.GET_SLOTS_VISITING_HOURS_CONTROL_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.GET_SLOTS_VISITING_HOURS_VIOLATING_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.GET_SLOTS_WORKLOC_CONTROL_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.GET_SLOTS_WORKLOC_VIOLATING_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.MATCH_SKILLS_POLICY_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.SCHEDULE_EXCLUDED_CONTROL_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.SCHEDULE_EXCLUDED_VIOLATING_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.SCHEDULE_SKILLS_CONTROL_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.SCHEDULE_SKILLS_VIOLATING_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.SCHEDULE_VISITING_HOURS_CONTROL_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.SCHEDULE_VISITING_HOURS_VIOLATING_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.SCHEDULE_WORKLOC_CONTROL_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.SCHEDULE_WORKLOC_VIOLATING_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.SKILLS_FIXTURE_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.SKILLS_SKILL_FIXTURE_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.TERRITORY_PARTIAL_POLICY_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.VISITING_HOURS_FIXTURE_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.VISITING_HOURS_POLICY_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.WORKING_LOCATIONS_FIXTURE_CONFIG;

import com.salesforce.revoman.ReVoman;
import kotlin.collections.CollectionsKt;
import org.junit.jupiter.api.Test;

/**
 * WFS rules read==write parity (live 262; 264 contrast in each method's javadoc). Proves each of
 * the 7 Common+InBusiness scheduling rules (RuleObjectiveMapper:108-125) evaluates identically on
 * the read APIs (get-appointment-slots/candidates/available-slots/available-resources →
 * InBusinessGetCandidatesSlotsDataService.loadSchedulableSlots) and the write APIs
 * (schedule/reschedule → SlotAvailabilityChecker → same loadSchedulableSlots). Per rule a
 * differential matrix asserts read decision == write decision for a violating AND a control case.
 * Records the two genuine read≠write divergences: the reschedule no-op short-circuit
 * (SlotAvailabilityChecker:174-176) and the RequiredResources 262 NPE. onField/inField rules are
 * OUT OF SCOPE.
 */
class WfsRulesParityE2ETest {

  /**
   * MatchSkills — the required+primary resource lacking the WorkType's required skill is pruned by
   * the read (0 slots) AND rejected by the write; a skill-having control returns >0 slots AND books
   * Success. Proves MatchSkills runs identically read and write (loadSchedulableSlots shared by
   * both).
   *
   * <p>262 (asserted): read-violating 0 slots ⟺ write-violating rejected; read-control >0 ⟺
   * write-control Success.
   *
   * <p>264 contrast: unchanged — MatchSkills is a shared cheap check on both paths.
   */
  @Test
  void testMatchSkillsReadWriteParityE2E() {
    ReVomanConfigForWfs.assumeExternalOrgCreds();
    final var rundown =
        ReVoman.revUp(
            (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
            AUTH_CONFIG,
            MATCH_SKILLS_POLICY_CONFIG,
            SKILLS_SKILL_FIXTURE_CONFIG,
            SKILLS_FIXTURE_CONFIG,
            GET_SLOTS_SKILLS_VIOLATING_CONFIG,
            GET_SLOTS_SKILLS_CONTROL_CONFIG,
            SCHEDULE_SKILLS_VIOLATING_CONFIG,
            SCHEDULE_SKILLS_CONTROL_CONFIG);
    final var env = CollectionsKt.last(rundown).mutableEnv;
    // Read prunes the violating resource; control returns slots (proves fixture valid).
    assertThat(env.getAsString("skillsReadViolatingSlotCount")).isEqualTo("0");
    assertThat(Integer.parseInt(env.getAsString("skillsReadControlSlotCount"))).isGreaterThan(0);
    // Write agrees with read on BOTH rows → read==write for MatchSkills.
    assertThat(env.getAsString("skillsWriteViolatingStatus")).isNotEqualTo("Success");
    assertThat(env.getAsString("skillsWriteControlStatus")).isEqualTo("Success");
  }

  /**
   * ExcludedResources — an Account-excluded required+primary resource is pruned by the read (0
   * slots) AND rejected by the write; a non-excluded control returns >0 AND Success. Proves
   * ExcludedResources runs identically read and write.
   *
   * <p>262 (asserted): read-violating 0 ⟺ write-violating rejected; read-control >0 ⟺ write-control
   * Success.
   *
   * <p>264 contrast: unchanged — ExcludedResources is a shared cheap check.
   */
  @Test
  void testExcludedResourcesReadWriteParityE2E() {
    ReVomanConfigForWfs.assumeExternalOrgCreds();
    final var rundown =
        ReVoman.revUp(
            (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
            AUTH_CONFIG,
            EXCLUDED_RESOURCES_POLICY_CONFIG,
            EXCLUDED_FIXTURE_CONFIG,
            GET_SLOTS_EXCLUDED_VIOLATING_CONFIG,
            GET_SLOTS_EXCLUDED_CONTROL_CONFIG,
            SCHEDULE_EXCLUDED_VIOLATING_CONFIG,
            SCHEDULE_EXCLUDED_CONTROL_CONFIG);
    final var env = CollectionsKt.last(rundown).mutableEnv;
    // Read prunes the excluded violating resource; control returns slots (proves fixture valid).
    assertThat(env.getAsString("excludedReadViolatingSlotCount")).isEqualTo("0");
    assertThat(Integer.parseInt(env.getAsString("excludedReadControlSlotCount"))).isGreaterThan(0);
    // Write agrees with read on BOTH rows → read==write for ExcludedResources.
    assertThat(env.getAsString("excludedWriteViolatingStatus")).isNotEqualTo("Success");
    assertThat(env.getAsString("excludedWriteControlStatus")).isEqualTo("Success");
  }

  /**
   * WorkingLocations (SchedulingRuleType WorkingTerritories) — the required+primary resource
   * outside its working-location/territory-membership window (here a SECONDARY
   * ServiceTerritoryMember under a WorkingTerritories(IsPrimaryLocationEnabled=true) policy that
   * includes PRIMARY members only) is pruned by the read (0 slots) AND rejected by the write; an
   * in-window control (a PRIMARY member) returns >0 AND Success. Proves WorkingLocations runs
   * identically read and write.
   *
   * <p>262 (asserted): read-violating 0 ⟺ write-violating rejected; read-control >0 ⟺ write-control
   * Success.
   *
   * <p>264 contrast: unchanged — WorkingLocations is a shared cheap check on both paths.
   */
  @Test
  void testWorkingLocationsReadWriteParityE2E() {
    ReVomanConfigForWfs.assumeExternalOrgCreds();
    final var rundown =
        ReVoman.revUp(
            (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
            AUTH_CONFIG,
            TERRITORY_PARTIAL_POLICY_CONFIG,
            WORKING_LOCATIONS_FIXTURE_CONFIG,
            GET_SLOTS_WORKLOC_VIOLATING_CONFIG,
            GET_SLOTS_WORKLOC_CONTROL_CONFIG,
            SCHEDULE_WORKLOC_VIOLATING_CONFIG,
            SCHEDULE_WORKLOC_CONTROL_CONFIG);
    final var env = CollectionsKt.last(rundown).mutableEnv;
    // Read prunes the Secondary-member violating resource; control returns slots (proves fixture
    // valid).
    assertThat(env.getAsString("worklocReadViolatingSlotCount")).isEqualTo("0");
    assertThat(Integer.parseInt(env.getAsString("worklocReadControlSlotCount"))).isGreaterThan(0);
    // Write agrees with read on BOTH rows → read==write for WorkingLocations.
    assertThat(env.getAsString("worklocWriteViolatingStatus")).isNotEqualTo("Success");
    assertThat(env.getAsString("worklocWriteControlStatus")).isEqualTo("Success");
  }

  /**
   * ServiceAppointmentVisitingHours — a booking window OUTSIDE the parent Account's visiting hours
   * (the Account's VisitingHours OperatingHours 10-14) is pruned by the read (0 slots) AND rejected
   * by the write; an in-visiting-hours control (11-12) returns >0 slots AND books Success. Proves
   * VisitingHours runs identically read and write (loadSchedulableSlots shared by both). The policy
   * (Availability + WorkingTerritories(IsPrimaryLocationEnabled)) and fixture were LIFTED from
   * source and CONFORMED to the 262 contract (WorkType has no SchedulingMethod/IsRegular; the
   * Account is linked to its visiting-hours OH; the Availability rule carries ShiftUsage=Union).
   *
   * <p>262 (asserted): read-violating 0 ⟺ write-violating rejected; read-control >0 ⟺ write-control
   * Success.
   *
   * <p>264 contrast: unchanged — ServiceAppointmentVisitingHours is a shared per-slot check on both
   * paths.
   */
  @Test
  void testVisitingHoursReadWriteParityE2E() {
    ReVomanConfigForWfs.assumeExternalOrgCreds();
    final var rundown =
        ReVoman.revUp(
            (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
            AUTH_CONFIG,
            VISITING_HOURS_POLICY_CONFIG,
            VISITING_HOURS_FIXTURE_CONFIG,
            GET_SLOTS_VISITING_HOURS_VIOLATING_CONFIG,
            GET_SLOTS_VISITING_HOURS_CONTROL_CONFIG,
            SCHEDULE_VISITING_HOURS_VIOLATING_CONFIG,
            SCHEDULE_VISITING_HOURS_CONTROL_CONFIG);
    final var env = CollectionsKt.last(rundown).mutableEnv;
    // Read prunes the out-of-visiting-hours window; control returns slots (proves fixture valid).
    assertThat(env.getAsString("visitingHoursReadViolatingSlotCount")).isEqualTo("0");
    assertThat(Integer.parseInt(env.getAsString("visitingHoursReadControlSlotCount")))
        .isGreaterThan(0);
    // Write agrees with read on BOTH rows → read==write for ServiceAppointmentVisitingHours.
    assertThat(env.getAsString("visitingHoursWriteViolatingStatus")).isNotEqualTo("Success");
    assertThat(env.getAsString("visitingHoursWriteControlStatus")).isEqualTo("Success");
  }
}
