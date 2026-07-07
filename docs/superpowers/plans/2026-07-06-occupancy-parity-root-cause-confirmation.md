# Occupancy Parity Root-Cause Confirmation — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the dead "add CalendarEvents rule" occupancy confirmation with two ReVoman characterization tests that pin the verified root cause — Unified's write path trusts caller-supplied resources (proven by its read path excluding the busy worker), and OLD's refusal is gated by `OrgPreferences.Overbooking` (proven by flipping it ON to make OLD book).

**Architecture:** Extend `SchedulerVsUnifiedParityE2ETest` with two new `@Test` methods driven by `ReVoman.revUp(...)`. Reuse the existing prior-assignment fixtures and the existing occupancy-cell helpers. Add a Unified get-candidates read-probe fixture and OLD Overbooking flip/revert steps. No product code changes; no Core FTests.

**Tech Stack:** ReVoman (Kotlin lib) driving Postman V3 collections over HTTPS against two live orgs; JUnit5 + Truth assertions (Java integration test); `gradle integrationTest`.

## Global Constraints

- **ReVomanTests, NOT Core FTests.** All new tests live in `src/integrationTest/java/com/salesforce/revoman/integration/core/scheduler/SchedulerVsUnifiedParityE2ETest.java` and use `ReVoman.revUp(...)`. No `ftest-inventory.xml`, no Core test harness.
- **Build/test command:** `gradle` — NEVER `./gradlew`. JDK 21+.
- **Live-org gated:** every new test starts with `SchedulerParityConfig.assumeBothOrgCreds();` (or the single-org assume for Unified-only / OLD-only). Needs `~/.revoman/config.yaml` (Unified/WFS org `00Dxx00iDhF3Q8O`) and `~/.revoman/scheduler-config.yaml` (OLD scheduler org `00Dxx00iDhF3QtA`).
- **262 release contract:** `SchedulingMethod="OnSite"`; WorkType has no SchedulingMethod; schedule body has no `referenceId`; composite node URLs RELATIVE; `versionPath` (v67.0) from env. (Reuse existing fixtures — they already comply.)
- **Every new fixture folder needs a `.resources/definition.yaml`** `$kind: collection` marker (bearer auth block) or the run throws FileNotFoundException. Clone from a sibling fixture's `.resources/definition.yaml`.
- **`ignoreHTTPStatusUnsuccessful: "true"`** on every book/read request whose non-2xx is a legitimate outcome to capture (refusal/empty read), so it isn't counted as a step failure.
- **Java style:** `/my-java-coding-style` — `final var` locals, functional style, Vavr/Optional over null, newest Java the module allows. Match the surrounding test file's existing idiom.
- **Terminology:** prose uses "primary resource" / "non-required resource" (not lead/helper); identifiers/env-keys/fixture-paths unchanged.
- **Google Sheet:** `147N4ZEteXjxgKx34f9ZWoPJ5T_42l2li83x9Ejq03FQ`, rows `multi-resource!12` and `[WIP] How each test works!C20`. Write ONE cell at a time (MCP times out on multi-cell writes); read back after a timeout.

---

## File Structure

- **Modify** `src/integrationTest/java/.../scheduler/SchedulerVsUnifiedParityE2ETest.java` — add 2 test methods + 1 read-probe helper; correct the existing occupancy method's javadoc.
- **Modify** `src/integrationTest/java/.../scheduler/SchedulerParityConfig.java` — add OLD-side Kick constants (overbooking flip/revert) + WriteOutcome helper reuse.
- **Modify** `src/integrationTest/java/.../core/wfs/ReVomanConfigForWfs.java` — add Unified get-candidates read-probe Kick constant.
- **Create** `src/integrationTest/resources/pm-templates/v3/core/wfs/booking/get-candidates-prior-appt2-b-required/` — Unified read-probe fixture (+ `.resources/`).
- **Create** `src/integrationTest/resources/pm-templates/v3/core/scheduler/booking/enable-overbooking/` — OLD Metadata-API flip step (+ `.resources/`).
- **Create** `src/integrationTest/resources/pm-templates/v3/core/scheduler/booking/disable-overbooking/` — OLD revert step (+ `.resources/`).

---

## Task 1: SPIKE — verify the OLD Overbooking flip mechanism live

