/*
 * Copyright 2026 salesforce.com, inc.
 * All Rights Reserved
 * Company Confidential
 */

package com.salesforce.revoman.integration.core.scheduler;

import static com.salesforce.revoman.input.config.StepPick.PostTxnStepPick.afterStepContainingHeader;
import static com.salesforce.revoman.integration.core.CoreUtils.ASSERT_COMPOSITE_GRAPH_RESPONSE_SUCCESS;
import static com.salesforce.revoman.integration.core.CoreUtils.ASSERT_COMPOSITE_RESPONSE_SUCCESS;
import static com.salesforce.revoman.integration.core.CoreUtils.unmarshallCompositeGraphResponse;
import static com.salesforce.revoman.integration.core.CoreUtils.unmarshallCompositeResponse;
import static com.salesforce.revoman.output.ExeType.HTTP_STATUS;

import com.salesforce.revoman.input.ExternalOrgConfig;
import com.salesforce.revoman.input.config.Kick;
import com.salesforce.revoman.integration.core.adapters.IDAdapter;
import com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs;
import com.salesforce.revoman.output.log.ConsoleRunLogSink;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;

/**
 * Two-org config seam for the Salesforce Scheduler ↔ Unified {@code 1.*} non-required
 * resource-fitness parity tests. The OLD side (scheduler org) is driven over public Scheduler REST;
 * the Unified side reuses the existing {@link ReVomanConfigForWfs} Unified Kicks against the live
 * Unified org. The two OnSite engines are SEPARATE re-implementations (old {@code
 * scheduling-impl.SchedulingServiceImpl} vs {@code
 * unified-scheduling-impl.InBusinessAppointmentSlotCalculator}), so parity is not guaranteed by
 * construction — these tests assert it.
 *
 * <p>The old side reads its creds from {@code ~/.revoman/scheduler-config.yaml} (a SECOND
 * external-org file, distinct from the {@code ~/.revoman/config.yaml} the Unified side uses). Both
 * files live in {@code $HOME}, never committed; absent creds → the tests JUnit-skip via {@link
 * #assumeBothOrgCreds}.
 */
public final class SchedulerParityConfig {

  private SchedulerParityConfig() {}

  /** OLD scheduler-org creds overlay, read once from {@code ~/.revoman/scheduler-config.yaml}. */
  static final Map<String, Object> SCHEDULER_ORG_CONFIG =
      ExternalOrgConfig.readExternalOrgConfig(
          System.getProperty("user.home") + "/.revoman/scheduler-config.yaml");

  static final String V3_SCHEDULER_PATH = "pm-templates/v3/core/scheduler/";
  static final String ENV_PATH = V3_SCHEDULER_PATH + "scheduler.environment.yaml";
  static final String NODE_MODULE_RELATIVE_PATH = "js";
  static final String IGNORE_HTTP_STATUS_UNSUCCESSFUL = "ignoreHTTPStatusUnsuccessful";

  static final Kick OLD_AUTH_CONFIG = oldKickFor(V3_SCHEDULER_PATH + "auth");
  static final Kick OLD_DOUBLE_BOOK_FIXTURE_CONFIG =
      oldKickFor(V3_SCHEDULER_PATH + "fixtures/double-book");

  /**
   * Shared Lightning-Scheduler user-access grant, DRY-consolidated out of the read and write
   * booking collections into its own Kick. The classic engine ({@code SchedulingServiceImpl
   * .removeResourcesWithoutPerm}) prunes every ServiceResource whose backing User lacks the {@code
   * lightningSchedulerUserAccess} user-perm on the REST path, so BOTH the read and the write revUp
   * this Kick right after {@link #OLD_DOUBLE_BOOK_FIXTURE_CONFIG} to keep the fixture resources
   * alive.
   */
  static final Kick OLD_GRANT_LS_ACCESS_CONFIG =
      oldKickFor(V3_SCHEDULER_PATH + "booking/grant-ls-user-access");

  static final Kick OLD_GET_SLOTS_DOUBLE_BOOK_CONFIG =
      oldKickFor(V3_SCHEDULER_PATH + "booking/get-appointment-slots-double-book");
  static final Kick OLD_BOOK_NON_REQUIRED_CONFIG =
      oldKickFor(V3_SCHEDULER_PATH + "booking/service-appointments-double-book-non-required");
  static final Kick OLD_BOOK_REQUIRED_CONTROL_CONFIG =
      oldKickFor(V3_SCHEDULER_PATH + "booking/service-appointments-double-book-required-control");

