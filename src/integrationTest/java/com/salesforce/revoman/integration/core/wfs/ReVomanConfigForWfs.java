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

import com.salesforce.revoman.input.config.Kick;
import com.salesforce.revoman.integration.core.adapters.IDAdapter;

/**
 * Off-core ReVoman INTEGRATION test config (NOT a ReVomanFTest) for the Workforce Scheduling (WFS)
 * Decision-1 "non-required helper" scenarios. These are scripted HTTP clients that hit a remote WFS
 * org/workspace and stitch loosely-coupled V3 Postman collections via {@code ReVoman.revUp(...)};
 * they run as plain JUnit tests in the integrationTest source set (mirrors the {@code bt2bs}/{@code
 * pq} siblings).
 *
 * <p>The collections are V3 (each folder is a directory carrying {@code .resources/definition.yaml}
 * + {@code *.request.yaml}); ReVoman auto-detects V3 from the {@code templatePath} directory. The
 * shared env is the V3 {@code ws.environment.yaml} (creds blanked — the reader fills baseUrl/tokens
 * for their own workspace).
 *
 * <p>Decision-1 unit-under-test: whether a NON-required "helper" resource (a 2nd AssignedResource
 * with {@code isRequiredResource=false}, riding along with a required+primary anchor) is validated
 * on the Schedule write path.
 *
 * <p>--------------------------------------- ENV / WORKSPACE SETUP (cannot be done over REST)
 * ------
 *
 * <ul>
 *   <li>Org pref {@code WorkforceSchdMulResSchdPref} + InBusinessScheduling enabled (multi-resource
 *       scheduling: a helper = 2 AssignedResources, exactly one with {@code
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
 * Because those settings are pre-provisioned on the workspace, the E2E tests are excluded from
 * aggregate runs (`gradle clean build`) by the {@code integration.core.*} test filter; invoke them
 * on-demand with {@code -PincludeCoreIT}.
 */
public final class ReVomanConfigForWfs {

  private ReVomanConfigForWfs() {}

  static final String V3_WFS_PATH = "pm-templates/v3/core/wfs/";
  static final String ENV_PATH = V3_WFS_PATH + "ws.environment.yaml";
  static final String NODE_MODULE_RELATIVE_PATH = "js";
  static final String IGNORE_HTTP_STATUS_UNSUCCESSFUL = "ignoreHTTPStatusUnsuccessful";

  // ## Persona creation and setup (login as admin, discover version, query profile + WFS perm sets,
  // create the case-worker [resourceA anchor] and manager [resourceB helper] users). V3 `auth`
  // folder.
  static final Kick AUTH_CONFIG = kickFor(V3_WFS_PATH + "auth");

  // ## Policies (each carries an Availability(C)+ShiftUsage(Union) rule plus the rule under test).
  static final Kick AVAILABILITY_OP_HOURS_POLICY_CONFIG =
      kickFor(V3_WFS_PATH + "policies/availability-op-hours-policy");
  static final Kick EXCLUDED_RESOURCES_POLICY_CONFIG =
      kickFor(V3_WFS_PATH + "policies/excluded-resources-availability-policy");
  static final Kick TERRITORY_PARTIAL_POLICY_CONFIG =
      kickFor(V3_WFS_PATH + "policies/territory-membership-partial-policy");
  static final Kick MATCH_SKILLS_POLICY_CONFIG =
      kickFor(V3_WFS_PATH + "policies/match-skills-non-required-policy");

  // ## Decision 1.4 — required-resource demand (account ResourcePreference) cannot be satisfied by a
  // NON-required helper.
  static final Kick REQUIRED_RESOURCES_POLICY_CONFIG =
      kickFor(V3_WFS_PATH + "policies/required-resources-availability-policy");

  // ## Fixtures (composite/graph data graphs: territory/OH/WorkType/resources/account/shifts).
  static final Kick DOUBLE_BOOK_FIXTURE_CONFIG =
      kickFor(V3_WFS_PATH + "fixtures/double-book-non-required");
  static final Kick EXCLUDED_FIXTURE_CONFIG =
      kickFor(V3_WFS_PATH + "fixtures/excluded-non-required");
  static final Kick TERRITORY_FIXTURE_CONFIG =
      kickFor(V3_WFS_PATH + "fixtures/territory-non-required");
  static final Kick SKILLS_FIXTURE_CONFIG = kickFor(V3_WFS_PATH + "fixtures/skills-non-required");
  static final Kick SKILLS_SKILL_FIXTURE_CONFIG =
      kickFor(V3_WFS_PATH + "fixtures/skills-non-required-skill");
  static final Kick WORKING_LOCATIONS_FIXTURE_CONFIG =
      kickFor(V3_WFS_PATH + "fixtures/working-locations-secondary-non-required");
  static final Kick REQUIRED_NON_REQUIRED_FIXTURE_CONFIG =
      kickFor(V3_WFS_PATH + "fixtures/required-non-required");

