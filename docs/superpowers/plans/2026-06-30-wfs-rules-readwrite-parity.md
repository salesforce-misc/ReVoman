# WFS Rules read==write Parity Suite — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prove — with live ReVoman tests + jdwp Core double-asserts — that every one of the 7 Common+InBusiness scheduling rules evaluates identically on all rule-evaluating read APIs and the write APIs (read==write), and faithfully record the two genuine divergences (reschedule no-op short-circuit; RequiredResources 262 NPE).

**Architecture:** New `WfsRulesParityE2ETest` class (sibling to the two existing parity classes). Per rule: one chained `ReVoman.revUp(...)` over one fixture with a matched violating/control pair, asserting read decision == write decision in both rows. Plus a cross-API agreement test (one violation through all 6 rule-evaluating APIs) and a no-op-reschedule test. jdwp anchors the ambiguous cases.

**Tech Stack:** Java 21 (integrationTest), JUnit 5, Google Truth, ReVoman (this repo), V3 Postman collections (YAML), Salesforce unified-scheduling Connect API (v67 REST, v64 SOAP), live workspace org via `~/.revoman/config.yaml`, jdwp MCP for Core double-asserts.

## Global Constraints

- **No changes to `/opt/workspace/core-public/core`.** Divergences are characterized + handed off, never fixed.
- **NO YAML `#` comments in any Postman collection/env file** (`*.request.yaml`, `.resources/definition.yaml`, `*.environment.yaml`) — corrupts Postman import. Convey intent via the `description:` YAML key or `//` inside `scripts: code:` JS blocks only. Applies to `.postman/resources.yaml` too.
- **Keep `.postman/resources.yaml` updated** — append every new V3 collection folder under `localResources.collections` (`../src/integrationTest/resources/pm-templates/v3/...`) and any new env under `localResources.environments`; no comments; don't touch `cloudResources`.
- **Assert 262 behavior, document 264 contrast in each test method javadoc.** The suite is ALLOWED to discover read≠write; record it faithfully, never manufacture parity.
- **REST on dynamic `versionPath` (v67); SOAP persona login v64.**
- **Every act under assertion carries `x-revoman-ledger: "off"`.** Acts expected non-2xx carry `ignoreHTTPStatusUnsuccessful: "true"`.
- **One `revUp` per test, each starting with `AUTH_CONFIG`** → fresh manager persona → fresh `ServiceResource(RelatedRecordId, ResourceType)` (Approach A). All folders run under `{{managerToken}}`.
- **Capture scripts handle BOTH error shapes** — top-level `[{errorCode,message}]` AND `appointments[0].errors[0]{errorCode,message}` — plus `schedulingStatus` and (for reads) `data.result[].slots[]` counts.
- **Honesty guardrails:** (1) every control must return >0 slots AND book Success (proves the fixture is valid, so the violating empty/reject is the RULE not a dead fixture); (2) RequiredResources records the real read-prune vs write-NPE divergence; (3) each test javadoc cites the Core file:line it proves.
- **Tests run live** (external-org creds); verdicts encoded from observation. If live diverges from the code-derived expectation, update the assertion to observed AND record the divergence; never weaken to force a pass.
- **jdwp double-assert** where an API verdict alone is ambiguous (no-op reschedule short-circuit; RequiredResources crash; AppointmentStartTimeInterval 0-slots-for-unclear-reason). Record jdwp findings in the decision log.

## Core facts (established; do not re-investigate)

- Engine entrypoint: `InBusinessGetCandidatesSlotsDataService.loadSchedulableSlots` (unified-scheduling-impl). Read APIs get-appointment-slots/get-appointment-candidates/get-available-slots/get-available-resources all reach it with the full 7-rule `SchedulingPolicyInfo` set. Write (schedule/reschedule) re-invokes it via `SlotAvailabilityChecker.isSlotAvailable` → `getSlots` → `InBusinessGetSlotsHandler`.
- Reschedule no-op short-circuit: `SlotAvailabilityChecker:174-176` returns `true` when `!timesAreChanging && !resourcesHaveChanged` (loadSchedulableSlots NOT re-invoked).
- RequiredResources on 262: the write path throws `INTERNAL_SERVER_ERROR` (`serviceTerritoryMembers` NPE) on the non-required-satisfier violating case (Decision 1.4, already characterized in `WfsWritePathParityE2ETest.testNonRequiredHelperCannotSatisfyRequiredDemandE2E`).
- 7 rules (`RuleObjectiveMapper:108-125`): COMMON = MatchSkills, ExcludedResources, RequiredResources, Availability, ServiceAppointmentVisitingHours, WorkingLocations; IN_BUSINESS = AppointmentStartTimeInterval.
- Policy-authoring recipe (manager persona, dynamic version): SchedulingRule (`SchedulingRuleType` code, `SchedulingCategory:A`) at `/sobjects/SchedulingRule`; SchedulingRuleParameter at `/sobjects/SchedulingRuleParameter`; SchedulingPolicy at `/tooling/sobjects/SchedulingPolicy`; link via `/tooling/sobjects/SchedulingPolicyRule` (`IsActive:true`). An Availability(C)+ShiftUsage rule + a WorkingTerritories+IsPrimaryLocationEnabled=true rule are REQUIRED in every policy or slot-gen yields 0 slots (from prior sessions).

## Established repo idioms (copy these shapes)

- Read act body (GetAppointmentSlots): `booking/get-slots-parity-available/10-*.request.yaml` (already in repo). `coreDetails` with `appointmentMode:"Regular"`, `locationConstraints.addressIds:[...]`, `assignedResources:[{isRequiredResource:true, serviceResourceId}]`; count via `data.result[].slots[]`.
- Write act body (Schedule): `booking/schedule-parity-available/10-*.request.yaml`. `coreDetails` with `locationConstraints.id`, `assignedResources:[{isPrimaryResource,isRequiredResource,serviceResourceId}]`; capture `appointments[0].schedulingStatus`.
- Reschedule act body: `booking/reschedule-delete-primary-no-flag/10-*.request.yaml`. `rescheduleAppointments:[{schedulingMethod, appointmentId, ...}]`.
- Dual-shape errorCode capture: `booking/schedule-single-required-no-primary/10-*.request.yaml` afterResponse.
- Fixture graph: `fixtures/required-non-required/create-*.request.yaml` (composite/graph, sets ids from `graphs[0].graphResponse.compositeResponse`).
- Kick constant + test method: `ReVomanConfigForWfs.java` + `WfsWritePathParityE2ETest.java` / `WfsReadPathParityE2ETest.java`.

## File Structure