  // ## Prior-assignment occupancy (does an existing assignment block a later required booking, even
  // when B first joined as an OPTIONAL non-required resource?). 3-resource fixture: A/B/C all
  // AVAILABLE at 11:00 (no
  // shift gap — unlike double-book); B is the only shared worker (A on appt #1, C the dedicated
  // free
  // primary on appt #2), so a refused appt #2 can only be B's prior assignment.
  static final Kick OLD_PRIOR_ASSIGNMENT_FIXTURE_CONFIG =
      oldKickFor(V3_SCHEDULER_PATH + "fixtures/prior-assignment");
  static final Kick OLD_PRIOR_APPT1_B_REQUIRED_CONFIG =
      oldKickFor(V3_SCHEDULER_PATH + "booking/service-appointments-prior-appt1-b-required");
  static final Kick OLD_PRIOR_APPT1_B_OPTIONAL_CONFIG =
      oldKickFor(V3_SCHEDULER_PATH + "booking/service-appointments-prior-appt1-b-optional");
  static final Kick OLD_PRIOR_APPT2_B_REQUIRED_CONFIG =
      oldKickFor(V3_SCHEDULER_PATH + "booking/service-appointments-prior-appt2-b-required");
  static final Kick OLD_PRIOR_APPT2_B_OPTIONAL_CONFIG =
      oldKickFor(V3_SCHEDULER_PATH + "booking/service-appointments-prior-appt2-b-optional");
  static final Kick OLD_PRIOR_APPT2_B_PRIMARY_CONFIG =
      oldKickFor(V3_SCHEDULER_PATH + "booking/service-appointments-prior-appt2-b-primary");
  static final Kick OLD_ENABLE_OVERBOOKING_CONFIG =
      oldKickFor(V3_SCHEDULER_PATH + "booking/enable-overbooking");
  static final Kick OLD_DISABLE_OVERBOOKING_CONFIG =
      oldKickFor(V3_SCHEDULER_PATH + "booking/disable-overbooking");

  // ## Scenario 1b (Territory) — old-side Kicks. resourceB is a non-required resource whose
  // ServiceTerritoryMember coverage does NOT include the booking window (membership narrowed to end
  // at
  // noon while the window straddles noon), so only the classic engine's territory/STM-membership
  // filter
  // could exclude it. resourceA is a clean primary member covering the whole window. The fixture
  // Kick
  // runs the composite/graph then a follow-up STM-narrowing PATCH; the read/book Kicks reuse the
  // shared
  // grant-ls-user-access prerequisite exactly as the 1.5 double-book slice does.
  static final Kick OLD_TERRITORY_FIXTURE_CONFIG =
      oldKickFor(V3_SCHEDULER_PATH + "fixtures/territory-helper");
  static final Kick OLD_GET_SLOTS_TERRITORY_CONFIG =
      oldKickFor(V3_SCHEDULER_PATH + "booking/get-appointment-slots-territory");
  static final Kick OLD_BOOK_TERRITORY_NON_REQUIRED_CONFIG =
      oldKickFor(V3_SCHEDULER_PATH + "booking/service-appointments-territory-non-required");
  static final Kick OLD_BOOK_TERRITORY_REQUIRED_CONTROL_CONFIG =
      oldKickFor(V3_SCHEDULER_PATH + "booking/service-appointments-territory-required-control");

  // ## 1d WorkingLocations (territory-member ROLE) — a Secondary ('S') member non-required resource
  // under a PRIMARY-ONLY
  // AppointmentSchedulingPolicy. The fixture seeds the primary-only policy AND the P/S member
  // graph; the
  // read/book acts pass its id via schedulingPolicyId so the classic STM filter drops the Secondary
  // resourceB by its ROLE (SchedulingDbUtil.createPrimarySecondarySTMWhereCondition → TerritoryType
  // IN
  // ('P','R')) — not by availability, and not as a non-member.
  static final Kick OLD_WORKLOC_FIXTURE_CONFIG =
      oldKickFor(V3_SCHEDULER_PATH + "fixtures/workloc-helper");
  static final Kick OLD_GET_SLOTS_WORKLOC_CONFIG =
      oldKickFor(V3_SCHEDULER_PATH + "booking/get-appointment-slots-workloc");
  static final Kick OLD_BOOK_WORKLOC_NON_REQUIRED_CONFIG =
      oldKickFor(V3_SCHEDULER_PATH + "booking/service-appointments-workloc-non-required");
  static final Kick OLD_BOOK_WORKLOC_REQUIRED_CONTROL_CONFIG =
      oldKickFor(V3_SCHEDULER_PATH + "booking/service-appointments-workloc-required-control");

