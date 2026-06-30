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
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.REQUIRED_NON_REQUIRED_FIXTURE_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.REQUIRED_NON_REQUIRED_SATISFIER_VIOLATING_SCHEDULE_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.REQUIRED_RESOURCES_POLICY_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.REQUIRED_SATISFIER_BOOKABLE_SCHEDULE_CONFIG;
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
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * WFS read↔write parity write-path characterization (live 262; 264 contrast in each method's javadoc).
 * Supersedes WfsHelperFitnessE2ETest (Decision 1) and WfsDoubleBookHelperE2ETest (Decision 1.5).
 *
 * <p>Decisions covered: 1 (helper fitness), 1.4 (helper can't satisfy a required-resource demand),
 * 1.5 (helper double-books), 3 (missing isRequiredResource flag + the L142 single-required-no-primary
 * control). Each scenario is its own {@code ReVoman.revUp(...)} starting with {@code AUTH_CONFIG}
 * (fresh env + fresh timestamped users → no ServiceResource (RelatedRecordId, ResourceType) collision).
 */
@Disabled(
    "needs a WFS workspace org: multi-resource pref (WorkforceSchdMulResSchdPref) + InBusinessScheduling"
        + " enabled + Shift.Status DynEnum seeded + each Availability rule's ShiftUsage param. See"
        + " ReVomanConfigForWfs.")
class WfsWritePathParityE2ETest {

  /**
   * Decision 1 — a NON-required "helper" resource is NOT fitness-checked on the Schedule write path,
   * across four dimensions (EXCLUDED / TERRITORY / SKILLS / WORKING-LOCATIONS). Each dimension is a
   * clean required+primary resourceA plus a NON-required resourceB violating exactly one rule.
   *
   * <p>262 (asserted): the helper escapes every fitness rule → each dimension books Success.
   * <p>264 contrast: the helper WOULD be rejected with the matching rule code (ExcludedResources /
   * MatchTerritory / MatchSkills / WorkingLocations) → flip each expected verdict to "ScheduleError".
   *
   * <p>Approach A: each dimension is its own revUp starting with AUTH_CONFIG → fresh env + fresh
   * timestamped users, so the four dimensions' ServiceResource(RelatedRecordId, ResourceType) rows
   * never collide (the SR-uniqueness collision that made the old single-revUp run roll back dims 2-4).
   */
  @Test
  void testNonRequiredHelperFitnessE2E() {
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
   * Decision 1.5 — a NON-required helper is NOT availability-checked, so it may double-book. The A/B
   * flips ONLY isRequiredResource on resourceB over the SAME fixture (resourceB is BUSY at the window).
   *
   * <p>262 (asserted): the busy NON-required helper books Success (no availability check). The
   * REQUIRED control (isRequiredResource=true) is availability-checked → not-Success.
   * <p>264 contrast: the doc flags helper double-book as a gap InField blocks; under 264 the non-required
   * act would also be rejected (not-available) rather than Success.
   */
  @Test
  void testNonRequiredHelperDoubleBooksE2E() {
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
    assertThat(env.getAsString("doubleBookRequiredControlSchedulingStatus")).isNotEqualTo("Success");
  }

  /**
   * Decision 1.4 — a NON-required helper CANNOT satisfy an account's required-resource demand
   * (ResourcePreference Required). resourceA (required+primary) is clean but NOT on the account's
   * required list; resourceB IS on the required list but is assigned NON-required.
   *
   * <p>262 (asserted): the violating booking CRASHES with errorCode=INTERNAL_SERVER_ERROR — a server
   * NPE ("Cannot invoke ...ArrayListMultimap.values() because this.serviceTerritoryMembers is null"),
   * NOT the predicted clean ScheduleError errorCode=RequiredResources (a 262 crash bug). The control
   * (resourceB flipped to isRequiredResource=true) is rejected with NO RequiredResources error and no
   * crash (availability may still block it — INVALID_INPUT not-available).
   * <p>264 contrast: the violating booking gives a clean ScheduleError errorCode=RequiredResources
   * (no crash) since the satisfaction rule evaluates over REQUIRED resources only and the non-required
   * resourceB does not count; control unchanged.
   *
   * <p>Approach A: two SEPARATE revUps, each starting with AUTH_CONFIG → fresh env + fresh timestamped
   * users, so the violating and control fixtures' ServiceResource(RelatedRecordId, ResourceType) rows
   * never collide.
   */
  @Test
  void testNonRequiredHelperCannotSatisfyRequiredDemandE2E() {
    // Violating: only a NON-required helper present for the account's required demand → 262 server NPE.
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
    // Control: a genuine required satisfier → no RequiredResources error (fresh AUTH per Approach A).
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

  private static void assertDimensionBooksSuccess(
      final String verdictEnvKey, final Kick... configs) {
    final var rundown =
        ReVoman.revUp(
            (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(), configs);
    assertThat(CollectionsKt.last(rundown).mutableEnv).containsEntry(verdictEnvKey, "Success");
  }
}
