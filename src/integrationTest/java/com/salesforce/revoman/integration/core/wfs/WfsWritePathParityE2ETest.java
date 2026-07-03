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
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.EXCLUDED_FIXTURE_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.EXCLUDED_RESOURCES_POLICY_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.EXCLUDED_SCHEDULE_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.MATCH_SKILLS_POLICY_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.MISSING_REQUIRED_FLAG_SCHEDULE_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.REQUIRED_NON_REQUIRED_FIXTURE_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.REQUIRED_NON_REQUIRED_SATISFIER_VIOLATING_SCHEDULE_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.REQUIRED_RESOURCES_POLICY_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.REQUIRED_SATISFIER_BOOKABLE_SCHEDULE_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.RESCHEDULE_DELETE_ALL_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.RESCHEDULE_DELETE_ALL_WITH_POLICY_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.RESCHEDULE_DELETE_PRIMARY_NO_FLAG_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.RESCHEDULE_DELETE_PRIMARY_WITH_FLAG_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.RESCHEDULE_DEMOTE_PRIMARY_TWO_CREW_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.SCHEDULE_PRIMARY_NOT_REQUIRED_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.SCHEDULE_PRIMARY_REQUIRED_CONTROL_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.SCHEDULE_TWO_PRIMARY_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.SCHEDULE_TWO_RESOURCE_CLEAN_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.SINGLE_REQUIRED_NO_PRIMARY_SCHEDULE_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.SKILLS_FIXTURE_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.SKILLS_SCHEDULE_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.SKILLS_SKILL_FIXTURE_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.TERRITORY_FIXTURE_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.TERRITORY_PARTIAL_POLICY_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.TERRITORY_SCHEDULE_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.WORKING_LOCATIONS_FIXTURE_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.WORKING_LOCATIONS_SCHEDULE_CONFIG;

import com.salesforce.revoman.ReVoman;
import com.salesforce.revoman.input.config.Kick;
import kotlin.collections.CollectionsKt;
import org.junit.jupiter.api.Test;

/**
 * WFS read↔write parity write-path characterization (live 262). Supersedes WfsHelperFitnessE2ETest
 * (Decision 1) and WfsDoubleBookHelperE2ETest (Decision 1.5).
 *
 * <p>Decisions covered: 1 (helper fitness), 1.4 (helper can't satisfy a required-resource demand),
 * 1.5 (helper double-books), 3 (missing isRequiredResource flag + the L142
 * single-required-no-primary control). Each scenario is its own {@code ReVoman.revUp(...)} starting
 * with {@code AUTH_CONFIG} (fresh env + fresh timestamped users → no ServiceResource
 * (RelatedRecordId, ResourceType) collision).
 */
class WfsWritePathParityE2ETest {

  /**
   * Decision 1 — a NON-required "helper" resource is NOT fitness-checked on the Schedule write
   * path, across four dimensions (EXCLUDED / TERRITORY / SKILLS / WORKING-LOCATIONS). Each
   * dimension is a clean required+primary resourceA plus a NON-required resourceB violating exactly
   * one rule.
   *
   * <p>262 (asserted): the helper escapes every fitness rule → each dimension books Success.
   *
   * <p>Approach A: each dimension is its own revUp starting with AUTH_CONFIG → fresh env + fresh
   * timestamped users, so the four dimensions' ServiceResource(RelatedRecordId, ResourceType) rows
   * never collide (the SR-uniqueness collision that made the old single-revUp run roll back dims
   * 2-4).
   */
  @Test
  void testNonRequiredHelperFitnessE2E() {
    ReVomanConfigForWfs.assumeExternalOrgCreds();
    assertDimensionBooksSuccess(
        "excludedNonReqSchedulingStatus",
        AUTH_CONFIG,
        EXCLUDED_RESOURCES_POLICY_CONFIG,
        EXCLUDED_FIXTURE_CONFIG,
        EXCLUDED_SCHEDULE_CONFIG);
    assertDimensionBooksSuccess(
        "territoryNonReqSchedulingStatus",
        AUTH_CONFIG,
        TERRITORY_PARTIAL_POLICY_CONFIG,
        TERRITORY_FIXTURE_CONFIG,
        TERRITORY_SCHEDULE_CONFIG);
    assertDimensionBooksSuccess(
        "skillsNonReqSchedulingStatus",
        AUTH_CONFIG,
        MATCH_SKILLS_POLICY_CONFIG,
        SKILLS_SKILL_FIXTURE_CONFIG,
        SKILLS_FIXTURE_CONFIG,
        SKILLS_SCHEDULE_CONFIG);
    assertDimensionBooksSuccess(
        "workingLocationsSecondaryNonReqSchedulingStatus",
        AUTH_CONFIG,
        AVAILABILITY_OP_HOURS_POLICY_CONFIG,
        WORKING_LOCATIONS_FIXTURE_CONFIG,
        WORKING_LOCATIONS_SCHEDULE_CONFIG);
  }