  // * NOTE 2026-07-03 gopal.akshintala: 1a (Excluded / account block-list) Kicks. The Excluded rule
  // is
  // * account-scoped (ResourcePreference PreferenceType='Excluded'), enforced on the OLD side ONLY
  // on the
  // * getAppointmentCandidates read path (checkResourcePrefs prunes the excluded SR); plain
  // * getAppointmentSlots and the classic service-appointments book path pass
  // accountResourcePreferences=null
  // * → the rule short-circuits true. Hence the read probe is getAppointmentCandidates (with
  // accountId), and
  // * the write probe characterizes the (unenforced) book outcome.
  static final Kick OLD_EXCLUDED_FIXTURE_CONFIG =
      oldKickFor(V3_SCHEDULER_PATH + "fixtures/excluded-helper");
  static final Kick OLD_GET_CANDIDATES_EXCLUDED_CONFIG =
      oldKickFor(V3_SCHEDULER_PATH + "booking/get-candidates-excluded");
  // * NOTE 2026-07-07 gopal.akshintala: slots-path complement of the candidates read - proves the
  // * account Excluded list is NOT applied on getAppointmentSlots (only on
  // getAppointmentCandidates).
  // * Reads getAppointmentSlots WITH accountId naming the excluded resourceB; expects slots > 0.
  static final Kick OLD_GET_SLOTS_EXCLUDED_CONFIG =
      oldKickFor(V3_SCHEDULER_PATH + "booking/get-appointment-slots-excluded");
  static final Kick OLD_BOOK_EXCLUDED_NON_REQUIRED_CONFIG =
      oldKickFor(V3_SCHEDULER_PATH + "booking/service-appointments-excluded-non-required");
  // * NOTE 2026-07-07 gopal.akshintala: required-excluded variant — books resourceB (the
  // account-Excluded
  // * resource) as the SINGLE required+primary assignedResource. Classic write passes
  // * accountResourcePreferences=null, so the Excluded list is not enforced on write → expected
  // BOOKED.
  // * This is the DIVERGENCE from Unified
  // (WfsRulesParityE2ETest#testExcludedResourcesReadWriteParityE2E
  // * asserts Unified REJECTS the same required-excluded write via SlotAvailabilityChecker reusing
  // the
  // * pruned get-slots read). Verifies the code-derived divergence live on 262.
  static final Kick OLD_BOOK_EXCLUDED_REQUIRED_CONFIG =
      oldKickFor(V3_SCHEDULER_PATH + "booking/service-appointments-excluded-required");

  // ## 1c (Skills) — the WorkType requires a Skill; skilled resourceA holds a ServiceResourceSkill,
  // skill-less resourceB has none. skills-helper is a TWO-Kick-shaped fixture (05-create-skill runs
  // the
  // SETUP-object Skill in its own transaction under adminToken — MIXED_DML forbids it inside the
  // non-setup graph — then 10-create-skills-helper-graph builds the rest and FK-references the
  // Skill).
  static final Kick OLD_SKILLS_FIXTURE_CONFIG =
      oldKickFor(V3_SCHEDULER_PATH + "fixtures/skills-helper");
  // Three read shapes: 10 single skill-less B → EXCLUDED (old single path skill-checks it → proves
  // the
  // rule exists), 20 single skilled A → INCLUDED (non-vacuity), 30 multi A-primary +
  // B-required-helper →
  // B ESCAPES (old multi path skill-checks the primary ONLY → parity with Unified non-required
  // resource-escapes).
  static final Kick OLD_GET_SLOTS_SKILLS_CONFIG =
      oldKickFor(V3_SCHEDULER_PATH + "booking/get-appointment-slots-skills");
  static final Kick OLD_BOOK_SKILLS_NON_REQUIRED_CONFIG =
      oldKickFor(V3_SCHEDULER_PATH + "booking/service-appointments-skills-non-required");

  // ## Decision 1.4 — Required-resources: the account (ResourcePreference Required) demands a
  // SPECIFIC
  // worker (resourceB), which is present ONLY as a NON-required resource. resourceA
  // (required+primary) is
  // clean but is NOT the account-required worker. On the OLD classic engine the account Required
  // pref is a
  // candidate-POOL FILTER (SchedulingDbUtil.checkResourcePrefs keeps only the account's required
  // set), read
  // via getAppointmentCandidates — semantically distinct from the Unified "non-required resource
  // must SATISFY a demand"
  // satisfier rule. See {@link
  // SchedulerVsUnifiedParityE2ETest#testHelperRequiredResourceParity_1_4_E2E}.
  static final Kick OLD_REQUIRED_HELPER_FIXTURE_CONFIG =
      oldKickFor(V3_SCHEDULER_PATH + "fixtures/required-helper");
  static final Kick OLD_GET_CANDIDATES_REQUIRED_CONFIG =
      oldKickFor(V3_SCHEDULER_PATH + "booking/get-candidates-required");
  static final Kick OLD_BOOK_REQUIRED_NON_REQUIRED_CONFIG =
      oldKickFor(V3_SCHEDULER_PATH + "booking/service-appointments-required-non-required");

