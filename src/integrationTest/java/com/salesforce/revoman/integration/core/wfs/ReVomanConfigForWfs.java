/*
 * Copyright 2025 salesforce.com, inc.
 * All Rights Reserved
 * Company Confidential
 */

package com.salesforce.revoman.integration.core.wfs;

import static com.salesforce.revoman.input.config.StepPick.PostTxnStepPick.afterStepContainingHeader;
import static com.salesforce.revoman.integration.core.CoreUtils.ASSERT_COMPOSITE_GRAPH_RESPONSE_SUCCESS;
import static com.salesforce.revoman.integration.core.CoreUtils.ASSERT_COMPOSITE_RESPONSE_SUCCESS;
import static com.salesforce.revoman.integration.core.CoreUtils.unmarshallCompositeGraphResponse;
import static com.salesforce.revoman.integration.core.CoreUtils.unmarshallCompositeResponse;
import static com.salesforce.revoman.output.ExeType.HTTP_STATUS;

import com.salesforce.revoman.input.ExternalOrgConfig;
import com.salesforce.revoman.input.config.Kick;
import com.salesforce.revoman.integration.core.adapters.IDAdapter;
import com.salesforce.revoman.output.log.LogLevel;
import com.salesforce.revoman.output.log.RunLogSink;
import com.salesforce.revoman.output.log.StepEvent;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;

/**
 * Off-core ReVoman INTEGRATION test config (NOT a ReVomanFTest) for the Workforce Scheduling (WFS)
 * read↔write parity scenarios. These are scripted HTTP clients that hit a remote WFS org/workspace
 * and stitch loosely-coupled V3 Postman collections via {@code ReVoman.revUp(...)}; they run as
 * plain JUnit tests in the integrationTest source set (mirrors the {@code bt2bs}/{@code pq}
 * siblings).
 *
 * <p>The collections are V3 (each folder is a directory carrying {@code .resources/definition.yaml}
 * + {@code *.request.yaml}); ReVoman auto-detects V3 from the {@code templatePath} directory. The
 * shared env is the V3 {@code ws.environment.yaml} (creds blanked — the reader fills baseUrl/tokens
 * for their own workspace).
 *
 * <p>Decisions under test (each characterizes live 262):
 *
 * <ul>
 *   <li><b>1</b> — a non-required resource is NOT fitness-checked on the Schedule write path (4
 *       dims: excluded / territory / skills / working-locations). {@code
 *       WfsWritePathParityE2ETest}.
 *   <li><b>1.4</b> — a NON-required resource cannot satisfy an account's required-resource demand
 *       (262 CRASHES with a serviceTerritoryMembers NPE rather than a clean RequiredResources
 *       error).
 *   <li><b>1.5</b> — a NON-required resource is not availability-checked, so it may double-book.
 *   <li><b>3</b> — a missing {@code isRequiredResource} flag (262 CRASHES) + the doc-L142 control
 *       (single {@code isRequiredResource=true}, no {@code isPrimaryResource}, must be a valid
 *       Schedule).
 *   <li><b>4</b> — two {@code isPrimaryResource=true} resources → clean input-validation reject
 *       ({@code INVALID_INPUT} / HTTP 400, "only one ... primary resource"), before availability.
 *   <li><b>5</b> — a primary resource marked NOT required is REJECTED at persist (no auto-correct,
 *       no double-book): probe rejected, required-primary control Success.
 *   <li><b>4z</b> — there is NO "reschedule must keep a primary" rule (refutes the doc): Arm A
 *       (isPrimaryResource on a Delete entry) → INVALID_INPUT payload-field guard; Arm B (delete
 *       primary, no flag) is allowed by the primary-count validator (zero primaries OK) — on 262 it
 *       is rejected only by a downstream availability re-check (SlotNotAvailable), never by a
 *       no-primary rule.
 *   <li><b>8</b> — {@code resourceLimitApptDistribution} caps the load-balancing read list ({@code
 *       0} → empty). {@code WfsReadPathParityE2ETest}.
 *   <li><b>9</b> — the Shift availability read runs in user mode ({@code SystemMode.NONE}) while
 *       sibling reads run {@code SFDC_FULL}: a caller lacking sharing on a resource's Shift rows
 *       silently gets no slots (proven via a manager-owns-shifts / case-worker-reads cross-persona
 *       repro).
 *   <li><b>2</b> — a shown slot is a PROMISE on the cheap checks the read and write paths share
 *       (characterized with availability: read offers slots ⟺ write Succeeds; read 0 ⟺ write
 *       rejected).
 * </ul>
 *
 * <p>--------------------------------------- ENV / WORKSPACE SETUP (cannot be done over REST)
 * ------
 *
 * <ul>
 *   <li>Org pref {@code WorkforceSchdMulResSchdPref} + InBusinessScheduling enabled (multi-resource
 *       scheduling: a non-required resource = 2 AssignedResources, exactly one with {@code
 *       isPrimaryResource=true}).
 *   <li>{@code Shift.Status} DynEnum pre-seeded so {@code Status:"Confirmed"} shifts validate
 *       (W-22901809).
 *   <li>Each resource needs a Confirmed Shift covering the window AND a ServiceTerritoryMember
 *       effective in the past.
 *   <li>Release contract targets a 262 org: {@code SchedulingMethod="OnSite"} everywhere
 *       (TimeSlot/Shift + schedule body); WorkType has NO SchedulingMethod/IsRegular; the schedule
 *       body has NO referenceId; composite/graph node URLs are RELATIVE. {@code versionPath}
 *       (v67.0) comes from the env.
 *   <li>Each Availability rule (SchedulingRuleType=C) now carries an explicit {@code ShiftUsage}
 *       SchedulingRuleParameter ({@code Value="ConsiderOpHoursAndShiftsUnion"}) baked into the
 *       policy folders — without it slot-gen returns ZERO slots (there is no default).
 * </ul>
 *
 * <p>Those settings are pre-provisioned on the workspace, so these E2E tests are excluded from
 * aggregate runs (`gradle clean build`) by the {@code integration.core.*} test filter; invoke them
 * on-demand with {@code -PincludeCoreIT}. When run, they execute LIVE against the provisioned WFS
 * workspace org via {@code ~/.revoman/config.yaml} external-org creds (baseUrl / username /
 * password). They carry no {@code @Disabled} annotation — they are skipped only when those creds
 * are absent.
 */