  /**
   * Decision 1.5 — a NON-required helper is NOT availability-checked, so it may double-book. The
   * A/B flips ONLY isRequiredResource on resourceB over the SAME fixture (resourceB is BUSY at the
   * window).
   *
   * <p>262 (asserted): the busy NON-required helper books Success (no availability check). The
   * REQUIRED control (isRequiredResource=true) is availability-checked → not-Success.
   */
  @Test
  void testNonRequiredHelperDoubleBooksE2E() {
    ReVomanConfigForWfs.assumeExternalOrgCreds();
    final var rundown =
        ReVoman.revUp(
            (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
            AUTH_CONFIG,
            AVAILABILITY_OP_HOURS_POLICY_CONFIG,
            DOUBLE_BOOK_FIXTURE_CONFIG,
            DOUBLE_BOOK_NON_REQUIRED_SCHEDULE_CONFIG,
            DOUBLE_BOOK_REQUIRED_CONFLICT_SCHEDULE_CONFIG);
    final var env = CollectionsKt.last(rundown).mutableEnv;
    assertThat(env).containsEntry("doubleBookNonRequiredSchedulingStatus", "Success");
    assertThat(env.getAsString("doubleBookRequiredControlSchedulingStatus"))
        .isNotEqualTo("Success");
  }

  /**
   * Decision 1.4 — a NON-required helper CANNOT satisfy an account's required-resource demand
   * (ResourcePreference Required). resourceA (required+primary) is clean but NOT on the account's
   * required list; resourceB IS on the required list but is assigned NON-required.
   *
   * <p>262 (asserted): the violating booking CRASHES with errorCode=INTERNAL_SERVER_ERROR — a
   * server NPE ("Cannot invoke ...ArrayListMultimap.values() because this.serviceTerritoryMembers
   * is null"), NOT the predicted clean ScheduleError errorCode=RequiredResources (a 262 crash bug).
   * The control (resourceB flipped to isRequiredResource=true) is rejected with NO
   * RequiredResources error and no crash (availability may still block it — INVALID_INPUT
   * not-available).
   *
   * <p>Approach A: two SEPARATE revUps, each starting with AUTH_CONFIG → fresh env + fresh
   * timestamped users, so the violating and control fixtures' ServiceResource(RelatedRecordId,
   * ResourceType) rows never collide.
   */
  @Test
  void testNonRequiredHelperCannotSatisfyRequiredDemandE2E() {
    ReVomanConfigForWfs.assumeExternalOrgCreds();
    // Violating: only a NON-required helper present for the account's required demand → 262 server
    // NPE.
    final var violatingRundown =
        ReVoman.revUp(
            (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
            AUTH_CONFIG,
            REQUIRED_RESOURCES_POLICY_CONFIG,
            REQUIRED_NON_REQUIRED_FIXTURE_CONFIG,
            REQUIRED_NON_REQUIRED_SATISFIER_VIOLATING_SCHEDULE_CONFIG);
    final var violatingEnv = CollectionsKt.last(violatingRundown).mutableEnv;
    assertThat(violatingEnv.getAsString("requiredNonReqSatisfierErrorCode"))
        .isEqualTo("INTERNAL_SERVER_ERROR");
    assertThat(violatingEnv.getAsString("requiredNonReqSatisfierErrorMessage"))
        .contains("serviceTerritoryMembers");
    // Control: a genuine required satisfier → no RequiredResources error (fresh AUTH per Approach
    // A).
    final var controlRundown =
        ReVoman.revUp(
            (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
            AUTH_CONFIG,
            REQUIRED_RESOURCES_POLICY_CONFIG,
            REQUIRED_NON_REQUIRED_FIXTURE_CONFIG,
            REQUIRED_SATISFIER_BOOKABLE_SCHEDULE_CONFIG);
    final var controlEnv = CollectionsKt.last(controlRundown).mutableEnv;
    assertThat(controlEnv.getAsString("requiredSatisfierControlErrorCode"))
        .isNotEqualTo("RequiredResources");
  }

  /**
   * Decision 3 — a missing isRequiredResource flag, plus the doc L142 single-required-no-primary
   * control. Act A schedules a SINGLE assigned resource that OMITS isRequiredResource entirely (and
   * isPrimaryResource), exercising the missing-flag code path. Act B (doc L142 control) sends a
   * single isRequiredResource=true with NO isPrimaryResource on FRESH resources, which MUST be a
   * valid Schedule (isPrimaryResource is multi-resource-only plumbing, NOT the Decision-1
   * variable). Both use AVAILABILITY_OP_HOURS_POLICY_CONFIG (Availability + WorkingTerritories, NO
   * RequiredResources rule) over the required-non-required fixture, so the RequiredResources rule's
   * Task-4 NPE does not confound the missing-flag probe.
   *
   * <p>262 (asserted): Act A CRASHES with errorCode=INTERNAL_SERVER_ERROR — a server NPE ("Cannot
   * invoke \"java.lang.Boolean.booleanValue()\" because the return value of
   * \"common.api.soap.Entity.getField(String)\" is null"), the 262 missing-flag NPE the doc
   * predicts. Act B → schedulingStatus=Success.
   *
   * <p>Approach A: two SEPARATE revUps, each starting with AUTH_CONFIG → fresh env + fresh
   * timestamped users, so the two acts' ServiceResource(RelatedRecordId, ResourceType) rows never
   * collide. Both acts carry {@code ignoreHTTPStatusUnsuccessful: "true"} so {@code
   * firstUnIgnoredUnsuccessfulStepReport()} stays null.
   */
  @Test
  void testMissingRequiredFlagE2E() {
    ReVomanConfigForWfs.assumeExternalOrgCreds();
    // Act A: a single assigned resource that OMITS isRequiredResource → 262 missing-flag NPE crash.
    final var missingFlagRundown =
        ReVoman.revUp(
            (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
            AUTH_CONFIG,
            AVAILABILITY_OP_HOURS_POLICY_CONFIG,
            REQUIRED_NON_REQUIRED_FIXTURE_CONFIG,
            MISSING_REQUIRED_FLAG_SCHEDULE_CONFIG);
    final var missingFlagEnv = CollectionsKt.last(missingFlagRundown).mutableEnv;
    assertThat(missingFlagEnv.getAsString("missingRequiredFlagErrorCode"))
        .isEqualTo("INTERNAL_SERVER_ERROR");
    assertThat(missingFlagEnv.getAsString("missingRequiredFlagErrorMessage"))
        .contains("Boolean.booleanValue()");
    // Act B (doc L142): single isRequiredResource=true, NO isPrimaryResource, FRESH resources →
    // valid.
    final var l142Rundown =
        ReVoman.revUp(
            (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
            AUTH_CONFIG,
            AVAILABILITY_OP_HOURS_POLICY_CONFIG,
            REQUIRED_NON_REQUIRED_FIXTURE_CONFIG,
            SINGLE_REQUIRED_NO_PRIMARY_SCHEDULE_CONFIG);
    assertThat(CollectionsKt.last(l142Rundown).mutableEnv)
        .containsEntry("singleRequiredNoPrimaryStatus", "Success");
  }

  /**
   * Decision 4 — TWO assigned resources both {@code isPrimaryResource=true} are rejected UP FRONT
   * at input validation ({@code ScheduleCommonValidator.validatePrimaryResourceConstraints}),
   * before any availability/persist. Multi-resource scheduling requires EXACTLY one primary.
   *
   * <p>262 (asserted): the live org returns a clean top-level {@code ConnectErrorCode
   * INVALID_INPUT} / HTTP 400 with message "Only one of the provided assigned resource can be a
   * primary resource." No booking occurs (the throw is in {@code validatePayload}, so {@code
   * appointments[0]} is absent → status null). Per the product doc the 262 ERA was thought to
   * produce a "confusing database error", but the CURRENT 262 code already returns this clean
   * message.
   */
  @Test
  void testTwoPrimaryResourcesRejectedE2E() {
    ReVomanConfigForWfs.assumeExternalOrgCreds();
    final var rundown =
        ReVoman.revUp(
            (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
            AUTH_CONFIG,
            AVAILABILITY_OP_HOURS_POLICY_CONFIG,
            REQUIRED_NON_REQUIRED_FIXTURE_CONFIG,
            SCHEDULE_TWO_PRIMARY_CONFIG);
    final var env = CollectionsKt.last(rundown).mutableEnv;
    // Clean input-validation reject: INVALID_INPUT, "can be a primary resource" message (rules out
    // a
    // look-alike availability INVALID_INPUT, which has a different message), HTTP 400, no booking.
    assertThat(env.getAsString("twoPrimaryErrorCode")).isEqualTo("INVALID_INPUT");
    assertThat(env.getAsString("twoPrimaryErrorMessage")).contains("can be a primary resource");
    assertThat(env.getAsString("twoPrimaryHttpCode")).isEqualTo("400");
    assertThat(env.getAsString("twoPrimaryStatus")).isAnyOf(null, "null");
  }

  /**
   * Decision 5 — a single resource marked {@code isPrimaryResource=true} but {@code
   * isRequiredResource=false} is REJECTED, NOT auto-corrected and NOT silently double-booked. Input
   * validation counts primaries only (it never inspects the required flag), so the contradictory
   * payload passes validation and reaches PERSIST, where {@code
   * LightningSchedulerAssignedResourceValidator} rejects it. The window is FREE, so availability
   * passes and the persist reject is what surfaces.
   *
   * <p>262 (asserted): the probe is rejected with schedulingStatus=PersistError, errorCode={@code
   * INVALID_FIELD}, message "Only an required service resource can be set as a primary service
   * resource." — the live shape is now PINNED to {@code appointments[0].errors[0]} /
   * schedulingStatus=PersistError / HTTP 201 (the capture script still probes both shapes, but the
   * live response uses the per-appointment one). The required primary CONTROL over the same
   * resource/window books Success. This REFUTES both the story's "quietly fixed to required"
   * auto-correct expectation AND the 262 "could be double-booked" claim: the request is rejected AT
   * PERSIST, so no ServiceAppointment is created, hence no double-book — INFERRED from the persist
   * reject (no SA exists to query), not independently observed via an SA-count read.
   *
   * <p>Approach A: two SEPARATE revUps so the probe's (rejected, non-persisting) attempt and the
   * control's (persisting) booking never share ServiceResource rows.
   */
  @Test
  void testPrimaryNotRequiredRejectedE2E() {
    ReVomanConfigForWfs.assumeExternalOrgCreds();
    // Probe: primary + NOT required, free window → rejected at persist (not Success, not
    // double-booked).
    final var probeRundown =
        ReVoman.revUp(
            (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
            AUTH_CONFIG,
            AVAILABILITY_OP_HOURS_POLICY_CONFIG,
            REQUIRED_NON_REQUIRED_FIXTURE_CONFIG,
            SCHEDULE_PRIMARY_NOT_REQUIRED_CONFIG);
    final var probeEnv = CollectionsKt.last(probeRundown).mutableEnv;
    assertThat(probeEnv.getAsString("primaryNotReqStatus")).isEqualTo("PersistError");
    assertThat(probeEnv.getAsString("primaryNotReqErrorCode")).isEqualTo("INVALID_FIELD");
    // Pin to the primary-must-be-required persist rule ("Only an required service resource can be
    // set as a primary service resource.") — "primary service resource" is the safe substring.
    assertThat(probeEnv.getAsString("primaryNotReqErrorMessage"))
        .contains("primary service resource");
    // Control: primary + required, same resource/window → Success (fresh AUTH per Approach A).
    final var controlRundown =
        ReVoman.revUp(
            (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
            AUTH_CONFIG,
            AVAILABILITY_OP_HOURS_POLICY_CONFIG,
            REQUIRED_NON_REQUIRED_FIXTURE_CONFIG,
            SCHEDULE_PRIMARY_REQUIRED_CONTROL_CONFIG);
    assertThat(CollectionsKt.last(controlRundown).mutableEnv)
        .containsEntry("primaryReqControlStatus", "Success");
  }

  /**
   * Decision 4z — there is NO "reschedule must keep a primary" rule (so the reschedule API does NOT
   * forbid leaving an appointment with no primary — the product doc's blanket "not possible to
   * schedule or reschedule without a primary" is WRONG); on this 262 org the delete-primary
   * reschedule is then blocked only by a SEPARATE downstream availability gap, never by a primary
   * rule. The doc conflates two independent rules. One revUp chains a clean two-resource Schedule
   * (captures the SA id) then two reschedule arms over that SA.
   *
   * <p>262 (asserted): the clean Schedule books Success. Arm A — {@code DeleteOperation} on the
   * primary WITH {@code isPrimaryResource:true} on the delete entry — is rejected ({@code
   * INVALID_INPUT}, HTTP 400, "isPrimaryResource cannot be set for Delete"): a PAYLOAD-FIELD guard
   * ({@code RescheduleCommonValidator.validateDeleteOperationFields}), not a crew rule, so it does
   * NOT mutate the SA. Arm B — {@code DeleteOperation} on the primary WITHOUT the flag — is NOT
   * rejected by any primary rule: {@code validatePrimaryResourceCount} only throws {@code
   * MultiplePrimary} when {@code primaryCount > 1} ("allow zero primaries for reschedule"), so
   * there is NO "must keep a primary" rule — which is what refutes the doc. The Core func test
   * {@code testRescheduleAppointmentDeleteAllAssignedResources} relies on the same allow-zero rule.
   *
   * <p>LIVE-OBSERVED on the 262 org under test (deviation from the brief's expected Arm-B Success —
   * see Step 10 decision log): Arm B is NOT Success; it is rejected with {@code INVALID_INPUT} /
   * HTTP 400 "The service resources are not available for the requested slot." — a DOWNSTREAM
   * AVAILABILITY re-check ({@code SlotNotAvailable}), never a primary-count / required-primary
   * error. On 262 the reschedule slot-gen yields no slot for the surviving non-primary crew. The
   * doc is still refuted at the validation layer (zero primaries is allowed); the test asserts the
   * OBSERVED rejection faithfully (availability, NOT no-primary) rather than forcing a Success this
   * org cannot produce. Adding {@code startTime}/{@code endTime} (Step 10 option 2) and switching
   * to delete-ALL (Step 10 option 3) were both tried live and both still returned the same {@code
   * SlotNotAvailable}, confirming the 262 availability-path gap.
   *
   * <p>The product OPEN QUESTION (should a reschedule be allowed to leave no primary?) is a
   * DECISION, not a code bug: the primary-count validator already allows it.
   */
  @Test
  void testRescheduleNoPrimaryE2E() {
    ReVomanConfigForWfs.assumeExternalOrgCreds();
    final var rundown =
        ReVoman.revUp(
            (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
            AUTH_CONFIG,
            AVAILABILITY_OP_HOURS_POLICY_CONFIG,
            REQUIRED_NON_REQUIRED_FIXTURE_CONFIG,
            SCHEDULE_TWO_RESOURCE_CLEAN_CONFIG,
            RESCHEDULE_DELETE_PRIMARY_WITH_FLAG_CONFIG,
            RESCHEDULE_DELETE_PRIMARY_NO_FLAG_CONFIG);
    final var env = CollectionsKt.last(rundown).mutableEnv;
    // Setup booked, and the SA id was captured for the reschedule arms.
    assertThat(env.getAsString("reschedCleanStatus")).isEqualTo("Success");
    assertThat(env.getAsString("reschedCleanSaId")).isNotNull();
    // Arm A: isPrimaryResource on a Delete entry → clean INVALID_INPUT (payload-field guard), HTTP
    // 400.
    assertThat(env.getAsString("reschedWithFlagErrorCode")).isEqualTo("INVALID_INPUT");
    assertThat(env.getAsString("reschedWithFlagHttpCode")).isEqualTo("400");
    // Arm B: delete the primary, no flag → rejected ONLY by the downstream availability re-check
    // (SlotNotAvailable), NEVER by a no-primary rule (on 262 the reschedule slot-gen yields no slot
    // for the surviving non-primary crew).
    // This is what refutes the doc: there is no "must keep a primary" validation. Characterized
    // faithfully.
    assertThat(env.getAsString("reschedNoFlagStatus")).isNotEqualTo("Success");
    assertThat(env.getAsString("reschedNoFlagErrorCode")).isEqualTo("INVALID_INPUT");
    assertThat(env.getAsString("reschedNoFlagHttpCode")).isEqualTo("400");
    assertThat(env.getAsString("reschedNoFlagErrorMessage"))
        .contains("not available for the requested slot");
  }

  /**
   * Decision 4z — delete-ALL reschedule with NO {@code schedulingPolicyId} (falls back to the org
   * DEFAULT OnSite policy). Deletes BOTH assigned resources (resourceA + resourceB) with {@code
   * DeleteOperation} and NO {@code isPrimaryResource} flag — the empty-crew case that the sibling
   * {@link #testRescheduleNoPrimaryE2E} Arm B (delete the primary, keep a REQUIRED secondary) could
   * not reach. This is the POLICY-LESS half of the reconciling pair; see {@link
   * #testRescheduleDeleteAllWithPolicySucceedsE2E} for the twin that supplies a good policy and
   * SUCCEEDS.
   *
   * <p>The brief's HYPOTHESIS (from a static code read + the Core func test {@code
   * OnSiteRescheduleAppointmentsConnectApiTest.testRescheduleAppointmentDeleteAllAssignedResources},
   * which asserts Success): the availability re-check ({@code
   * SlotAvailabilityChecker.isSlotAvailable}) computes its required-resource set via {@code
   * extractServiceResourceIds}, which SKIPS {@code DeleteOperation} entries → deleting ALL resources
   * yields an EMPTY required set → the availability check runs against no resources and PASSES → the
   * reschedule SUCCEEDS with an empty crew (no primary), proving Decision 4z end-to-end.
   *
   * <p>262 (LIVE-OBSERVED): the clean two-resource Schedule books Success (captures {@code
   * reschedCleanSaId}), but this POLICY-LESS delete-ALL reschedule is NOT Success — it is rejected
   * with {@code INVALID_INPUT} / HTTP 400 / top-level "The service resources are not available for
   * the requested slot." ({@code SlotNotAvailable}), {@code schedulingStatus == null}, the SAME
   * downstream availability re-check that blocks Arm B.
   *
   * <p>ROOT CAUSE (live-isolated, one-variable runs + live tooling query — NOT a release gate): the
   * rejection is caused by the resolved SCHEDULING POLICY's RULE SET, not by the release and not by the
   * request times. With no {@code schedulingPolicyId} on the reschedule, {@code
   * SlotAvailabilityChecker.buildGetSlotsRequest} carries a null policy and {@code
   * InBusinessGetSlotsHandler.getSchedulingPolicyConfiguration} falls back to {@code
   * getDefaultOnSiteSchedulingPolicy} ({@code DefaultOnSiteSchdPlcy}). That default policy has 7 rules —
   * including a {@code RequiredResources} rule — whereas the good policy
   * ({@code availabilityOpHoursPolicyId}) has only Availability + WorkingTerritories. The
   * {@code required-non-required} fixture attaches a {@code ResourcePreference(Required)=resourceB} to
   * the account, so once the crew is EMPTY the account-required resourceB is absent → the
   * RequiredResources rule fails → zero slots. (NOTE: {@code ShiftUsage} is NOT the difference — a live
   * tooling query confirmed BOTH policies' Availability rule carry the identical
   * {@code ShiftUsage=ConsiderOpHoursAndShiftsUnion}.) Confirmed by a diagnostic run: delete-all under a
   * policy = Availability+WorkingTerritories+RequiredResources reproduces the exact 400; the good 2-rule
   * policy Succeeds ({@link #testRescheduleDeleteAllWithPolicySucceedsE2E}). Adding a new-time move alone
   * did NOT change the outcome (still 400). The Core func test books Success because its local org's
   * DEFAULT OnSite policy admits the empty crew — same 262 code, different default-policy rule set. So
   * the empty-crew delete-all CAN complete on 262 (leaving a no-primary SA); it is gated only by whether
   * the resolved policy's rules admit an empty crew. This test faithfully pins the policy-less/org-default
   * rejection; the twin pins the good-policy Success.
   *
   * <p>Doc-refutation status: Decision 4z (there is NO "reschedule must keep a primary" rule) is
   * refuted at the VALIDATION layer by {@link #testRescheduleNoPrimaryE2E} Arm B (zero primaries is
   * allowed by {@code validatePrimaryResourceCount}); the END-TO-END empty-crew completion is
   * demonstrated on 262 by the good-policy twin.
   *
   * <p>Own {@code revUp} starting with AUTH_CONFIG → fresh env + fresh timestamped users, so the SA
   * created for this probe does not collide with the sibling reschedule test's fixtures.
   */
  @Test
  void testRescheduleDeleteAllLeavesNoPrimaryE2E() {
    ReVomanConfigForWfs.assumeExternalOrgCreds();
    final var rundown =
        ReVoman.revUp(
            (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
            AUTH_CONFIG,
            AVAILABILITY_OP_HOURS_POLICY_CONFIG,
            REQUIRED_NON_REQUIRED_FIXTURE_CONFIG,
            SCHEDULE_TWO_RESOURCE_CLEAN_CONFIG,
            RESCHEDULE_DELETE_ALL_CONFIG);
    final var env = CollectionsKt.last(rundown).mutableEnv;
    // Setup booked, and the SA id was captured for the delete-all reschedule.
    assertThat(env.getAsString("reschedCleanStatus")).isEqualTo("Success");
    assertThat(env.getAsString("reschedCleanSaId")).isNotNull();
    // POLICY-LESS delete-ALL reschedule on 262 is NOT Success: with no schedulingPolicyId it runs the
    // empty-crew slot-gen under the org DEFAULT OnSite policy, which carries a RequiredResources rule.
    // The account has a ResourcePreference(Required)=resourceB, so an EMPTY crew fails RequiredResources
    // → zero slots → INVALID_INPUT / HTTP 400 / "not available for the requested slot",
    // schedulingStatus null. This is NOT a release gate: the good policy
    // (Availability+WorkingTerritories, NO RequiredResources) flips it to Success — see
    // testRescheduleDeleteAllWithPolicySucceedsE2E. Asserted as OBSERVED for the org-default policy.
    assertThat(env.getAsString("reschedDeleteAllStatus")).isAnyOf(null, "null");
    assertThat(env.getAsString("reschedDeleteAllErrorCode")).isEqualTo("INVALID_INPUT");
    assertThat(env.getAsString("reschedDeleteAllHttpCode")).isEqualTo("400");
    assertThat(env.getAsString("reschedDeleteAllErrorMessage"))
        .contains("not available for the requested slot");
  }

  /**
   * Decision 4z delete-ALL WITH an explicit scheduling policy — the reconciling twin of {@link
   * #testRescheduleDeleteAllLeavesNoPrimaryE2E}, and the resolution of why that sibling could not
   * reproduce the Core func test's Success. It deletes the SAME two resources (resourceA + resourceB,
   * no {@code isPrimaryResource} flag, NO {@code startTime}/{@code endTime}) — byte-for-byte identical
   * to the sibling EXCEPT it adds {@code schedulingPolicyId=availabilityOpHoursPolicyId} (a 2-rule
   * policy: Availability + WorkingTerritories, with NO RequiredResources rule).
   *
   * <p>Root cause (live-isolated on this 262 org + a live tooling query): the delete-all reschedule is
   * rejected by empty-crew slot-gen ONLY when it runs under a policy that carries a {@code
   * RequiredResources} rule. The sibling omits {@code schedulingPolicyId}, so {@code
   * SlotAvailabilityChecker.buildGetSlotsRequest} carries a null policy and {@code
   * InBusinessGetSlotsHandler.getSchedulingPolicyConfiguration} falls back to {@code
   * getDefaultOnSiteSchedulingPolicy} ({@code DefaultOnSiteSchdPlcy}) — a 7-rule policy that INCLUDES
   * {@code RequiredResources}. The {@code required-non-required} fixture attaches a {@code
   * ResourcePreference(Required)=resourceB} to the account, so an EMPTY crew fails RequiredResources →
   * zero slots → {@code SlotNotAvailable}/400. (Both policies' Availability rule carry the identical
   * {@code ShiftUsage=ConsiderOpHoursAndShiftsUnion} — verified by tooling query — so ShiftUsage is NOT
   * the difference; the RULE SET is.) Adding a new-time move alone did NOT fix it (still 400) — proving
   * time was never the variable. Supplying the good policy here (no RequiredResources rule) DOES: the
   * empty-crew re-check (deletes skipped by {@code extractServiceResourceIds}; the SA-under-reschedule
   * excluded via {@code withAppointmentIdsExcludedForAvailability}) finds a slot and the delete-all
   * SUCCEEDS.
   *
   * <p>Why this reconciles the two tests: the Core func test {@code
   * OnSiteRescheduleAppointmentsConnectApiTest.testRescheduleAppointmentDeleteAllAssignedResources}
   * runs against a local org whose DEFAULT OnSite policy admits the empty crew (its rule set / account
   * data does not reject it), so its policy-less empty-crew delete-all finds slots and books Success.
   * Same 262 code; the ONLY difference is the resolved scheduling policy's rule set relative to the
   * account's required-resource preferences — NOT the release. With a policy that has no
   * RequiredResources rule supplied, both the ReVoman probe and the func test agree: an empty-crew
   * delete-all reschedule SUCCEEDS on 262, leaving an appointment with no resources and no primary.
   *
   * <p>262 (LIVE-OBSERVED): {@code reschedDeleteAllWithPolicyStatus == "Success"} — the write-path
   * counterpart of the passing Core func test.
   *
   * <p>Own {@code revUp} starting with AUTH_CONFIG → fresh env + fresh timestamped users, so this
   * probe's SA does not collide with the sibling reschedule tests' fixtures.
   */
  @Test
  void testRescheduleDeleteAllWithPolicySucceedsE2E() {
    ReVomanConfigForWfs.assumeExternalOrgCreds();
    final var rundown =
        ReVoman.revUp(
            (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
            AUTH_CONFIG,
            AVAILABILITY_OP_HOURS_POLICY_CONFIG,
            REQUIRED_NON_REQUIRED_FIXTURE_CONFIG,
            SCHEDULE_TWO_RESOURCE_CLEAN_CONFIG,
            RESCHEDULE_DELETE_ALL_WITH_POLICY_CONFIG);
    final var env = CollectionsKt.last(rundown).mutableEnv;
    // Setup booked, and the SA id was captured for the delete-all reschedule.
    assertThat(env.getAsString("reschedCleanStatus")).isEqualTo("Success");
    assertThat(env.getAsString("reschedCleanSaId")).isNotNull();
    // Delete-ALL WITH the well-formed policy SUCCEEDS on 262: the empty-crew availability re-check runs
    // under a policy whose Availability rule has a usable ShiftUsage param, finds a slot, and books
    // (the SA-under-reschedule is excluded from the unavailability query). This is the same outcome the
    // Core func test testRescheduleAppointmentDeleteAllAssignedResources asserts — reconciling the two
    // tests and pinning the sibling's rejection to the under-configured org-DEFAULT policy, NOT a
    // release gate and NOT the request times.
    assertThat(env.getAsString("reschedDeleteAllWithPolicyStatus")).isEqualTo("Success");
    assertThat(env.getAsString("reschedDeleteAllWithPolicySaId")).isNotNull();
  }

  /**
   * Decision 4z DEMOTE — the under-guarded "reschedule leaves TWO-or-more workers with NO primary"
   * shape that the sibling delete arms ({@link #testRescheduleNoPrimaryE2E} Arm B shrinks to 1
   * survivor; {@link #testRescheduleDeleteAllLeavesNoPrimaryE2E} shrinks to 0) never build. A clean
   * two-resource Schedule (resourceA primary+required, resourceB non-primary+required) captures
   * {@code reschedCleanSaId}; the reschedule then sends TWO {@code UpdateOperation} entries with NO
   * {@code startTime}/{@code endTime}: resourceA flipped to {@code isPrimaryResource=false} (still
   * {@code isRequiredResource=true}) and resourceB re-stated non-primary+required — so the
   * EFFECTIVE crew stays 2 workers but ZERO primaries. {@code
   * RescheduleCommonValidator.validatePrimaryResourceCount} rejects only {@code primaryCount > 1}
   * (no NoPrimary check), so validation ALLOWS zero primaries — this is NOT blocked at validation.
   * The demote keeps the SAME required resources (only a primary flag flips), the best shot at
   * making {@code resourcesHaveChanged==false} and reaching the {@code SlotAvailabilityChecker}
   * short-circuit (nothing structurally changed).
   *
   * <p>262 (LIVE-OBSERVED 2026-07-01 on the org under test): the clean two-resource Schedule books
   * Success (captures {@code reschedCleanSaId}); the demote reschedule is NOT Success — it is
   * rejected with schedulingStatus null, {@code INVALID_INPUT} / HTTP 400 / top-level "The service
   * resources are not available for the requested slot." ({@code SlotNotAvailable}) — the SAME
   * downstream availability re-check that blocks the delete-primary Arm B and the delete-ALL probe.
   * So on 262 a demote-to-2-workers-no-primary reschedule is BLOCKED by the reschedule availability
   * re-check; it does NOT crash on the 15/18-char record-id bug (that recompute NPE variant is
   * reached only after the availability check clears — here availability rejects first) and it does
   * NOT succeed/persist a no-primary crew. The short-circuit does NOT fire (the 15/18-char
   * required-id mismatch keeps resourcesHaveChanged true, so slot-gen still runs and rejects). The
   * doc is still refuted at the VALIDATION layer (zero primaries is allowed); the availability
   * rejection is a separate downstream gap. Asserted as OBSERVED, not the brief's hypothesized
   * crash/success.
   *
   * <p>Own {@code revUp} starting with AUTH_CONFIG → fresh env + fresh timestamped users, so this
   * probe's SA does not collide with the sibling reschedule tests' fixtures.
   */
  @Test
  void testRescheduleDemotePrimaryTwoCrewNoPrimaryE2E() {
    ReVomanConfigForWfs.assumeExternalOrgCreds();
    final var rundown =
        ReVoman.revUp(
            (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
            AUTH_CONFIG,
            AVAILABILITY_OP_HOURS_POLICY_CONFIG,
            REQUIRED_NON_REQUIRED_FIXTURE_CONFIG,
            SCHEDULE_TWO_RESOURCE_CLEAN_CONFIG,
            RESCHEDULE_DEMOTE_PRIMARY_TWO_CREW_CONFIG);
    final var env = CollectionsKt.last(rundown).mutableEnv;
    // Setup booked, and the SA id was captured for the demote reschedule.
    assertThat(env.getAsString("reschedCleanStatus")).isEqualTo("Success");
    assertThat(env.getAsString("reschedCleanSaId")).isNotNull();
    // Demote-to-2-workers-no-primary on 262 is NOT Success: it is rejected by the SAME downstream
    // availability re-check (SlotNotAvailable) that blocks the delete-primary Arm B — INVALID_INPUT
    // /
    // HTTP 400 / "not available for the requested slot", schedulingStatus null. It does NOT crash
    // on
    // the 15/18-char record-id bug and does NOT persist a no-primary crew. Asserted as OBSERVED.
    assertThat(env.getAsString("reschedDemoteStatus")).isAnyOf(null, "null");
    assertThat(env.getAsString("reschedDemoteErrorCode")).isEqualTo("INVALID_INPUT");
    assertThat(env.getAsString("reschedDemoteHttpCode")).isEqualTo("400");
    assertThat(env.getAsString("reschedDemoteErrorMessage"))
        .contains("not available for the requested slot");
  }

  private static void assertDimensionBooksSuccess(
      final String verdictEnvKey, final Kick... configs) {
    final var rundown =
        ReVoman.revUp(
            (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(), configs);
    assertThat(CollectionsKt.last(rundown).mutableEnv).containsEntry(verdictEnvKey, "Success");
  }
}