**Rationale:** The spec's primary mechanism (Metadata API `updateMetadata(IndustriesSettings)`) and fallback (PLSQL) both need live confirmation before writing the fixture. This spike is throwaway curl/psql — no committed code — that decides which mechanism the fixture uses. It also verifies the `AppointmentBooking` org-perm prerequisite (`orgHasOverbooking` is an AND).

**Files:** none committed (spike). Record findings in `~/work/impl-decisions/2026-07-06-occupancy-parity-premise-break-reframe.md`.

- [ ] **Step 1: Mint an OLD-org session token via SOAP login**

Read creds from `~/.revoman/scheduler-config.yaml` (`baseUrl`, `username`, `password`). SOAP-login to `${baseUrl}/services/Soap/u/64.0` (envelope shape in `pm-templates/v3/core/scheduler/auth/login-as-sysadmin.request.yaml`), extract `<sessionId>`.

- [ ] **Step 2: Discover the org's API version**

`GET ${baseUrl}/services/data/` → take the highest `version` (e.g. `64.0`). Metadata endpoint is `${baseUrl}/services/Soap/m/${version}`.

- [ ] **Step 3: Attempt the Metadata API flip**

POST to `${baseUrl}/services/Soap/m/${version}` with `SOAPAction: ''` and body:
```xml
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/"
    xmlns:met="http://soap.sforce.com/2006/04/metadata">
  <soapenv:Header><met:SessionHeader><met:sessionId>SESSION_ID</met:sessionId></met:SessionHeader></soapenv:Header>
  <soapenv:Body>
    <met:updateMetadata>
      <met:metadata xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:type="met:IndustriesSettings">
        <met:fullName>IndustriesSettings</met:fullName>
        <met:enableOverbookingOrgPref>true</met:enableOverbookingOrgPref>
      </met:metadata>
    </met:updateMetadata>
  </soapenv:Body>
</soapenv:Envelope>
```
Expected: SOAP body with `<result><success>true</success>`. If the type/field name is wrong, first call `describeMetadata` or `readMetadata` for `IndustriesSettings` to confirm the exact field, and check `metadata-gen-ai-impl/java/resources/MetadataTypesContext/IndustriesSettings.json` in the Core checkout.

- [ ] **Step 4: Verify the flip took effect end-to-end**

