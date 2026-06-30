# WFS Decisions 2/4/4z/5 ReVoman Tests — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add live ReVoman integrationTest characterization for the four remaining WFS read↔write parity decisions (2, 4, 4z, 5), in the existing `core/wfs` harness.

**Architecture:** Each decision is one JUnit test method that runs a `ReVoman.revUp(...)` pipeline of V3 Postman collection folders (`AUTH_CONFIG` → existing fixture/policy → new booking acts), captures each act's verdict into the env via an `afterResponse` script, and asserts the observed 262 verdict with Google-Truth. New work is per-scenario **booking act folders** + `Kick` constants + test methods. Reuses the in-repo `required-non-required` fixture and `availability-op-hours-policy` (no new fixture/policy lift).

**Tech Stack:** Java 21 (integrationTest source set), JUnit 5, Google Truth, ReVoman (this repo's lib), V3 Postman collections (YAML), Salesforce unified-scheduling Connect API (v67 REST, v64 SOAP login), live workspace org via `~/.revoman/config.yaml`.

## Global Constraints

- **No changes to `/opt/workspace/core-public/core`.** Bugs are characterized, not fixed; out-of-scope bugs handed off to `~/work/handoff` per `~/work/handoff/CLAUDE.md`.
- **Assert 262, document 264 contrast in every test method's javadoc.** (Suite contract.)
- **REST on dynamic `versionPath` (resolves to v67); SOAP persona login at v64.**
- **Every act under assertion carries `x-revoman-ledger: "off"`** (its response IS the verdict; must dispatch fresh, never replay a cached value).
- **Acts expected to return non-2xx carry `ignoreHTTPStatusUnsuccessful: "true"`** (so `haltOnFailureOfTypeExcept(HTTP_STATUS, ...)` does not halt the run and the verdict is read from the captured value, not HTTP).
- **One `revUp` per test, each starting with `AUTH_CONFIG`** → fresh manager persona → fresh timestamped `ServiceResource(RelatedRecordId, ResourceType)` rows → no cross-test SR-uniqueness collision (Approach A).
- **All collection folders run under `{{managerToken}}`** (the real least-privilege manager persona minted by `AUTH_CONFIG`). Resource-owner Users stay admin-created inside the fixture graph (manager cannot set `ProfileId`).
- **Capture scripts handle BOTH error shapes** — top-level array `[{errorCode, message}]` AND `appointments[0].errors[0]{errorCode, message}` — plus `appointments[0].schedulingStatus`.
- **Multi-resource org pref `WorkforceSchdMulResSchdPref` is ON** for the workspace (precondition; `isPrimaryResource` is only processed when on).
- **These tests run live against the provisioned org** (not unattended CI). Verdicts are encoded from live observation; if a live run diverges from the investigated expectation, update the assertion to the observed value AND record the divergence in the decision log.

## File Structure

**New booking act folders** (each = a directory + `.resources/definition.yaml` + `NN-name.request.yaml`), under
`src/integrationTest/resources/pm-templates/v3/core/wfs/booking/`:

- `schedule-two-primary/` — Decision 4: two `isPrimaryResource:true` resources → input-validation reject.
- `schedule-primary-not-required/` — Decision 5 probe: single primary+optional → persist reject.
- `schedule-primary-required-control/` — Decision 5 control: single primary+required → Success.
- `schedule-two-resource-clean/` — Decision 4z setup: primary + non-primary, both required, both free → Success; captures `reschedCleanSaId`.
- `reschedule-delete-primary-with-flag/` — Decision 4z Arm A: `DeleteOperation` on the primary WITH `isPrimaryResource:true` → 400 `INVALID_INPUT`.
- `reschedule-delete-primary-no-flag/` — Decision 4z Arm B: `DeleteOperation` on the primary WITHOUT the flag → Success, no-primary crew.
- `get-slots-parity-available/` — Decision 2: GetAppointmentSlots, available window → slots > 0.
- `get-slots-parity-unavailable/` — Decision 2: GetAppointmentSlots, unavailable window → slots = 0.
- `schedule-parity-unavailable/` — Decision 2: Schedule into the unavailable window → rejected.
- `schedule-parity-available/` — Decision 2: Schedule into the available window → Success.

**Modified Java:**
- `…/wfs/ReVomanConfigForWfs.java` — add one `Kick` constant per new folder + class-javadoc bullets for Decisions 2/4/4z/5.
- `…/wfs/WfsWritePathParityE2ETest.java` — add `testTwoPrimaryResourcesRejectedE2E` (4), `testPrimaryNotRequiredRejectedE2E` (5), `testRescheduleNoPrimaryE2E` (4z).
- `…/wfs/WfsReadPathParityE2ETest.java` — add `testCheapCheckReadWritePromiseE2E` (2); update class javadoc.

**Reused as-is (no edit):**
- `…/wfs/fixtures/required-non-required/` — resourceA (caseWorkerUserId) + resourceB (caseManagerUserId), both Primary STMs effective a month ago, both Confirmed Shift 08-16 tomorrow, both available; ResourcePreference(Required)→resourceB. Sets `requiredNonReqTerritoryId/WorkTypeId/AccountId/ResourceAId/ResourceBId`.
- `…/wfs/policies/availability-op-hours-policy/` — Availability(C)+ShiftUsage(Union) + WorkingTerritories; NO RequiredResources rule (so no Task-4 NPE confound). Sets `availabilityOpHoursPolicyId`.
- `…/wfs/auth/` — `AUTH_CONFIG` persona mint.

**Reference (read-only, do not edit):**
- `…/booking/schedule-double-book-non-required-violating/` and `…/schedule-double-book-required-conflict/` — the canonical A/B two-resource schedule act shape.
- `…/booking/schedule-single-required-no-primary/` — the single-resource schedule + dual-shape capture script.
- `…/booking/get-slots-sharing-split-as-manager/` — the GetAppointmentSlots act + `data.result[].slots[]` count script.
- `…/booking/get-available-resources-limit-zero/` — the dual-shape errorCode capture pattern.

---

## Task 1: Decision 4 — two primary resources rejected at input validation

**Files:**
- Create: `src/integrationTest/resources/pm-templates/v3/core/wfs/booking/schedule-two-primary/.resources/definition.yaml`
- Create: `src/integrationTest/resources/pm-templates/v3/core/wfs/booking/schedule-two-primary/10-schedule-two-primary.request.yaml`
- Modify: `src/integrationTest/java/com/salesforce/revoman/integration/core/wfs/ReVomanConfigForWfs.java`
- Modify: `src/integrationTest/java/com/salesforce/revoman/integration/core/wfs/WfsWritePathParityE2ETest.java`

**Interfaces:**
- Consumes: `AUTH_CONFIG`, `AVAILABILITY_OP_HOURS_POLICY_CONFIG`, `REQUIRED_NON_REQUIRED_FIXTURE_CONFIG` (existing `Kick`s); env `availabilityOpHoursPolicyId`, `requiredNonReqWorkTypeId`, `requiredNonReqTerritoryId`, `requiredNonReqAccountId`, `requiredNonReqResourceAId`, `requiredNonReqResourceBId`.
- Produces: `Kick SCHEDULE_TWO_PRIMARY_CONFIG`; env keys `twoPrimaryHttpCode`, `twoPrimaryStatus`, `twoPrimaryErrorCode`, `twoPrimaryErrorMessage`.

- [ ] **Step 1: Create the act definition.yaml**

`booking/schedule-two-primary/.resources/definition.yaml`:
```yaml
$kind: collection
description: |-
  Decision 4 act — schedules ONE OnSite SA with TWO assigned resources BOTH isPrimaryResource=true
  (resourceA + resourceB, both also isRequiredResource=true) under the availability-op-hours policy over
  the required-non-required fixture, tomorrow 11:00-12:00 UTC. Multi-resource scheduling requires EXACTLY
  one primary, so two primaries is an invalid input combination caught UP FRONT at input validation
  (ScheduleCommonValidator.validatePrimaryResourceConstraints) — before any availability/persist. 264:
  clean ConnectErrorCode INVALID_INPUT / HTTP 400 / message "Only one of the provided assigned resources
  can be a primary resource." 262 (historical): a confusing DB error; current code returns the clean one.
auth:
  - id: 1a2b3c4d-0004-4aaa-9bbb-000000000004
    type: bearer
    name: bearer auth
    credentials:
      token: "{{managerToken}}"
```

- [ ] **Step 2: Create the act request.yaml**

`booking/schedule-two-primary/10-schedule-two-primary.request.yaml`:
```yaml
$kind: http-request
description: >-
  Two assigned resources BOTH isPrimaryResource=true → input-validation reject (top-level
  ConnectErrorCode INVALID_INPUT, HTTP 400, "primary resource" message). No booking occurs (the throw
  is in validatePayload, before per-appointment processing) → appointments[0] is absent. Window
  tomorrow 11:00-12:00 UTC (60m == WorkType EstimatedDuration), inside member OH 08-16 + Shift; window
  is irrelevant since validation fails first, but kept clean so the verdict is unambiguously the
  primary-count rule and not an availability artefact.
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
          "schedulingPolicyId": "{{availabilityOpHoursPolicyId}}",
          "coreDetails": {
            "startTime": "{{twoPrimaryStart}}",
            "endTime": "{{twoPrimaryEnd}}",
            "status": "Scheduled",
            "subject": "Decision 4 two-primary probe",
            "workConfiguration": {
              "workTypeId": "{{requiredNonReqWorkTypeId}}"
            },
            "locationConstraints": {
              "id": "{{requiredNonReqTerritoryId}}"
            }
          },
          "serviceAppointmentParent": {
            "id": "{{requiredNonReqAccountId}}"
          },
          "assignedResources": [
            {
              "isPrimaryResource": true,
              "isRequiredResource": true,
              "serviceResourceId": "{{requiredNonReqResourceAId}}"
            },
            {
              "isPrimaryResource": true,
              "isRequiredResource": true,
              "serviceResourceId": "{{requiredNonReqResourceBId}}"
            }
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
      pm.environment.set("twoPrimaryStart", start.toISOString());
      pm.environment.set("twoPrimaryEnd", end.toISOString());
    language: text/javascript
  - type: afterResponse
    code: |-
      // Two-primary is an input-validation reject → top-level ConnectErrorCode array [{errorCode,message}]
      // + HTTP 400. Capture http code + both error shapes so the assertion can't be a false positive.
      pm.environment.set("twoPrimaryHttpCode", String(pm.response.code));
      const data = pm.response.json();
      const arr = Array.isArray(data) ? data : null;
      const appts = (data && data.appointments) || [];
      pm.environment.set("twoPrimaryStatus", appts.length ? appts[0].schedulingStatus : null);
      let ec = null, msg = null;
      if (arr && arr.length) { ec = arr[0].errorCode; msg = arr[0].message; }
      else if (appts.length && appts[0].errors && appts[0].errors[0]) { ec = appts[0].errors[0].errorCode; msg = appts[0].errors[0].message; }
      pm.environment.set("twoPrimaryErrorCode", ec);
      pm.environment.set("twoPrimaryErrorMessage", msg);
      console.log("DEC4 http=" + pm.response.code + " ec=" + ec + " msg=" + msg);
    language: text/javascript
order: 1000
```

- [ ] **Step 3: Add the Kick constant**

In `ReVomanConfigForWfs.java`, after the `SINGLE_REQUIRED_NO_PRIMARY_SCHEDULE_CONFIG` block (the Decision 3 section, ~line 141), add:
```java
  // ## Decision 4 — two primary resources → clean input-validation reject (INVALID_INPUT / HTTP 400,
  // "Only one of the provided assigned resources can be a primary resource"). Caught up front in
  // ScheduleCommonValidator.validatePrimaryResourceConstraints, before availability/persist.
  static final Kick SCHEDULE_TWO_PRIMARY_CONFIG =
      kickFor(V3_WFS_PATH + "booking/schedule-two-primary");
```
Also add a bullet to the class javadoc `<ul>` (after the `<b>3</b>` bullet):
```java
 *   <li><b>4</b> — two {@code isPrimaryResource=true} resources → clean input-validation reject
 *       ({@code INVALID_INPUT} / HTTP 400, "only one ... primary resource"), before availability.
```

- [ ] **Step 4: Add the test method**

In `WfsWritePathParityE2ETest.java`, add the imports (top, alphabetical with the other static imports):
```java
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.SCHEDULE_TWO_PRIMARY_CONFIG;
```
Add the method (after `testMissingRequiredFlagE2E`, before the private helper):
```java
  /**
   * Decision 4 — TWO assigned resources both {@code isPrimaryResource=true} are rejected UP FRONT at
   * input validation ({@code ScheduleCommonValidator.validatePrimaryResourceConstraints}), before any
   * availability/persist. Multi-resource scheduling requires EXACTLY one primary.
   *
   * <p>262 (asserted): a clean top-level {@code ConnectErrorCode INVALID_INPUT} / HTTP 400 with message
   * "Only one of the provided assigned resources can be a primary resource." No booking occurs (the throw
   * is in {@code validatePayload}, so {@code appointments[0]} is absent → status null). Per the product
   * doc the 262 ERA produced a "confusing database error"; the CURRENT code already returns the clean
   * message, so this characterizes the 264 target.
   * <p>264 contrast: unchanged — the clean {@code INVALID_INPUT} message is the intended behavior; no
   * action needed (error-message polish already shipped).
   */
  @Test
  void testTwoPrimaryResourcesRejectedE2E() {
    final var rundown =
        ReVoman.revUp(
            (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
            AUTH_CONFIG,
            AVAILABILITY_OP_HOURS_POLICY_CONFIG,
            REQUIRED_NON_REQUIRED_FIXTURE_CONFIG,
            SCHEDULE_TWO_PRIMARY_CONFIG);
    final var env = CollectionsKt.last(rundown).mutableEnv;
    // Clean input-validation reject: INVALID_INPUT, "primary resource" message, HTTP 400, no booking.
    assertThat(env.getAsString("twoPrimaryErrorCode")).isEqualTo("INVALID_INPUT");
    assertThat(env.getAsString("twoPrimaryErrorMessage")).contains("primary resource");
    assertThat(env.getAsString("twoPrimaryHttpCode")).isEqualTo("400");
    assertThat(env.getAsString("twoPrimaryStatus")).isAnyOf(null, "null");
  }
```

- [ ] **Step 5: Compile the integrationTest source set**

Run: `gradle compileIntegrationTestJava`
Expected: `BUILD SUCCESSFUL` (no compile errors from the new constant/method).

- [ ] **Step 6: Run the test live, record the verdict**

Run: `gradle integrationTest --tests "com.salesforce.revoman.integration.core.wfs.WfsWritePathParityE2ETest.testTwoPrimaryResourcesRejectedE2E"`
Expected: PASS. The console log line `DEC4 http=400 ec=INVALID_INPUT msg=...primary resource...` confirms the verdict.
If the live verdict diverges (e.g. errorCode differs, or the message text differs), update the assertion to the OBSERVED value and note the divergence for the decision log. Do not change Core.

- [ ] **Step 7: Commit**

```bash
git add src/integrationTest/resources/pm-templates/v3/core/wfs/booking/schedule-two-primary \
        src/integrationTest/java/com/salesforce/revoman/integration/core/wfs/ReVomanConfigForWfs.java \
        src/integrationTest/java/com/salesforce/revoman/integration/core/wfs/WfsWritePathParityE2ETest.java
git commit -m "test(wfs): Decision 4 two-primary input-validation reject (INVALID_INPUT)"
```

---

## Task 2: Decision 5 — primary-but-not-required rejected at persist (no auto-correct, no double-book)

**Files:**
- Create: `…/booking/schedule-primary-not-required/.resources/definition.yaml`
- Create: `…/booking/schedule-primary-not-required/10-schedule-primary-not-required.request.yaml`
- Create: `…/booking/schedule-primary-required-control/.resources/definition.yaml`
- Create: `…/booking/schedule-primary-required-control/10-schedule-primary-required-control.request.yaml`
- Modify: `…/wfs/ReVomanConfigForWfs.java`
- Modify: `…/wfs/WfsWritePathParityE2ETest.java`

**Interfaces:**
- Consumes: `AUTH_CONFIG`, `AVAILABILITY_OP_HOURS_POLICY_CONFIG`, `REQUIRED_NON_REQUIRED_FIXTURE_CONFIG`; env `availabilityOpHoursPolicyId`, `requiredNonReqWorkTypeId`, `requiredNonReqTerritoryId`, `requiredNonReqAccountId`, `requiredNonReqResourceAId`.
- Produces: `Kick SCHEDULE_PRIMARY_NOT_REQUIRED_CONFIG`, `Kick SCHEDULE_PRIMARY_REQUIRED_CONTROL_CONFIG`; env keys `primaryNotReqStatus`, `primaryNotReqErrorCode`, `primaryNotReqErrorMessage`, `primaryReqControlStatus`.

- [ ] **Step 1: Create the probe definition.yaml**

`booking/schedule-primary-not-required/.resources/definition.yaml`:
```yaml
$kind: collection
description: |-
  Decision 5 probe — a SINGLE assigned resource marked isPrimaryResource=true but isRequiredResource=false
  (a contradictory request the story expected to be "quietly fixed to required"). Live API does NOT
  auto-correct: input validation only counts primaries (it does not inspect the required flag), so the
  payload passes validation and reaches PERSIST, where LightningSchedulerAssignedResourceValidator rejects
  it (INVALID_FIELD on IsRequiredResource: "Only an required service resource can be set as a primary
  service resource."). The window is FREE, so availability passes and the persist error is what surfaces
  (a busy window would surface an availability error first). Because the request is rejected, the SA never
  persists → NO double-booking (refuting the 262 "could be double-booked" claim).
auth:
  - id: 1a2b3c4d-0005-4aaa-9bbb-000000000051
    type: bearer
    name: bearer auth
    credentials:
      token: "{{managerToken}}"
```

- [ ] **Step 2: Create the probe request.yaml**

`booking/schedule-primary-not-required/10-schedule-primary-not-required.request.yaml`:
```yaml
$kind: http-request
description: >-
  Single resourceA, isPrimaryResource=true, isRequiredResource=false, into a FREE window tomorrow
  11:00-12:00 UTC (60m == WorkType EstimatedDuration, inside member OH 08-16 + Shift). Availability
  passes (free) → the PERSIST-layer reject surfaces. Capture the verdict in both shapes without
  prejudging which (top-level array vs appointments[0].errors[0]); the live run pins the shape.
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
          "schedulingPolicyId": "{{availabilityOpHoursPolicyId}}",
          "coreDetails": {
            "startTime": "{{primaryNotReqStart}}",
            "endTime": "{{primaryNotReqEnd}}",
            "status": "Scheduled",
            "subject": "Decision 5 primary-not-required probe",
            "workConfiguration": {
              "workTypeId": "{{requiredNonReqWorkTypeId}}"
            },
            "locationConstraints": {
              "id": "{{requiredNonReqTerritoryId}}"
            }
          },
          "serviceAppointmentParent": {
            "id": "{{requiredNonReqAccountId}}"
          },
          "assignedResources": [
            {
              "isPrimaryResource": true,
              "isRequiredResource": false,
              "serviceResourceId": "{{requiredNonReqResourceAId}}"
            }
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
      pm.environment.set("primaryNotReqStart", start.toISOString());
      pm.environment.set("primaryNotReqEnd", end.toISOString());
    language: text/javascript
  - type: afterResponse
    code: |-
      pm.environment.set("primaryNotReqHttpCode", String(pm.response.code));
      const data = pm.response.json();
      const arr = Array.isArray(data) ? data : null;
      const appts = (data && data.appointments) || [];
      pm.environment.set("primaryNotReqStatus", appts.length ? appts[0].schedulingStatus : null);
      let ec = null, msg = null;
      if (arr && arr.length) { ec = arr[0].errorCode; msg = arr[0].message; }
      else if (appts.length && appts[0].errors && appts[0].errors[0]) { ec = appts[0].errors[0].errorCode; msg = appts[0].errors[0].message; }
      pm.environment.set("primaryNotReqErrorCode", ec);
      pm.environment.set("primaryNotReqErrorMessage", msg);
      console.log("DEC5 probe http=" + pm.response.code + " status=" + pm.environment.get("primaryNotReqStatus") + " ec=" + ec + " msg=" + msg);
    language: text/javascript
order: 1000
```

- [ ] **Step 3: Create the control definition.yaml**

`booking/schedule-primary-required-control/.resources/definition.yaml`:
```yaml
$kind: collection
description: |-
  Decision 5 control — identical to the probe but isRequiredResource=true (a genuine required primary).
  Proves the probe's rejection is SPECIFICALLY the primary-must-be-required persist rule and not a
  fixture/availability artefact: the only-required flag flips and the SAME resource/window now books
  Success. FRESH resources per Approach A so the probe's (rejected, non-persisting) attempt cannot
  pollute the control's availability.
auth:
  - id: 1a2b3c4d-0005-4aaa-9bbb-000000000052
    type: bearer
    name: bearer auth
    credentials:
      token: "{{managerToken}}"
```

- [ ] **Step 4: Create the control request.yaml**

`booking/schedule-primary-required-control/10-schedule-primary-required-control.request.yaml`:
```yaml
$kind: http-request
description: >-
  CONTROL — single resourceA, isPrimaryResource=true AND isRequiredResource=true, same FREE window
  tomorrow 11:00-12:00 UTC. A required primary is valid → Success. Busy-proof half: isolates the
  Decision-5 reject to the not-required flag, not the resource/window.
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
          "schedulingPolicyId": "{{availabilityOpHoursPolicyId}}",
          "coreDetails": {
            "startTime": "{{primaryReqControlStart}}",
            "endTime": "{{primaryReqControlEnd}}",
            "status": "Scheduled",
            "subject": "Decision 5 primary-required control",
            "workConfiguration": {
              "workTypeId": "{{requiredNonReqWorkTypeId}}"
            },
            "locationConstraints": {
              "id": "{{requiredNonReqTerritoryId}}"
            }
          },
          "serviceAppointmentParent": {
            "id": "{{requiredNonReqAccountId}}"
          },
          "assignedResources": [
            {
              "isPrimaryResource": true,
              "isRequiredResource": true,
              "serviceResourceId": "{{requiredNonReqResourceAId}}"
            }
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
      pm.environment.set("primaryReqControlStart", start.toISOString());
      pm.environment.set("primaryReqControlEnd", end.toISOString());
    language: text/javascript
  - type: afterResponse
    code: |-
      const data = pm.response.json();
      const appts = (data && data.appointments) || [];
      pm.environment.set("primaryReqControlStatus", appts.length ? appts[0].schedulingStatus : null);
      console.log("DEC5 control http=" + pm.response.code + " status=" + pm.environment.get("primaryReqControlStatus"));
    language: text/javascript
order: 1000
```

- [ ] **Step 5: Add the Kick constants**

In `ReVomanConfigForWfs.java`, after the Decision 4 block from Task 1, add:
```java
  // ## Decision 5 — a primary resource marked NOT required is REJECTED at persist (no auto-correct, no
  // double-book). Probe: single isPrimaryResource=true, isRequiredResource=false → persist INVALID_FIELD.
  // Control: flip isRequiredResource=true → Success (isolates the reject to the not-required flag).
  static final Kick SCHEDULE_PRIMARY_NOT_REQUIRED_CONFIG =
      kickFor(V3_WFS_PATH + "booking/schedule-primary-not-required");
  static final Kick SCHEDULE_PRIMARY_REQUIRED_CONTROL_CONFIG =
      kickFor(V3_WFS_PATH + "booking/schedule-primary-required-control");
```
Add a class-javadoc bullet (after the `<b>4</b>` bullet):
```java
 *   <li><b>5</b> — a primary resource marked NOT required is REJECTED at persist (no auto-correct, no
 *       double-book): probe rejected, required-primary control Success.
```

- [ ] **Step 6: Add the test method**

In `WfsWritePathParityE2ETest.java`, add imports:
```java
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.SCHEDULE_PRIMARY_NOT_REQUIRED_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.SCHEDULE_PRIMARY_REQUIRED_CONTROL_CONFIG;
```
Add the method (after `testTwoPrimaryResourcesRejectedE2E`):
```java
  /**
   * Decision 5 — a single resource marked {@code isPrimaryResource=true} but {@code
   * isRequiredResource=false} is REJECTED, NOT auto-corrected and NOT silently double-booked. Input
   * validation counts primaries only (it never inspects the required flag), so the contradictory payload
   * passes validation and reaches PERSIST, where {@code LightningSchedulerAssignedResourceValidator}
   * rejects it. The window is FREE, so availability passes and the persist reject is what surfaces.
   *
   * <p>262 (asserted): the probe is rejected (NOT Success) — the live verdict is captured in both error
   * shapes (top-level {@code INVALID_FIELD} array vs {@code appointments[0].errors[0]}); the required
   * primary CONTROL over the same resource/window books Success. This REFUTES both the story's
   * "quietly fixed to required" auto-correct expectation AND the 262 "could be double-booked" claim
   * (the request never persists).
   * <p>264 contrast: unchanged — reject (not auto-correct) is the intended persist behavior; the doc's
   * open question (auto-correct vs reject) resolves to reject. (A fast-fail at input validation, as the
   * notes recommend, would only change WHERE it's rejected, not THAT it's rejected.)
   *
   * <p>Approach A: two SEPARATE revUps so the probe's (rejected, non-persisting) attempt and the
   * control's (persisting) booking never share ServiceResource rows.
   */
  @Test
  void testPrimaryNotRequiredRejectedE2E() {
    // Probe: primary + NOT required, free window → rejected at persist (not Success, not double-booked).
    final var probeRundown =
        ReVoman.revUp(
            (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
            AUTH_CONFIG,
            AVAILABILITY_OP_HOURS_POLICY_CONFIG,
            REQUIRED_NON_REQUIRED_FIXTURE_CONFIG,
            SCHEDULE_PRIMARY_NOT_REQUIRED_CONFIG);
    final var probeEnv = CollectionsKt.last(probeRundown).mutableEnv;
    assertThat(probeEnv.getAsString("primaryNotReqStatus")).isNotEqualTo("Success");
    assertThat(probeEnv.getAsString("primaryNotReqErrorCode")).isNotNull();
    // Control: primary + required, same resource/window → Success (fresh AUTH per Approach A).
    final var controlRundown =
        ReVoman.revUp(
            (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
            AUTH_CONFIG,
            AVAILABILITY_OP_HOURS_POLICY_CONFIG,
            REQUIRED_NON_REQUIRED_FIXTURE_CONFIG,
            SCHEDULE_PRIMARY_REQUIRED_CONTROL_CONFIG);
    assertThat(CollectionsKt.last(controlRundown).mutableEnv)
        .containsEntry("primaryReqControlStatus", "Success");
  }
```

- [ ] **Step 7: Compile**

Run: `gradle compileIntegrationTestJava`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8: Run live, record verdict**

Run: `gradle integrationTest --tests "com.salesforce.revoman.integration.core.wfs.WfsWritePathParityE2ETest.testPrimaryNotRequiredRejectedE2E"`
Expected: PASS. Console logs `DEC5 probe …` (status not Success, an errorCode present) and `DEC5 control … status=Success`.
RECORD the observed probe shape (errorCode value + message + whether top-level or `appointments[0]`) for the decision log — this is the previously-uncertain response shape. If the probe unexpectedly returns Success (would mean auto-correct or no-reject), STOP and capture it as a finding (it would contradict the investigation); do not weaken the assertion to force a pass.

- [ ] **Step 9: Commit**

```bash
git add src/integrationTest/resources/pm-templates/v3/core/wfs/booking/schedule-primary-not-required \
        src/integrationTest/resources/pm-templates/v3/core/wfs/booking/schedule-primary-required-control \
        src/integrationTest/java/com/salesforce/revoman/integration/core/wfs/ReVomanConfigForWfs.java \
        src/integrationTest/java/com/salesforce/revoman/integration/core/wfs/WfsWritePathParityE2ETest.java
git commit -m "test(wfs): Decision 5 primary-not-required persist reject (no auto-correct/double-book)"
```

---

## Task 3: Decision 4z — reschedule that leaves no primary (the doc's claim is wrong)

**Files:**
- Create: `…/booking/schedule-two-resource-clean/.resources/definition.yaml`
- Create: `…/booking/schedule-two-resource-clean/10-schedule-two-resource-clean.request.yaml`
- Create: `…/booking/reschedule-delete-primary-with-flag/.resources/definition.yaml`
- Create: `…/booking/reschedule-delete-primary-with-flag/10-reschedule-delete-primary-with-flag.request.yaml`
- Create: `…/booking/reschedule-delete-primary-no-flag/.resources/definition.yaml`
- Create: `…/booking/reschedule-delete-primary-no-flag/10-reschedule-delete-primary-no-flag.request.yaml`
- Modify: `…/wfs/ReVomanConfigForWfs.java`
- Modify: `…/wfs/WfsWritePathParityE2ETest.java`

**Interfaces:**
- Consumes: `AUTH_CONFIG`, `AVAILABILITY_OP_HOURS_POLICY_CONFIG`, `REQUIRED_NON_REQUIRED_FIXTURE_CONFIG`; env `availabilityOpHoursPolicyId`, `requiredNonReqWorkTypeId`, `requiredNonReqTerritoryId`, `requiredNonReqAccountId`, `requiredNonReqResourceAId`, `requiredNonReqResourceBId`.
- Produces: `Kick SCHEDULE_TWO_RESOURCE_CLEAN_CONFIG`, `Kick RESCHEDULE_DELETE_PRIMARY_WITH_FLAG_CONFIG`, `Kick RESCHEDULE_DELETE_PRIMARY_NO_FLAG_CONFIG`; env keys `reschedCleanStatus`, `reschedCleanSaId`, `reschedWithFlagErrorCode`, `reschedWithFlagHttpCode`, `reschedNoFlagStatus`.

- [ ] **Step 1: Create the clean two-resource schedule definition.yaml**

`booking/schedule-two-resource-clean/.resources/definition.yaml`:
```yaml
$kind: collection
description: |-
  Decision 4z setup — schedules ONE OnSite SA with TWO assigned resources: resourceA primary+required and
  resourceB non-primary+required, both free in the window. Books Success and captures the created
  serviceAppointmentId into reschedCleanSaId, which the two reschedule acts below consume. Exactly one
  primary, so the schedule path is satisfied.
auth:
  - id: 1a2b3c4d-004f-4aaa-9bbb-00000000004a
    type: bearer
    name: bearer auth
    credentials:
      token: "{{managerToken}}"
```

- [ ] **Step 2: Create the clean two-resource schedule request.yaml**

`booking/schedule-two-resource-clean/10-schedule-two-resource-clean.request.yaml`:
```yaml
$kind: http-request
description: >-
  Two resources (resourceA primary+required, resourceB non-primary+required), both free, tomorrow
  11:00-12:00 UTC (60m == WorkType EstimatedDuration, inside member OH 08-16 + Shift). Books Success;
  captures the created SA id (appointments[0].serviceAppointment.serviceAppointmentId) into
  reschedCleanSaId for the reschedule acts. Must persist, so NOT ledger-off here would be wrong on warm
  runs — this is a producer of reschedCleanSaId; ledger-off keeps it fresh.
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
          "schedulingPolicyId": "{{availabilityOpHoursPolicyId}}",
          "coreDetails": {
            "startTime": "{{reschedWindowStart}}",
            "endTime": "{{reschedWindowEnd}}",
            "status": "Scheduled",
            "subject": "Decision 4z clean two-resource SA",
            "workConfiguration": {
              "workTypeId": "{{requiredNonReqWorkTypeId}}"
            },
            "locationConstraints": {
              "id": "{{requiredNonReqTerritoryId}}"
            }
          },
          "serviceAppointmentParent": {
            "id": "{{requiredNonReqAccountId}}"
          },
          "assignedResources": [
            {
              "isPrimaryResource": true,
              "isRequiredResource": true,
              "serviceResourceId": "{{requiredNonReqResourceAId}}"
            },
            {
              "isPrimaryResource": false,
              "isRequiredResource": true,
              "serviceResourceId": "{{requiredNonReqResourceBId}}"
            }
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
      pm.environment.set("reschedWindowStart", start.toISOString());
      pm.environment.set("reschedWindowEnd", end.toISOString());
    language: text/javascript
  - type: afterResponse
    code: |-
      const data = pm.response.json();
      const appts = (data && data.appointments) || [];
      const a0 = appts.length ? appts[0] : null;
      pm.environment.set("reschedCleanStatus", a0 ? a0.schedulingStatus : null);
      const saId = a0 && a0.serviceAppointment ? a0.serviceAppointment.serviceAppointmentId : null;
      pm.environment.set("reschedCleanSaId", saId);
      console.log("DEC4z clean http=" + pm.response.code + " status=" + (a0 && a0.schedulingStatus) + " saId=" + saId);
    language: text/javascript
order: 1000
```

- [ ] **Step 3: Create the Arm A (with-flag) reschedule definition.yaml**

`booking/reschedule-delete-primary-with-flag/.resources/definition.yaml`:
```yaml
$kind: collection
description: |-
  Decision 4z Arm A — reschedules the SA created above, DeleteOperation on the primary resourceA WITH
  isPrimaryResource:true on the delete entry. This is the ONLY shape that produces the doc's quoted error:
  RescheduleCommonValidator.validateDeleteOperationFields rejects isPrimaryResource on a Delete entry
  (INVALID_INPUT, "isPrimaryResource cannot be set for Delete" / InvalidPropertyForDelete). It is a
  PAYLOAD-FIELD rule (the flag is present on a delete), NOT a "the delete leaves no primary" rule.
auth:
  - id: 1a2b3c4d-004f-4aaa-9bbb-00000000004b
    type: bearer
    name: bearer auth
    credentials:
      token: "{{managerToken}}"
```

- [ ] **Step 4: Create the Arm A (with-flag) reschedule request.yaml**

`booking/reschedule-delete-primary-with-flag/10-reschedule-delete-primary-with-flag.request.yaml`:
```yaml
$kind: http-request
description: >-
  Reschedule reschedCleanSaId, DeleteOperation on the primary resourceA WITH isPrimaryResource:true on the
  delete entry → 400 INVALID_INPUT (InvalidPropertyForDelete, "isPrimaryResource cannot be set for
  Delete"). Validation-only; the SA is NOT mutated (so Arm B below can still delete resourceA).
url: "{{baseUrl}}{{versionPath}}/connect/unified-scheduling/actions/reschedule"
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
      "rescheduleAppointments": [
        {
          "schedulingMethod": "OnSite",
          "appointmentId": "{{reschedCleanSaId}}",
          "assignedResources": [
            {
              "operation": "DeleteOperation",
              "assignedResource": {
                "isPrimaryResource": true,
                "serviceResourceId": "{{requiredNonReqResourceAId}}"
              }
            }
          ]
        }
      ]
    }
scripts:
  - type: afterResponse
    code: |-
      pm.environment.set("reschedWithFlagHttpCode", String(pm.response.code));
      const data = pm.response.json();
      const arr = Array.isArray(data) ? data : null;
      const appts = (data && data.appointments) || [];
      let ec = null, msg = null;
      if (arr && arr.length) { ec = arr[0].errorCode; msg = arr[0].message; }
      else if (appts.length && appts[0].errors && appts[0].errors[0]) { ec = appts[0].errors[0].errorCode; msg = appts[0].errors[0].message; }
      pm.environment.set("reschedWithFlagErrorCode", ec);
      pm.environment.set("reschedWithFlagErrorMessage", msg);
      console.log("DEC4z armA http=" + pm.response.code + " ec=" + ec + " msg=" + msg);
    language: text/javascript
order: 2000
```

- [ ] **Step 5: Create the Arm B (no-flag) reschedule definition.yaml**

`booking/reschedule-delete-primary-no-flag/.resources/definition.yaml`:
```yaml
$kind: collection
description: |-
  Decision 4z Arm B — reschedules the SAME SA, DeleteOperation on the primary resourceA WITHOUT
  isPrimaryResource on the delete entry (only operation + serviceResourceId). This SUCCEEDS: the
  payload-field guard does not fire (no flag sent), and RescheduleCommonValidator.validatePrimaryResourceCount
  explicitly ALLOWS zero primaries for reschedule. The resulting crew (resourceB, non-primary) has NO
  primary — proving a reschedule CAN leave an appointment without a primary, which REFUTES the product
  doc's blanket "not possible to reschedule without a primary."
auth:
  - id: 1a2b3c4d-004f-4aaa-9bbb-00000000004c
    type: bearer
    name: bearer auth
    credentials:
      token: "{{managerToken}}"
```

- [ ] **Step 6: Create the Arm B (no-flag) reschedule request.yaml**

`booking/reschedule-delete-primary-no-flag/10-reschedule-delete-primary-no-flag.request.yaml`:
```yaml
$kind: http-request
description: >-
  Reschedule reschedCleanSaId, DeleteOperation on the primary resourceA WITHOUT isPrimaryResource (only
  operation + serviceResourceId). Succeeds; the resulting crew = resourceB (non-primary) → no primary on
  the appointment. Refutes the doc's "reschedule must keep a primary". Runs AFTER Arm A (which did not
  mutate the SA, so resourceA is still present to delete here).
url: "{{baseUrl}}{{versionPath}}/connect/unified-scheduling/actions/reschedule"
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
      "rescheduleAppointments": [
        {
          "schedulingMethod": "OnSite",
          "appointmentId": "{{reschedCleanSaId}}",
          "assignedResources": [
            {
              "operation": "DeleteOperation",
              "assignedResource": {
                "serviceResourceId": "{{requiredNonReqResourceAId}}"
              }
            }
          ]
        }
      ]
    }
scripts:
  - type: afterResponse
    code: |-
      pm.environment.set("reschedNoFlagHttpCode", String(pm.response.code));
      const data = pm.response.json();
      const appts = (data && data.appointments) || [];
      const a0 = appts.length ? appts[0] : null;
      pm.environment.set("reschedNoFlagStatus", a0 ? a0.schedulingStatus : null);
      let ec = null;
      if (Array.isArray(data) && data.length) { ec = data[0].errorCode; }
      else if (a0 && a0.errors && a0.errors[0]) { ec = a0.errors[0].errorCode; }
      pm.environment.set("reschedNoFlagErrorCode", ec);
      console.log("DEC4z armB http=" + pm.response.code + " status=" + (a0 && a0.schedulingStatus) + " ec=" + ec);
    language: text/javascript
order: 3000
```

- [ ] **Step 7: Add the Kick constants**

In `ReVomanConfigForWfs.java`, after the Decision 5 block, add:
```java
  // ## Decision 4z — a reschedule CAN leave an appointment with no primary (the product doc's blanket
  // "not possible" is WRONG). Two independent rules: (Arm A) isPrimaryResource on a DeleteOperation entry
  // → INVALID_INPUT "isPrimaryResource cannot be set for Delete" (a payload-field guard); (Arm B) deleting
  // the primary WITHOUT the flag → Success, zero-primary crew (RescheduleCommonValidator explicitly allows
  // zero primaries for reschedule). Clean two-resource schedule sets up reschedCleanSaId for both arms.
  static final Kick SCHEDULE_TWO_RESOURCE_CLEAN_CONFIG =
      kickFor(V3_WFS_PATH + "booking/schedule-two-resource-clean");
  static final Kick RESCHEDULE_DELETE_PRIMARY_WITH_FLAG_CONFIG =
      kickFor(V3_WFS_PATH + "booking/reschedule-delete-primary-with-flag");
  static final Kick RESCHEDULE_DELETE_PRIMARY_NO_FLAG_CONFIG =
      kickFor(V3_WFS_PATH + "booking/reschedule-delete-primary-no-flag");
```
Add a class-javadoc bullet (after the `<b>5</b>` bullet):
```java
 *   <li><b>4z</b> — a reschedule CAN leave an appointment with no primary (refutes the doc): Arm A
 *       (isPrimaryResource on a Delete entry) → INVALID_INPUT; Arm B (delete primary, no flag) → Success.
```

- [ ] **Step 8: Add the test method**

In `WfsWritePathParityE2ETest.java`, add imports:
```java
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.RESCHEDULE_DELETE_PRIMARY_NO_FLAG_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.RESCHEDULE_DELETE_PRIMARY_WITH_FLAG_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.SCHEDULE_TWO_RESOURCE_CLEAN_CONFIG;
```
Add the method (after `testPrimaryNotRequiredRejectedE2E`):
```java
  /**
   * Decision 4z — a reschedule CAN leave an appointment with NO primary resource. The product doc's
   * conclusion ("not possible to schedule or reschedule without a primary") is WRONG for the reschedule
   * API: it conflates two independent rules. One revUp chains a clean two-resource Schedule (captures the
   * SA id) then two reschedule arms over that SA.
   *
   * <p>262 (asserted): the clean Schedule books Success. Arm A — {@code DeleteOperation} on the primary
   * WITH {@code isPrimaryResource:true} on the delete entry — is rejected ({@code INVALID_INPUT}, HTTP
   * 400, "isPrimaryResource cannot be set for Delete"): a PAYLOAD-FIELD guard
   * ({@code RescheduleCommonValidator.validateDeleteOperationFields}), not a crew rule, so it does NOT
   * mutate the SA. Arm B — {@code DeleteOperation} on the primary WITHOUT the flag — SUCCEEDS, leaving the
   * appointment with only the non-primary resourceB (zero primaries), because {@code
   * validatePrimaryResourceCount} explicitly allows zero primaries on reschedule. This is exactly why the
   * Core func test {@code testRescheduleAppointmentDeleteAllAssignedResources} passes.
   * <p>264 contrast: unchanged — both behaviors are intended; the doc's blanket claim is the thing to fix.
   * The product OPEN QUESTION (should a reschedule be allowed to leave no primary?) is a DECISION, not a
   * code bug: today it IS allowed.
   */
  @Test
  void testRescheduleNoPrimaryE2E() {
    final var rundown =
        ReVoman.revUp(
            (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
            AUTH_CONFIG,
            AVAILABILITY_OP_HOURS_POLICY_CONFIG,
            REQUIRED_NON_REQUIRED_FIXTURE_CONFIG,
            SCHEDULE_TWO_RESOURCE_CLEAN_CONFIG,
            RESCHEDULE_DELETE_PRIMARY_WITH_FLAG_CONFIG,
            RESCHEDULE_DELETE_PRIMARY_NO_FLAG_CONFIG);
    final var env = CollectionsKt.last(rundown).mutableEnv;
    // Setup booked, and the SA id was captured for the reschedule arms.
    assertThat(env.getAsString("reschedCleanStatus")).isEqualTo("Success");
    assertThat(env.getAsString("reschedCleanSaId")).isNotNull();
    // Arm A: isPrimaryResource on a Delete entry → clean INVALID_INPUT (payload-field guard), HTTP 400.
    assertThat(env.getAsString("reschedWithFlagErrorCode")).isEqualTo("INVALID_INPUT");
    assertThat(env.getAsString("reschedWithFlagHttpCode")).isEqualTo("400");
    // Arm B: delete the primary WITHOUT the flag → Success, leaving a zero-primary crew (refutes the doc).
    assertThat(env.getAsString("reschedNoFlagStatus")).isEqualTo("Success");
  }
```

- [ ] **Step 9: Compile**

Run: `gradle compileIntegrationTestJava`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 10: Run live, record verdicts**

Run: `gradle integrationTest --tests "com.salesforce.revoman.integration.core.wfs.WfsWritePathParityE2ETest.testRescheduleNoPrimaryE2E"`
Expected: PASS. Console: `DEC4z clean … status=Success saId=…`, `DEC4z armA http=400 ec=INVALID_INPUT …`, `DEC4z armB … status=Success`.
LIVE-ADJUST RULES (record any in the decision log; do NOT change Core):
- If Arm B fails because the single remaining non-primary resourceB trips a rule, switch Arm B to delete BOTH resources (add a second `DeleteOperation` entry for `requiredNonReqResourceBId`, no flag) — the func-test-proven delete-all path — still demonstrating "reschedule to no-primary succeeds."
- If the reschedule needs `startTime`/`endTime`, add `"startTime": "{{reschedWindowStart}}", "endTime": "{{reschedWindowEnd}}"` to the reschedule bodies (same window; resourceB is available there).
- If Arm A's errorCode/message differs, encode the observed value.

- [ ] **Step 11: Commit**

```bash
git add src/integrationTest/resources/pm-templates/v3/core/wfs/booking/schedule-two-resource-clean \
        src/integrationTest/resources/pm-templates/v3/core/wfs/booking/reschedule-delete-primary-with-flag \
        src/integrationTest/resources/pm-templates/v3/core/wfs/booking/reschedule-delete-primary-no-flag \
        src/integrationTest/java/com/salesforce/revoman/integration/core/wfs/ReVomanConfigForWfs.java \
        src/integrationTest/java/com/salesforce/revoman/integration/core/wfs/WfsWritePathParityE2ETest.java
git commit -m "test(wfs): Decision 4z reschedule CAN leave no primary (refutes doc claim)"
```

---

## Task 4: Decision 2 — a shown slot is a promise on the cheap (availability) check (read==write)

**Files:**
- Create: `…/booking/get-slots-parity-available/.resources/definition.yaml`
- Create: `…/booking/get-slots-parity-available/10-get-slots-parity-available.request.yaml`
- Create: `…/booking/get-slots-parity-unavailable/.resources/definition.yaml`
- Create: `…/booking/get-slots-parity-unavailable/10-get-slots-parity-unavailable.request.yaml`
- Create: `…/booking/schedule-parity-unavailable/.resources/definition.yaml`
- Create: `…/booking/schedule-parity-unavailable/10-schedule-parity-unavailable.request.yaml`
- Create: `…/booking/schedule-parity-available/.resources/definition.yaml`
- Create: `…/booking/schedule-parity-available/10-schedule-parity-available.request.yaml`
- Modify: `…/wfs/ReVomanConfigForWfs.java`
- Modify: `…/wfs/WfsReadPathParityE2ETest.java`

**Interfaces:**
- Consumes: `AUTH_CONFIG`, `AVAILABILITY_OP_HOURS_POLICY_CONFIG`, `REQUIRED_NON_REQUIRED_FIXTURE_CONFIG`; env `availabilityOpHoursPolicyId`, `requiredNonReqWorkTypeId`, `requiredNonReqTerritoryId`, `requiredNonReqAccountId`, `requiredNonReqResourceAId`.
- Produces: `Kick GET_SLOTS_PARITY_AVAILABLE_CONFIG`, `Kick GET_SLOTS_PARITY_UNAVAILABLE_CONFIG`, `Kick SCHEDULE_PARITY_UNAVAILABLE_CONFIG`, `Kick SCHEDULE_PARITY_AVAILABLE_CONFIG`; env keys `parityReadAvailSlotCount`, `parityReadUnavailSlotCount`, `parityWriteUnavailStatus`, `parityWriteAvailStatus`.

Window contract for this task: resourceA (in `required-non-required`) has member OH 08-16 + a Confirmed Shift 08-16 tomorrow. **Available window** = tomorrow 11:00-12:00 UTC (inside 08-16). **Unavailable window** = tomorrow 17:00-18:00 UTC (outside 08-16 → availability cheap-check fails). GetAppointmentSlots searches WITHIN the requested window, so a 17:00-18:00 request yields no slots; a Schedule into 17:00-18:00 is availability-rejected.

- [ ] **Step 1: Create the available get-slots definition.yaml**

`booking/get-slots-parity-available/.resources/definition.yaml`:
```yaml
$kind: collection
description: |-
  Decision 2 — GetAppointmentSlots for resourceA in the AVAILABLE window (tomorrow 11:00-12:00 UTC, inside
  the resource's member OH 08-16 + Confirmed Shift). The cheap availability check passes, so the read path
  OFFERS slots (count > 0). Paired with the AVAILABLE write act, this proves a shown slot is a PROMISE on
  the cheap checks the read and write paths SHARE.
auth:
  - id: 1a2b3c4d-0002-4aaa-9bbb-000000000021
    type: bearer
    name: bearer auth
    credentials:
      token: "{{managerToken}}"
```

- [ ] **Step 2: Create the available get-slots request.yaml**

`booking/get-slots-parity-available/10-get-slots-parity-available.request.yaml`:
```yaml
$kind: http-request
description: >-
  GetAppointmentSlots, resourceA, AVAILABLE window tomorrow 11:00-12:00 UTC. Read offers slots (>0) because
  the shared cheap availability check passes. Counts data.result[].slots[].
url: "{{baseUrl}}{{versionPath}}/connect/unified-scheduling/actions/get-appointment-slots"
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
          "schedulingPolicyId": "{{availabilityOpHoursPolicyId}}",
          "coreDetails": {
            "startTime": "{{parityAvailStart}}",
            "endTime": "{{parityAvailEnd}}",
            "appointmentMode": "Regular",
            "workConfiguration": { "workTypeId": "{{requiredNonReqWorkTypeId}}" },
            "locationConstraints": { "addressIds": ["{{requiredNonReqTerritoryId}}"] }
          },
          "serviceAppointmentParent": { "id": "{{requiredNonReqAccountId}}" },
          "assignedResources": [ { "isRequiredResource": true, "serviceResourceId": "{{requiredNonReqResourceAId}}" } ]
        }
      ]
    }
scripts:
  - type: beforeRequest
    code: |-
      const s = new Date(); s.setUTCDate(s.getUTCDate() + 1); s.setUTCHours(11, 0, 0, 0);
      const e = new Date(s.getTime() + 60 * 60 * 1000);
      pm.environment.set("parityAvailStart", s.toISOString());
      pm.environment.set("parityAvailEnd", e.toISOString());
    language: text/javascript
  - type: afterResponse
    code: |-
      const data = pm.response.json() || {};
      const results = data.result || [];
      let n = 0; results.forEach(r => { n += (r.slots || []).length; });
      pm.environment.set("parityReadAvailSlotCount", String(n));
      console.log("DEC2 read-avail http=" + pm.response.code + " slots=" + n);
    language: text/javascript
order: 1000
```

- [ ] **Step 3: Create the unavailable get-slots definition.yaml**

`booking/get-slots-parity-unavailable/.resources/definition.yaml`:
```yaml
$kind: collection
description: |-
  Decision 2 — GetAppointmentSlots for resourceA in the UNAVAILABLE window (tomorrow 17:00-18:00 UTC,
  OUTSIDE the resource's member OH 08-16 / Shift). The cheap availability check fails, so the read path
  offers NO slots (count == 0). Paired with the UNAVAILABLE write act, this proves the read↔write
  agreement on the cheap check (a not-shown slot is consistently not bookable).
auth:
  - id: 1a2b3c4d-0002-4aaa-9bbb-000000000022
    type: bearer
    name: bearer auth
    credentials:
      token: "{{managerToken}}"
```

- [ ] **Step 4: Create the unavailable get-slots request.yaml**

`booking/get-slots-parity-unavailable/10-get-slots-parity-unavailable.request.yaml`:
```yaml
$kind: http-request
description: >-
  GetAppointmentSlots, resourceA, UNAVAILABLE window tomorrow 17:00-18:00 UTC (outside member OH 08-16).
  Read offers 0 slots (the shared cheap availability check fails). Counts data.result[].slots[].
url: "{{baseUrl}}{{versionPath}}/connect/unified-scheduling/actions/get-appointment-slots"
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
          "schedulingPolicyId": "{{availabilityOpHoursPolicyId}}",
          "coreDetails": {
            "startTime": "{{parityUnavailStart}}",
            "endTime": "{{parityUnavailEnd}}",
            "appointmentMode": "Regular",
            "workConfiguration": { "workTypeId": "{{requiredNonReqWorkTypeId}}" },
            "locationConstraints": { "addressIds": ["{{requiredNonReqTerritoryId}}"] }
          },
          "serviceAppointmentParent": { "id": "{{requiredNonReqAccountId}}" },
          "assignedResources": [ { "isRequiredResource": true, "serviceResourceId": "{{requiredNonReqResourceAId}}" } ]
        }
      ]
    }
scripts:
  - type: beforeRequest
    code: |-
      const s = new Date(); s.setUTCDate(s.getUTCDate() + 1); s.setUTCHours(17, 0, 0, 0);
      const e = new Date(s.getTime() + 60 * 60 * 1000);
      pm.environment.set("parityUnavailStart", s.toISOString());
      pm.environment.set("parityUnavailEnd", e.toISOString());
    language: text/javascript
  - type: afterResponse
    code: |-
      const data = pm.response.json() || {};
      const results = data.result || [];
      let n = 0; results.forEach(r => { n += (r.slots || []).length; });
      pm.environment.set("parityReadUnavailSlotCount", String(n));
      console.log("DEC2 read-unavail http=" + pm.response.code + " slots=" + n);
    language: text/javascript
order: 1100
```

- [ ] **Step 5: Create the unavailable schedule definition.yaml**

`booking/schedule-parity-unavailable/.resources/definition.yaml`:
```yaml
$kind: collection
description: |-
  Decision 2 — Schedule resourceA into the UNAVAILABLE window (tomorrow 17:00-18:00 UTC). The write path
  re-runs the SAME cheap availability check the read path used, so the booking is REJECTED (not Success).
  This matches the read path offering 0 slots for the same window: read==write on the cheap check. Runs
  before the available write so its (rejected, non-persisting) attempt cannot affect availability.
auth:
  - id: 1a2b3c4d-0002-4aaa-9bbb-000000000023
    type: bearer
    name: bearer auth
    credentials:
      token: "{{managerToken}}"
```

- [ ] **Step 6: Create the unavailable schedule request.yaml**

`booking/schedule-parity-unavailable/10-schedule-parity-unavailable.request.yaml`:
```yaml
$kind: http-request
description: >-
  Schedule resourceA into the UNAVAILABLE window tomorrow 17:00-18:00 UTC → rejected (availability). Single
  required+primary resource so the only thing that can block it is the shared cheap availability check.
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
          "schedulingPolicyId": "{{availabilityOpHoursPolicyId}}",
          "coreDetails": {
            "startTime": "{{parityUnavailStart}}",
            "endTime": "{{parityUnavailEnd}}",
            "status": "Scheduled",
            "subject": "Decision 2 parity unavailable write",
            "workConfiguration": { "workTypeId": "{{requiredNonReqWorkTypeId}}" },
            "locationConstraints": { "id": "{{requiredNonReqTerritoryId}}" }
          },
          "serviceAppointmentParent": { "id": "{{requiredNonReqAccountId}}" },
          "assignedResources": [ { "isPrimaryResource": true, "isRequiredResource": true, "serviceResourceId": "{{requiredNonReqResourceAId}}" } ]
        }
      ]
    }
scripts:
  - type: beforeRequest
    code: |-
      const s = new Date(); s.setUTCDate(s.getUTCDate() + 1); s.setUTCHours(17, 0, 0, 0);
      const e = new Date(s.getTime() + 60 * 60 * 1000);
      pm.environment.set("parityUnavailStart", s.toISOString());
      pm.environment.set("parityUnavailEnd", e.toISOString());
    language: text/javascript
  - type: afterResponse
    code: |-
      pm.environment.set("parityWriteUnavailHttpCode", String(pm.response.code));
      const data = pm.response.json();
      const appts = (data && data.appointments) || [];
      pm.environment.set("parityWriteUnavailStatus", appts.length ? appts[0].schedulingStatus : null);
      let ec = null;
      if (Array.isArray(data) && data.length) { ec = data[0].errorCode; }
      else if (appts.length && appts[0].errors && appts[0].errors[0]) { ec = appts[0].errors[0].errorCode; }
      pm.environment.set("parityWriteUnavailErrorCode", ec);
      console.log("DEC2 write-unavail http=" + pm.response.code + " status=" + pm.environment.get("parityWriteUnavailStatus") + " ec=" + ec);
    language: text/javascript
order: 1200
```

- [ ] **Step 7: Create the available schedule definition.yaml**

`booking/schedule-parity-available/.resources/definition.yaml`:
```yaml
$kind: collection
description: |-
  Decision 2 — Schedule resourceA into the AVAILABLE window (tomorrow 11:00-12:00 UTC, the window the read
  path offered slots for). Books Success: the slot the read path SHOWED is a PROMISE on the shared cheap
  checks — booking into it succeeds. This is the positive half of the read==write cheap-check parity.
  Runs LAST (it persists an SA).
auth:
  - id: 1a2b3c4d-0002-4aaa-9bbb-000000000024
    type: bearer
    name: bearer auth
    credentials:
      token: "{{managerToken}}"
```

- [ ] **Step 8: Create the available schedule request.yaml**

`booking/schedule-parity-available/10-schedule-parity-available.request.yaml`:
```yaml
$kind: http-request
description: >-
  Schedule resourceA into the AVAILABLE window tomorrow 11:00-12:00 UTC → Success. The read path offered
  slots here; the write path keeps that promise on the shared cheap checks.
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
          "schedulingPolicyId": "{{availabilityOpHoursPolicyId}}",
          "coreDetails": {
            "startTime": "{{parityAvailStart}}",
            "endTime": "{{parityAvailEnd}}",
            "status": "Scheduled",
            "subject": "Decision 2 parity available write",
            "workConfiguration": { "workTypeId": "{{requiredNonReqWorkTypeId}}" },
            "locationConstraints": { "id": "{{requiredNonReqTerritoryId}}" }
          },
          "serviceAppointmentParent": { "id": "{{requiredNonReqAccountId}}" },
          "assignedResources": [ { "isPrimaryResource": true, "isRequiredResource": true, "serviceResourceId": "{{requiredNonReqResourceAId}}" } ]
        }
      ]
    }
scripts:
  - type: beforeRequest
    code: |-
      const s = new Date(); s.setUTCDate(s.getUTCDate() + 1); s.setUTCHours(11, 0, 0, 0);
      const e = new Date(s.getTime() + 60 * 60 * 1000);
      pm.environment.set("parityAvailStart", s.toISOString());
      pm.environment.set("parityAvailEnd", e.toISOString());
    language: text/javascript
  - type: afterResponse
    code: |-
      const data = pm.response.json();
      const appts = (data && data.appointments) || [];
      pm.environment.set("parityWriteAvailStatus", appts.length ? appts[0].schedulingStatus : null);
      console.log("DEC2 write-avail http=" + pm.response.code + " status=" + pm.environment.get("parityWriteAvailStatus"));
    language: text/javascript
order: 1300
```

- [ ] **Step 9: Add the Kick constants**

In `ReVomanConfigForWfs.java`, after the Decision 9 block (end of the constants), add:
```java
  // ## Decision 2 — "is a shown slot a promise?" The cheap checks (skill/territory/free-busy/location/
  // excluded) are SHARED by the read and write paths, so a shown slot is a PROMISE on them. Characterized
  // with availability: read offers slots in the available window ⟺ write into it Succeeds; read offers 0
  // in the unavailable window ⟺ write is rejected. The field-match "shown-but-rejected" half is NOT
  // characterizable on 262 (the three field-match rules are OnField/ESO-internal; the live OnSite path
  // shares read==write) — see the test javadoc + decision log.
  static final Kick GET_SLOTS_PARITY_AVAILABLE_CONFIG =
      kickFor(V3_WFS_PATH + "booking/get-slots-parity-available");
  static final Kick GET_SLOTS_PARITY_UNAVAILABLE_CONFIG =
      kickFor(V3_WFS_PATH + "booking/get-slots-parity-unavailable");
  static final Kick SCHEDULE_PARITY_UNAVAILABLE_CONFIG =
      kickFor(V3_WFS_PATH + "booking/schedule-parity-unavailable");
  static final Kick SCHEDULE_PARITY_AVAILABLE_CONFIG =
      kickFor(V3_WFS_PATH + "booking/schedule-parity-available");
```
Add a class-javadoc bullet (after the `<b>9</b>` bullet):
```java
 *   <li><b>2</b> — a shown slot is a PROMISE on the cheap checks the read and write paths share
 *       (characterized with availability: read offers slots ⟺ write Succeeds; read 0 ⟺ write rejected).
```

- [ ] **Step 10: Add the test method**

In `WfsReadPathParityE2ETest.java`, add imports:
```java
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.AVAILABILITY_OP_HOURS_POLICY_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.GET_SLOTS_PARITY_AVAILABLE_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.GET_SLOTS_PARITY_UNAVAILABLE_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.SCHEDULE_PARITY_AVAILABLE_CONFIG;
import static com.salesforce.revoman.integration.core.wfs.ReVomanConfigForWfs.SCHEDULE_PARITY_UNAVAILABLE_CONFIG;
```
Add the method (after `testShiftSharingModeSplitE2E`):
```java
  /**
   * Decision 2 — "is a shown slot a promise or a suggestion?" The cheap checks the read and write paths
   * SHARE (skill / territory / free-busy / location / excluded) are a PROMISE: a slot the read path shows
   * won't be turned down at booking on them. This characterizes the VERIFIED half with the availability
   * cheap-check over a single resource: in the AVAILABLE window the read offers slots (>0) AND the write
   * Succeeds; in the UNAVAILABLE window the read offers 0 slots AND the write is rejected — read==write.
   *
   * <p>The doc's other half — a slot SHOWN by the read path yet TURNED DOWN at booking on a field-match
   * rule (Match Fields / Match Boolean / Extended Match) — is NOT characterizable on 262 through these
   * endpoints: those three rule types are OnField-only and evaluated inside the external ESO optimizer
   * (a black box to Core); the OnField path is a 262 stub and unwired, and the live InBusiness (OnSite)
   * path re-runs the identical read-path slot calc on write, so it shares read==write. The readable Core
   * code shows NO defer — matching the doc's note that "InField defers only the heavy one" is a
   * reasonable assumption, not a verified fact. So this test pins the verified cheap-check promise; the
   * field-match defer remains the doc's open question (see the decision log for citations).
   * <p>264 contrast: unchanged for the cheap checks (they stay a shared promise). If field-match deferral
   * is ever implemented in Core (not ESO), a shown slot could be turned down on a field-match rule — a
   * separate, future characterization.
   */
  @Test
  void testCheapCheckReadWritePromiseE2E() {
    final var rundown =
        ReVoman.revUp(
            (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
            AUTH_CONFIG,
            AVAILABILITY_OP_HOURS_POLICY_CONFIG,
            REQUIRED_NON_REQUIRED_FIXTURE_CONFIG,
            GET_SLOTS_PARITY_AVAILABLE_CONFIG,
            GET_SLOTS_PARITY_UNAVAILABLE_CONFIG,
            SCHEDULE_PARITY_UNAVAILABLE_CONFIG,
            SCHEDULE_PARITY_AVAILABLE_CONFIG);
    final var env = CollectionsKt.last(rundown).mutableEnv;
    // Read shows the available window and hides the unavailable one (the shared cheap check).
    assertThat(Integer.parseInt(env.getAsString("parityReadAvailSlotCount"))).isGreaterThan(0);
    assertThat(env.getAsString("parityReadUnavailSlotCount")).isEqualTo("0");
    // Write keeps the promise: shown ⟺ Success, not-shown ⟺ rejected.
    assertThat(env.getAsString("parityWriteAvailStatus")).isEqualTo("Success");
    assertThat(env.getAsString("parityWriteUnavailStatus")).isNotEqualTo("Success");
  }
```

- [ ] **Step 11: Compile**

Run: `gradle compileIntegrationTestJava`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 12: Run live, record verdicts**

Run: `gradle integrationTest --tests "com.salesforce.revoman.integration.core.wfs.WfsReadPathParityE2ETest.testCheapCheckReadWritePromiseE2E"`
Expected: PASS. Console: `DEC2 read-avail … slots>0`, `DEC2 read-unavail … slots=0`, `DEC2 write-unavail … status!=Success`, `DEC2 write-avail … status=Success`.
LIVE-ADJUST RULES (record in the decision log; do NOT change Core):
- If the available read returns 0 slots, the slot-search window may need widening (e.g. request 08:00-16:00 and assert ≥1 slot) — the cheap check should still admit resourceA inside its OH. Widen the AVAILABLE read window; keep the AVAILABLE write at a concrete 60m slot inside it.
- If GetAppointmentSlots rejects `assignedResources` in the read body, drop it and use top-level `filterByResources: ["{{requiredNonReqResourceAId}}"]` instead (per the openapi read schema).
- If the unavailable write returns Success (would mean the write path does NOT share the availability check — contradicts the investigation), STOP and capture it as a finding; do not weaken the assertion.

- [ ] **Step 13: Commit**

```bash
git add src/integrationTest/resources/pm-templates/v3/core/wfs/booking/get-slots-parity-available \
        src/integrationTest/resources/pm-templates/v3/core/wfs/booking/get-slots-parity-unavailable \
        src/integrationTest/resources/pm-templates/v3/core/wfs/booking/schedule-parity-unavailable \
        src/integrationTest/resources/pm-templates/v3/core/wfs/booking/schedule-parity-available \
        src/integrationTest/java/com/salesforce/revoman/integration/core/wfs/ReVomanConfigForWfs.java \
        src/integrationTest/java/com/salesforce/revoman/integration/core/wfs/WfsReadPathParityE2ETest.java
git commit -m "test(wfs): Decision 2 shown-slot-is-a-promise on shared cheap checks (read==write)"
```

---

## Task 5: Decision log + bug handoff + final review

**Files:**
- Create: `~/work/impl-decisions/2026-06-30-decision2-4-4z-5-revoman-tests.md`
- Create/append: `~/work/handoff/2026-06-30-wfs-doc-4z-no-primary-contradiction.md` + update `~/work/handoff/hand-off-log.md`
- Modify (if a report exists): `docs/superpowers/2026-06-30-wfs-parity-test-report.md` (append Decisions 2/4/4z/5)

- [ ] **Step 1: Write the decision log**

Create `~/work/impl-decisions/2026-06-30-decision2-4-4z-5-revoman-tests.md` capturing, per the autonomous-mode handoff instructions:
- **Spec-vs-reality divergences** (the headline findings): 4 = current code already returns the clean `INVALID_INPUT` (doc's "confusing DB error" was the older 262); 4z = the doc's "not possible to reschedule without a primary" is **WRONG** (reschedule explicitly allows zero primaries; the quoted error is a payload-field guard, not a crew rule); 5 = no auto-correct and no double-book (reject), refuting both the story's expectation and the 262 double-book claim; 2 = the field-match "shown-but-rejected" half is **not characterizable on 262** (OnField/ESO black box), so only the verified cheap-check promise is tested.
- **The observed Decision-5 response shape** (the previously-uncertain top-level vs `appointments[0].errors[0]` — fill from the live run).
- **Design decisions:** assert-262 / 264-in-javadoc; Approach A; reuse `required-non-required` + `availability-op-hours-policy` (no new lift); Decision 2 placed in the read-path class.
- **Deviations:** none to Core; any live-adjust rules that fired (4z Arm B delete-all fallback / reschedule times; Decision 2 window widening / `filterByResources`).
- **Tradeoffs:** Decision 2 characterizes the verified half rather than attempting an un-observable ESO field-match defer; verbatim-vs-class assertions (chose `contains`/`isEqualTo` on the stable errorCode, `contains` on message substrings).
- **Open questions for the user:** (4z) confirm the product intends to ALLOW reschedule-to-no-primary (today it does) or wants the gap closed; (5) confirm reject (not auto-correct) is the intended resolution of the doc's open question; (2) the field-match defer remains an open product/perf question, unverifiable from Core source.

- [ ] **Step 2: Write the out-of-scope bug/doc handoff**

Create `~/work/handoff/2026-06-30-wfs-doc-4z-no-primary-contradiction.md` (self-contained, per `~/work/handoff/CLAUDE.md`): the product decision doc (`2026-06-21-PRODUCT-presentation-read-write-parity.md`, Decision 4z) states "It is not possible to schedule or reschedule an appointment without a primary resource" — this is **incorrect for the reschedule API**. Provide the repro (the two reschedule arms), the code citations (`RescheduleCommonValidator.validatePrimaryResourceCount:217-226` "allow zero primaries"; `validateDeleteOperationFields:200-204` payload-field guard; func test `testRescheduleAppointmentDeleteAllAssignedResources:648`), and the fix direction (correct the doc's verdict; the product OPEN QUESTION "should reschedule be allowed to leave no primary" is a real decision — today it is allowed). This is a DOC bug, not a Core code bug. Append a one-line entry to `~/work/handoff/hand-off-log.md`.

- [ ] **Step 3: Holistic review (best review agent)**

Dispatch the strongest review agent over the whole new diff with a HOLISTIC brief — review not only the code but each SCENARIO from all angles: (a) does each fixture/window isolate the rule under test (no confounds)? (b) does each assertion actually PROVE the decision's claim (not a weaker proxy)? (c) are the 262-asserted / 264-contrast javadocs accurate against the investigated code citations? (d) are the dual-shape error captures correct? (e) is the ledger-off / ignore-HTTP-status hygiene right on every act? (f) any orphaned Kick constants, any act-ordering hazard (persisting act before a read on the same fixture)? Fold findings back per the receiving-code-review skill.

- [ ] **Step 4: Commit the docs**

```bash
git add docs/superpowers/specs docs/superpowers/plans
git commit -m "docs(wfs): decision log + plan for Decisions 2/4/4z/5"
```
(The `~/work/impl-decisions` and `~/work/handoff` files are outside the repo; they are not committed here.)

## Self-Review (completed)

- **Spec coverage:** Decision 4 → Task 1; Decision 5 → Task 2; Decision 4z → Task 3; Decision 2 → Task 4; decision-log/handoff/review → Task 5. All four scenarios + the autonomous-mode reporting covered.
- **Placeholder scan:** every act body, script, Kick, and test method is shown in full; no TBD/TODO.
- **Type/name consistency:** Kick names and env keys produced by each task are consumed verbatim by that task's test method; `CollectionsKt.last(rundown).mutableEnv` + `getAsString`/`containsEntry`/`Integer.parseInt` match the existing tests' idioms; `firstUnIgnoredUnsuccessfulStepReport()` halt-guard matches the suite.
- **Live-run reality:** each task encodes the investigated expected verdict and includes explicit LIVE-ADJUST rules (record divergences, never weaken an assertion to force a pass, never edit Core).
