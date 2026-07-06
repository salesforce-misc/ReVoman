# Prior-Assignment Occupancy Parity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add one ReVoman parity test proving whether a worker's prior appointment assignment occupies their time and blocks a later *required* booking on an overlapping appointment — even when the worker joined the first appointment as an optional (non-required) helper — asserting old Scheduler and Unified agree across all four first-booking × second-booking combinations.

**Architecture:** A single new `@Test testPriorAssignmentOccupancyParity_E2E` in `SchedulerVsUnifiedParityE2ETest`, built like `testRescheduleNoPrimaryParity_4z_E2E`: per matrix cell, a fresh `ReVoman.revUp(...)` chains AUTH → FIXTURE → GRANT → book appt #1 → book appt #2, capturing the appt-#2 outcome into the run env. Both products are exercised; each cell asserts `oldOutcome == unifiedOutcome`. A new 3-resource fixture makes worker B genuinely available at 11:00 (unlike the double-book fixture's shift gap), so B's prior assignment is the only possible blocker; a dedicated free primary C on appt #2 removes the shared-primary confound.

**Tech Stack:** Java 21 + JUnit5 + AssertJ (`assertThat`), ReVoman (`Kick`, `ReVoman.revUp`), Postman V3 collections (`*.request.yaml`) executed against a live Salesforce 262 org over Connect REST (`/connect/scheduling/service-appointments`) and Unified Connect (`/connect/unified-scheduling/actions/schedule`). Build/run with `gradle` (never `./gradlew`).

## Global Constraints

- JDK 21+; `gradle` for all builds/tests (never `./gradlew`).
- Postman V3 YAML: NEVER use `#` comments in collection/env YAML (corrupts import); use `description:` or JS-block `//` inside `scripts`.
- Old side reads creds from `~/.revoman/scheduler-config.yaml`; Unified side from `~/.revoman/config.yaml`. Absent → test JUnit-skips via `SchedulerParityConfig.assumeBothOrgCreds()`.
- Every old-side Kick is built by `SchedulerParityConfig.oldKickFor(templatePath)`; every WFS Kick by `ReVomanConfigForWfs.kickFor(templatePath)`. Do not hand-roll `Kick.configure()`.
- Act steps under assertion carry headers `x-revoman-ledger: "off"` and `ignoreHTTPStatusUnsuccessful: "true"` so a legitimate refusal (non-2xx) is read from the captured status, not treated as a step failure.
- Fresh timestamped users minted per run (`{{$timestamp}}` in usernames); no cleanup. Never reuse fixed usernames (a pre-existing `ResourceType='T'` ServiceResource → `DUPLICATE_VALUE` rolls back the whole graph).
- BOOKED requires a real 18-char ServiceAppointment id starting `08p`; AssignedResource ids start `03r`.
- Copyright header (verbatim) on every new `.java` file:
  ```java
  /*
   * Copyright 2026 salesforce.com, inc.
   * All Rights Reserved
   * Company Confidential
   */
  ```
- Spec: `docs/superpowers/specs/2026-07-06-prior-assignment-occupancy-parity-design.md`.

## File Map

**Create — old-side (scheduler org) Postman collections under `src/integrationTest/resources/pm-templates/v3/core/scheduler/`:**
- `fixtures/prior-assignment/create-prior-assignment-graph.request.yaml` — 3-resource graph, B available, 2 accounts.
- `booking/service-appointments-prior-appt1-b-required/10-book.request.yaml` — appt #1: A primary+required, B required.
- `booking/service-appointments-prior-appt1-b-optional/10-book.request.yaml` — appt #1: A primary+required, B optional.
- `booking/service-appointments-prior-appt2-b-required/10-book.request.yaml` — appt #2: C primary+required, B required.
- `booking/service-appointments-prior-appt2-b-optional/10-book.request.yaml` — appt #2: C primary+required, B optional.

**Create — WFS-side (Unified) collections under `src/integrationTest/resources/pm-templates/v3/core/wfs/`:**
- `fixtures/prior-assignment/create-prior-assignment-graph.request.yaml` — Unified-org analog (adminToken, WFS env var names).
- `booking/schedule-prior-appt1-b-required/10-schedule.request.yaml`
- `booking/schedule-prior-appt1-b-optional/10-schedule.request.yaml`
- `booking/schedule-prior-appt2-b-required/10-schedule.request.yaml`
- `booking/schedule-prior-appt2-b-optional/10-schedule.request.yaml`

**Modify — Java config + test:**
- `src/integrationTest/java/com/salesforce/revoman/integration/core/scheduler/SchedulerParityConfig.java` — add 5 `OLD_PRIOR_*` Kick constants.
- `src/integrationTest/java/com/salesforce/revoman/integration/core/wfs/ReVomanConfigForWfs.java` — add 5 `PRIOR_*` Kick constants.
- `src/integrationTest/java/com/salesforce/revoman/integration/core/scheduler/SchedulerVsUnifiedParityE2ETest.java` — add `testPriorAssignmentOccupancyParity_E2E`.

**Reference (read, do not modify) — clone sources:**
- `.../scheduler/fixtures/double-book/create-double-book-graph.request.yaml` (fixture template).
- `.../scheduler/booking/service-appointments-clean-two-resource/10-book-clean.request.yaml` (book shape).
- `.../wfs/booking/schedule-double-book-required-conflict/10-schedule-double-book-required-conflict.request.yaml` (Unified schedule shape).

> **Verification model note:** these are live-org integration tests, not unit tests. There is no local red/green cycle for fixtures/collections in isolation — the real red→green is Task 8 (the test runs against the org). Per-task verification is therefore: YAML parses / Java compiles (`gradle testClasses` — actually `compileIntegrationTestJava`), and each infra task is committed on its own so a reviewer can gate it. Task 7 writes the test (it compiles but will fail live until the org is confirmed); Task 8 runs it live and is where behavior is proven.

---

### Task 1: Old-side fixture — 3-resource prior-assignment graph

**Files:**
- Create: `src/integrationTest/resources/pm-templates/v3/core/scheduler/fixtures/prior-assignment/create-prior-assignment-graph.request.yaml`
- Create: `src/integrationTest/resources/pm-templates/v3/core/scheduler/fixtures/prior-assignment/.resources/definition.yaml` (empty marker, mirror the double-book one)

**Interfaces:**
- Produces (env vars after run): `schedTerritoryId`, `schedWorkTypeId`, `schedAccountId`, `schedAccountId2`, `schedResourceAId`, `schedResourceBId`, `schedResourceCId`.

**Clone source:** `.../fixtures/double-book/create-double-book-graph.request.yaml`. Diffs from that file: (1) add a third User `refUserC` + ServiceResource `refResourceC` + member OH C + its 7 TimeSlots (10:00–14:00) + STM C + Shift C; (2) **change member OH B and Shift B from 12:00–14:00 to 10:00–14:00** so B is available at 11:00; (3) add a second Account `refAccount2`; (4) capture `schedResourceCId` and `schedAccountId2` in the afterResponse script.

- [ ] **Step 1: Copy the double-book graph as the starting point**

```bash
cd /home/sfwork/code-clones/work/revoman-root
mkdir -p src/integrationTest/resources/pm-templates/v3/core/scheduler/fixtures/prior-assignment/.resources
cp src/integrationTest/resources/pm-templates/v3/core/scheduler/fixtures/double-book/create-double-book-graph.request.yaml \
   src/integrationTest/resources/pm-templates/v3/core/scheduler/fixtures/prior-assignment/create-prior-assignment-graph.request.yaml
cp src/integrationTest/resources/pm-templates/v3/core/scheduler/fixtures/double-book/.resources/definition.yaml \
   src/integrationTest/resources/pm-templates/v3/core/scheduler/fixtures/prior-assignment/.resources/definition.yaml
```

- [ ] **Step 2: Rewrite the `description` block** to describe the 3-resource / B-available / 2-account intent. Replace the existing `description: >- … order: 500`-era prose. Use this exact `description`:

```yaml
description: >-
  OLD scheduler-org PRIOR-ASSIGNMENT data graph (clone of the double-book graph, retargeted for the
  occupancy test). THREE User-backed ServiceResources (resourceA/B/C on three fresh timestamped users),
  all PRIMARY ServiceTerritoryMembers, ALL fully available over 11:00-11:30 (member OH + Confirmed Shift
  10:00-14:00 each). Unlike the double-book graph, resourceB is AVAILABLE at 11:00 — so B's shift is NOT
  the discriminator; the only thing that can block a second overlapping booking of B is B's assignment on
  the first appointment. resourceA is appt #1's primary, resourceC is appt #2's dedicated free primary
  (so a refused appt #2 cannot be blamed on the primary being double-booked). TWO Accounts so the two
  appointments are independent parents. Sets schedTerritoryId / schedWorkTypeId / schedAccountId /
  schedAccountId2 / schedResourceAId / schedResourceBId / schedResourceCId. Fresh timestamped users per
  run (the proven double-book pattern) so the ServiceResource (RelatedRecordId, ResourceType) uniqueness
  key never collides.
```

- [ ] **Step 3: Add the third User and its ServiceResource.** In the `compositeRequest` array, after the `refUserB` User POST, add a `refUserC` User POST (copy `refUserB`'s block, change `referenceId` to `refUserC`, `Username`/`Email` to `{{schedUserCName}}`, `Alias` to `sres-c`, `LastName` to `ResourceC`). After the `refResourceB` ServiceResource POST, add:

```json
{
  "method": "POST",
  "url": "{{versionPath}}/sobjects/ServiceResource",
  "referenceId": "refResourceC",
  "body": { "Name": "PriorAssign Resource C {{$timestamp}}", "RelatedRecordId": "@{refUserC.id}", "ResourceType": "T", "IsActive": true, "IsPrimary": true }
}
```

- [ ] **Step 4: Add member OH C + 7 TimeSlots (10:00–14:00).** After the `refMemberOhB` OperatingHours POST add a `refMemberOhC` OperatingHours POST (`Name: "PriorAssign Member OH C {{$timestamp}}"`, `TimeZone: "GMT"`). After the `refMemberBtsSun` TimeSlot, add 7 TimeSlots for `@{refMemberOhC.id}` (Monday–Sunday), each `StartTime: "10:00:00.000Z"`, `EndTime: "14:00:00.000Z"`, `Type: "Normal"`, `referenceId` `refMemberCtsMon`…`refMemberCtsSun`.

- [ ] **Step 5: Fix member OH B TimeSlots to 10:00–14:00.** In every `refMemberBts*` TimeSlot (Mon–Sun), change `StartTime` from `"12:00:00.000Z"` to `"10:00:00.000Z"` (EndTime already `14:00:00.000Z`). This is what makes B available at 11:00.

- [ ] **Step 6: Add STM C + Shift C.** After `refStmB` ServiceTerritoryMember, add:

```json
{
  "method": "POST",
  "url": "{{versionPath}}/sobjects/ServiceTerritoryMember",
  "referenceId": "refStmC",
  "body": { "ServiceTerritoryId": "@{refTerritory.id}", "ServiceResourceId": "@{refResourceC.id}", "TerritoryType": "P", "OperatingHoursId": "@{refMemberOhC.id}", "EffectiveStartDate": "{{doubleBookStmEffectiveStart}}" }
}
```
After `refShiftB` Shift, add:
```json
{
  "method": "POST",
  "url": "{{versionPath}}/sobjects/Shift",
  "referenceId": "refShiftC",
  "body": { "ServiceResourceId": "@{refResourceC.id}", "ServiceTerritoryId": "@{refTerritory.id}", "StartTime": "{{priorShiftStart}}", "EndTime": "{{priorShiftEnd}}", "TimeSlotType": "Normal", "Status": "Confirmed" }
}
```

- [ ] **Step 7: Add the second Account.** After `refAccount`, add:
```json
{
  "method": "POST",
  "url": "{{versionPath}}/sobjects/Account",
  "referenceId": "refAccount2",
  "body": { "Name": "PriorAssign Account 2 {{$timestamp}}" }
}
```

- [ ] **Step 8: Update the `beforeRequest` script** to mint the third user and set one shared shift window (10:00–14:00) for all three resources. Replace the shift-B block (which set 12:00) so B and C both use 10:00–14:00. Use this beforeRequest body:

```javascript
pm.environment.set("schedUserAName", pm.variables.replaceIn("prior-res-a-{{$timestamp}}@revoman.org"));
pm.environment.set("schedUserBName", pm.variables.replaceIn("prior-res-b-{{$timestamp}}@revoman.org"));
pm.environment.set("schedUserCName", pm.variables.replaceIn("prior-res-c-{{$timestamp}}@revoman.org"));
const monthAgo = new Date();
monthAgo.setUTCMonth(monthAgo.getUTCMonth() - 1);
pm.environment.set("doubleBookStmEffectiveStart", monthAgo.toISOString());
// All three resources share one Confirmed Shift window 10:00-14:00 (covers the 11:00-11:30 booking
// window), so every resource is AVAILABLE at 11:00 — B's availability is NOT the discriminator here.
const shiftStart = new Date();
shiftStart.setUTCDate(shiftStart.getUTCDate() + 1);
shiftStart.setUTCHours(10, 0, 0, 0);
const shiftEnd = new Date(shiftStart.getTime());
shiftEnd.setUTCHours(14, 0, 0, 0);
pm.environment.set("doubleBookShiftAStart", shiftStart.toISOString());
pm.environment.set("doubleBookShiftAEnd", shiftEnd.toISOString());
pm.environment.set("doubleBookShiftBStart", shiftStart.toISOString());
pm.environment.set("doubleBookShiftBEnd", shiftEnd.toISOString());
pm.environment.set("priorShiftStart", shiftStart.toISOString());
pm.environment.set("priorShiftEnd", shiftEnd.toISOString());
```

- [ ] **Step 9: Update the `afterResponse` script** to capture the new ids. In the existing `byRef` block, add these two lines alongside the existing `pm.environment.set` calls:

```javascript
pm.environment.set("schedResourceCId", byRef["refResourceC"]);
pm.environment.set("schedAccountId2", byRef["refAccount2"]);
```

- [ ] **Step 10: Verify the YAML parses and imports.** Run the compile-only task (collections are loaded at test time; this confirms nothing else broke):

Run: `gradle compileIntegrationTestJava`
Expected: `BUILD SUCCESSFUL` (this task adds no Java yet; it guards against an accidental edit elsewhere).

- [ ] **Step 11: Commit**

```bash
git add src/integrationTest/resources/pm-templates/v3/core/scheduler/fixtures/prior-assignment
git commit -m "test(scheduler-parity): old-side prior-assignment fixture (3 resources, B available, 2 accounts)"
```

---

### Task 2: Old-side booking collections — appt #1 and appt #2, B-flag variants

**Files:**
- Create: `.../scheduler/booking/service-appointments-prior-appt1-b-required/10-book.request.yaml`
- Create: `.../scheduler/booking/service-appointments-prior-appt1-b-optional/10-book.request.yaml`
- Create: `.../scheduler/booking/service-appointments-prior-appt2-b-required/10-book.request.yaml`
- Create: `.../scheduler/booking/service-appointments-prior-appt2-b-optional/10-book.request.yaml`

**Interfaces:**
- appt1 collections consume `schedTerritoryId`, `schedAccountId`, `schedWorkTypeId`, `schedResourceAId`, `schedResourceBId`; produce `priorAppt1Http`, `priorAppt1SaId`.
- appt2 collections consume `schedTerritoryId`, `schedAccountId2`, `schedWorkTypeId`, `schedResourceCId`, `schedResourceBId`; produce `priorAppt2Http`, `priorAppt2SaId`.

**Clone source:** `.../booking/service-appointments-clean-two-resource/10-book-clean.request.yaml`.

- [ ] **Step 1: Create the appt #1, B-required collection.** Write `service-appointments-prior-appt1-b-required/10-book.request.yaml`:

```yaml
$kind: http-request
description: >-
  PRIOR-ASSIGNMENT appt #1 (B REQUIRED): POST /connect/scheduling/service-appointments on account #1,
  tomorrow 11:00-11:30 UTC, assignedResources = resourceA (required+primary) and resourceB (required,
  non-primary). All available, so it books (HTTP 2xx, SA id). Seeds a REQUIRED prior assignment for B.
  Captures priorAppt1Http + priorAppt1SaId.
url: "{{baseUrl}}{{versionPath}}/connect/scheduling/service-appointments"
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
      "serviceAppointment": {
        "serviceTerritoryId": "{{schedTerritoryId}}",
        "parentRecordId": "{{schedAccountId}}",
        "workTypeId": "{{schedWorkTypeId}}",
        "schedStartTime": "{{schedBookStart}}",
        "schedEndTime": "{{schedBookEnd}}",
        "extendedFields": [ { "name": "Status", "value": "Scheduled" } ]
      },
      "assignedResources": [
        { "serviceResourceId": "{{schedResourceAId}}", "isRequiredResource": true, "isPrimaryResource": true },
        { "serviceResourceId": "{{schedResourceBId}}", "isRequiredResource": true, "isPrimaryResource": false }
      ]
    }
scripts:
  - type: beforeRequest
    code: |-
      const s = new Date(); s.setUTCDate(s.getUTCDate() + 1); s.setUTCHours(11, 0, 0, 0);
      const e = new Date(s.getTime() + 30 * 60 * 1000);
      pm.environment.set("schedBookStart", s.toISOString());
      pm.environment.set("schedBookEnd", e.toISOString());
    language: text/javascript
  - type: afterResponse
    code: |-
      const data = pm.response.json() || {};
      const result = data.result || data;
      pm.environment.set("priorAppt1Http", String(pm.response.code));
      pm.environment.set("priorAppt1SaId", result.serviceAppointmentId || (result.serviceAppointment && result.serviceAppointment.id) || "");
      console.log("OLD prior appt1 (B req) http=" + pm.response.code + " body=" + JSON.stringify(data).slice(0,300));
    language: text/javascript
order: 1000
```

- [ ] **Step 2: Create the appt #1, B-optional collection.** Write `service-appointments-prior-appt1-b-optional/10-book.request.yaml` — identical to Step 1 except: the `description` says `(B OPTIONAL)` and "Seeds an OPTIONAL prior assignment for B"; the resourceB line uses `"isRequiredResource": false`; the console.log says `(B opt)`. Full body:

```yaml
$kind: http-request
description: >-
  PRIOR-ASSIGNMENT appt #1 (B OPTIONAL): POST /connect/scheduling/service-appointments on account #1,
  tomorrow 11:00-11:30 UTC, assignedResources = resourceA (required+primary) and resourceB (non-required,
  non-primary). All available, so it books (HTTP 2xx, SA id). Seeds an OPTIONAL prior assignment for B.
  Captures priorAppt1Http + priorAppt1SaId.
url: "{{baseUrl}}{{versionPath}}/connect/scheduling/service-appointments"
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
      "serviceAppointment": {
        "serviceTerritoryId": "{{schedTerritoryId}}",
        "parentRecordId": "{{schedAccountId}}",
        "workTypeId": "{{schedWorkTypeId}}",
        "schedStartTime": "{{schedBookStart}}",
        "schedEndTime": "{{schedBookEnd}}",
        "extendedFields": [ { "name": "Status", "value": "Scheduled" } ]
      },
      "assignedResources": [
        { "serviceResourceId": "{{schedResourceAId}}", "isRequiredResource": true, "isPrimaryResource": true },
        { "serviceResourceId": "{{schedResourceBId}}", "isRequiredResource": false, "isPrimaryResource": false }
      ]
    }
scripts:
  - type: beforeRequest
    code: |-
      const s = new Date(); s.setUTCDate(s.getUTCDate() + 1); s.setUTCHours(11, 0, 0, 0);
      const e = new Date(s.getTime() + 30 * 60 * 1000);
      pm.environment.set("schedBookStart", s.toISOString());
      pm.environment.set("schedBookEnd", e.toISOString());
    language: text/javascript
  - type: afterResponse
    code: |-
      const data = pm.response.json() || {};
      const result = data.result || data;
      pm.environment.set("priorAppt1Http", String(pm.response.code));
      pm.environment.set("priorAppt1SaId", result.serviceAppointmentId || (result.serviceAppointment && result.serviceAppointment.id) || "");
      console.log("OLD prior appt1 (B opt) http=" + pm.response.code + " body=" + JSON.stringify(data).slice(0,300));
    language: text/javascript
order: 1000
```

- [ ] **Step 3: Create the appt #2, B-required collection.** Write `service-appointments-prior-appt2-b-required/10-book.request.yaml` — primary is resourceC, parent is `schedAccountId2`, B is required:

```yaml
$kind: http-request
description: >-
  PRIOR-ASSIGNMENT appt #2 (B REQUIRED): POST /connect/scheduling/service-appointments on account #2, SAME
  tomorrow 11:00-11:30 UTC window, assignedResources = resourceC (required+primary, a DEDICATED free
  primary so a refusal cannot be blamed on the primary) and resourceB (required, non-primary). Because B
  is required here it IS availability/occupancy-checked; if B's appt #1 assignment occupies the window this
  is REFUSED. Captures priorAppt2Http + priorAppt2SaId.
url: "{{baseUrl}}{{versionPath}}/connect/scheduling/service-appointments"
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
      "serviceAppointment": {
        "serviceTerritoryId": "{{schedTerritoryId}}",
        "parentRecordId": "{{schedAccountId2}}",
        "workTypeId": "{{schedWorkTypeId}}",
        "schedStartTime": "{{schedBookStart}}",
        "schedEndTime": "{{schedBookEnd}}",
        "extendedFields": [ { "name": "Status", "value": "Scheduled" } ]
      },
      "assignedResources": [
        { "serviceResourceId": "{{schedResourceCId}}", "isRequiredResource": true, "isPrimaryResource": true },
        { "serviceResourceId": "{{schedResourceBId}}", "isRequiredResource": true, "isPrimaryResource": false }
      ]
    }
scripts:
  - type: beforeRequest
    code: |-
      const s = new Date(); s.setUTCDate(s.getUTCDate() + 1); s.setUTCHours(11, 0, 0, 0);
      const e = new Date(s.getTime() + 30 * 60 * 1000);
      pm.environment.set("schedBookStart", s.toISOString());
      pm.environment.set("schedBookEnd", e.toISOString());
    language: text/javascript
  - type: afterResponse
    code: |-
      const data = pm.response.json() || {};
      const result = data.result || data;
      pm.environment.set("priorAppt2Http", String(pm.response.code));
      pm.environment.set("priorAppt2SaId", result.serviceAppointmentId || (result.serviceAppointment && result.serviceAppointment.id) || "");
      console.log("OLD prior appt2 (B req) http=" + pm.response.code + " body=" + JSON.stringify(data).slice(0,300));
    language: text/javascript
order: 1000
```

- [ ] **Step 4: Create the appt #2, B-optional collection.** Write `service-appointments-prior-appt2-b-optional/10-book.request.yaml` — identical to Step 3 except resourceB is `"isRequiredResource": false`, description says `(B OPTIONAL)` and "Because B is non-required here it is NOT occupancy-checked → BOOKED regardless of appt #1", console.log `(B opt)`. Full body:

```yaml
$kind: http-request
description: >-
  PRIOR-ASSIGNMENT appt #2 (B OPTIONAL): POST /connect/scheduling/service-appointments on account #2, SAME
  tomorrow 11:00-11:30 UTC window, assignedResources = resourceC (required+primary) and resourceB
  (non-required, non-primary). Because B is non-required here it is NOT occupancy-checked → this BOOKS
  regardless of B's appt #1 assignment. Captures priorAppt2Http + priorAppt2SaId.
url: "{{baseUrl}}{{versionPath}}/connect/scheduling/service-appointments"
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
      "serviceAppointment": {
        "serviceTerritoryId": "{{schedTerritoryId}}",
        "parentRecordId": "{{schedAccountId2}}",
        "workTypeId": "{{schedWorkTypeId}}",
        "schedStartTime": "{{schedBookStart}}",
        "schedEndTime": "{{schedBookEnd}}",
        "extendedFields": [ { "name": "Status", "value": "Scheduled" } ]
      },
      "assignedResources": [
        { "serviceResourceId": "{{schedResourceCId}}", "isRequiredResource": true, "isPrimaryResource": true },
        { "serviceResourceId": "{{schedResourceBId}}", "isRequiredResource": false, "isPrimaryResource": false }
      ]
    }
scripts:
  - type: beforeRequest
    code: |-
      const s = new Date(); s.setUTCDate(s.getUTCDate() + 1); s.setUTCHours(11, 0, 0, 0);
      const e = new Date(s.getTime() + 30 * 60 * 1000);
      pm.environment.set("schedBookStart", s.toISOString());
      pm.environment.set("schedBookEnd", e.toISOString());
    language: text/javascript
  - type: afterResponse
    code: |-
      const data = pm.response.json() || {};
      const result = data.result || data;
      pm.environment.set("priorAppt2Http", String(pm.response.code));
      pm.environment.set("priorAppt2SaId", result.serviceAppointmentId || (result.serviceAppointment && result.serviceAppointment.id) || "");
      console.log("OLD prior appt2 (B opt) http=" + pm.response.code + " body=" + JSON.stringify(data).slice(0,300));
    language: text/javascript
order: 1000
```

- [ ] **Step 5: Verify compile still clean**

Run: `gradle compileIntegrationTestJava`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add src/integrationTest/resources/pm-templates/v3/core/scheduler/booking/service-appointments-prior-appt1-b-required \
        src/integrationTest/resources/pm-templates/v3/core/scheduler/booking/service-appointments-prior-appt1-b-optional \
        src/integrationTest/resources/pm-templates/v3/core/scheduler/booking/service-appointments-prior-appt2-b-required \
        src/integrationTest/resources/pm-templates/v3/core/scheduler/booking/service-appointments-prior-appt2-b-optional
git commit -m "test(scheduler-parity): old-side prior-assignment appt#1/appt#2 booking collections"
```

---

### Task 3: WFS-side fixture — Unified prior-assignment graph

**Files:**
- Create: `src/integrationTest/resources/pm-templates/v3/core/wfs/fixtures/prior-assignment/create-prior-assignment-graph.request.yaml`
- Create: `.../wfs/fixtures/prior-assignment/.resources/definition.yaml` (mirror the WFS double-book marker)

**Interfaces:**
- Produces (env vars): `priorTerritoryId`, `priorWorkTypeId`, `priorAccountId`, `priorAccountId2`, `priorResourceAId`, `priorResourceBId`, `priorResourceCId` (WFS-side names, distinct from old-side `sched*`).

**Clone source:** `.../wfs/fixtures/double-book-non-required/create-double-book-non-required-graph.request.yaml`. Apply the SAME structural changes as Task 1 (third resource C, B available 10:00–14:00, second account), but keep the WFS env-var naming and `{{adminToken}}` auth the double-book WFS graph already uses.

- [ ] **Step 1: Read the WFS double-book graph in full** so the clone matches its exact variable names and auth:

Run: `sed -n '1,60p' src/integrationTest/resources/pm-templates/v3/core/wfs/fixtures/double-book-non-required/create-double-book-non-required-graph.request.yaml`
Expected: shows the graph's `description`, its env-var names (e.g. `doubleBookResourceAId`, `doubleBookAccountId`, `doubleBookTerritoryId`, `doubleBookWorkTypeId`) and the beforeRequest/afterResponse scripts.

- [ ] **Step 2: Copy the WFS graph and its marker**

```bash
cd /home/sfwork/code-clones/work/revoman-root
mkdir -p src/integrationTest/resources/pm-templates/v3/core/wfs/fixtures/prior-assignment/.resources
cp src/integrationTest/resources/pm-templates/v3/core/wfs/fixtures/double-book-non-required/create-double-book-non-required-graph.request.yaml \
   src/integrationTest/resources/pm-templates/v3/core/wfs/fixtures/prior-assignment/create-prior-assignment-graph.request.yaml
cp src/integrationTest/resources/pm-templates/v3/core/wfs/fixtures/double-book-non-required/.resources/definition.yaml \
   src/integrationTest/resources/pm-templates/v3/core/wfs/fixtures/prior-assignment/.resources/definition.yaml 2>/dev/null || true
```

- [ ] **Step 3: Apply the Task-1 structural changes using the WFS graph's own variable names.** Mirror Task 1 Steps 3–9 exactly, but substitute the double-book WFS graph's variable names (whatever Step 1 revealed — expected `doubleBook*`). Concretely: add `refUserC` + `refResourceC`; add member OH C + 7 TimeSlots 10:00–14:00; **change resourceB's member-OH TimeSlots and Shift to 10:00–14:00**; add STM C + Shift C; add `refAccount2`; mint a third user in beforeRequest with all shifts 10:00–14:00; capture the new ids in afterResponse under the WFS-side names `priorResourceCId` and `priorAccountId2`. Rename the emitted ids to the WFS `prior*` interface names above (either re-alias in afterResponse or keep the graph's own names and reference those from the collections in Task 4 — pick one and be consistent; the interface list above is the contract Task 4 relies on).

> Decision to lock here: **emit the `prior*`-prefixed names** (`priorTerritoryId`, `priorResourceAId`, `priorResourceBId`, `priorResourceCId`, `priorWorkTypeId`, `priorAccountId`, `priorAccountId2`) from this fixture's afterResponse, so Task 4's collections reference a clean, purpose-named set rather than the inherited `doubleBook*` names.

- [ ] **Step 4: Verify compile clean**

Run: `gradle compileIntegrationTestJava`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add src/integrationTest/resources/pm-templates/v3/core/wfs/fixtures/prior-assignment
git commit -m "test(scheduler-parity): WFS-side prior-assignment fixture (3 resources, B available, 2 accounts)"
```

---

### Task 4: WFS-side booking collections — Unified appt #1 and appt #2

**Files:**
- Create: `.../wfs/booking/schedule-prior-appt1-b-required/10-schedule.request.yaml`
- Create: `.../wfs/booking/schedule-prior-appt1-b-optional/10-schedule.request.yaml`
- Create: `.../wfs/booking/schedule-prior-appt2-b-required/10-schedule.request.yaml`
- Create: `.../wfs/booking/schedule-prior-appt2-b-optional/10-schedule.request.yaml`

**Interfaces:**
- appt1 collections consume `availabilityOpHoursPolicyId`, `priorTerritoryId`, `priorAccountId`, `priorWorkTypeId`, `priorResourceAId`, `priorResourceBId`; produce `priorAppt1SchedulingStatus`.
- appt2 collections consume `availabilityOpHoursPolicyId`, `priorTerritoryId`, `priorAccountId2`, `priorWorkTypeId`, `priorResourceCId`, `priorResourceBId`; produce `priorAppt2SchedulingStatus`, `priorAppt2ErrorCode`, `priorAppt2Http`.

**Clone source:** `.../wfs/booking/schedule-double-book-required-conflict/10-schedule-double-book-required-conflict.request.yaml` (the Unified `actions/schedule` shape).

- [ ] **Step 1: Create appt #1, B-required.** Write `schedule-prior-appt1-b-required/10-schedule.request.yaml`:

```yaml
$kind: http-request
description: >-
  Unified PRIOR-ASSIGNMENT appt #1 (B REQUIRED): POST /connect/unified-scheduling/actions/schedule on
  account #1, tomorrow 11:00-11:30 UTC, assignedResources = resourceA (primary+required) and resourceB
  (required, non-primary). All available → schedulingStatus Success. Seeds a REQUIRED prior assignment for
  B. Captures priorAppt1SchedulingStatus.
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
            "startTime": "{{priorApptStart}}",
            "endTime": "{{priorApptEnd}}",
            "status": "Scheduled",
            "subject": "Prior-assignment appt #1 (B required)",
            "workConfiguration": { "workTypeId": "{{priorWorkTypeId}}" },
            "locationConstraints": { "id": "{{priorTerritoryId}}" }
          },
          "serviceAppointmentParent": { "id": "{{priorAccountId}}" },
          "assignedResources": [
            { "isPrimaryResource": true,  "isRequiredResource": true, "serviceResourceId": "{{priorResourceAId}}" },
            { "isPrimaryResource": false, "isRequiredResource": true, "serviceResourceId": "{{priorResourceBId}}" }
          ]
        }
      ]
    }
scripts:
  - type: beforeRequest
    code: |-
      const start = new Date(); start.setUTCDate(start.getUTCDate() + 1); start.setUTCHours(11, 0, 0, 0);
      const end = new Date(start.getTime() + 30 * 60 * 1000);
      pm.environment.set("priorApptStart", start.toISOString());
      pm.environment.set("priorApptEnd", end.toISOString());
    language: text/javascript
  - type: afterResponse
    code: |-
      const appointments = (pm.response.json() || {}).appointments || [];
      pm.environment.set("priorAppt1SchedulingStatus", appointments.length ? appointments[0].schedulingStatus : null);
      console.log("WFS prior appt1 (B req) status=" + (appointments.length ? appointments[0].schedulingStatus : "none"));
    language: text/javascript
order: 1000
```

- [ ] **Step 2: Create appt #1, B-optional.** Same as Step 1 with resourceB `"isRequiredResource": false`, subject "(B optional)", console.log "(B opt)". Full body:

```yaml
$kind: http-request
description: >-
  Unified PRIOR-ASSIGNMENT appt #1 (B OPTIONAL): POST /connect/unified-scheduling/actions/schedule on
  account #1, tomorrow 11:00-11:30 UTC, assignedResources = resourceA (primary+required) and resourceB
  (non-required, non-primary). All available → schedulingStatus Success. Seeds an OPTIONAL prior
  assignment for B. Captures priorAppt1SchedulingStatus.
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
            "startTime": "{{priorApptStart}}",
            "endTime": "{{priorApptEnd}}",
            "status": "Scheduled",
            "subject": "Prior-assignment appt #1 (B optional)",
            "workConfiguration": { "workTypeId": "{{priorWorkTypeId}}" },
            "locationConstraints": { "id": "{{priorTerritoryId}}" }
          },
          "serviceAppointmentParent": { "id": "{{priorAccountId}}" },
          "assignedResources": [
            { "isPrimaryResource": true,  "isRequiredResource": true,  "serviceResourceId": "{{priorResourceAId}}" },
            { "isPrimaryResource": false, "isRequiredResource": false, "serviceResourceId": "{{priorResourceBId}}" }
          ]
        }
      ]
    }
scripts:
  - type: beforeRequest
    code: |-
      const start = new Date(); start.setUTCDate(start.getUTCDate() + 1); start.setUTCHours(11, 0, 0, 0);
      const end = new Date(start.getTime() + 30 * 60 * 1000);
      pm.environment.set("priorApptStart", start.toISOString());
      pm.environment.set("priorApptEnd", end.toISOString());
    language: text/javascript
  - type: afterResponse
    code: |-
      const appointments = (pm.response.json() || {}).appointments || [];
      pm.environment.set("priorAppt1SchedulingStatus", appointments.length ? appointments[0].schedulingStatus : null);
      console.log("WFS prior appt1 (B opt) status=" + (appointments.length ? appointments[0].schedulingStatus : "none"));
    language: text/javascript
order: 1000
```

- [ ] **Step 3: Create appt #2, B-required.** Primary resourceC, parent `priorAccountId2`, B required. Capture status + errorCode + http (appt #2 is the cell under assertion; may crash 500 on Unified like 1.4/3, so capture the error envelope):

```yaml
$kind: http-request
description: >-
  Unified PRIOR-ASSIGNMENT appt #2 (B REQUIRED): POST /connect/unified-scheduling/actions/schedule on
  account #2, SAME tomorrow 11:00-11:30 UTC window, assignedResources = resourceC (primary+required,
  dedicated free primary) and resourceB (required, non-primary). B is occupancy-checked here; if its appt
  #1 assignment holds the window this is refused (schedulingStatus != Success). Captures
  priorAppt2SchedulingStatus + priorAppt2ErrorCode + priorAppt2Http.
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
            "startTime": "{{priorApptStart}}",
            "endTime": "{{priorApptEnd}}",
            "status": "Scheduled",
            "subject": "Prior-assignment appt #2 (B required)",
            "workConfiguration": { "workTypeId": "{{priorWorkTypeId}}" },
            "locationConstraints": { "id": "{{priorTerritoryId}}" }
          },
          "serviceAppointmentParent": { "id": "{{priorAccountId2}}" },
          "assignedResources": [
            { "isPrimaryResource": true,  "isRequiredResource": true, "serviceResourceId": "{{priorResourceCId}}" },
            { "isPrimaryResource": false, "isRequiredResource": true, "serviceResourceId": "{{priorResourceBId}}" }
          ]
        }
      ]
    }
scripts:
  - type: beforeRequest
    code: |-
      const start = new Date(); start.setUTCDate(start.getUTCDate() + 1); start.setUTCHours(11, 0, 0, 0);
      const end = new Date(start.getTime() + 30 * 60 * 1000);
      pm.environment.set("priorApptStart", start.toISOString());
      pm.environment.set("priorApptEnd", end.toISOString());
    language: text/javascript
  - type: afterResponse
    code: |-
      const data = pm.response.json() || {};
      const appointments = data.appointments || [];
      pm.environment.set("priorAppt2SchedulingStatus", appointments.length ? appointments[0].schedulingStatus : null);
      // Error envelope (in case Unified 500s like scenarios 1.4/3): top-level errorCode if present.
      const err = (data.error && data.error.errorCode) || (Array.isArray(data) && data[0] && data[0].errorCode) || "";
      pm.environment.set("priorAppt2ErrorCode", err);
      pm.environment.set("priorAppt2Http", String(pm.response.code));
      console.log("WFS prior appt2 (B req) http=" + pm.response.code + " status=" + (appointments.length ? appointments[0].schedulingStatus : "none") + " body=" + JSON.stringify(data).slice(0,300));
    language: text/javascript
order: 1000
```

- [ ] **Step 4: Create appt #2, B-optional.** Same as Step 3 with resourceB `"isRequiredResource": false`, subject "(B optional)". Full body:

```yaml
$kind: http-request
description: >-
  Unified PRIOR-ASSIGNMENT appt #2 (B OPTIONAL): POST /connect/unified-scheduling/actions/schedule on
  account #2, SAME tomorrow 11:00-11:30 UTC window, assignedResources = resourceC (primary+required) and
  resourceB (non-required, non-primary). B is NOT occupancy-checked here → Success regardless of appt #1.
  Captures priorAppt2SchedulingStatus + priorAppt2ErrorCode + priorAppt2Http.
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
            "startTime": "{{priorApptStart}}",
            "endTime": "{{priorApptEnd}}",
            "status": "Scheduled",
            "subject": "Prior-assignment appt #2 (B optional)",
            "workConfiguration": { "workTypeId": "{{priorWorkTypeId}}" },
            "locationConstraints": { "id": "{{priorTerritoryId}}" }
          },
          "serviceAppointmentParent": { "id": "{{priorAccountId2}}" },
          "assignedResources": [
            { "isPrimaryResource": true,  "isRequiredResource": true,  "serviceResourceId": "{{priorResourceCId}}" },
            { "isPrimaryResource": false, "isRequiredResource": false, "serviceResourceId": "{{priorResourceBId}}" }
          ]
        }
      ]
    }
scripts:
  - type: beforeRequest
    code: |-
      const start = new Date(); start.setUTCDate(start.getUTCDate() + 1); start.setUTCHours(11, 0, 0, 0);
      const end = new Date(start.getTime() + 30 * 60 * 1000);
      pm.environment.set("priorApptStart", start.toISOString());
      pm.environment.set("priorApptEnd", end.toISOString());
    language: text/javascript
  - type: afterResponse
    code: |-
      const data = pm.response.json() || {};
      const appointments = data.appointments || [];
      pm.environment.set("priorAppt2SchedulingStatus", appointments.length ? appointments[0].schedulingStatus : null);
      const err = (data.error && data.error.errorCode) || (Array.isArray(data) && data[0] && data[0].errorCode) || "";
      pm.environment.set("priorAppt2ErrorCode", err);
      pm.environment.set("priorAppt2Http", String(pm.response.code));
      console.log("WFS prior appt2 (B opt) http=" + pm.response.code + " status=" + (appointments.length ? appointments[0].schedulingStatus : "none") + " body=" + JSON.stringify(data).slice(0,300));
    language: text/javascript
order: 1000
```

- [ ] **Step 5: Verify compile clean**

Run: `gradle compileIntegrationTestJava`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add src/integrationTest/resources/pm-templates/v3/core/wfs/booking/schedule-prior-appt1-b-required \
        src/integrationTest/resources/pm-templates/v3/core/wfs/booking/schedule-prior-appt1-b-optional \
        src/integrationTest/resources/pm-templates/v3/core/wfs/booking/schedule-prior-appt2-b-required \
        src/integrationTest/resources/pm-templates/v3/core/wfs/booking/schedule-prior-appt2-b-optional
git commit -m "test(scheduler-parity): WFS-side prior-assignment appt#1/appt#2 schedule collections"
```

---

### Task 5: Config constants — old-side and WFS Kicks

**Files:**
- Modify: `src/integrationTest/java/com/salesforce/revoman/integration/core/scheduler/SchedulerParityConfig.java`
- Modify: `src/integrationTest/java/com/salesforce/revoman/integration/core/wfs/ReVomanConfigForWfs.java`

**Interfaces:**
- Produces (Java constants consumed by Task 7):
  - `SchedulerParityConfig.OLD_PRIOR_ASSIGNMENT_FIXTURE_CONFIG`
  - `SchedulerParityConfig.OLD_PRIOR_APPT1_B_REQUIRED_CONFIG`
  - `SchedulerParityConfig.OLD_PRIOR_APPT1_B_OPTIONAL_CONFIG`
  - `SchedulerParityConfig.OLD_PRIOR_APPT2_B_REQUIRED_CONFIG`
  - `SchedulerParityConfig.OLD_PRIOR_APPT2_B_OPTIONAL_CONFIG`
  - `ReVomanConfigForWfs.PRIOR_ASSIGNMENT_FIXTURE_CONFIG`
  - `ReVomanConfigForWfs.PRIOR_APPT1_B_REQUIRED_CONFIG`
  - `ReVomanConfigForWfs.PRIOR_APPT1_B_OPTIONAL_CONFIG`
  - `ReVomanConfigForWfs.PRIOR_APPT2_B_REQUIRED_CONFIG`
  - `ReVomanConfigForWfs.PRIOR_APPT2_B_OPTIONAL_CONFIG`
- Consumes: `SchedulerParityConfig.oldKickFor(String)`, `SchedulerParityConfig.V3_SCHEDULER_PATH`; `ReVomanConfigForWfs.kickFor(String)`, `ReVomanConfigForWfs.V3_WFS_PATH`.

- [ ] **Step 1: Add old-side constants.** In `SchedulerParityConfig.java`, immediately after the existing `OLD_BOOK_REQUIRED_CONTROL_CONFIG` definition (around line 67), add:

```java
  // ## Prior-assignment occupancy (does an existing assignment block a later required booking, even
  // when B first joined as an OPTIONAL helper?). 3-resource fixture: A/B/C all AVAILABLE at 11:00 (no
  // shift gap — unlike double-book); B is the only shared worker (A on appt #1, C the dedicated free
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
```

> If `V3_SCHEDULER_PATH` is not visible at that scope, confirm its declaration near line 44 (`static final String V3_SCHEDULER_PATH = "pm-templates/v3/core/scheduler/";`) — it is a class field, so it is in scope.

- [ ] **Step 2: Add WFS-side constants.** In `ReVomanConfigForWfs.java`, immediately after the `DOUBLE_BOOK_REQUIRED_CONFLICT_SCHEDULE_CONFIG` definition (around line 237), add:

```java
  // ## Prior-assignment occupancy parity (Unified side). 3-resource graph, B available, 2 accounts;
  // appt #1 seeds B's assignment, appt #2 re-books B on an overlapping window with C as a free primary.
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
```

> Confirm `V3_WFS_PATH` exists near the top of `ReVomanConfigForWfs` (used by the double-book defs at lines 209/235). If the WFS AUTH/policy fixture requires a specific enablement Kick (the double-book path pairs `AUTH_CONFIG` + `AVAILABILITY_OP_HOURS_POLICY_CONFIG` + `DOUBLE_BOOK_FIXTURE_CONFIG`), no new constant is needed — Task 7 reuses `AUTH_CONFIG` and `AVAILABILITY_OP_HOURS_POLICY_CONFIG`.

- [ ] **Step 3: Verify compile**

Run: `gradle compileIntegrationTestJava`
Expected: `BUILD SUCCESSFUL` (constants compile; nothing consumes them yet).

- [ ] **Step 4: Commit**

```bash
git add src/integrationTest/java/com/salesforce/revoman/integration/core/scheduler/SchedulerParityConfig.java \
        src/integrationTest/java/com/salesforce/revoman/integration/core/wfs/ReVomanConfigForWfs.java
git commit -m "test(scheduler-parity): Kick constants for prior-assignment occupancy fixture + collections"
```

---

### Task 6: Verify the occupancy-outcome normalizer covers appt #2

**Files:**
- Modify (only if needed): `src/integrationTest/java/com/salesforce/revoman/integration/core/scheduler/SchedulerParityConfig.java`

**Interfaces:**
- Consumes/reuses: `SchedulerParityConfig.oldWriteOutcome(String saId, String http)` → `WriteOutcome`; `SchedulerParityConfig.unifiedWriteOutcome(String schedulingStatus, String http)` → `WriteOutcome`. Both already exist (lines ~399 and ~407).

The appt-#2 outcome maps cleanly onto the existing normalizers, so **no new method is expected**:
- Old side: `oldWriteOutcome(priorAppt2SaId, priorAppt2Http)` → BOOKED iff an SA id came back; CRASHED on HTTP 500; else REFUSED.
- Unified side: `unifiedWriteOutcome(priorAppt2SchedulingStatus, priorAppt2Http)` → BOOKED iff status "Success"; CRASHED on 500; else REFUSED.

- [ ] **Step 1: Confirm the two normalizers exist with those exact signatures**

Run: `grep -n "static WriteOutcome oldWriteOutcome\|static WriteOutcome unifiedWriteOutcome" src/integrationTest/java/com/salesforce/revoman/integration/core/scheduler/SchedulerParityConfig.java`
Expected: two matches — `oldWriteOutcome(final String saId, final String http)` and `unifiedWriteOutcome(final String schedulingStatus, final String http)`.

- [ ] **Step 2: No code change.** If both exist (they do per the current file), this task is a no-op confirmation — do NOT add a duplicate normalizer. If, and only if, either is missing, add it verbatim:

```java
  /** Old side: BOOKED iff an SA id came back; CRASHED on HTTP 500; else REFUSED. */
  static WriteOutcome oldWriteOutcome(final String saId, final String http) {
    if (saId != null && saId.length() == 18 && saId.startsWith("08p")) {
      return WriteOutcome.BOOKED;
    }
    return "500".equals(http) ? WriteOutcome.CRASHED : WriteOutcome.REFUSED;
  }
```

(There is no commit for a no-op; fold any real change into Task 7's commit.)

---

### Task 7: The parity test method

**Files:**
- Modify: `src/integrationTest/java/com/salesforce/revoman/integration/core/scheduler/SchedulerVsUnifiedParityE2ETest.java`

**Interfaces:**
- Consumes: all ten Task-5 constants; `SchedulerParityConfig.assumeBothOrgCreds()`, `SchedulerParityConfig.OLD_AUTH_CONFIG`, `SchedulerParityConfig.OLD_GRANT_LS_ACCESS_CONFIG`, `SchedulerParityConfig.oldWriteOutcome`, `SchedulerParityConfig.WriteOutcome`; `ReVomanConfigForWfs.AUTH_CONFIG`, `ReVomanConfigForWfs.AVAILABILITY_OP_HOURS_POLICY_CONFIG`, `SchedulerParityConfig.unifiedWriteOutcome`; `ReVoman.revUp`, `CollectionsKt.last`, `assertThat`.
- Produces: `void testPriorAssignmentOccupancyParity_E2E()`.

- [ ] **Step 1: Add a private helper that runs one old-side cell.** Near the other private helpers at the bottom of `SchedulerVsUnifiedParityE2ETest` (e.g. after `unifiedNoPrimaryValidationVerdict`), add:

```java
  /**
   * Runs one old-side occupancy cell: AUTH → prior-assignment FIXTURE → GRANT → book appt #1 (seeds B's
   * assignment) → book appt #2 (re-books B on the overlapping window). Returns the appt-#2 write outcome.
   * Asserts non-vacuity: appt #1 itself booked a real SA (18-char 08p id), else appt #2's refusal would be
   * a dead-fixture artifact rather than an occupancy block.
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
```

> `Kick` is already imported in `SchedulerParityConfig`; confirm the test file imports `com.salesforce.revoman.input.config.Kick` (the 4z/5 tests reference configs but pass them positionally, so the import may be absent). If absent, add `import com.salesforce.revoman.input.config.Kick;` to the test file's imports.

- [ ] **Step 2: Add a private helper that runs one WFS-side cell.** After Step 1's helper, add:

```java
  /**
   * Runs one Unified occupancy cell: AUTH → op-hours policy → prior-assignment FIXTURE → book appt #1 →
   * book appt #2. Returns the appt-#2 write outcome. Non-vacuity: appt #1 scheduled Success.
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
```

- [ ] **Step 3: Add the test method with the KDoc and all four cells.** Add before the closing brace of the class (after the last `@Test`), with this full KDoc + body:

```java
  /**
   * Prior-assignment occupancy parity — does an EXISTING appointment assignment on worker B occupy B's
   * time and block a later REQUIRED booking on an overlapping appointment, even when B first joined as an
   * OPTIONAL (non-required) helper? {@code required} vs {@code non-required} decides whether B is CHECKED
   * when joining an appointment — not whether B's existing assignments COUNT against a future check. This
   * test asserts the full 2x2 (appt #1 flavor x appt #2 flavor) on BOTH engines.
   *
   * <p>Fixture isolates the mechanism: A/B/C are all AVAILABLE at 11:00 (member OH + Shift 10:00-14:00, no
   * shift gap — unlike the 1.5 double-book fixture). B is the only shared worker: A is appt #1's primary,
   * C is appt #2's DEDICATED free primary, so a refused appt #2 cannot be blamed on the primary being
   * double-booked. The two appointments use two Accounts on the SAME 11:00-11:30 window.
   *
   * <p>Truth table (both products; each cell a fresh revUp):
   * <ul>
   *   <li>(a) #1 required, #2 required -> REFUSED — the fail-loud guard: proves occupancy is enforced at
   *       all. If this BOOKS, the org allows overbooking and the whole test is meaningless.
   *   <li>(b) #1 OPTIONAL, #2 required -> REFUSED — THE claim: an optional first booking still occupies.
   *   <li>(c) #1 optional, #2 optional -> BOOKED — appt #2 is non-required, so B is not occupancy-checked.
   *   <li>(d) #1 required, #2 optional -> BOOKED — symmetry; appt #2 non-required is not checked.
   * </ul>
   *
   * <p>Parity: each cell asserts old == unified. A divergence (e.g. a Unified HTTP 500 crash like 1.4/3)
   * is asserted verbatim via the normalized {@link SchedulerParityConfig.WriteOutcome}, not forced green.
   *
   * <p>Non-vacuity: every cell asserts appt #1 itself booked (old: 18-char 08p SA id; unified: Success),
   * else appt #2's refusal would be a dead-fixture artifact rather than an occupancy block. The (a) guard
   * additionally detects an overbooking-permissive org: if (a) does not refuse on BOTH engines the test
   * fails, so nothing passes vacuously.
   *
   * <p>Old-side revUps mint 3 fresh {@code prior-res-*@revoman.org} users per run and never clean up; four
   * cells x one old revUp each -> ~12 fresh users/run. The leading success guard
   * ({@code firstUnIgnoredUnsuccessfulStepReport() == null}) surfaces a rolled-back fixture/grant loudly;
   * the book acts carry {@code ignoreHTTPStatusUnsuccessful} so a legit refusal is read from the captured
   * status, not counted as a step failure.
   */
  @Test
  void testPriorAssignmentOccupancyParity_E2E() {
    SchedulerParityConfig.assumeBothOrgCreds();

    // (a) #1 required -> #2 required: the fail-loud occupancy guard. Both must REFUSE.
    final var oldReqReq =
        oldOccupancyCell(
            SchedulerParityConfig.OLD_PRIOR_APPT1_B_REQUIRED_CONFIG,
            SchedulerParityConfig.OLD_PRIOR_APPT2_B_REQUIRED_CONFIG);
    final var unifiedReqReq =
        unifiedOccupancyCell(
            ReVomanConfigForWfs.PRIOR_APPT1_B_REQUIRED_CONFIG,
            ReVomanConfigForWfs.PRIOR_APPT2_B_REQUIRED_CONFIG);
    // If this books, the org allows overbooking and the whole test is meaningless — fail loud here.
    assertThat(oldReqReq)
        .as("(a) required->required must REFUSE on OLD; if BOOKED the org allows overbooking")
        .isEqualTo(SchedulerParityConfig.WriteOutcome.REFUSED);
    assertThat(unifiedReqReq)
        .as("(a) required->required must REFUSE on Unified; if BOOKED the org allows overbooking")
        .isEqualTo(SchedulerParityConfig.WriteOutcome.REFUSED);
    assertThat(oldReqReq).isEqualTo(unifiedReqReq);

    // (b) #1 OPTIONAL -> #2 required: THE claim — an optional first booking still occupies B.
    final var oldOptReq =
        oldOccupancyCell(
            SchedulerParityConfig.OLD_PRIOR_APPT1_B_OPTIONAL_CONFIG,
            SchedulerParityConfig.OLD_PRIOR_APPT2_B_REQUIRED_CONFIG);
    final var unifiedOptReq =
        unifiedOccupancyCell(
            ReVomanConfigForWfs.PRIOR_APPT1_B_OPTIONAL_CONFIG,
            ReVomanConfigForWfs.PRIOR_APPT2_B_REQUIRED_CONFIG);
    assertThat(oldOptReq)
        .as("(b) optional->required: optional prior assignment still occupies B -> REFUSED on OLD")
        .isEqualTo(SchedulerParityConfig.WriteOutcome.REFUSED);
    assertThat(unifiedOptReq)
        .as("(b) optional->required: optional prior assignment still occupies B -> REFUSED on Unified")
        .isEqualTo(SchedulerParityConfig.WriteOutcome.REFUSED);
    assertThat(oldOptReq).isEqualTo(unifiedOptReq);

    // (c) #1 optional -> #2 optional: appt #2 non-required, B not occupancy-checked -> BOOKED.
    final var oldOptOpt =
        oldOccupancyCell(
            SchedulerParityConfig.OLD_PRIOR_APPT1_B_OPTIONAL_CONFIG,
            SchedulerParityConfig.OLD_PRIOR_APPT2_B_OPTIONAL_CONFIG);
    final var unifiedOptOpt =
        unifiedOccupancyCell(
            ReVomanConfigForWfs.PRIOR_APPT1_B_OPTIONAL_CONFIG,
            ReVomanConfigForWfs.PRIOR_APPT2_B_OPTIONAL_CONFIG);
    assertThat(oldOptOpt)
        .as("(c) optional->optional: appt #2 non-required is not occupancy-checked -> BOOKED on OLD")
        .isEqualTo(SchedulerParityConfig.WriteOutcome.BOOKED);
    assertThat(unifiedOptOpt)
        .as("(c) optional->optional: appt #2 non-required is not occupancy-checked -> BOOKED on Unified")
        .isEqualTo(SchedulerParityConfig.WriteOutcome.BOOKED);
    assertThat(oldOptOpt).isEqualTo(unifiedOptOpt);

    // (d) #1 required -> #2 optional: symmetry; appt #2 non-required -> BOOKED.
    final var oldReqOpt =
        oldOccupancyCell(
            SchedulerParityConfig.OLD_PRIOR_APPT1_B_REQUIRED_CONFIG,
            SchedulerParityConfig.OLD_PRIOR_APPT2_B_OPTIONAL_CONFIG);
    final var unifiedReqOpt =
        unifiedOccupancyCell(
            ReVomanConfigForWfs.PRIOR_APPT1_B_REQUIRED_CONFIG,
            ReVomanConfigForWfs.PRIOR_APPT2_B_OPTIONAL_CONFIG);
    assertThat(oldReqOpt)
        .as("(d) required->optional: appt #2 non-required is not occupancy-checked -> BOOKED on OLD")
        .isEqualTo(SchedulerParityConfig.WriteOutcome.BOOKED);
    assertThat(unifiedReqOpt)
        .as("(d) required->optional: appt #2 non-required is not occupancy-checked -> BOOKED on Unified")
        .isEqualTo(SchedulerParityConfig.WriteOutcome.BOOKED);
    assertThat(oldReqOpt).isEqualTo(unifiedReqOpt);
  }
```

- [ ] **Step 4: Compile the test**

Run: `gradle compileIntegrationTestJava`
Expected: `BUILD SUCCESSFUL`. If it fails on a missing `Kick` import, add `import com.salesforce.revoman.input.config.Kick;` to the test file and recompile.

- [ ] **Step 5: Apply formatting**

Run: `gradle spotlessApply`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add src/integrationTest/java/com/salesforce/revoman/integration/core/scheduler/SchedulerVsUnifiedParityE2ETest.java \
        src/integrationTest/java/com/salesforce/revoman/integration/core/scheduler/SchedulerParityConfig.java
git commit -m "test(scheduler-parity): prior-assignment occupancy parity test (optional booking occupies?)"
```

---

### Task 8: Run live, interpret, and record the finding

**Files:** none (execution + spec/sheet follow-up).

- [ ] **Step 1: Confirm both org cred files exist** (else the test JUnit-skips silently):

Run: `ls -la ~/.revoman/scheduler-config.yaml ~/.revoman/config.yaml`
Expected: both files present. If either is missing, the test will skip — provision creds per the `wfs-headless-testing` / external-org config before proceeding.

- [ ] **Step 2: Run the test live**

Run: `gradle integrationTest --tests "com.salesforce.revoman.integration.core.scheduler.SchedulerVsUnifiedParityE2ETest.testPriorAssignmentOccupancyParity_E2E"`
Expected (hypothesis holds): `BUILD SUCCESSFUL`, 1 test passed. This means all four cells matched the truth table on both engines — an optional prior assignment occupies B and blocks a later required booking, identically on old and new.

- [ ] **Step 3: If cell (a) failed (booked instead of refused)** — the org allows overbooking; the whole premise can't be tested on this org. Do NOT weaken the assertion. Record it: the org needs a "prevent overlapping appointments" / conflict rule. Capture the failure output and stop; this is a provisioning blocker, not a code bug. Note in the spec's Risks section that risk #1 materialized.

- [ ] **Step 4: If cell (b) diverged (old REFUSED, unified BOOKED, or a 500 crash)** — this is a genuine finding, not a test bug. The `assertThat(oldOptReq).isEqualTo(unifiedOptReq)` line will have failed. Read the captured `priorAppt2SchedulingStatus` / `priorAppt2ErrorCode` / `priorAppt2Http` from the run log. If Unified returned HTTP 500, the outcome normalizes to CRASHED — mirror the 1.4/3 handling: change cell (b)'s parity assertion to pin the divergence verbatim (assert old == REFUSED and unified == CRASHED) with a comment citing the observed error, rather than forcing equality. Re-run to confirm the pinned assertion is green.

- [ ] **Step 5: On green, update the parity Sheet** (`147N4ZEteXjxgKx34f9ZWoPJ5T_42l2li83x9Ejq03FQ`) — only now, reflecting verified behavior:
  - Add a row to the **multi-resource** tab: Scenario "Prior booking blocks a later required one", What we asked / Old / New / Agree / Proven-by-test = `testPriorAssignmentOccupancyParity_E2E`.
  - Add a **How each test works** row (Setup / Fire / Check bullets) with the GitHub hyperlink to the new method.

- [ ] **Step 6: Final full-suite guard** (make sure the new fixture/collections didn't disturb neighbors):

Run: `gradle integrationTest --tests "com.salesforce.revoman.integration.core.scheduler.SchedulerVsUnifiedParityE2ETest"`
Expected: all methods pass (allow for the documented restful-api.dev / fixture-flake reruns noted in project memory; re-run once on a flake).

- [ ] **Step 7: Commit any Task-8 assertion adjustments**

```bash
git add src/integrationTest/java/com/salesforce/revoman/integration/core/scheduler/SchedulerVsUnifiedParityE2ETest.java
git commit -m "test(scheduler-parity): pin prior-assignment occupancy live-observed outcomes"
```

---

## Self-Review

**Spec coverage:**
- Behavior model / 3-resource isolation → Task 1 (fixture), Task 7 KDoc. ✓
- 2×2 truth table → Task 7 cells (a)–(d). ✓
- Parity (old == unified per cell) → Task 7 `isEqualTo` lines. ✓
- Fail-loud (a) guard → Task 7 cell (a) with `.as(...)` overbooking note; Task 8 Step 3. ✓
- Fixture (B available, 3 resources, 2 accounts) → Task 1 + Task 3. ✓
- Booking collections (appt #1 ×2, appt #2 ×2, both sides) → Task 2 + Task 4. ✓
- Reuse WriteOutcome / normalizers → Task 6 (confirm, no dup). ✓
- Non-vacuity (appt #1 booked) → Task 7 helpers. ✓
- Divergence asserted verbatim / crash handling → Task 8 Step 4. ✓
- Sheet update after green → Task 8 Step 5. ✓
- Risks (overbooking, no-AR-on-optional, exact overlap, Unified crash) → Task 8 Steps 3–4; overlap fixed by shared 11:00–11:30 window in all collections. ✓
- Out-of-scope items → not built (no tasks); correct. ✓

**Placeholder scan:** No "TBD"/"implement later". Each collection body is given in full (not "similar to"). The one judgment call — WFS graph variable renaming (Task 3 Step 3) — is resolved by an explicit "lock here: emit `prior*` names" decision. ✓

**Type consistency:** `WriteOutcome` (BOOKED/REFUSED/CRASHED), `oldWriteOutcome(saId, http)`, `unifiedWriteOutcome(schedulingStatus, http)`, `oldKickFor`, `kickFor`, env-var names (`priorAppt1SaId`, `priorAppt2Http`, `priorAppt2SchedulingStatus`, `schedResourceCId`, `schedAccountId2`, WFS `prior*`) are used identically across Tasks 2/4/5/7. Old-side collections emit `sched*`/`priorAppt*`; WFS collections emit `prior*`/`priorAppt*SchedulingStatus`. ✓

**Note on non-standard TDD:** these are live-org integration tests; there is no isolated unit red/green. Verification per infra task is compile/parse; the behavioral red→green is Task 8. This is called out in the File Map note and is the established pattern for this suite.