public final class ReVomanConfigForWfs {

  private ReVomanConfigForWfs() {}

  /**
   * External-org creds overlaid onto every {@link #kickFor} Kick's {@code dynamicEnvironment}
   * (which {@code Environment.mergeEnvs} applies LAST, so it overrides the blank {@code
   * baseUrl}/{@code username}/{@code password} in the committed {@code ws.environment.yaml}). Read
   * once from {@code ~/.revoman/config.yaml}; absent file → empty overlay (tests then skip via
   * {@link #assumeExternalOrgCreds}). NEVER commit that file — it holds org creds and lives in
   * {@code $HOME}.
   */
  static final Map<String, Object> EXTERNAL_ORG_CONFIG = ExternalOrgConfig.readExternalOrgConfig();

  /**
   * Skip (JUnit assumption — NOT fail) when the external-org creds are absent or blank, honoring
   * this class's contract that the WFS tests "are skipped only when those creds are absent". Set
   * {@code ~/.revoman/config.yaml} (baseUrl / username / password) to run them live.
   */
  static void assumeExternalOrgCreds() {
    Assumptions.assumeTrue(
        hasText("baseUrl") && hasText("username") && hasText("password"),
        "WFS external-org creds absent — set ~/.revoman/config.yaml (baseUrl/username/password)."
            + " Skipping.");
  }

  private static boolean hasText(final String key) {
    final Object value = EXTERNAL_ORG_CONFIG.get(key);
    return value != null && !value.toString().isBlank();
  }

  static final String V3_WFS_PATH = "pm-templates/v3/core/wfs/";
  static final String ENV_PATH = V3_WFS_PATH + "ws.environment.yaml";
  static final String NODE_MODULE_RELATIVE_PATH = "js";
  static final String IGNORE_HTTP_STATUS_UNSUCCESSFUL = "ignoreHTTPStatusUnsuccessful";

  // ## Print sink — tees each step's full HTTP request/response (wire text, JSON body
  // pretty-printed)
  // to stdout so the exchange shows up in the JUnit/Gradle log (showStandardStreams = true). Only a
  // non-NoOp sink makes ReVoman render the bodies (ReVoman.emitStepFinished skips rendering when no
  // sink is installed), so wiring this into every kickFor Kick is what surfaces req/resp per step.
  static final RunLogSink PRINT_SINK =
      new RunLogSink() {
        @Override
        public void line(final LogLevel level, final String message) {
          // The step trace is already emitted via KotlinLogging; nothing extra to print here.
        }

        @Override
        public void event(final StepEvent event) {
          if (event instanceof final StepEvent.StepFinished finished) {
            System.out.println(
                "── STEP "
                    + finished.getPath()
                    + " ["
                    + finished.getHttpStatus()
                    + "] "
                    + finished.getOutcome());
            if (finished.getRequestMsg() != null) {
              System.out.println("REQ:\n" + finished.getRequestMsg());
            }
            if (finished.getResponseMsg() != null) {
              System.out.println("RESP:\n" + finished.getResponseMsg());
            }
          }
        }

        @Override
        public void close() {
          // ReVoman never calls this — the caller owns the sink's lifecycle across revUp runs.
        }
      };

  // ## Persona creation and setup. Admin SOAP-logs-in (adminToken/accessToken) ONLY for admin-only
  // setup
  // (create the case-worker [resourceA owner] + manager [resourceB owner] Users — needs Manage
  // Users —
  // and the setup-object Skill). The MANAGER is then minted as a REAL least-privilege session: set
  // its
  // password → SOAP-login (v64) → its OWN {{managerToken}}. Every policy/fixture/act folder for
  // Decisions 1/1.4/1.5/3/8 runs under {{managerToken}}, so the manager OWNS the policy + fixture
  // rows it
  // later books/reads against (no admin-token alias; the API-under-test runs as the manager,
  // matching
  // Decision 9). V3 `auth` folder.
  // * NOTE 2026-07-03 gopal.akshintala: widened to public so the cross-package Scheduler-vs-Unified
  // * parity slice (…core.scheduler.SchedulerVsUnifiedParityE2ETest) can reuse the 264 double-book
  // * Kicks unchanged; wiring/behavior untouched.
  public static final Kick AUTH_CONFIG = kickFor(V3_WFS_PATH + "auth");