Re-run the OLD prior-appt occupancy scenario by hand (or just trust Task 4's assertion). Minimum: confirm `updateMetadata` returned success. If it returned an error about the `AppointmentBooking` perm or the pref being unknown, record it.

- [ ] **Step 5: If Metadata API fails — try the PLSQL fallback**

`PGPASSWORD=sdb /opt/workspace/sdb/sdbbuild/current262/bin/psql -h 127.0.0.1 -p 5436 -U saydb -d sdb262`. Inspect `core.organization.preferences*` for org `00Dxx00iDhF3QtA`; determine the column+bit for `Overbooking` (index 427) by reading how `system/organization/setting/OrgPreference.java` maps index→column/bit. Flip it, then check whether the running server sees it (may need a server restart via a sub-agent: core `app-server.sh stop && start`). Record whether cache made a restart necessary.

- [ ] **Step 6: Record the DECISION**

Append to the decision-log file: which mechanism works (Metadata API vs PLSQL+restart), the exact working request/SQL, and whether the `AppointmentBooking` perm was already on. This determines Task 3's fixture content. If NEITHER works over the wire, write an out-of-scope blocker handoff (see "Fallff" note at plan end) and mark the OLD-side test `@Disabled` with a reason, proceeding with the Unified test only.

---

## Task 2: Unified read-probe fixture — get-candidates for appt #2 excludes busy B

**Files:**
- Create: `src/integrationTest/resources/pm-templates/v3/core/wfs/booking/get-candidates-prior-appt2-b-required/10-get-candidates.request.yaml`
- Create: `src/integrationTest/resources/pm-templates/v3/core/wfs/booking/get-candidates-prior-appt2-b-required/.resources/definition.yaml`
- Modify: `src/integrationTest/java/com/salesforce/revoman/integration/core/wfs/ReVomanConfigForWfs.java` (add Kick constant near line 264, after `PRIOR_APPT2_B_OPTIONAL_CONFIG`)

**Interfaces:**
- Consumes: env vars emitted by `PRIOR_ASSIGNMENT_FIXTURE_CONFIG` (`priorAccountId2`, `priorWorkTypeId`, `priorTerritoryId`, `priorResourceBId`, `priorResourceCId`, `availabilityOpHoursPolicyId`) and `priorApptStart`/`priorApptEnd` set by the appt-schedule steps.
- Produces: env var `priorAppt2CandidatesBIncluded` ("1"/"0") and `priorAppt2CandidatesCIncluded` ("1"/"0"); Kick constant `ReVomanConfigForWfs.PRIOR_CANDIDATES_APPT2_CONFIG`.

- [ ] **Step 1: Create the `.resources/definition.yaml` collection marker**

Clone the structure from `pm-templates/v3/core/wfs/booking/get-candidates-skills-violating/.resources/definition.yaml`. Content:
```yaml
$kind: collection
description: |-
  Unified READ-PROBE: get-appointment-candidates for prior-assignment appt #2's window (same
  11:00-11:30 as appt #1) naming resourceB as required. After appt #1 has committed B's overlapping
  ServiceAppointment, this proves Unified's READ path DOES compute occupancy — B is excluded from the
  candidate list while the free primary C is offered. Runs under {{managerToken}}. Contrast with the
  schedule action (write path), which books B anyway.
auth:
  - id: 8b2c3d4e-5f60-4172-9b8b-3c4d5e6f7182
    type: bearer
    name: bearer auth
    credentials:
      token: "{{managerToken}}"
```

- [ ] **Step 2: Create the get-candidates request**

`10-get-candidates.request.yaml` — clone the body/scripts shape from `get-candidates-skills-violating` (which proves the `result[].candidates[]` list key and the "candidates action rejects `isPrimaryResource`" rule). Retarget to the prior-assignment fixture + op-hours policy + appt #2 window (account #2). Name resourceB (required) AND resourceC (required) so the single call shows B excluded / C included:
```yaml
$kind: http-request
description: >-
  Unified READ-PROBE (prior-assignment appt #2 window): get-appointment-candidates on account #2,
  tomorrow 11:00-11:30 UTC (SAME window as appt #1), assignedResources = resourceC (required) and
  resourceB (required). isPrimaryResource is OMITTED (the candidates action rejects it — LIVE-OBSERVED
  input-validation quirk). Run AFTER appt #1 books B, so B holds an overlapping committed
  ServiceAppointment. Expectation: Unified's read path computes occupancy (UnavailabilityService),
  so the candidate list EXCLUDES busy B but INCLUDES free C. Captures per-resource inclusion.
url: "{{baseUrl}}{{versionPath}}/connect/unified-scheduling/actions/get-appointment-candidates"
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
            "appointmentMode": "Regular",
            "workConfiguration": { "workTypeId": "{{priorWorkTypeId}}" },
            "locationConstraints": { "id": "{{priorTerritoryId}}" }
          },
          "serviceAppointmentParent": { "id": "{{priorAccountId2}}" },
          "assignedResources": [
            { "isRequiredResource": true, "serviceResourceId": "{{priorResourceCId}}" },
            { "isRequiredResource": true, "serviceResourceId": "{{priorResourceBId}}" }
          ]
        }
      ]
    }
scripts:
  - type: beforeRequest
    code: |-
      const s = new Date(); s.setUTCDate(s.getUTCDate() + 1); s.setUTCHours(11, 0, 0, 0);
      const e = new Date(s.getTime() + 30 * 60 * 1000);
      pm.environment.set("priorApptStart", s.toISOString());
      pm.environment.set("priorApptEnd", e.toISOString());
    language: text/javascript
  - type: afterResponse
    code: |-
      // candidates action's list key is result[].candidates[] (verified in get-candidates-skills-violating).
      // Each candidate carries the offered serviceResourceId(s). Determine per-resource inclusion.
      const data = pm.response.json() || {};
      const results = data.result || [];
      const ids = new Set();
      results.forEach(r => (r.candidates || []).forEach(c => {
        // a candidate may expose resources under different keys across releases; collect any id-ish field
        const rid = c.serviceResourceId || c.resourceId || (c.serviceResource && c.serviceResource.id);
        if (rid) ids.add(String(rid).substring(0,15));
        (c.assignedResources || c.resources || []).forEach(ar => {
          const arId = ar.serviceResourceId || ar.id; if (arId) ids.add(String(arId).substring(0,15));
        });
      }));
      const b15 = String(pm.environment.get("priorResourceBId")||"").substring(0,15);
      const c15 = String(pm.environment.get("priorResourceCId")||"").substring(0,15);
      pm.environment.set("priorAppt2CandidatesBIncluded", ids.has(b15) ? "1" : "0");
      pm.environment.set("priorAppt2CandidatesCIncluded", ids.has(c15) ? "1" : "0");
      console.log("READPROBE candidates http=" + pm.response.code + " Bincl=" + (ids.has(b15)?1:0) + " Cincl=" + (ids.has(c15)?1:0) + " body=" + JSON.stringify(data).slice(0,400));
    language: text/javascript
order: 1000
```
NOTE: the candidate-response resource-id shape is release-specific. Task 5's live run must confirm the `ids` extraction actually finds C (control). If C is not found, the extraction keys are wrong — dump the body (already logged) and fix the `afterResponse` key names before trusting B's exclusion. Fallback if candidates shape is intractable: switch to `get-appointment-slots` (slot count 0 for a B-required window = B excluded), mirroring `get-slots-required-violating`.

- [ ] **Step 3: Add the Kick constant**

In `ReVomanConfigForWfs.java`, after the `PRIOR_APPT2_B_OPTIONAL_CONFIG` block (~line 264):
```java
  public static final Kick PRIOR_CANDIDATES_APPT2_CONFIG =
      kickFor(V3_WFS_PATH + "booking/get-candidates-prior-appt2-b-required");
```

- [ ] **Step 4: Compile test classes**

Run: `gradle testClasses` (or `compileIntegrationTestJava`). Expected: BUILD SUCCESSFUL (constant added, no test yet uses it).

- [ ] **Step 5: Commit**

```bash
git add src/integrationTest/resources/pm-templates/v3/core/wfs/booking/get-candidates-prior-appt2-b-required \
        src/integrationTest/java/com/salesforce/revoman/integration/core/wfs/ReVomanConfigForWfs.java
git commit -m "test(scheduler-parity): add Unified get-candidates read-probe fixture for prior-appt2 window" \
  -- src/integrationTest/resources/pm-templates/v3/core/wfs/booking/get-candidates-prior-appt2-b-required \
     src/integrationTest/java/com/salesforce/revoman/integration/core/wfs/ReVomanConfigForWfs.java
```

---

## Task 3: OLD Overbooking flip + revert fixtures

**Task 1 SPIKE RESULT (live-confirmed):** Metadata API works — `updateMetadata(IndustriesSettings{enableOverbookingOrgPref})` returns `<success>true</success>` and reads back live (no restart). Use it. Corrections from the spike vs the draft below: **`SOAPAction: updateMetadata`** (NOT empty `''`), `Content-Type: text/xml; charset=UTF-8`, endpoint `/services/Soap/m/{{version}}` (v67.0), token var `{{adminToken}}`. The `AppointmentBooking` perm prereq is satisfied (multi-resource pref already ON). No PLSQL fallback needed.

**Files:**
- Create: `pm-templates/v3/core/scheduler/booking/enable-overbooking/10-update-metadata.request.yaml`
- Create: `pm-templates/v3/core/scheduler/booking/enable-overbooking/.resources/definition.yaml`
- Create: `pm-templates/v3/core/scheduler/booking/disable-overbooking/10-update-metadata.request.yaml`
- Create: `pm-templates/v3/core/scheduler/booking/disable-overbooking/.resources/definition.yaml`
- Modify: `SchedulerParityConfig.java` (add 2 Kick constants after `OLD_PRIOR_APPT2_B_OPTIONAL_CONFIG`, ~line 88)

**Interfaces:**
- Consumes: `{{baseUrl}}`, `{{adminToken}}` (SOAP sessionId from OLD auth), `{{version}}` (numeric, from latest-api-version).
- Produces: env `overbookingFlipSuccess` ("true"/"false"); Kick constants `SchedulerParityConfig.OLD_ENABLE_OVERBOOKING_CONFIG`, `OLD_DISABLE_OVERBOOKING_CONFIG`.

- [ ] **Step 1: Create enable-overbooking `.resources/definition.yaml`**

Clone auth block from `pm-templates/v3/core/scheduler/booking/service-appointments-prior-appt2-b-required/.resources/definition.yaml` (bearer `{{adminToken}}`). Description: "OLD Metadata-API flip: enable OrgPreferences.Overbooking (enableOverbookingOrgPref=true) so the OLD booking API allows double-booking an occupied resource. Reads live, no restart."

- [ ] **Step 2: Create the enable request** (`10-update-metadata.request.yaml`)

Use the EXACT working envelope from Task 1's spike. Template:
```yaml
$kind: http-request
description: >-
  OLD Overbooking flip ON via Metadata API updateMetadata(IndustriesSettings). enableOverbookingOrgPref=true
  makes AppointmentBookingAccessChecks.orgHasOverbooking() true so SchedulingServiceImpl treats an overlapping
  existing appointment as concurrent-allowed → OLD books over it (converges with Unified). Asserts SOAP success.
url: "{{baseUrl}}/services/Soap/m/{{version}}"
method: POST
headers:
  Content-Type: text/xml
  SOAPAction: updateMetadata
  charset: UTF-8
  Accept: text/xml
body:
  type: text
  content: |-
    <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/"
        xmlns:met="http://soap.sforce.com/2006/04/metadata">
      <soapenv:Header><met:SessionHeader><met:sessionId>{{adminToken}}</met:sessionId></met:SessionHeader></soapenv:Header>
      <soapenv:Body>
        <met:updateMetadata>
          <met:metadata xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:type="met:IndustriesSettings">
            <met:fullName>IndustriesSettings</met:fullName>
            <met:enableOverbookingOrgPref>true</met:enableOverbookingOrgPref>
          </met:metadata>
        </met:updateMetadata>
      </soapenv:Body>
    </soapenv:Envelope>
scripts:
  - type: afterResponse
    code: |-
      var xml2js = require('xml2js');
      xml2js.parseString(pm.response.text(), { explicitArray: false }, (_, j) => {
        try {
          const r = j['soapenv:Envelope']['soapenv:Body'].updateMetadataResponse.result;
          pm.environment.set("overbookingFlipSuccess", String(r.success));
        } catch (e) { pm.environment.set("overbookingFlipSuccess", "false"); }
      });
      console.log("OVERBOOKING enable http=" + pm.response.code + " ok=" + pm.environment.get("overbookingFlipSuccess") + " body=" + pm.response.text().slice(0,400));
    language: text/javascript
settings:
  disabledSystemHeaders:
    - accept
    - content-type
order: 1000
```

- [ ] **Step 3: Create disable-overbooking fixture** (mirror of Step 1–2 with `enableOverbookingOrgPref>false`, no success-capture needed; description "revert to default OFF so sibling OLD scenarios keep default-OFF"). Same `.resources` marker (new auth id).

- [ ] **Step 4: Add Kick constants to `SchedulerParityConfig.java`** (after line 88):
```java
  static final Kick OLD_ENABLE_OVERBOOKING_CONFIG =
      oldKickFor(V3_SCHEDULER_PATH + "booking/enable-overbooking");
  static final Kick OLD_DISABLE_OVERBOOKING_CONFIG =
      oldKickFor(V3_SCHEDULER_PATH + "booking/disable-overbooking");
```

- [ ] **Step 5: Compile** — `gradle testClasses`. Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**
```bash
git add src/integrationTest/resources/pm-templates/v3/core/scheduler/booking/enable-overbooking \
        src/integrationTest/resources/pm-templates/v3/core/scheduler/booking/disable-overbooking \
        src/integrationTest/java/com/salesforce/revoman/integration/core/scheduler/SchedulerParityConfig.java
git commit -m "test(scheduler-parity): add OLD Overbooking flip/revert Metadata-API fixtures" \
  -- src/integrationTest/resources/pm-templates/v3/core/scheduler/booking/enable-overbooking \
     src/integrationTest/resources/pm-templates/v3/core/scheduler/booking/disable-overbooking \
     src/integrationTest/java/com/salesforce/revoman/integration/core/scheduler/SchedulerParityConfig.java
```

---

## Task 4: Add the two test methods

**Files:**
- Modify: `SchedulerVsUnifiedParityE2ETest.java` (add 2 `@Test` methods + 1 helper after the existing `testPriorAssignmentOccupancyParity_E2E`, ~line 1948; and correct that method's javadoc per Task 6)

**Interfaces:**
- Consumes: `ReVomanConfigForWfs.{AUTH_CONFIG, AVAILABILITY_OP_HOURS_POLICY_CONFIG, PRIOR_ASSIGNMENT_FIXTURE_CONFIG, PRIOR_APPT1_B_REQUIRED_CONFIG, PRIOR_CANDIDATES_APPT2_CONFIG}`; `SchedulerParityConfig.{OLD_AUTH_CONFIG, OLD_PRIOR_ASSIGNMENT_FIXTURE_CONFIG, OLD_GRANT_LS_ACCESS_CONFIG, OLD_PRIOR_APPT1_B_REQUIRED_CONFIG, OLD_PRIOR_APPT2_B_REQUIRED_CONFIG, OLD_PRIOR_APPT1_B_OPTIONAL_CONFIG, OLD_PRIOR_APPT2_B_OPTIONAL_CONFIG, OLD_ENABLE_OVERBOOKING_CONFIG, OLD_DISABLE_OVERBOOKING_CONFIG, WriteOutcome, oldWriteOutcome, oldOccupancyCell, assumeBothOrgCreds}`.
- Produces: `@Test testPriorAssignmentUnifiedReadVsWriteE2E`, `@Test testPriorAssignmentOldOverbookingFlipE2E`.

- [ ] **Step 1: Write `testPriorAssignmentUnifiedReadVsWriteE2E` (the failing test)**

Add after `testPriorAssignmentOccupancyParity_E2E`. Single revUp: AUTH → policy → fixture → schedule appt #1 (B required) → get-candidates read-probe. Assert: appt #1 booked (Success), read-probe **excludes B** AND **includes C**. Then reuse `unifiedOccupancyCell` (or an inline write) to assert appt #2 **BOOKS** B.
```java
  /**
   * Unified read-vs-write occupancy contract (verified against Core p4/260-patch). Unified's READ
   * surface DOES compute occupancy: after appt #1 commits an overlapping ServiceAppointment on B,
   * {@code get-appointment-candidates} for appt #2's window EXCLUDES busy B while still offering the
   * free primary C ({@code UnavailabilityService.getResourceUnavailability} subtracts existing SAs,
   * invoked only on the get-candidates/get-slots path). Yet the WRITE surface
   * ({@code /actions/schedule}) BOOKS B over that same appointment, because {@code ScheduleProcessor}
   * persists the caller-supplied {@code assignedResources} with no occupancy re-check. This pins the
   * OLD↔Unified divergence at the read/write boundary — a write-path API-contract difference, NOT a
   * missing policy rule (the handoff's CalendarEvents hypothesis is false: that rule was removed in 264
   * and even on 262 gated only Salesforce Event records, never ServiceAppointments).
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
    // Control: the read surface offers the free primary C (proves the read ran and the extraction works).
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
```
NOTE: `unifiedOccupancyCell` runs its own revUp (fresh fixture) — the read-probe env above and the write cell are independent revUps, which is fine (the write cell re-seeds appt #1). If reusing the same env is preferred, inline the appt#2 schedule instead; the two-revUp form is simpler and matches the existing test's per-cell pattern.

- [ ] **Step 2: Write `testPriorAssignmentOldOverbookingFlipE2E` (the failing test)**

OLD org only. Flip Overbooking ON, run all 4 cells (each must now BOOK), revert OFF in a finally. Add a private helper `oldOccupancyCellWithOverbooking` mirroring `oldOccupancyCell` but inserting `OLD_ENABLE_OVERBOOKING_CONFIG` after AUTH — OR flip once outside and run the existing `oldOccupancyCell`. Preferred: flip once, run cells, revert once.
```java
  /**
   * OLD Overbooking flip confirmation. The existing {@link #testPriorAssignmentOccupancyParity_E2E}
   * pins OLD REFUSING all four cells at the default {@code OrgPreferences.Overbooking=OFF}
   * ({@code AppointmentBookingAccessChecks.orgHasOverbooking()} gates the concurrency branch in
   * {@code SchedulingServiceImpl.findAvailableTimeSlots}, reached from the write path via
   * {@code ServiceAppointmentServiceImpl.create → areResourcesAvailable → getAppointmentSlots}). With
   * the pref flipped ON, OLD treats the overlapping existing appointment as concurrent-allowed and
   * BOOKS all four — converging with Unified's write behavior. Confirms the OLD refusal is pref-gated
   * config, not a hard product invariant. Reverts the pref to OFF afterward so sibling OLD scenarios
   * keep their default-OFF assumption.
   */
  @Test
  void testPriorAssignmentOldOverbookingFlipE2E() {
    SchedulerParityConfig.assumeBothOrgCreds();
    try {
      flipOldOverbooking(ReVomanConfigForWfs /*unused*/); // see helper below
      // (a) req->req, (b) opt->req, (c) opt->opt, (d) req->opt — all BOOK with Overbooking ON.
      assertWithMessage("Overbooking ON: (a) req->req must now BOOK")
          .that(oldOccupancyCell(
              SchedulerParityConfig.OLD_PRIOR_APPT1_B_REQUIRED_CONFIG,
              SchedulerParityConfig.OLD_PRIOR_APPT2_B_REQUIRED_CONFIG))
          .isEqualTo(SchedulerParityConfig.WriteOutcome.BOOKED);
      assertThat(oldOccupancyCell(
              SchedulerParityConfig.OLD_PRIOR_APPT1_B_OPTIONAL_CONFIG,
              SchedulerParityConfig.OLD_PRIOR_APPT2_B_REQUIRED_CONFIG))
          .isEqualTo(SchedulerParityConfig.WriteOutcome.BOOKED);
      assertThat(oldOccupancyCell(
              SchedulerParityConfig.OLD_PRIOR_APPT1_B_OPTIONAL_CONFIG,
              SchedulerParityConfig.OLD_PRIOR_APPT2_B_OPTIONAL_CONFIG))
          .isEqualTo(SchedulerParityConfig.WriteOutcome.BOOKED);
      assertThat(oldOccupancyCell(
              SchedulerParityConfig.OLD_PRIOR_APPT1_B_REQUIRED_CONFIG,
              SchedulerParityConfig.OLD_PRIOR_APPT2_B_OPTIONAL_CONFIG))
          .isEqualTo(SchedulerParityConfig.WriteOutcome.BOOKED);
    } finally {
      revertOldOverbooking();
    }
  }
```
Implement `flipOldOverbooking` / `revertOldOverbooking` as private static helpers in the test that revUp `OLD_AUTH_CONFIG` + `OLD_ENABLE_OVERBOOKING_CONFIG` (resp. DISABLE) and assert `overbookingFlipSuccess == "true"` on enable via `assertWithMessage` (fail loud if the flip no-op'd — else BOOKED would be a false positive). NOTE: `oldOccupancyCell` starts its own revUp with a fresh AUTH; since Overbooking is an ORG-level pref (not session), flipping it in a prior revUp persists for subsequent revUps on the same org — verify this holds live (it should; the pref is committed org state). If the pref somehow doesn't persist across revUps, fold the enable step into a variant occupancy-cell helper that includes `OLD_ENABLE_OVERBOOKING_CONFIG` in the same revUp.

- [ ] **Step 3: Compile** — `gradle testClasses`. Expected: BUILD SUCCESSFUL. (Fix the `flipOldOverbooking` placeholder signature — no unused param; shown above schematically.)

- [ ] **Step 4: Commit**
```bash
git add src/integrationTest/java/com/salesforce/revoman/integration/core/scheduler/SchedulerVsUnifiedParityE2ETest.java
git commit -m "test(scheduler-parity): add Unified read-vs-write probe + OLD Overbooking-flip test methods" \
  -- src/integrationTest/java/com/salesforce/revoman/integration/core/scheduler/SchedulerVsUnifiedParityE2ETest.java
```

---

## Task 5: Live run + iterate to green

**Files:** none new; fix fixtures/tests from Tasks 2–4 as the live run reveals.

- [ ] **Step 1: Run the Unified read-vs-write test**

Run (spawn a sub-agent for this ~4min run so the main thread is free):
`gradle integrationTest --tests "com.salesforce.revoman.integration.core.scheduler.SchedulerVsUnifiedParityE2ETest.testPriorAssignmentUnifiedReadVsWriteE2E"`
Expected: PASS. If the C-included control fails → fix the candidate-id extraction keys in the fixture `afterResponse` (dump body is logged) or switch to the get-slots fallback. Do NOT weaken the B-excluded assertion to force green.

- [ ] **Step 2: Run the OLD Overbooking-flip test**

`gradle integrationTest --tests "...SchedulerVsUnifiedParityE2ETest.testPriorAssignmentOldOverbookingFlipE2E"`
Expected: PASS (all 4 cells BOOK). If a cell still REFUSES → the flip didn't take effect: check `overbookingFlipSuccess`, the `AppointmentBooking` perm (Task 1), and pref-cache/persistence. Apply Task 1's fallback if needed.

- [ ] **Step 3: Confirm no collateral breakage**

Re-run the original occupancy test to confirm the revert restored default-OFF:
`gradle integrationTest --tests "...SchedulerVsUnifiedParityE2ETest.testPriorAssignmentOccupancyParity_E2E"`
Expected: PASS (OLD still REFUSES all 4 — proves `disable-overbooking` reverted cleanly). If it now BOOKS, the revert failed — fix `disable-overbooking` before proceeding.

- [ ] **Step 4: Commit any fixture/test fixes**
```bash
git add -A
git commit -m "test(scheduler-parity): fix read-probe extraction / overbooking flip per live run" -- <changed paths>
```

---

## Task 6: Correct existing javadoc + comments (verified truth)

**Files:** Modify `SchedulerVsUnifiedParityE2ETest.java` — the `testPriorAssignmentOccupancyParity_E2E` javadoc (~lines 1814–1887) and inline comments still say the drift is CalendarEvents-config-gated with a TODO to add the rule. Replace with the verified root cause.

- [ ] **Step 1: Rewrite the "264 Unified engine" + "Root cause" + TODO paragraphs**

Replace the CalendarEvents-config-gated explanation with: Unified's `schedule` action persists caller-supplied resources without an occupancy check (`ScheduleProcessor`); occupancy is computed only on the get-candidates/get-slots read path (`UnavailabilityService`, sole caller `InBusinessGetCandidatesSlotsDataService`); the CalendarEvents rule was removed in 264 and never gated ServiceAppointment occupancy. Replace the TODO ("add CalendarEvents rule and re-run") with cross-references to the two new tests (`testPriorAssignmentUnifiedReadVsWriteE2E`, `testPriorAssignmentOldOverbookingFlipE2E`) that confirm the true root cause. Keep the verbatim-pin framing (no `old==unified` assert).

- [ ] **Step 2: Compile + spot-run** — `gradle testClasses`. Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**
```bash
git add src/integrationTest/java/com/salesforce/revoman/integration/core/scheduler/SchedulerVsUnifiedParityE2ETest.java
git commit -m "docs(scheduler-parity): correct occupancy javadoc — write-path contract diff, not CalendarEvents config (CalendarEvents removed in 264)" \
  -- src/integrationTest/java/com/salesforce/revoman/integration/core/scheduler/SchedulerVsUnifiedParityE2ETest.java
```

---

## Task 7: Update the Google Sheet

**Files:** none (external). Sheet `147N4ZEteXjxgKx34f9ZWoPJ5T_42l2li83x9Ejq03FQ`.

- [ ] **Step 1: Update `multi-resource!12`** — verdict from "unconfirmed/likely config" to the CONFIRMED result: "Verified (Core p4/260-patch): write-path contract difference — Unified schedule action trusts caller resources (no occupancy check); OLD refusal is OrgPreferences.Overbooking-gated (flip ON → OLD books). NOT config-gated; CalendarEvents removed in 264." ONE cell write.

- [ ] **Step 2: Update `[WIP] How each test works!C20`** — reflect the two new tests + the read-vs-write finding. ONE cell write. If either write times out, READ BACK before retrying (writes usually don't land on timeout).

- [ ] **Step 3: No commit** (external). Note completion in the decision log.

---

## Self-Review notes (done during authoring)

- **Spec coverage:** Part 1 (read-vs-write) → Tasks 2,4. Part 2 (OLD flip) → Tasks 1,3,4. Mechanism decision → Task 1. Faithfulness/fail-loud → Task 4 assertWithMessage + Task 3 success capture. Doc/sheet → Tasks 6,7. All spec sections covered.
- **Empirical points flagged, not hidden:** the Metadata-API envelope shape (Task 1 spike), candidate-response id extraction (Task 2 note + Task 5 control assertion), and pref persistence across revUps (Task 4 note) are the three things that can only be nailed live — each has an explicit verify step and a named fallback, not a silent assumption.
- **Fallff / blocker:** if OLD Overbooking can't be flipped over the wire AND PLSQL+restart is out of reach, mark `testPriorAssignmentOldOverbookingFlipE2E` `@Disabled("<reason>")`, write a blocker to `~/work/handoff` per its CLAUDE.md, and ship the Unified read-vs-write test alone (it already pins the more novel finding). Log the decision.