  // ======================================================================================
  // Fan-out Decisions 2 / 3 / 4 / 4z / 5 / 8 / 9 — old-side Kick declarations.
  // ======================================================================================

  // ## Decision 2 (slot promise) — is a SHOWN time-slot a real promise? An intra-product read↔write
  // consistency check over a SINGLE resource: an AVAILABLE window (inside member OH ∪ Shift 08-16 →
  // read offers slots ⟺ book Succeeds) vs an UNAVAILABLE window (outside those hours → read 0 slots
  // ⟺
  // book Refused). The fixture is single-resource; the read is single-resource getAppointmentSlots
  // (requiredResourceIds=[A], NO primaryResourceId — the classic engine rejects a primary that also
  // appears required); the two book acts book resourceA (required+primary) into each window. The
  // fixture sets schedResourceBId = schedResourceAId so the shared grant-ls-user-access Kick
  // (grants
  // BOTH resource users) runs unchanged. See {@link
  // SchedulerVsUnifiedParityE2ETest#testSlotPromiseParity_2_E2E}.
  static final Kick OLD_PROMISE_FIXTURE_CONFIG =
      oldKickFor(V3_SCHEDULER_PATH + "fixtures/promise-helper");
  static final Kick OLD_GET_SLOTS_PROMISE_AVAILABLE_CONFIG =
      oldKickFor(V3_SCHEDULER_PATH + "booking/get-appointment-slots-promise-available");
  static final Kick OLD_GET_SLOTS_PROMISE_UNAVAILABLE_CONFIG =
      oldKickFor(V3_SCHEDULER_PATH + "booking/get-appointment-slots-promise-unavailable");
  static final Kick OLD_BOOK_PROMISE_AVAILABLE_CONFIG =
      oldKickFor(V3_SCHEDULER_PATH + "booking/service-appointments-promise-available");
  static final Kick OLD_BOOK_PROMISE_UNAVAILABLE_CONFIG =
      oldKickFor(V3_SCHEDULER_PATH + "booking/service-appointments-promise-unavailable");

  // ## Decision 3 — a single AssignedResource sent OMITTING isRequiredResource entirely. On Unified
  // the
  // missing-flag payload CRASHES (Boolean.booleanValue() NPE, HTTP 500 INTERNAL_SERVER_ERROR). The
  // OLD
  // probe reuses the double-book fixture (fresh users + grant chain, resourceA FREE at the 11:00
  // window
  // so availability does not confound) and books a SINGLE resourceA: Act A OMITS isRequiredResource
  // (the
  // parity question — does OLD also crash, or degrade gracefully?), and Act B (doc L142 control)
  // sends a
  // single isRequiredResource=true with NO isPrimaryResource → must BOOK (proves the fixture is
  // live).
  static final Kick OLD_BOOK_MISSING_REQUIRED_FLAG_CONFIG =
      oldKickFor(V3_SCHEDULER_PATH + "booking/service-appointments-missing-required-flag");
  static final Kick OLD_BOOK_SINGLE_REQUIRED_NO_PRIMARY_CONFIG =
      oldKickFor(V3_SCHEDULER_PATH + "booking/service-appointments-single-required-no-primary");

  // ## Decision 4 — two "primary" workers on ONE appointment are rejected. Multi-resource
  // scheduling
  // requires EXACTLY one primary. The fixture seeds TWO clean, fully-available PRIMARY-eligible
  // resources
  // (both member OH + Confirmed Shift 10-14, covering the 11:00-11:30 window) so availability is
  // NOT the
  // discriminator — only the primary-count rule is. The two-primary book act sends both
  // AssignedResources
  // isPrimaryResource=true → REFUSED. LIVE-OBSERVED: the OLD connect API rejects at INPUT
  // validation with
  // errorCode INVALID_API_INPUT / HTTP 400, "Only one assignedResource can have isPrimaryResource
  // set to
  // true…" (no booking). The one-primary control (A primary + B non-primary, both free) BOOKS
  // (non-vacuity).
  // Both engines reject at connect-API INPUT validation (pre-persist) at HTTP 400 — Unified with
  // INVALID_INPUT,
  // OLD with INVALID_API_INPUT — so the WHERE coincides and only the errorCode + message text
  // differ; the
  // OUTCOME is parity: both refuse two primaries, no booking. See {@link
  // SchedulerVsUnifiedParityE2ETest#testTwoPrimaryParity_4_E2E}.
  static final Kick OLD_TWO_PRIMARY_FIXTURE_CONFIG =
      oldKickFor(V3_SCHEDULER_PATH + "fixtures/two-primary-helper");
  static final Kick OLD_BOOK_TWO_PRIMARY_CONFIG =
      oldKickFor(V3_SCHEDULER_PATH + "booking/service-appointments-two-primary");
  static final Kick OLD_BOOK_ONE_PRIMARY_CONTROL_CONFIG =
      oldKickFor(V3_SCHEDULER_PATH + "booking/service-appointments-one-primary-control");

