/*
 * Copyright 2026 salesforce.com, inc.
 * All Rights Reserved
 * Company Confidential
 */

package com.salesforce.revoman.integration.core.scheduler;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static com.salesforce.revoman.integration.core.scheduler.SchedulerParityConfig.OLD_AUTH_CONFIG;

import com.salesforce.revoman.ReVoman;
import com.salesforce.revoman.input.config.Kick;
import com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs;
import kotlin.collections.CollectionsKt;
import org.junit.jupiter.api.Test;

/**
 * Scheduler ↔ Unified {@code 1.*} non-required resource-fitness parity — differential tests. Each
 * scenario diffs the OLD Salesforce Scheduler decision against the Unified decision on BOTH the
 * read (INCLUDED/EXCLUDED) and write (BOOKED/REFUSED/CRASHED) axes.
 */
class SchedulerVsUnifiedParityE2ETest {

  // * NOTE 2026-07-03 gopal.akshintala: Decision 4z demote-arm expectations, PINNED to the
  // LIVE-observed
  // * scheduler-org result (set after the first clean run — the classic engine's PATCH-demote
  // behavior is
  // * characterized, not assumed). See testRescheduleNoPrimaryParity_4z_E2E's Arm 2.
  private static final String EXPECTED_DEMOTE_HTTP = "204";
  private static final String EXPECTED_DEMOTE_PRIMARY_COUNT = "0";
  private static final SchedulerParityConfig.NoPrimaryOutcome EXPECTED_DEMOTE_OUTCOME =
      SchedulerParityConfig.NoPrimaryOutcome.LEFT_NO_PRIMARY;

  @Test
  void schedulerOrgAuthBindsE2E() {
    SchedulerParityConfig.assumeBothOrgCreds();
    final var rundown = ReVoman.revUp(OLD_AUTH_CONFIG);
    final var env = rundown.mutableEnv;
    assertThat(env.getAsString("adminToken")).isNotEmpty();
    assertThat(env.getAsString("versionPath")).contains("/services/data/v");
  }

  /**
   * Decision 1.5 parity — a busy NON-required resource. OLD Salesforce Scheduler vs Unified OnSite
   * must agree on both axes: the busy non-required resource BOOKS (not availability-checked) while
   * a required busy control is REFUSED; and the read offers the fixture's slots only when the busy
   * resource is NOT a hard required constraint. Old side: public Scheduler REST ({@code POST
   * /scheduling/getAppointmentSlots} + {@code POST /connect/scheduling/service-appointments}).
   * Unified side: the existing WFS double-book Connect acts.
   *
   * <p>KEY PARITY FINDING (both products agree): a non-required resource BOOKS (it is not
   * availability-checked, so it may double-book) and is read-EXCLUDED when that same busy resource
   * is named a hard required constraint; the required-control write is REFUSED
   * (availability-checked). All four normalized verdicts align → 1.5 parity CONFIRMED.
   *
   * <p>Divergence, were it to appear, is a first-class finding: it would be asserted faithfully
   * (with a {@code <p>DIVERGENCE:} paragraph naming the mismatch) rather than forced to a green.
   *
   * <p>Old-side revUps mint 2 fresh {@code sched-res-*@revoman.org} users per run and never clean
   * up; three old-side revUps here (read + two write arms) → ~6 fresh users/run. The leading
   * success guard ({@code firstUnIgnoredUnsuccessfulStepReport() == null}) surfaces a rolled-back
   * fixture/grant (e.g. LICENSE_LIMIT_EXCEEDED) loudly at the fixture step rather than masking it
   * as a false verdict; the read/book acts carry {@code ignoreHTTPStatusUnsuccessful} so legit 400s
   * / empty reads do not trip it.
   */
  @Test
  void testHelperDoubleBookParity_1_5_E2E() {
    SchedulerParityConfig.assumeBothOrgCreds();

    // --- OLD side: read decision + write (non-required resource) + write (required control), fresh
    // revUps ---
    // Each old-side revUp mirrors the probe sequence: AUTH → FIXTURE → GRANT (Lightning-Scheduler
    // user-access, else the classic engine prunes the fixture resources → dead read/write) →
    // READ/BOOK.
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
    // Lightning-Scheduler user-access grant) is live. Without this, a silently-failed grant would
    // make
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

    // --- Unified side: reuse the proven WFS double-book acts, one revUp ---
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

    // --- PARITY: both products book the busy non-required resource (a non-required resource is NOT
    // availability-checked) ---
    // Shape guard (restored from the removed write probe): the old-side non-required resource
    // booking must return
    // a real
    // ServiceAppointment id (18-char, 08p prefix), so BOOKED reflects a genuine persist, not an
    // empty pass.
    assertThat(oldHelperEnv.getAsString("oldWriteHelperSaId")).hasLength(18);
    assertThat(oldHelperEnv.getAsString("oldWriteHelperSaId")).startsWith("08p");
    assertThat(oldHelperOutcome).isEqualTo(SchedulerParityConfig.WriteOutcome.BOOKED);
    assertThat(unifiedHelperOutcome).isEqualTo(SchedulerParityConfig.WriteOutcome.BOOKED);
    assertThat(oldHelperOutcome).isEqualTo(unifiedHelperOutcome);
    // Old read excludes the busy resource when it is a hard required id (the old-side availability
    // proof).
    assertThat(oldReadDecision).isEqualTo(SchedulerParityConfig.ReadDecision.EXCLUDED);
    // Both required controls (the busy resource named required) are REFUSED → the availability
    // proof.
    assertThat(oldControlOutcome).isEqualTo(SchedulerParityConfig.WriteOutcome.REFUSED);
    assertThat(unifiedControlOutcome).isEqualTo(SchedulerParityConfig.WriteOutcome.REFUSED);
    assertThat(oldControlOutcome).isEqualTo(unifiedControlOutcome);
    // Unified control (busy resource as required) is refused → the Unified availability proof.
    assertThat(unifiedEnv.getAsString("doubleBookRequiredControlSchedulingStatus"))
        .isNotEqualTo("Success");
  }

  /**
   * Scenario 1b (Territory) parity — a NON-required resource whose ServiceTerritoryMember coverage
   * does NOT include the booking window. OLD Salesforce Scheduler vs Unified OnSite must agree on
   * both axes: the out-of-territory non-required resource BOOKS (its territory is NOT
   * fitness-checked because it is non-required) while the SAME resourceB named a hard REQUIRED
   * constraint is REFUSED (the classic engine's territory/STM-membership filter runs only for
   * required resources); and the read offers the fixture's slots only when the out-of-territory
   * resource is NOT a hard required constraint. Old side: public Scheduler REST ({@code POST
   * /scheduling/getAppointmentSlots} + {@code POST /connect/scheduling/service-appointments}).
   * Unified side: the existing WFS territory Kicks ({@code TERRITORY_PARTIAL_POLICY_CONFIG} /
   * {@code TERRITORY_FIXTURE_CONFIG} / {@code TERRITORY_SCHEDULE_CONFIG}), which book the
   * non-required resource Success (the escape under test).
   *
   * <p>Constructibility: CLEANLY CONSTRUCTIBLE on the old read path. Both resources are fully
   * AVAILABLE over the 11:30-12:30 straddle-noon window (shared member OH 08-16 + Confirmed Shift
   * 08-16), so availability is NOT the discriminator — only territory coverage is: resourceB's
   * ServiceTerritoryMember is narrowed (by a follow-up PATCH after its Shift exists, so the
   * full-day Shift survives) to END at noon, so its membership OVERLAPS but does not CONTAIN the
   * straddle-noon window. The classic multi-resource read AND-intersects the required resources
   * over the STM-committed window → A+B (B required) = 0 slots, A-only > 0. This mirrors the WFS
   * territory fixture's partial-membership isolation shape.
   *
   * <p>KEY PARITY FINDING (both products agree): an out-of-territory non-required resource BOOKS
   * (it is not territory/membership-checked, so it may violate territory coverage) and is
   * read-EXCLUDED when that same resource is named a hard required constraint; the required-control
   * write is REFUSED (territory-checked). Old read EXCLUDED + old non-required resource BOOKED +
   * old required-control REFUSED + Unified non-required resource BOOKED → 1b territory parity
   * CONFIRMED.
   *
   * <p>A divergence, were it to appear, would be asserted faithfully rather than forced to a green.
   *
   * <p>Old-side revUps mint 2 fresh {@code sched-terr-*@revoman.org} users per run and never clean
   * up; three old-side revUps here (read + two write arms) → ~6 fresh users/run. The leading
   * success guard ({@code firstUnIgnoredUnsuccessfulStepReport() == null}) surfaces a rolled-back
   * fixture/grant loudly; the read/book acts carry {@code ignoreHTTPStatusUnsuccessful} so legit
   * 400s / empty reads do not trip it.
   */
  @Test
  void testHelperTerritoryParity_1b_E2E() {
    SchedulerParityConfig.assumeBothOrgCreds();

    // --- OLD side: read decision + write (non-required resource) + write (required control), fresh
    // revUps ---
    // Each old-side revUp mirrors the probe sequence: AUTH → FIXTURE (graph + STM-narrowing PATCH)
    // →
    // GRANT (Lightning-Scheduler user-access, else the classic engine prunes the fixture resources
    // →
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
    // Non-vacuity guard: the A-only (clean, in-territory) control read MUST offer >0 slots, proving
    // the
    // fixture (and the Lightning-Scheduler user-access grant) is live. Without this, a
    // silently-failed
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

    // --- Unified side: reuse the proven WFS territory acts, one revUp. The territory
    // scenario
    // has ONE act (the non-required resource booking Success is the escape under test); there is no
    // Unified
    // required-control act for territory, so the required→REFUSED axis is proven on the OLD side
    // only. ---
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

    // --- PARITY: both products book the out-of-territory non-required resource (a non-required
    // resource is NOT
    // territory-
    // checked) ---
    // Shape guard: the old-side non-required resource booking must return a real ServiceAppointment
    // id (18-char,
    // 08p
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
    // territory-checked proof (isolates the non-required resource Success to the non-required
    // flag).
    assertThat(oldControlOutcome).isEqualTo(SchedulerParityConfig.WriteOutcome.REFUSED);
    // Unified non-required resource (out-of-territory resource as non-required) books Success → the
    // Unified escape it
    // shares
    // with old.
    assertThat(unifiedEnv.getAsString("territoryNonReqSchedulingStatus")).isEqualTo("Success");
  }

  /**
   * Decision 1d parity — a NON-required resource whose ONLY disqualifier is its territory-member
   * ROLE: resourceB is a SECONDARY ({@code TerritoryType='S'}) member under a PRIMARY-ONLY
   * SchedulingPolicy ({@code ShouldUsePrimaryMembers=true, ShouldUseSecondaryMembers=false}). This
   * is the cleanest WorkingLocations isolation — resourceB IS a member (not a non-member exclusion)
   * and is fully AVAILABLE at the window (member OH 10-14 + Confirmed Shift 10-14 both cover the
   * 11:00-11:30 booking, the proven 1.5 double-book resourceA window), so its Secondary ROLE alone
   * excludes it. OLD Salesforce Scheduler vs Unified OnSite must agree: the Secondary non-required
   * resource BOOKS (a non-required resource is not eligibility-checked, so its Secondary role
   * escapes the WorkingLocations rule); the OLD read EXCLUDES that same Secondary resource when it
   * is a hard required constraint (the primary-only STM filter drops it), and the OLD
   * required-control write is REFUSED.
   *
   * <p>Old side: public Scheduler REST ({@code POST /scheduling/getAppointmentSlots} + {@code POST
   * /connect/scheduling/service-appointments}), each carrying {@code schedulingPolicyId} = the
   * seeded primary-only {@code AppointmentSchedulingPolicy}. The classic engine reads the
   * primary/secondary booleans straight off that policy entity and applies them as a
   * ServiceTerritoryMember SOQL filter ({@code
   * SchedulingDbUtil.createPrimarySecondarySTMWhereCondition} → {@code TerritoryType IN
   * ('P','R')}), on BOTH the read and the booking availability check — so a Secondary-only resource
   * is dropped by ROLE (JDWP/source-confirmed: {@code scheduling-impl/.../SchedulingDbUtil.java}).
   * Unified side: the existing WFS working-locations Connect act under the primary-only
   * WorkingTerritories(IsPrimaryLocationEnabled) policy, which books the non-required Secondary
   * non-required resource Success (the Unified non-required resource-escape finding).
   *
   * <p>KEY PARITY FINDING (both products agree): a Secondary-member non-required resource BOOKS (it
   * is not eligibility-checked, so its WorkingLocations role is never evaluated) and is
   * read-EXCLUDED / write- REFUSED when that same Secondary resource is named a hard required
   * constraint → 1d parity CONFIRMED.
   *
   * <p>A divergence, were it to appear, is a first-class finding asserted faithfully rather than
   * forced to a green.
   *
   * <p>Old-side revUps mint 2 fresh {@code sched-wl-*@revoman.org} users per run and never clean
   * up; three old-side revUps here (read + two write arms) → ~6 fresh users/run. The leading
   * success guard ({@code firstUnIgnoredUnsuccessfulStepReport() == null}) surfaces a rolled-back
   * fixture/grant loudly at the fixture step; the read/book acts carry {@code
   * ignoreHTTPStatusUnsuccessful} so legit 400s / empty reads do not trip it.
   */
  @Test
  void testHelperWorkingLocationsParity_1d_E2E() {
    SchedulerParityConfig.assumeBothOrgCreds();

    // --- OLD side: read decision + write (non-required resource) + write (required control), fresh
    // revUps ---
    // Each old-side revUp mirrors the probe sequence: AUTH → FIXTURE (seeds the primary-only policy
    // + P/S
    // member graph) → GRANT (Lightning-Scheduler user-access, else the classic engine prunes the
    // fixture
    // resources → dead read/write) → READ/BOOK (each passing schedulingPolicyId = the primary-only
    // policy).
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
    // Non-vacuity guard: the A-only (Primary member) control read MUST offer >0 slots, proving the
    // fixture,
    // the primary-only policy, and the Lightning-Scheduler user-access grant are all live. Without
    // this, a
    // silently-failed grant or an over-broad policy would make BOTH reads 0 → EXCLUDED would pass
    // for the
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

    // --- Unified side: reuse the proven WFS working-locations acts under the primary-only
    // policy ---
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

    // --- PARITY: both products book the Secondary-member non-required resource (a non-required
    // resource is NOT
    // eligibility-checked) ---
    // Shape guard: the old-side non-required resource booking must return a real ServiceAppointment
    // id (18-char,
    // 08p
    // prefix), so BOOKED reflects a genuine persist, not an empty pass.
    assertThat(oldHelperEnv.getAsString("oldWriteHelperSaId")).hasLength(18);
    assertThat(oldHelperEnv.getAsString("oldWriteHelperSaId")).startsWith("08p");
    assertThat(oldHelperOutcome).isEqualTo(SchedulerParityConfig.WriteOutcome.BOOKED);
    assertThat(unifiedHelperOutcome).isEqualTo(SchedulerParityConfig.WriteOutcome.BOOKED);
    assertThat(oldHelperOutcome).isEqualTo(unifiedHelperOutcome);
    // Old read excludes the Secondary resource when it is a hard required id (the old-side ROLE
    // proof).
    assertThat(oldReadDecision).isEqualTo(SchedulerParityConfig.ReadDecision.EXCLUDED);
    // Old required control (Secondary resource named required) is REFUSED → the old-side
    // ROLE-enforcement proof.
    assertThat(oldControlOutcome).isEqualTo(SchedulerParityConfig.WriteOutcome.REFUSED);
    // Unified non-required resource (non-required Secondary member) books Success → the Unified
    // WorkingLocations
    // non-required resource-escape finding.
    assertThat(unifiedEnv.getAsString("workingLocationsSecondaryNonReqSchedulingStatus"))
        .isEqualTo("Success");
  }

