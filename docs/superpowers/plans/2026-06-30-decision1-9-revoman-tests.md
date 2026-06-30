# WFS read↔write parity decisions 1, 1.4, 1.5, 3, 8, 9 — ReVoman tests Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Author ReVoman E2E tests that characterize the live 262 behavior of six WFS read↔write parity decisions, each documenting the 264 contrast in javadoc, under one uniform structure that supersedes the two existing WFS tests.

**Architecture:** Two JUnit test classes in the revoman-root integrationTest source set — `WfsWritePathParityE2ETest` (decisions 1, 1.4, 1.5, 3) and `WfsReadPathParityE2ETest` (decisions 8, 9) — share an extended `ReVomanConfigForWfs` Kick factory. Each independent scenario is its own `ReVoman.revUp(...)` starting with `AUTH_CONFIG`, giving it a fresh env + freshly-timestamped users so the ServiceResource `(RelatedRecordId, ResourceType)` uniqueness collision is structurally impossible (Approach A). Tests stitch loosely-coupled V3 Postman collections and assert against a live WFS workspace org (external-org mode).

**Tech Stack:** Java (integrationTest source set), JUnit 5, Google Truth, ReVoman (`Kick` DSL / `revUp` / `Rundown`), Kotlin stdlib (`CollectionsKt`), V3 Postman collections (`.resources/definition.yaml` + `*.request.yaml`), postman-cli (fast standalone act runs), gradle (`:compileIntegrationTestJava`).

## Global Constraints

- **Target release:** assert **262** (the live workspace org). Each `@Test` javadoc states the 262 verdict asserted AND the 264 contrast (flip to ScheduleError + rule code, or proposed read-path semantics). Verbatim pattern: existing `WfsHelperFitnessE2ETest` javadoc.
- **Isolation (Approach A):** each independent scenario = its own `revUp(...)` starting with `AUTH_CONFIG`. Never share users across scenarios that each create a ServiceResource.
- **Run mode:** external-org only (revoman-root is NOT the core repo → no FTestOrg mode). Iterate via `~/.revoman/config.yaml` → workspace org.
- **Release contract (262):** `SchedulingMethod="OnSite"` everywhere; WorkType has NO SchedulingMethod/IsRegular; schedule body has NO referenceId at top level except where the existing acts include it; composite/graph node URLs RELATIVE; `versionPath` (v67.0) from env.
- **Window ≥ duration invariant:** every booking/schedule act's window must be ≥ the WorkType EstimatedDuration (else slot-gen yields ZERO slots and the verdict is masked). WorkType durations: fitness fixtures 60m, double-book 30m, required-non-required 60m, overlapping-shifts 120m.
- **Act-step `test` scripts stash the verdict** (`*SchedulingStatus` / resource count) into `pm.environment`; the JUnit assertion reads `rundown.firstUnIgnoredUnsuccessfulStepReport() == null` plus Truth env assertions. Act steps carry `x-revoman-ledger: "off"`.
- **Java style:** follow the repo's existing test idiom (it matches the user's functional style) — `final var`, static-import the Kick constants, `CollectionsKt.last(rundown).mutableEnv`.
- **Naming:** kebab collection dirs `schedule-<scenario>-<violating|control>`, `fixtures/<scenario>`, `policies/<rule>-policy`; Kick constants UPPER_SNAKE ending `_CONFIG`.
- **NEVER commit `~/.revoman/config.yaml`** (it holds org creds; it lives in `$HOME`, outside the repo).
- **Tests kept `@Disabled` in-repo** (like the existing two); the `@Disabled` message documents the WFS workspace-org provisioning preconditions.

## Source / target locations

- **Source collections (lift from):** `~/work/revoman/unified-validation-262/postman/collections/`
- **Target collections:** `~/code-clones/work/revoman-root/src/integrationTest/resources/pm-templates/v3/core/wfs/`
- **Test classes:** `~/code-clones/work/revoman-root/src/integrationTest/java/com/salesforce/revoman/integration/core/wfs/`
- **In-scope core source** (read-only, for action-name / SystemMode confirmation): `unified-scheduling-connect-api`, `unified-scheduling-impl`.

## File Structure

- Create: `.../wfs/WfsWritePathParityE2ETest.java` — decisions 1, 1.4, 1.5, 3 (4 `@Test`s).
- Create: `.../wfs/WfsReadPathParityE2ETest.java` — decisions 8, 9 (2 `@Test`s).
- Modify: `.../wfs/ReVomanConfigForWfs.java` — add new Kick constants; prune orphaned ones at the end.
- Delete: `.../wfs/WfsHelperFitnessE2ETest.java`, `.../wfs/WfsDoubleBookHelperE2ETest.java`.
- Lift (new dirs under target): `policies/required-resources-availability-policy`, `fixtures/required-non-required`, `booking/schedule-required-non-required-satisfier-violating`, `booking/schedule-required-satisfier-bookable`, `fixtures/availability-overlapping-shifts`.
- Author (new dirs under target): `booking/schedule-missing-required-flag`, `booking/schedule-single-required-no-primary`, `policies/load-balancing-policy`, `fixtures/load-balancing`, `booking/get-available-resources-limit-zero`, `booking/get-available-resources-limit-positive`, `booking/get-slots-as-manager`, `booking/get-slots-as-admin-control`.
- External (NOT committed): `~/.revoman/config.yaml`.

---

### Task 1: Environment + skeleton scaffolding

**Files:**
- Create: `~/.revoman/config.yaml` (external, not committed)
- Create: `.../wfs/WfsWritePathParityE2ETest.java`
- Create: `.../wfs/WfsReadPathParityE2ETest.java`
- Modify: none yet (`ReVomanConfigForWfs.java` constants come per-decision)

**Interfaces:**
- Produces: two compiling, `@Disabled` test classes with no `@Test` bodies yet; a working `~/.revoman/config.yaml` for external-org runs.

- [ ] **Step 1: Create the external-org config**

Write `~/.revoman/config.yaml` (fill the workspace-org baseUrl/username/password — the org from the handoff):

```yaml
# External-org config for revoman-root integrationTest external-org mode. NOT committed.
baseUrl: "https://orgfarm-4dbef90d6c.my.salesforce-com.ulw8yfxin3z40n159sw1u8b.aa.crm.dev:6101"
username: "<workspace-admin-username>"
password: "<workspace-admin-password>"
```

- [ ] **Step 2: Create the write-path skeleton class**

Create `WfsWritePathParityE2ETest.java`:

