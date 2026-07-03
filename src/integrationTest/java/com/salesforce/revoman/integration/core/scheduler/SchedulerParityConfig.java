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
import java.util.Map;
import org.junit.jupiter.api.Assumptions;

/**
 * Two-org config seam for the Salesforce Scheduler ↔ Unified(264) {@code 1.*} helper-fitness parity
 * tests. The OLD side (scheduler org) is driven over public Scheduler REST; the 264 side reuses the
 * existing {@link ReVomanConfigForWfs} Unified Kicks against the 262 org (endpoints identical 262↔264 per the parity effort).
 * The two OnSite engines are SEPARATE re-implementations (old {@code
 * scheduling-impl.SchedulingServiceImpl} vs {@code unified-scheduling-impl.InBusinessAppointmentSlotCalculator}),
 * so parity is not guaranteed by construction — these tests assert it.
 *
 * <p>The old side reads its creds from {@code ~/.revoman/scheduler-config.yaml} (a SECOND external-org
 * file, distinct from the {@code ~/.revoman/config.yaml} the Unified side uses). Both files live in
 * {@code $HOME}, never committed; absent creds → the tests JUnit-skip via {@link #assumeBothOrgCreds}.
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
   * Shared Lightning-Scheduler user-access grant, DRY-consolidated out of the read and write booking
   * collections into its own Kick. The classic engine ({@code SchedulingServiceImpl
   * .removeResourcesWithoutPerm}) prunes every ServiceResource whose backing User lacks the {@code
   * lightningSchedulerUserAccess} user-perm on the REST path, so BOTH the read and the write revUp this
   * Kick right after {@link #OLD_DOUBLE_BOOK_FIXTURE_CONFIG} to keep the fixture resources alive.
   */
  static final Kick OLD_GRANT_LS_ACCESS_CONFIG =
      oldKickFor(V3_SCHEDULER_PATH + "booking/grant-ls-user-access");
  static final Kick OLD_GET_SLOTS_DOUBLE_BOOK_CONFIG =
      oldKickFor(V3_SCHEDULER_PATH + "booking/get-appointment-slots-double-book");
  static final Kick OLD_BOOK_NON_REQUIRED_CONFIG =
      oldKickFor(V3_SCHEDULER_PATH + "booking/service-appointments-double-book-non-required");
  static final Kick OLD_BOOK_REQUIRED_CONTROL_CONFIG =
      oldKickFor(V3_SCHEDULER_PATH + "booking/service-appointments-double-book-required-control");

  // ## Scenario 1b (Territory) — old-side Kicks. resourceB is a non-required helper whose
  // ServiceTerritoryMember coverage does NOT include the booking window (membership narrowed to end at
  // noon while the window straddles noon), so only the classic engine's territory/STM-membership filter
  // could exclude it. resourceA is a clean primary member covering the whole window. The fixture Kick
  // runs the composite/graph then a follow-up STM-narrowing PATCH; the read/book Kicks reuse the shared
  // grant-ls-user-access prerequisite exactly as the 1.5 double-book slice does.
  static final Kick OLD_TERRITORY_FIXTURE_CONFIG =
      oldKickFor(V3_SCHEDULER_PATH + "fixtures/territory-helper");
  static final Kick OLD_GET_SLOTS_TERRITORY_CONFIG =
      oldKickFor(V3_SCHEDULER_PATH + "booking/get-appointment-slots-territory");
  static final Kick OLD_BOOK_TERRITORY_NON_REQUIRED_CONFIG =
      oldKickFor(V3_SCHEDULER_PATH + "booking/service-appointments-territory-non-required");
  static final Kick OLD_BOOK_TERRITORY_REQUIRED_CONTROL_CONFIG =
      oldKickFor(V3_SCHEDULER_PATH + "booking/service-appointments-territory-required-control");

  // ## 1d WorkingLocations (territory-member ROLE) — a Secondary ('S') member helper under a PRIMARY-ONLY
  // AppointmentSchedulingPolicy. The fixture seeds the primary-only policy AND the P/S member graph; the
  // read/book acts pass its id via schedulingPolicyId so the classic STM filter drops the Secondary
  // resourceB by its ROLE (SchedulingDbUtil.createPrimarySecondarySTMWhereCondition → TerritoryType IN
  // ('P','R')) — not by availability, and not as a non-member.
  static final Kick OLD_WORKLOC_FIXTURE_CONFIG =
      oldKickFor(V3_SCHEDULER_PATH + "fixtures/workloc-helper");
  static final Kick OLD_GET_SLOTS_WORKLOC_CONFIG =
      oldKickFor(V3_SCHEDULER_PATH + "booking/get-appointment-slots-workloc");
  static final Kick OLD_BOOK_WORKLOC_NON_REQUIRED_CONFIG =
      oldKickFor(V3_SCHEDULER_PATH + "booking/service-appointments-workloc-non-required");
  static final Kick OLD_BOOK_WORKLOC_REQUIRED_CONTROL_CONFIG =
      oldKickFor(V3_SCHEDULER_PATH + "booking/service-appointments-workloc-required-control");

  // * NOTE 2026-07-03 gopal.akshintala: 1a (Excluded / account block-list) Kicks. The Excluded rule is
  // * account-scoped (ResourcePreference PreferenceType='Excluded'), enforced on the OLD side ONLY on the
  // * getAppointmentCandidates read path (checkResourcePrefs prunes the excluded SR); plain
  // * getAppointmentSlots and the classic service-appointments book path pass accountResourcePreferences=null
  // * → the rule short-circuits true. Hence the read probe is getAppointmentCandidates (with accountId), and
  // * the write probe characterizes the (unenforced) book outcome.
  static final Kick OLD_EXCLUDED_FIXTURE_CONFIG =
      oldKickFor(V3_SCHEDULER_PATH + "fixtures/excluded-helper");
  static final Kick OLD_GET_CANDIDATES_EXCLUDED_CONFIG =
      oldKickFor(V3_SCHEDULER_PATH + "booking/get-candidates-excluded");
  static final Kick OLD_BOOK_EXCLUDED_NON_REQUIRED_CONFIG =
      oldKickFor(V3_SCHEDULER_PATH + "booking/service-appointments-excluded-non-required");

  // ## 1c (Skills) — the WorkType requires a Skill; skilled resourceA holds a ServiceResourceSkill,
  // skill-less resourceB has none. skills-helper is a TWO-Kick-shaped fixture (05-create-skill runs the
  // SETUP-object Skill in its own transaction under adminToken — MIXED_DML forbids it inside the
  // non-setup graph — then 10-create-skills-helper-graph builds the rest and FK-references the Skill).
  static final Kick OLD_SKILLS_FIXTURE_CONFIG =
      oldKickFor(V3_SCHEDULER_PATH + "fixtures/skills-helper");
  // Three read shapes: 10 single skill-less B → EXCLUDED (old single path skill-checks it → proves the
  // rule exists), 20 single skilled A → INCLUDED (non-vacuity), 30 multi A-primary + B-required-helper →
  // B ESCAPES (old multi path skill-checks the primary ONLY → parity with 264 helper-escapes).
  static final Kick OLD_GET_SLOTS_SKILLS_CONFIG =
      oldKickFor(V3_SCHEDULER_PATH + "booking/get-appointment-slots-skills");
  static final Kick OLD_BOOK_SKILLS_NON_REQUIRED_CONFIG =
      oldKickFor(V3_SCHEDULER_PATH + "booking/service-appointments-skills-non-required");

  // ## Decision 1.4 — Required-resources: the account (ResourcePreference Required) demands a SPECIFIC
  // worker (resourceB), which is present ONLY as a NON-required helper. resourceA (required+primary) is
  // clean but is NOT the account-required worker. On the OLD classic engine the account Required pref is a
  // candidate-POOL FILTER (SchedulingDbUtil.checkResourcePrefs keeps only the account's required set), read
  // via getAppointmentCandidates — semantically distinct from the 264 "helper must SATISFY a demand"
  // satisfier rule. See {@link SchedulerVsUnifiedParityE2ETest#testHelperRequiredResourceParity_1_4_E2E}.
  static final Kick OLD_REQUIRED_HELPER_FIXTURE_CONFIG =
      oldKickFor(V3_SCHEDULER_PATH + "fixtures/required-helper");
  static final Kick OLD_GET_CANDIDATES_REQUIRED_CONFIG =
      oldKickFor(V3_SCHEDULER_PATH + "booking/get-candidates-required");
  static final Kick OLD_BOOK_REQUIRED_NON_REQUIRED_CONFIG =
      oldKickFor(V3_SCHEDULER_PATH + "booking/service-appointments-required-non-required");

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
        hasText(wfsConfig, "baseUrl") && hasText(wfsConfig, "username") && hasText(wfsConfig, "password"),
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

  /** Normalized write outcome, comparable across old-REST and Unified error envelopes (guide R3). */
  enum WriteOutcome {
    BOOKED,
    REFUSED,
    CRASHED
  }

  /** Old side: a busy resource is INCLUDED iff naming it required did NOT zero the slot count. */
  static ReadDecision oldReadDecision(final String withBusyCount) {
    return "0".equals(withBusyCount) ? ReadDecision.EXCLUDED : ReadDecision.INCLUDED;
  }

  /** Old side: BOOKED iff an SA id came back; CRASHED on HTTP 500; else REFUSED. */
  static WriteOutcome oldWriteOutcome(final String saId, final String http) {
    if (saId != null && !saId.isBlank()) {
      return WriteOutcome.BOOKED;
    }
    return "500".equals(http) ? WriteOutcome.CRASHED : WriteOutcome.REFUSED;
  }

  /** Unified side: schedulingStatus=="Success" → BOOKED; ScheduleError/PersistError → REFUSED; 500 → CRASHED. */
  static WriteOutcome unifiedWriteOutcome(final String schedulingStatus, final String http) {
    if ("Success".equals(schedulingStatus)) {
      return WriteOutcome.BOOKED;
    }
    return "500".equals(http) ? WriteOutcome.CRASHED : WriteOutcome.REFUSED;
  }

  /**
   * Old side (candidates path): a resource is INCLUDED iff it appears in at least one candidate's {@code
   * resources[]}. The getAppointmentCandidates read prunes an account-Excluded ServiceResource, so an
   * excluded resource comes back {@code "0"} (present-flag) → EXCLUDED. Used by 1a where the rule is
   * account ResourcePreference(Excluded), enforced only on the candidates read path.
   */
  static ReadDecision oldCandidatesReadDecision(final String resourcePresentFlag) {
    return "1".equals(resourcePresentFlag) ? ReadDecision.INCLUDED : ReadDecision.EXCLUDED;
  }

  /**
   * Unified side (Decision 1.4), normalizing from the error ENVELOPE rather than HTTP: the 264 violating
   * write returns a top-level {@code [{errorCode:"INTERNAL_SERVER_ERROR", ...}]} (a serviceTerritoryMembers
   * NPE) → CRASHED; a schedulingStatus=="Success" → BOOKED; anything else → REFUSED.
   */
  static WriteOutcome unifiedWriteOutcomeFromErrorCode(
      final String schedulingStatus, final String errorCode) {
    if ("Success".equals(schedulingStatus)) {
      return WriteOutcome.BOOKED;
    }
    return "INTERNAL_SERVER_ERROR".equals(errorCode) ? WriteOutcome.CRASHED : WriteOutcome.REFUSED;
  }

  /**
   * Old-side read decision for the Required-resources (1.4) candidates path, normalizing to the write
   * vocabulary so the read↔write divergence is directly comparable: an HTTP 500 / errorCode
   * INTERNAL_SERVER_ERROR is CRASHED (would match the 264 write); a clean 0-candidate response is REFUSED;
   * a non-empty candidate list is BOOKED (offered).
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

  /** Old-side Kick: same wiring as {@link ReVomanConfigForWfs} but overlaying the SCHEDULER creds. */
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
        .off();
  }
}
