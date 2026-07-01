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
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.RESCHEDULE_DELETE_PRIMARY_NO_FLAG_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.RESCHEDULE_DELETE_PRIMARY_WITH_FLAG_CONFIG;
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
 * WFS read↔write parity write-path characterization (live 262; 264 contrast in each method's
 * javadoc). Supersedes WfsHelperFitnessE2ETest (Decision 1) and WfsDoubleBookHelperE2ETest
 * (Decision 1.5).
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
   * <p>264 contrast: the helper WOULD be rejected with the matching rule code (ExcludedResources /
   * MatchTerritory / MatchSkills / WorkingLocations) → flip each expected verdict to
   * "ScheduleError".
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
   *
   * <p>264 contrast: the doc flags helper double-book as a gap InField blocks; under 264 the
   * non-required act would also be rejected (not-available) rather than Success.
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
   * <p>264 contrast: the violating booking gives a clean ScheduleError errorCode=RequiredResources
   * (no crash) since the satisfaction rule evaluates over REQUIRED resources only and the
   * non-required resourceB does not count; control unchanged.
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
   * <p>264 contrast: Act A → a missing isRequiredResource is treated as not-required and handled
   * cleanly (no crash); Act B unchanged.
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
   * <p>Characterization-only (NOT a 262-broken→264-fixed contrast): the asserted output is the SAME
   * on 262 and 264 — the clean message already ships on 262, so the two releases COINCIDE here and
   * there is no behavior delta to flip. The live org returns a clean top-level {@code
   * ConnectErrorCode INVALID_INPUT} / HTTP 400 with message "Only one of the provided assigned
   * resource can be a primary resource." No booking occurs (the throw is in {@code
   * validatePayload}, so {@code appointments[0]} is absent → status null). Per the product doc the
   * 262 ERA was thought to produce a "confusing database error", but the CURRENT 262 code already
   * returns this clean message, which is also the 264 target — so 262 == 264 (error-message polish
   * already shipped).
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
   * <p>264 contrast: unchanged — reject (not auto-correct) is the intended persist behavior; the
   * doc's open question (auto-correct vs reject) resolves to reject. (A fast-fail at input
   * validation, as the notes recommend, would only change WHERE it's rejected, not THAT it's
   * rejected.)
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
   * error. On 262 the reschedule slot-gen yields no slot for the surviving non-primary crew because
   * 264 reworked reschedule availability (an EFFECTIVE-SET MERGE that re-evaluates the REAL
   * surviving crew = existing − deleted ∪ created ∪ updated) so the surviving-crew slot is found —
   * and that rework is 264-only (verified by branch diff against 262: the effective-set merge +
   * rule-enforcer files are absent on 262). 262 lacks it. (A separate empty-effective-set
   * short-circuit also exists on 264, but it fires only when the crew is EMPTY, i.e. delete-ALL —
   * NOT for this delete-primary-leaving-one case, where resourceB survives.) The doc is still
   * refuted at the validation layer (zero primaries is allowed); the test asserts the OBSERVED
   * rejection faithfully (availability, NOT no-primary) rather than forcing a Success this org
   * cannot produce. Adding {@code startTime}/{@code endTime} (Step 10 option 2) and switching to
   * delete-ALL (Step 10 option 3) were both tried live and both still returned the same {@code
   * SlotNotAvailable}, confirming the 262 availability-path gap.
   *
   * <p>264 contrast: 264 reworked reschedule availability (effective-set merge over the real
   * surviving crew = existing − deleted ∪ created ∪ updated), so the surviving-crew slot is found
   * and the same delete-primary call would book {@code Success} on 264 — i.e. the doc's blanket
   * claim is refuted even more directly there. (264 also adds an empty-effective-set short-circuit,
   * but that fires only for delete-ALL, not for this delete-primary-leaving-one case.) The product
   * OPEN QUESTION (should a reschedule be allowed to leave no primary?) is a DECISION, not a code
   * bug: the primary-count validator already allows it.
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
    // (SlotNotAvailable), NEVER by a no-primary rule (262 lacks 264's reworked reschedule
    // availability — the effective-set merge over the real surviving crew that would find the
    // surviving-crew slot).
    // This is what refutes the doc: there is no "must keep a primary" validation. Characterized
    // faithfully.
    assertThat(env.getAsString("reschedNoFlagStatus")).isNotEqualTo("Success");
    assertThat(env.getAsString("reschedNoFlagErrorCode")).isEqualTo("INVALID_INPUT");
    assertThat(env.getAsString("reschedNoFlagHttpCode")).isEqualTo("400");
    assertThat(env.getAsString("reschedNoFlagErrorMessage"))
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