```java
/*
 * Copyright 2025 salesforce.com, inc.
 * All Rights Reserved
 * Company Confidential
 */

package com.salesforce.revoman.integration.core.wfs;

import org.junit.jupiter.api.Disabled;

/**
 * WFS read↔write parity write-path characterization (live 262; 264 contrast in each method's javadoc).
 * Supersedes WfsHelperFitnessE2ETest (Decision 1) and WfsDoubleBookHelperE2ETest (Decision 1.5).
 *
 * <p>Decisions covered: 1 (helper fitness), 1.4 (helper can't satisfy a required-resource demand),
 * 1.5 (helper double-books), 3 (missing isRequiredResource flag + the L142 single-required-no-primary
 * control). Each scenario is its own {@code ReVoman.revUp(...)} starting with {@code AUTH_CONFIG}
 * (fresh env + fresh timestamped users → no ServiceResource (RelatedRecordId, ResourceType) collision).
 */
@Disabled(
    "needs a WFS workspace org: multi-resource pref (WorkforceSchdMulResSchdPref) + InBusinessScheduling"
        + " enabled + Shift.Status DynEnum seeded + each Availability rule's ShiftUsage param. See"
        + " ReVomanConfigForWfs.")
class WfsWritePathParityE2ETest {}
```

- [ ] **Step 3: Create the read-path skeleton class**

Create `WfsReadPathParityE2ETest.java`:

```java
/*
 * Copyright 2025 salesforce.com, inc.
 * All Rights Reserved
 * Company Confidential
 */

package com.salesforce.revoman.integration.core.wfs;

import org.junit.jupiter.api.Disabled;

/**
 * WFS read↔write parity read-path characterization (live 262; 264 contrast in each method's javadoc).
 *
 * <p>Decisions covered: 8 (resourceLimitApptDistribution cap — load-balancing read), 9 (Shift
 * sharing-mode split — user-mode SystemMode.NONE shift read vs SFDC_FULL sibling reads). Read-path
 * enforce contract: a rule violation / cap returns EMPTY slots/resources, HTTP 200, NO 400/exception.
 */
@Disabled(
    "needs a WFS workspace org: see ReVomanConfigForWfs. Decision 9 additionally needs Shift OWD=Private"
        + " and a manager persona without sharing on admin-owned Shift rows.")
class WfsReadPathParityE2ETest {}
```

- [ ] **Step 4: Verify compile**

Run: `cd ~/code-clones/work/revoman-root && gradle :compileIntegrationTestJava`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
cd ~/code-clones/work/revoman-root
git add src/integrationTest/java/com/salesforce/revoman/integration/core/wfs/WfsWritePathParityE2ETest.java \
        src/integrationTest/java/com/salesforce/revoman/integration/core/wfs/WfsReadPathParityE2ETest.java
git commit -m "test(wfs): scaffold WfsWritePathParityE2ETest + WfsReadPathParityE2ETest skeletons"
```

---

### Task 2: Decision 1 — non-required helper fitness (4 dims, Approach A)

**Files:**
- Modify: `.../wfs/WfsWritePathParityE2ETest.java` (add `testNonRequiredHelperFitnessE2E`)
- Verify (already in repo): `policies/{excluded-resources-availability,territory-membership-partial,match-skills-non-required,availability-op-hours}-policy`, `fixtures/{excluded,territory,skills,skills-...-skill,working-locations-secondary}-non-required`, `booking/schedule-{excluded,territory-membership,skills,working-locations-secondary}-non-required-violating`
- Modify (if needed): the territory + skills booking acts (window ≥ 60m)

**Interfaces:**
- Consumes: existing Kick constants `AUTH_CONFIG`, `EXCLUDED_RESOURCES_POLICY_CONFIG`, `EXCLUDED_FIXTURE_CONFIG`, `EXCLUDED_SCHEDULE_CONFIG`, `TERRITORY_PARTIAL_POLICY_CONFIG`, `TERRITORY_FIXTURE_CONFIG`, `TERRITORY_SCHEDULE_CONFIG`, `MATCH_SKILLS_POLICY_CONFIG`, `SKILLS_SKILL_FIXTURE_CONFIG`, `SKILLS_FIXTURE_CONFIG`, `SKILLS_SCHEDULE_CONFIG`, `AVAILABILITY_OP_HOURS_POLICY_CONFIG`, `WORKING_LOCATIONS_FIXTURE_CONFIG`, `WORKING_LOCATIONS_SCHEDULE_CONFIG` (all already defined in `ReVomanConfigForWfs`).
- Produces: env keys per dim — `excludedNonReqSchedulingStatus`, `territoryNonReqSchedulingStatus`, `skillsNonReqSchedulingStatus`, `workingLocationsSecondaryNonReqSchedulingStatus` (already stashed by the existing acts' `test` scripts).

- [ ] **Step 1: Verify window ≥ duration on all 4 fitness booking acts**

Run:
```bash
grep -rn 'setUTCHours\|EstimatedDuration\|getTime() +' \
  ~/code-clones/work/revoman-root/src/integrationTest/resources/pm-templates/v3/core/wfs/booking/schedule-territory-membership-non-required-violating/ \
  ~/code-clones/work/revoman-root/src/integrationTest/resources/pm-templates/v3/core/wfs/booking/schedule-skills-non-required-violating/
```
Expected: each beforeRequest window span ≥ 60 min (the fitness WorkTypes are 60m). The excluded + working-locations acts were already fixed to 60m. If territory/skills still use a 30-min window, widen the `end` to `start + 60*60*1000` and add the comment `// * NOTE 2026-06-30 gopalakshintala: window >= WorkType EstimatedDuration (60m) — else slot-gen yields zero slots and masks the fitness verdict.`

- [ ] **Step 2: Write the `@Test` (4 separate revUps, Approach A)**

Add to `WfsWritePathParityE2ETest` (static-import the 14 Kick constants + `assertThat`, `ReVoman`, `CollectionsKt`, `Test`):

```java
  /**
   * Decision 1 — a NON-required "helper" resource is NOT fitness-checked on the Schedule write path,
   * across four dimensions (EXCLUDED / TERRITORY / SKILLS / WORKING-LOCATIONS). Each dimension is a
   * clean required+primary resourceA plus a NON-required resourceB violating exactly one rule.
   *
   * <p>262 (asserted): the helper escapes every fitness rule → each dimension books Success.
   * <p>264 contrast: the helper WOULD be rejected with the matching rule code (ExcludedResources /
   * MatchTerritory / MatchSkills / WorkingLocations) → flip each expected verdict to "ScheduleError".
   *
   * <p>Approach A: each dimension is its own revUp starting with AUTH_CONFIG → fresh env + fresh
   * timestamped users, so the four dimensions' ServiceResource(RelatedRecordId, ResourceType) rows
   * never collide (the SR-uniqueness collision that made the old single-revUp run roll back dims 2-4).
   */
  @Test
  void testNonRequiredHelperFitnessE2E() {
    assertDimensionBooksSuccess(
        "excludedNonReqSchedulingStatus",
        AUTH_CONFIG,
        EXCLUDED_RESOURCES_POLICY_CONFIG,
        EXCLUDED_FIXTURE_CONFIG,
        EXCLUDED_SCHEDULE_CONFIG);
    assertDimensionBooksSuccess(
        "territoryNonReqSchedulingStatus",
        AUTH_CONFIG,
        TERRITORY_PARTIAL_POLICY_CONFIG,
        TERRITORY_FIXTURE_CONFIG,
        TERRITORY_SCHEDULE_CONFIG);
    assertDimensionBooksSuccess(
        "skillsNonReqSchedulingStatus",
        AUTH_CONFIG,
        MATCH_SKILLS_POLICY_CONFIG,
        SKILLS_SKILL_FIXTURE_CONFIG,
        SKILLS_FIXTURE_CONFIG,
        SKILLS_SCHEDULE_CONFIG);
    assertDimensionBooksSuccess(
        "workingLocationsSecondaryNonReqSchedulingStatus",
        AUTH_CONFIG,
        AVAILABILITY_OP_HOURS_POLICY_CONFIG,
        WORKING_LOCATIONS_FIXTURE_CONFIG,
        WORKING_LOCATIONS_SCHEDULE_CONFIG);
  }

  private static void assertDimensionBooksSuccess(
      final String verdictEnvKey, final Kick... configs) {
    final var rundown =
        ReVoman.revUp(
            (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(), configs);
    assertThat(CollectionsKt.last(rundown).mutableEnv).containsEntry(verdictEnvKey, "Success");
  }
```

