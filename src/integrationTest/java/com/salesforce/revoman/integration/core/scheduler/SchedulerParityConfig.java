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