- **New Java:** `src/integrationTest/java/com/salesforce/revoman/integration/core/wfs/WfsRulesParityE2ETest.java` — the parity suite (one method per rule + cross-API + no-op reschedule).
- **Modified Java:** `…/wfs/ReVomanConfigForWfs.java` — add Kick constants + class-javadoc bullets.
- **New collection folders** under `…/pm-templates/v3/core/wfs/`:
  - `policies/visiting-hours-op-hours-policy/`, `fixtures/visiting-hours-account-oh/` (lift+conform) + `booking/get-slots-visiting-hours-violating/`, `booking/schedule-visiting-hours-violating/`, `booking/get-slots-visiting-hours-control/`, `booking/schedule-visiting-hours-control/`.
  - `booking/get-slots-workloc-violating/` + write/control acts (reuse repo `territory-membership-partial-policy` + `working-locations-secondary-non-required` fixture, or lift `territory-membership-partial` fixture).
  - `booking/get-slots-skills-violating/` + write/control (reuse repo `match-skills-non-required-policy` + `skills-non-required` fixture; violation on the required+primary resource).
  - `booking/get-slots-excluded-violating/` + write/control (reuse repo `excluded-resources-availability-policy` + `excluded-non-required` fixture; exclude the required+primary resource).
  - `booking/get-slots-required-violating/` + the RequiredResources read act (reuse repo `required-resources-availability-policy` + `required-non-required` fixture).
  - `policies/start-time-interval-policy/`, `fixtures/start-time-interval/` (net-new) + read/write/control acts.
  - Cross-API: `booking/get-candidates-skills-violating/`, `booking/get-available-slots-skills-violating/`, `booking/get-available-resources-skills-violating/` (MatchSkills violation through the other 3 read APIs).
  - No-op reschedule: `booking/schedule-noop-resched-setup/`, `booking/reschedule-noop/`.
- **Modified:** `.postman/resources.yaml` (register every new folder).

---

## Task 1: Scaffold `WfsRulesParityE2ETest` + prove the pattern on MatchSkills (read==write)

The first task establishes the class skeleton AND the full parity-triple pattern on one rule (MatchSkills), so every later rule task is a clean copy. MatchSkills reuses the in-repo `match-skills-non-required-policy` + `skills-non-required` fixture (already conformed), so no fixture lift is needed — lowest-risk first rule.

**Files:**
- Create: `src/integrationTest/java/com/salesforce/revoman/integration/core/wfs/WfsRulesParityE2ETest.java`
- Create: `…/wfs/booking/get-slots-skills-violating/{.resources/definition.yaml,10-get-slots-skills-violating.request.yaml}`
- Create: `…/wfs/booking/get-slots-skills-control/{.resources/definition.yaml,10-get-slots-skills-control.request.yaml}`
- Create: `…/wfs/booking/schedule-skills-violating/{.resources/definition.yaml,10-schedule-skills-violating.request.yaml}`
- Create: `…/wfs/booking/schedule-skills-control/{.resources/definition.yaml,10-schedule-skills-control.request.yaml}`
- Modify: `…/wfs/ReVomanConfigForWfs.java`
- Modify: `.postman/resources.yaml`

**Interfaces:**
- Consumes: `AUTH_CONFIG`, `MATCH_SKILLS_POLICY_CONFIG`, `SKILLS_SKILL_FIXTURE_CONFIG`, `SKILLS_FIXTURE_CONFIG` (existing Kicks); env `matchSkillsPolicyId` and the skills fixture's territory/workType/account/resource ids + the skill/required-skill ids.
- Produces: Kicks `GET_SLOTS_SKILLS_VIOLATING_CONFIG`, `GET_SLOTS_SKILLS_CONTROL_CONFIG`, `SCHEDULE_SKILLS_VIOLATING_CONFIG`, `SCHEDULE_SKILLS_CONTROL_CONFIG`; env keys `skillsReadViolatingSlotCount`, `skillsReadControlSlotCount`, `skillsWriteViolatingStatus`, `skillsWriteViolatingErrorCode`, `skillsWriteControlStatus`.

- [ ] **Step 1: Inspect the existing skills fixture to learn its exact env keys + how it wires the skill**

Run: `cat src/integrationTest/resources/pm-templates/v3/core/wfs/fixtures/skills-non-required/create-*.request.yaml src/integrationTest/resources/pm-templates/v3/core/wfs/fixtures/skills-non-required-skill/*.request.yaml`
Note the env keys it sets (resource ids, workType id, territory id, account id, the Skill/ServiceResourceSkill/SkillRequirement ids) and WHICH resource carries (or lacks) the required skill. The VIOLATING case must put the missing-skill violation on the **required+primary** resource (not a helper); the CONTROL gives that resource the skill. If the existing fixture only models a non-required helper missing the skill, the violating READ/WRITE acts must assign that resource as `isRequiredResource:true, isPrimaryResource:true` so the rule actually prunes/rejects. Record the exact env key names for use below.

- [ ] **Step 2: Create the violating read act** (`get-slots-skills-violating/.resources/definition.yaml`)

```yaml
$kind: collection
description: >-
  Rules parity (MatchSkills) READ violating — GetAppointmentSlots for the required+primary resource that
  LACKS the WorkType's required skill under the match-skills policy. MatchSkills prunes it, so the read
  offers ZERO slots. Paired with the write violating act to prove read==write on MatchSkills.
auth:
  - id: 2b1c0d00-0001-4aaa-9bbb-000000000001
    type: bearer
    name: bearer auth
    credentials:
      token: "{{managerToken}}"
```
`get-slots-skills-violating/10-get-slots-skills-violating.request.yaml` — model on the repo `get-slots-parity-available` read act, but point workType/territory/account/resource at the skills fixture's env keys (from Step 1), assign the skill-lacking resource, window tomorrow 11:00-12:00 UTC. Capture:
```yaml
scripts:
  - type: afterResponse
    code: |-
      // MatchSkills prunes the skill-lacking required resource → read offers 0 slots.
      const data = pm.response.json() || {};
      const results = data.result || [];
      let n = 0; results.forEach(r => { n += (r.slots || []).length; });
      pm.environment.set("skillsReadViolatingSlotCount", String(n));
      console.log("RULESPARITY skills read-violating slots=" + n);
    language: text/javascript
```
(Full body mirrors `get-slots-parity-available`; only the ids + resource + capture-key differ. NO YAML `#` comments — use `//` inside the JS block as shown.)

- [ ] **Step 3: Create the control read act** (`get-slots-skills-control/`)

Same as Step 2 but the resource HAS the required skill (control fixture wiring), capturing `skillsReadControlSlotCount`. Description: "CONTROL — the required+primary resource HAS the skill → MatchSkills passes → read offers >0 slots (proves the fixture is valid, so the violating 0 is the rule)."