  // ## Policies (each carries an Availability(C)+ShiftUsage(Union) rule plus the rule under test).
  public static final Kick AVAILABILITY_OP_HOURS_POLICY_CONFIG =
      kickFor(V3_WFS_PATH + "policies/availability-op-hours-policy");
  // * NOTE 2026-07-03 gopal.akshintala: widened to public for cross-package Scheduler-vs-Unified
  // parity reuse; visibility only.
  public static final Kick EXCLUDED_RESOURCES_POLICY_CONFIG =
      kickFor(V3_WFS_PATH + "policies/excluded-resources-availability-policy");
  // * NOTE 2026-07-03 gopal.akshintala: widened to public for cross-package Scheduler-vs-Unified
  // parity reuse; visibility only.
  public static final Kick TERRITORY_PARTIAL_POLICY_CONFIG =
      kickFor(V3_WFS_PATH + "policies/territory-membership-partial-policy");
  // * NOTE 2026-07-03 gopal.akshintala: widened to public for cross-package Scheduler-vs-Unified
  // parity reuse; visibility only.
  public static final Kick MATCH_SKILLS_POLICY_CONFIG =
      kickFor(V3_WFS_PATH + "policies/match-skills-non-required-policy");
  static final Kick VISITING_HOURS_POLICY_CONFIG =
      kickFor(V3_WFS_PATH + "policies/visiting-hours-op-hours-policy");

  // ## Decision 1.4 — required-resource demand (account ResourcePreference) cannot be satisfied by
  // a
  // NON-required resource.
  // * NOTE 2026-07-03 gopal.akshintala: widened to public for cross-package Scheduler-vs-Unified
  // parity reuse; visibility only.
  public static final Kick REQUIRED_RESOURCES_POLICY_CONFIG =
      kickFor(V3_WFS_PATH + "policies/required-resources-availability-policy");

  // ## Fixtures (composite/graph data graphs: territory/OH/WorkType/resources/account/shifts).
  public static final Kick DOUBLE_BOOK_FIXTURE_CONFIG =
      kickFor(V3_WFS_PATH + "fixtures/double-book-non-required");
  // * NOTE 2026-07-03 gopal.akshintala: widened to public for cross-package Scheduler-vs-Unified
  // parity reuse; visibility only.
  public static final Kick EXCLUDED_FIXTURE_CONFIG =
      kickFor(V3_WFS_PATH + "fixtures/excluded-non-required");
  // * NOTE 2026-07-03 gopal.akshintala: widened to public for cross-package Scheduler-vs-Unified
  // parity reuse; visibility only.
  public static final Kick TERRITORY_FIXTURE_CONFIG =
      kickFor(V3_WFS_PATH + "fixtures/territory-non-required");
  // * NOTE 2026-07-03 gopal.akshintala: widened to public for cross-package Scheduler-vs-Unified
  // parity reuse; visibility only.
  public static final Kick SKILLS_FIXTURE_CONFIG =
      kickFor(V3_WFS_PATH + "fixtures/skills-non-required");
  // * NOTE 2026-07-03 gopal.akshintala: widened to public for cross-package Scheduler-vs-Unified
  // parity reuse; visibility only.
  public static final Kick SKILLS_SKILL_FIXTURE_CONFIG =
      kickFor(V3_WFS_PATH + "fixtures/skills-non-required-skill");
  // * NOTE 2026-07-03 gopal.akshintala: widened to public for cross-package Scheduler-vs-Unified
  // parity reuse; visibility only.
  public static final Kick WORKING_LOCATIONS_FIXTURE_CONFIG =
      kickFor(V3_WFS_PATH + "fixtures/working-locations-secondary-non-required");
  // * NOTE 2026-07-03 gopal.akshintala: widened to public for cross-package Scheduler-vs-Unified
  // parity reuse; visibility only.
  public static final Kick REQUIRED_NON_REQUIRED_FIXTURE_CONFIG =
      kickFor(V3_WFS_PATH + "fixtures/required-non-required");
  static final Kick VISITING_HOURS_FIXTURE_CONFIG =
      kickFor(V3_WFS_PATH + "fixtures/visiting-hours-account-oh");

  // ## Schedule act-steps (the Schedule write call whose response carries the verdict; each
  // captures
  // its schedulingStatus into the env for the JUnit Truth assertion).
  public static final Kick DOUBLE_BOOK_NON_REQUIRED_SCHEDULE_CONFIG =
      kickFor(V3_WFS_PATH + "booking/schedule-double-book-non-required-violating");
  public static final Kick DOUBLE_BOOK_REQUIRED_CONFLICT_SCHEDULE_CONFIG =
      kickFor(V3_WFS_PATH + "booking/schedule-double-book-required-conflict");

  // ## Prior-assignment occupancy parity (Unified side). 3-resource graph, B available, 2 accounts;
  // appt #1 seeds B's assignment, appt #2 re-books B on an overlapping window with C as a free
  // primary.
  // The fixture folder holds TWO requests under ONE Kick: 05-create-resource-c-user (per-request
  // auth override to {{adminToken}} — manager persona lacks Manage Users) then the manager-token
  // graph.
  public static final Kick PRIOR_ASSIGNMENT_FIXTURE_CONFIG =
      kickFor(V3_WFS_PATH + "fixtures/prior-assignment");
  public static final Kick PRIOR_APPT1_B_REQUIRED_CONFIG =
      kickFor(V3_WFS_PATH + "booking/schedule-prior-appt1-b-required");
  public static final Kick PRIOR_APPT1_B_OPTIONAL_CONFIG =
      kickFor(V3_WFS_PATH + "booking/schedule-prior-appt1-b-optional");
  public static final Kick PRIOR_APPT2_B_REQUIRED_CONFIG =
      kickFor(V3_WFS_PATH + "booking/schedule-prior-appt2-b-required");
  public static final Kick PRIOR_APPT2_B_OPTIONAL_CONFIG =
      kickFor(V3_WFS_PATH + "booking/schedule-prior-appt2-b-optional");
  public static final Kick PRIOR_CANDIDATES_APPT2_CONFIG =
      kickFor(V3_WFS_PATH + "booking/get-candidates-prior-appt2-b-required");

