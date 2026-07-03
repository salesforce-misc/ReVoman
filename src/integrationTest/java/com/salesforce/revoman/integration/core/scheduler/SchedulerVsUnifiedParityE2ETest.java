/*
 * Copyright 2026 salesforce.com, inc.
 * All Rights Reserved
 * Company Confidential
 */

package com.salesforce.revoman.integration.core.scheduler;

import static com.google.common.truth.Truth.assertThat;
import static com.salesforce.revoman.integration.core.scheduler.SchedulerParityConfig.OLD_AUTH_CONFIG;

import com.salesforce.revoman.ReVoman;
import com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs;
import kotlin.collections.CollectionsKt;
import org.junit.jupiter.api.Test;

/**
 * Scheduler ↔ Unified(264) {@code 1.*} helper-fitness parity — differential tests. Each scenario diffs
 * the OLD Salesforce Scheduler decision against the 264-Unified(262-proxy) decision on BOTH the read
 * (INCLUDED/EXCLUDED) and write (BOOKED/REFUSED/CRASHED) axes.
 */
class SchedulerVsUnifiedParityE2ETest {

  @Test
  void schedulerOrgAuthBindsE2E() {
    SchedulerParityConfig.assumeBothOrgCreds();
    final var rundown = ReVoman.revUp(OLD_AUTH_CONFIG);
    final var env = rundown.mutableEnv;
    assertThat(env.getAsString("adminToken")).isNotEmpty();
    assertThat(env.getAsString("versionPath")).contains("/services/data/v");
  }