  // ## Schedule act-steps (the Schedule write call whose response carries the verdict; each
  // captures
  // its schedulingStatus into the env for the JUnit Truth assertion).
  static final Kick DOUBLE_BOOK_NON_REQUIRED_SCHEDULE_CONFIG =
      kickFor(V3_WFS_PATH + "booking/schedule-double-book-non-required-violating");
  static final Kick DOUBLE_BOOK_REQUIRED_CONFLICT_SCHEDULE_CONFIG =
      kickFor(V3_WFS_PATH + "booking/schedule-double-book-required-conflict");
  static final Kick EXCLUDED_SCHEDULE_CONFIG =
      kickFor(V3_WFS_PATH + "booking/schedule-excluded-non-required-violating");
  static final Kick TERRITORY_SCHEDULE_CONFIG =
      kickFor(V3_WFS_PATH + "booking/schedule-territory-membership-non-required-violating");
  static final Kick SKILLS_SCHEDULE_CONFIG =
      kickFor(V3_WFS_PATH + "booking/schedule-skills-non-required-violating");
  static final Kick WORKING_LOCATIONS_SCHEDULE_CONFIG =
      kickFor(V3_WFS_PATH + "booking/schedule-working-locations-secondary-non-required-violating");
  static final Kick REQUIRED_NON_REQUIRED_SATISFIER_VIOLATING_SCHEDULE_CONFIG =
      kickFor(V3_WFS_PATH + "booking/schedule-required-non-required-satisfier-violating");
  static final Kick REQUIRED_SATISFIER_BOOKABLE_SCHEDULE_CONFIG =
      kickFor(V3_WFS_PATH + "booking/schedule-required-satisfier-bookable");

  // ## Decision 3 — missing isRequiredResource flag (crash characterization) + the L142 control.
  static final Kick MISSING_REQUIRED_FLAG_SCHEDULE_CONFIG =
      kickFor(V3_WFS_PATH + "booking/schedule-missing-required-flag");
  static final Kick SINGLE_REQUIRED_NO_PRIMARY_SCHEDULE_CONFIG =
      kickFor(V3_WFS_PATH + "booking/schedule-single-required-no-primary");

  // ## Decision 8 — resourceLimitApptDistribution cap on the load-balancing read path (read-only). The
  // workspace default OnSite policy carries the seeded DefaultOnSiteSchdPlcy_LoadBalancing objective, so
  // the read acts omit schedulingPolicyName and rely on the default policy.
  static final Kick GET_RESOURCES_LIMIT_ZERO_CONFIG =
      kickFor(V3_WFS_PATH + "booking/get-available-resources-limit-zero");
  static final Kick GET_RESOURCES_LIMIT_POSITIVE_CONFIG =
      kickFor(V3_WFS_PATH + "booking/get-available-resources-limit-positive");

  // ## Decision 9 — Shift sharing-mode split (user-mode SystemMode.NONE shift read vs SFDC_FULL siblings).
  // adminToken mints two real personas (manager + case-worker, each their OWN SOAP session); the manager
  // creates + OWNS the policy/fixture/Shift rows; the case-worker (no sharing on the manager's Private
  // shifts) is the sharing-deprived reader. Assigning WorkforceSchedulingManager/Resource auto-grants the
  // WorkforceSchedulingPsl seat. The resource-owner User is admin-created (the manager cannot set ProfileId).
  static final Kick AUTH_PERSONAS_DEC9_CONFIG = kickFor(V3_WFS_PATH + "auth-personas-dec9");
  static final Kick SHARING_SPLIT_POLICY_CONFIG =
      kickFor(V3_WFS_PATH + "policies/sharing-split-shifts-policy");
  static final Kick SHARING_SPLIT_FIXTURE_CONFIG =
      kickFor(V3_WFS_PATH + "fixtures/sharing-split-overlapping-shifts");
  static final Kick GET_SLOTS_SHARING_SPLIT_AS_MANAGER_CONFIG =
      kickFor(V3_WFS_PATH + "booking/get-slots-sharing-split-as-manager");
  static final Kick GET_SLOTS_SHARING_SPLIT_AS_CASEWORKER_CONFIG =
      kickFor(V3_WFS_PATH + "booking/get-slots-sharing-split-as-caseworker");

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