  // * NOTE 2026-07-03 gopal.akshintala: widened to public for cross-package Scheduler-vs-Unified
  // parity reuse; visibility only.
  public static final Kick EXCLUDED_SCHEDULE_CONFIG =
      kickFor(V3_WFS_PATH + "booking/schedule-excluded-non-required-violating");
  // * NOTE 2026-07-03 gopal.akshintala: widened to public for cross-package Scheduler-vs-Unified
  // parity reuse; visibility only.
  public static final Kick TERRITORY_SCHEDULE_CONFIG =
      kickFor(V3_WFS_PATH + "booking/schedule-territory-membership-non-required-violating");
  // * NOTE 2026-07-03 gopal.akshintala: widened to public for cross-package Scheduler-vs-Unified
  // parity reuse; visibility only.
  public static final Kick SKILLS_SCHEDULE_CONFIG =
      kickFor(V3_WFS_PATH + "booking/schedule-skills-non-required-violating");
  // * NOTE 2026-07-03 gopal.akshintala: widened to public for cross-package Scheduler-vs-Unified
  // parity reuse; visibility only.
  public static final Kick WORKING_LOCATIONS_SCHEDULE_CONFIG =
      kickFor(V3_WFS_PATH + "booking/schedule-working-locations-secondary-non-required-violating");
  // * NOTE 2026-07-03 gopal.akshintala: widened to public for cross-package Scheduler-vs-Unified
  // parity reuse; visibility only.
  public static final Kick REQUIRED_NON_REQUIRED_SATISFIER_VIOLATING_SCHEDULE_CONFIG =
      kickFor(V3_WFS_PATH + "booking/schedule-required-non-required-satisfier-violating");
  // * NOTE 2026-07-03 gopal.akshintala: widened to public for cross-package Scheduler-vs-Unified
  // parity reuse; visibility only.
  public static final Kick REQUIRED_SATISFIER_BOOKABLE_SCHEDULE_CONFIG =
      kickFor(V3_WFS_PATH + "booking/schedule-required-satisfier-bookable");

  // ## Decision 3 — missing isRequiredResource flag (crash characterization) + the L142 control.
  // * NOTE 2026-07-03 gopal.akshintala: widened to public for cross-package Scheduler-vs-Unified
  // parity reuse; visibility only.
  public static final Kick MISSING_REQUIRED_FLAG_SCHEDULE_CONFIG =
      kickFor(V3_WFS_PATH + "booking/schedule-missing-required-flag");
  // * NOTE 2026-07-03 gopal.akshintala: widened to public for cross-package Scheduler-vs-Unified
  // parity reuse; visibility only.
  public static final Kick SINGLE_REQUIRED_NO_PRIMARY_SCHEDULE_CONFIG =
      kickFor(V3_WFS_PATH + "booking/schedule-single-required-no-primary");

  // ## Decision 4 — two primary resources → clean input-validation reject (INVALID_INPUT / HTTP
  // 400,
  // "Only one of the provided assigned resource can be a primary resource"). Caught up front in
  // ScheduleCommonValidator.validatePrimaryResourceConstraints, before availability/persist.
  // * NOTE 2026-07-03 gopal.akshintala: widened to public for cross-package Scheduler-vs-Unified
  // parity reuse; visibility only.
  public static final Kick SCHEDULE_TWO_PRIMARY_CONFIG =
      kickFor(V3_WFS_PATH + "booking/schedule-two-primary");

  // ## Decision 5 — a primary resource marked NOT required is REJECTED at persist (no auto-correct,
  // no
  // double-book). Probe: single isPrimaryResource=true, isRequiredResource=false → persist
  // INVALID_FIELD.
  // Control: flip isRequiredResource=true → Success (isolates the reject to the not-required flag).
  // * NOTE 2026-07-03 gopal.akshintala: widened to public for cross-package Scheduler-vs-Unified
  // parity reuse; visibility only.
  public static final Kick SCHEDULE_PRIMARY_NOT_REQUIRED_CONFIG =
      kickFor(V3_WFS_PATH + "booking/schedule-primary-not-required");
  // * NOTE 2026-07-03 gopal.akshintala: widened to public for cross-package Scheduler-vs-Unified
  // parity reuse; visibility only.
  public static final Kick SCHEDULE_PRIMARY_REQUIRED_CONTROL_CONFIG =
      kickFor(V3_WFS_PATH + "booking/schedule-primary-required-control");

