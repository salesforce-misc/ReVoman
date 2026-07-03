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

  /**
   * Scenario 1b (Territory) parity — a NON-required helper whose ServiceTerritoryMember coverage does
   * NOT include the booking window. OLD Salesforce Scheduler vs 264 Unified OnSite (262 proxy) must
   * agree on both axes: the out-of-territory helper BOOKS (its territory is NOT fitness-checked because
   * it is non-required) while the SAME resourceB named a hard REQUIRED constraint is REFUSED (the
   * classic engine's territory/STM-membership filter runs only for required resources); and the read
   * offers the fixture's slots only when the out-of-territory resource is NOT a hard required
   * constraint. Old side: public Scheduler REST ({@code POST /scheduling/getAppointmentSlots} + {@code
   * POST /connect/scheduling/service-appointments}). Unified side: the existing WFS territory Kicks
   * ({@code TERRITORY_PARTIAL_POLICY_CONFIG} / {@code TERRITORY_FIXTURE_CONFIG} / {@code
   * TERRITORY_SCHEDULE_CONFIG}), which book the non-required helper Success (the escape under test).
   *
   * <p>Constructibility: CLEANLY CONSTRUCTIBLE on the old read path. Both resources are fully AVAILABLE
   * over the 11:30-12:30 straddle-noon window (shared member OH 08-16 + Confirmed Shift 08-16), so
   * availability is NOT the discriminator — only territory coverage is: resourceB's ServiceTerritoryMember
   * is narrowed (by a follow-up PATCH after its Shift exists, so the full-day Shift survives) to END at
   * noon, so its membership OVERLAPS but does not CONTAIN the straddle-noon window. The classic
   * multi-resource read AND-intersects the required resources over the STM-committed window → A+B (B
   * required) = 0 slots, A-only > 0. This mirrors the WFS territory fixture's partial-membership
   * isolation shape.
   *
   * <p>KEY PARITY FINDING (both products agree): a non-required out-of-territory helper BOOKS (it is not
   * territory/membership-checked, so it may violate territory coverage) and is read-EXCLUDED when that
   * same resource is named a hard required constraint; the required-control write is REFUSED
   * (territory-checked). Old read EXCLUDED + old helper BOOKED + old required-control REFUSED + 264
   * helper BOOKED → 1b territory parity CONFIRMED.
   *
   * <p>262-proxy caveat: the 264 verdict is 262-observed and stands in for 264 (endpoints identical
   * 262↔264 per the parity effort). 1b is crash-free (MatchTerritory is a clean fitness rule), so it is
   * unaffected by the 262-specific crash caveats (1.4 / 3). A divergence, were it to appear, would be
   * asserted faithfully rather than forced to a green.
   *
   * <p>Old-side revUps mint 2 fresh {@code sched-terr-*@revoman.org} users per run and never clean up;
   * three old-side revUps here (read + two write arms) → ~6 fresh users/run. The leading success guard
   * ({@code firstUnIgnoredUnsuccessfulStepReport() == null}) surfaces a rolled-back fixture/grant loudly;
   * the read/book acts carry {@code ignoreHTTPStatusUnsuccessful} so legit 400s / empty reads do not trip
   * it.
   */
  @Test
  void testHelperTerritoryParity_1b_E2E() {
    SchedulerParityConfig.assumeBothOrgCreds();

    // --- OLD side: read decision + write (helper) + write (required control), fresh revUps ---
    // Each old-side revUp mirrors the probe sequence: AUTH → FIXTURE (graph + STM-narrowing PATCH) →
    // GRANT (Lightning-Scheduler user-access, else the classic engine prunes the fixture resources →
    // dead read/write) → READ/BOOK.
    final var oldReadEnv =
        CollectionsKt.last(
                ReVoman.revUp(
                    (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
                    SchedulerParityConfig.OLD_AUTH_CONFIG,
                    SchedulerParityConfig.OLD_TERRITORY_FIXTURE_CONFIG,
                    SchedulerParityConfig.OLD_GRANT_LS_ACCESS_CONFIG,
                    SchedulerParityConfig.OLD_GET_SLOTS_TERRITORY_CONFIG))
            .mutableEnv;
    final var oldReadDecision =
        SchedulerParityConfig.oldReadDecision(oldReadEnv.getAsString("oldReadWithBSlotCount"));
    // Non-vacuity guard: the A-only (clean, in-territory) control read MUST offer >0 slots, proving the
    // fixture (and the Lightning-Scheduler user-access grant) is live. Without this, a silently-failed
    // grant would make BOTH reads 0 → EXCLUDED would pass for the WRONG reason (dead read, not
    // resourceB's out-of-territory membership).
    assertThat(oldReadEnv.getAsString("oldReadAOnlySlotCount")).isNotEqualTo("0");

    final var oldHelperEnv =
        CollectionsKt.last(
                ReVoman.revUp(
                    (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
                    SchedulerParityConfig.OLD_AUTH_CONFIG,
                    SchedulerParityConfig.OLD_TERRITORY_FIXTURE_CONFIG,
                    SchedulerParityConfig.OLD_GRANT_LS_ACCESS_CONFIG,
                    SchedulerParityConfig.OLD_BOOK_TERRITORY_NON_REQUIRED_CONFIG))
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
                    SchedulerParityConfig.OLD_TERRITORY_FIXTURE_CONFIG,
                    SchedulerParityConfig.OLD_GRANT_LS_ACCESS_CONFIG,
                    SchedulerParityConfig.OLD_BOOK_TERRITORY_REQUIRED_CONTROL_CONFIG))
            .mutableEnv;
    final var oldControlOutcome =
        SchedulerParityConfig.oldWriteOutcome(
            oldControlEnv.getAsString("oldWriteRequiredControlSaId"),
            oldControlEnv.getAsString("oldWriteRequiredControlHttp"));

    // --- 264 side (262 proxy): reuse the proven WFS territory acts, one revUp. The territory scenario
    // has ONE act (the non-required helper booking Success is the escape under test); there is no 264
    // required-control act for territory, so the required→REFUSED axis is proven on the OLD side only. ---
    final var unifiedEnv =
        CollectionsKt.last(
                ReVoman.revUp(
                    (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
                    ReVomanConfigForWfs.AUTH_CONFIG,
                    ReVomanConfigForWfs.TERRITORY_PARTIAL_POLICY_CONFIG,
                    ReVomanConfigForWfs.TERRITORY_FIXTURE_CONFIG,
                    ReVomanConfigForWfs.TERRITORY_SCHEDULE_CONFIG))
            .mutableEnv;
    final var unifiedHelperOutcome =
        SchedulerParityConfig.unifiedWriteOutcome(
            unifiedEnv.getAsString("territoryNonReqSchedulingStatus"), "201");

    // --- PARITY: both products book the non-required out-of-territory helper (helper is NOT territory-
    // checked) ---
    // Shape guard: the old-side helper booking must return a real ServiceAppointment id (18-char, 08p
    // prefix), so BOOKED reflects a genuine persist, not an empty pass.
    assertThat(oldHelperEnv.getAsString("oldWriteHelperSaId")).hasLength(18);
    assertThat(oldHelperEnv.getAsString("oldWriteHelperSaId")).startsWith("08p");
    assertThat(oldHelperOutcome).isEqualTo(SchedulerParityConfig.WriteOutcome.BOOKED);
    assertThat(unifiedHelperOutcome).isEqualTo(SchedulerParityConfig.WriteOutcome.BOOKED);
    assertThat(oldHelperOutcome).isEqualTo(unifiedHelperOutcome);
    // Old read excludes the out-of-territory resource when it is a hard required id (the old-side
    // territory proof).
    assertThat(oldReadDecision).isEqualTo(SchedulerParityConfig.ReadDecision.EXCLUDED);
    // Old required control (the out-of-territory resource named required) is REFUSED → the old-side
    // territory-checked proof (isolates the helper Success to the non-required flag).
    assertThat(oldControlOutcome).isEqualTo(SchedulerParityConfig.WriteOutcome.REFUSED);
    // 264 helper (out-of-territory resource as non-required) books Success → the Unified escape it shares
    // with old.
    assertThat(unifiedEnv.getAsString("territoryNonReqSchedulingStatus")).isEqualTo("Success");
  }

  /**
   * Decision 1d parity — a NON-required helper whose ONLY disqualifier is its territory-member ROLE:
   * resourceB is a SECONDARY ({@code TerritoryType='S'}) member under a PRIMARY-ONLY SchedulingPolicy
   * ({@code ShouldUsePrimaryMembers=true, ShouldUseSecondaryMembers=false}). This is the cleanest
   * WorkingLocations isolation — resourceB IS a member (not a non-member exclusion) and is fully AVAILABLE
   * at the window (member OH 10-14 + Confirmed Shift 10-14 both cover the 11:00-11:30 booking, the proven
   * 1.5 double-book resourceA window), so its Secondary ROLE alone excludes it. OLD Salesforce Scheduler vs
   * 264 Unified OnSite (262 proxy) must agree: the Secondary
   * helper BOOKS (a non-required resource is not eligibility-checked, so its Secondary role escapes the
   * WorkingLocations rule); the OLD read EXCLUDES that same Secondary resource when it is a hard required
   * constraint (the primary-only STM filter drops it), and the OLD required-control write is REFUSED.
   *
   * <p>Old side: public Scheduler REST ({@code POST /scheduling/getAppointmentSlots} + {@code POST
   * /connect/scheduling/service-appointments}), each carrying {@code schedulingPolicyId} = the seeded
   * primary-only {@code AppointmentSchedulingPolicy}. The classic engine reads the primary/secondary
   * booleans straight off that policy entity and applies them as a ServiceTerritoryMember SOQL filter
   * ({@code SchedulingDbUtil.createPrimarySecondarySTMWhereCondition} → {@code TerritoryType IN ('P','R')}),
   * on BOTH the read and the booking availability check — so a Secondary-only resource is dropped by ROLE
   * (JDWP/source-confirmed: {@code scheduling-impl/.../SchedulingDbUtil.java}). Unified side: the existing
   * WFS working-locations Connect act under the primary-only WorkingTerritories(IsPrimaryLocationEnabled)
   * policy, which books the non-required Secondary helper Success (the 264 helper-escape finding).
   *
   * <p>KEY PARITY FINDING (both products agree): a non-required Secondary-member helper BOOKS (it is not
   * eligibility-checked, so its WorkingLocations role is never evaluated) and is read-EXCLUDED / write-
   * REFUSED when that same Secondary resource is named a hard required constraint → 1d parity CONFIRMED.
   *
   * <p>262-proxy caveat: the Unified verdict is 262-observed and stands in for 264 (endpoints identical
   * 262↔264 per the parity effort). 1d is crash-free. A divergence, were it to appear, is a first-class
   * finding asserted faithfully rather than forced to a green.
   *
   * <p>Old-side revUps mint 2 fresh {@code sched-wl-*@revoman.org} users per run and never clean up; three
   * old-side revUps here (read + two write arms) → ~6 fresh users/run. The leading success guard ({@code
   * firstUnIgnoredUnsuccessfulStepReport() == null}) surfaces a rolled-back fixture/grant loudly at the
   * fixture step; the read/book acts carry {@code ignoreHTTPStatusUnsuccessful} so legit 400s / empty reads
   * do not trip it.
   */
  @Test
  void testHelperWorkingLocationsParity_1d_E2E() {
    SchedulerParityConfig.assumeBothOrgCreds();

    // --- OLD side: read decision + write (helper) + write (required control), fresh revUps ---
    // Each old-side revUp mirrors the probe sequence: AUTH → FIXTURE (seeds the primary-only policy + P/S
    // member graph) → GRANT (Lightning-Scheduler user-access, else the classic engine prunes the fixture
    // resources → dead read/write) → READ/BOOK (each passing schedulingPolicyId = the primary-only policy).
    final var oldReadEnv =
        CollectionsKt.last(
                ReVoman.revUp(
                    (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
                    SchedulerParityConfig.OLD_AUTH_CONFIG,
                    SchedulerParityConfig.OLD_WORKLOC_FIXTURE_CONFIG,
                    SchedulerParityConfig.OLD_GRANT_LS_ACCESS_CONFIG,
                    SchedulerParityConfig.OLD_GET_SLOTS_WORKLOC_CONFIG))
            .mutableEnv;
    final var oldReadDecision =
        SchedulerParityConfig.oldReadDecision(oldReadEnv.getAsString("oldReadWithBSlotCount"));
    // Non-vacuity guard: the A-only (Primary member) control read MUST offer >0 slots, proving the fixture,
    // the primary-only policy, and the Lightning-Scheduler user-access grant are all live. Without this, a
    // silently-failed grant or an over-broad policy would make BOTH reads 0 → EXCLUDED would pass for the
    // WRONG reason (dead read, not the Secondary role).
    assertThat(oldReadEnv.getAsString("oldReadAOnlySlotCount")).isNotEqualTo("0");

    final var oldHelperEnv =
        CollectionsKt.last(
                ReVoman.revUp(
                    (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
                    SchedulerParityConfig.OLD_AUTH_CONFIG,
                    SchedulerParityConfig.OLD_WORKLOC_FIXTURE_CONFIG,
                    SchedulerParityConfig.OLD_GRANT_LS_ACCESS_CONFIG,
                    SchedulerParityConfig.OLD_BOOK_WORKLOC_NON_REQUIRED_CONFIG))
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
                    SchedulerParityConfig.OLD_WORKLOC_FIXTURE_CONFIG,
                    SchedulerParityConfig.OLD_GRANT_LS_ACCESS_CONFIG,
                    SchedulerParityConfig.OLD_BOOK_WORKLOC_REQUIRED_CONTROL_CONFIG))
            .mutableEnv;
    final var oldControlOutcome =
        SchedulerParityConfig.oldWriteOutcome(
            oldControlEnv.getAsString("oldWriteRequiredControlSaId"),
            oldControlEnv.getAsString("oldWriteRequiredControlHttp"));

    // --- 264 side (262 proxy): reuse the proven WFS working-locations acts under the primary-only policy ---
    final var unifiedEnv =
        CollectionsKt.last(
                ReVoman.revUp(
                    (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
                    ReVomanConfigForWfs.AUTH_CONFIG,
                    ReVomanConfigForWfs.AVAILABILITY_OP_HOURS_POLICY_CONFIG,
                    ReVomanConfigForWfs.WORKING_LOCATIONS_FIXTURE_CONFIG,
                    ReVomanConfigForWfs.WORKING_LOCATIONS_SCHEDULE_CONFIG))
            .mutableEnv;
    final var unifiedHelperOutcome =
        SchedulerParityConfig.unifiedWriteOutcome(
            unifiedEnv.getAsString("workingLocationsSecondaryNonReqSchedulingStatus"), "201");

    // --- PARITY: both products book the non-required Secondary-member helper (helper is NOT eligibility-checked) ---
    // Shape guard: the old-side helper booking must return a real ServiceAppointment id (18-char, 08p
    // prefix), so BOOKED reflects a genuine persist, not an empty pass.
    assertThat(oldHelperEnv.getAsString("oldWriteHelperSaId")).hasLength(18);
    assertThat(oldHelperEnv.getAsString("oldWriteHelperSaId")).startsWith("08p");
    assertThat(oldHelperOutcome).isEqualTo(SchedulerParityConfig.WriteOutcome.BOOKED);
    assertThat(unifiedHelperOutcome).isEqualTo(SchedulerParityConfig.WriteOutcome.BOOKED);
    assertThat(oldHelperOutcome).isEqualTo(unifiedHelperOutcome);
    // Old read excludes the Secondary resource when it is a hard required id (the old-side ROLE proof).
    assertThat(oldReadDecision).isEqualTo(SchedulerParityConfig.ReadDecision.EXCLUDED);
    // Old required control (Secondary resource named required) is REFUSED → the old-side ROLE-enforcement proof.
    assertThat(oldControlOutcome).isEqualTo(SchedulerParityConfig.WriteOutcome.REFUSED);
    // Unified helper (non-required Secondary member) books Success → the 264 WorkingLocations helper-escape finding.
    assertThat(unifiedEnv.getAsString("workingLocationsSecondaryNonReqSchedulingStatus"))
        .isEqualTo("Success");
  }

  /**
   * Decision 1a parity — a NON-required helper on the ACCOUNT's block-list (an account
   * ResourcePreference of PreferenceType=Excluded naming resourceB). resourceA is clean; only the
   * Excluded rule is in play. Does the scheduling engine fitness-check the non-required helper against
   * the account block-list on the WRITE (book) path?
   *
   * <p>KEY PARITY FINDING (both products agree on the write axis): the non-required Excluded helper is
   * NOT fitness-checked on the book path, so the SA BOOKS on both engines. 264 Unified: the WFS
   * schedule-excluded-non-required act returns schedulingStatus=Success (the helper escapes
   * ExcludedResources). OLD Salesforce Scheduler: {@code POST /connect/scheduling/service-appointments}
   * validates only the required/primary resources and passes {@code accountResourcePreferences=null}
   * (ServiceAppointmentHelper.areResourcesAvailable → SchedulingServiceImpl.getAppointmentSlots), so the
   * account ResourcePreference(Excluded) is never evaluated on write → the SA books with an {@code 08p}
   * serviceAppointmentId. → WRITE parity CONFIRMED (both BOOKED).
   *
   * <p>PARTIAL / path-dependent constructibility (read axis): on the OLD engine the account
   * ResourcePreference(Excluded) is enforced ONLY on the {@code POST /scheduling/getAppointmentCandidates}
   * path (SchedulingServiceImpl.getAppointmentCandidates → loadAccountResourcePreferences(accountId) →
   * SchedulingDbUtil.checkResourcePrefs PRUNES the excluded SR), NOT on plain getAppointmentSlots
   * (accountResourcePreferences=null → checkResourcePrefs short-circuits true, SchedulingDbUtil ~L706-708).
   * So the OLD read probe uses getAppointmentCandidates: WITH accountId, resourceB is pruned (absent from
   * every candidate's {@code resources[]}) → EXCLUDED; the NO-account control keeps resourceB present
   * (non-vacuity: the fixture is live and resourceB is a real, available candidate — its WITH-account
   * absence is caused by the Excluded pref, not a dead read). NOTE the read axis is thus a candidate-
   * pruning decision (a DIFFERENT surface from the book-time "helper fitness" the write asks); it is
   * constructible on the candidates path but does not have a clean non-required-vs-required control there
   * (getAppointmentCandidates has no per-resource required/non-required input). The write axis carries the
   * true 1a parity verdict; the read is captured faithfully as supporting evidence, not forced to mirror
   * the write.
   *
   * <p>262-proxy caveat: the 264 verdict is 262-observed (endpoints identical 262↔264 per the parity
   * effort); 1a is crash-free so it is unaffected by the 262-specific INTERNAL_SERVER_ERROR bugs (1.4 / 3).
   *
   * <p>Old-side revUps mint 2 fresh {@code sched-excl-*@revoman.org} users per run and never clean up; two
   * old-side revUps here (candidates read + book) → ~4 fresh users/run. The leading success guard ({@code
   * firstUnIgnoredUnsuccessfulStepReport() == null}) surfaces a rolled-back fixture/grant loudly at the
   * fixture step; the read/book acts carry {@code ignoreHTTPStatusUnsuccessful} so legit non-2xx / empty
   * reads do not trip it.
   */
  @Test
  void testHelperExcludedParity_1a_E2E() {
    SchedulerParityConfig.assumeBothOrgCreds();

    // --- OLD side: candidates read decision (with vs without accountId), fresh revUp ---
    // AUTH → FIXTURE → GRANT (Lightning-Scheduler user-access, else the classic engine prunes the
    // fixture resources → dead read) → CANDIDATES read (with accountId + no-account control).
    final var oldReadEnv =
        CollectionsKt.last(
                ReVoman.revUp(
                    (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
                    SchedulerParityConfig.OLD_AUTH_CONFIG,
                    SchedulerParityConfig.OLD_EXCLUDED_FIXTURE_CONFIG,
                    SchedulerParityConfig.OLD_GRANT_LS_ACCESS_CONFIG,
                    SchedulerParityConfig.OLD_GET_CANDIDATES_EXCLUDED_CONFIG))
            .mutableEnv;
    final var oldExcludedReadDecision =
        SchedulerParityConfig.oldCandidatesReadDecision(
            oldReadEnv.getAsString("oldCandidatesWithAcctResourceBPresent"));
    // Non-vacuity: the NO-account control MUST keep resourceB present (proves the fixture is live and
    // resourceB is a real candidate at the window — so its WITH-account absence is the Excluded pref,
    // not a dead read / silently-failed grant). resourceA must also be a candidate in the WITH-account read.
    assertThat(oldReadEnv.getAsString("oldCandidatesNoAcctResourceBPresent")).isEqualTo("1");
    assertThat(oldReadEnv.getAsString("oldCandidatesWithAcctResourceAPresent")).isEqualTo("1");

    // --- OLD side: write (book) the non-required Excluded helper, fresh revUp ---
    final var oldHelperEnv =
        CollectionsKt.last(
                ReVoman.revUp(
                    (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
                    SchedulerParityConfig.OLD_AUTH_CONFIG,
                    SchedulerParityConfig.OLD_EXCLUDED_FIXTURE_CONFIG,
                    SchedulerParityConfig.OLD_GRANT_LS_ACCESS_CONFIG,
                    SchedulerParityConfig.OLD_BOOK_EXCLUDED_NON_REQUIRED_CONFIG))
            .mutableEnv;
    final var oldHelperOutcome =
        SchedulerParityConfig.oldWriteOutcome(
            oldHelperEnv.getAsString("oldWriteHelperSaId"),
            oldHelperEnv.getAsString("oldWriteHelperHttp"));

    // --- 264 side (262 proxy): reuse the proven WFS excluded-non-required acts, one revUp ---
    final var unifiedEnv =
        CollectionsKt.last(
                ReVoman.revUp(
                    (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
                    ReVomanConfigForWfs.AUTH_CONFIG,
                    ReVomanConfigForWfs.EXCLUDED_RESOURCES_POLICY_CONFIG,
                    ReVomanConfigForWfs.EXCLUDED_FIXTURE_CONFIG,
                    ReVomanConfigForWfs.EXCLUDED_SCHEDULE_CONFIG))
            .mutableEnv;
    final var unifiedHelperOutcome =
        SchedulerParityConfig.unifiedWriteOutcome(
            unifiedEnv.getAsString("excludedNonReqSchedulingStatus"), "201");

    // --- PARITY (write axis — the 1a verdict): both products book the non-required Excluded helper ---
    // Shape guard: the old-side helper booking returns a real 18-char ServiceAppointment id (08p prefix),
    // so BOOKED reflects a genuine persist, not an empty pass.
    assertThat(oldHelperEnv.getAsString("oldWriteHelperSaId")).hasLength(18);
    assertThat(oldHelperEnv.getAsString("oldWriteHelperSaId")).startsWith("08p");
    assertThat(oldHelperOutcome).isEqualTo(SchedulerParityConfig.WriteOutcome.BOOKED);
    assertThat(unifiedHelperOutcome).isEqualTo(SchedulerParityConfig.WriteOutcome.BOOKED);
    assertThat(oldHelperOutcome).isEqualTo(unifiedHelperOutcome);
    // Read-axis evidence (path-dependent, supporting): the OLD candidates read PRUNES the account-Excluded
    // resourceB (EXCLUDED) — the account block-list is applied on the candidates surface.
    assertThat(oldExcludedReadDecision).isEqualTo(SchedulerParityConfig.ReadDecision.EXCLUDED);
  }

  @Test
  void testHelperSkillsParity_1c_E2E() {
    SchedulerParityConfig.assumeBothOrgCreds();

    // --- OLD side: read decisions (single B, single A, multi helper), one revUp; then the helper write.
    // Each old-side revUp mirrors AUTH → FIXTURE (skills-helper: create-skill + graph) → GRANT
    // (Lightning-Scheduler user-access, else the classic engine prunes the resources → dead read/write) →
    // READ/BOOK.
    final var oldReadEnv =
        CollectionsKt.last(
                ReVoman.revUp(
                    (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
                    SchedulerParityConfig.OLD_AUTH_CONFIG,
                    SchedulerParityConfig.OLD_SKILLS_FIXTURE_CONFIG,
                    SchedulerParityConfig.OLD_GRANT_LS_ACCESS_CONFIG,
                    SchedulerParityConfig.OLD_GET_SLOTS_SKILLS_CONFIG))
            .mutableEnv;
    // Single-resource path: skill-less B alone is skill-checked → EXCLUDED; skilled A alone → INCLUDED.
    final var oldReadBOnlyDecision =
        SchedulerParityConfig.oldReadDecision(oldReadEnv.getAsString("oldReadBOnlySlotCount"));
    // Multi-resource path: B is a non-primary helper, skill-checked on the PRIMARY only → B ESCAPES.
    final var oldReadMultiHelperDecision =
        SchedulerParityConfig.oldReadDecision(
            oldReadEnv.getAsString("oldReadMultiHelperSlotCount"));
    // Non-vacuity guard: skilled A alone MUST offer > 0 slots, proving the fixture + grant are live.
    // Without this, a silently-failed grant would zero BOTH reads → EXCLUDED would pass for the WRONG
    // reason (dead read, not the missing skill).
    assertThat(oldReadEnv.getAsString("oldReadAOnlySlotCount")).isNotEqualTo("0");

    final var oldHelperEnv =
        CollectionsKt.last(
                ReVoman.revUp(
                    (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
                    SchedulerParityConfig.OLD_AUTH_CONFIG,
                    SchedulerParityConfig.OLD_SKILLS_FIXTURE_CONFIG,
                    SchedulerParityConfig.OLD_GRANT_LS_ACCESS_CONFIG,
                    SchedulerParityConfig.OLD_BOOK_SKILLS_NON_REQUIRED_CONFIG))
            .mutableEnv;
    final var oldHelperOutcome =
        SchedulerParityConfig.oldWriteOutcome(
            oldHelperEnv.getAsString("oldWriteHelperSaId"),
            oldHelperEnv.getAsString("oldWriteHelperHttp"));

    // --- 264 side (262 proxy): reuse the proven WFS skills acts, one revUp. The non-required skill-less
    // helper escapes MatchSkills on the required-only write path → Success (skillsNonReqSchedulingStatus).
    final var unifiedEnv =
        CollectionsKt.last(
                ReVoman.revUp(
                    (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
                    ReVomanConfigForWfs.AUTH_CONFIG,
                    ReVomanConfigForWfs.MATCH_SKILLS_POLICY_CONFIG,
                    ReVomanConfigForWfs.SKILLS_SKILL_FIXTURE_CONFIG,
                    ReVomanConfigForWfs.SKILLS_FIXTURE_CONFIG,
                    ReVomanConfigForWfs.SKILLS_SCHEDULE_CONFIG))
            .mutableEnv;
    final var unifiedHelperOutcome =
        SchedulerParityConfig.unifiedWriteOutcome(
            unifiedEnv.getAsString("skillsNonReqSchedulingStatus"), "201");

    // --- The OLD engine HAS the Skills rule (single-resource path proof) ---
    // Skill-less B named the SOLE required resource is skill-checked → EXCLUDED (0 slots).
    assertThat(oldReadBOnlyDecision).isEqualTo(SchedulerParityConfig.ReadDecision.EXCLUDED);

    // --- PARITY (helper escapes; PARTIAL — different mechanism) ---
    // Shape guard: the old-side helper booking returns a real 18-char, 08p ServiceAppointment id, so
    // BOOKED reflects a genuine persist, not an empty pass.
    assertThat(oldHelperEnv.getAsString("oldWriteHelperSaId")).hasLength(18);
    assertThat(oldHelperEnv.getAsString("oldWriteHelperSaId")).startsWith("08p");
    // OLD non-required skill-less helper BOOKS (not skill-fitness-checked as a non-primary helper) …
    assertThat(oldHelperOutcome).isEqualTo(SchedulerParityConfig.WriteOutcome.BOOKED);
    // … and on the read the multi helper (A primary + B required) is INCLUDED (B escapes → slots > 0),
    // the primary-only-skill-matching mechanism made observable on the read axis too.
    assertThat(oldReadMultiHelperDecision).isEqualTo(SchedulerParityConfig.ReadDecision.INCLUDED);
    // 264 non-required skill-less helper also BOOKS (escapes MatchSkills on the required-only path) …
    assertThat(unifiedHelperOutcome).isEqualTo(SchedulerParityConfig.WriteOutcome.BOOKED);
    // … so OLD and 264 AGREE on the observable helper-escape write outcome (mechanism differs → PARTIAL).
    assertThat(oldHelperOutcome).isEqualTo(unifiedHelperOutcome);
  }

  @Test
  void testHelperRequiredResourceParity_1_4_E2E() {
    SchedulerParityConfig.assumeBothOrgCreds();

    // --- OLD side: candidates read (account demands resourceB, only resourceA offered) ---
    // AUTH → FIXTURE (Account ResourcePreference Required=resourceB + policy ShouldEnforceRequiredResource)
    // → GRANT (Lightning-Scheduler user-access, else the classic engine prunes the resources → dead read)
    // → getAppointmentCandidates (A-only probe + B-required control).
    final var oldReadEnv =
        CollectionsKt.last(
                ReVoman.revUp(
                    (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
                    SchedulerParityConfig.OLD_AUTH_CONFIG,
                    SchedulerParityConfig.OLD_REQUIRED_HELPER_FIXTURE_CONFIG,
                    SchedulerParityConfig.OLD_GRANT_LS_ACCESS_CONFIG,
                    SchedulerParityConfig.OLD_GET_CANDIDATES_REQUIRED_CONFIG))
            .mutableEnv;
    final var oldReadOutcome =
        SchedulerParityConfig.oldCandidatesOutcome(
            oldReadEnv.getAsString("oldCandidatesWithBReqCount"),
            oldReadEnv.getAsString("oldCandidatesWithBReqHttp"),
            oldReadEnv.getAsString("oldCandidatesWithBReqErrorCode"));
    // Non-vacuity guard: the B-required control (the account-required worker, offered) MUST return >0
    // candidates, proving the fixture + the required-resources policy + the Lightning-Scheduler grant are
    // all live. Without this, a dead read would make the A-only 0-count REFUSE pass for the WRONG reason.
    assertThat(oldReadEnv.getAsString("oldCandidatesBReqCount")).isNotEqualTo("0");

    // --- OLD side: write (resourceA required+primary + resourceB non-required helper) ---
    final var oldWriteEnv =
        CollectionsKt.last(
                ReVoman.revUp(
                    (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
                    SchedulerParityConfig.OLD_AUTH_CONFIG,
                    SchedulerParityConfig.OLD_REQUIRED_HELPER_FIXTURE_CONFIG,
                    SchedulerParityConfig.OLD_GRANT_LS_ACCESS_CONFIG,
                    SchedulerParityConfig.OLD_BOOK_REQUIRED_NON_REQUIRED_CONFIG))
            .mutableEnv;
    final var oldWriteOutcome =
        SchedulerParityConfig.oldWriteOutcome(
            oldWriteEnv.getAsString("oldWriteReqHelperSaId"),
            oldWriteEnv.getAsString("oldWriteReqHelperHttp"));

    // --- 264 side (262 proxy): reuse the proven WFS Required-resources acts (violating + control) ---
    final var unifiedEnv =
        CollectionsKt.last(
                ReVoman.revUp(
                    (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
                    ReVomanConfigForWfs.AUTH_CONFIG,
                    ReVomanConfigForWfs.REQUIRED_RESOURCES_POLICY_CONFIG,
                    ReVomanConfigForWfs.REQUIRED_NON_REQUIRED_FIXTURE_CONFIG,
                    ReVomanConfigForWfs.REQUIRED_NON_REQUIRED_SATISFIER_VIOLATING_SCHEDULE_CONFIG,
                    ReVomanConfigForWfs.REQUIRED_SATISFIER_BOOKABLE_SCHEDULE_CONFIG))
            .mutableEnv;
    final var unifiedViolatingOutcome =
        SchedulerParityConfig.unifiedWriteOutcomeFromErrorCode(
            unifiedEnv.getAsString("requiredNonReqSatisfierStatus"),
            unifiedEnv.getAsString("requiredNonReqSatisfierErrorCode"));

    // --- 264 CRASH asserted verbatim (the locked-in 262 serviceTerritoryMembers NPE) ---
    assertThat(unifiedEnv.getAsString("requiredNonReqSatisfierErrorCode"))
        .isEqualTo("INTERNAL_SERVER_ERROR");
    assertThat(unifiedEnv.getAsString("requiredNonReqSatisfierErrorMessage"))
        .contains("serviceTerritoryMembers");
    assertThat(unifiedViolatingOutcome).isEqualTo(SchedulerParityConfig.WriteOutcome.CRASHED);
    // 264 control (resourceB flipped to required) → no RequiredResources satisfaction error.
    assertThat(unifiedEnv.getAsString("requiredSatisfierControlErrorCode"))
        .isNotEqualTo("RequiredResources");

    // --- OLD outcomes pinned verbatim (characterization, NOT forced to match 264) ---
    // OLD read cleanly REFUSES: the account-required pool filter drops the non-required resourceA → 0
    // candidates, HTTP 200, no INTERNAL_SERVER_ERROR (the classic ServiceTerritory never NPEs on
    // serviceTerritoryMembers). This is the crash-vs-clean DIVERGENCE from the 264 write.
    assertThat(oldReadEnv.getAsString("oldCandidatesWithBReqCount")).isEqualTo("0");
    assertThat(oldReadEnv.getAsString("oldCandidatesWithBReqErrorCode")).isNotEqualTo("INTERNAL_SERVER_ERROR");
    assertThat(oldReadOutcome).isEqualTo(SchedulerParityConfig.WriteOutcome.REFUSED);
    // OLD write ALSO cleanly REFUSES (LIVE-OBSERVED): HTTP 400, errorCode=INTERNAL_ERROR, message "We
    // couldn't find any resources for your request." — no SA id, and crucially NOT a 500 crash. The classic
    // book path's internal slot-gen consults the account-required-resource enforcement and cannot satisfy
    // the account's demand for resourceB (present only as a non-required helper) → no resources found →
    // refuse. (The initial hypothesis that the write would BOOK resourceA outright was refuted by the org.)
    assertThat(oldWriteEnv.getAsString("oldWriteReqHelperSaId")).isEmpty();
    assertThat(oldWriteEnv.getAsString("oldWriteReqHelperHttp")).isEqualTo("400");
    assertThat(oldWriteEnv.getAsString("oldWriteReqHelperErrorCode")).isEqualTo("INTERNAL_ERROR");
    assertThat(oldWriteEnv.getAsString("oldWriteReqHelperErrorMessage"))
        .contains("couldn't find any resources");
    assertThat(oldWriteOutcome).isEqualTo(SchedulerParityConfig.WriteOutcome.REFUSED);

    // --- DIVERGENCE recorded: OLD refuses CLEANLY on BOTH axes (read 200/empty candidates; write 400
    // INTERNAL_ERROR) while the 264 write CRASHES (500 INTERNAL_SERVER_ERROR serviceTerritoryMembers NPE).
    // OLD ≠ 264 on both axes — a crash-vs-clean divergence, asserted verbatim, NOT forced to a green. ---
    assertThat(oldReadOutcome).isNotEqualTo(unifiedViolatingOutcome);
    assertThat(oldWriteOutcome).isNotEqualTo(unifiedViolatingOutcome);
  }
}
