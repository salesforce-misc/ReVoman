# Scheduler ↔ Unified `1.*` Parity — Vertical Slice (1.5) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a two-org differential ReVoman harness and prove the `1.5` (Availability / double-book helper) parity slice: does OLD Salesforce Scheduler and Unified OnSite make the SAME include/exclude read decision and book/refuse write outcome for a busy non-required helper?

**Architecture:** A new `com.salesforce.revoman.integration.core.scheduler` package. `SchedulerParityConfig` adds a SECOND external-org creds overlay (`~/.revoman/scheduler-config.yaml`) for the scheduler org, alongside the existing `~/.revoman/config.yaml` (262/Unified side, reused via `ReVomanConfigForWfs`). Each `1.*` test runs the same logical fixture against BOTH orgs — old side over public Scheduler REST (`/scheduling/getAppointmentSlots`, `/connect/scheduling/service-appointments`), Unified side over the existing WFS Connect acts — then diffs normalized verdicts.

**Tech Stack:** ReVoman (`ReVoman.revUp`, `Kick` DSL), JUnit 5, Google Truth, Postman V3 collections (YAML dirs), Kotlin stdlib interop. Java per `/my-java-coding-style` (final var, functional, no bare null).

## Global Constraints

- **Old side = scheduler org** `orgfarm-0c6bcb96c0…crm.dev:6101`; **Unified side = the live Unified org** `orgfarm-4dbef90d6c…crm.dev:6101` (version 262 / local HEAD). Both local-bound → ReVoman external-org mode.
- **Never commit creds.** `~/.revoman/config.yaml` and `~/.revoman/scheduler-config.yaml` live in `$HOME`, outside the repo. Committed env yamls stay blank.
- **Old-side SOAP login uses v64** (`/services/Soap/u/64.0`); v67 rejects SOAP login. REST version comes from `latest-api-version` (`versionPath`).
- **SchedulingMethod="OnSite"** on TimeSlot/Shift + any Unified schedule body; WorkType carries NO SchedulingMethod. (Old scheduler org value validity is probed in Task 2.)
- **Verdict equality compares NORMALIZED verdicts only** (`BOOKED/REFUSED/CRASHED`, `INCLUDED/EXCLUDED`) — never raw errorCodes (old REST `isError`/HTTP vs Unified `schedulingStatus`; guide R3).
- **Control-first discipline:** confirm each control RED for the intended reason before trusting the positive GREEN.
- Run branch: `wfs/decision-1-9-revoman-tests` in `~/code-clones/work/revoman-root`. Fast loop = external-org mode; the Java lives in the `integrationTest` source set (`gradle :compileIntegrationTestJava`).

---

## File structure

- **Create** `src/integrationTest/java/com/salesforce/revoman/integration/core/scheduler/SchedulerParityConfig.java` — two-org config seam. Old-side Kicks (scheduler org creds overlay) + reuse of `ReVomanConfigForWfs` Unified Kicks + `assumeBothOrgCreds()`.
- **Create** `src/integrationTest/java/com/salesforce/revoman/integration/core/scheduler/SchedulerVsUnifiedParityE2ETest.java` — the differential test class (slice: one method).
- **Create** V3 old-Scheduler collection dirs under `src/integrationTest/resources/pm-templates/v3/core/scheduler/`:
  - `auth/` — SOAP v64 login + latest-api-version (admin session; scheduler org has no manager-persona requirement for the slice).
  - `fixtures/double-book/` — the double-book data graph (clone of the WFS one, retargeted).
  - `booking/get-appointment-slots-double-book/` — old READ act (`/scheduling/getAppointmentSlots`), captures `oldReadResourceBIncluded`.
  - `booking/service-appointments-double-book-non-required/` — old WRITE act (non-required helper), captures `oldWriteHelperOutcome`.
  - `booking/service-appointments-double-book-required-control/` — old WRITE control (helper→required), captures `oldWriteRequiredControlOutcome`.
  - `scheduler.environment.yaml` — blank-cred env (baseUrl/username/password) for the old side.
- **Create** `~/.revoman/scheduler-config.yaml` (in `$HOME`, NOT committed) — scheduler org baseUrl/username/password.
- **Reuse unchanged:** `ReVomanConfigForWfs` Unified Kicks (`AUTH_CONFIG`, `AVAILABILITY_OP_HOURS_POLICY_CONFIG`, `DOUBLE_BOOK_FIXTURE_CONFIG`, `DOUBLE_BOOK_NON_REQUIRED_SCHEDULE_CONFIG`, `DOUBLE_BOOK_REQUIRED_CONFLICT_SCHEDULE_CONFIG`).

---

### Task 1: Two-org config seam + scheduler-org auth bind

Proves the second org binds and mints a session — the novel harness mechanic — before any fixture work.

**Files:**
- Create: `src/integrationTest/java/com/salesforce/revoman/integration/core/scheduler/SchedulerParityConfig.java`
- Create: `src/integrationTest/resources/pm-templates/v3/core/scheduler/scheduler.environment.yaml`
- Create: `src/integrationTest/resources/pm-templates/v3/core/scheduler/auth/.resources/definition.yaml`
- Create: `src/integrationTest/resources/pm-templates/v3/core/scheduler/auth/login-as-sysadmin.request.yaml`
- Create: `src/integrationTest/resources/pm-templates/v3/core/scheduler/auth/latest-api-version.request.yaml`
- Create (temporary probe test): `src/integrationTest/java/com/salesforce/revoman/integration/core/scheduler/SchedulerVsUnifiedParityE2ETest.java`
- Create (in `$HOME`, not committed): `~/.revoman/scheduler-config.yaml`