- [ ] **Step 4: Create the violating + control write acts** (`schedule-skills-violating/`, `schedule-skills-control/`)

Model on repo `schedule-parity-unavailable` / `schedule-parity-available`. Violating: schedule the skill-lacking required+primary resource into the same window → rejected; capture `skillsWriteViolatingStatus` + `skillsWriteViolatingErrorCode` (dual-shape). Control: resource has the skill → Success; capture `skillsWriteControlStatus`. Both carry `x-revoman-ledger:"off"` + `ignoreHTTPStatusUnsuccessful:"true"`.

- [ ] **Step 5: Add the 4 Kick constants + class-javadoc note in `ReVomanConfigForWfs.java`**

After the existing Decision-2 block, add a "Rules parity" section:
```java
  // ## Rules read==write parity — one differential matrix per Common+InBusiness rule. Each rule: a
  // violating act (rule fires → read 0 slots / write rejected) + a control act (rule passes → read >0 /
  // write Success), asserting read decision == write decision. See WfsRulesParityE2ETest.
  static final Kick GET_SLOTS_SKILLS_VIOLATING_CONFIG =
      kickFor(V3_WFS_PATH + "booking/get-slots-skills-violating");
  static final Kick GET_SLOTS_SKILLS_CONTROL_CONFIG =
      kickFor(V3_WFS_PATH + "booking/get-slots-skills-control");
  static final Kick SCHEDULE_SKILLS_VIOLATING_CONFIG =
      kickFor(V3_WFS_PATH + "booking/schedule-skills-violating");
  static final Kick SCHEDULE_SKILLS_CONTROL_CONFIG =
      kickFor(V3_WFS_PATH + "booking/schedule-skills-control");
```

- [ ] **Step 6: Create `WfsRulesParityE2ETest.java` with the MatchSkills method**

```java
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
```

- [ ] **Step 7: Register the 4 new folders in `.postman/resources.yaml`**

Append under `localResources.collections` (no comments):
```
    - ../src/integrationTest/resources/pm-templates/v3/core/wfs/booking/get-slots-skills-violating
    - ../src/integrationTest/resources/pm-templates/v3/core/wfs/booking/get-slots-skills-control
    - ../src/integrationTest/resources/pm-templates/v3/core/wfs/booking/schedule-skills-violating
    - ../src/integrationTest/resources/pm-templates/v3/core/wfs/booking/schedule-skills-control
```

- [ ] **Step 8: Compile**

Run: `gradle compileIntegrationTestJava`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 9: Run live, record verdicts**

Run: `gradle integrationTest --tests "com.salesforce.revoman.integration.core.wfs.WfsRulesParityE2ETest.testMatchSkillsReadWriteParityE2E"`
Expected: PASS. Console: `RULESPARITY skills read-violating slots=0`, control >0, write-violating not-Success, write-control Success.
LIVE-ADJUST (record, never weaken/force): if read-violating returns >0 (skill not pruning), the fixture likely gave the resource the skill — inspect + fix the fixture wiring (Step 1). If control returns 0 (fixture dead), the window/OH/shift is wrong — widen/verify. If write-violating unexpectedly Succeeds while read pruned → **that is a read≠write divergence** — STOP, capture both responses, jdwp-confirm, record it (do NOT loosen).

- [ ] **Step 10: Commit**

```bash
git add src/integrationTest/resources/pm-templates/v3/core/wfs/booking/get-slots-skills-violating \
        src/integrationTest/resources/pm-templates/v3/core/wfs/booking/get-slots-skills-control \
        src/integrationTest/resources/pm-templates/v3/core/wfs/booking/schedule-skills-violating \
        src/integrationTest/resources/pm-templates/v3/core/wfs/booking/schedule-skills-control \
        src/integrationTest/java/com/salesforce/revoman/integration/core/wfs/ReVomanConfigForWfs.java \
        src/integrationTest/java/com/salesforce/revoman/integration/core/wfs/WfsRulesParityE2ETest.java \
        .postman/resources.yaml
git commit -m "test(wfs): rules parity scaffold + MatchSkills read==write matrix"
```

---

## Task 2: ExcludedResources read==write parity

Reuses the in-repo `excluded-resources-availability-policy` + `excluded-non-required` fixture. Violation = the required+primary resource is on the Account's ExcludedResource list; control = not excluded.

**Files:**
- Create: `…/booking/get-slots-excluded-violating/`, `get-slots-excluded-control/`, `schedule-excluded-violating/`, `schedule-excluded-control/` (each `.resources/definition.yaml` + `10-*.request.yaml`)
- Modify: `…/wfs/ReVomanConfigForWfs.java`, `…/wfs/WfsRulesParityE2ETest.java`, `.postman/resources.yaml`

**Interfaces:**
- Consumes: `AUTH_CONFIG`, `EXCLUDED_RESOURCES_POLICY_CONFIG`, `EXCLUDED_FIXTURE_CONFIG`; the excluded fixture's env ids.
- Produces: Kicks `GET_SLOTS_EXCLUDED_VIOLATING_CONFIG`, `GET_SLOTS_EXCLUDED_CONTROL_CONFIG`, `SCHEDULE_EXCLUDED_VIOLATING_CONFIG`, `SCHEDULE_EXCLUDED_CONTROL_CONFIG`; env keys `excludedRead{Violating,Control}SlotCount`, `excludedWrite{Violating,Control}Status`, `excludedWriteViolatingErrorCode`.

- [ ] **Step 1: Inspect the excluded fixture env keys**

Run: `cat src/integrationTest/resources/pm-templates/v3/core/wfs/fixtures/excluded-non-required/create-*.request.yaml`
Record the resource/account/workType/territory ids and how the ExcludedResource (ResourcePreference PreferenceType=Excluded) is linked. The violating act assigns the EXCLUDED resource as required+primary; the control uses a non-excluded resource (or removes the exclusion).

- [ ] **Step 2: Create the 4 acts**

`get-slots-excluded-violating/` — GetAppointmentSlots for the excluded required+primary resource → 0 slots; capture `excludedReadViolatingSlotCount`. `get-slots-excluded-control/` — non-excluded resource → >0; `excludedReadControlSlotCount`. `schedule-excluded-violating/` — schedule the excluded resource → rejected; capture `excludedWriteViolatingStatus`+`excludedWriteViolatingErrorCode` (dual-shape). `schedule-excluded-control/` → Success; `excludedWriteControlStatus`. Model bodies on the repo `get-slots-parity-*`/`schedule-parity-*` acts; distinct auth UUIDs; ledger-off + ignore-HTTP-status; NO YAML comments.

- [ ] **Step 3: Add 4 Kick constants** (mirror Task 1 Step 5, "excluded" names).