Add import `com.salesforce.revoman.input.config.Kick;`.

- [ ] **Step 3: Run live (external-org), per dimension first via postman-cli for a fast verdict**

Run each dim standalone to confirm Success before the JUnit run (example for excluded):
```bash
~/.npm-global/bin/postman collection run \
  ~/code-clones/work/revoman-root/src/integrationTest/resources/pm-templates/v3/core/wfs/booking/schedule-excluded-non-required-violating \
  -e ~/work/revoman/unified-validation-262/postman/environments/ws.environment.yaml --insecure
```
Expected: the act's `test` script logs `excludedNonReqSchedulingStatus=Success`.

- [ ] **Step 4: Run the JUnit test against the live org**

Temporarily remove `@Disabled` (or run with the integrationTest task that ignores it), then:
Run: `cd ~/code-clones/work/revoman-root && gradle :integrationTest --tests '*WfsWritePathParityE2ETest.testNonRequiredHelperFitnessE2E'`
Expected: PASS — all four env keys = "Success".

- [ ] **Step 5: Re-add `@Disabled` and commit**

```bash
cd ~/code-clones/work/revoman-root
git add src/integrationTest/java/com/salesforce/revoman/integration/core/wfs/WfsWritePathParityE2ETest.java \
        src/integrationTest/resources/pm-templates/v3/core/wfs/booking/
git commit -m "test(wfs): Decision 1 helper-fitness via per-dimension revUp (Approach A)"
```

---

### Task 3: Decision 1.5 — non-required helper double-books

**Files:**
- Modify: `.../wfs/WfsWritePathParityE2ETest.java` (add `testNonRequiredHelperDoubleBooksE2E`)
- Verify (already in repo): `fixtures/double-book-non-required`, `booking/schedule-double-book-non-required-violating`, `booking/schedule-double-book-required-conflict`, `policies/availability-op-hours-policy`

**Interfaces:**
- Consumes: `AUTH_CONFIG`, `AVAILABILITY_OP_HOURS_POLICY_CONFIG`, `DOUBLE_BOOK_FIXTURE_CONFIG`, `DOUBLE_BOOK_NON_REQUIRED_SCHEDULE_CONFIG`, `DOUBLE_BOOK_REQUIRED_CONFLICT_SCHEDULE_CONFIG` (already defined).
- Produces: env keys `doubleBookNonRequiredSchedulingStatus`, `doubleBookRequiredControlSchedulingStatus` (stashed by the acts).

- [ ] **Step 1: Write the `@Test` (single revUp — A and B share one fixture, no cross-scenario SR collision)**

```java
  /**
   * Decision 1.5 — a NON-required helper is NOT availability-checked, so it may double-book. The A/B
   * flips ONLY isRequiredResource on resourceB over the SAME fixture (resourceB is BUSY at the window).
   *
   * <p>262 (asserted): the busy NON-required helper books Success (no availability check). The
   * REQUIRED control (isRequiredResource=true) is availability-checked → not-Success.
   * <p>264 contrast: the doc flags helper double-book as a gap InField blocks; under 264 the non-required
   * act would also be rejected (not-available) rather than Success.
   */
  @Test
  void testNonRequiredHelperDoubleBooksE2E() {
    final var rundown =
        ReVoman.revUp(
            (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
            AUTH_CONFIG,
            AVAILABILITY_OP_HOURS_POLICY_CONFIG,
            DOUBLE_BOOK_FIXTURE_CONFIG,
            DOUBLE_BOOK_NON_REQUIRED_SCHEDULE_CONFIG,
            DOUBLE_BOOK_REQUIRED_CONFLICT_SCHEDULE_CONFIG);
    final var env = CollectionsKt.last(rundown).mutableEnv;
    assertThat(env).containsEntry("doubleBookNonRequiredSchedulingStatus", "Success");
    assertThat(env.getAsString("doubleBookRequiredControlSchedulingStatus")).isNotEqualTo("Success");
  }
```

- [ ] **Step 2: Run live**

Run: `gradle :integrationTest --tests '*WfsWritePathParityE2ETest.testNonRequiredHelperDoubleBooksE2E'` (with `@Disabled` temporarily off)
Expected: PASS — non-required = "Success", required control ≠ "Success".

- [ ] **Step 3: Commit**

```bash
git add src/integrationTest/java/com/salesforce/revoman/integration/core/wfs/WfsWritePathParityE2ETest.java
git commit -m "test(wfs): Decision 1.5 helper double-books"
```

---

### Task 4: Decision 1.4 — non-required helper cannot satisfy a required-resource demand

**Files:**
- Modify: `.../wfs/ReVomanConfigForWfs.java` (add 4 constants)
- Modify: `.../wfs/WfsWritePathParityE2ETest.java` (add `testNonRequiredHelperCannotSatisfyRequiredDemandE2E`)
- Lift: `policies/required-resources-availability-policy`, `fixtures/required-non-required`, `booking/schedule-required-non-required-satisfier-violating`, `booking/schedule-required-satisfier-bookable`