  // ## Decision 4z — there is NO "reschedule must keep a primary" rule (the product doc's blanket
  // "not
  // possible" is WRONG). (Arm A) isPrimaryResource on a DeleteOperation entry → INVALID_INPUT
  // "isPrimaryResource cannot be set for delete." (a payload-field guard in
  // validateDeleteOperationFields).
  // (Arm B) deleting the primary WITHOUT the flag → the primary-count validator allows zero
  // primaries
  // (validatePrimaryResourceCount only throws MultiplePrimary when primaryCount > 1), so no
  // no-primary
  // rejection fires. LIVE-OBSERVED on the 262 org: Arm B is rejected ONLY by the downstream
  // availability
  // re-check (INVALID_INPUT "The service resources are not available for the requested slot." /
  // SlotNotAvailable) — never by a no-primary rule. Characterized faithfully
  // (availability, not no-primary). Clean two-resource schedule sets up reschedCleanSaId for both
  // arms.
  // * NOTE 2026-07-03 gopal.akshintala: widened to public (visibility only, no wiring change) so
  // the
  // * Scheduler↔Unified Decision-4z parity test (SchedulerVsUnifiedParityE2ETest
  // * .testRescheduleNoPrimaryParity_4z_E2E) can reuse the proven 264 reschedule-no-primary acts.
  public static final Kick SCHEDULE_TWO_RESOURCE_CLEAN_CONFIG =
      kickFor(V3_WFS_PATH + "booking/schedule-two-resource-clean");
  // * NOTE 2026-07-03 gopal.akshintala: widened to public for cross-package Scheduler-vs-Unified
  // parity reuse; visibility only.
  public static final Kick RESCHEDULE_DELETE_PRIMARY_WITH_FLAG_CONFIG =
      kickFor(V3_WFS_PATH + "booking/reschedule-delete-primary-with-flag");
  // * NOTE 2026-07-03 gopal.akshintala: widened to public for cross-package Scheduler-vs-Unified
  // parity reuse; visibility only.
  public static final Kick RESCHEDULE_DELETE_PRIMARY_NO_FLAG_CONFIG =
      kickFor(V3_WFS_PATH + "booking/reschedule-delete-primary-no-flag");

  // ## Decision 4z delete-ALL probe — deletes BOTH assigned resources (no isPrimaryResource flag on
  // either delete entry). The brief HYPOTHESIZED an empty-crew Success (extractServiceResourceIds
  // skips DeleteOperation entries → empty required set → availability re-check passes), mirroring
  // the
  // Core func test testRescheduleAppointmentDeleteAllAssignedResources (asserts Success).
  // LIVE-OBSERVED on this 262 org the probe is REJECTED with INVALID_INPUT / "The service resources
  // are not available for the requested slot." (SlotNotAvailable) — the SAME downstream
  // availability
  // re-check that blocks the delete-primary Arm B; on 262 the availability check is not bypassed
  // even for delete-ALL.
  // Characterized faithfully as the OBSERVED rejection, not the hypothesized Success.
  static final Kick RESCHEDULE_DELETE_ALL_CONFIG =
      kickFor(V3_WFS_PATH + "booking/reschedule-delete-all");

  // ## Decision 4z delete-ALL WITH an explicit schedulingPolicyId — the reconciling twin of
  // RESCHEDULE_DELETE_ALL. IDENTICAL delete-BOTH body (no times), differing in ONE field: it passes
  // schedulingPolicyId={{availabilityOpHoursPolicyId}} (the well-formed Availability+ShiftUsage
  // policy the
  // clean Schedule used). The sibling RESCHEDULE_DELETE_ALL omits the policy, so the availability
  // re-check
  // falls back to the org DEFAULT OnSite policy (SlotAvailabilityChecker.buildGetSlotsRequest
  // passes the
  // request policyId through; a null policyId resolves via getDefaultOnSiteSchedulingPolicy). On
  // this org
  // the default policy's Availability rule lacks a usable ShiftUsage parameter → zero slots →
  // SlotNotAvailable. Supplying the good policy makes the empty-crew delete-all SUCCEED on 262,
  // matching the
  // Core func test testRescheduleAppointmentDeleteAllAssignedResources — isolating the resolved
  // scheduling
  // policy (explicit vs org-default) as the single variable that reconciles the two tests.
  static final Kick RESCHEDULE_DELETE_ALL_WITH_POLICY_CONFIG =
      kickFor(V3_WFS_PATH + "booking/reschedule-delete-all-with-policy");

  // ## Decision 4z DEMOTE probe — the under-guarded "leave TWO-or-more workers with NO primary"
  // shape (the sibling delete arms only shrink the crew to 1 or 0). Reschedules the SAME clean
  // two-resource SA (reschedCleanSaId) with NO startTime/endTime and TWO UpdateOperation entries:
  // resourceA flipped to isPrimaryResource=false (still isRequiredResource=true) + resourceB
  // re-stated non-primary+required → EFFECTIVE crew = 2 workers, 0 primaries.
  // validatePrimaryResourceCount rejects only primaryCount > 1 (no NoPrimary check), so validation
  // ALLOWS zero primaries → NOT blocked at validation. Characterizes whether 262 (a) CRASHES on the
  // 15/18-char record-id resourcesHaveChanged mismatch (reschedule-recompute NPE), (b) is BLOCKED
  // by
  // the downstream availability re-check (SlotNotAvailable), or (c) SUCCEEDS and persists a
  // 2-worker
  // no-primary crew. Asserted as OBSERVED. Runs AFTER the clean two-resource setup.
  static final Kick RESCHEDULE_DEMOTE_PRIMARY_TWO_CREW_CONFIG =
      kickFor(V3_WFS_PATH + "booking/reschedule-demote-primary-two-crew");