  /**
   * Decision 1.5 parity — a busy NON-required helper. OLD Salesforce Scheduler vs 264 Unified OnSite
   * (262 proxy) must agree on both axes: the busy helper BOOKS (not availability-checked) while a
   * required busy control is REFUSED; and the read offers the fixture's slots only when the busy
   * resource is NOT a hard required constraint. Old side: public Scheduler REST ({@code
   * POST /scheduling/getAppointmentSlots} + {@code POST /connect/scheduling/service-appointments}).
   * Unified side: the existing WFS double-book Connect acts.
   *
   * <p>KEY PARITY FINDING (both products agree): a non-required helper BOOKS (it is not
   * availability-checked, so it may double-book) and is read-EXCLUDED when that same busy resource is
   * named a hard required constraint; the required-control write is REFUSED (availability-checked). All
   * four normalized verdicts align → 1.5 parity CONFIRMED.
   *
   * <p>262-proxy caveat: these verdicts are 262-observed and stand in for 264 (endpoints are identical
   * 262↔264 per the parity effort). A true-264 org may differ from 262 on the locked-in crash decisions
   * (1.4 / 3 — 262-specific INTERNAL_SERVER_ERROR bugs); 1.5 is crash-free, so it is unaffected by that
   * caveat. Divergence, were it to appear, is a first-class finding: it would be asserted faithfully
   * (with a {@code <p>DIVERGENCE:} paragraph naming the mismatch) rather than forced to a green.
   *
   * <p>Old-side revUps mint 2 fresh {@code sched-res-*@revoman.org} users per run and never clean up;
   * three old-side revUps here (read + two write arms) → ~6 fresh users/run. The leading success guard
   * ({@code firstUnIgnoredUnsuccessfulStepReport() == null}) surfaces a rolled-back fixture/grant (e.g.
   * LICENSE_LIMIT_EXCEEDED) loudly at the fixture step rather than masking it as a false verdict; the
   * read/book acts carry {@code ignoreHTTPStatusUnsuccessful} so legit 400s / empty reads do not trip it.
   */
  @Test
  void testHelperDoubleBookParity_1_5_E2E() {
    SchedulerParityConfig.assumeBothOrgCreds();

    // --- OLD side: read decision + write (helper) + write (required control), fresh revUps ---
    // Each old-side revUp mirrors the probe sequence: AUTH → FIXTURE → GRANT (Lightning-Scheduler
    // user-access, else the classic engine prunes the fixture resources → dead read/write) → READ/BOOK.
    final var oldReadEnv =
        CollectionsKt.last(
                ReVoman.revUp(
                    (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
                    SchedulerParityConfig.OLD_AUTH_CONFIG,
                    SchedulerParityConfig.OLD_DOUBLE_BOOK_FIXTURE_CONFIG,
                    SchedulerParityConfig.OLD_GRANT_LS_ACCESS_CONFIG,
                    SchedulerParityConfig.OLD_GET_SLOTS_DOUBLE_BOOK_CONFIG))
            .mutableEnv;
    final var oldReadDecision =
        SchedulerParityConfig.oldReadDecision(oldReadEnv.getAsString("oldReadWithBSlotCount"));
    // Non-vacuity guard: the A-only control read MUST offer >0 slots, proving the fixture (and the
    // Lightning-Scheduler user-access grant) is live. Without this, a silently-failed grant would make
    // BOTH reads 0 → EXCLUDED would pass for the WRONG reason (dead read, not the busy resource).
    assertThat(oldReadEnv.getAsString("oldReadAOnlySlotCount")).isNotEqualTo("0");

    final var oldHelperEnv =
        CollectionsKt.last(
                ReVoman.revUp(
                    (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
                    SchedulerParityConfig.OLD_AUTH_CONFIG,
                    SchedulerParityConfig.OLD_DOUBLE_BOOK_FIXTURE_CONFIG,
                    SchedulerParityConfig.OLD_GRANT_LS_ACCESS_CONFIG,
                    SchedulerParityConfig.OLD_BOOK_NON_REQUIRED_CONFIG))
            .mutableEnv;
    final var oldHelperOutcome =
        SchedulerParityConfig.oldWriteOutcome(
            oldHelperEnv.getAsString("oldWriteHelperSaId"),
            oldHelperEnv.getAsString("oldWriteHelperHttp"));

    final var oldControlEnv =
        CollectionsKt.last(
                ReVoman.revUp(
                    (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
                    SchedulerParityConfig.OLD_AUTH_CONFIG,
                    SchedulerParityConfig.OLD_DOUBLE_BOOK_FIXTURE_CONFIG,
                    SchedulerParityConfig.OLD_GRANT_LS_ACCESS_CONFIG,
                    SchedulerParityConfig.OLD_BOOK_REQUIRED_CONTROL_CONFIG))
            .mutableEnv;
    final var oldControlOutcome =
        SchedulerParityConfig.oldWriteOutcome(
            oldControlEnv.getAsString("oldWriteRequiredControlSaId"),
            oldControlEnv.getAsString("oldWriteRequiredControlHttp"));

    // --- 264 side (262 proxy): reuse the proven WFS double-book acts, one revUp ---
    final var unifiedEnv =
        CollectionsKt.last(
                ReVoman.revUp(
                    (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
                    ReVomanConfigForWfs.AUTH_CONFIG,
                    ReVomanConfigForWfs.AVAILABILITY_OP_HOURS_POLICY_CONFIG,
                    ReVomanConfigForWfs.DOUBLE_BOOK_FIXTURE_CONFIG,
                    ReVomanConfigForWfs.DOUBLE_BOOK_NON_REQUIRED_SCHEDULE_CONFIG,
                    ReVomanConfigForWfs.DOUBLE_BOOK_REQUIRED_CONFLICT_SCHEDULE_CONFIG))
            .mutableEnv;
    final var unifiedHelperOutcome =
        SchedulerParityConfig.unifiedWriteOutcome(
            unifiedEnv.getAsString("doubleBookNonRequiredSchedulingStatus"), "201");
    final var unifiedControlOutcome =
        SchedulerParityConfig.unifiedWriteOutcome(
            unifiedEnv.getAsString("doubleBookRequiredControlSchedulingStatus"), "201");

    // --- PARITY: both products book the non-required busy helper (helper is NOT availability-checked) ---
    // Shape guard (restored from the removed write probe): the old-side helper booking must return a real
    // ServiceAppointment id (18-char, 08p prefix), so BOOKED reflects a genuine persist, not an empty pass.
    assertThat(oldHelperEnv.getAsString("oldWriteHelperSaId")).hasLength(18);
    assertThat(oldHelperEnv.getAsString("oldWriteHelperSaId")).startsWith("08p");
    assertThat(oldHelperOutcome).isEqualTo(SchedulerParityConfig.WriteOutcome.BOOKED);
    assertThat(unifiedHelperOutcome).isEqualTo(SchedulerParityConfig.WriteOutcome.BOOKED);
    assertThat(oldHelperOutcome).isEqualTo(unifiedHelperOutcome);
    // Old read excludes the busy resource when it is a hard required id (the old-side availability proof).
    assertThat(oldReadDecision).isEqualTo(SchedulerParityConfig.ReadDecision.EXCLUDED);
    // Both required controls (the busy resource named required) are REFUSED → the availability proof.
    assertThat(oldControlOutcome).isEqualTo(SchedulerParityConfig.WriteOutcome.REFUSED);
    assertThat(unifiedControlOutcome).isEqualTo(SchedulerParityConfig.WriteOutcome.REFUSED);
    assertThat(oldControlOutcome).isEqualTo(unifiedControlOutcome);
    // Unified control (busy resource as required) is refused → the Unified availability proof.
    assertThat(unifiedEnv.getAsString("doubleBookRequiredControlSchedulingStatus"))
        .isNotEqualTo("Success");
  }
}
