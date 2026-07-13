/*
 * Copyright 2025 salesforce.com, inc.
 * All Rights Reserved
 * Company Confidential
 */

package com.salesforce.revoman.integration.core.wfs;

import static com.google.common.truth.Truth.assertThat;
import static com.salesforce.revoman.integration.core.CoreUtils.assumeOrgCredsPresent;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.AUTH_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.AVAILABILITY_OP_HOURS_POLICY_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.EXCLUDED_FIXTURE_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.EXCLUDED_RESOURCES_POLICY_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.EXCLUDED_SCHEDULE_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.MATCH_SKILLS_POLICY_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.SKILLS_FIXTURE_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.SKILLS_SCHEDULE_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.SKILLS_SKILL_FIXTURE_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.TERRITORY_FIXTURE_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.TERRITORY_PARTIAL_POLICY_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.TERRITORY_SCHEDULE_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.WORKING_LOCATIONS_FIXTURE_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.WORKING_LOCATIONS_SCHEDULE_CONFIG;

import com.salesforce.revoman.ReVoman;
import java.util.Map;
import kotlin.collections.CollectionsKt;
import org.junit.jupiter.api.Test;

/**
 * WFS Decision-1 Scenario B (FITNESS / stricter half): probes whether a NON-required "helper"
 * resource is fitness-checked on the Schedule write path across FOUR dimensions, each a clean
 * required+primary resourceA plus a NON-required resourceB that VIOLATES exactly one rule:
 *
 * <ol>
 *   <li>EXCLUDED — resourceB is on the Account's ResourcePreference Excluded list (rule code
 *       ExcludedResources).
 *   <li>TERRITORY — resourceB's ServiceTerritoryMember window ends at noon, the booking straddles
 *       it (rule code MatchTerritory).
 *   <li>SKILLS — resourceB lacks the WorkType's required Skill; only resourceA has the
 *       ServiceResourceSkill (rule code MatchSkills).
 *   <li>WORKING-LOCATIONS — resourceB is a Secondary territory member under a primary-only
 *       WorkingTerritories policy (rule code WorkingLocations).
 * </ol>
 *
 * <p>HYPOTHESIS (to be confirmed by the run):
 *
 * <ul>
 *   <li>262 (DEFAULT here): the helper is NOT fitness-checked → every probe books {@code
 *       schedulingStatus=Success}. These assertions encode that expectation.
 *   <li>264 CONTRAST: the helper WOULD be rejected with the matching rule code
 *       (ExcludedResources/MatchTerritory/MatchSkills/WorkingLocations). To re-target this test to
 *       a 264 org, flip each expected verdict below from "Success" to "ScheduleError" (the step
 *       `test` scripts in helper-fitness.postman_collection.json document the same contrast).
 * </ul>
 */
// Needs a WFS workspace org: multi-resource pref (WorkforceSchdMulResSchdPref) +
// InBusinessScheduling
// enabled + Shift.Status DynEnum seeded + each Availability rule's ShiftUsage param. See
// ReVomanConfigForWfs. Excluded from aggregate runs (`gradle clean build`) by the
// `integration.core.*` test filter; invoke on-demand with `-PincludeCoreIT`.
class WfsHelperFitnessE2ETest {

  @Test
  void testNonRequiredHelperFitnessE2E() {
    assumeOrgCredsPresent(ReVomanConfigForWfs.ENV_PATH);
    final var fitnessRundown =
        ReVoman.revUp(
            (rundown, ignore) ->
                assertThat(rundown.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
            AUTH_CONFIG,
            // EXCLUDED dimension: ExcludedResources policy + fixture + schedule.
            EXCLUDED_RESOURCES_POLICY_CONFIG,
            EXCLUDED_FIXTURE_CONFIG,
            EXCLUDED_SCHEDULE_CONFIG,
            // TERRITORY dimension: territory-membership-partial (MatchTerritory) policy + fixture +
            // schedule.
            TERRITORY_PARTIAL_POLICY_CONFIG,
            TERRITORY_FIXTURE_CONFIG,
            TERRITORY_SCHEDULE_CONFIG,
            // SKILLS dimension: match-skills policy + graph fixture + the master Skill + schedule.
            MATCH_SKILLS_POLICY_CONFIG,
            SKILLS_SKILL_FIXTURE_CONFIG,
            SKILLS_FIXTURE_CONFIG,
            SKILLS_SCHEDULE_CONFIG,
            // WORKING-LOCATIONS dimension: reuses the availability-op-hours (primary-only) policy +
            // fixture + schedule.
            AVAILABILITY_OP_HOURS_POLICY_CONFIG,
            WORKING_LOCATIONS_FIXTURE_CONFIG,
            WORKING_LOCATIONS_SCHEDULE_CONFIG);
    // 262 expectation: a NON-required helper escapes every fitness rule, so each probe books
    // Success. On 264 each of these would instead be "ScheduleError" with the matching rule code.
    assertThat(CollectionsKt.last(fitnessRundown).mutableEnv)
        .containsAtLeastEntriesIn(
            Map.of(
                "excludedNonReqSchedulingStatus", "Success",
                "territoryNonReqSchedulingStatus", "Success",
                "skillsNonReqSchedulingStatus", "Success",
                "workingLocationsSecondaryNonReqSchedulingStatus", "Success"));
  }
}