  // ## Decision 8 — resourceLimitApptDistribution cap on the load-balancing read path (read-only).
  // The
  // workspace default OnSite policy carries the seeded DefaultOnSiteSchdPlcy_LoadBalancing
  // objective, so
  // the read acts omit schedulingPolicyName and rely on the default policy.
  // * NOTE 2026-07-03 gopal.akshintala: widened to public for cross-package Scheduler-vs-Unified
  // parity reuse; visibility only.
  public static final Kick GET_RESOURCES_LIMIT_ZERO_CONFIG =
      kickFor(V3_WFS_PATH + "booking/get-available-resources-limit-zero");
  // * NOTE 2026-07-03 gopal.akshintala: widened to public for cross-package Scheduler-vs-Unified
  // parity reuse; visibility only.
  public static final Kick GET_RESOURCES_LIMIT_POSITIVE_CONFIG =
      kickFor(V3_WFS_PATH + "booking/get-available-resources-limit-positive");

  // ## Decision 9 — Shift sharing-mode split (user-mode SystemMode.NONE shift read vs SFDC_FULL
  // siblings).
  // adminToken mints two real personas (manager + case-worker, each their OWN SOAP session); the
  // manager
  // creates + OWNS the policy/fixture/Shift rows; the case-worker (no sharing on the manager's
  // Private
  // shifts) is the sharing-deprived reader. Assigning WorkforceSchedulingManager/Resource
  // auto-grants the
  // WorkforceSchedulingPsl seat. The resource-owner User is admin-created (the manager cannot set
  // ProfileId).
  // * NOTE 2026-07-03 gopal.akshintala: visibility widened package-private → public so the
  // * Scheduler↔Unified Decision-9 parity test (SchedulerVsUnifiedParityE2ETest) can reuse the 264
  // side
  // * of the sharing-mode-split scenario. Visibility-only change; wiring unchanged.
  public static final Kick AUTH_PERSONAS_DEC9_CONFIG = kickFor(V3_WFS_PATH + "auth-personas-dec9");
  // * NOTE 2026-07-03 gopal.akshintala: widened to public for cross-package Scheduler-vs-Unified
  // parity reuse; visibility only.
  public static final Kick SHARING_SPLIT_POLICY_CONFIG =
      kickFor(V3_WFS_PATH + "policies/sharing-split-shifts-policy");
  // * NOTE 2026-07-03 gopal.akshintala: widened to public for cross-package Scheduler-vs-Unified
  // parity reuse; visibility only.
  public static final Kick SHARING_SPLIT_FIXTURE_CONFIG =
      kickFor(V3_WFS_PATH + "fixtures/sharing-split-overlapping-shifts");
  // * NOTE 2026-07-03 gopal.akshintala: widened to public for cross-package Scheduler-vs-Unified
  // parity reuse; visibility only.
  public static final Kick GET_SLOTS_SHARING_SPLIT_AS_MANAGER_CONFIG =
      kickFor(V3_WFS_PATH + "booking/get-slots-sharing-split-as-manager");
  // * NOTE 2026-07-03 gopal.akshintala: widened to public for cross-package Scheduler-vs-Unified
  // parity reuse; visibility only.
  public static final Kick GET_SLOTS_SHARING_SPLIT_AS_CASEWORKER_CONFIG =
      kickFor(V3_WFS_PATH + "booking/get-slots-sharing-split-as-caseworker");

  // ## Decision 2 — "is a shown slot a promise?" The cheap checks
  // (skill/territory/free-busy/location/
  // excluded) are SHARED by the read and write paths, so a shown slot is a PROMISE on them.
  // Characterized
  // with availability: read offers slots in the available window ⟺ write into it Succeeds; read
  // offers 0
  // in the unavailable window ⟺ write is rejected. The field-match "shown-but-rejected" half is NOT
  // characterizable on 262 (the three field-match rules are OnField/ESO-internal; the live OnSite
  // path
  // shares read==write) — see the test javadoc + decision log.
  // * NOTE 2026-07-03 gopal.akshintala: widened to public for cross-package Scheduler-vs-Unified
  // parity reuse; visibility only.
  public static final Kick GET_SLOTS_PARITY_AVAILABLE_CONFIG =
      kickFor(V3_WFS_PATH + "booking/get-slots-parity-available");
  // * NOTE 2026-07-03 gopal.akshintala: widened to public for cross-package Scheduler-vs-Unified
  // parity reuse; visibility only.
  public static final Kick GET_SLOTS_PARITY_UNAVAILABLE_CONFIG =
      kickFor(V3_WFS_PATH + "booking/get-slots-parity-unavailable");
  // * NOTE 2026-07-03 gopal.akshintala: widened to public for cross-package Scheduler-vs-Unified
  // parity reuse; visibility only.
  public static final Kick SCHEDULE_PARITY_UNAVAILABLE_CONFIG =
      kickFor(V3_WFS_PATH + "booking/schedule-parity-unavailable");
  // * NOTE 2026-07-03 gopal.akshintala: widened to public for cross-package Scheduler-vs-Unified
  // parity reuse; visibility only.
  public static final Kick SCHEDULE_PARITY_AVAILABLE_CONFIG =
      kickFor(V3_WFS_PATH + "booking/schedule-parity-available");