**Interfaces:**
- Produces:
  - `SchedulerParityConfig.OLD_AUTH_CONFIG` (`Kick`) — scheduler-org SOAP-login + version.
  - `SchedulerParityConfig.assumeBothOrgCreds()` (`static void`) — JUnit-skips unless BOTH `~/.revoman/config.yaml` and `~/.revoman/scheduler-config.yaml` carry baseUrl/username/password.
  - Env keys after `OLD_AUTH_CONFIG`: `adminToken`, `accessToken`, `versionPath`, `apiVersion`, `orgId`.

- [ ] **Step 1: Create the (uncommitted) scheduler creds file**

```bash
mkdir -p ~/.revoman
cat > ~/.revoman/scheduler-config.yaml <<'YAML'
baseUrl: '<scheduler-org-baseUrl>'   # e.g. https://<org>...crm.dev:6101
username: <scheduler-org-username>
password: <scheduler-org-password>
YAML
```
Expected: file exists; `grep -c baseUrl ~/.revoman/scheduler-config.yaml` → `1`. (Confirm `~/.revoman/config.yaml` already holds the 262 creds; if not, create it with the 262 org's baseUrl/username/password.)

- [ ] **Step 2: Create the blank old-side env** `…/v3/core/scheduler/scheduler.environment.yaml`

```yaml
name: scheduler
values:
  - key: baseUrl
    value: ''
  - key: username
    value: ''
  - key: password
    value: ''
  - key: adminToken
    value: ''
  - key: accessToken
    value: ''
```

- [ ] **Step 3: Create the old-side auth collection definition** `…/scheduler/auth/.resources/definition.yaml`

```yaml
$kind: collection
description: |-
  Scheduler-org bootstrap auth for the Scheduler↔Unified 1.* parity slice. SOAP-logs-in as the org
  admin (v64) to seed {{adminToken}}/{{accessToken}}, then resolves versionPath. The slice's old-side
  reads/writes run under {{adminToken}} (admin session is sufficient for the read+book parity probe;
  no manager-persona split is needed here — that is a Decision-9 concern, out of slice).
auth:
  - id: 7e1f0c00-1005-4aaa-9bbb-000000000001
    type: bearer
    name: bearer auth
    credentials:
      token: "{{adminToken}}"
```

- [ ] **Step 4: Create SOAP login** `…/scheduler/auth/login-as-sysadmin.request.yaml`

```yaml
$kind: http-request
description: >-
  SOAP login (v64) to the SCHEDULER org; seeds adminToken/accessToken/orgId. Runs first (order 500) so
  latest-api-version and every downstream old-side call has a live session.
url: "{{baseUrl}}/services/Soap/u/64.0"
method: POST
headers:
  Content-Type: text/xml
  SOAPAction: login
  charset: UTF-8
  Accept: text/xml
body:
  type: text
  content: |-
    <?xml version="1.0" encoding="utf-8" ?>
    <env:Envelope xmlns:xsd="http://www.w3.org/2001/XMLSchema"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xmlns:env="http://schemas.xmlsoap.org/soap/envelope/">
      <env:Body>
        <n1:login xmlns:n1="urn:partner.soap.sforce.com">
          <n1:username><![CDATA[{{username}}]]></n1:username>
          <n1:password><![CDATA[{{password}}]]></n1:password>
        </n1:login>
      </env:Body>
    </env:Envelope>
scripts:
  - type: afterResponse
    code: |-
      var xml2js = require('xml2js')
      xml2js.parseString(pm.response.text(), { explicitArray: false }, (_, jsonResponse) => {
        let result = jsonResponse['soapenv:Envelope']['soapenv:Body'].loginResponse.result
        pm.environment.set("accessToken", result.sessionId)
        pm.environment.set("adminToken", result.sessionId)
        pm.environment.set("adminUserId", result.userId)
        pm.environment.set("orgId", result.userInfo.organizationId)
      })
    language: text/javascript
settings:
  disabledSystemHeaders:
    - accept
    - content-type
    - user-agent
order: 500
```

- [ ] **Step 5: Create version resolver** `…/scheduler/auth/latest-api-version.request.yaml`

```yaml
$kind: http-request
description: Resolve the scheduler org's latest API version → version / apiVersion / versionPath.
url: "{{baseUrl}}/services/data/"
method: GET
headers:
  Accept: application/json
scripts:
  - type: afterResponse
    code: |-
      var jsonData = pm.response.json();
      var lastNode = jsonData.pop();
      pm.environment.set("version", lastNode.version);
      pm.environment.set("apiVersion", "v" + lastNode.version);
      pm.environment.set("versionPath", lastNode.url);
    language: text/javascript
settings:
  disabledSystemHeaders:
    - accept
    - content-type
order: 1000
```

- [ ] **Step 6: Write `SchedulerParityConfig`** `…/core/scheduler/SchedulerParityConfig.java`

```java
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
 * Two-org config seam for the Salesforce Scheduler ↔ Unified {@code 1.*} helper-fitness parity
 * tests. The OLD side (scheduler org) is driven over public Scheduler REST; the Unified side reuses
 * the existing {@link ReVomanConfigForWfs} Unified Kicks against the live Unified org (version 262 /
 * local HEAD). The two OnSite engines are SEPARATE re-implementations (old {@code
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

  /**
   * Skip (JUnit assumption) unless BOTH orgs' creds are present: the Unified side reads {@code
   * ~/.revoman/config.yaml} (via {@link ReVomanConfigForWfs}) and the old side reads {@code
   * ~/.revoman/scheduler-config.yaml} (here). Either missing → skip, not fail.
   */
  static void assumeBothOrgCreds() {
    ReVomanConfigForWfs.assumeExternalOrgCreds();
    Assumptions.assumeTrue(
        schedulerHasText("baseUrl") && schedulerHasText("username") && schedulerHasText("password"),
        "Scheduler-org creds absent — set ~/.revoman/scheduler-config.yaml (baseUrl/username/password)."
            + " Skipping.");
  }

  private static boolean schedulerHasText(final String key) {
    final var value = SCHEDULER_ORG_CONFIG.get(key);
    return value != null && !value.toString().isBlank();
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
```

- [ ] **Step 7: Write the auth-bind probe test** `…/scheduler/SchedulerVsUnifiedParityE2ETest.java`

```java
/*
 * Copyright 2026 salesforce.com, inc.
 * All Rights Reserved
 * Company Confidential
 */

package com.salesforce.revoman.integration.core.scheduler;

import static com.google.common.truth.Truth.assertThat;
import static com.salesforce.revoman.integration.core.scheduler.SchedulerParityConfig.OLD_AUTH_CONFIG;

import com.salesforce.revoman.ReVoman;
import kotlin.collections.CollectionsKt;
import org.junit.jupiter.api.Test;

/**
 * Scheduler ↔ Unified {@code 1.*} helper-fitness parity — differential tests. Each scenario diffs
 * the OLD Salesforce Scheduler decision against the Unified decision on BOTH the read
 * (INCLUDED/EXCLUDED) and write (BOOKED/REFUSED/CRASHED) axes.
 */
class SchedulerVsUnifiedParityE2ETest {

  @Test
  void schedulerOrgAuthBindsE2E() {
    SchedulerParityConfig.assumeBothOrgCreds();
    final var rundown = ReVoman.revUp(OLD_AUTH_CONFIG);
    final var env = CollectionsKt.last(rundown).mutableEnv;
    assertThat(env.getAsString("adminToken")).isNotEmpty();
    assertThat(env.getAsString("versionPath")).contains("/services/data/v");
  }
}
```

- [ ] **Step 8: Compile**

Run: `cd ~/code-clones/work/revoman-root && gradle :compileIntegrationTestJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 9: Run the auth-bind probe**

Run: `cd ~/code-clones/work/revoman-root && gradle :integrationTest --tests '*SchedulerVsUnifiedParityE2ETest.schedulerOrgAuthBindsE2E'`
Expected: PASS (adminToken minted, versionPath resolved). If the org uses a different SOAP version, adjust `64.0`. If it SKIPS, the creds files are missing/blank — fix Step 1.

- [ ] **Step 10: Commit**

```bash
cd ~/code-clones/work/revoman-root
git add src/integrationTest/java/com/salesforce/revoman/integration/core/scheduler \
        src/integrationTest/resources/pm-templates/v3/core/scheduler
git commit -m "test(scheduler-parity): two-org config seam + scheduler-org auth bind"
```

---

### Task 2: Old-side double-book fixture graph

Clone the proven WFS double-book graph onto the scheduler org (standard objects, so the graph transfers). This task's deliverable is: the graph inserts clean on the scheduler org and exposes the same resource/territory/workType/account ids.

**Files:**
- Create: `…/v3/core/scheduler/fixtures/double-book/.resources/definition.yaml`
- Create: `…/v3/core/scheduler/fixtures/double-book/create-double-book-graph.request.yaml`
- Modify: `SchedulerParityConfig.java` (add the fixture Kick)

**Interfaces:**
- Produces `SchedulerParityConfig.OLD_DOUBLE_BOOK_FIXTURE_CONFIG` (`Kick`); env keys after it: `schedTerritoryId`, `schedWorkTypeId`, `schedAccountId`, `schedResourceAId`, `schedResourceBId`.
- Consumes from Task 1: a live `{{adminToken}}` + `{{versionPath}}`.

- [ ] **Step 1: Copy the WFS graph as the starting point**

```bash
cd ~/code-clones/work/revoman-root/src/integrationTest/resources/pm-templates/v3/core/scheduler
mkdir -p fixtures/double-book/.resources
cp ../wfs/fixtures/double-book-non-required/create-double-book-non-required-graph.request.yaml \
   fixtures/double-book/create-double-book-graph.request.yaml
```

- [ ] **Step 2: Retarget the copied graph.** In `fixtures/double-book/create-double-book-graph.request.yaml`, make exactly these edits (the graph body of OperatingHours/TimeSlot/ServiceTerritory/WorkType/STWT/ServiceResource/STM/Shift/Account is unchanged — standard objects book on either org):
  - The two `RelatedRecordId` values `{{caseWorkerUserId}}` / `{{caseManagerUserId}}` → `{{adminUserId}}` for resourceA and, for resourceB, a second scheduler-org user id. **If the scheduler org has only the admin user available**, create a second ServiceResource on an Asset instead (`ResourceType": "T"` → keep, but point `RelatedRecordId` at a second user; if none, add a `User` insert node to the graph, mirroring the WFS `auth/create-case-worker` body, under `{{adminToken}}`). Decide during the run from what the org exposes; log the choice.
  - In the `afterResponse` script, rename the captured env keys to the `sched*` names: `doubleBookTerritoryId`→`schedTerritoryId`, `doubleBookWorkTypeId`→`schedWorkTypeId`, `doubleBookAccountId`→`schedAccountId`, `doubleBookResourceAId`→`schedResourceAId`, `doubleBookResourceBId`→`schedResourceBId`. Keep the `beforeRequest` time-window script (shift A 10-14, shift B 12-14, STM effective a month ago) verbatim — the 11:00-11:30 booking window and B's noon-start gap are the mechanism.
  - Keep `SchedulingMethod": "OnSite"` on TimeSlot/Shift for now; **if the scheduler org rejects it** with `INVALID_OR_NULL_FOR_RESTRICTED_PICKLIST`, drop the `SchedulingMethod` field from TimeSlot/Shift entirely (old Scheduler doesn't require it) and record that in the file's top comment.

- [ ] **Step 3: Create the fixture definition** `fixtures/double-book/.resources/definition.yaml`

```yaml
$kind: collection
description: |-
  OLD Scheduler-org double-book data graph (clone of the WFS Unified fixture, retargeted to the scheduler
  org and admin session). Territory OH 08-16; resourceA member OH + Shift 10-14 (covers 11:00-11:30);
  resourceB member OH + Shift 12-14 (does NOT cover 11:00 → busy at the window). Standard objects, so the
  same graph books on the scheduler org. Sets sched{Territory,WorkType,Account,ResourceA,ResourceB}Id.
auth:
  - id: 7e1f0c00-1005-4aaa-9bbb-000000000010
    type: bearer
    name: bearer auth
    credentials:
      token: "{{adminToken}}"
```

- [ ] **Step 4: Add the fixture Kick to `SchedulerParityConfig`** (after `OLD_AUTH_CONFIG`)

```java
  static final Kick OLD_DOUBLE_BOOK_FIXTURE_CONFIG =
      oldKickFor(V3_SCHEDULER_PATH + "fixtures/double-book");
```

- [ ] **Step 5: Add a fixture-only probe test method** to `SchedulerVsUnifiedParityE2ETest`

```java
  @Test
  void oldDoubleBookFixtureInsertsE2E() {
    SchedulerParityConfig.assumeBothOrgCreds();
    final var rundown =
        ReVoman.revUp(
            SchedulerParityConfig.OLD_AUTH_CONFIG,
            SchedulerParityConfig.OLD_DOUBLE_BOOK_FIXTURE_CONFIG);
    final var env = CollectionsKt.last(rundown).mutableEnv;
    assertThat(env.getAsString("schedResourceAId")).isNotEmpty();
    assertThat(env.getAsString("schedResourceBId")).isNotEmpty();
    assertThat(env.getAsString("schedTerritoryId")).isNotEmpty();
  }
```

- [ ] **Step 6: Compile + run the fixture probe**

Run: `cd ~/code-clones/work/revoman-root && gradle :compileIntegrationTestJava && gradle :integrationTest --tests '*SchedulerVsUnifiedParityE2ETest.oldDoubleBookFixtureInsertsE2E'`
Expected: PASS; all five ids non-empty. If a composite step fails, the thrown message names the `referenceId` — fix that node (likely the SchedulingMethod picklist per Step 2, or the second-user requirement).

- [ ] **Step 7: Commit**

```bash
cd ~/code-clones/work/revoman-root
git add src/integrationTest
git commit -m "test(scheduler-parity): old-side double-book fixture graph"
```

---

### Task 3: Old-side READ act — getAppointmentSlots include/exclude decision

Capture whether resourceB is INCLUDED in old-Scheduler's returned slots when named as a required resource vs excluded — the read half of the 1.5 diff.

**Files:**
- Create: `…/scheduler/booking/get-appointment-slots-double-book/.resources/definition.yaml`
- Create: `…/scheduler/booking/get-appointment-slots-double-book/10-get-appointment-slots-double-book.request.yaml`
- Modify: `SchedulerParityConfig.java` (add the read Kick)

**Interfaces:**
- Produces `SchedulerParityConfig.OLD_GET_SLOTS_DOUBLE_BOOK_CONFIG` (`Kick`); env keys: `oldReadWithBSlotCount` (slots when A+B both required), `oldReadAOnlySlotCount` (slots when only A required). Old read INCLUDES B ⟺ naming B as required does NOT drop the count to 0 relative to A-only… but per research the old path AND-intersects required resources, so B-as-required (busy) → 0; the "helper" (non-required) has no read-input representation on the old side. So the read decision we capture is: **does adding busy B as a required id zero out the slots** (proving old read applies availability to required resources) — the old-side analog of "would a helper be checked?".
- Consumes: `schedResourceAId`, `schedResourceBId`, `schedTerritoryId`, `schedWorkTypeId` (Task 2), `versionPath`.

- [ ] **Step 1: Create the read act** `…/get-appointment-slots-double-book/10-get-appointment-slots-double-book.request.yaml`

```yaml
$kind: http-request
description: >-
  OLD Salesforce Scheduler read: POST /scheduling/getAppointmentSlots twice over the double-book fixture,
  tomorrow 11:00-11:30 UTC. Call 1 names BOTH resourceA (free) and resourceB (busy at 11:00) as
  requiredResourceIds → the old engine AND-intersects required-resource availability, so B being busy
  zeroes the slots. Call 2 names ONLY resourceA → slots > 0. This captures the old-side read decision
  (a busy REQUIRED resource is availability-checked and excludes the slot) that the 1.5 parity diff
  compares against the Unified read. NOTE: the old read has NO non-required "helper" input — every named
  id is required — so the read-side probe is "required-and-busy zeroes vs A-only offers", per research.
url: "{{baseUrl}}{{versionPath}}/scheduling/getAppointmentSlots"
method: POST
headers:
  Content-Type: application/json
  Accept: application/json
  ignoreHTTPStatusUnsuccessful: "true"
body:
  type: json
  content: |-
    {
      "workType": { "id": "{{schedWorkTypeId}}" },
      "territoryIds": ["{{schedTerritoryId}}"],
      "requiredResourceIds": ["{{schedResourceAId}}", "{{schedResourceBId}}"],
      "primaryResourceId": "{{schedResourceAId}}",
      "startTime": "{{schedReadStart}}",
      "endTime": "{{schedReadEnd}}"
    }
scripts:
  - type: beforeRequest
    code: |-
      const s = new Date(); s.setUTCDate(s.getUTCDate() + 1); s.setUTCHours(11, 0, 0, 0);
      const e = new Date(s.getTime() + 30 * 60 * 1000);
      pm.environment.set("schedReadStart", s.toISOString());
      pm.environment.set("schedReadEnd", e.toISOString());
    language: text/javascript
  - type: afterResponse
    code: |-
      const data = pm.response.json() || {};
      const slots = (data.timeSlots || data.slots || []);
      pm.environment.set("oldReadWithBSlotCount", String(slots.length));
      console.log("OLD read A+B http=" + pm.response.code + " slots=" + slots.length);
    language: text/javascript
order: 1000
```

- [ ] **Step 2: Create the A-only control read** in the same folder, `20-get-appointment-slots-a-only.request.yaml`

```yaml
$kind: http-request
description: >-
  Control: OLD getAppointmentSlots naming ONLY resourceA (free) as required, same window. Proves the
  fixture is bookable on the old side (slots > 0), so the A+B zero in step 10 is resourceB's busyness,
  not a dead fixture. Captures oldReadAOnlySlotCount.
url: "{{baseUrl}}{{versionPath}}/scheduling/getAppointmentSlots"
method: POST
headers:
  Content-Type: application/json
  Accept: application/json
  ignoreHTTPStatusUnsuccessful: "true"
body:
  type: json
  content: |-
    {
      "workType": { "id": "{{schedWorkTypeId}}" },
      "territoryIds": ["{{schedTerritoryId}}"],
      "requiredResourceIds": ["{{schedResourceAId}}"],
      "primaryResourceId": "{{schedResourceAId}}",
      "startTime": "{{schedReadStart}}",
      "endTime": "{{schedReadEnd}}"
    }
scripts:
  - type: afterResponse
    code: |-
      const data = pm.response.json() || {};
      const slots = (data.timeSlots || data.slots || []);
      pm.environment.set("oldReadAOnlySlotCount", String(slots.length));
      console.log("OLD read A-only http=" + pm.response.code + " slots=" + slots.length);
    language: text/javascript
order: 2000
```

- [ ] **Step 3: Create the read definition** `…/get-appointment-slots-double-book/.resources/definition.yaml`

```yaml
$kind: collection
description: |-
  OLD Scheduler getAppointmentSlots over the double-book fixture: A+B (B busy → 0 slots) vs A-only
  (> 0 slots). Captures oldReadWithBSlotCount + oldReadAOnlySlotCount for the 1.5 read-decision diff.
auth:
  - id: 7e1f0c00-1005-4aaa-9bbb-000000000020
    type: bearer
    name: bearer auth
    credentials:
      token: "{{adminToken}}"
```

- [ ] **Step 4: Add the read Kick to `SchedulerParityConfig`**

```java
  static final Kick OLD_GET_SLOTS_DOUBLE_BOOK_CONFIG =
      oldKickFor(V3_SCHEDULER_PATH + "booking/get-appointment-slots-double-book");
```

- [ ] **Step 5: Add a read-probe test method** to `SchedulerVsUnifiedParityE2ETest`

```java
  @Test
  void oldReadDoubleBookDecisionE2E() {
    SchedulerParityConfig.assumeBothOrgCreds();
    final var rundown =
        ReVoman.revUp(
            SchedulerParityConfig.OLD_AUTH_CONFIG,
            SchedulerParityConfig.OLD_DOUBLE_BOOK_FIXTURE_CONFIG,
            SchedulerParityConfig.OLD_GET_SLOTS_DOUBLE_BOOK_CONFIG);
    final var env = CollectionsKt.last(rundown).mutableEnv;
    // Control proves the fixture is live; A+B zero proves the busy required resource is availability-checked.
    assertThat(Integer.parseInt(env.getAsString("oldReadAOnlySlotCount"))).isGreaterThan(0);
    assertThat(env.getAsString("oldReadWithBSlotCount")).isEqualTo("0");
  }
```

- [ ] **Step 6: Compile + run**

Run: `cd ~/code-clones/work/revoman-root && gradle :compileIntegrationTestJava && gradle :integrationTest --tests '*SchedulerVsUnifiedParityE2ETest.oldReadDoubleBookDecisionE2E'`
Expected: PASS. If the A+B count is NOT 0, the old engine may union rather than intersect (the §1 open item in the spec) — record the observed count and switch the read assertion to characterize the observed old behavior (this is a finding, not a failure). If the endpoint 404s, confirm the `/scheduling/` path + version on the org (Task 1 `versionPath`).

- [ ] **Step 7: Commit**

```bash
cd ~/code-clones/work/revoman-root
git add src/integrationTest
git commit -m "test(scheduler-parity): old-side getAppointmentSlots read decision"
```

---

### Task 4: Old-side WRITE acts — book non-required helper + required control

Capture the old-Scheduler book outcome for a non-required busy helper (expected BOOKED) and its required control (expected REFUSED) — the write half of the 1.5 diff.

**Files:**
- Create: `…/scheduler/booking/service-appointments-double-book-non-required/{.resources/definition.yaml,10-book.request.yaml}`
- Create: `…/scheduler/booking/service-appointments-double-book-required-control/{.resources/definition.yaml,10-book.request.yaml}`
- Modify: `SchedulerParityConfig.java`

**Interfaces:**
- Produces `SchedulerParityConfig.OLD_BOOK_NON_REQUIRED_CONFIG`, `OLD_BOOK_REQUIRED_CONTROL_CONFIG` (`Kick`); env keys `oldWriteHelperHttp`, `oldWriteHelperSaId`, `oldWriteRequiredControlHttp`, `oldWriteRequiredControlSaId`.
- Consumes: fixture ids (Task 2), `versionPath`, `adminToken`.

- [ ] **Step 1: Create the non-required book act** `…/service-appointments-double-book-non-required/10-book.request.yaml`

```yaml
$kind: http-request
description: >-
  OLD Salesforce Scheduler book: POST /connect/scheduling/service-appointments, tomorrow 11:00-11:30 UTC,
  with TWO assignedResources — resourceA (isRequiredResource=true, isPrimaryResource=true, free) and
  resourceB (isRequiredResource=false, busy at 11:00). Per the object reference, a non-required resource
  is "considered available for other appointments" — so the old engine should NOT availability-check B →
  the SA books (HTTP 2xx, serviceAppointmentId returned). Captures oldWriteHelperHttp + oldWriteHelperSaId.
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
      pm.environment.set("oldWriteHelperHttp", String(pm.response.code));
      pm.environment.set("oldWriteHelperSaId", data.serviceAppointmentId || (data.serviceAppointment && data.serviceAppointment.id) || "");
      console.log("OLD book helper http=" + pm.response.code + " body=" + JSON.stringify(data).slice(0,300));
    language: text/javascript
order: 1000
```

- [ ] **Step 2: Create the required-control book act** `…/service-appointments-double-book-required-control/10-book.request.yaml` — byte-identical to Step 1 EXCEPT resourceB's `isRequiredResource` flips to `true`, and the capture keys are `oldWriteRequiredControlHttp` / `oldWriteRequiredControlSaId`. The description states: "Control — resourceB flipped to isRequiredResource=true; a required busy resource IS availability-checked → the book is REFUSED (non-2xx / no serviceAppointmentId), proving the helper Success above is the non-required flag's effect."

```yaml
$kind: http-request
description: >-
  Control: OLD book with resourceB isRequiredResource=true (still busy at 11:00). A REQUIRED busy resource
  IS availability-checked → the book is refused (non-2xx, no serviceAppointmentId). Isolates the helper
  Success to the non-required flag. Captures oldWriteRequiredControlHttp + oldWriteRequiredControlSaId.
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
      pm.environment.set("oldWriteRequiredControlHttp", String(pm.response.code));
      pm.environment.set("oldWriteRequiredControlSaId", data.serviceAppointmentId || (data.serviceAppointment && data.serviceAppointment.id) || "");
      console.log("OLD book required-control http=" + pm.response.code + " body=" + JSON.stringify(data).slice(0,300));
    language: text/javascript
order: 1000
```

- [ ] **Step 3: Create both definitions** (`…-non-required/.resources/definition.yaml` and `…-required-control/.resources/definition.yaml`), each:

```yaml
$kind: collection
description: |-
  OLD Scheduler book of the double-book fixture (see the request file). Runs under {{adminToken}}.
auth:
  - id: 7e1f0c00-1005-4aaa-9bbb-000000000030
    type: bearer
    name: bearer auth
    credentials:
      token: "{{adminToken}}"
```
(Use id `…031` for the control folder.)

- [ ] **Step 4: Add both Kicks to `SchedulerParityConfig`**

```java
  static final Kick OLD_BOOK_NON_REQUIRED_CONFIG =
      oldKickFor(V3_SCHEDULER_PATH + "booking/service-appointments-double-book-non-required");
  static final Kick OLD_BOOK_REQUIRED_CONTROL_CONFIG =
      oldKickFor(V3_SCHEDULER_PATH + "booking/service-appointments-double-book-required-control");
```

- [ ] **Step 5: Add a write-probe test method** to `SchedulerVsUnifiedParityE2ETest` (fresh revUp per arm → no ServiceResource collision)

```java
  @Test
  void oldWriteDoubleBookDecisionE2E() {
    SchedulerParityConfig.assumeBothOrgCreds();
    final var helperRundown =
        ReVoman.revUp(
            SchedulerParityConfig.OLD_AUTH_CONFIG,
            SchedulerParityConfig.OLD_DOUBLE_BOOK_FIXTURE_CONFIG,
            SchedulerParityConfig.OLD_BOOK_NON_REQUIRED_CONFIG);
    final var helperEnv = CollectionsKt.last(helperRundown).mutableEnv;
    // Non-required busy helper books (2xx + SA id).
    assertThat(helperEnv.getAsString("oldWriteHelperSaId")).isNotEmpty();
    final var controlRundown =
        ReVoman.revUp(
            SchedulerParityConfig.OLD_AUTH_CONFIG,
            SchedulerParityConfig.OLD_DOUBLE_BOOK_FIXTURE_CONFIG,
            SchedulerParityConfig.OLD_BOOK_REQUIRED_CONTROL_CONFIG);
    final var controlEnv = CollectionsKt.last(controlRundown).mutableEnv;
    // Required busy control is refused (no SA id).
    assertThat(controlEnv.getAsString("oldWriteRequiredControlSaId")).isEmpty();
  }
```

- [ ] **Step 6: Compile + run**

Run: `cd ~/code-clones/work/revoman-root && gradle :compileIntegrationTestJava && gradle :integrationTest --tests '*SchedulerVsUnifiedParityE2ETest.oldWriteDoubleBookDecisionE2E'`
Expected: PASS (helper books, control refused). If the control ALSO books (SA id non-empty), old Scheduler does NOT availability-check even required multi-resources on this path — record it; that is itself a divergence-vs-Unified finding. If the book endpoint needs `MultiResourceScheduling` enabled, confirm the org pref (spec §7 open item 3) — a `FIELD_INTEGRITY`/`INVALID_FIELD` on `isPrimaryResource` means the pref is off.

- [ ] **Step 7: Commit**

```bash
cd ~/code-clones/work/revoman-root
git add src/integrationTest
git commit -m "test(scheduler-parity): old-side service-appointments book + control"
```

---

### Task 5: The differential parity test — diff old vs Unified, normalized verdicts

Combine both orgs into one method: run the Unified double-book (reusing WFS Kicks), run the old-side read+write, normalize each side to `{readIncluded, writeOutcome}`, assert equal (or characterize divergence). Delete the temporary probe methods. Register in the ftest inventory if run via ftest-console.

**Files:**
- Modify: `SchedulerVsUnifiedParityE2ETest.java` (add the slice method; remove the 4 probe methods from Tasks 1-4, keeping `schedulerOrgAuthBindsE2E` optional as a smoke test)
- Modify: `SchedulerParityConfig.java` (add a `Verdict` helper + normalizers)
- Modify (if ftest-console is used): the module `ftest-inventory.xml`

**Interfaces:**
- Consumes: all old-side Kicks (Tasks 1-4) + `ReVomanConfigForWfs.{AUTH_CONFIG, AVAILABILITY_OP_HOURS_POLICY_CONFIG, DOUBLE_BOOK_FIXTURE_CONFIG, DOUBLE_BOOK_NON_REQUIRED_SCHEDULE_CONFIG, DOUBLE_BOOK_REQUIRED_CONFLICT_SCHEDULE_CONFIG}`.
- Produces: `testHelperDoubleBookParity_1_5_E2E` (the slice gate).

- [ ] **Step 1: Add verdict normalizers to `SchedulerParityConfig`**

```java
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
```

- [ ] **Step 2: Write the failing slice test** in `SchedulerVsUnifiedParityE2ETest`

```java
  /**
   * Decision 1.5 parity — a busy NON-required helper. OLD Salesforce Scheduler vs Unified OnSite
   * must agree on both axes: the busy helper BOOKS (not availability-checked) while a
   * required busy control is REFUSED; and the read offers the fixture's slots only when the busy
   * resource is not a hard required constraint. Old side: public Scheduler REST
   * (/scheduling/getAppointmentSlots + /connect/scheduling/service-appointments). Unified side: the
   * existing WFS double-book Connect acts. Divergence is a finding,
   * asserted faithfully rather than forced.
   */
  @Test
  void testHelperDoubleBookParity_1_5_E2E() {
    SchedulerParityConfig.assumeBothOrgCreds();

    // --- OLD side: read decision + write (helper) + write (required control), fresh revUps ---
    final var oldReadEnv =
        CollectionsKt.last(
                ReVoman.revUp(
                    SchedulerParityConfig.OLD_AUTH_CONFIG,
                    SchedulerParityConfig.OLD_DOUBLE_BOOK_FIXTURE_CONFIG,
                    SchedulerParityConfig.OLD_GET_SLOTS_DOUBLE_BOOK_CONFIG))
            .mutableEnv;
    final var oldReadDecision =
        SchedulerParityConfig.oldReadDecision(oldReadEnv.getAsString("oldReadWithBSlotCount"));

    final var oldHelperEnv =
        CollectionsKt.last(
                ReVoman.revUp(
                    SchedulerParityConfig.OLD_AUTH_CONFIG,
                    SchedulerParityConfig.OLD_DOUBLE_BOOK_FIXTURE_CONFIG,
                    SchedulerParityConfig.OLD_BOOK_NON_REQUIRED_CONFIG))
            .mutableEnv;
    final var oldHelperOutcome =
        SchedulerParityConfig.oldWriteOutcome(
            oldHelperEnv.getAsString("oldWriteHelperSaId"),
            oldHelperEnv.getAsString("oldWriteHelperHttp"));

    // --- Unified side: reuse the proven WFS double-book acts, one revUp ---
    final var unifiedEnv =
        CollectionsKt.last(
                ReVoman.revUp(
                    (r, ignore) -> assertThat(r.firstUnIgnoredUnsuccessfulStepReport()).isNull(),
                    ReVomanConfigForWfs.AUTH_CONFIG,
                    ReVomanConfigForWfs.AVAILABILITY_OP_HOURS_POLICY_CONFIG,
                    ReVomanConfigForWfs.DOUBLE_BOOK_FIXTURE_CONFIG,
                    ReVomanConfigForWfs.DOUBLE_BOOK_NON_REQUIRED_SCHEDULE_CONFIG,
                    ReVomanConfigForWfs.DOUBLE_BOOK_REQUIRED_CONFLICT_SCHEDULE_CONFIG))
            .mutableEnv;
    final var unifiedHelperOutcome =
        SchedulerParityConfig.unifiedWriteOutcome(
            unifiedEnv.getAsString("doubleBookNonRequiredSchedulingStatus"), "201");

    // --- PARITY: both products book the non-required busy helper (helper is NOT availability-checked) ---
    assertThat(oldHelperOutcome).isEqualTo(SchedulerParityConfig.WriteOutcome.BOOKED);
    assertThat(unifiedHelperOutcome).isEqualTo(SchedulerParityConfig.WriteOutcome.BOOKED);
    assertThat(oldHelperOutcome).isEqualTo(unifiedHelperOutcome);
    // Old read excludes the busy resource when it is a hard required id (the old-side availability proof).
    assertThat(oldReadDecision).isEqualTo(SchedulerParityConfig.ReadDecision.EXCLUDED);
    // Unified control (busy resource as required) is refused → the Unified availability proof.
    assertThat(unifiedEnv.getAsString("doubleBookRequiredControlSchedulingStatus"))
        .isNotEqualTo("Success");
  }
```

- [ ] **Step 3: Run to verify it fails first for the RIGHT reason**

Run: `cd ~/code-clones/work/revoman-root && gradle :compileIntegrationTestJava && gradle :integrationTest --tests '*SchedulerVsUnifiedParityE2ETest.testHelperDoubleBookParity_1_5_E2E'`
Expected: initially may FAIL on a live-observed value differing from the hypothesis (e.g. old read union not intersect, or old control books). Per the control-first rule, inspect the captured env values in the run log BEFORE adjusting. If the observed old behavior is a genuine divergence from Unified, re-characterize the assertion to the observed verdicts and document the divergence in the method javadoc + the report — that is the deliverable, not a forced green.

- [ ] **Step 4: Make it pass (agreement) or characterize (divergence)**

If both sides BOOK the helper and the controls/read behave as hypothesized → the assertions above pass; parity confirmed. If they diverge, replace the equality assertion with the observed pair and add a `<p>DIVERGENCE:` javadoc paragraph naming which side does what and the captured verdicts.

- [ ] **Step 5: Remove the temporary probe methods**

Delete `oldDoubleBookFixtureInsertsE2E`, `oldReadDoubleBookDecisionE2E`, `oldWriteDoubleBookDecisionE2E` from `SchedulerVsUnifiedParityE2ETest` (their coverage is now inside the slice). Keep `schedulerOrgAuthBindsE2E` as a lightweight smoke test.

- [ ] **Step 6: Re-run the full class green**

Run: `cd ~/code-clones/work/revoman-root && gradle :integrationTest --tests '*SchedulerVsUnifiedParityE2ETest'`
Expected: both methods PASS (or the slice faithfully characterizes a divergence, still green).

- [ ] **Step 7: Register in ftest-inventory (only if run via ftest-console)**

If this suite is discovered via a module `ftest-inventory.xml` (as the WFS suite is), add `SchedulerVsUnifiedParityE2ETest` there. If it runs purely as a gradle `integrationTest` JUnit class (like its WFS siblings), skip — no inventory entry needed.

- [ ] **Step 8: Commit**

```bash
cd ~/code-clones/work/revoman-root
git add src/integrationTest
git commit -m "test(scheduler-parity): 1.5 double-book helper parity slice (old vs Unified)"
```

---

## Self-Review

**Spec coverage:**
- Two-org differential harness → Task 1 (`SchedulerParityConfig`, second creds overlay, `assumeBothOrgCreds`). ✓
- Old side = public Scheduler REST (reads `/scheduling/getAppointmentSlots`, write `/connect/scheduling/service-appointments`) → Tasks 3, 4. ✓
- Unified side = the live Unified org, reuse WFS Kicks → Task 5. ✓
- Verdict contract (INCLUDED/EXCLUDED, BOOKED/REFUSED/CRASHED), normalized-not-raw (R3) → Task 5 Step 1. ✓
- Control per scenario (non-vacuous) → Tasks 3 (A-only), 4 (required control), 5. ✓
- Vertical slice = 1.5 → Tasks 2-5. ✓
- Divergence handled as first-class characterization → Task 5 Steps 3-4. ✓
- Creds-absent skip, license/collision hygiene (fresh revUp per write arm) → Tasks 1, 4, 5. ✓
- Spec §7 open items surfaced as run-time decision points → Task 2 Step 2 (SchedulingMethod/second user), Task 3 Step 6 (AND-vs-union), Task 4 Step 6 (MultiResourceScheduling pref). ✓

**Placeholder scan:** No TBD/TODO. Each code step carries complete file content or an exact clone+edit recipe (Task 2 clones an in-repo 300-line graph — the edits are enumerated exactly rather than re-pasting, since the engineer copies a real file). Endpoint bodies grounded in the authoritative dev-guide shapes from research.

**Type consistency:** `ReadDecision`/`WriteOutcome` enums + `oldReadDecision`/`oldWriteOutcome`/`unifiedWriteOutcome` signatures defined in Task 5 Step 1 match their uses in Step 2. Env keys are consistent across producer (yaml `pm.environment.set`) and consumer (Java `getAsString`): `oldReadWithBSlotCount`, `oldReadAOnlySlotCount`, `oldWriteHelperSaId`, `oldWriteHelperHttp`, `oldWriteRequiredControlSaId`, `sched{Territory,WorkType,Account,ResourceA,ResourceB}Id`, and the reused WFS `doubleBookNonRequiredSchedulingStatus`/`doubleBookRequiredControlSchedulingStatus`. Kick field names match `SchedulerParityConfig` declarations.

**Known residual unknowns (resolve live, all flagged in-task):** (a) old getAppointmentSlots AND-vs-union semantics; (b) SchedulingMethod picklist validity + second-user availability on the scheduler org; (c) MultiResourceScheduling org pref on the scheduler org; (d) exact old response field names (`timeSlots` vs `slots`, `serviceAppointmentId` vs nested) — the scripts defensively read both.