**Interfaces:**
- Produces (new Kick constants): `REQUIRED_RESOURCES_POLICY_CONFIG`, `REQUIRED_NON_REQUIRED_FIXTURE_CONFIG`, `REQUIRED_NON_REQUIRED_SATISFIER_VIOLATING_SCHEDULE_CONFIG`, `REQUIRED_SATISFIER_BOOKABLE_SCHEDULE_CONFIG`.
- Env keys produced by the lifted acts: violating act → a `requiredNonReq*` verdict status; control act → its own status. (Confirm the exact key names by reading the acts' `test` scripts in Step 2.)

- [ ] **Step 1: Lift the 4 collection dirs**

```bash
SRC=~/work/revoman/unified-validation-262/postman/collections
DST=~/code-clones/work/revoman-root/src/integrationTest/resources/pm-templates/v3/core/wfs
cp -r "$SRC/policies/required-resources-availability-policy" "$DST/policies/"
cp -r "$SRC/fixtures/required-non-required" "$DST/fixtures/"
cp -r "$SRC/booking/schedule-required-non-required-satisfier-violating" "$DST/booking/"
cp -r "$SRC/booking/schedule-required-satisfier-bookable" "$DST/booking/"
```

- [ ] **Step 2: Read the lifted acts' `test` scripts to learn the verdict env-key names**

Run:
```bash
grep -rn 'environment.set\|pm.expect\|errorCode\|schedulingStatus' \
  ~/code-clones/work/revoman-root/src/integrationTest/resources/pm-templates/v3/core/wfs/booking/schedule-required-non-required-satisfier-violating/ \
  ~/code-clones/work/revoman-root/src/integrationTest/resources/pm-templates/v3/core/wfs/booking/schedule-required-satisfier-bookable/
```
Expected: the env keys the acts stash (e.g. a `requiredNonReqSatisfier*` status / errorCode). Record them for Step 4.

- [ ] **Step 3: Add the Kick constants to `ReVomanConfigForWfs`**

In `ReVomanConfigForWfs.java`, under the policies / fixtures / schedule sections:

```java
  // ## Decision 1.4 — required-resource demand (account ResourcePreference) cannot be satisfied by a
  // NON-required helper.
  static final Kick REQUIRED_RESOURCES_POLICY_CONFIG =
      kickFor(V3_WFS_PATH + "policies/required-resources-availability-policy");
  static final Kick REQUIRED_NON_REQUIRED_FIXTURE_CONFIG =
      kickFor(V3_WFS_PATH + "fixtures/required-non-required");
  static final Kick REQUIRED_NON_REQUIRED_SATISFIER_VIOLATING_SCHEDULE_CONFIG =
      kickFor(V3_WFS_PATH + "booking/schedule-required-non-required-satisfier-violating");
  static final Kick REQUIRED_SATISFIER_BOOKABLE_SCHEDULE_CONFIG =
      kickFor(V3_WFS_PATH + "booking/schedule-required-satisfier-bookable");
```

- [ ] **Step 4: Characterize live — run the violating act standalone, capture the 262 verdict**

```bash
~/.npm-global/bin/postman collection run \
  ~/code-clones/work/revoman-root/src/integrationTest/resources/pm-templates/v3/core/wfs/booking/schedule-required-non-required-satisfier-violating \
  -e ~/work/revoman/unified-validation-262/postman/environments/ws.environment.yaml --insecure
```
Run AUTH + policy + fixture first (or use the JUnit harness) so the env vars resolve. Expected per the act's description: `ScheduleError` with `errorCode=RequiredResources`, SA NOT scheduled. **Record the exact errorCode/status observed** — that observed value is what the assertion encodes (do not guess).

- [ ] **Step 5: Write the `@Test` encoding the observed verdict**

Use the env-key names from Step 2 and the observed verdict from Step 4. Template (adjust key names + expected value to what you recorded):

```java
  /**
   * Decision 1.4 — a NON-required helper CANNOT satisfy an account's required-resource demand
   * (ResourcePreference Required). resourceA (required+primary) is clean but NOT on the required list;
   * resourceB IS on the required list but is assigned NON-required.
   *
   * <p>262 (asserted): the RequiredResources satisfaction rule evaluates over REQUIRED resources only,
   * so the non-required resourceB does not count → ScheduleError errorCode=RequiredResources. Control
   * (flip resourceB to isRequiredResource=true) → no RequiredResources error.
   * <p>264 contrast: same — 1.4 confirms a helper can't satisfy a required-resource demand (no flip).
   */
  @Test
  void testNonRequiredHelperCannotSatisfyRequiredDemandE2E() {
    // Violating: only a NON-required helper present for the account's required demand.
    final var violatingRundown =
        ReVoman.revUp(
            (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
            AUTH_CONFIG,
            REQUIRED_RESOURCES_POLICY_CONFIG,
            REQUIRED_NON_REQUIRED_FIXTURE_CONFIG,
            REQUIRED_NON_REQUIRED_SATISFIER_VIOLATING_SCHEDULE_CONFIG);
    // <ASSERT the recorded verdict env key == observed value, e.g. errorCode "RequiredResources">
    // Control: a genuine required satisfier → no RequiredResources error (fresh AUTH per Approach A).
    final var controlRundown =
        ReVoman.revUp(
            (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
            AUTH_CONFIG,
            REQUIRED_RESOURCES_POLICY_CONFIG,
            REQUIRED_NON_REQUIRED_FIXTURE_CONFIG,
            REQUIRED_SATISFIER_BOOKABLE_SCHEDULE_CONFIG);
    // <ASSERT the control env key shows NO RequiredResources error>
  }
```

Replace the `<ASSERT ...>` comment lines with the actual Truth assertions using the env keys recorded in Step 2 and the value observed in Step 4 (e.g. `assertThat(env).containsEntry("requiredNonReqSatisfierErrorCode", "RequiredResources");`).

- [ ] **Step 6: Run the JUnit test live and confirm green**

Run: `gradle :integrationTest --tests '*WfsWritePathParityE2ETest.testNonRequiredHelperCannotSatisfyRequiredDemandE2E'`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/integrationTest/java/com/salesforce/revoman/integration/core/wfs/ReVomanConfigForWfs.java \
        src/integrationTest/java/com/salesforce/revoman/integration/core/wfs/WfsWritePathParityE2ETest.java \
        src/integrationTest/resources/pm-templates/v3/core/wfs/policies/required-resources-availability-policy \
        src/integrationTest/resources/pm-templates/v3/core/wfs/fixtures/required-non-required \
        src/integrationTest/resources/pm-templates/v3/core/wfs/booking/schedule-required-non-required-satisfier-violating \
        src/integrationTest/resources/pm-templates/v3/core/wfs/booking/schedule-required-satisfier-bookable
git commit -m "test(wfs): Decision 1.4 helper cannot satisfy required-resource demand"
```

---

### Task 5: Decision 3 — missing isRequiredResource flag + L142 single-required-no-primary control

**Files:**
- Author: `booking/schedule-missing-required-flag/10-schedule-missing-required-flag.request.yaml` (+ `.resources/definition.yaml`)
- Author: `booking/schedule-single-required-no-primary/10-schedule-single-required-no-primary.request.yaml` (+ `.resources/definition.yaml`)
- Modify: `.../wfs/ReVomanConfigForWfs.java` (add 2 constants)
- Modify: `.../wfs/WfsWritePathParityE2ETest.java` (add `testMissingRequiredFlagE2E`)
- Reuse fixture: `fixtures/required-non-required` (has clean resourceA + an Account) — or `fixtures/double-book-non-required`; pick the one whose resourceA is fully available at the window.

**Interfaces:**
- Produces (new Kick constants): `MISSING_REQUIRED_FLAG_SCHEDULE_CONFIG`, `SINGLE_REQUIRED_NO_PRIMARY_SCHEDULE_CONFIG`.
- Produces env keys: `missingRequiredFlagStatus` / `missingRequiredFlagErrorCode` (Act A), `singleRequiredNoPrimaryStatus` (Act B) — set by the new acts' `test` scripts.

- [ ] **Step 1: Author Act A — schedule omitting `isRequiredResource` entirely**

Copy the structure of an existing single-resource clean schedule act (the get-slots act body shape, but POST to `/connect/unified-scheduling/actions/schedule`). The assignedResource OMITS `isRequiredResource` AND `isPrimaryResource`. Use the `required-non-required` fixture's resourceA + account/worktype/territory env keys. Create `booking/schedule-missing-required-flag/.resources/definition.yaml` (copy from any existing booking act's `.resources/definition.yaml` — bearer `{{adminToken}}`) and `10-schedule-missing-required-flag.request.yaml`:

```yaml
$kind: http-request
description: >-
  Decision 3 — schedules a SINGLE assigned resource that OMITS the isRequiredResource field entirely
  (and isPrimaryResource). On 262 this crashed / errored on the missing-flag path; this act captures the
  live 262 failure shape. On 264 a missing isRequiredResource is treated as not-required and handled
  cleanly. Window 11:00-12:00 UTC (>= the 60m WorkType EstimatedDuration).
url: "{{baseUrl}}{{versionPath}}/connect/unified-scheduling/actions/schedule"
method: POST
headers:
  Content-Type: application/json
  Accept: application/json
  x-revoman-ledger: "off"
  ignoreHTTPStatusUnsuccessful: "true"
body:
  type: json
  content: |-
    {
      "appointments": [
        {
          "schedulingMethod": "OnSite",
          "schedulingPolicyId": "{{requiredResourcesPolicyId}}",
          "coreDetails": {
            "startTime": "{{missingFlagBookingStart}}",
            "endTime": "{{missingFlagBookingEnd}}",
            "status": "Scheduled",
            "subject": "Decision 3 missing isRequiredResource probe",
            "workConfiguration": { "workTypeId": "{{requiredNonReqWorkTypeId}}" },
            "locationConstraints": { "id": "{{requiredNonReqTerritoryId}}" }
          },
          "serviceAppointmentParent": { "id": "{{requiredNonReqAccountId}}" },
          "assignedResources": [
            { "serviceResourceId": "{{requiredNonReqResourceAId}}" }
          ]
        }
      ]
    }
scripts:
  - type: beforeRequest
    code: |-
      const start = new Date();
      start.setUTCDate(start.getUTCDate() + 1);
      start.setUTCHours(11, 0, 0, 0);
      const end = new Date(start.getTime() + 60 * 60 * 1000);
      pm.environment.set("missingFlagBookingStart", start.toISOString());
      pm.environment.set("missingFlagBookingEnd", end.toISOString());
    language: text/javascript
  - type: afterResponse
    code: |-
      // Capture the live 262 verdict shape without prejudging it: stash status + any errorCode.
      var data = pm.response.json();
      try { pm.environment.set("missingRequiredFlagStatus", data[0] && data[0].schedulingStatus); } catch (e) {}
      try { pm.environment.set("missingRequiredFlagErrorCode",
            data[0] && data[0].errors && data[0].errors[0] && data[0].errors[0].errorCode); } catch (e) {}
    language: text/javascript
order: 1000
```

(The `ignoreHTTPStatusUnsuccessful` header lets a non-2xx response through so the test can characterize a crash; adjust the afterResponse parsing once the real response shape is observed in Step 4.)

- [ ] **Step 2: Author Act B — single isRequiredResource=true, NO isPrimaryResource, FRESH resources**

Create `booking/schedule-single-required-no-primary/.resources/definition.yaml` + `10-schedule-single-required-no-primary.request.yaml`. Same body as Act A but the assignedResource is `{ "isRequiredResource": true, "serviceResourceId": "{{requiredNonReqResourceAId}}" }` (required, no primary). Stash `singleRequiredNoPrimaryStatus`:

```yaml
  - type: afterResponse
    code: |-
      var data = pm.response.json();
      pm.environment.set("singleRequiredNoPrimaryStatus", data[0] && data[0].schedulingStatus);
    language: text/javascript
```

Description: `Decision 3 / doc L142 — a SINGLE assigned resource with isRequiredResource=true and NO isPrimaryResource MUST be a valid Schedule (isPrimary is multi-resource-only plumbing). FRESH resources per Approach A so prior bookings don't pollute availability.`

- [ ] **Step 3: Add the 2 Kick constants to `ReVomanConfigForWfs`**

```java
  // ## Decision 3 — missing isRequiredResource flag (crash characterization) + the L142 control.
  static final Kick MISSING_REQUIRED_FLAG_SCHEDULE_CONFIG =
      kickFor(V3_WFS_PATH + "booking/schedule-missing-required-flag");
  static final Kick SINGLE_REQUIRED_NO_PRIMARY_SCHEDULE_CONFIG =
      kickFor(V3_WFS_PATH + "booking/schedule-single-required-no-primary");
```

- [ ] **Step 4: Characterize live — run Act A standalone, observe the actual 262 failure**

Run AUTH + policy + fixture + Act A through the JUnit harness (Step 6 skeleton) or postman-cli. **Record the exact HTTP status, schedulingStatus, and errorCode/message.** This is the value the assertion encodes. (If 262 returns a structured error rather than a hard crash, the assertion checks that errorCode; if it is a 5xx/exception, assert the HTTP status + message fragment.)

- [ ] **Step 5: Verify Act B books Success on fresh resources**

Run AUTH + policy + fixture + Act B. Expected: `singleRequiredNoPrimaryStatus == "Success"` (the L142 contract). If it does NOT, the fixture's resourceA availability is the suspect (per L142's pollution note) — confirm resourceA has a Confirmed Shift + member OH covering the window and is otherwise unused.