  // ## Rules read==write parity — one differential matrix per Common+InBusiness rule. Each rule: a
  // violating act (rule fires → read 0 slots / write rejected) + a control act (rule passes → read
  // >0 /
  // write Success), asserting read decision == write decision. See WfsRulesParityE2ETest.
  static final Kick GET_SLOTS_SKILLS_VIOLATING_CONFIG =
      kickFor(V3_WFS_PATH + "booking/get-slots-skills-violating");
  static final Kick GET_SLOTS_SKILLS_CONTROL_CONFIG =
      kickFor(V3_WFS_PATH + "booking/get-slots-skills-control");
  static final Kick SCHEDULE_SKILLS_VIOLATING_CONFIG =
      kickFor(V3_WFS_PATH + "booking/schedule-skills-violating");
  static final Kick SCHEDULE_SKILLS_CONTROL_CONFIG =
      kickFor(V3_WFS_PATH + "booking/schedule-skills-control");
  static final Kick GET_SLOTS_EXCLUDED_VIOLATING_CONFIG =
      kickFor(V3_WFS_PATH + "booking/get-slots-excluded-violating");
  static final Kick GET_SLOTS_EXCLUDED_CONTROL_CONFIG =
      kickFor(V3_WFS_PATH + "booking/get-slots-excluded-control");
  static final Kick SCHEDULE_EXCLUDED_VIOLATING_CONFIG =
      kickFor(V3_WFS_PATH + "booking/schedule-excluded-violating");
  static final Kick SCHEDULE_EXCLUDED_CONTROL_CONFIG =
      kickFor(V3_WFS_PATH + "booking/schedule-excluded-control");
  static final Kick GET_SLOTS_WORKLOC_VIOLATING_CONFIG =
      kickFor(V3_WFS_PATH + "booking/get-slots-workloc-violating");
  static final Kick GET_SLOTS_WORKLOC_CONTROL_CONFIG =
      kickFor(V3_WFS_PATH + "booking/get-slots-workloc-control");
  static final Kick SCHEDULE_WORKLOC_VIOLATING_CONFIG =
      kickFor(V3_WFS_PATH + "booking/schedule-workloc-violating");
  static final Kick SCHEDULE_WORKLOC_CONTROL_CONFIG =
      kickFor(V3_WFS_PATH + "booking/schedule-workloc-control");
  // Task 4 — ServiceAppointmentVisitingHours (lifted+conformed from source): a booking window
  // OUTSIDE
  // the parent Account's VisitingHours OperatingHours (10-14) is pruned by the read (0 slots) AND
  // rejected by the write; an in-visiting-hours control (11-12) returns >0 slots AND books Success.
  static final Kick GET_SLOTS_VISITING_HOURS_VIOLATING_CONFIG =
      kickFor(V3_WFS_PATH + "booking/get-slots-visiting-hours-violating");
  static final Kick GET_SLOTS_VISITING_HOURS_CONTROL_CONFIG =
      kickFor(V3_WFS_PATH + "booking/get-slots-visiting-hours-control");
  static final Kick SCHEDULE_VISITING_HOURS_VIOLATING_CONFIG =
      kickFor(V3_WFS_PATH + "booking/schedule-visiting-hours-violating");
  static final Kick SCHEDULE_VISITING_HOURS_CONTROL_CONFIG =
      kickFor(V3_WFS_PATH + "booking/schedule-visiting-hours-control");

  // Task 5 — AppointmentStartTimeInterval (the sole IN_BUSINESS_RULE_TYPES member; net-new
  // policy+fixture). The policy carries Availability(C)+ShiftUsage=Union AND
  // WorkingTerritories+IsPrimaryLocationEnabled=true (both REQUIRED for slot-gen) PLUS an
  // AppointmentStartTimeInterval rule with a 60-min interval SchedulingRuleParameter. The WorkType
  // carries no AppointmentStartTimeInterval so the POLICY value is used. A booking start OFF the
  // 60-min boundary (11:30-12:30 — the only hour-aligned start 12:00 overruns the window) is pruned
  // by the read (0 slots) AND rejected by the write; an on-boundary control (11:00-12:00) returns
  // >0
  // AND books Success.
  static final Kick START_TIME_INTERVAL_POLICY_CONFIG =
      kickFor(V3_WFS_PATH + "policies/start-time-interval-policy");
  static final Kick START_TIME_INTERVAL_FIXTURE_CONFIG =
      kickFor(V3_WFS_PATH + "fixtures/start-time-interval");
  static final Kick GET_SLOTS_STI_VIOLATING_CONFIG =
      kickFor(V3_WFS_PATH + "booking/get-slots-sti-violating");
  static final Kick GET_SLOTS_STI_CONTROL_CONFIG =
      kickFor(V3_WFS_PATH + "booking/get-slots-sti-control");
  static final Kick SCHEDULE_STI_VIOLATING_CONFIG =
      kickFor(V3_WFS_PATH + "booking/schedule-sti-violating");
  static final Kick SCHEDULE_STI_CONTROL_CONFIG =
      kickFor(V3_WFS_PATH + "booking/schedule-sti-control");

  // Task 6 — RequiredResources read side of the 262 read≠write DIVERGENCE. Over the SAME
  // required-non-required fixture + RequiredResources policy as the write violating act, the READ
  // path
  // PRUNES cleanly (0 slots) when only a NON-required resource satisfies the account's
  // required-resource
  // demand, while the WRITE path on the same scenario CRASHES with a serviceTerritoryMembers NPE
  // (D1.4,
  // characterized in
  // WfsWritePathParityE2ETest.testNonRequiredHelperCannotSatisfyRequiredDemandE2E). A
  // genuine-required-satisfier control returns >0 slots (proves the fixture is bookable). See
  // WfsRulesParityE2ETest.testRequiredResourcesReadPrunesWhileWriteCrashesE2E.
  static final Kick GET_SLOTS_REQUIRED_VIOLATING_CONFIG =
      kickFor(V3_WFS_PATH + "booking/get-slots-required-violating");
  static final Kick GET_SLOTS_REQUIRED_CONTROL_CONFIG =
      kickFor(V3_WFS_PATH + "booking/get-slots-required-control");