- [ ] **Step 4: Add the test method** to `WfsRulesParityE2ETest.java`:
```java
  /**
   * ExcludedResources — an Account-excluded required+primary resource is pruned by the read (0 slots) AND
   * rejected by the write; a non-excluded control returns >0 AND Success. Proves ExcludedResources runs
   * identically read and write.
   * <p>262 (asserted): read-violating 0 ⟺ write-violating rejected; read-control >0 ⟺ write-control Success.
   * <p>264 contrast: unchanged — ExcludedResources is a shared cheap check.
   */
  @Test
  void testExcludedResourcesReadWriteParityE2E() {
    final var rundown =
        ReVoman.revUp(
            (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
            AUTH_CONFIG,
            EXCLUDED_RESOURCES_POLICY_CONFIG,
            EXCLUDED_FIXTURE_CONFIG,
            GET_SLOTS_EXCLUDED_VIOLATING_CONFIG,
            GET_SLOTS_EXCLUDED_CONTROL_CONFIG,
            SCHEDULE_EXCLUDED_VIOLATING_CONFIG,
            SCHEDULE_EXCLUDED_CONTROL_CONFIG);
    final var env = CollectionsKt.last(rundown).mutableEnv;
    assertThat(env.getAsString("excludedReadViolatingSlotCount")).isEqualTo("0");
    assertThat(Integer.parseInt(env.getAsString("excludedReadControlSlotCount"))).isGreaterThan(0);
    assertThat(env.getAsString("excludedWriteViolatingStatus")).isNotEqualTo("Success");
    assertThat(env.getAsString("excludedWriteControlStatus")).isEqualTo("Success");
  }
```
Add the 6 imports (4 new Kicks + `EXCLUDED_RESOURCES_POLICY_CONFIG` + `EXCLUDED_FIXTURE_CONFIG`).

- [ ] **Step 5: Register 4 folders in `.postman/resources.yaml`** (no comments).

- [ ] **Step 6: Compile** — `gradle compileIntegrationTestJava` → BUILD SUCCESSFUL.