- [ ] **Step 6: Write the `@Test`**

```java
  /**
   * Decision 3 — a missing isRequiredResource flag. Act A omits isRequiredResource (and
   * isPrimaryResource) entirely; on 262 this hit the missing-flag failure path. Act B (doc L142
   * control) sends a single isRequiredResource=true with NO isPrimaryResource on FRESH resources, which
   * MUST be a valid Schedule (isPrimary is multi-resource-only plumbing).
   *
   * <p>262 (asserted): Act A → the recorded 262 failure shape; Act B → Success.
   * <p>264 contrast: Act A → missing-treated-as-not-required, handled cleanly (no crash).
   */
  @Test
  void testMissingRequiredFlagE2E() {
    final var missingFlagRundown =
        ReVoman.revUp(
            (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
            AUTH_CONFIG,
            REQUIRED_RESOURCES_POLICY_CONFIG,
            REQUIRED_NON_REQUIRED_FIXTURE_CONFIG,
            MISSING_REQUIRED_FLAG_SCHEDULE_CONFIG);
    // <ASSERT the recorded Act A 262 failure: e.g. missingRequiredFlagErrorCode == "<observed>">
    final var l142Rundown =
        ReVoman.revUp(
            (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
            AUTH_CONFIG,
            REQUIRED_RESOURCES_POLICY_CONFIG,
            REQUIRED_NON_REQUIRED_FIXTURE_CONFIG,
            SINGLE_REQUIRED_NO_PRIMARY_SCHEDULE_CONFIG);
    assertThat(CollectionsKt.last(l142Rundown).mutableEnv)
        .containsEntry("singleRequiredNoPrimaryStatus", "Success");
  }
```