  // ## Decision 4z — a reschedule can leave an appointment with NO primary worker. The classic
  // Scheduler exposes NO reschedule connect API; "rescheduling" on the old side is direct DML/REST
  // on
  // the SA's AssignedResource rows. So the OLD analog is: (fixture) a CLEAN two-resource SA where
  // BOTH
  // resources are available at the window, (book) create it with resourceA primary+required +
  // resourceB
  // required and capture the AR ids, then (delete-primary) DELETE the primary AssignedResource and
  // (demote-primary) PATCH it to IsPrimaryResource=false — each attempting to leave the SA with no
  // primary. The old-side enforcement is the SAVE-TIME LightningSchedulerAssignedResourceValidator:
  // validatePrimaryAssignedResourceOnDelete BLOCKS deleting a primary AR (FIELD_INTEGRITY_EXCEPTION
  // "AssignedResourceDelete"), while validateAssignedResourceOnSave guards only
  // primary-must-be-required
  // and at-most-one-primary (no "must keep a primary" rule). The Unified side reuses the proven WFS
  // reschedule-no-primary acts (validation ALLOWS zero primaries; validatePrimaryResourceCount
  // throws
  // only when primaryCount > 1). See {@link
  // SchedulerVsUnifiedParityE2ETest#testRescheduleNoPrimaryParity_4z_E2E}.
  static final Kick OLD_RESCHEDULE_HELPER_FIXTURE_CONFIG =
      oldKickFor(V3_SCHEDULER_PATH + "fixtures/reschedule-helper");
  static final Kick OLD_BOOK_CLEAN_TWO_RESOURCE_CONFIG =
      oldKickFor(V3_SCHEDULER_PATH + "booking/service-appointments-clean-two-resource");
  static final Kick OLD_AR_DELETE_PRIMARY_CONFIG =
      oldKickFor(V3_SCHEDULER_PATH + "booking/assignedresource-delete-primary");
  static final Kick OLD_AR_DEMOTE_PRIMARY_CONFIG =
      oldKickFor(V3_SCHEDULER_PATH + "booking/assignedresource-demote-primary");

  // * NOTE 2026-07-03 gopal.akshintala: Decision 5 (primary-not-required) — a single
  // AssignedResource
  // * marked isPrimaryResource=true BUT isRequiredResource=false is a contradiction. The window is
  // FREE
  // * (availability passes), so the classic book path inserts the AssignedResource row and the
  // SAVE-TIME
  // * LightningSchedulerAssignedResourceValidator (fieldservice-impl — the SAME validator Unified
  // hits
  // * on this API/Apex/DML) rejects it ("Only an required service resource can be set as a primary
  // service
  // * resource."). Probe: single primary + NOT required → REFUSED. Control: flip
  // isRequiredResource=true
  // * → BOOKED (isolates the reject to the not-required flag). Single-resource fixture, so only
  // resourceA
  // * is needed and it is fully available over the 11:00-11:30 window.
  static final Kick OLD_PRIMARY_NOT_REQUIRED_FIXTURE_CONFIG =
      oldKickFor(V3_SCHEDULER_PATH + "fixtures/primary-not-required-helper");
  static final Kick OLD_BOOK_PRIMARY_NOT_REQUIRED_CONFIG =
      oldKickFor(V3_SCHEDULER_PATH + "booking/service-appointments-primary-not-required");
  static final Kick OLD_BOOK_PRIMARY_REQUIRED_CONTROL_CONFIG =
      oldKickFor(V3_SCHEDULER_PATH + "booking/service-appointments-primary-required-control");