  /**
   * Decision 1a parity — a NON-required resource on the ACCOUNT's block-list (an account
   * ResourcePreference of PreferenceType=Excluded naming resourceB). resourceA is clean; only the
   * Excluded rule is in play. Does the scheduling engine fitness-check the non-required resource
   * against the account block-list on the WRITE (book) path?
   *
   * <p>KEY PARITY FINDING (both products agree on the write axis): the non-required Excluded
   * non-required resource is NOT fitness-checked on the book path, so the SA BOOKS on both engines.
   * Unified: the WFS schedule-excluded-non-required act returns schedulingStatus=Success (the
   * non-required resource escapes ExcludedResources). OLD Salesforce Scheduler: {@code POST
   * /connect/scheduling/service-appointments} validates only the required/primary resources and
   * passes {@code accountResourcePreferences=null} (ServiceAppointmentHelper.areResourcesAvailable
   * → SchedulingServiceImpl.getAppointmentSlots), so the account ResourcePreference(Excluded) is
   * never evaluated on write → the SA books with an {@code 08p} serviceAppointmentId. → WRITE
   * parity CONFIRMED (both BOOKED).
   *
   * <p>PARTIAL / path-dependent constructibility (read axis): on the OLD engine the account
   * ResourcePreference(Excluded) is enforced ONLY on the {@code POST
   * /scheduling/getAppointmentCandidates} path (SchedulingServiceImpl.getAppointmentCandidates →
   * loadAccountResourcePreferences(accountId) → SchedulingDbUtil.checkResourcePrefs PRUNES the
   * excluded SR), NOT on plain getAppointmentSlots (accountResourcePreferences=null →
   * checkResourcePrefs short-circuits true, SchedulingDbUtil ~L706-708). So the OLD read probe uses
   * getAppointmentCandidates: WITH accountId, resourceB is pruned (absent from every candidate's
   * {@code resources[]}) → EXCLUDED; the NO-account control keeps resourceB present (non-vacuity:
   * the fixture is live and resourceB is a real, available candidate — its WITH-account absence is
   * caused by the Excluded pref, not a dead read). NOTE the read axis is thus a candidate- pruning
   * decision (a DIFFERENT surface from the book-time "non-required resource fitness" the write
   * asks); it is constructible on the candidates path but does not have a clean
   * non-required-vs-required control there (getAppointmentCandidates has no per-resource
   * required/non-required input). The write axis carries the true 1a parity verdict; the read is
   * captured faithfully as supporting evidence, not forced to mirror the write.
   *
   * <p>Old-side revUps mint 2 fresh {@code sched-excl-*@revoman.org} users per run and never clean
   * up; two old-side revUps here (candidates read + book) → ~4 fresh users/run. The leading success
   * guard ({@code firstUnIgnoredUnsuccessfulStepReport() == null}) surfaces a rolled-back
   * fixture/grant loudly at the fixture step; the read/book acts carry {@code
   * ignoreHTTPStatusUnsuccessful} so legit non-2xx / empty reads do not trip it.
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
                    SchedulerParityConfig.OLD_GET_CANDIDATES_EXCLUDED_CONFIG,
                    SchedulerParityConfig.OLD_GET_SLOTS_EXCLUDED_CONFIG))
            .mutableEnv;
    final var oldExcludedReadDecision =
        SchedulerParityConfig.oldCandidatesReadDecision(
            oldReadEnv.getAsString("oldCandidatesWithAcctResourceBPresent"));
    // Non-vacuity: the NO-account control MUST keep resourceB present (proves the fixture is live
    // and
    // resourceB is a real candidate at the window — so its WITH-account absence is the Excluded
    // pref,
    // not a dead read / silently-failed grant). resourceA must also be a candidate in the
    // WITH-account read.
    assertThat(oldReadEnv.getAsString("oldCandidatesNoAcctResourceBPresent")).isEqualTo("1");
    assertThat(oldReadEnv.getAsString("oldCandidatesWithAcctResourceAPresent")).isEqualTo("1");

    // --- OLD side: write (book) the non-required Excluded non-required resource, fresh revUp ---
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

    // --- Unified side: reuse the proven WFS excluded-non-required acts, one revUp ---
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

    // --- PARITY (write axis — the 1a verdict): both products book the non-required Excluded
    // non-required resource
    // ---
    // Shape guard: the old-side non-required resource booking returns a real 18-char
    // ServiceAppointment id (08p
    // prefix),
    // so BOOKED reflects a genuine persist, not an empty pass.
    assertThat(oldHelperEnv.getAsString("oldWriteHelperSaId")).hasLength(18);
    assertThat(oldHelperEnv.getAsString("oldWriteHelperSaId")).startsWith("08p");
    assertThat(oldHelperOutcome).isEqualTo(SchedulerParityConfig.WriteOutcome.BOOKED);
    assertThat(unifiedHelperOutcome).isEqualTo(SchedulerParityConfig.WriteOutcome.BOOKED);
    assertThat(oldHelperOutcome).isEqualTo(unifiedHelperOutcome);
    // Read-axis evidence (path-dependent, supporting): the OLD candidates read PRUNES the
    // account-Excluded
    // resourceB (EXCLUDED) — the account block-list is applied on the candidates surface.
    assertThat(oldExcludedReadDecision).isEqualTo(SchedulerParityConfig.ReadDecision.EXCLUDED);
    // Slots-path COMPLEMENT (proves the Excluded list is candidates-only, not enforced on
    // getAppointmentSlots): the SAME account-excluded resourceB, read via getAppointmentSlots WITH
    // accountId, still returns > 0 slots - the slots path passes accountResourcePreferences=null
    // (SchedulingServiceImpl:493) so checkResourcePrefs never prunes it. Contrast the candidates
    // read
    // above, which hides it. resourceB is genuinely available, so > 0 here is the un-pruned
    // resource,
    // not a coincidence.
    assertThat(Integer.parseInt(oldReadEnv.getAsString("oldSlotsExcludedBWithAcctCount")))
        .isGreaterThan(0);

    // --- Unified slots-read (262): the SAME account-excluded resource, read via
    // get-appointment-slots.
    // Unlike Scheduler (candidates-only enforcement), Unified applies Excluded UNIFORMLY - all
    // reads and
    // the write share ResourceManagementService.buildServiceTerritoryMap -> checkResourcePrefs - so
    // the
    // Unified slots read PRUNES the excluded resource (0 slots). This is the read-axis DIVERGENCE
    // from
    // Scheduler's >0 above. Reuses the proven WFS get-slots-excluded-violating act (the same one
    // WfsRulesParityE2ETest.testExcludedResourcesReadWriteParityE2E asserts reads 0).
    final var unifiedReadEnv =
        CollectionsKt.last(
                ReVoman.revUp(
                    (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
                    ReVomanConfigForWfs.AUTH_CONFIG,
                    ReVomanConfigForWfs.EXCLUDED_RESOURCES_POLICY_CONFIG,
                    ReVomanConfigForWfs.EXCLUDED_FIXTURE_CONFIG,
                    ReVomanConfigForWfs.GET_SLOTS_EXCLUDED_VIOLATING_CONFIG))
            .mutableEnv;
    // Unified PRUNES on the slots read (0 slots) where Scheduler does not (>0) - the read-axis
    // divergence.
    assertThat(unifiedReadEnv.getAsString("excludedReadViolatingSlotCount")).isEqualTo("0");
  }

  /**
   * Decision 1a REQUIRED-excluded variant - the account-Excluded resource sent as the SINGLE
   * required+primary resource on the WRITE. Unlike the 1a non-required helper (both products BOOK,
   * parity), this DIVERGES: OLD Salesforce Scheduler BOOKS it, Unified REJECTS it.
   *
   * <p>Root cause (read from 262 Core source). OLD: the classic book path
   * (ServiceAppointmentHelper.areResourcesAvailable to SchedulingServiceImpl.getAppointmentSlots to
   * getAppointmentSlotsForTerritoryWorkTypes) passes accountResourcePreferences=null
   * (SchedulingServiceImpl.java:493), so SchedulingDbUtil.checkResourcePrefs short-circuits true
   * (SchedulingDbUtil.java:706-709) and the account Excluded list is NEVER evaluated on write, so
   * the SA BOOKS even though the required+primary resource is account-excluded. UNIFIED: the
   * schedule write validates via SlotAvailabilityChecker.isSlotAvailable, which re-invokes the SAME
   * pruned InBusinessGetSlotsHandler read (ResourceManagementService.buildServiceTerritoryMap to
   * checkResourcePrefs prunes the excluded SR), so the excluded resource yields no matching slot
   * and the write is REJECTED (schedulingStatus != Success). A read/write consistency difference on
   * the account Excluded rule, distinct from the non-required-helper escape in 1a.
   *
   * <p>resourceB is genuinely available on BOTH orgs (Confirmed Shift 08-16, Primary STM), so the
   * account Excluded rule is the ONLY thing that can stop the write - isolating the divergence.
   *
   * <p>Unified side reuses the proven WFS excluded-required violating write act
   * (SCHEDULE_EXCLUDED_VIOLATING_CONFIG), the same one WfsRulesParityE2ETest
   * .testExcludedResourcesReadWriteParityE2E asserts rejects. Live-observed; 1a is crash-free.
   */
  @Test
  void testRequiredExcludedWriteDivergence_1a_req_E2E() {
    SchedulerParityConfig.assumeBothOrgCreds();

    // --- OLD side: book the account-Excluded resource as required+primary, fresh revUp -> BOOKED
    // ---
    final var oldEnv =
        CollectionsKt.last(
                ReVoman.revUp(
                    (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
                    SchedulerParityConfig.OLD_AUTH_CONFIG,
                    SchedulerParityConfig.OLD_EXCLUDED_FIXTURE_CONFIG,
                    SchedulerParityConfig.OLD_GRANT_LS_ACCESS_CONFIG,
                    SchedulerParityConfig.OLD_BOOK_EXCLUDED_REQUIRED_CONFIG))
            .mutableEnv;
    final var oldReqExcludedOutcome =
        SchedulerParityConfig.oldWriteOutcome(
            oldEnv.getAsString("oldReqExcludedWriteSaId"),
            oldEnv.getAsString("oldReqExcludedWriteHttp"));

    // --- Unified side: schedule the account-Excluded resource as required+primary -> REJECTED ---
    final var unifiedEnv =
        CollectionsKt.last(
                ReVoman.revUp(
                    (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
                    ReVomanConfigForWfs.AUTH_CONFIG,
                    ReVomanConfigForWfs.EXCLUDED_RESOURCES_POLICY_CONFIG,
                    ReVomanConfigForWfs.EXCLUDED_FIXTURE_CONFIG,
                    ReVomanConfigForWfs.SCHEDULE_EXCLUDED_VIOLATING_CONFIG))
            .mutableEnv;

    // --- DIVERGENCE (write axis): OLD BOOKS the account-excluded required resource; Unified
    // REJECTS.
    // Shape guard: the old-side booking returns a real 18-char ServiceAppointment id (08p prefix),
    // so BOOKED reflects a genuine persist, not an empty pass.
    assertThat(oldEnv.getAsString("oldReqExcludedWriteSaId")).hasLength(18);
    assertThat(oldEnv.getAsString("oldReqExcludedWriteSaId")).startsWith("08p");
    assertThat(oldReqExcludedOutcome).isEqualTo(SchedulerParityConfig.WriteOutcome.BOOKED);
    // Unified rejects the same required-excluded write (schedulingStatus != Success).
    assertThat(unifiedEnv.getAsString("excludedWriteViolatingStatus")).isNotEqualTo("Success");
    // The products DISAGREE on the write axis - the divergence, asserted verbatim.
    assertWithMessage("required-excluded write: OLD should BOOK, Unified should REJECT")
        .that(oldReqExcludedOutcome)
        .isEqualTo(SchedulerParityConfig.WriteOutcome.BOOKED);
  }

  @Test
  void testHelperSkillsParity_1c_E2E() {
    SchedulerParityConfig.assumeBothOrgCreds();

    // --- OLD side: read decisions (single B, single A, multi non-required resource), one revUp;
    // then the non-required resource
    // write.
    // Each old-side revUp mirrors AUTH → FIXTURE (skills-helper: create-skill + graph) → GRANT
    // (Lightning-Scheduler user-access, else the classic engine prunes the resources → dead
    // read/write) →
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
    // Single-resource path: skill-less B alone is skill-checked → EXCLUDED; skilled A alone →
    // INCLUDED.
    final var oldReadBOnlyDecision =
        SchedulerParityConfig.oldReadDecision(oldReadEnv.getAsString("oldReadBOnlySlotCount"));
    // Multi-resource path: B is a non-primary non-required resource, skill-checked on the PRIMARY
    // only → B
    // ESCAPES.
    final var oldReadMultiHelperDecision =
        SchedulerParityConfig.oldReadDecision(
            oldReadEnv.getAsString("oldReadMultiHelperSlotCount"));
    // Non-vacuity guard: skilled A alone MUST offer > 0 slots, proving the fixture + grant are
    // live.
    // Without this, a silently-failed grant would zero BOTH reads → EXCLUDED would pass for the
    // WRONG
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

    // --- Unified side: reuse the proven WFS skills acts, one revUp. The non-required
    // skill-less
    // non-required resource escapes MatchSkills on the required-only write path → Success
    // (skillsNonReqSchedulingStatus).
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

    // --- PARITY (non-required resource escapes; PARTIAL — different mechanism) ---
    // Shape guard: the old-side non-required resource booking returns a real 18-char, 08p
    // ServiceAppointment id,
    // so
    // BOOKED reflects a genuine persist, not an empty pass.
    assertThat(oldHelperEnv.getAsString("oldWriteHelperSaId")).hasLength(18);
    assertThat(oldHelperEnv.getAsString("oldWriteHelperSaId")).startsWith("08p");
    // OLD non-required skill-less non-required resource BOOKS (not skill-fitness-checked as a
    // non-primary non-required resource)
    // …
    assertThat(oldHelperOutcome).isEqualTo(SchedulerParityConfig.WriteOutcome.BOOKED);
    // … and on the read the multi non-required resource (A primary + B required) is INCLUDED (B
    // escapes → slots >
    // 0),
    // the primary-only-skill-matching mechanism made observable on the read axis too.
    assertThat(oldReadMultiHelperDecision).isEqualTo(SchedulerParityConfig.ReadDecision.INCLUDED);
    // Unified non-required skill-less non-required resource also BOOKS (escapes MatchSkills on the
    // required-only path)
    // …
    assertThat(unifiedHelperOutcome).isEqualTo(SchedulerParityConfig.WriteOutcome.BOOKED);
    // … so OLD and Unified AGREE on the observable non-required resource-escape write outcome
    // (mechanism differs →
    // PARTIAL).
    assertThat(oldHelperOutcome).isEqualTo(unifiedHelperOutcome);
  }

  @Test
  void testHelperRequiredResourceParity_1_4_E2E() {
    SchedulerParityConfig.assumeBothOrgCreds();

    // --- OLD side: candidates read (account demands resourceB, only resourceA offered) ---
    // AUTH → FIXTURE (Account ResourcePreference Required=resourceB + policy
    // ShouldEnforceRequiredResource)
    // → GRANT (Lightning-Scheduler user-access, else the classic engine prunes the resources → dead
    // read)
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
    // Non-vacuity guard: the B-required control (the account-required worker, offered) MUST return
    // >0
    // candidates, proving the fixture + the required-resources policy + the Lightning-Scheduler
    // grant are
    // all live. Without this, a dead read would make the A-only 0-count REFUSE pass for the WRONG
    // reason.
    assertThat(oldReadEnv.getAsString("oldCandidatesBReqCount")).isNotEqualTo("0");

    // --- OLD side: write (resourceA required+primary + resourceB non-required resource) ---
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

    // --- Unified side: reuse the proven WFS Required-resources acts (violating + control)
    // ---
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

    // --- Unified CRASH asserted verbatim (the live serviceTerritoryMembers NPE) ---
    assertThat(unifiedEnv.getAsString("requiredNonReqSatisfierErrorCode"))
        .isEqualTo("INTERNAL_SERVER_ERROR");
    assertThat(unifiedEnv.getAsString("requiredNonReqSatisfierErrorMessage"))
        .contains("serviceTerritoryMembers");
    assertThat(unifiedViolatingOutcome).isEqualTo(SchedulerParityConfig.WriteOutcome.CRASHED);
    // Unified control (resourceB flipped to required) → no RequiredResources satisfaction error.
    assertThat(unifiedEnv.getAsString("requiredSatisfierControlErrorCode"))
        .isNotEqualTo("RequiredResources");

    // --- OLD outcomes pinned verbatim (characterization, NOT forced to match Unified) ---
    // OLD read cleanly REFUSES: the account-required pool filter drops the non-required resourceA →
    // 0
    // candidates, HTTP 200, no INTERNAL_SERVER_ERROR (the classic ServiceTerritory never NPEs on
    // serviceTerritoryMembers). This is the crash-vs-clean DIVERGENCE from the Unified write.
    assertThat(oldReadEnv.getAsString("oldCandidatesWithBReqCount")).isEqualTo("0");
    assertThat(oldReadEnv.getAsString("oldCandidatesWithBReqErrorCode"))
        .isNotEqualTo("INTERNAL_SERVER_ERROR");
    assertThat(oldReadOutcome).isEqualTo(SchedulerParityConfig.WriteOutcome.REFUSED);
    // OLD write ALSO cleanly REFUSES (LIVE-OBSERVED): HTTP 400, errorCode=INTERNAL_ERROR, message
    // "We
    // couldn't find any resources for your request." — no SA id, and crucially NOT a 500 crash. The
    // classic
    // book path's internal slot-gen consults the account-required-resource enforcement and cannot
    // satisfy
    // the account's demand for resourceB (present only as a non-required resource) → no resources
    // found →
    // refuse. (The initial hypothesis that the write would BOOK resourceA outright was refuted by
    // the org.)
    assertThat(oldWriteEnv.getAsString("oldWriteReqHelperSaId")).isEmpty();
    assertThat(oldWriteEnv.getAsString("oldWriteReqHelperHttp")).isEqualTo("400");
    assertThat(oldWriteEnv.getAsString("oldWriteReqHelperErrorCode")).isEqualTo("INTERNAL_ERROR");
    assertThat(oldWriteEnv.getAsString("oldWriteReqHelperErrorMessage"))
        .contains("couldn't find any resources");
    assertThat(oldWriteOutcome).isEqualTo(SchedulerParityConfig.WriteOutcome.REFUSED);

    // --- DIVERGENCE recorded: OLD refuses CLEANLY on BOTH axes (read 200/empty candidates; write
    // 400
    // INTERNAL_ERROR) while the Unified write CRASHES (500 INTERNAL_SERVER_ERROR
    // serviceTerritoryMembers NPE).
    // OLD ≠ Unified on both axes — a crash-vs-clean divergence, asserted verbatim, NOT forced to a
    // green. ---
    assertThat(oldReadOutcome).isNotEqualTo(unifiedViolatingOutcome);
    assertThat(oldWriteOutcome).isNotEqualTo(unifiedViolatingOutcome);
  }

  /**
   * Decision 2 (slot promise) parity — is a SHOWN time-slot a real promise on the shared cheap
   * checks (skill / territory / free-busy / location / availability)? This is largely an
   * INTRA-product read↔write consistency check, run on BOTH products: over a SINGLE resource, an
   * AVAILABLE window (inside the resource's hours) and an UNAVAILABLE window (outside). The
   * read↔write agreement under test is: {@code read-offers-slot ⟺ write-succeeds} AND {@code
   * read-hides-slot ⟺ write-refused}. Both products call the same slot-gen for the read and
   * RE-CHECK availability on the book, so a shown slot is a genuine promise on the cheap checks —
   * the read and write paths agree.
   *
   * <p>Old side: public Scheduler REST over a single-resource fixture (resourceA available 08-16).
   * The AVAILABLE read ({@code POST /scheduling/getAppointmentSlots}, window 11:00-11:30 inside
   * 08-16) offers &gt;0 slots and the AVAILABLE book ({@code POST
   * /connect/scheduling/service-appointments}) Succeeds; the UNAVAILABLE read (window 17:00-17:30
   * outside 08-16) offers 0 slots and the UNAVAILABLE book is Refused. Unified side: the existing
   * WFS Decision-2 acts — {@code GET_SLOTS_PARITY_AVAILABLE} / {@code GET_SLOTS_PARITY_UNAVAILABLE}
   * read + {@code SCHEDULE_PARITY_AVAILABLE} / {@code SCHEDULE_PARITY_UNAVAILABLE} write over the
   * same availability-op-hours policy + required-non-required fixture (member OH 08-16), asserting
   * the identical read↔write agreement.
   *
   * <p>KEY PARITY FINDING (both products agree): a shown slot IS a promise on the shared
   * availability cheap check. On BOTH old Scheduler and Unified the read↔write agreement holds —
   * read INCLUDED ⟺ write BOOKED on the available window, read EXCLUDED ⟺ write REFUSED on the
   * unavailable window — and the two products' agreement MATCHES. Decision 2 parity CONFIRMED.
   *
   * <p>A divergence, were it to appear, is a first-class finding asserted faithfully rather than
   * forced to a green.
   *
   * <p>Constructibility: CLEANLY CONSTRUCTIBLE on the old read path — the single-resource
   * availability cheap check is exactly what old getAppointmentSlots runs, and the classic book
   * path re-checks it, so the old side proves read↔write on the same shared check the Unified side
   * does. (The doc's field-match "shown-but-rejected" half — Match Fields / Boolean / Extended
   * Match — is NOT characterizable on 262 through these endpoints; it is out of Decision 2's
   * cheap-check scope, see the WFS test javadoc.)
   *
   * <p>Old-side revUps mint 1 fresh {@code sched-promise-a-*@revoman.org} user per run and never
   * clean up; four old-side revUps here (avail read + unavail read + avail book + unavail book) →
   * ~4 fresh users/run. The leading success guard ({@code firstUnIgnoredUnsuccessfulStepReport() ==
   * null}) surfaces a rolled-back fixture/grant loudly at the fixture step; the read/book acts
   * carry {@code ignoreHTTPStatusUnsuccessful} so legit 400s / empty reads (a refusal) do not trip
   * it.
   */
  @Test
  void testSlotPromiseParity_2_E2E() {
    SchedulerParityConfig.assumeBothOrgCreds();

    // --- OLD side: read (available + unavailable), fresh revUp ---
    // AUTH → FIXTURE (single resource, available 08-16) → GRANT (Lightning-Scheduler user-access,
    // else the
    // classic engine prunes the resource → dead 0-slot read) → READ (available window + unavailable
    // window).
    final var oldReadEnv =
        CollectionsKt.last(
                ReVoman.revUp(
                    (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
                    SchedulerParityConfig.OLD_AUTH_CONFIG,
                    SchedulerParityConfig.OLD_PROMISE_FIXTURE_CONFIG,
                    SchedulerParityConfig.OLD_GRANT_LS_ACCESS_CONFIG,
                    SchedulerParityConfig.OLD_GET_SLOTS_PROMISE_AVAILABLE_CONFIG,
                    SchedulerParityConfig.OLD_GET_SLOTS_PROMISE_UNAVAILABLE_CONFIG))
            .mutableEnv;
    // oldReadDecision(count): "0" → EXCLUDED, else INCLUDED. Available window shows the resource;
    // the
    // unavailable window hides it.
    final var oldReadAvailDecision =
        SchedulerParityConfig.oldReadDecision(oldReadEnv.getAsString("oldPromiseReadAvailCount"));
    final var oldReadUnavailDecision =
        SchedulerParityConfig.oldReadDecision(oldReadEnv.getAsString("oldPromiseReadUnavailCount"));
    // Non-vacuity: the AVAILABLE read MUST offer >0 slots, proving the fixture + the
    // Lightning-Scheduler
    // grant are live. Without this, a silently-failed grant would zero BOTH reads → the unavailable
    // read-hides axis would pass for the WRONG reason (dead read, not the window being outside
    // hours).
    assertThat(oldReadEnv.getAsString("oldPromiseReadAvailCount")).isNotEqualTo("0");

    // --- OLD side: write (available), fresh revUp → Success (a shown slot is a promise) ---
    final var oldWriteAvailEnv =
        CollectionsKt.last(
                ReVoman.revUp(
                    (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
                    SchedulerParityConfig.OLD_AUTH_CONFIG,
                    SchedulerParityConfig.OLD_PROMISE_FIXTURE_CONFIG,
                    SchedulerParityConfig.OLD_GRANT_LS_ACCESS_CONFIG,
                    SchedulerParityConfig.OLD_BOOK_PROMISE_AVAILABLE_CONFIG))
            .mutableEnv;
    final var oldWriteAvailOutcome =
        SchedulerParityConfig.oldWriteOutcome(
            oldWriteAvailEnv.getAsString("oldPromiseWriteAvailSaId"),
            oldWriteAvailEnv.getAsString("oldPromiseWriteAvailHttp"));

    // --- OLD side: write (unavailable), fresh revUp → Refused (a hidden slot is not offered) ---
    final var oldWriteUnavailEnv =
        CollectionsKt.last(
                ReVoman.revUp(
                    (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
                    SchedulerParityConfig.OLD_AUTH_CONFIG,
                    SchedulerParityConfig.OLD_PROMISE_FIXTURE_CONFIG,
                    SchedulerParityConfig.OLD_GRANT_LS_ACCESS_CONFIG,
                    SchedulerParityConfig.OLD_BOOK_PROMISE_UNAVAILABLE_CONFIG))
            .mutableEnv;
    final var oldWriteUnavailOutcome =
        SchedulerParityConfig.oldWriteOutcome(
            oldWriteUnavailEnv.getAsString("oldPromiseWriteUnavailSaId"),
            oldWriteUnavailEnv.getAsString("oldPromiseWriteUnavailHttp"));

    // --- Unified side: reuse the proven WFS Decision-2 read + write acts, one revUp ---
    final var unifiedEnv =
        CollectionsKt.last(
                ReVoman.revUp(
                    (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
                    ReVomanConfigForWfs.AUTH_CONFIG,
                    ReVomanConfigForWfs.AVAILABILITY_OP_HOURS_POLICY_CONFIG,
                    ReVomanConfigForWfs.REQUIRED_NON_REQUIRED_FIXTURE_CONFIG,
                    ReVomanConfigForWfs.GET_SLOTS_PARITY_AVAILABLE_CONFIG,
                    ReVomanConfigForWfs.GET_SLOTS_PARITY_UNAVAILABLE_CONFIG,
                    ReVomanConfigForWfs.SCHEDULE_PARITY_UNAVAILABLE_CONFIG,
                    ReVomanConfigForWfs.SCHEDULE_PARITY_AVAILABLE_CONFIG))
            .mutableEnv;
    final var unifiedReadAvailDecision =
        SchedulerParityConfig.oldReadDecision(unifiedEnv.getAsString("parityReadAvailSlotCount"));
    final var unifiedReadUnavailDecision =
        SchedulerParityConfig.oldReadDecision(unifiedEnv.getAsString("parityReadUnavailSlotCount"));
    final var unifiedWriteAvailOutcome =
        SchedulerParityConfig.unifiedWriteOutcome(
            unifiedEnv.getAsString("parityWriteAvailStatus"), "201");
    final var unifiedWriteUnavailOutcome =
        SchedulerParityConfig.unifiedWriteOutcome(
            unifiedEnv.getAsString("parityWriteUnavailStatus"), "400");

    // --- PARITY: a shown slot is a real promise on BOTH products (read↔write agree on the cheap
    // checks) ---
    // Shape guard: the old-side AVAILABLE booking must return a real ServiceAppointment id
    // (18-char, 08p
    // prefix), so BOOKED reflects a genuine persist, not an empty pass.
    assertThat(oldWriteAvailEnv.getAsString("oldPromiseWriteAvailSaId")).hasLength(18);
    assertThat(oldWriteAvailEnv.getAsString("oldPromiseWriteAvailSaId")).startsWith("08p");
    // The UNAVAILABLE booking must NOT return an SA id (a genuine refusal, not a silent book
    // elsewhere).
    assertThat(oldWriteUnavailEnv.getAsString("oldPromiseWriteUnavailSaId")).isEmpty();

    // OLD read↔write agreement: available shown ⟺ booked; unavailable hidden ⟺ refused.
    assertThat(oldReadAvailDecision).isEqualTo(SchedulerParityConfig.ReadDecision.INCLUDED);
    assertThat(oldWriteAvailOutcome).isEqualTo(SchedulerParityConfig.WriteOutcome.BOOKED);
    assertThat(oldReadUnavailDecision).isEqualTo(SchedulerParityConfig.ReadDecision.EXCLUDED);
    assertThat(oldWriteUnavailOutcome).isEqualTo(SchedulerParityConfig.WriteOutcome.REFUSED);

    // Unified read↔write agreement: the SAME shape (shown ⟺ booked, hidden ⟺ refused).
    assertThat(unifiedReadAvailDecision).isEqualTo(SchedulerParityConfig.ReadDecision.INCLUDED);
    assertThat(unifiedWriteAvailOutcome).isEqualTo(SchedulerParityConfig.WriteOutcome.BOOKED);
    assertThat(unifiedReadUnavailDecision).isEqualTo(SchedulerParityConfig.ReadDecision.EXCLUDED);
    assertThat(unifiedWriteUnavailOutcome).isEqualTo(SchedulerParityConfig.WriteOutcome.REFUSED);

    // The two products' read↔write agreement MATCHES on both windows → Decision 2 parity CONFIRMED.
    assertThat(oldReadAvailDecision).isEqualTo(unifiedReadAvailDecision);
    assertThat(oldWriteAvailOutcome).isEqualTo(unifiedWriteAvailOutcome);
    assertThat(oldReadUnavailDecision).isEqualTo(unifiedReadUnavailDecision);
    assertThat(oldWriteUnavailOutcome).isEqualTo(unifiedWriteUnavailOutcome);
  }

  /**
   * Decision 3 parity — an AssignedResource sent OMITTING the {@code isRequiredResource} flag
   * entirely. A single resourceA (FREE at the window) is booked with NO {@code isRequiredResource}
   * (and no {@code isPrimaryResource}). Does OLD Salesforce Scheduler ALSO crash like Unified, or
   * does it degrade gracefully? Paired with the doc L142 control (a single {@code
   * isRequiredResource=true}, no {@code isPrimaryResource}) which MUST book valid on both engines.
   * Old side: public Scheduler REST ({@code POST /connect/scheduling/service-appointments}) over
   * the double-book fixture (fresh users + the Lightning-Scheduler grant; resourceA's member OH +
   * Confirmed Shift 10:00-14:00 cover the 11:00-11:30 window, so availability does not confound the
   * missing-flag probe). Unified side: the existing WFS Decision-3 Kicks ({@code
   * MISSING_REQUIRED_FLAG_SCHEDULE_CONFIG} → Unified crash, {@code
   * SINGLE_REQUIRED_NO_PRIMARY_SCHEDULE_CONFIG} → the L142 Success control).
   *
   * <p>KEY PARITY FINDING (crash-vs-clean DIVERGENCE — like 1.4, and even sharper): the Unified
   * write CRASHES on the omitted flag — errorCode {@code INTERNAL_SERVER_ERROR}, HTTP 500, a {@code
   * Boolean.booleanValue()} NPE ("because the return value of
   * common.api.soap.Entity.getField(String) is null"). The OLD classic engine does NOT crash: it
   * degrades gracefully with a CLEAN, TARGETED input-validation refusal — LIVE-OBSERVED {@code POST
   * /connect/scheduling/service-appointments} returns HTTP 400, errorCode {@code
   * INVALID_API_INPUT}, message "Specify a valid value for isRequiredResource and try again." (it
   * NAMES the exact missing field), no ServiceAppointment id and NOT a 500. So OLD ≠ Unified on the
   * write axis — a crash-vs-clean divergence (both pinned verbatim: the Unified raw NPE, and the
   * OLD field-naming clean refusal), NOT forced to a green. Both engines agree on the L142 control
   * (single required, no primary → BOOKED / Success), which isolates the divergence to the OMITTED
   * flag: the SAME omitted-flag payload the OLD engine catches at input validation and rejects
   * cleanly, Unified lets through to a raw server NPE.
   *
   * <p>Unified is expected to FIX this missing-flag NPE (treat a missing {@code isRequiredResource}
   * as not-required and handle it cleanly, or validate it up front as the OLD engine already does),
   * in which case Unified would converge toward the OLD clean-refusal/clean-handle behavior and
   * this scenario would flip from a crash-vs-clean divergence to parity. The divergence is asserted
   * as LIVE-OBSERVED, faithfully — the OLD engine's clean INVALID_API_INPUT refusal is arguably the
   * reference behavior the Unified fix should match.
   *
   * <p>Old-side revUps mint 2 fresh {@code sched-res-*@revoman.org} users per run and never clean
   * up; two old-side revUps here (missing-flag + L142 control) → ~4 fresh users/run. The leading
   * success guard ({@code firstUnIgnoredUnsuccessfulStepReport() == null}) surfaces a rolled-back
   * fixture/grant loudly at the fixture step; the book acts carry {@code
   * ignoreHTTPStatusUnsuccessful} so a legit non-2xx (a refusal or a 500 crash) is characterized
   * rather than tripping the guard.
   */
  @Test
  void testMissingRequiredFlagParity_3_E2E() {
    SchedulerParityConfig.assumeBothOrgCreds();

    // --- OLD side: Act A (missing isRequiredResource) + Act B (L142 control), fresh revUps ---
    // Each old-side revUp mirrors AUTH → FIXTURE (double-book, fresh users) → GRANT
    // (Lightning-Scheduler
    // user-access, else the classic engine prunes the fixture resources → dead book) → BOOK.
    final var oldMissingFlagEnv =
        CollectionsKt.last(
                ReVoman.revUp(
                    (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
                    SchedulerParityConfig.OLD_AUTH_CONFIG,
                    SchedulerParityConfig.OLD_DOUBLE_BOOK_FIXTURE_CONFIG,
                    SchedulerParityConfig.OLD_GRANT_LS_ACCESS_CONFIG,
                    SchedulerParityConfig.OLD_BOOK_MISSING_REQUIRED_FLAG_CONFIG))
            .mutableEnv;
    final var oldMissingFlagOutcome =
        SchedulerParityConfig.oldWriteOutcome(
            oldMissingFlagEnv.getAsString("oldMissingFlagSaId"),
            oldMissingFlagEnv.getAsString("oldMissingFlagHttp"));

    final var oldControlEnv =
        CollectionsKt.last(
                ReVoman.revUp(
                    (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
                    SchedulerParityConfig.OLD_AUTH_CONFIG,
                    SchedulerParityConfig.OLD_DOUBLE_BOOK_FIXTURE_CONFIG,
                    SchedulerParityConfig.OLD_GRANT_LS_ACCESS_CONFIG,
                    SchedulerParityConfig.OLD_BOOK_SINGLE_REQUIRED_NO_PRIMARY_CONFIG))
            .mutableEnv;
    final var oldControlOutcome =
        SchedulerParityConfig.oldWriteOutcome(
            oldControlEnv.getAsString("oldSingleRequiredNoPrimarySaId"),
            oldControlEnv.getAsString("oldSingleRequiredNoPrimaryHttp"));

    // --- Unified side: the WFS Decision-3 missing-flag crash + the L142 Success control
    // ---
    // Act A: single assignedResource OMITTING isRequiredResource → 262 missing-flag NPE crash.
    // Act B (doc L142): single isRequiredResource=true, NO isPrimaryResource, fresh resources →
    // Success.
    final var unifiedMissingFlagEnv =
        CollectionsKt.last(
                ReVoman.revUp(
                    (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
                    ReVomanConfigForWfs.AUTH_CONFIG,
                    ReVomanConfigForWfs.AVAILABILITY_OP_HOURS_POLICY_CONFIG,
                    ReVomanConfigForWfs.REQUIRED_NON_REQUIRED_FIXTURE_CONFIG,
                    ReVomanConfigForWfs.MISSING_REQUIRED_FLAG_SCHEDULE_CONFIG))
            .mutableEnv;
    final var unifiedMissingFlagOutcome =
        SchedulerParityConfig.unifiedWriteOutcomeFromErrorCode(
            unifiedMissingFlagEnv.getAsString("missingRequiredFlagStatus"),
            unifiedMissingFlagEnv.getAsString("missingRequiredFlagErrorCode"));

    final var unifiedControlEnv =
        CollectionsKt.last(
                ReVoman.revUp(
                    (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
                    ReVomanConfigForWfs.AUTH_CONFIG,
                    ReVomanConfigForWfs.AVAILABILITY_OP_HOURS_POLICY_CONFIG,
                    ReVomanConfigForWfs.REQUIRED_NON_REQUIRED_FIXTURE_CONFIG,
                    ReVomanConfigForWfs.SINGLE_REQUIRED_NO_PRIMARY_SCHEDULE_CONFIG))
            .mutableEnv;

    // --- Unified CRASH asserted verbatim (the live missing-flag Boolean.booleanValue NPE) ---
    assertThat(unifiedMissingFlagEnv.getAsString("missingRequiredFlagErrorCode"))
        .isEqualTo("INTERNAL_SERVER_ERROR");
    assertThat(unifiedMissingFlagEnv.getAsString("missingRequiredFlagErrorMessage"))
        .contains("Boolean.booleanValue()");
    assertThat(unifiedMissingFlagOutcome).isEqualTo(SchedulerParityConfig.WriteOutcome.CRASHED);
    // Unified L142 control (single required, no primary) → Success (proves the crash is the omitted
    // flag).
    assertThat(unifiedControlEnv.getAsString("singleRequiredNoPrimaryStatus")).isEqualTo("Success");

    // --- OLD outcomes pinned verbatim (characterization, NOT forced to match Unified) ---
    // OLD does NOT crash on the omitted isRequiredResource: it degrades gracefully with a CLEAN,
    // TARGETED
    // input-validation refusal — LIVE-OBSERVED HTTP 400, errorCode=INVALID_API_INPUT, message
    // "Specify a
    // valid value for isRequiredResource and try again." (it NAMES the exact missing field), no SA
    // id and
    // crucially NOT a 500 crash. This is the crash-vs-clean DIVERGENCE from the Unified write.
    assertThat(oldMissingFlagEnv.getAsString("oldMissingFlagSaId")).isEmpty();
    assertThat(oldMissingFlagEnv.getAsString("oldMissingFlagHttp")).isEqualTo("400");
    assertThat(oldMissingFlagEnv.getAsString("oldMissingFlagErrorCode"))
        .isEqualTo("INVALID_API_INPUT");
    assertThat(oldMissingFlagEnv.getAsString("oldMissingFlagErrorCode"))
        .isNotEqualTo("INTERNAL_SERVER_ERROR");
    assertThat(oldMissingFlagEnv.getAsString("oldMissingFlagErrorMessage"))
        .contains("isRequiredResource");
    assertThat(oldMissingFlagOutcome).isEqualTo(SchedulerParityConfig.WriteOutcome.REFUSED);
    // OLD L142 control (single required, no primary) BOOKS (18-char 08p id) → both engines agree on
    // the
    // control, isolating the divergence to the OMITTED flag (proving the fixture + grant are live
    // and
    // resourceA is bookable, so the Act-A refusal is the omitted flag, not a dead/unavailable
    // resource).
    assertThat(oldControlEnv.getAsString("oldSingleRequiredNoPrimarySaId")).hasLength(18);
    assertThat(oldControlEnv.getAsString("oldSingleRequiredNoPrimarySaId")).startsWith("08p");
    assertThat(oldControlOutcome).isEqualTo(SchedulerParityConfig.WriteOutcome.BOOKED);
    // Both engines agree the L142 control books.
    assertThat(oldControlOutcome)
        .isEqualTo(
            SchedulerParityConfig.unifiedWriteOutcome(
                unifiedControlEnv.getAsString("singleRequiredNoPrimaryStatus"), "201"));

    // --- DIVERGENCE recorded: OLD refuses CLEANLY (400 INVALID_API_INPUT, naming
    // isRequiredResource) on
    // the omitted flag while the Unified write CRASHES (500 INTERNAL_SERVER_ERROR
    // Boolean.booleanValue
    // NPE).
    // OLD ≠ Unified on the write axis — a crash-vs-clean divergence, asserted verbatim, NOT forced
    // to a
    // green. Both agree on the L142 control (BOOKED / Success), isolating the divergence to the
    // OMITTED
    // isRequiredResource flag. ---
    assertThat(oldMissingFlagOutcome).isNotEqualTo(unifiedMissingFlagOutcome);
  }

  /**
   * Decision 4 parity — two "primary" workers on ONE appointment are REJECTED. Multi-resource
   * scheduling requires EXACTLY one primary. Both products refuse a book that names two {@code
   * isPrimaryResource=true} AssignedResources; a one-primary control BOOKS. OLD Salesforce
   * Scheduler vs Unified OnSite agree on the OUTCOME (both REFUSED, no booking) — while the WHERE
   * each rejects differs (a documented difference, NOT a failure).
   *
   * <p>Unified: rejects UP FRONT at INPUT validation ({@code
   * ScheduleCommonValidator.validatePrimaryResourceConstraints}), before availability/persist — a
   * clean top-level {@code ConnectErrorCode INVALID_INPUT} / HTTP 400 with message "Only one of the
   * provided assigned resource can be a primary resource." No {@code appointments[0]} → status null
   * → no booking. Reuses the WFS {@code SCHEDULE_TWO_PRIMARY_CONFIG} act (over the
   * availability-op-hours policy + the required-non-required fixture's two resources).
   *
   * <p>OLD Salesforce Scheduler: the two-primary {@code POST
   * /connect/scheduling/service-appointments} is REFUSED (non-2xx, no {@code 08p}
   * serviceAppointmentId). LIVE-OBSERVED on the scheduler org (v67): the OLD connect API rejects at
   * INPUT validation with a top-level {@code ConnectErrorCode INVALID_API_INPUT} / HTTP 400 and
   * message "Only one assignedResource can have isPrimaryResource set to true. Check the request
   * and try again." So the WHERE turned out CLOSER than the field-integrity/save-time hypothesis in
   * the brief — both engines refuse at connect-API INPUT validation (pre-persist); only the
   * errorCode ({@code INVALID_API_INPUT} vs Unified's {@code INVALID_INPUT}) and the message TEXT
   * differ. Captured faithfully; the OUTCOME (REFUSED, no booking) is the parity. The fixture seeds
   * TWO clean, fully-available PRIMARY-eligible resources (both member OH + Confirmed Shift 10-14
   * over the 11:00-11:30 window) so availability is NOT the discriminator — only the primary-count
   * rule is; the one-primary control (A primary + B non-primary, both free) BOOKS (18-char {@code
   * 08p} id) → non-vacuity.
   *
   * <p>KEY PARITY FINDING (both products agree on the OUTCOME): two primaries on one appointment
   * are REFUSED (no booking) on BOTH engines, and a single-primary control BOOKS on the OLD surface
   * → Decision 4 parity CONFIRMED on the OUTCOME. WHERE-difference (minor, characterized): BOTH
   * reject at connect-API INPUT validation / HTTP 400 (pre-persist) — Unified with errorCode {@code
   * INVALID_INPUT} ("Only one of the provided assigned resource can be a primary resource."), OLD
   * with {@code INVALID_API_INPUT} ("Only one assignedResource can have isPrimaryResource set to
   * true…") — differing only in errorCode + message text, not the WHERE. Characterized, not forced
   * to a green.
   *
   * <p>Old-side revUps mint 2 fresh {@code sched-2prim-*@revoman.org} users per run and never clean
   * up; two old-side revUps here (two-primary probe + one-primary control) → ~4 fresh users/run.
   * The leading success guard ({@code firstUnIgnoredUnsuccessfulStepReport() == null}) surfaces a
   * rolled-back fixture/grant loudly at the fixture step; the book acts carry {@code
   * ignoreHTTPStatusUnsuccessful} so the legit refusal is not a step failure.
   */
  @Test
  void testTwoPrimaryParity_4_E2E() {
    SchedulerParityConfig.assumeBothOrgCreds();

    // --- OLD side: two-primary probe (REFUSED) + one-primary control (BOOKED), fresh revUps ---
    // AUTH → FIXTURE (two clean, fully-available primary-eligible resources) → GRANT
    // (Lightning-Scheduler
    // user-access, else the classic engine prunes the resources → dead book) → BOOK.
    final var oldTwoPrimaryEnv =
        CollectionsKt.last(
                ReVoman.revUp(
                    (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
                    SchedulerParityConfig.OLD_AUTH_CONFIG,
                    SchedulerParityConfig.OLD_TWO_PRIMARY_FIXTURE_CONFIG,
                    SchedulerParityConfig.OLD_GRANT_LS_ACCESS_CONFIG,
                    SchedulerParityConfig.OLD_BOOK_TWO_PRIMARY_CONFIG))
            .mutableEnv;
    final var oldTwoPrimaryOutcome =
        SchedulerParityConfig.oldWriteOutcome(
            oldTwoPrimaryEnv.getAsString("twoPrimaryOldSaId"),
            oldTwoPrimaryEnv.getAsString("twoPrimaryOldHttp"));

    final var oldControlEnv =
        CollectionsKt.last(
                ReVoman.revUp(
                    (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
                    SchedulerParityConfig.OLD_AUTH_CONFIG,
                    SchedulerParityConfig.OLD_TWO_PRIMARY_FIXTURE_CONFIG,
                    SchedulerParityConfig.OLD_GRANT_LS_ACCESS_CONFIG,
                    SchedulerParityConfig.OLD_BOOK_ONE_PRIMARY_CONTROL_CONFIG))
            .mutableEnv;
    final var oldControlOutcome =
        SchedulerParityConfig.oldWriteOutcome(
            oldControlEnv.getAsString("twoPrimaryControlSaId"),
            oldControlEnv.getAsString("twoPrimaryControlHttp"));

    // --- Unified side: reuse the proven WFS two-primary act, one revUp ---
    final var unifiedEnv =
        CollectionsKt.last(
                ReVoman.revUp(
                    (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
                    ReVomanConfigForWfs.AUTH_CONFIG,
                    ReVomanConfigForWfs.AVAILABILITY_OP_HOURS_POLICY_CONFIG,
                    ReVomanConfigForWfs.REQUIRED_NON_REQUIRED_FIXTURE_CONFIG,
                    ReVomanConfigForWfs.SCHEDULE_TWO_PRIMARY_CONFIG))
            .mutableEnv;
    final var unifiedTwoPrimaryOutcome =
        SchedulerParityConfig.unifiedWriteOutcome(
            unifiedEnv.getAsString("twoPrimaryStatus"),
            unifiedEnv.getAsString("twoPrimaryHttpCode"));

    // --- Non-vacuity: the one-primary control BOOKS a real 18-char, 08p ServiceAppointment,
    // proving the
    // fixture + both resources are live and bookable (so the two-primary REFUSAL is the second
    // primary
    // flag, not a dead fixture). ---
    assertThat(oldControlEnv.getAsString("twoPrimaryControlSaId")).hasLength(18);
    assertThat(oldControlEnv.getAsString("twoPrimaryControlSaId")).startsWith("08p");
    assertThat(oldControlOutcome).isEqualTo(SchedulerParityConfig.WriteOutcome.BOOKED);

    // --- Unified side pinned verbatim: clean INPUT-validation reject (the WHERE Unified refuses)
    // ---
    assertThat(unifiedEnv.getAsString("twoPrimaryErrorCode")).isEqualTo("INVALID_INPUT");
    assertThat(unifiedEnv.getAsString("twoPrimaryErrorMessage"))
        .contains("can be a primary resource");
    assertThat(unifiedEnv.getAsString("twoPrimaryHttpCode")).isEqualTo("400");
    assertThat(unifiedEnv.getAsString("twoPrimaryStatus")).isAnyOf(null, "null");
    assertThat(unifiedTwoPrimaryOutcome).isEqualTo(SchedulerParityConfig.WriteOutcome.REFUSED);

    // --- OLD side pinned faithfully (LIVE-OBSERVED): REFUSED, no SA id, HTTP 400, errorCode
    // INVALID_API_INPUT, message "Only one assignedResource can have isPrimaryResource set to
    // true…" — a
    // connect-API INPUT-validation reject (NOT a persist/field-integrity error, and NOT a 500). The
    // WHERE
    // is thus the SAME layer as Unified (connect-API input validation, pre-persist); only the
    // errorCode +
    // message text differ. Characterized verbatim, not forced to match Unified's exact
    // code/message.
    // ---
    assertThat(oldTwoPrimaryEnv.getAsString("twoPrimaryOldSaId")).isEmpty();
    assertThat(oldTwoPrimaryEnv.getAsString("twoPrimaryOldHttp")).isEqualTo("400");
    assertThat(oldTwoPrimaryEnv.getAsString("twoPrimaryOldErrorCode"))
        .isEqualTo("INVALID_API_INPUT");
    assertThat(oldTwoPrimaryEnv.getAsString("twoPrimaryOldErrorMessage"))
        .contains("isPrimaryResource set to true");
    assertThat(oldTwoPrimaryOutcome).isEqualTo(SchedulerParityConfig.WriteOutcome.REFUSED);

    // --- PARITY (the Decision 4 verdict): both products REFUSE two primaries on one appointment
    // (no
    // booking), and a single-primary control BOOKS on the OLD surface → parity CONFIRMED on the
    // OUTCOME.
    // Both reject at the SAME layer (connect-API INPUT validation, pre-persist); only the errorCode
    // + message text differ (Unified INVALID_INPUT vs OLD INVALID_API_INPUT) — a documented
    // difference, asserted above, NOT a failure. ---
    assertThat(oldTwoPrimaryOutcome).isEqualTo(unifiedTwoPrimaryOutcome);
  }

  /**
   * Decision 4z parity — a reschedule can leave an appointment with NO primary worker. The classic
   * Scheduler has NO reschedule connect API; the OLD "reschedule" is an sObject DELETE/PATCH
   * stand-in on the AssignedResource rows hitting the same save-time validator. Arm 1 (DELETE the
   * primary AR) is BLOCKED by {@code validatePrimaryAssignedResourceOnDelete}
   * (FIELD_INTEGRITY_EXCEPTION); Arm 2 (DEMOTE the primary via PATCH IsPrimaryResource=false)
   * SUCCEEDS and leaves 0 primaries (no no-primary guard on UPDATE). Route-dependent verdict: OLD
   * is STRICTER on the delete route but CONVERGES on the update route with Unified (whose
   * reschedule validation likewise permits zero primaries) — neither product has a product-wide
   * must-keep-a-primary invariant → Decision 4z confirmed on BOTH sides.
   */
  @Test
  void testRescheduleNoPrimaryParity_4z_E2E() {
    SchedulerParityConfig.assumeBothOrgCreds();

    // --- OLD side, Arm 1 (DELETE the primary AssignedResource): AUTH → FIXTURE (clean 2-resource
    // graph) → GRANT → BOOK-CLEAN (+ AR query) → DELETE-PRIMARY (+ verify survived), one revUp. ---
    final var oldDeleteEnv =
        CollectionsKt.last(
                ReVoman.revUp(
                    (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
                    SchedulerParityConfig.OLD_AUTH_CONFIG,
                    SchedulerParityConfig.OLD_RESCHEDULE_HELPER_FIXTURE_CONFIG,
                    SchedulerParityConfig.OLD_GRANT_LS_ACCESS_CONFIG,
                    SchedulerParityConfig.OLD_BOOK_CLEAN_TWO_RESOURCE_CONFIG,
                    SchedulerParityConfig.OLD_AR_DELETE_PRIMARY_CONFIG))
            .mutableEnv;
    // Non-vacuity: the clean two-resource SA MUST have booked with exactly 2 AssignedResource rows
    // and a
    // resolved primary AR id — else the delete probe would target nothing and BLOCKED would pass
    // for the
    // WRONG reason (dead fixture, not the delete guard).
    assertThat(oldDeleteEnv.getAsString("reschedCleanSaId")).hasLength(18);
    assertThat(oldDeleteEnv.getAsString("reschedCleanSaId")).startsWith("08p");
    assertThat(oldDeleteEnv.getAsString("reschedCleanArCount")).isEqualTo("2");
    assertThat(oldDeleteEnv.getAsString("reschedPrimaryArId")).startsWith("03r");
    final var oldDeleteOutcome =
        SchedulerParityConfig.oldNoPrimaryOutcome(
            oldDeleteEnv.getAsString("reschedDeletePrimaryHttp"),
            oldDeleteEnv.getAsString("reschedDeletePrimaryErrorCode"),
            // primaryGone: "1" iff the primary AR no longer exists after the delete attempt.
            "0".equals(oldDeleteEnv.getAsString("reschedPrimaryArStillExists")) ? "1" : "0");

    // --- OLD side, Arm 2 (DEMOTE the primary AR to IsPrimaryResource=false): a FRESH clean SA so
    // the two
    // arms are independent. AUTH → FIXTURE → GRANT → BOOK-CLEAN (+ AR query) → DEMOTE-PRIMARY (+
    // verify). ---
    final var oldDemoteEnv =
        CollectionsKt.last(
                ReVoman.revUp(
                    (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
                    SchedulerParityConfig.OLD_AUTH_CONFIG,
                    SchedulerParityConfig.OLD_RESCHEDULE_HELPER_FIXTURE_CONFIG,
                    SchedulerParityConfig.OLD_GRANT_LS_ACCESS_CONFIG,
                    SchedulerParityConfig.OLD_BOOK_CLEAN_TWO_RESOURCE_CONFIG,
                    SchedulerParityConfig.OLD_AR_DEMOTE_PRIMARY_CONFIG))
            .mutableEnv;
    assertThat(oldDemoteEnv.getAsString("reschedCleanArCount")).isEqualTo("2");
    assertThat(oldDemoteEnv.getAsString("reschedPrimaryArId")).startsWith("03r");
    final var oldDemoteOutcome =
        SchedulerParityConfig.oldNoPrimaryOutcome(
            oldDemoteEnv.getAsString("reschedDemotePrimaryHttp"),
            oldDemoteEnv.getAsString("reschedDemotePrimaryErrorCode"),
            // primaryGone: "1" iff ZERO primaries survive the demote attempt.
            "0".equals(oldDemoteEnv.getAsString("reschedPrimaryCountAfterDemote")) ? "1" : "0");

    // --- Unified side: reuse the proven WFS reschedule-no-primary acts, one revUp. ---
    final var unifiedEnv =
        CollectionsKt.last(
                ReVoman.revUp(
                    (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
                    ReVomanConfigForWfs.AUTH_CONFIG,
                    ReVomanConfigForWfs.AVAILABILITY_OP_HOURS_POLICY_CONFIG,
                    ReVomanConfigForWfs.REQUIRED_NON_REQUIRED_FIXTURE_CONFIG,
                    ReVomanConfigForWfs.SCHEDULE_TWO_RESOURCE_CLEAN_CONFIG,
                    ReVomanConfigForWfs.RESCHEDULE_DELETE_PRIMARY_WITH_FLAG_CONFIG,
                    ReVomanConfigForWfs.RESCHEDULE_DELETE_PRIMARY_NO_FLAG_CONFIG))
            .mutableEnv;
    // Unified Arm B (delete primary, no flag) is the closest analog to the old delete-primary
    // probe.
    final var unifiedNoFlagOutcome =
        SchedulerParityConfig.unifiedNoPrimaryOutcome(
            unifiedEnv.getAsString("reschedNoFlagStatus"),
            unifiedEnv.getAsString("reschedNoFlagErrorCode"),
            unifiedEnv.getAsString("reschedNoFlagHttpCode"));

    // --- Unified setup + arms asserted verbatim (the live-observed result) ---
    // Clean two-resource Schedule booked Success (the SA id for both reschedule arms).
    assertThat(unifiedEnv.getAsString("reschedCleanStatus")).isEqualTo("Success");
    assertThat(unifiedEnv.getAsString("reschedCleanSaId")).isNotNull();
    // Arm A: isPrimaryResource on a Delete entry → clean INVALID_INPUT payload-field guard, HTTP
    // 400
    // (a field-shape rejection, NOT a no-primary rule).
    assertThat(unifiedEnv.getAsString("reschedWithFlagErrorCode")).isEqualTo("INVALID_INPUT");
    assertThat(unifiedEnv.getAsString("reschedWithFlagHttpCode")).isEqualTo("400");
    // Arm B: delete primary WITHOUT the flag → validation ALLOWS zero primaries (no NoPrimary
    // check),
    // so it is refused ONLY by the downstream availability re-check (SlotNotAvailable) — never
    // by a "must keep a primary" rule. This is the 4z fact: no reschedule no-primary validation.
    assertThat(unifiedEnv.getAsString("reschedNoFlagStatus")).isNotEqualTo("Success");
    assertThat(unifiedEnv.getAsString("reschedNoFlagErrorCode")).isEqualTo("INVALID_INPUT");
    assertThat(unifiedEnv.getAsString("reschedNoFlagHttpCode")).isEqualTo("400");
    assertThat(unifiedEnv.getAsString("reschedNoFlagErrorMessage"))
        .contains("not available for the requested slot");
    // On the live org the arms die at the availability re-check, not validation → normalized
    // BLOCKED
    // (the end-to-end no-primary completion is validation-layer only, per the WFS
    // characterization).
    assertThat(unifiedNoFlagOutcome).isEqualTo(SchedulerParityConfig.NoPrimaryOutcome.BLOCKED);

    // --- OLD outcomes pinned verbatim (characterization) ---
    // Arm 1 (DELETE the primary AR): BLOCKED by the save-time delete guard
    // (validatePrimaryAssignedResourceOnDelete → FIELD_INTEGRITY_EXCEPTION
    // "AssignedResourceDelete"),
    // HTTP 400, and the primary AR SURVIVES (still present after the attempt) → the SA is NOT left
    // without a primary via delete. The old side hard-enforces the primary-worker invariant at DML
    // time.
    assertThat(oldDeleteEnv.getAsString("reschedDeletePrimaryHttp")).isEqualTo("400");
    assertThat(oldDeleteEnv.getAsString("reschedDeletePrimaryErrorCode"))
        .isEqualTo("FIELD_INTEGRITY_EXCEPTION");
    assertThat(oldDeleteEnv.getAsString("reschedPrimaryArStillExists")).isEqualTo("1");
    assertThat(oldDeleteOutcome).isEqualTo(SchedulerParityConfig.NoPrimaryOutcome.BLOCKED);

    // Arm 2 (DEMOTE the primary AR to IsPrimaryResource=false): probes whether the old save
    // validator
    // has ANY no-primary guard beyond the delete path. LIVE-OBSERVED (pinned verbatim): the PATCH
    // SUCCEEDS (HTTP 204) and leaves ZERO primaries on the SA (reschedPrimaryCountAfterDemote ==
    // "0") →
    // LEFT_NO_PRIMARY. So validateAssignedResourceOnSave has NO "must keep a primary" invariant —
    // the
    // primary-worker enforcement lives ONLY in the DELETE guard, not on update. This is the
    // old-side
    // route that DOES leave an appointment with no primary — the direct analog of Unified's
    // permissive
    // reschedule validation.
    assertThat(oldDemoteEnv.getAsString("reschedDemotePrimaryHttp"))
        .isEqualTo(EXPECTED_DEMOTE_HTTP);
    assertThat(oldDemoteEnv.getAsString("reschedPrimaryCountAfterDemote"))
        .isEqualTo(EXPECTED_DEMOTE_PRIMARY_COUNT);
    assertThat(oldDemoteOutcome).isEqualTo(EXPECTED_DEMOTE_OUTCOME);

    // --- 4z VERDICT (structural divergence + a convergent no-primary fact, all LIVE-observed) ---
    // (1) DELETE route: OLD is STRICTER — its save-time delete guard
    // (validatePrimaryAssignedResourceOnDelete → FIELD_INTEGRITY_EXCEPTION) HARD-BLOCKS removing
    // the
    // primary AR (primary survives), so the delete route CANNOT leave a no-primary SA. On the
    // Unified
    // side
    // the analogous delete-primary reschedule (Arm B) passes VALIDATION (zero primaries allowed)
    // and is
    // refused only downstream (availability re-check) → both probes normalize to BLOCKED here,
    // but
    // for DIFFERENT reasons: old = a genuine no-primary DML guard, Unified = an availability gap
    // over a
    // permissive validator. So on the delete route old ≥ Unified in strictness (enforcement-layer
    // divergence), and the observable outcomes AGREE.
    assertThat(oldDeleteOutcome).isEqualTo(unifiedNoFlagOutcome);
    // (2) DEMOTE/UPDATE route (the deeper fact): NEITHER product enforces a blanket "appointment
    // must
    // always keep a primary" invariant. OLD's save validator has no no-primary check on UPDATE, so
    // the
    // demote PATCH LEAVES a no-primary SA (LEFT_NO_PRIMARY); Unified's reschedule validation
    // likewise
    // permits
    // zero primaries (validatePrimaryResourceCount throws only for > 1). So both engines CAN be
    // driven to
    // a no-primary state — old via demote-PATCH, Unified via reschedule validation —
    // confirming Decision 4z on BOTH sides. The old delete guard is a route-specific stricture, not
    // a
    // product-wide primary invariant. Asserted faithfully (not forced to a single AGREE/DIVERGE
    // label).
    assertThat(oldDemoteOutcome).isEqualTo(unifiedNoPrimaryValidationVerdict());
  }

  /**
   * The Unified no-primary VALIDATION verdict for the 4z comparison: Unified reschedule validation
   * ALLOWS zero primaries (there is no NoPrimary check — {@code validatePrimaryResourceCount}
   * throws only when {@code primaryCount > 1}), so at the validation layer Unified would
   * LEAVE_NO_PRIMARY (on the live org it is stopped only by the downstream availability gap). This
   * mirrors the old side's demote route, which leaves a no-primary SA. Kept as a named constant so
   * the demote-route parity read is explicit: both products permit no-primary at their validation
   * layer.
   */
  private static SchedulerParityConfig.NoPrimaryOutcome unifiedNoPrimaryValidationVerdict() {
    return SchedulerParityConfig.NoPrimaryOutcome.LEFT_NO_PRIMARY;
  }

  /**
   * Decision 5 parity — a single AssignedResource marked {@code isPrimaryResource=true} BUT {@code
   * isRequiredResource=false} is a CONTRADICTION and is REJECTED at PERSIST (not auto-corrected,
   * not double-booked). This is the HIGHEST-VALUE parity proof in the set: the rejection is
   * enforced by {@code LightningSchedulerAssignedResourceValidator} (fieldservice-impl), a
   * SAVE-TIME validator on the AssignedResource entity that fires on EVERY create/update of an
   * AssignedResource — this Connect API, Apex, direct DML, bulk — whenever multi-resource
   * scheduling is on. So the OLD Salesforce Scheduler book path ({@code POST
   * /connect/scheduling/service-appointments}, which inserts AssignedResource rows) hits the SAME
   * validator Unified hits → literally the same code path.
   *
   * <p>The booking window is FREE for resourceA (member OH + Confirmed Shift 10-14 both cover the
   * 11:00-11:30 window), so availability passes and the persist-time primary-must-be-required
   * reject is the sole discriminator. Probe: one AssignedResource isPrimaryResource=true +
   * isRequiredResource=false → REFUSED (persist/field error, no SA id). Control: the same
   * resource/window flipped to isRequiredResource=true (a valid required-primary) → BOOKED. Old
   * side: public Scheduler REST ({@code POST /connect/scheduling/service-appointments}). Unified
   * side: the existing WFS Decision-5 Kicks ({@code SCHEDULE_PRIMARY_NOT_REQUIRED_CONFIG} →
   * PersistError/INVALID_FIELD; {@code SCHEDULE_PRIMARY_REQUIRED_CONTROL_CONFIG} → Success).
   *
   * <p>KEY PARITY FINDING (both products agree): the primary-not-required contradiction is REFUSED
   * on both engines (both fire the shared save-time AssignedResource validator), while the
   * required-primary control BOOKS on both → Decision 5 parity CONFIRMED on the write axis. The OLD
   * validator's observed error envelope is captured faithfully (it may differ from the Unified
   * schedulingStatus=PersistError / errorCode=INVALID_FIELD envelope); the PARITY is on the
   * normalized OUTCOME (both REFUSE the contradiction, both BOOK the control), not on the raw
   * message text.
   *
   * <p>A divergence, were it to appear, would be asserted faithfully rather than forced to a green.
   *
   * <p>Old-side revUps mint 1 fresh {@code sched-pnr-a-*@revoman.org} user per run and never clean
   * up; two old-side revUps here (probe + control) → ~2 fresh users/run. The leading success guard
   * ({@code firstUnIgnoredUnsuccessfulStepReport() == null}) surfaces a rolled-back fixture/grant
   * loudly at the fixture step; the book acts carry {@code ignoreHTTPStatusUnsuccessful} so the
   * legit refusal (a non-2xx / error envelope) is not counted as a step failure.
   */
  @Test
  void testPrimaryNotRequiredParity_5_E2E() {
    SchedulerParityConfig.assumeBothOrgCreds();

    // --- OLD side: probe (primary + NOT required, free window → persist reject) + control (primary
    // +
    // required → booked), fresh revUps. Each mirrors AUTH → FIXTURE (single fully-available
    // resourceA) →
    // GRANT (Lightning-Scheduler user-access, else the classic engine prunes the resource → dead
    // book) →
    // BOOK. ---
    final var oldProbeEnv =
        CollectionsKt.last(
                ReVoman.revUp(
                    (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
                    SchedulerParityConfig.OLD_AUTH_CONFIG,
                    SchedulerParityConfig.OLD_PRIMARY_NOT_REQUIRED_FIXTURE_CONFIG,
                    SchedulerParityConfig.OLD_GRANT_LS_ACCESS_CONFIG,
                    SchedulerParityConfig.OLD_BOOK_PRIMARY_NOT_REQUIRED_CONFIG))
            .mutableEnv;
    final var oldProbeOutcome =
        SchedulerParityConfig.oldWriteOutcome(
            oldProbeEnv.getAsString("oldWriteHelperSaId"),
            oldProbeEnv.getAsString("oldWriteHelperHttp"));

    final var oldControlEnv =
        CollectionsKt.last(
                ReVoman.revUp(
                    (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
                    SchedulerParityConfig.OLD_AUTH_CONFIG,
                    SchedulerParityConfig.OLD_PRIMARY_NOT_REQUIRED_FIXTURE_CONFIG,
                    SchedulerParityConfig.OLD_GRANT_LS_ACCESS_CONFIG,
                    SchedulerParityConfig.OLD_BOOK_PRIMARY_REQUIRED_CONTROL_CONFIG))
            .mutableEnv;
    final var oldControlOutcome =
        SchedulerParityConfig.oldWriteOutcome(
            oldControlEnv.getAsString("oldWriteRequiredControlSaId"),
            oldControlEnv.getAsString("oldWriteRequiredControlHttp"));

    // --- Unified side: reuse the proven WFS Decision-5 acts (probe rejected at persist +
    // required
    // control booked), two revUps (Approach A: the rejected probe and the persisting control never
    // share
    // ServiceResource rows). ---
    final var unifiedProbeEnv =
        CollectionsKt.last(
                ReVoman.revUp(
                    (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
                    ReVomanConfigForWfs.AUTH_CONFIG,
                    ReVomanConfigForWfs.AVAILABILITY_OP_HOURS_POLICY_CONFIG,
                    ReVomanConfigForWfs.REQUIRED_NON_REQUIRED_FIXTURE_CONFIG,
                    ReVomanConfigForWfs.SCHEDULE_PRIMARY_NOT_REQUIRED_CONFIG))
            .mutableEnv;
    final var unifiedProbeOutcome =
        SchedulerParityConfig.unifiedWriteOutcome(
            unifiedProbeEnv.getAsString("primaryNotReqStatus"), "201");

    final var unifiedControlEnv =
        CollectionsKt.last(
                ReVoman.revUp(
                    (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
                    ReVomanConfigForWfs.AUTH_CONFIG,
                    ReVomanConfigForWfs.AVAILABILITY_OP_HOURS_POLICY_CONFIG,
                    ReVomanConfigForWfs.REQUIRED_NON_REQUIRED_FIXTURE_CONFIG,
                    ReVomanConfigForWfs.SCHEDULE_PRIMARY_REQUIRED_CONTROL_CONFIG))
            .mutableEnv;
    final var unifiedControlOutcome =
        SchedulerParityConfig.unifiedWriteOutcome(
            unifiedControlEnv.getAsString("primaryReqControlStatus"), "201");

    // --- PARITY: both engines REFUSE the primary-not-required contradiction (the shared save-time
    // AssignedResource validator) and BOOK the required-primary control ---
    // The old probe must NOT return a ServiceAppointment id (the persist reject leaves it empty),
    // so
    // REFUSED reflects a genuine rejection, not an empty/vacuous pass.
    assertThat(oldProbeEnv.getAsString("oldWriteHelperSaId")).isEmpty();
    assertThat(oldProbeOutcome).isEqualTo(SchedulerParityConfig.WriteOutcome.REFUSED);
    assertThat(unifiedProbeOutcome).isEqualTo(SchedulerParityConfig.WriteOutcome.REFUSED);
    assertThat(oldProbeOutcome).isEqualTo(unifiedProbeOutcome);

    // Non-vacuity: the required-primary control (same resource/window) MUST book a real 18-char,
    // 08p
    // ServiceAppointment id on the OLD side, proving the fixture + grant are live and the window is
    // genuinely free — so the probe's refusal is the primary-not-required rule, not a dead
    // resource.
    assertThat(oldControlEnv.getAsString("oldWriteRequiredControlSaId")).hasLength(18);
    assertThat(oldControlEnv.getAsString("oldWriteRequiredControlSaId")).startsWith("08p");
    assertThat(oldControlOutcome).isEqualTo(SchedulerParityConfig.WriteOutcome.BOOKED);
    assertThat(unifiedControlOutcome).isEqualTo(SchedulerParityConfig.WriteOutcome.BOOKED);
    assertThat(oldControlOutcome).isEqualTo(unifiedControlOutcome);

    // Unified probe pinned to the Decision-5 persist-validation reject (the shared validator's
    // Unified
    // envelope).
    assertThat(unifiedProbeEnv.getAsString("primaryNotReqStatus")).isEqualTo("PersistError");
    assertThat(unifiedProbeEnv.getAsString("primaryNotReqErrorCode")).isEqualTo("INVALID_FIELD");
    // Unified control books Success.
    assertThat(unifiedControlEnv.getAsString("primaryReqControlStatus")).isEqualTo("Success");
  }

  @Test
  void testResourceLimitCapParity_8_E2E() {
    SchedulerParityConfig.assumeBothOrgCreds();

    // --- OLD side: both candidates reads (limit=0 + limit=50 control) over the load-balancing
    // fixture ---
    // AUTH → FIXTURE (AppointmentAssignmentPolicy(loadBalancing) + policy FK + 2-resource graph) →
    // GRANT
    // (Lightning-Scheduler user-access, else the classic engine prunes the resources → dead read) →
    // CANDIDATES reads (limit=0 probe + limit=50 control), NO filterByResources so the cap path
    // engages.
    final var oldReadEnv =
        CollectionsKt.last(
                ReVoman.revUp(
                    (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
                    SchedulerParityConfig.OLD_AUTH_CONFIG,
                    SchedulerParityConfig.OLD_LIMIT_FIXTURE_CONFIG,
                    SchedulerParityConfig.OLD_GRANT_LS_ACCESS_CONFIG,
                    SchedulerParityConfig.OLD_GET_CANDIDATES_LIMIT_ZERO_CONFIG,
                    SchedulerParityConfig.OLD_GET_CANDIDATES_LIMIT_POSITIVE_CONFIG))
            .mutableEnv;
    final var oldLimitZeroCount = oldReadEnv.getAsString("limitZeroResourceCount");
    final var oldLimitPositiveCount = oldReadEnv.getAsString("limitPositiveResourceCount");
    // Was the OLD load-balancing FK attachable? The
    // AppointmentSchedulingPolicy.AppointmentAssignmentPolicy
    // column is exposed only when the org has smart scheduling enabled
    // (orgHasSmartSchedulingEnabled()), so
    // this fixture-recorded flag is the live probe of that org gate — the switch that decides
    // whether the
    // classic engine can even take the load-balancing candidates path where the cap lives.
    final var oldCapConstructible =
        "true".equals(oldReadEnv.getAsString("schedLimitCapConstructible"));
    final var oldCapEngaged =
        SchedulerParityConfig.oldLimitCapEngaged(oldLimitZeroCount, oldLimitPositiveCount);
    final var oldLimitZeroDecision = SchedulerParityConfig.oldReadDecision(oldLimitZeroCount);
    final var oldLimitPositiveDecision =
        SchedulerParityConfig.oldReadDecision(oldLimitPositiveCount);

    // --- Unified side: reuse the proven WFS Decision-8 read Kicks, one revUp ---
    final var unifiedEnv =
        CollectionsKt.last(
                ReVoman.revUp(
                    (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
                    ReVomanConfigForWfs.AUTH_CONFIG,
                    ReVomanConfigForWfs.REQUIRED_NON_REQUIRED_FIXTURE_CONFIG,
                    ReVomanConfigForWfs.GET_RESOURCES_LIMIT_ZERO_CONFIG,
                    ReVomanConfigForWfs.GET_RESOURCES_LIMIT_POSITIVE_CONFIG))
            .mutableEnv;
    final var unifiedLimitZeroCount = unifiedEnv.getAsString("limitZeroResourceCount");
    final var unifiedLimitPositiveCount = unifiedEnv.getAsString("limitPositiveResourceCount");
    final var unifiedLimitZeroDecision =
        SchedulerParityConfig.oldReadDecision(unifiedLimitZeroCount);
    final var unifiedLimitPositiveDecision =
        SchedulerParityConfig.oldReadDecision(unifiedLimitPositiveCount);

    // --- Unified (asserted): the cap engages on the default load-balancing policy — limit=0 EMPTY,
    // limit=50 >0.
    // Neither read is a swallowed 4xx/5xx (both acts are ignore-HTTP-status), so assert no
    // errorCode.
    assertThat(unifiedEnv.getAsString("limitZeroErrorCode")).isAnyOf(null, "null", "");
    assertThat(unifiedEnv.getAsString("limitPositiveErrorCode")).isAnyOf(null, "null", "");
    assertThat(unifiedLimitZeroCount).isEqualTo("0");
    assertThat(Integer.parseInt(unifiedLimitPositiveCount)).isGreaterThan(0);
    assertThat(unifiedLimitZeroDecision).isEqualTo(SchedulerParityConfig.ReadDecision.EXCLUDED);
    assertThat(unifiedLimitPositiveDecision).isEqualTo(SchedulerParityConfig.ReadDecision.INCLUDED);

    // --- OLD side: neither probe is a crash (no 500 / errorCode) — the cap (or its no-op) is a
    // clean read.
    assertThat(oldReadEnv.getAsString("limitZeroHttp")).isNotEqualTo("500");
    assertThat(oldReadEnv.getAsString("limitPositiveHttp")).isNotEqualTo("500");
    assertThat(oldReadEnv.getAsString("limitZeroErrorCode")).isAnyOf(null, "null", "");
    // Non-vacuity: the positive-limit control MUST offer resources on BOTH engines
    // (fixture/policy/grant
    // are live). Whether or not the OLD cap engages, limit=50 returns the full pool → > 0.
    assertThat(Integer.parseInt(oldLimitPositiveCount)).isGreaterThan(0);
    assertThat(oldLimitPositiveDecision).isEqualTo(SchedulerParityConfig.ReadDecision.INCLUDED);

    // --- PARITY: characterize the OLD cap vs Unified, faithfully. ---
    if (oldCapConstructible) {
      // The org HAS smart scheduling → the load-balancing FK attached → the classic cap path is
      // live.
      // AGREE: the OLD cap behaves like Unified — limit=0 → EMPTY, positive → resources. (Both
      // engines
      // route
      // the candidate set through a load-balancing objective; the cap semantics match.)
      assertThat(oldCapEngaged).isTrue();
      assertThat(oldLimitZeroCount).isEqualTo("0");
      assertThat(oldLimitZeroDecision).isEqualTo(SchedulerParityConfig.ReadDecision.EXCLUDED);
      assertThat(oldLimitZeroDecision).isEqualTo(unifiedLimitZeroDecision);
      assertThat(oldLimitPositiveDecision).isEqualTo(unifiedLimitPositiveDecision);
    } else {
      // CONFIGURATION NUANCE (recorded verbatim, NOT forced green): the scheduler org lacks smart
      // scheduling
      // (SsAppointmentDistribution pref / AppointmentDistribution perm), so the
      // AppointmentSchedulingPolicy.AppointmentAssignmentPolicy FK COLUMN is absent (the attach
      // PATCH 400s
      // INVALID_FIELD "No such column 'AppointmentAssignmentPolicy'"). Without that FK the classic
      // engine
      // (SchedulingServiceImpl.getAppointmentCandidatesForTerritoryWorkTypes → getSmartPolicy)
      // never takes
      // the load-balancing branch, so resourceLimitApptDistribution is accepted on the request but
      // NEVER
      // applied — the cap is a no-op and limit=0 does NOT empty the pool. Unified ships the
      // LoadBalancing
      // objective on its DEFAULT OnSite policy, so ITS cap always engages (limit=0 → EMPTY). This
      // is the
      // true OLD-vs-Unified divergence for Decision 8: the cap parameter is honored identically
      // ONLY
      // once the
      // load-balancing objective/policy is present on BOTH — a configuration precondition Unified
      // pre-satisfies
      // and this scheduler org does not. Pin the observed OLD no-op: limit=0 stays INCLUDED (pool
      // not
      // emptied), diverging from Unified's EXCLUDED.
      assertThat(oldReadEnv.getAsString("schedLimitAttachErrorCode")).isEqualTo("INVALID_FIELD");
      assertThat(oldCapEngaged).isFalse();
      assertThat(oldLimitZeroDecision).isEqualTo(SchedulerParityConfig.ReadDecision.INCLUDED);
      assertThat(oldLimitZeroDecision).isNotEqualTo(unifiedLimitZeroDecision);
      // Both OLD probes see the SAME (uncapped) pool → the parameter had no effect on the OLD
      // engine.
      assertThat(oldLimitZeroCount).isEqualTo(oldLimitPositiveCount);
    }
  }

  @Test
  void testShiftSharingSplitParity_9_E2E() {
    SchedulerParityConfig.assumeBothOrgCreds();

    // --- Unified side: the proven WFS Decision-9 chain. adminToken mints manager +
    // case-worker
    // (own SOAP sessions); the manager OWNS the policy/fixture/Private Shift rows; the case-worker
    // lacks
    // sharing on them. One revUp: AUTH → persona-mint → manager-owned policy + fixture → manager
    // read
    // (control) → case-worker read (probe). The SHIFT read is the user-mode gate here. ---
    final var unifiedEnv =
        CollectionsKt.last(
                ReVoman.revUp(
                    (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
                    ReVomanConfigForWfs.AUTH_CONFIG,
                    ReVomanConfigForWfs.AUTH_PERSONAS_DEC9_CONFIG,
                    ReVomanConfigForWfs.SHARING_SPLIT_POLICY_CONFIG,
                    ReVomanConfigForWfs.SHARING_SPLIT_FIXTURE_CONFIG,
                    ReVomanConfigForWfs.GET_SLOTS_SHARING_SPLIT_AS_MANAGER_CONFIG,
                    ReVomanConfigForWfs.GET_SLOTS_SHARING_SPLIT_AS_CASEWORKER_CONFIG))
            .mutableEnv;
    final var unifiedManagerGate =
        SchedulerParityConfig.sharingGate(unifiedEnv.getAsString("dec9CaseWorkerSlotCount"), "200");
    // Unified shift-gated split asserted verbatim (mirrors
    // WfsReadPathParityE2ETest.testShiftSharingModeSplitE2E):
    // the shift OWNER (manager) sees availability; the sharing-deprived case-worker sees NONE,
    // silently.
    assertThat(Integer.parseInt(unifiedEnv.getAsString("dec9ManagerSlotCount"))).isGreaterThan(0);
    assertThat(unifiedEnv.getAsString("dec9CaseWorkerSlotCount")).isEqualTo("0");
    // Unified case-worker (sharing-deprived) is GATED (zero slots, HTTP 200, no error).
    assertThat(unifiedManagerGate).isEqualTo(SchedulerParityConfig.SharingGate.GATED);

    // --- OLD side: cross-persona classic getAppointmentSlots. AUTH (admin) → persona-mint (manager
    // +
    // case-worker + resource-owner, own SOAP sessions) → manager-owned fixture (admin skeleton +
    // manager
    // ServiceResource + admin STM/Shift on it) → manager read (control) → case-worker read (probe).
    // The
    // sharing gate on OLD is the user-mode STM/ServiceResource read; the shift read is full-access.
    // ---
    final var oldEnv =
        CollectionsKt.last(
                ReVoman.revUp(
                    (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
                    SchedulerParityConfig.OLD_AUTH_CONFIG,
                    SchedulerParityConfig.OLD_AUTH_PERSONAS_DEC9_CONFIG,
                    SchedulerParityConfig.OLD_SHARING_SPLIT_FIXTURE_CONFIG,
                    SchedulerParityConfig.OLD_GET_SLOTS_SHARING_AS_MANAGER_CONFIG,
                    SchedulerParityConfig.OLD_GET_SLOTS_SHARING_AS_CASEWORKER_CONFIG))
            .mutableEnv;
    final var oldCaseWorkerGate =
        SchedulerParityConfig.sharingGate(
            oldEnv.getAsString("oldD9CaseWorkerSlotCount"),
            oldEnv.getAsString("oldD9CaseWorkerHttp"));

    // --- OLD case-worker (sharing-deprived) is GATED: zero slots, HTTP 200, NO error — the classic
    // engine's
    // user-mode STM/ServiceResource read gate (NOT the shift read, which is full-access). This is
    // the axis
    // under test and the point of parity with Unified on the OBSERVABLE contract. ---
    assertThat(oldEnv.getAsString("oldD9CaseWorkerHttp")).isEqualTo("200");
    assertThat(oldEnv.getAsString("oldD9CaseWorkerSlotCount")).isEqualTo("0");
    assertThat(oldCaseWorkerGate).isEqualTo(SchedulerParityConfig.SharingGate.GATED);

    // --- PARITY (observable contract): BOTH engines silently gate a sharing-deprived reader to
    // zero slots,
    // HTTP 200, no error → GATED == GATED. They AGREE on the user-visible half-secured behavior.
    // ---
    assertThat(oldCaseWorkerGate).isEqualTo(unifiedManagerGate);
    // --- DIVERGENCE (mechanism, source-confirmed, recorded not forced): Unified gates on the SHIFT
    // read
    // (user-mode) while OLD gates on the STM/ServiceResource read (user-mode) and reads shifts
    // full-access —
    // an INVERTED sharing axis. The OLD manager positive-control (owner-sees-slots) is a documented
    // provisioning blocker (object-perm chain: OperatingHours/WorkType CRUD via the Greeter permset
    // + record
    // shares on the Private graph), so it is NOT asserted green — see this method's javadoc. ---
  }

  /**
   * Runs one old-side occupancy cell: AUTH → prior-assignment FIXTURE → GRANT → book appt #1 (seeds
   * B's assignment) → book appt #2 (re-books B on the overlapping window). Returns the appt-#2
   * write outcome. Asserts non-vacuity: appt #1 itself booked a real SA (18-char 08p id), else appt
   * #2's refusal would be a dead-fixture artifact rather than an occupancy block.
   */
  private static SchedulerParityConfig.WriteOutcome oldOccupancyCell(
      final Kick appt1Config, final Kick appt2Config) {
    final var env =
        CollectionsKt.last(
                ReVoman.revUp(
                    (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
                    SchedulerParityConfig.OLD_AUTH_CONFIG,
                    SchedulerParityConfig.OLD_PRIOR_ASSIGNMENT_FIXTURE_CONFIG,
                    SchedulerParityConfig.OLD_GRANT_LS_ACCESS_CONFIG,
                    appt1Config,
                    appt2Config))
            .mutableEnv;
    assertThat(env.getAsString("priorAppt1SaId")).hasLength(18);
    assertThat(env.getAsString("priorAppt1SaId")).startsWith("08p");
    return SchedulerParityConfig.oldWriteOutcome(
        env.getAsString("priorAppt2SaId"), env.getAsString("priorAppt2Http"));
  }

  /**
   * Runs one Unified occupancy cell: AUTH → op-hours policy → prior-assignment FIXTURE → book appt
   * #1 → book appt #2. Returns the appt-#2 write outcome. Non-vacuity: appt #1 scheduled Success.
   */
  private static SchedulerParityConfig.WriteOutcome unifiedOccupancyCell(
      final Kick appt1Config, final Kick appt2Config) {
    final var env =
        CollectionsKt.last(
                ReVoman.revUp(
                    (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
                    ReVomanConfigForWfs.AUTH_CONFIG,
                    ReVomanConfigForWfs.AVAILABILITY_OP_HOURS_POLICY_CONFIG,
                    ReVomanConfigForWfs.PRIOR_ASSIGNMENT_FIXTURE_CONFIG,
                    appt1Config,
                    appt2Config))
            .mutableEnv;
    assertThat(env.getAsString("priorAppt1SchedulingStatus")).isEqualTo("Success");
    return SchedulerParityConfig.unifiedWriteOutcome(
        env.getAsString("priorAppt2SchedulingStatus"), env.getAsString("priorAppt2Http"));
  }

  /**
   * Prior-assignment occupancy — does an EXISTING appointment assignment on worker B occupy B's
   * time and block a later booking on an overlapping appointment? The question probes whether
   * {@code required} vs {@code non-required} on either booking changes the answer: it decides
   * whether B is fitness-CHECKED when joining an appointment, but the separate question here is
   * whether B's existing assignments COUNT against a future booking.
   *
   * <p>Fixture isolates the mechanism: A/B/C are all AVAILABLE at 11:00 (member OH + Shift
   * 10:00-14:00, no shift gap — unlike the 1.5 double-book fixture). B is the only shared worker: A
   * is appt #1's primary, C is appt #2's DEDICATED free primary, so a refused appt #2 cannot be
   * blamed on the primary being double-booked. The two appointments use two Accounts on the SAME
   * 11:00-11:30 window. Each of the four (appt#1 flavor x appt#2 flavor) cells is a fresh revUp on
   * each engine.
   *
   * <p><b>KEY FINDING — WRITE-PATH CONTRACT DIFFERENCE (LIVE-observed 2026-07-06, both orgs, not
   * FROM-CACHE; root cause verified in Core p4/260-patch and confirmed by two companion tests):</b>
   * on every cell of the 2x2 OLD Salesforce Scheduler REFUSED all four while Unified BOOKED all
   * four. This is a genuine product-behavior difference on the booking (write) path: OLD's write
   * API re-checks resource availability before persisting, so an overlapping prior appointment
   * blocks it; Unified's schedule action persists the caller-supplied resources without an
   * occupancy re-check (occupancy lives only on Unified's get-candidates/get-slots READ path). It
   * is NOT the policy-config artifact first suspected — see the "Unified engine" and "OLD engine"
   * paragraphs below for the verified mechanism on each side.
   *
   * <ul>
   *   <li>(a) #1 required, #2 required — OLD REFUSED / Unified BOOKED.
   *   <li>(b) #1 OPTIONAL, #2 required — OLD REFUSED / Unified BOOKED.
   *   <li>(c) #1 optional, #2 optional — OLD REFUSED / Unified BOOKED.
   *   <li>(d) #1 required, #2 optional — OLD REFUSED / Unified BOOKED.
   * </ul>
   *
   * <p><b>OLD engine:</b> an existing overlapping assignment is HARD occupancy. B is refused a
   * second overlapping booking regardless of the required/optional flag on EITHER booking —
   * including cells (b)/(c) where B joined appt #1 only as an OPTIONAL non-required resource. So on
   * the old engine "an existing assignment counts even when booked as optional" is TRUE, and it
   * counts even when the second booking is optional too.
   *
   * <p><b>Unified engine — MULTI-RESOURCE SLOT-INTERSECTION GAP (debugger-verified on live HEAD
   * {@code 90cdfeb7}, {@code p4/262-patch}, {@code -Dversion=262}, 2026-07-07; confirmed live by
   * {@link #testPriorAssignmentUnifiedReadVsWriteE2E}):</b> Unified double-books B in every cell —
   * but NOT because "the write surface never checks occupancy" (that earlier explanation was
   * WRONG). The write path DOES run an occupancy check: {@code InBusinessScheduleHandler.handle} →
   * {@code SlotAvailabilityChecker.isSlotAvailable} (before DML, throws {@code SlotNotAvailable},
   * holds a double-booking lock), and it DOES load occupancy for the busy required resource B
   * ({@code extractServiceResourceIds} includes required B; {@code
   * UnavailabilityService.getResourceUnavailability} subtracts B's existing SA). The double-book
   * comes from a specific gap in the multi-resource slot logic: {@code
   * InBusinessGetSlotsHandler.getOverlappingSlotsBetweenResources} intersects only over {@code
   * schedulableSlots.keySet()} — resources that produced ≥1 slot. A fully-busy required resource
   * generates ZERO slots, so it is ABSENT from the keySet and silently dropped from the
   * intersection. With the free primary C still producing an 11:00 slot, {@code hasExactSlotMatch}
   * returns true → {@code isSlotAvailable}=true → B is persisted (double-booked). Only the PRIMARY
   * resource is guarded ({@code processSlotsRequest} {@code containsKey(primaryResourceId)}); a
   * busy PRIMARY would be refused. Non-required helpers are excluded from the check entirely
   * ({@code extractServiceResourceIds} filters to required-only). Live debugger evidence (req×req
   * cell): {@code serviceResourceIds}={C,B} (size 2) but {@code schedulableSlots.keySet()}={C} →
   * {@code slotsOutput}=[11:00 slot] → {@code hasExactSlotMatch}=true → BOOKED. The read≠write
   * split is this collapse difference: get-candidates ({@code
   * InBusinessGetCandidatesHandler.buildCandidatesList}) emits per-resource rows so it EXCLUDES
   * busy B while offering free C; the schedule action collapses via the keySet intersection and
   * books B. The handoff's earlier "{@code CalendarEvents} rule gates it" hypothesis was WRONG on
   * two counts — the {@code CalendarEvents} rule has since been REMOVED, and even on the live org
   * {@code SchedulingPolicyInfo.shouldConsiderCalendarEvents()} gated only Salesforce {@code Event}
   * sObjects (its unavailability SOQL excludes appointment-linked events), never ServiceAppointment
   * occupancy.
   *
   * <p><b>OLD engine — occupancy is TWO-knob (verified in {@code scheduling.SchedulingServiceImpl},
   * confirmed live by {@link #testPriorAssignmentOldOverbookingPrefInsufficientE2E}):</b> OLD's
   * WRITE path DOES re-check availability ({@code ServiceAppointmentServiceImpl.create →
   * areResourcesAvailable → getAppointmentSlots}), so an overlapping prior appointment blocks the
   * booking by default. Two settings must BOTH change to allow the double-book: (1) the org-wide
   * {@code OrgPreferences.Overbooking} pref ({@code enableOverbookingOrgPref}, default OFF, read
   * via {@code AppointmentBookingAccessChecks.orgHasOverbooking()}), AND (2) the member
   * operating-hours {@code TimeSlot.MaxAppointments > 1} (default 1) — because concurrency is gated
   * by {@code isConcurrentSchedulingInterval(slot) = slot.getMaxAppointments() > 1} (line ~1924),
   * and the pref only refines capacity accounting WITHIN an already-concurrent slot. The companion
   * test confirms the pref alone is necessary-but-not-sufficient: with Overbooking flipped ON but
   * MaxAppointments=1, OLD still REFUSES.
   *
   * <p><b>So the OLD-REFUSES / Unified-BOOKS split is a genuine product-behavior difference on the
   * write path</b> (OLD write validates availability; Unified write trusts the caller), NOT the
   * config artifact the handoff first suspected. This test PINS each cell's observed outcome
   * VERBATIM via the normalized {@link SchedulerParityConfig.WriteOutcome} (old REFUSED, Unified
   * BOOKED) — the same way the 1.4 / 3 divergences are pinned. There is deliberately no {@code old
   * == unified} equality assertion. The two companion tests ({@link
   * #testPriorAssignmentUnifiedReadVsWriteE2E}, {@link
   * #testPriorAssignmentOldOverbookingPrefInsufficientE2E}) confirm the root cause on each engine.
   *
   * <p>Non-vacuity: each cell's helper method asserts appt #1 itself booked (old: 18-char 08p SA
   * id; unified: schedulingStatus Success) before reading appt #2's outcome, so a REFUSED appt #2
   * reflects a real occupancy block rather than a dead fixture. The fixture also proves B is
   * genuinely AVAILABLE at 11:00 (no shift gap), so the old-side refusals are occupancy, not
   * availability.
   *
   * <p>Old-side revUps mint 3 fresh {@code prior-res-*@revoman.org} users per run and never clean
   * up; four cells x one old revUp each -> ~12 fresh users/run. The Unified side admin-mints
   * resourceC's user (the manager persona lacks Manage Users) inside the fixture Kick. The leading
   * success guard ({@code firstUnIgnoredUnsuccessfulStepReport() == null}) surfaces a rolled-back
   * fixture/grant loudly; the book acts carry {@code ignoreHTTPStatusUnsuccessful} so a legit
   * refusal is read from the captured status, not counted as a step failure.
   */
  @Test
  void testPriorAssignmentOccupancyParity_E2E() {
    SchedulerParityConfig.assumeBothOrgCreds();

    // LIVE-OBSERVED TRUTH TABLE (2026-07-06, both orgs, not FROM-CACHE) — a TOTAL divergence:
    // OLD Salesforce Scheduler REFUSED all four cells; Unified BOOKED all four. Each cell is
    // asserted
    // VERBATIM to the observed outcome (not forced to a parity match), the same way the 1.4 / 3
    // crash
    // divergences are pinned. The old==unified equality asserts are intentionally ABSENT — they
    // diverge.
    final Kick oldReqA1 = SchedulerParityConfig.OLD_PRIOR_APPT1_B_REQUIRED_CONFIG;
    final Kick oldOptA1 = SchedulerParityConfig.OLD_PRIOR_APPT1_B_OPTIONAL_CONFIG;
    final Kick oldReqA2 = SchedulerParityConfig.OLD_PRIOR_APPT2_B_REQUIRED_CONFIG;
    final Kick oldOptA2 = SchedulerParityConfig.OLD_PRIOR_APPT2_B_OPTIONAL_CONFIG;
    final Kick uniReqA1 = ReVomanConfigForWfs.PRIOR_APPT1_B_REQUIRED_CONFIG;
    final Kick uniOptA1 = ReVomanConfigForWfs.PRIOR_APPT1_B_OPTIONAL_CONFIG;
    final Kick uniReqA2 = ReVomanConfigForWfs.PRIOR_APPT2_B_REQUIRED_CONFIG;
    final Kick uniOptA2 = ReVomanConfigForWfs.PRIOR_APPT2_B_OPTIONAL_CONFIG;

    // (a) #1 required -> #2 required. OLD REFUSES the 2nd overlapping booking (enforces occupancy);
    // Unified BOOKS it (no prior-appointment occupancy check — double-books even a REQUIRED
    // resource). The old-side (a) verdict is the occupancy-is-enforced-at-all anchor: if it BOOKS,
    // the org allows overbooking and every old-side refusal below is meaningless — so name that
    // failure explicitly rather than surfacing a bare REFUSED != BOOKED.
    assertWithMessage(
            "(a) old required->required must REFUSE (occupancy enforced); if BOOKED, the org allows"
                + " overbooking and the old-side occupancy findings are vacuous")
        .that(oldOccupancyCell(oldReqA1, oldReqA2))
        .isEqualTo(SchedulerParityConfig.WriteOutcome.REFUSED);
    assertThat(unifiedOccupancyCell(uniReqA1, uniReqA2))
        .isEqualTo(SchedulerParityConfig.WriteOutcome.BOOKED);

    // (b) #1 OPTIONAL -> #2 required (the headline question). OLD still REFUSES — an OPTIONAL prior
    // assignment DOES occupy B on the old engine. Unified BOOKS — the optional prior assignment
    // does
    // not occupy (nor does any prior assignment).
    assertThat(oldOccupancyCell(oldOptA1, oldReqA2))
        .isEqualTo(SchedulerParityConfig.WriteOutcome.REFUSED);
    assertThat(unifiedOccupancyCell(uniOptA1, uniReqA2))
        .isEqualTo(SchedulerParityConfig.WriteOutcome.BOOKED);

    // (c) #1 optional -> #2 optional. OLD REFUSES even though appt #2 marks B optional — the old
    // engine
    // treats B's existing overlapping assignment as hard occupancy regardless of the 2nd booking's
    // flag.
    // Unified BOOKS.
    assertThat(oldOccupancyCell(oldOptA1, oldOptA2))
        .isEqualTo(SchedulerParityConfig.WriteOutcome.REFUSED);
    assertThat(unifiedOccupancyCell(uniOptA1, uniOptA2))
        .isEqualTo(SchedulerParityConfig.WriteOutcome.BOOKED);

    // (d) #1 required -> #2 optional. Same as (c) with a required first booking: OLD REFUSES,
    // Unified
    // BOOKS. Confirms old-side occupancy enforcement is independent of BOTH bookings' required
    // flags.
    assertThat(oldOccupancyCell(oldReqA1, oldOptA2))
        .isEqualTo(SchedulerParityConfig.WriteOutcome.REFUSED);
    assertThat(unifiedOccupancyCell(uniReqA1, uniOptA2))
        .isEqualTo(SchedulerParityConfig.WriteOutcome.BOOKED);
  }

  /**
   * Unified read-vs-write occupancy divergence (DEBUGGER-VERIFIED on live HEAD {@code 90cdfeb7},
   * {@code p4/262-patch}, {@code -Dversion=262}, 2026-07-07 — the running server IS the local HEAD
   * checkout, so this is current behavior, NOT version skew). Unified's READ surface DOES compute
   * occupancy: after appt #1 commits an overlapping ServiceAppointment on B, {@code
   * get-appointment-candidates} for appt #2's window EXCLUDES busy B while still offering the free
   * primary C. Yet the WRITE surface ({@code /actions/schedule}) BOOKS B over that same
   * appointment. The write path DOES run an occupancy check ({@code
   * InBusinessScheduleHandler.handle} → {@code SlotAvailabilityChecker.isSlotAvailable}) and DOES
   * load occupancy for busy required B — but its multi-resource slot-intersection ({@code
   * InBusinessGetSlotsHandler.getOverlappingSlotsBetweenResources}) iterates only {@code
   * schedulableSlots.keySet()}, and a fully-busy required resource produces ZERO slots so it is
   * ABSENT from the keySet and silently dropped; the free primary C's slot survives, {@code
   * hasExactSlotMatch}=true, B is booked. The read/write split is therefore a COLLAPSE difference:
   * get-candidates ({@code buildCandidatesList}) reports per-resource so it excludes B; the write
   * (get-slots) intersects over the keySet so it books B. Only the PRIMARY is guarded; a busy
   * primary would be refused. Live debugger trace (this cell): {@code serviceResourceIds}={C,B} but
   * {@code schedulableSlots.keySet()}={C} → {@code slotsOutput}=[11:00 slot] → {@code
   * hasExactSlotMatch}=true → BOOKED. NOT a missing policy rule (the handoff's CalendarEvents
   * hypothesis is false: that rule has since been removed and even on the live org gated only
   * Salesforce Event records, never ServiceAppointments).
   *
   * <p>Non-vacuity: appt #1's {@code priorAppt1SchedulingStatus == Success} proves B's overlapping
   * assignment actually committed, and the read-side control ({@code priorAppt2CandidatesCIncluded
   * == 1}, fail-loud) proves the get-candidates read ran and the candidate-id extraction works — so
   * the {@code priorAppt2CandidatesBIncluded == 0} finding is a real occupancy exclusion, not a
   * dead read. The write cell reuses {@link #unifiedOccupancyCell} (its own fresh-fixture revUp
   * that re-seeds appt #1), so the read-probe env and the write cell are independent revUps,
   * matching the existing occupancy test's per-cell pattern.
   */
  @Test
  void testPriorAssignmentUnifiedReadVsWriteE2E() {
    SchedulerParityConfig.assumeBothOrgCreds();
    final var env =
        CollectionsKt.last(
                ReVoman.revUp(
                    (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
                    ReVomanConfigForWfs.AUTH_CONFIG,
                    ReVomanConfigForWfs.AVAILABILITY_OP_HOURS_POLICY_CONFIG,
                    ReVomanConfigForWfs.PRIOR_ASSIGNMENT_FIXTURE_CONFIG,
                    ReVomanConfigForWfs.PRIOR_APPT1_B_REQUIRED_CONFIG,
                    ReVomanConfigForWfs.PRIOR_CANDIDATES_APPT2_CONFIG))
            .mutableEnv;
    // Non-vacuity: appt #1 actually committed B's overlapping assignment.
    assertThat(env.getAsString("priorAppt1SchedulingStatus")).isEqualTo("Success");
    // Control: the read surface offers the free primary C (proves the read ran and the extraction
    // works).
    assertWithMessage(
            "read-probe must offer free primary C; if not, the candidate extraction keys are wrong,"
                + " not an occupancy signal")
        .that(env.getAsString("priorAppt2CandidatesCIncluded"))
        .isEqualTo("1");
    // THE read-side finding: Unified's read path EXCLUDES busy B.
    assertThat(env.getAsString("priorAppt2CandidatesBIncluded")).isEqualTo("0");
    // THE write-side finding: the schedule action BOOKS B over the same appointment anyway.
    assertThat(
            unifiedOccupancyCell(
                ReVomanConfigForWfs.PRIOR_APPT1_B_REQUIRED_CONFIG,
                ReVomanConfigForWfs.PRIOR_APPT2_B_REQUIRED_CONFIG))
        .isEqualTo(SchedulerParityConfig.WriteOutcome.BOOKED);
  }

  /**
   * OLD overbooking gate characterization — the {@code OrgPreferences.Overbooking} pref is
   * NECESSARY BUT NOT SUFFICIENT to double-book an occupied worker. The existing {@link
   * #testPriorAssignmentOccupancyParity_E2E} pins OLD REFUSING all four cells at the default {@code
   * Overbooking=OFF}. The handoff hypothesis was "flip the pref ON → OLD books". This test flips it
   * ON (verified live: {@code updateMetadata(IndustriesSettings.enableOverbookingOrgPref=true)}
   * returns success) and shows OLD STILL REFUSES — because the pref is only HALF the gate.
   *
   * <p><b>Verified in {@code SchedulingServiceImpl.findAvailableTimeSlots} (Core p4/260-patch):</b>
   * an overlapping prior appointment is dropped from unavailability only when {@code
   * isOverlappingIntervalAndNotConcurrent(...)} sees a CONCURRENT slot, where {@code
   * isConcurrentSchedulingInterval(slot) = slot.getMaxAppointments() > 1} (line ~1924). The {@code
   * Overbooking} pref only gates {@code isaValidConcurrentAndConcurrentUnavailability} (line
   * ~1892), which merely refines remaining-capacity accounting WITHIN an already-concurrent slot.
   * With the member operating-hours {@code TimeSlot.MaxAppointments} at its default of 1 ({@code
   * TimeSlot.entity.xml defaultFormula="1"}; the prior-assignment fixture sets no MaxAppointments),
   * every slot is non-concurrent, so an overlapping appointment is a hard block regardless of the
   * pref. The dominant gate is {@code TimeSlot.MaxAppointments > 1} on the member OH; the pref is
   * necessary-but-not-sufficient. Full two-knob confirmation (pref ON + MaxAppointments≥2 → OLD
   * books) needs a fixture variant with concurrent-capacity TimeSlots and is tracked as follow-up
   * (see {@code ~/work/handoff}).
   *
   * <p>Overbooking is ORG-level committed state (not a session flag), so flipping it ON once before
   * the cells persists across each {@link #oldOccupancyCell}'s own fresh-AUTH revUp — the
   * intervening cells re-authenticate but observe the committed pref. The flip itself is asserted
   * to report success in {@link #flipOldOverbooking()} (fail-loud), so a REFUSED cell below
   * reflects the MaxAppointments gate, not a no-op flip. The revert runs in a {@code finally}
   * (best-effort, no assert) so a mid-run failure still restores the default-OFF org state.
   */
  @Test
  void testPriorAssignmentOldOverbookingPrefInsufficientE2E() {
    SchedulerParityConfig.assumeBothOrgCreds();
    try {
      flipOldOverbooking();
      // Even with Overbooking ON, all four cells still REFUSE: the member-OH TimeSlot
      // MaxAppointments
      // defaults to 1, so slots are non-concurrent and the overlapping prior appointment stays a
      // hard
      // block. Cell (a) req->req carries the fail-loud anchor — if it BOOKS, the pref DID suffice
      // and
      // this characterization (pref necessary-but-not-sufficient) is wrong; name that explicitly.
      assertWithMessage(
              "Overbooking ON but MaxAppointments=1: (a) req->req must still REFUSE (pref alone is"
                  + " insufficient; MaxAppointments>1 is the dominant gate). If BOOKED, the pref DID"
                  + " suffice and this characterization is wrong.")
          .that(
              oldOccupancyCell(
                  SchedulerParityConfig.OLD_PRIOR_APPT1_B_REQUIRED_CONFIG,
                  SchedulerParityConfig.OLD_PRIOR_APPT2_B_REQUIRED_CONFIG))
          .isEqualTo(SchedulerParityConfig.WriteOutcome.REFUSED);
      assertThat(
              oldOccupancyCell(
                  SchedulerParityConfig.OLD_PRIOR_APPT1_B_OPTIONAL_CONFIG,
                  SchedulerParityConfig.OLD_PRIOR_APPT2_B_REQUIRED_CONFIG))
          .isEqualTo(SchedulerParityConfig.WriteOutcome.REFUSED);
      assertThat(
              oldOccupancyCell(
                  SchedulerParityConfig.OLD_PRIOR_APPT1_B_OPTIONAL_CONFIG,
                  SchedulerParityConfig.OLD_PRIOR_APPT2_B_OPTIONAL_CONFIG))
          .isEqualTo(SchedulerParityConfig.WriteOutcome.REFUSED);
      assertThat(
              oldOccupancyCell(
                  SchedulerParityConfig.OLD_PRIOR_APPT1_B_REQUIRED_CONFIG,
                  SchedulerParityConfig.OLD_PRIOR_APPT2_B_OPTIONAL_CONFIG))
          .isEqualTo(SchedulerParityConfig.WriteOutcome.REFUSED);
    } finally {
      revertOldOverbooking();
    }
  }

  /**
   * Prior-assignment occupancy — the "busy worker booked as PRIMARY on a second appointment" case.
   * This adjudicates a teammate claim ("in Scheduler, if one SA has B as an OPTIONAL resource, B can
   * then be booked as PRIMARY in a second overlapping appointment") that the existing 2x2 {@link
   * #testPriorAssignmentOccupancyParity_E2E} does NOT cover: every cell of that matrix books B as a
   * required/optional NON-primary helper (C is always the primary), so B-as-primary was untested.
   *
   * <p>Fixture is the same 3-resource prior-assignment graph (A/B/C all AVAILABLE at 11:00, B the only
   * shared worker). Appt #1 assigns B as an OPTIONAL non-required helper (reusing {@code
   * *_PRIOR_APPT1_B_OPTIONAL_CONFIG} — the weakest possible prior hold, matching the teammate's
   * premise). Appt #2 then makes the busy B the PRIMARY+required resource and demotes the dedicated
   * free worker C to required, non-primary — the mirror of the b-required cell (which had C primary,
   * B non-primary).
   *
   * <p><b>OLD engine — REFUSED (LIVE-pinned).</b> OLD's write-path availability re-check ({@code
   * ServiceAppointmentServiceImpl.create → areResourcesAvailable}) treats B's existing overlapping
   * assignment as hard occupancy regardless of B's role on either booking (the 2x2 already showed OLD
   * refuses all four required/optional combinations). So the teammate's claim is FALSE on OLD: an
   * optional prior assignment still occupies B, and B cannot be re-booked into an overlapping slot —
   * as a primary or otherwise. Cell carries the fail-loud anchor: if it BOOKS, the org is
   * overbooking-enabled and the whole occupancy family is vacuous.
   *
   * <p><b>Unified engine — BOOKED in BOTH cells (LIVE-pinned 2026-07-07, both orgs; REFUTES "a busy
   * primary is spared").</b> The prediction was that making the busy B the PRIMARY would trip the
   * primary guard in {@code InBusinessGetSlotsHandler.processSlotsRequest} ({@code
   * containsKey(primaryResourceId)}) and REFUSE — the opposite of the b-required cell where a busy
   * NON-primary B is silently dropped from the {@code schedulableSlots.keySet()} intersection and
   * BOOKS. Both live cells DISPROVE that. Two cells are needed because the first has a confound:
   *
   * <ul>
   *   <li>appt #1 B OPTIONAL → appt #2 B PRIMARY: BOOKED — but on its own this is ambiguous, since a
   *       BOOKED could mean "a busy primary is not spared" OR "an optional prior simply never occupies
   *       B", leaving the primary guard untested.
   *   <li>appt #1 B REQUIRED → appt #2 B PRIMARY: BOOKED — the discriminating cell. A required prior
   *       DOES occupy B (proven live by {@link #testPriorAssignmentUnifiedReadVsWriteE2E}), so this
   *       removes the confound and shows the busy PRIMARY is double-booked outright.
   * </ul>
   *
   * <p>So the double-book is NOT gated by the assigned-resource role: a busy primary is double-booked
   * just like a busy non-primary helper. This directly contradicts the earlier "only the primary is
   * guarded / a busy primary is refused" reading (the debugger observation that only a busy primary was
   * refused must have been narrower than a general guard — a follow-up should re-examine whether the
   * request's primary maps to the {@code primaryResourceId} key the guard checks, via {@link
   * #testPriorAssignmentUnifiedReadVsWriteE2E}'s multi-resource collapse). Both outcomes are pinned to
   * the LIVE observation via {@link SchedulerParityConfig.WriteOutcome} (no forced old==unified
   * equality), the same way every other cell in this suite is pinned.
   */
  @Test
  void testPriorAssignmentBAsPrimaryE2E() {
    SchedulerParityConfig.assumeBothOrgCreds();

    // OLD: an OPTIONAL prior assignment on B still occupies B — re-booking B (now as PRIMARY) over the
    // same window is REFUSED. Directly refutes "optional prior ⇒ B free to be primary later" on OLD.
    // Fail-loud anchor: a BOOKED here means the org allows overbooking and the occupancy family is vacuous.
    assertWithMessage(
            "OLD: optional prior assignment on B must still block re-booking B as PRIMARY (occupancy is"
                + " role-agnostic). If BOOKED, the org allows overbooking and the occupancy findings are"
                + " vacuous — not a confirmation of the teammate's claim.")
        .that(
            oldOccupancyCell(
                SchedulerParityConfig.OLD_PRIOR_APPT1_B_OPTIONAL_CONFIG,
                SchedulerParityConfig.OLD_PRIOR_APPT2_B_PRIMARY_CONFIG))
        .isEqualTo(SchedulerParityConfig.WriteOutcome.REFUSED);

    // Unified: LIVE-OBSERVED BOOKED — the busy B is double-booked even as PRIMARY+required. This
    // REFUTES the "only the primary is guarded ⇒ busy primary refuses" prediction: the Unified write
    // path double-books B regardless of its assigned-resource role. Pinned to the observed outcome (as
    // every cell in this suite is), NOT forced to match OLD.
    assertThat(
            unifiedOccupancyCell(
                ReVomanConfigForWfs.PRIOR_APPT1_B_OPTIONAL_CONFIG,
                ReVomanConfigForWfs.PRIOR_APPT2_B_PRIMARY_CONFIG))
        .isEqualTo(SchedulerParityConfig.WriteOutcome.BOOKED);

    // DISCRIMINATING CELL — appt #1 B REQUIRED (unambiguously occupies B, exactly as the read-vs-write
    // test's committed prior), then appt #2 makes that busy B the PRIMARY. This strips the confound
    // from the optional-prior cell above (where a BOOKED could mean either "busy primary not spared" OR
    // "an optional prior simply doesn't occupy"). A required prior DOES occupy (proven live by {@link
    // #testPriorAssignmentUnifiedReadVsWriteE2E}), so this isolates the primary-guard question.
    // LIVE-OBSERVED BOOKED (2026-07-07): a busy PRIMARY is double-booked too. This is the decisive
    // refutation of "only the primary is guarded / a busy primary is spared" — with B's prior
    // assignment unambiguously occupying the window, Unified still books B on top of it as the primary.
    // So the double-book is NOT role-gated: busy helper AND busy primary both slip through.
    assertThat(
            unifiedOccupancyCell(
                ReVomanConfigForWfs.PRIOR_APPT1_B_REQUIRED_CONFIG,
                ReVomanConfigForWfs.PRIOR_APPT2_B_PRIMARY_CONFIG))
        .isEqualTo(SchedulerParityConfig.WriteOutcome.BOOKED);
  }

  /**
   * Flips the OLD org's {@code OrgPreferences.Overbooking} pref ON (AUTH → enable-overbooking) and
   * fails loud if the metadata update did not report success — else a subsequent BOOKED cell would
   * be a false positive against a still-OFF org.
   */
  private static void flipOldOverbooking() {
    final var env =
        CollectionsKt.last(
                ReVoman.revUp(
                    (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
                    SchedulerParityConfig.OLD_AUTH_CONFIG,
                    SchedulerParityConfig.OLD_ENABLE_OVERBOOKING_CONFIG))
            .mutableEnv;
    assertWithMessage(
            "Overbooking flip ON must report success; if not, the pref never took and every BOOKED"
                + " cell below is a false positive against a still-OFF org")
        .that(env.getAsString("overbookingFlipSuccess"))
        .isEqualTo("true");
  }

  /**
   * Reverts the OLD org's {@code OrgPreferences.Overbooking} pref back to OFF (AUTH →
   * disable-overbooking). Best-effort cleanup run in a {@code finally}: no assert, so a revert
   * hiccup does not mask the test's real verdict.
   */
  private static void revertOldOverbooking() {
    ReVoman.revUp(
        (r, ignore) -> {},
        SchedulerParityConfig.OLD_AUTH_CONFIG,
        SchedulerParityConfig.OLD_DISABLE_OVERBOOKING_CONFIG);
  }
}