Replace `<ASSERT ...>` with the Truth assertion encoding the value recorded in Step 4.

- [ ] **Step 7: Run the JUnit test live and commit**

Run: `gradle :integrationTest --tests '*WfsWritePathParityE2ETest.testMissingRequiredFlagE2E'` → PASS.
```bash
git add src/integrationTest/java/com/salesforce/revoman/integration/core/wfs/ReVomanConfigForWfs.java \
        src/integrationTest/java/com/salesforce/revoman/integration/core/wfs/WfsWritePathParityE2ETest.java \
        src/integrationTest/resources/pm-templates/v3/core/wfs/booking/schedule-missing-required-flag \
        src/integrationTest/resources/pm-templates/v3/core/wfs/booking/schedule-single-required-no-primary
git commit -m "test(wfs): Decision 3 missing-required-flag crash + L142 single-required-no-primary control"
```

---

### Task 6: Decision 8 — resourceLimitApptDistribution cap (read-path boundary pair)

**Files:**
- Author: `policies/load-balancing-policy/` (a default OnSite load-balancing policy — Availability(C)+ShiftUsage rule, NO RequiredResources rule)
- Author: `fixtures/load-balancing/` (≥2 eligible resources sharing one territory/worktype/account)
- Author: `booking/get-available-resources-limit-zero/`, `booking/get-available-resources-limit-positive/`
- Modify: `.../wfs/ReVomanConfigForWfs.java` (add 4 constants)
- Modify: `.../wfs/WfsReadPathParityE2ETest.java` (add `testResourceLimitApptDistributionCapE2E`)

**Interfaces:**
- Produces (new Kick constants): `LOAD_BALANCING_POLICY_CONFIG`, `LOAD_BALANCING_FIXTURE_CONFIG`, `GET_RESOURCES_LIMIT_ZERO_CONFIG`, `GET_RESOURCES_LIMIT_POSITIVE_CONFIG`.
- Produces env keys: `limitZeroResourceCount` (int, expected 0), `limitPositiveResourceCount` (int, expected > 0).

- [ ] **Step 1: Discover the GetAvailableResources action endpoint + the AuxiliaryDetails/resourceLimitApptDistribution input shape**

Run (in-scope core source, read-only):
```bash
grep -rni 'get-available-resources\|getAvailableResources\|resourceLimitApptDistribution\|AuxiliaryDetails' \
  /opt/workspace/core-public/core/unified-scheduling-connect-api/ | head -40
```
Record: the exact action URL (e.g. `/connect/unified-scheduling/actions/get-available-resources`), the request wrapper that nests `AuxiliaryDetails`, and the response field that lists resources (to count them). Confirm `findLeastUtilizedResources` is reached only on the **load-balancing** path (no named required resource), per `AppointmentDistributionService`.

- [ ] **Step 2: Author the load-balancing policy**

Copy `policies/availability-op-hours-policy` as the base (it already has Availability(C)+ShiftUsage). Adjust so it is the default OnSite load-balancing policy (no RequiredResources rule; whatever the discovery in Step 1 shows triggers the `findLeastUtilizedResources` path). Place under `policies/load-balancing-policy/`, stash `loadBalancingPolicyId`.

- [ ] **Step 3: Author the fixture with ≥2 eligible resources**

Base on `fixtures/required-non-required` (it already builds 2 User-backed ServiceResources on distinct users, a territory, a 60m WorkType, an account, Confirmed Shifts). Drop the ResourcePreference(Required) link (we want a plain load-balancing pool, not a required demand). Both resources must be eligible at the probe window. Stash `loadBalancingTerritoryId`, `loadBalancingWorkTypeId`, `loadBalancingAccountId` and the resource ids. Place under `fixtures/load-balancing/`.

- [ ] **Step 4: Author Act 0 — limit=0 → empty**

`booking/get-available-resources-limit-zero/10-get-available-resources-limit-zero.request.yaml`, POST to the action URL from Step 1, body nests `resourceLimitApptDistribution: 0` under the `AuxiliaryDetails` wrapper. afterResponse stashes the resource count:
```yaml
  - type: afterResponse
    code: |-
      var data = pm.response.json();
      // <count the resources in the response per the field discovered in Step 1>
      pm.environment.set("limitZeroResourceCount", /* resource array length */ 0);
    language: text/javascript
```
Window ≥ 60m. `x-revoman-ledger: "off"`.

- [ ] **Step 5: Author Act N — explicit limit above seeded count → resources returned**

`booking/get-available-resources-limit-positive/...`, identical but `resourceLimitApptDistribution: 50` (well above the 2 seeded). afterResponse stashes `limitPositiveResourceCount`.

- [ ] **Step 6: Add the 4 Kick constants to `ReVomanConfigForWfs`**

```java
  // ## Decision 8 — resourceLimitApptDistribution cap on the load-balancing read path (read-only).
  static final Kick LOAD_BALANCING_POLICY_CONFIG =
      kickFor(V3_WFS_PATH + "policies/load-balancing-policy");
  static final Kick LOAD_BALANCING_FIXTURE_CONFIG =
      kickFor(V3_WFS_PATH + "fixtures/load-balancing");
  static final Kick GET_RESOURCES_LIMIT_ZERO_CONFIG =
      kickFor(V3_WFS_PATH + "booking/get-available-resources-limit-zero");
  static final Kick GET_RESOURCES_LIMIT_POSITIVE_CONFIG =
      kickFor(V3_WFS_PATH + "booking/get-available-resources-limit-positive");
```

- [ ] **Step 7: Characterize live — run both acts, confirm 0 vs positive**

Run AUTH + policy + fixture + both acts (one revUp). Expected: `limitZeroResourceCount == 0`, `limitPositiveResourceCount > 0`. If `limitZero` is NOT 0, re-check Step 1 (is this actually the load-balancing path? a named-required-resource request bypasses `findLeastUtilizedResources`).

- [ ] **Step 8: Write the `@Test`**