  // Task 7 — cross-API agreement: the SAME MatchSkills violation (a required+primary resource
  // lacking
  // the WorkType's required skill) run through the 3 not-yet-exercised read APIs. All 4 read APIs
  // (get-appointment-slots + these 3) reach the same loadSchedulableSlots engine;
  // get-available-resources
  // calls the same getCandidatesProcessor.process (AvailableResourcesServiceImpl:322) then only
  // post-processes/truncates the surviving resources. The three appointment reads request resourceB
  // via
  // assignedResources so pruning it leaves 0 slots/candidates/available-slots;
  // get-available-resources
  // takes NO assignedResources and returns EVERY available resource, so the SKILLED resourceA
  // survives
  // while the UNSKILLED resourceB is pruned/ABSENT (LIVE-VERIFIED 2026-07-01 — confirms the full
  // 7-rule
  // engine, not a subset). Matches the schedule write's rejection. Reuses the Task-1 skills fixture
  // +
  // policy and the existing GET_SLOTS_SKILLS_VIOLATING/SCHEDULE_SKILLS_VIOLATING acts. See
  // WfsRulesParityE2ETest.testCrossApiRuleAgreementE2E.
  static final Kick GET_CANDIDATES_SKILLS_VIOLATING_CONFIG =
      kickFor(V3_WFS_PATH + "booking/get-candidates-skills-violating");
  static final Kick GET_AVAILABLE_SLOTS_SKILLS_VIOLATING_CONFIG =
      kickFor(V3_WFS_PATH + "booking/get-available-slots-skills-violating");
  static final Kick GET_AVAILABLE_RESOURCES_SKILLS_VIOLATING_CONFIG =
      kickFor(V3_WFS_PATH + "booking/get-available-resources-skills-violating");

  // Task 8 — no-op reschedule short-circuit (write<read: SlotAvailabilityChecker:174-176
  // if (!timesAreChanging && !resourcesHaveChanged) return true is the ONE place the write skips
  // getSlots/loadSchedulableSlots). Setup schedules resourceA (required+primary) into an available
  // window
  // (Success, captures noopSetupSaId over the availability-op-hours policy + required-non-required
  // fixture).
  // The no-op reschedule then reschedules that SA with NO time change + an UpdateOperation
  // re-stating
  // resourceA. LIVE + jdwp-VERIFIED 2026-07-01 that the short-circuit does NOT fire for a
  // required-resource
  // SA: haveResourcesChanged compares the existing SOQL required-id set (18-char) vs the request's,
  // and the
  // request-side id (stored RAW then truncated to 15-char by the ESO request DTO) never equals the
  // 18-char
  // existing id → resourcesHaveChanged==true (empty assignedResources is equally unequal); the two
  // size-1
  // sets sat in different hash buckets at the debugger. So the short-circuit is UNREACHABLE over
  // REST for a
  // required-resource SA — the reschedule recomputes and 262 500-CRASHES
  // (ServiceTerritory.getServiceResourceIds
  // NPE, cf. Decision 1.4). This REFUTES the "no-op returns Success via the short-circuit" premise;
  // characterized faithfully. See WfsRulesParityE2ETest.testNoOpRescheduleShortCircuitE2E.
  static final Kick SCHEDULE_NOOP_RESCHED_SETUP_CONFIG =
      kickFor(V3_WFS_PATH + "booking/schedule-noop-resched-setup");
  static final Kick RESCHEDULE_NOOP_CONFIG = kickFor(V3_WFS_PATH + "booking/reschedule-noop");

  /**
   * One Kick per V3 collection folder, all sharing the same shape as the {@code bt2bs} sibling:
   * composite/graph + composite response unmarshalling/asserting, IDAdapter, the JS node-modules
   * path, insecure HTTP (remote workspace), and {@code haltOnFailureOfTypeExcept(HTTP_STATUS, ...)}
   * so steps headered {@code ignoreHTTPStatusUnsuccessful} may legitimately return a non-2xx.
   */
  private static Kick kickFor(final String templatePath) {
    return Kick.configure()
        .templatePath(templatePath)
        .environmentPath(ENV_PATH)
        .dynamicEnvironment(EXTERNAL_ORG_CONFIG)
        .responseConfig(unmarshallCompositeGraphResponse(), unmarshallCompositeResponse())
        .hooks(ASSERT_COMPOSITE_GRAPH_RESPONSE_SUCCESS, ASSERT_COMPOSITE_RESPONSE_SUCCESS)
        .globalCustomTypeAdapter(IDAdapter.INSTANCE)
        .nodeModulesPath(NODE_MODULE_RELATIVE_PATH)
        .haltOnFailureOfTypeExcept(
            HTTP_STATUS, afterStepContainingHeader(IGNORE_HTTP_STATUS_UNSUCCESSFUL))
        .insecureHttp(true)
        .runLogSink(PRINT_SINK)
        .off();
  }
}