  // ## Decision 8 — resourceLimitApptDistribution cap on the load-balancing candidates read path
  // (read-only). The OLD classic engine has NO SchedulingObjective/SchedulingPolicyObjective (those
  // are
  // Unified-only); its load-balancing cap instead lives behind an
  // AppointmentAssignmentPolicy(loadBalancing)
  // SETUP record whose FK on the AppointmentSchedulingPolicy flips
  // SchedulingServiceImpl.getAppointmentCandidatesForTerritoryWorkTypes onto the smart-scheduling
  // path
  // (SmartSchedulerServiceImpl.getServiceResourceByResourceLimitApptDistribution →
  // getLeastUtilizedResources
  // → subList(0, N)). The cap engages ONLY when filterByResources is EMPTY, the policy carries that
  // FK, AND
  // the org has smart scheduling enabled (SsAppointmentDistribution pref + AppointmentDistribution
  // perm) — so
  // the fixture seeds the assignment policy + policy FK, and the reads omit filterByResources.
  // limit=0 →
  // subList(0,0) → empty (a literal cap-of-0, matching Unified's Stream.limit(0)); limit=50 (> 2
  // seeded
  // resources) → no trim → pool returned. See
  // {@link SchedulerVsUnifiedParityE2ETest#testResourceLimitCapParity_8_E2E}.
  static final Kick OLD_LIMIT_FIXTURE_CONFIG =
      oldKickFor(V3_SCHEDULER_PATH + "fixtures/limit-helper");
  static final Kick OLD_GET_CANDIDATES_LIMIT_ZERO_CONFIG =
      oldKickFor(V3_SCHEDULER_PATH + "booking/get-candidates-limit-zero");
  static final Kick OLD_GET_CANDIDATES_LIMIT_POSITIVE_CONFIG =
      oldKickFor(V3_SCHEDULER_PATH + "booking/get-candidates-limit-positive");

  // * NOTE 2026-07-03 gopal.akshintala: Decision 9 (Shift sharing-mode split) — the classic-engine
  // * mirror of the WFS auth-personas-dec9 machinery. Unlike the 1.* non-required resource-fitness
  // scenarios (single
  // * admin session), Decision 9 is a CROSS-PERSONA sharing question, so the old side mints TWO
  // real
  // * personas with their OWN SOAP sessions (a MANAGER who OWNS the Private fixture rows + a
  // sharing-
  // * deprived CASE-WORKER) plus an admin-created resource-owner User, then runs the SAME classic
  // * getAppointmentSlots read as each persona over the manager-owned fixture. The parity crux
  // (source-
  // * confirmed, scheduling-impl): the classic engine reads SHIFTS in FULL-access mode
  // * (SoqlUtil.runSoqlWithoutMruUpdate single-arg → SFDC_FULL, ShiftsDbUtil.getShiftEntities)
  // while its
  // * STM/ServiceResource read is the user-mode gate (SystemMode.NONE, gated by
  // orgHasDepriveSoqlAccess)
  // * — the INVERSE of Unified, whose SHIFT read is the user-mode gate. So old never gates on the
  // shift; the
  // * observable slot-count split (if any) comes from the STM/ServiceResource sharing gate instead.
  static final Kick OLD_AUTH_PERSONAS_DEC9_CONFIG =
      oldKickFor(V3_SCHEDULER_PATH + "auth-personas-old");
  static final Kick OLD_SHARING_SPLIT_FIXTURE_CONFIG =
      oldKickFor(V3_SCHEDULER_PATH + "fixtures/sharing-split-helper");
  static final Kick OLD_GET_SLOTS_SHARING_AS_MANAGER_CONFIG =
      oldKickFor(V3_SCHEDULER_PATH + "booking/get-appointment-slots-sharing-as-manager");
  static final Kick OLD_GET_SLOTS_SHARING_AS_CASEWORKER_CONFIG =
      oldKickFor(V3_SCHEDULER_PATH + "booking/get-appointment-slots-sharing-as-caseworker");

  /**
   * Skip (JUnit assumption) unless BOTH orgs' creds are present: the Unified side reads {@code
   * ~/.revoman/config.yaml} and the old side reads {@code ~/.revoman/scheduler-config.yaml} (here).
   * Either missing → skip, not fail.
   */
  static void assumeBothOrgCreds() {
    // Check WFS creds (262 org) — read directly since EXTERNAL_ORG_CONFIG is package-private in
    // ReVomanConfigForWfs (cross-package not visible).
    final var wfsConfig = ExternalOrgConfig.readExternalOrgConfig();
    Assumptions.assumeTrue(
        hasText(wfsConfig, "baseUrl")
            && hasText(wfsConfig, "username")
            && hasText(wfsConfig, "password"),
        "WFS external-org creds absent — set ~/.revoman/config.yaml (baseUrl/username/password)."
            + " Skipping.");
    // Check scheduler creds
    Assumptions.assumeTrue(
        schedulerHasText("baseUrl") && schedulerHasText("username") && schedulerHasText("password"),
        "Scheduler-org creds absent — set ~/.revoman/scheduler-config.yaml (baseUrl/username/password)."
            + " Skipping.");
  }

  private static boolean hasText(final Map<String, Object> config, final String key) {
    final var value = config.get(key);
    return value != null && !value.toString().isBlank();
  }

  private static boolean schedulerHasText(final String key) {
    return hasText(SCHEDULER_ORG_CONFIG, key);
  }