```java
  /**
   * Decision 8 — resourceLimitApptDistribution caps the load-balancing resource list AFTER the
   * per-resource rules filter candidates (read-only presentation cap, no write-path counterpart).
   *
   * <p>262 (asserted): limit=0 → empty resource list (AppointmentDistributionService
   * .findLeastUtilizedResources: 0 → Stream.limit(0) → empty); an explicit positive limit above the
   * seeded count → resources returned. Proves 0 is a literal cap-of-0, not "no limit".
   * <p>264 contrast: if product picks option A ("no cap"), 0/negative → all eligible resources (the
   * surprising empty list on the default policy goes away).
   */
  @Test
  void testResourceLimitApptDistributionCapE2E() {
    final var rundown =
        ReVoman.revUp(
            (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
            AUTH_CONFIG,
            LOAD_BALANCING_POLICY_CONFIG,
            LOAD_BALANCING_FIXTURE_CONFIG,
            GET_RESOURCES_LIMIT_ZERO_CONFIG,
            GET_RESOURCES_LIMIT_POSITIVE_CONFIG);
    final var env = CollectionsKt.last(rundown).mutableEnv;
    assertThat(env.getAsString("limitZeroResourceCount")).isEqualTo("0");
    assertThat(Integer.parseInt(env.getAsString("limitPositiveResourceCount"))).isGreaterThan(0);
  }
```

- [ ] **Step 9: Run live and commit**

Run: `gradle :integrationTest --tests '*WfsReadPathParityE2ETest.testResourceLimitApptDistributionCapE2E'` → PASS.
```bash
git add src/integrationTest/java/com/salesforce/revoman/integration/core/wfs/ReVomanConfigForWfs.java \
        src/integrationTest/java/com/salesforce/revoman/integration/core/wfs/WfsReadPathParityE2ETest.java \
        src/integrationTest/resources/pm-templates/v3/core/wfs/policies/load-balancing-policy \
        src/integrationTest/resources/pm-templates/v3/core/wfs/fixtures/load-balancing \
        src/integrationTest/resources/pm-templates/v3/core/wfs/booking/get-available-resources-limit-zero \
        src/integrationTest/resources/pm-templates/v3/core/wfs/booking/get-available-resources-limit-positive
git commit -m "test(wfs): Decision 8 resourceLimitApptDistribution cap boundary pair (read-path)"
```

---

### Task 7: Decision 9 — Shift sharing-mode split (cross-persona repro)

**Files:**
- Lift: `fixtures/availability-overlapping-shifts` (admin creates the resource + Shift rows)
- Author: `booking/get-slots-as-manager/` (GetSlots executed under `{{managerToken}}`)
- Author: `booking/get-slots-as-admin-control/` (same GetSlots under `{{adminToken}}`)
- Modify: `.../wfs/ReVomanConfigForWfs.java` (add 3 constants)
- Modify: `.../wfs/WfsReadPathParityE2ETest.java` (add `testShiftSharingModeSplitE2E`)

**Interfaces:**
- Produces (new Kick constants): `OVERLAPPING_SHIFTS_FIXTURE_CONFIG`, `GET_SLOTS_AS_MANAGER_CONFIG`, `GET_SLOTS_AS_ADMIN_CONTROL_CONFIG`.
- Produces env keys: `managerSlotCount` (expected 0), `adminSlotCount` (expected > 0).

- [ ] **Step 1: Confirm the manager-token execution mechanism for a V3 act**

Read an existing act's `.resources/definition.yaml` (auth block uses `{{adminToken}}`). Determine how to run a single act under `{{managerToken}}`: a per-folder `.resources/definition.yaml` with `auth.credentials.token: "{{managerToken}}"`. Confirm ReVoman applies the folder-level auth. Record the mechanism. (The env already carries `managerToken`, minted by the `auth/login-as-sysadmin` step.)

- [ ] **Step 2: Confirm Shift OWD = Private on the workspace org**

Run (via the token-mint helper / a quick REST query, or psql) to check the Shift object's org-wide default sharing. If Shift is Public Read, the manager would see admin shifts and the repro shows nothing — in that case set Shift OWD to Private on the workspace org (setup step, documented in the `@Disabled` message). Record the OWD state.

- [ ] **Step 3: Lift the overlapping-shifts fixture**

```bash
SRC=~/work/revoman/unified-validation-262/postman/collections
DST=~/code-clones/work/revoman-root/src/integrationTest/resources/pm-templates/v3/core/wfs
cp -r "$SRC/fixtures/availability-overlapping-shifts" "$DST/fixtures/"
```
Note: this fixture creates its OWN resource on a self-created user with admin-owned Shift rows — exactly the admin-owned shift rows the manager must NOT see. Stash keys: `overlappingShift*` (territory, worktype, account, resource, shift ids). Confirm by reading the lifted graph's afterResponse.

- [ ] **Step 4: Author the manager-mode GetSlots act**

`booking/get-slots-as-manager/.resources/definition.yaml` with `auth.credentials.token: "{{managerToken}}"`, and `10-get-slots-as-manager.request.yaml` POST to `/connect/unified-scheduling/actions/get-appointment-slots`, body targets the overlapping-shifts resource/worktype/account/territory over a window inside the shifts (e.g. tomorrow 12:00-14:00 UTC, ≥ the 120m WorkType — use 12:00-14:00 = 120m). afterResponse stashes `managerSlotCount`:
```yaml
  - type: afterResponse
    code: |-
      var data = pm.response.json();
      // GetSlots returns 200 with EMPTY slots when the manager can't see the admin-owned Shift rows
      // (user-mode SystemMode.NONE shift read). Count the slots in the response.
      pm.environment.set("managerSlotCount", /* slots array length per response shape */ 0);
    language: text/javascript
```
`x-revoman-ledger: "off"`. The act must assert HTTP 200 (no error) — the contract is silent-empty, not an exception.

- [ ] **Step 5: Author the admin-control GetSlots act**

`booking/get-slots-as-admin-control/` — identical body, `.resources/definition.yaml` uses `{{adminToken}}` (the shift owner, full visibility). afterResponse stashes `adminSlotCount`. This proves the empty manager result is the sharing gate, not an empty fixture.

- [ ] **Step 6: Add the 3 Kick constants**

```java
  // ## Decision 9 — Shift sharing-mode split: user-mode (SystemMode.NONE) SHIFT read gates availability
  // on the booking user's sharing of Shift rows, while sibling reads run SFDC_FULL.
  static final Kick OVERLAPPING_SHIFTS_FIXTURE_CONFIG =
      kickFor(V3_WFS_PATH + "fixtures/availability-overlapping-shifts");
  static final Kick GET_SLOTS_AS_MANAGER_CONFIG =
      kickFor(V3_WFS_PATH + "booking/get-slots-as-manager");
  static final Kick GET_SLOTS_AS_ADMIN_CONTROL_CONFIG =
      kickFor(V3_WFS_PATH + "booking/get-slots-as-admin-control");
```

- [ ] **Step 7: Characterize live**

Run AUTH (creates the manager persona) + fixture (admin creates the shifts) + manager act + admin control (one revUp; the fixture creates a fresh resource, so no SR collision with other decisions). Expected: `managerSlotCount == 0` (HTTP 200, no error), `adminSlotCount > 0`. If `managerSlotCount > 0`, the sharing gap didn't bite — re-check Step 2 (Shift OWD) and that the manager truly lacks a share on the admin shifts.

- [ ] **Step 8: Write the `@Test`**

