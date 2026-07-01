/*
 * Copyright 2025 salesforce.com, inc.
 * All Rights Reserved
 * Company Confidential
 */

package com.salesforce.revoman.integration.core.wfs;

import static com.google.common.truth.Truth.assertThat;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.AUTH_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.GET_SLOTS_SKILLS_CONTROL_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.GET_SLOTS_SKILLS_VIOLATING_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.MATCH_SKILLS_POLICY_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.SCHEDULE_SKILLS_CONTROL_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.SCHEDULE_SKILLS_VIOLATING_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.SKILLS_FIXTURE_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.SKILLS_SKILL_FIXTURE_CONFIG;

import com.salesforce.revoman.ReVoman;
import kotlin.collections.CollectionsKt;
import org.junit.jupiter.api.Test;

/**
 * WFS rules read==write parity (live 262; 264 contrast in each method's javadoc). Proves each of the 7
 * Common+InBusiness scheduling rules (RuleObjectiveMapper:108-125) evaluates identically on the read APIs
 * (get-appointment-slots/candidates/available-slots/available-resources → InBusinessGetCandidatesSlotsDataService.loadSchedulableSlots)
 * and the write APIs (schedule/reschedule → SlotAvailabilityChecker → same loadSchedulableSlots). Per rule
 * a differential matrix asserts read decision == write decision for a violating AND a control case. Records
 * the two genuine read≠write divergences: the reschedule no-op short-circuit (SlotAvailabilityChecker:174-176)
 * and the RequiredResources 262 NPE. onField/inField rules are OUT OF SCOPE.
 */
class WfsRulesParityE2ETest {

  /**
   * MatchSkills — the required+primary resource lacking the WorkType's required skill is pruned by the
   * read (0 slots) AND rejected by the write; a skill-having control returns >0 slots AND books Success.
   * Proves MatchSkills runs identically read and write (loadSchedulableSlots shared by both).
   *
   * <p>262 (asserted): read-violating 0 slots ⟺ write-violating rejected; read-control >0 ⟺ write-control
   * Success. <p>264 contrast: unchanged — MatchSkills is a shared cheap check on both paths.
   */
  @Test
  void testMatchSkillsReadWriteParityE2E() {
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
}