  /** Normalized read decision for a resource: was it offered/kept in the availability result? */
  enum ReadDecision {
    INCLUDED,
    EXCLUDED
  }

  /**
   * Normalized write outcome, comparable across old-REST and Unified error envelopes (guide R3).
   */
  enum WriteOutcome {
    BOOKED,
    REFUSED,
    CRASHED
  }

  /**
   * Decision 4z normalized outcome for a "leave the appointment with no primary" attempt,
   * comparable across the OLD sObject DELETE/PATCH probes and the Unified reschedule API:
   * LEFT_NO_PRIMARY means the appointment ended with zero primary workers (the state the doc says
   * should be impossible), BLOCKED means a rule refused the change (2xx never happened, or the
   * primary survived), CRASHED means an HTTP 500 / INTERNAL_SERVER_ERROR.
   */
  enum NoPrimaryOutcome {
    LEFT_NO_PRIMARY,
    BLOCKED,
    CRASHED
  }

  /**
   * Decision-9 sharing-gate verdict, shared by both engines: does a sharing-DEPRIVED reader (the
   * case-worker, no sharing on the private fixture rows) get GATED (zero slots, HTTP 200, no error)
   * or does it read the SAME availability as the privileged reader (OPEN)? On Unified this gate is
   * the SHIFT user-mode read; on OLD classic it is the STM/ServiceResource user-mode read (the
   * shift read is full-access) — same observable (zero slots) via an INVERTED axis.
   */
  enum SharingGate {
    GATED,
    OPEN
  }

  /** Old side: a busy resource is INCLUDED iff naming it required did NOT zero the slot count. */
  static ReadDecision oldReadDecision(final String withBusyCount) {
    return "0".equals(withBusyCount) ? ReadDecision.EXCLUDED : ReadDecision.INCLUDED;
  }

  /**
   * Old side (Decision 8): did the load-balancing {@code resourceLimitApptDistribution} cap
   * actually engage? It engaged iff the limit=0 probe returned an EMPTY resource pool ({@code "0"})
   * WHILE the positive-limit control still returned resources (&gt; 0). If the org lacks smart
   * scheduling the classic engine never takes the load-balancing branch, so limit=0 does NOT empty
   * the pool (both counts &gt; 0) → cap did not engage (a configuration nuance, not a divergence in
   * the cap semantics).
   */
  static boolean oldLimitCapEngaged(final String limitZeroCount, final String limitPositiveCount) {
    final var positiveOffered =
        limitPositiveCount != null
            && !limitPositiveCount.isBlank()
            && !"0".equals(limitPositiveCount);
    return "0".equals(limitZeroCount) && positiveOffered;
  }

  /**
   * A sharing-deprived reader is GATED iff its slot count is "0" on an HTTP-200 (no-error) read —
   * the silent empty-availability contract. A non-zero count is OPEN (sees availability despite
   * lacking sharing). A non-200 is neither (it is an access/perm error, not the silent sharing
   * gate) → reported as OPEN=false via GATED only on the clean-empty case.
   */
  static SharingGate sharingGate(final String slotCount, final String http) {
    return "0".equals(slotCount) && "200".equals(http) ? SharingGate.GATED : SharingGate.OPEN;
  }

  /**
   * Old side (sObject DELETE/PATCH probe): LEFT_NO_PRIMARY iff the mutation succeeded (HTTP 2xx, no
   * errorCode) AND the post-mutation state confirms no surviving primary ({@code primaryGone} is
   * "1"); CRASHED on HTTP 500; otherwise BLOCKED (a save-validator rejection kept a primary).
   */
  static NoPrimaryOutcome oldNoPrimaryOutcome(
      final String http, final String errorCode, final String primaryGone) {
    if ("500".equals(http)) {
      return NoPrimaryOutcome.CRASHED;
    }
    final var httpOk = http != null && http.startsWith("2");
    final var noError = errorCode == null || errorCode.isBlank();
    return (httpOk && noError && "1".equals(primaryGone))
        ? NoPrimaryOutcome.LEFT_NO_PRIMARY
        : NoPrimaryOutcome.BLOCKED;
  }

  /**
   * Unified side (reschedule API): LEFT_NO_PRIMARY iff the reschedule returned Success (persisting
   * a no-primary crew); CRASHED on HTTP 500 / INTERNAL_SERVER_ERROR; otherwise BLOCKED (validation
   * or the downstream availability re-check refused it). Note: on the live Unified org the
   * delete-primary arms are BLOCKED by the availability re-check, NOT by any no-primary rule —
   * validation itself ALLOWS zero primaries; that validation-layer permissiveness is the Unified
   * fact the parity diff turns on.
   */
  static NoPrimaryOutcome unifiedNoPrimaryOutcome(
      final String schedulingStatus, final String errorCode, final String http) {
    if ("Success".equals(schedulingStatus)) {
      return NoPrimaryOutcome.LEFT_NO_PRIMARY;
    }
    return ("500".equals(http) || "INTERNAL_SERVER_ERROR".equals(errorCode))
        ? NoPrimaryOutcome.CRASHED
        : NoPrimaryOutcome.BLOCKED;
  }