```java
  /**
   * Decision 9 — the SHIFT availability read runs in user mode (SystemMode.NONE, respects caller
   * sharing) while sibling resource/absence/event reads run SFDC_FULL. A manager who lacks sharing on
   * admin-owned Shift rows silently gets empty availability / no slots, with NO error.
   *
   * <p>262 (asserted): manager-mode GetSlots → 0 slots, HTTP 200, no error; the same GetSlots as the
   * shift owner (admin) → slots returned (the empty result is the sharing gate, not an empty fixture).
   * <p>264 contrast: option A documents the gate as a contract (optionally surface a reason instead of
   * silent-empty); option B aligns the modes so the manager would also see slots.
   */
  @Test
  void testShiftSharingModeSplitE2E() {
    final var rundown =
        ReVoman.revUp(
            (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
            AUTH_CONFIG,
            OVERLAPPING_SHIFTS_FIXTURE_CONFIG,
            GET_SLOTS_AS_MANAGER_CONFIG,
            GET_SLOTS_AS_ADMIN_CONTROL_CONFIG);
    final var env = CollectionsKt.last(rundown).mutableEnv;
    assertThat(env.getAsString("managerSlotCount")).isEqualTo("0");
    assertThat(Integer.parseInt(env.getAsString("adminSlotCount"))).isGreaterThan(0);
  }
```

- [ ] **Step 9: Run live and commit**

Run: `gradle :integrationTest --tests '*WfsReadPathParityE2ETest.testShiftSharingModeSplitE2E'` → PASS.
```bash
git add src/integrationTest/java/com/salesforce/revoman/integration/core/wfs/ReVomanConfigForWfs.java \
        src/integrationTest/java/com/salesforce/revoman/integration/core/wfs/WfsReadPathParityE2ETest.java \
        src/integrationTest/resources/pm-templates/v3/core/wfs/fixtures/availability-overlapping-shifts \
        src/integrationTest/resources/pm-templates/v3/core/wfs/booking/get-slots-as-manager \
        src/integrationTest/resources/pm-templates/v3/core/wfs/booking/get-slots-as-admin-control
git commit -m "test(wfs): Decision 9 Shift sharing-mode split (cross-persona repro)"
```

---

### Task 8: Delete superseded tests + prune orphaned config + final verification

**Files:**
- Delete: `.../wfs/WfsHelperFitnessE2ETest.java`, `.../wfs/WfsDoubleBookHelperE2ETest.java`
- Modify: `.../wfs/ReVomanConfigForWfs.java` (prune any constant no longer referenced; update the class javadoc to list all six decisions)

**Interfaces:**
- Consumes: nothing new.
- Produces: a clean compiling integrationTest source set with only the two new test classes.

- [ ] **Step 1: Delete the two superseded test classes**

```bash
cd ~/code-clones/work/revoman-root
git rm src/integrationTest/java/com/salesforce/revoman/integration/core/wfs/WfsHelperFitnessE2ETest.java \
       src/integrationTest/java/com/salesforce/revoman/integration/core/wfs/WfsDoubleBookHelperE2ETest.java
```

- [ ] **Step 2: Find orphaned Kick constants**

Run:
```bash
cd ~/code-clones/work/revoman-root
for c in $(grep -oE 'static final Kick [A-Z_]+' src/integrationTest/java/com/salesforce/revoman/integration/core/wfs/ReVomanConfigForWfs.java | awk '{print $4}'); do
  n=$(grep -rn "$c" src/integrationTest/java/com/salesforce/revoman/integration/core/wfs/Wfs*.java | wc -l)
  echo "$n  $c"
done
```
Expected: every constant has ≥1 reference. Any with `0` is orphaned — remove its declaration from `ReVomanConfigForWfs.java`. (Note: `DOUBLE_BOOK_*` and the fitness constants are still used by the new tests, so they stay.)

- [ ] **Step 3: Update the `ReVomanConfigForWfs` class javadoc**

Update the top-of-class javadoc so it lists all six decisions (1, 1.4, 1.5, 3, 8, 9) the config now serves, not just "Decision-1 non-required helper".

- [ ] **Step 4: Verify compile**

Run: `gradle :compileIntegrationTestJava`
Expected: `BUILD SUCCESSFUL` (no references to the deleted classes).

- [ ] **Step 5: Full live run of both new classes**

Run (with `@Disabled` temporarily off): `gradle :integrationTest --tests '*WfsWritePathParityE2ETest' --tests '*WfsReadPathParityE2ETest'`
Expected: all 6 `@Test`s PASS against the live workspace org. Re-add `@Disabled` after confirming.

- [ ] **Step 6: Commit**

```bash
git add -A src/integrationTest/java/com/salesforce/revoman/integration/core/wfs/
git commit -m "test(wfs): delete superseded fitness/double-book tests, prune orphaned config"
```

---

## Self-Review

**Spec coverage:**
- Decision 1 → Task 2. Decision 1.4 → Task 4. Decision 1.5 → Task 3. Decision 3 (+ L142) → Task 5. Decision 8 → Task 6. Decision 9 → Task 7. ✓
- Q3 two-class structure → Tasks 1/2/3/4/5 (write) + 6/7 (read). ✓
- Q4 boundary pair → Task 6 Acts 0/N. ✓
- Q5 full cross-persona → Task 7 manager + admin control. ✓
- Q6 crash + L142 control → Task 5 Acts A/B. ✓
- Approach A (per-scenario fresh AUTH) → Tasks 2 (4 revUps), 4/5 (separate violating/control revUps). ✓
- Harness fix window≥duration → Task 2 Step 1; per-dimension fresh users → Approach A throughout. ✓
- Supersede + delete old tests → Task 8. ✓
- External-org run mode → Task 1 Step 1 (`~/.revoman/config.yaml`). ✓

**Live-confirm items** carried as explicit plan steps (not placeholders): Dec 3/1.4 failure shape = Task 4 Step 4 / Task 5 Step 4 (characterize, then encode observed value); Dec 8 action name = Task 6 Step 1; Dec 9 manager-token mechanism = Task 7 Step 1; Dec 9 Shift OWD = Task 7 Step 2.

**Deliberate write-time placeholders:** the `<ASSERT ...>` lines in Tasks 4 and 5, and the response-count parsing in Tasks 6/7 afterResponse scripts, are intentionally encoded-from-observation (characterization tests cannot assert a value not yet observed). Each is paired with the exact preceding step that produces the value to fill in. This is the honest TDD-equivalent for live characterization, not a vague TODO.

**Type consistency:** `assertDimensionBooksSuccess(String, Kick...)` defined Task 2, used only Task 2. Env-key names are produced by the acts and consumed by the matching `@Test` in the same task. `CollectionsKt.last(rundown).mutableEnv` + `.getAsString(...)` / `.containsEntry(...)` used consistently (matches the existing `WfsDoubleBookHelperE2ETest`). Kick constant names match between `ReVomanConfigForWfs` declarations and the static imports in each test.
