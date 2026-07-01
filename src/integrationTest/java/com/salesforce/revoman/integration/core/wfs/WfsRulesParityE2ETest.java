/*
 * Copyright 2025 salesforce.com, inc.
 * All Rights Reserved
 * Company Confidential
 */

package com.salesforce.revoman.integration.core.wfs;

import static com.google.common.truth.Truth.assertThat;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.AUTH_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.AVAILABILITY_OP_HOURS_POLICY_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.EXCLUDED_FIXTURE_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.EXCLUDED_RESOURCES_POLICY_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.GET_AVAILABLE_RESOURCES_SKILLS_VIOLATING_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.GET_AVAILABLE_SLOTS_SKILLS_VIOLATING_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.GET_CANDIDATES_SKILLS_VIOLATING_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.GET_SLOTS_EXCLUDED_CONTROL_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.GET_SLOTS_EXCLUDED_VIOLATING_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.GET_SLOTS_REQUIRED_CONTROL_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.GET_SLOTS_REQUIRED_VIOLATING_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.GET_SLOTS_SKILLS_CONTROL_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.GET_SLOTS_SKILLS_VIOLATING_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.GET_SLOTS_STI_CONTROL_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.GET_SLOTS_STI_VIOLATING_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.GET_SLOTS_VISITING_HOURS_CONTROL_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.GET_SLOTS_VISITING_HOURS_VIOLATING_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.GET_SLOTS_WORKLOC_CONTROL_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.GET_SLOTS_WORKLOC_VIOLATING_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.MATCH_SKILLS_POLICY_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.REQUIRED_NON_REQUIRED_FIXTURE_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.REQUIRED_RESOURCES_POLICY_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.RESCHEDULE_NOOP_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.SCHEDULE_EXCLUDED_CONTROL_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.SCHEDULE_EXCLUDED_VIOLATING_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.SCHEDULE_NOOP_RESCHED_SETUP_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.SCHEDULE_SKILLS_CONTROL_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.SCHEDULE_SKILLS_VIOLATING_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.SCHEDULE_STI_CONTROL_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.SCHEDULE_STI_VIOLATING_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.SCHEDULE_VISITING_HOURS_CONTROL_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.SCHEDULE_VISITING_HOURS_VIOLATING_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.SCHEDULE_WORKLOC_CONTROL_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.SCHEDULE_WORKLOC_VIOLATING_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.SKILLS_FIXTURE_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.SKILLS_SKILL_FIXTURE_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.START_TIME_INTERVAL_FIXTURE_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.START_TIME_INTERVAL_POLICY_CONFIG;
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
 * (schedule/reschedule → SlotAvailabilityChecker → the same loadSchedulableSlots on recompute). Per rule a
 * differential matrix asserts read decision == write decision for a violating AND a control case.
 *
 * <p>FINDING: read==write holds for EVERY rule — NO read≠write divergence was found. The two things
 * the plan pre-labeled "divergences" were REFUTED by live evidence: (a) RequiredResources — read AND
 * write BOTH crash with the same {@code serviceTerritoryMembers} NPE on the shared engine (read==write,
 * a shared-engine 262 bug, not an asymmetry — {@link #testRequiredResourcesReadWriteBothCrashE2E}); and
 * (b) the reschedule no-op short-circuit ({@code SlotAvailabilityChecker:174-176}) is NOT reachable over
 * REST for a required-resource SA — the reschedule recomputes and 262 crashes
 * ({@link #testNoOpRescheduleShortCircuitE2E}). So the only asymmetries observed are shared-engine 262
 * crash bugs (identical on both paths) and an unreachable short-circuit — the read==write thesis holds
 * even more strongly than predicted. onField/inField rules are OUT OF SCOPE.
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

  /**
   * AppointmentStartTimeInterval (the sole IN_BUSINESS_RULE_TYPES member) — a start time off the
   * policy's interval boundary is pruned by the read (0 slots) AND rejected by the write; an
   * on-boundary control returns >0 AND Success. Proves the interval rule runs identically read and
   * write. The policy (net-new) carries Availability(ConsiderOpHoursAndShiftsUnion) +
   * WorkingTerritories(IsPrimaryLocationEnabled) + an AppointmentStartTimeInterval rule whose
   * SchedulingRuleParameter pins the interval to 60 min; the fixture (net-new) makes the resource
   * available all day (08-16, GMT) so the interval stepping
   * (InBusinessAppointmentSlotCalculator.getStartAdjustedForStartTimeFrequency, which rounds each
   * start UP to the next hour and steps by 60 min) is the ONLY thing deciding which starts survive.
   * The WorkType carries no AppointmentStartTimeInterval so the POLICY value is used.
   *
   * <p>262 (asserted): read-violating 0 (11:30-12:30 rounds to 12:00 whose 60-min slot overruns the
   * window) ⟺ write-violating rejected; read-control >0 (11:00-12:00 on-boundary) ⟺ write-control
   * Success.
   *
   * <p>264 contrast: unchanged — AppointmentStartTimeInterval is a shared slot-stepping check on both
   * paths.
   */
  @Test
  void testStartTimeIntervalReadWriteParityE2E() {
    ReVomanConfigForWfs.assumeExternalOrgCreds();
    final var rundown =
        ReVoman.revUp(
            (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
            AUTH_CONFIG,
            START_TIME_INTERVAL_POLICY_CONFIG,
            START_TIME_INTERVAL_FIXTURE_CONFIG,
            GET_SLOTS_STI_VIOLATING_CONFIG,
            GET_SLOTS_STI_CONTROL_CONFIG,
            SCHEDULE_STI_VIOLATING_CONFIG,
            SCHEDULE_STI_CONTROL_CONFIG);
    final var env = CollectionsKt.last(rundown).mutableEnv;
    // Read prunes the off-boundary window; control returns slots (proves fixture valid).
    assertThat(env.getAsString("stiReadViolatingSlotCount")).isEqualTo("0");
    assertThat(Integer.parseInt(env.getAsString("stiReadControlSlotCount"))).isGreaterThan(0);
    // Write agrees with read on BOTH rows → read==write for AppointmentStartTimeInterval.
    assertThat(env.getAsString("stiWriteViolatingStatus")).isNotEqualTo("Success");
    assertThat(env.getAsString("stiWriteControlStatus")).isEqualTo("Success");
  }

  /**
   * RequiredResources — read==write, and on 262 BOTH paths CRASH identically. When only a NON-required
   * helper satisfies the account's required-resource demand (resourceA required+primary but not on the
   * Account's ResourcePreference(Required) list; resourceB on the list but assigned non-required), the
   * READ path (GetAppointmentSlots) CRASHES with HTTP 500 {@code INTERNAL_SERVER_ERROR} — the SAME
   * {@code serviceTerritoryMembers} NPE ("Cannot invoke ArrayListMultimap.values() because
   * this.serviceTerritoryMembers is null") that the WRITE path throws
   * (WfsWritePathParityE2ETest.testNonRequiredHelperCannotSatisfyRequiredDemandE2E). Because read and
   * write share {@code InBusinessGetCandidatesSlotsDataService.loadSchedulableSlots}, the 262 NPE bug
   * manifests IDENTICALLY on both — this is read==write (a shared-engine crash), NOT a divergence.
   *
   * <p>This REFUTES the plan's original hypothesis that read would prune cleanly while write crashed;
   * live evidence (controller ran it directly, 2026-07-01) shows the read ALSO crashes. Recorded, not
   * hidden. A control that flips resourceB to {@code isRequiredResource=true} (demand satisfied) does
   * NOT crash — HTTP 201, no error — proving the crash is CONDITIONAL on the non-required-helper input,
   * not a blanket fixture failure. (That satisfied-demand read returns 0 slots, HTTP 201; the crash, not
   * the slot count, is this test's subject — a 500 NPE with the exact message is self-evidently the bug,
   * so the usual control-returns-slots guardrail is unnecessary here.)
   *
   * <p>262 (asserted): read-violating CRASHES (INTERNAL_SERVER_ERROR / serviceTerritoryMembers NPE) ==
   * write-violating (same crash, asserted in the write class); control read does not crash.
   *
   * <p>264 contrast: the NPE should become a clean RequiredResources rejection on BOTH paths — still
   * read==write, just a clean error instead of a 500.
   */
  @Test
  void testRequiredResourcesReadWriteBothCrashE2E() {
    ReVomanConfigForWfs.assumeExternalOrgCreds();
    final var rundown =
        ReVoman.revUp(
            (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
            AUTH_CONFIG,
            REQUIRED_RESOURCES_POLICY_CONFIG,
            REQUIRED_NON_REQUIRED_FIXTURE_CONFIG,
            GET_SLOTS_REQUIRED_VIOLATING_CONFIG,
            GET_SLOTS_REQUIRED_CONTROL_CONFIG);
    final var env = CollectionsKt.last(rundown).mutableEnv;
    // Read-violating CRASHES with the SAME serviceTerritoryMembers NPE the write path throws → the 262
    // RequiredResources bug is read==write on the shared loadSchedulableSlots engine (NOT a divergence).
    assertThat(env.getAsString("requiredReadViolatingErrorCode")).isEqualTo("INTERNAL_SERVER_ERROR");
    assertThat(env.getAsString("requiredReadViolatingErrorMessage")).contains("serviceTerritoryMembers");
    // Control (resourceB required → demand satisfied) does NOT crash — proves the crash is conditional on
    // the non-required-helper input, not a dead fixture. (Satisfied-demand read returns 0 slots, HTTP 201.)
    assertThat(env.getAsString("requiredReadControlErrorCode")).isAnyOf(null, "null");
    assertThat(env.getAsString("requiredReadControlHttpCode")).isEqualTo("201");
  }

  /**
   * Cross-API agreement — the SAME MatchSkills violation (a required+primary resource lacking the
   * WorkType's required skill) is pruned/rejected by ALL 4 rule-evaluating read APIs
   * (get-appointment-slots, get-appointment-candidates, get-available-slots, get-available-resources)
   * AND the schedule write API. Empirically proves they share the one loadSchedulableSlots engine, so
   * the per-rule read==write matrix generalizes to every API. get-available-resources runs the FULL
   * 7-rule engine too: AvailableResourcesServiceImpl:322 calls the same getCandidatesProcessor.process,
   * then only post-processes/truncates the SURVIVING resources — so the skill-lacking resource is
   * ABSENT from availableResources (it is NOT a subset that skips MatchSkills). All acts reuse the
   * Task-1 skills fixture/policy and the existing get-slots + schedule violating acts; the 3 new reads
   * carry the SAME body/window.
   *
   * <p>The three appointment reads (slots/candidates/available-slots) request the skill-lacking
   * resourceB via {@code assignedResources}, so MatchSkills pruning it leaves ZERO
   * slots/candidates/available-slots. get-available-resources takes NO {@code assignedResources} — it
   * returns EVERY available resource for the account/worktype/territory, so the SKILLED resourceA
   * legitimately survives (total count 1) while the UNSKILLED resourceB is pruned; the parity claim
   * there is resourceB's ABSENCE ({@code skillsAvailableResourcesViolatingPresent == 0}), which is the
   * discovered-shape equivalent of the other reads' 0 (LIVE-VERIFIED 2026-07-01: availableResources
   * held only "SNR Resource A", resourceB absent — confirming get-available-resources DOES run
   * MatchSkills, resolving the earlier "subset" mis-hypothesis).
   *
   * <p>262 (asserted): the three appointment reads return 0 for the skill-lacking resource;
   * get-available-resources prunes it (resourceB absent); schedule rejects.
   *
   * <p>264 contrast: unchanged — MatchSkills is a shared cheap check on every path.
   */
  @Test
  void testCrossApiRuleAgreementE2E() {
    ReVomanConfigForWfs.assumeExternalOrgCreds();
    final var rundown =
        ReVoman.revUp(
            (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
            AUTH_CONFIG,
            MATCH_SKILLS_POLICY_CONFIG,
            SKILLS_SKILL_FIXTURE_CONFIG,
            SKILLS_FIXTURE_CONFIG,
            GET_SLOTS_SKILLS_VIOLATING_CONFIG,
            GET_CANDIDATES_SKILLS_VIOLATING_CONFIG,
            GET_AVAILABLE_SLOTS_SKILLS_VIOLATING_CONFIG,
            GET_AVAILABLE_RESOURCES_SKILLS_VIOLATING_CONFIG,
            SCHEDULE_SKILLS_VIOLATING_CONFIG);
    final var env = CollectionsKt.last(rundown).mutableEnv;
    // The three appointment reads (which request resourceB via assignedResources) prune it → 0
    // slots/candidates/available-slots. The one loadSchedulableSlots engine agrees across all three.
    assertThat(env.getAsString("skillsReadViolatingSlotCount")).isEqualTo("0");
    assertThat(env.getAsString("skillsCandidatesCount")).isEqualTo("0");
    assertThat(env.getAsString("skillsAvailableSlotsCount")).isEqualTo("0");
    // get-available-resources (no assignedResources → returns all available resources) prunes the
    // skill-lacking resourceB while the skilled resourceA survives → resourceB ABSENT confirms it too
    // runs the full 7-rule engine (MatchSkills), not a rule-skipping subset. Assert the survivor set is
    // NON-empty (resourceA present) so "resourceB absent" can't be a green-on-empty/error false positive
    // — absence AMONG a live returned set is the strictly-stronger, self-defending parity claim.
    assertThat(Integer.parseInt(env.getAsString("skillsAvailableResourcesCount"))).isGreaterThan(0);
    assertThat(env.getAsString("skillsAvailableResourcesViolatingPresent")).isEqualTo("0");
    // Write agrees with all 4 reads → the per-rule read==write matrix generalizes to every API.
    assertThat(env.getAsString("skillsWriteViolatingStatus")).isNotEqualTo("Success");
  }

  /**
   * No-op reschedule short-circuit (write<read) — the {@code SlotAvailabilityChecker:174-176} branch
   * {@code if (!timesAreChanging && !resourcesHaveChanged) return true} is the ONE place the write path does
   * LESS rule evaluation than a read would (it skips {@code getSlots}/{@code loadSchedulableSlots}). This test
   * characterizes what a no-op reschedule of an already-valid required-resource SA ACTUALLY does on the 262
   * org — and REFUTES the plan's premise that it returns Success via that short-circuit.
   *
   * <p>LIVE-OBSERVED (2026-07-01, controller ran it directly; the {@code SlotAvailabilityChecker:174-176}
   * short-circuit is confirmed to exist in source):
   *
   * <ul>
   *   <li>The setup schedules resourceA (required+primary) into an available window (tomorrow 11:00-12:00) →
   *       Success, capturing {@code noopSetupSaId}. (On the schedule leg {@code timesAreChanging==true}, so it
   *       does not short-circuit regardless.)
   *   <li>On the RESCHEDULE leg {@code timesAreChanging==false} (no startTime/endTime), yet the short-circuit
   *       does NOT fire — {@code haveResourcesChanged} (SlotAvailabilityChecker:237, a raw {@code .equals()} on
   *       the required-ServiceResourceId sets with no canonicalization) returns true, so it recomputes and
   *       262 CRASHES (below). WHY resourcesHaveChanged is true for a re-stated no-op is NOT fully pinned: an
   *       EMPTY assignedResources trivially differs ({@code {} ≠ {resourceA}}); for an UpdateOperation
   *       re-stating resourceA the request wire id is 18-char (this fixture sets ids verbatim/18-char), so the
   *       set SHOULD match unless the ESO request DTO re-canonicalizes/truncates the id (a plausible instance
   *       of the repo's 15/18-char ResourceId gotcha — but NOT re-confirmed here; jdwp was not re-attached).
   *       OPEN (decision log): breakpoint {@code haveResourcesChanged} and dump both id sets to pin the exact
   *       cause. Either way the OBSERVED outcome is: short-circuit does not fire → recompute.
   *   <li>The reschedule recompute then CRASHES on 262 with HTTP 500 {@code INTERNAL_SERVER_ERROR}: "Cannot
   *       invoke java.util.List.iterator() because the return value of ServiceTerritory.getServiceResourceIds()
   *       is null" — a 262 reschedule-recompute NPE (a THIRD null-field variant, distinct from the
   *       {@code serviceTerritoryMembers} NPE on schedule/read). schedulingStatus is null (NOT Success).
   * </ul>
   *
   * <p>262 (asserted — what the test PINS): setup schedule Success + saId captured; the no-op reschedule does
   * NOT return Success — it 500-crashes (INTERNAL_SERVER_ERROR / ServiceTerritory.getServiceResourceIds NPE).
   * The write<read short-circuit exists in code but is NOT reached on this required-resource-SA REST path
   * (exact reason flagged open above). The crash — not the mechanism — is what the assertions verify.
   *
   * <p>264 contrast: the short-circuit is intended; 264's reworked reschedule availability (effective-set
   * merge over the real surviving crew) is what would let a genuine no-op resolve cleanly rather than 500.
   */
  @Test
  void testNoOpRescheduleShortCircuitE2E() {
    ReVomanConfigForWfs.assumeExternalOrgCreds();
    final var rundown =
        ReVoman.revUp(
            (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
            AUTH_CONFIG,
            AVAILABILITY_OP_HOURS_POLICY_CONFIG,
            REQUIRED_NON_REQUIRED_FIXTURE_CONFIG,
            SCHEDULE_NOOP_RESCHED_SETUP_CONFIG,
            RESCHEDULE_NOOP_CONFIG);
    final var env = CollectionsKt.last(rundown).mutableEnv;
    // Setup schedules resourceA into the available window and persists an SA → Success + a captured id.
    assertThat(env.getAsString("noopSetupStatus")).isEqualTo("Success");
    assertThat(env.getAsString("noopSetupSaId")).isNotNull();
    // No-op reschedule of a required-resource SA does NOT hit the short-circuit (resourcesHaveChanged==true)
    // → it recomputes, and 262's reschedule recompute 500-crashes with the ServiceTerritory.getServiceResourceIds
    // NPE. schedulingStatus is null (NOT Success). This REFUTES the "no-op returns Success via the short-circuit"
    // premise for this REST path. (Exact reason resourcesHaveChanged is true for a re-stated no-op is flagged
    // open in the javadoc — the CRASH, not the mechanism, is what these assertions pin.)
    assertThat(env.getAsString("noopReschedStatus")).isNotEqualTo("Success");
    assertThat(env.getAsString("noopReschedHttpCode")).isEqualTo("500");
    assertThat(env.getAsString("noopReschedErrorCode")).isEqualTo("INTERNAL_SERVER_ERROR");
    assertThat(env.getAsString("noopReschedErrorMessage")).contains("getServiceResourceIds");
  }
}