  /** Old side: BOOKED iff an SA id came back; CRASHED on HTTP 500; else REFUSED. */
  static WriteOutcome oldWriteOutcome(final String saId, final String http) {
    if (saId != null && !saId.isBlank()) {
      return WriteOutcome.BOOKED;
    }
    return "500".equals(http) ? WriteOutcome.CRASHED : WriteOutcome.REFUSED;
  }

  /**
   * Unified side: schedulingStatus=="Success" → BOOKED; ScheduleError/PersistError → REFUSED; 500 →
   * CRASHED.
   */
  static WriteOutcome unifiedWriteOutcome(final String schedulingStatus, final String http) {
    if ("Success".equals(schedulingStatus)) {
      return WriteOutcome.BOOKED;
    }
    return "500".equals(http) ? WriteOutcome.CRASHED : WriteOutcome.REFUSED;
  }

  /**
   * Old side (candidates path): a resource is INCLUDED iff it appears in at least one candidate's
   * {@code resources[]}. The getAppointmentCandidates read prunes an account-Excluded
   * ServiceResource, so an excluded resource comes back {@code "0"} (present-flag) → EXCLUDED. Used
   * by 1a where the rule is account ResourcePreference(Excluded), enforced only on the candidates
   * read path.
   */
  static ReadDecision oldCandidatesReadDecision(final String resourcePresentFlag) {
    return "1".equals(resourcePresentFlag) ? ReadDecision.INCLUDED : ReadDecision.EXCLUDED;
  }

  /**
   * Unified side (Decision 1.4), normalizing from the error ENVELOPE rather than HTTP: the Unified
   * violating write returns a top-level {@code [{errorCode:"INTERNAL_SERVER_ERROR", ...}]} (a
   * serviceTerritoryMembers NPE) → CRASHED; a schedulingStatus=="Success" → BOOKED; anything else →
   * REFUSED.
   */
  static WriteOutcome unifiedWriteOutcomeFromErrorCode(
      final String schedulingStatus, final String errorCode) {
    if ("Success".equals(schedulingStatus)) {
      return WriteOutcome.BOOKED;
    }
    return "INTERNAL_SERVER_ERROR".equals(errorCode) ? WriteOutcome.CRASHED : WriteOutcome.REFUSED;
  }

  /**
   * Old-side read decision for the Required-resources (1.4) candidates path, normalizing to the
   * write vocabulary so the read↔write divergence is directly comparable: an HTTP 500 / errorCode
   * INTERNAL_SERVER_ERROR is CRASHED (would match the Unified write); a clean 0-candidate response
   * is REFUSED; a non-empty candidate list is BOOKED (offered).
   */
  static WriteOutcome oldCandidatesOutcome(
      final String candidateCount, final String http, final String errorCode) {
    if ("500".equals(http) || "INTERNAL_SERVER_ERROR".equals(errorCode)) {
      return WriteOutcome.CRASHED;
    }
    return (candidateCount != null && !"0".equals(candidateCount) && !candidateCount.isBlank())
        ? WriteOutcome.BOOKED
        : WriteOutcome.REFUSED;
  }

  /**
   * Old-side Kick: same wiring as {@link ReVomanConfigForWfs} but overlaying the SCHEDULER creds.
   */
  private static Kick oldKickFor(final String templatePath) {
    return Kick.configure()
        .templatePath(templatePath)
        .environmentPath(ENV_PATH)
        .dynamicEnvironment(SCHEDULER_ORG_CONFIG)
        .responseConfig(unmarshallCompositeGraphResponse(), unmarshallCompositeResponse())
        .hooks(ASSERT_COMPOSITE_GRAPH_RESPONSE_SUCCESS, ASSERT_COMPOSITE_RESPONSE_SUCCESS)
        .globalCustomTypeAdapter(IDAdapter.INSTANCE)
        .nodeModulesPath(NODE_MODULE_RELATIVE_PATH)
        .haltOnFailureOfTypeExcept(
            HTTP_STATUS, afterStepContainingHeader(IGNORE_HTTP_STATUS_UNSUCCESSFUL))
        .insecureHttp(true)
        .runLogSink(ConsoleRunLogSink.DEFAULT)
        .off();
  }
}