- [ ] **Step 7: Run live** — `gradle integrationTest --tests "…WfsRulesParityE2ETest.testExcludedResourcesReadWriteParityE2E"`. Same LIVE-ADJUST rules as Task 1 Step 9 (a write-Success-while-read-pruned = record a divergence, jdwp-confirm, don't loosen).

- [ ] **Step 8: Commit** — `git commit -m "test(wfs): ExcludedResources read==write parity matrix"` (add the 4 folders + 3 modified files).

---

## Task 3: WorkingLocations read==write parity

Reuses the in-repo `territory-membership-partial-policy` + `working-locations-secondary-non-required` fixture (or lift the source `territory-membership-partial` fixture if the repo one doesn't model a required-resource violation). Violation = the required+primary resource is outside its working-location/territory-membership window; control = inside.

**Files:** `…/booking/get-slots-workloc-violating/`, `get-slots-workloc-control/`, `schedule-workloc-violating/`, `schedule-workloc-control/`; modify config + test + resources.yaml.

**Interfaces:**
- Consumes: `AUTH_CONFIG`, `TERRITORY_PARTIAL_POLICY_CONFIG`, `WORKING_LOCATIONS_FIXTURE_CONFIG`.
- Produces: Kicks `GET_SLOTS_WORKLOC_{VIOLATING,CONTROL}_CONFIG`, `SCHEDULE_WORKLOC_{VIOLATING,CONTROL}_CONFIG`; env keys `worklocRead{Violating,Control}SlotCount`, `worklocWrite{Violating,Control}Status`, `worklocWriteViolatingErrorCode`.

- [ ] **Step 1: Inspect the working-locations fixture + territory-partial policy**

Run: `cat src/integrationTest/resources/pm-templates/v3/core/wfs/fixtures/working-locations-secondary-non-required/create-*.request.yaml src/integrationTest/resources/pm-templates/v3/core/wfs/policies/territory-membership-partial-policy/*.request.yaml`
Determine the window/territory that makes the required+primary resource violate WorkingLocations (e.g. a partial-membership window that excludes the booking time) vs the control window inside it. If the existing fixture only violates on a secondary/non-required resource, adapt the acts to assign the violating resource as required+primary.

- [ ] **Step 2: Create the 4 acts** (violating read/write → 0 slots / rejected; control → >0 / Success), capturing the `workloc*` env keys. Same shapes as Task 1/2; NO YAML comments; distinct auth UUIDs.

- [ ] **Step 3: Add 4 Kick constants.**

- [ ] **Step 4: Add the test method:**
```java
  /**
   * WorkingLocations (SchedulingRuleType WorkingTerritories) — the required+primary resource outside its
   * working-location/territory-membership window is pruned by the read (0 slots) AND rejected by the write;
   * an in-window control returns >0 AND Success.
   * <p>262 (asserted): read-violating 0 ⟺ write-violating rejected; read-control >0 ⟺ write-control Success.
   * <p>264 contrast: unchanged.
   */
  @Test
  void testWorkingLocationsReadWriteParityE2E() {
    final var rundown =
        ReVoman.revUp(
            (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
            AUTH_CONFIG,
            TERRITORY_PARTIAL_POLICY_CONFIG,
            WORKING_LOCATIONS_FIXTURE_CONFIG,
            GET_SLOTS_WORKLOC_VIOLATING_CONFIG,
            GET_SLOTS_WORKLOC_CONTROL_CONFIG,
            SCHEDULE_WORKLOC_VIOLATING_CONFIG,
            SCHEDULE_WORKLOC_CONTROL_CONFIG);
    final var env = CollectionsKt.last(rundown).mutableEnv;
    assertThat(env.getAsString("worklocReadViolatingSlotCount")).isEqualTo("0");
    assertThat(Integer.parseInt(env.getAsString("worklocReadControlSlotCount"))).isGreaterThan(0);
    assertThat(env.getAsString("worklocWriteViolatingStatus")).isNotEqualTo("Success");
    assertThat(env.getAsString("worklocWriteControlStatus")).isEqualTo("Success");
  }
```

- [ ] **Step 5: Register folders in resources.yaml. Step 6: Compile. Step 7: Run live** (`…testWorkingLocationsReadWriteParityE2E`; LIVE-ADJUST per Task 1). **Step 8: Commit** `test(wfs): WorkingLocations read==write parity matrix`.

---

## Task 4: ServiceAppointmentVisitingHours read==write parity (lift + conform)

No in-repo policy/fixture — lift `visiting-hours-op-hours-policy` + `visiting-hours-account-oh` from source `~/work/revoman/unified-validation-262/postman/collections/`, conforming to the 262 contract (WorkType has NO SchedulingMethod/IsRegular; no top-level referenceId; relative composite/graph URLs; window ≥ WorkType duration). The source has read (`get-slots-visiting-hours`) and write (`schedule-visiting-hours-violating-window`) acts to adapt.

**Files:** `policies/visiting-hours-op-hours-policy/`, `fixtures/visiting-hours-account-oh/` (lift), `booking/get-slots-visiting-hours-violating/`, `get-slots-visiting-hours-control/`, `schedule-visiting-hours-violating/`, `schedule-visiting-hours-control/`; modify config + test + resources.yaml.

**Interfaces:**
- Produces: Kicks `VISITING_HOURS_POLICY_CONFIG`, `VISITING_HOURS_FIXTURE_CONFIG`, `GET_SLOTS_VISITING_HOURS_{VIOLATING,CONTROL}_CONFIG`, `SCHEDULE_VISITING_HOURS_{VIOLATING,CONTROL}_CONFIG`; env keys `visitingHoursRead{Violating,Control}SlotCount`, `visitingHoursWrite{Violating,Control}Status`, `visitingHoursWriteViolatingErrorCode`.

- [ ] **Step 1: Copy the source policy + fixture folders into the repo, conforming to 262**

Run:
```bash
SRC=~/work/revoman/unified-validation-262/postman/collections
DST=src/integrationTest/resources/pm-templates/v3/core/wfs
cp -r "$SRC/policies/visiting-hours-op-hours-policy" "$DST/policies/"
cp -r "$SRC/fixtures/visiting-hours-account-oh" "$DST/fixtures/"
```
Then EDIT the copied fixture graph: remove any `SchedulingMethod`/`IsRegular` from the WorkType body; remove any top-level `referenceId` from schedule bodies; ensure composite/graph node URLs are relative and `Status:"Confirmed"` shifts + a Primary STM effective in the past exist; set the account VisitingHours OperatingHours so the booking window VIOLATES it for the violating case. Change every `token:` to `{{managerToken}}`. Confirm NO YAML `#` comments remain in the copied files (source may contain them — strip to `description:`/JS-only). Verify env keys the fixture sets (territory/workType/account/resource/OH ids).

- [ ] **Step 2: Create the 4 violating/control acts** over the visiting-hours fixture. Violating window = outside the account's visiting hours (rule prunes → 0 slots read / rejected write). Control window = inside visiting hours (>0 / Success). Capture `visitingHours*` keys. NO YAML comments.

- [ ] **Step 3: Add Kick constants** for the policy, fixture, and 4 acts.

- [ ] **Step 4: Add the test method:**
```java
  /**
   * ServiceAppointmentVisitingHours — a booking window OUTSIDE the account's visiting hours is pruned by
   * the read (0 slots) AND rejected by the write; an in-visiting-hours control returns >0 AND Success.
   * <p>262 (asserted): read-violating 0 ⟺ write-violating rejected; read-control >0 ⟺ write-control Success.
   * <p>264 contrast: unchanged.
   */
  @Test
  void testVisitingHoursReadWriteParityE2E() {
    final var rundown =
        ReVoman.revUp(
            (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
            AUTH_CONFIG,
            VISITING_HOURS_POLICY_CONFIG,
            VISITING_HOURS_FIXTURE_CONFIG,
            GET_SLOTS_VISITING_HOURS_VIOLATING_CONFIG,
            GET_SLOTS_VISITING_HOURS_CONTROL_CONFIG,
            SCHEDULE_VISITING_HOURS_VIOLATING_CONFIG,
            SCHEDULE_VISITING_HOURS_CONTROL_CONFIG);
    final var env = CollectionsKt.last(rundown).mutableEnv;
    assertThat(env.getAsString("visitingHoursReadViolatingSlotCount")).isEqualTo("0");
    assertThat(Integer.parseInt(env.getAsString("visitingHoursReadControlSlotCount"))).isGreaterThan(0);
    assertThat(env.getAsString("visitingHoursWriteViolatingStatus")).isNotEqualTo("Success");
    assertThat(env.getAsString("visitingHoursWriteControlStatus")).isEqualTo("Success");
  }
```

- [ ] **Step 5: Register the policy + fixture + 4 act folders in resources.yaml.** **Step 6: Compile.** **Step 7: Run live** (`…testVisitingHoursReadWriteParityE2E`; LIVE-ADJUST + if 0-slots-for-unclear-reason on the control, jdwp `loadSchedulableSlots`). **Step 8: Commit** `test(wfs): VisitingHours read==write parity matrix (lifted+conformed)`.

---

## Task 5: AppointmentStartTimeInterval read==write parity (net-new fixture + policy)

Highest-uncertainty task — no source to lift. Author a policy with an `AppointmentStartTimeInterval` SchedulingRuleParameter and a fixture; violation = a booking start time NOT on the interval boundary (rule prunes) vs control on the boundary. jdwp is expected here if slots don't generate.

**Files:** `policies/start-time-interval-policy/`, `fixtures/start-time-interval/` (net-new), `booking/get-slots-sti-violating/`, `get-slots-sti-control/`, `schedule-sti-violating/`, `schedule-sti-control/`; modify config + test + resources.yaml.

**Interfaces:**
- Produces: Kicks `START_TIME_INTERVAL_POLICY_CONFIG`, `START_TIME_INTERVAL_FIXTURE_CONFIG`, `GET_SLOTS_STI_{VIOLATING,CONTROL}_CONFIG`, `SCHEDULE_STI_{VIOLATING,CONTROL}_CONFIG`; env keys `stiRead{Violating,Control}SlotCount`, `stiWrite{Violating,Control}Status`, `stiWriteViolatingErrorCode`.

- [ ] **Step 1: Author the net-new policy** (`policies/start-time-interval-policy/`)

A policy folder modeled on the repo `availability-op-hours-policy` (Availability(C)+ShiftUsage=Union + WorkingTerritories+IsPrimaryLocationEnabled=true — both REQUIRED for slot-gen), PLUS a SchedulingRule of type `AppointmentStartTimeInterval` with a SchedulingRuleParameter. Determine the exact parameter key from Core: run `rg -n "AppointmentStartTimeInterval|getAppointmentStartTimeInterval|StartTimeInterval" /opt/workspace/core-public/core/unified-scheduling-impl` and read `SchedulingPolicyInfo.getAppointmentStartTimeInterval` (~:222) to find the SchedulingParameterKey it reads (e.g. an interval-minutes key) and the SchedulingRule DeveloperName/category. Author the SchedulingRule + SchedulingRuleParameter + SchedulingPolicy + SchedulingPolicyRule chain (recipe in Global/Core facts). Set the interval to e.g. 60 minutes. Capture `startTimeIntervalPolicyId`. NO YAML comments.

- [ ] **Step 2: Author the net-new fixture** (`fixtures/start-time-interval/`) — a composite/graph modeled on `fixtures/required-non-required` (territory OH 08-16 + weekly TimeSlots, member OH, WorkType 60m NO SchedulingMethod/IsRegular, ServiceResource on caseWorkerUserId, Primary STM effective past, Confirmed Shift 08-16 tomorrow SchedulingMethod=OnSite TimeSlotType=Normal, Account). Set `sti*` env ids. NO YAML comments.

- [ ] **Step 3: Create the 4 acts.** Violating: booking start time OFF the interval boundary (e.g. 11:30 when interval anchors on the hour) → read 0 slots at that exact start / write rejected. Control: on-boundary start (11:00) → >0 / Success. Capture `sti*` keys. (The read act searches a window; assert 0 slots for the violating exact-start vs >0 for control — adjust window semantics per how the interval rule steps slots, confirmed in Step 1.)

- [ ] **Step 4: Add Kick constants** (policy, fixture, 4 acts).

- [ ] **Step 5: Add the test method:**
```java
  /**
   * AppointmentStartTimeInterval (the sole IN_BUSINESS_RULE_TYPES member) — a start time off the policy's
   * interval boundary is pruned by the read (0 slots) AND rejected by the write; an on-boundary control
   * returns >0 AND Success. Proves the interval rule runs identically read and write.
   * <p>262 (asserted): read-violating 0 ⟺ write-violating rejected; read-control >0 ⟺ write-control Success.
   * <p>264 contrast: unchanged.
   */
  @Test
  void testStartTimeIntervalReadWriteParityE2E() {
    final var rundown =
        ReVoman.revUp(
            (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
            AUTH_CONFIG,
            START_TIME_INTERVAL_POLICY_CONFIG,
            START_TIME_INTERVAL_FIXTURE_CONFIG,
            GET_SLOTS_STI_VIOLATING_CONFIG,
            GET_SLOTS_STI_CONTROL_CONFIG,
            SCHEDULE_STI_VIOLATING_CONFIG,
            SCHEDULE_STI_CONTROL_CONFIG);
    final var env = CollectionsKt.last(rundown).mutableEnv;
    assertThat(env.getAsString("stiReadViolatingSlotCount")).isEqualTo("0");
    assertThat(Integer.parseInt(env.getAsString("stiReadControlSlotCount"))).isGreaterThan(0);
    assertThat(env.getAsString("stiWriteViolatingStatus")).isNotEqualTo("Success");
    assertThat(env.getAsString("stiWriteControlStatus")).isEqualTo("Success");
  }
```

- [ ] **Step 6: Register folders. Step 7: Compile. Step 8: Run live.** If the control returns 0 slots (fixture/policy not generating), **jdwp double-assert**: attach to the Core server, breakpoint in `loadSchedulableSlots` / `InBusinessAppointmentSlotCalculator` start-time-interval stepping (~:1014-1088), confirm whether the interval rule is pruning (correct) or the fixture is dead (fix fixture). Record jdwp findings. **Step 9: Commit** `test(wfs): AppointmentStartTimeInterval read==write parity matrix (net-new)`.

---

## Task 6: RequiredResources read==write — record the 262 divergence (read prunes, write NPEs)

RequiredResources is the divergence case. The write side is ALREADY characterized by `WfsWritePathParityE2ETest.testNonRequiredHelperCannotSatisfyRequiredDemandE2E` (violating → INTERNAL_SERVER_ERROR serviceTerritoryMembers NPE). This task adds the READ side over the SAME `required-non-required` fixture and asserts the divergence: read prunes cleanly (0 slots or the required-satisfier absent) while write crashes — a genuine read≠write asymmetry, jdwp-confirmed.

**Files:** `booking/get-slots-required-violating/`, `get-slots-required-control/`; modify config + test + resources.yaml.

**Interfaces:**
- Consumes: `AUTH_CONFIG`, `REQUIRED_RESOURCES_POLICY_CONFIG`, `REQUIRED_NON_REQUIRED_FIXTURE_CONFIG`.
- Produces: Kicks `GET_SLOTS_REQUIRED_VIOLATING_CONFIG`, `GET_SLOTS_REQUIRED_CONTROL_CONFIG`; env keys `requiredReadViolatingSlotCount`, `requiredReadControlSlotCount`.

- [ ] **Step 1: Create the read acts** over the `required-non-required` fixture. Violating: GetAppointmentSlots where only a NON-required helper satisfies the account's required-resource demand (mirror the write violating act's fixture wiring) → capture `requiredReadViolatingSlotCount`. Control: a genuine required satisfier → `requiredReadControlSlotCount` >0. NO YAML comments.

- [ ] **Step 2: Add 2 Kick constants.**

- [ ] **Step 3: Add the test method** — asserts the READ side AND references the known write NPE divergence:
```java
  /**
   * RequiredResources — the READ side prunes when only a non-required helper satisfies the account's
   * required-resource demand (0 slots), while the WRITE side on 262 CRASHES with INTERNAL_SERVER_ERROR
   * (serviceTerritoryMembers NPE) on the same scenario — see
   * WfsWritePathParityE2ETest.testNonRequiredHelperCannotSatisfyRequiredDemandE2E. This is a GENUINE
   * read≠write divergence on 262 (read prunes cleanly; write throws), jdwp-confirmed, recorded not hidden.
   * A genuine-required-satisfier control returns >0 slots (read side valid).
   * <p>262 (asserted): read prunes (0 / control >0); write NPE-crashes (asserted in the write class).
   * <p>264 contrast: the write NPE should become a clean RequiredResources rejection, restoring read==write.
   */
  @Test
  void testRequiredResourcesReadPrunesWhileWriteCrashesE2E() {
    final var rundown =
        ReVoman.revUp(
            (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
            AUTH_CONFIG,
            REQUIRED_RESOURCES_POLICY_CONFIG,
            REQUIRED_NON_REQUIRED_FIXTURE_CONFIG,
            GET_SLOTS_REQUIRED_VIOLATING_CONFIG,
            GET_SLOTS_REQUIRED_CONTROL_CONFIG);
    final var env = CollectionsKt.last(rundown).mutableEnv;
    // Read side: the non-required-only demand prunes; the genuine-satisfier control returns slots.
    assertThat(env.getAsString("requiredReadViolatingSlotCount")).isEqualTo("0");
    assertThat(Integer.parseInt(env.getAsString("requiredReadControlSlotCount"))).isGreaterThan(0);
    // The write side of this scenario is characterized (as the 262 NPE crash) in
    // WfsWritePathParityE2ETest.testNonRequiredHelperCannotSatisfyRequiredDemandE2E — read≠write on 262.
  }
```

- [ ] **Step 4: Register folders. Step 5: Compile. Step 6: Run live.**
If the read-violating returns >0 (read does NOT prune where write crashes), that is itself a finding — capture it. **jdwp double-assert:** confirm on the read path the required-satisfaction evaluation prunes the candidate, and on the write path the same scenario hits the `serviceTerritoryMembers` NPE — proving the asymmetry is read-prunes / write-crashes, not both-crash. Record in the decision log.

- [ ] **Step 7: Commit** `test(wfs): RequiredResources read-prunes-while-write-crashes divergence (262)`.

---

## Task 7: Cross-API agreement — one MatchSkills violation through all 6 rule-evaluating APIs

Proves the 4 read APIs + 2 write APIs share the one `loadSchedulableSlots` engine, so the per-rule matrix generalizes to every API. Reuses the Task-1 skills fixture/policy; adds the 3 not-yet-exercised read APIs (get-appointment-candidates, get-available-slots, get-available-resources) for the violating skills case.

**Files:** `booking/get-candidates-skills-violating/`, `get-available-slots-skills-violating/`, `get-available-resources-skills-violating/`; modify config + test + resources.yaml.

**Interfaces:**
- Consumes: Task-1 skills Kicks + fixture; the existing `GET_SLOTS_SKILLS_VIOLATING_CONFIG` + `SCHEDULE_SKILLS_VIOLATING_CONFIG`.
- Produces: Kicks `GET_CANDIDATES_SKILLS_VIOLATING_CONFIG`, `GET_AVAILABLE_SLOTS_SKILLS_VIOLATING_CONFIG`, `GET_AVAILABLE_RESOURCES_SKILLS_VIOLATING_CONFIG`; env keys `skillsCandidatesCount`, `skillsAvailableSlotsCount`, `skillsAvailableResourcesCount`.

- [ ] **Step 1: Create get-appointment-candidates violating act** — body identical to `get-slots-skills-violating` but URL `.../actions/get-appointment-candidates`; response is `data.result[].candidates[]` (or the candidate shape — verify the response key by running once and inspecting). Capture `skillsCandidatesCount`. NO YAML comments.

- [ ] **Step 2: Create get-available-slots violating act** — URL `.../actions/get-available-slots`; body carries `assignedResources` (dispatches to get-appointment-slots per `GetAvailableSlotServiceImpl:73-102`). Capture `skillsAvailableSlotsCount` from its result shape (verify key on first run).

- [ ] **Step 3: Create get-available-resources violating act** — URL `.../actions/get-available-resources`; body per the repo `get-available-resources-limit-zero` act shape (parentRecordId/workTypeId/territoryIds/limit above seeded count/fieldLimit); the skill-lacking resource should be ABSENT from `availableResources` (array-of-arrays; flatten to count). Capture `skillsAvailableResourcesCount`. NO YAML comments.

- [ ] **Step 4: Add 3 Kick constants.**

- [ ] **Step 5: Add the test method** — runs the violating skills case through all 4 reads + schedule, asserts unanimous pruning/rejection:
```java
  /**
   * Cross-API agreement — the SAME MatchSkills violation (a required+primary resource lacking the skill)
   * is pruned/rejected by ALL 4 rule-evaluating read APIs (get-appointment-slots, get-appointment-candidates,
   * get-available-slots, get-available-resources) AND the schedule write API. Empirically proves they share
   * the one loadSchedulableSlots engine, so the per-rule read==write matrix generalizes to every API.
   * <p>262 (asserted): every read returns 0 for the skill-lacking resource; schedule rejects.
   * <p>264 contrast: unchanged.
   */
  @Test
  void testCrossApiRuleAgreementE2E() {
    final var rundown =
        ReVoman.revUp(
            (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
            AUTH_CONFIG,
            MATCH_SKILLS_POLICY_CONFIG,
            SKILLS_SKILL_FIXTURE_CONFIG,
            SKILLS_FIXTURE_CONFIG,
            GET_SLOTS_SKILLS_VIOLATING_CONFIG,
            GET_CANDIDATES_SKILLS_VIOLATING_CONFIG,
            GET_AVAILABLE_SLOTS_SKILLS_VIOLATING_CONFIG,
            GET_AVAILABLE_RESOURCES_SKILLS_VIOLATING_CONFIG,
            SCHEDULE_SKILLS_VIOLATING_CONFIG);
    final var env = CollectionsKt.last(rundown).mutableEnv;
    // All 4 reads prune the skill-lacking resource.
    assertThat(env.getAsString("skillsReadViolatingSlotCount")).isEqualTo("0");
    assertThat(env.getAsString("skillsCandidatesCount")).isEqualTo("0");
    assertThat(env.getAsString("skillsAvailableSlotsCount")).isEqualTo("0");
    assertThat(env.getAsString("skillsAvailableResourcesCount")).isEqualTo("0");
    // Write agrees.
    assertThat(env.getAsString("skillsWriteViolatingStatus")).isNotEqualTo("Success");
  }
```
(Note: `SCHEDULE_SKILLS_VIOLATING_CONFIG` sets `skillsWriteViolatingStatus`; the get-slots act sets `skillsReadViolatingSlotCount` — both from Task 1, reused here.)

- [ ] **Step 6: Register folders. Step 7: Compile. Step 8: Run live.**
FIRST run may reveal the exact response keys for candidates/available-slots/available-resources — inspect the console/response and fix the capture scripts to the real shapes (record any that differ from the assumption). If get-available-resources still RETURNS the resource (doesn't prune), that would refute "full rule set" — STOP, jdwp-confirm against `AvailableResourcesServiceImpl:322` → `getCandidatesProcessor.process`, capture the finding (this is the exact contradiction the investigation resolved; verify live).

- [ ] **Step 9: Commit** `test(wfs): cross-API rule agreement (MatchSkills through all 6 rule-evaluating APIs)`.

---

## Task 8: No-op reschedule short-circuit (write < read) + jdwp confirm

Proves a reschedule that changes neither time nor required-resources SUCCEEDS even into a now-unavailable state — because `SlotAvailabilityChecker:174-176` short-circuits and skips the availability recompute. A genuine write≠read case. jdwp confirms the branch (API Success alone is ambiguous).

**Files:** `booking/schedule-noop-resched-setup/`, `booking/reschedule-noop/`; modify config + test + resources.yaml.

**Interfaces:**
- Consumes: `AUTH_CONFIG`, `AVAILABILITY_OP_HOURS_POLICY_CONFIG`, `REQUIRED_NON_REQUIRED_FIXTURE_CONFIG`.
- Produces: Kicks `SCHEDULE_NOOP_RESCHED_SETUP_CONFIG`, `RESCHEDULE_NOOP_CONFIG`; env keys `noopSetupStatus`, `noopSetupSaId`, `noopReschedStatus`.

- [ ] **Step 1: Create the setup schedule act** — schedule resourceA into an available window (tomorrow 11:00-12:00), Success; capture `noopSetupStatus` + `noopSetupSaId` (`appointments[0].serviceAppointment.serviceAppointmentId`). Model on repo `schedule-two-resource-clean`. NO YAML comments.

- [ ] **Step 2: Create the no-op reschedule act** — `POST .../actions/reschedule` with `rescheduleAppointments:[{schedulingMethod:"OnSite", appointmentId:"{{noopSetupSaId}}"}]` — NO startTime/endTime, NO assignedResources changes (so `timesAreChanging==false && resourcesHaveChanged==false`). Capture `noopReschedStatus`. To make the point that it skips availability, the fixture's shift/OH should be such that a real recompute at the unchanged window still passes (a no-op reschedule of an already-valid SA) — assert Success proves the short-circuit path returns true. (A stronger variant — reschedule into a now-unavailable state — requires mutating availability mid-run, which is hard over REST; the no-op-of-valid-SA + jdwp branch confirmation is the reliable proof.) NO YAML comments.

- [ ] **Step 3: Add 2 Kick constants.**

- [ ] **Step 4: Add the test method:**
```java
  /**
   * No-op reschedule short-circuit — a reschedule that changes NEITHER time NOR required-resources returns
   * Success WITHOUT re-running the availability slot calc: SlotAvailabilityChecker:174-176 returns true when
   * !timesAreChanging && !resourcesHaveChanged. This is a genuine write<read case (the write does LESS rule
   * evaluation than a read would). jdwp confirms the short-circuit branch (see the decision log).
   * <p>262 (asserted): setup schedule Success; no-op reschedule Success via the short-circuit.
   * <p>264 contrast: unchanged (the short-circuit is intended).
   */
  @Test
  void testNoOpRescheduleShortCircuitE2E() {
    final var rundown =
        ReVoman.revUp(
            (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
            AUTH_CONFIG,
            AVAILABILITY_OP_HOURS_POLICY_CONFIG,
            REQUIRED_NON_REQUIRED_FIXTURE_CONFIG,
            SCHEDULE_NOOP_RESCHED_SETUP_CONFIG,
            RESCHEDULE_NOOP_CONFIG);
    final var env = CollectionsKt.last(rundown).mutableEnv;
    assertThat(env.getAsString("noopSetupStatus")).isEqualTo("Success");
    assertThat(env.getAsString("noopSetupSaId")).isNotNull();
    assertThat(env.getAsString("noopReschedStatus")).isEqualTo("Success");
  }
```

- [ ] **Step 5: Register folders. Step 6: Compile. Step 7: Run live.**
**jdwp double-assert (required):** attach to the Core server, breakpoint at `SlotAvailabilityChecker:174-176`; issue the no-op reschedule; confirm `timesAreChanging==false && resourcesHaveChanged==false` and the early `return true` executes (loadSchedulableSlots NOT re-invoked — set a breakpoint there too and confirm it is NOT hit on the reschedule). Record the jdwp evidence in the decision log. (If jdwp/server is unavailable, record the code-derived expectation + note the live Success is consistent, and mark for jdwp re-confirm.)

- [ ] **Step 8: Commit** `test(wfs): no-op reschedule short-circuit (write<read, jdwp-confirmed)`.

---

## Task 9: Decision log + handoff + holistic whole-suite review

- [ ] **Step 1: Write the decision log** `~/work/impl-decisions/2026-06-30-wfs-rules-readwrite-parity.md` — the full code→test→jdwp mapping per rule; the Q1/Q2/Q3 answers as PROVEN (Q1: no inert rules in scope; Q2: all 6 rule-evaluating APIs share the engine; Q3: read==write for all 7 rules EXCEPT the two recorded divergences); the reschedule no-op short-circuit + RequiredResources NPE divergences with jdwp evidence; any live-adjusts; the get-available-resources "not a subset" correction (with the AvailableResourcesServiceImpl:322 citation) superseding the earlier investigation error; open questions.

- [ ] **Step 2: Handoff** — if the RequiredResources read≠write NPE is not already covered by the existing handoff `~/work/handoff/2026-06-30-262-schedule-npe-crashes-revoman-characterized.md`, append the read-prunes-while-write-crashes framing to it (the read side is new evidence). Update `~/work/handoff/hand-off-log.md`.

- [ ] **Step 3: Holistic whole-suite review** (best review agent, opus) over the full `WfsRulesParityE2ETest` diff — judge code AND scenario faithfulness from all angles: does each matrix row actually prove read==write (control-must-book guardrail present)? are the divergences honestly recorded not hidden? are the cross-API captures reading the right response keys? is the jdwp evidence real? any orphaned Kick / act-ordering hazard / YAML `#` comment that would break Postman import / missing resources.yaml entry? Fold Critical/Important findings via a fix subagent.

- [ ] **Step 4: Mark the spec IMPLEMENTED** (status banner, like the predecessor specs) and commit `docs(wfs): mark rules read==write parity spec IMPLEMENTED`.

## Self-Review (completed)

- **Spec coverage:** 7 rules → Tasks 1 (MatchSkills), 2 (Excluded), 3 (WorkingLocations), 4 (VisitingHours), 5 (StartTimeInterval), 6 (RequiredResources divergence); Availability referenced (Decision 2). All 4 read APIs exercised: get-appointment-slots (Tasks 1-6), get-appointment-candidates + get-available-slots + get-available-resources (Task 7). Both writes: schedule (Tasks 1-5, 7), reschedule (Task 8 no-op + the divergence framing). Q1/Q2/Q3 + both divergences + jdwp + decision log/handoff/review → Task 9. No gaps.
- **Placeholder scan:** every act has an explicit body-shape reference to a concrete repo file + a full capture script; Java methods are complete; the only "verify on first run" items are response-key confirmations for the 3 new read APIs (Task 7) and the StartTimeInterval parameter key (Task 5) — these are genuine live-discovery steps with a named investigation command, not placeholders.
- **Type/name consistency:** env-key naming is uniform (`<rule>Read{Violating,Control}SlotCount`, `<rule>Write{Violating,Control}Status`, `<rule>WriteViolatingErrorCode`); Kick names match their folders; `CollectionsKt.last(rundown).mutableEnv` + `getAsString`/`Integer.parseInt` match the existing suite; `firstUnIgnoredUnsuccessfulStepReport()` halt-guard consistent.
- **Constraints:** no-YAML-comment rule is stated in Global Constraints and repeated in every act-authoring step; resources.yaml update is a step in every task that adds folders; jdwp double-assert is a named step in Tasks 5/6/8 per the user's steer.
