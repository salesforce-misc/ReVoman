# Salesforce Scheduler ↔ Unified (264) — `1.*` Helper-Fitness Parity: Design

**Date:** 2026-07-03
**Author:** Gopal S Akshintala (with Claude, brainstorming session)
**Branch:** `wfs/decision-1-9-revoman-tests` (revoman-root)
**Status:** DESIGN — approved through Sections 1–3; vertical slice (1.5) to be built first.

**Relates to:**
- `docs/superpowers/2026-06-30-wfs-parity-test-report.md` — the 262↔264 Unified read/write parity report; source of the `1.*` scenario catalogue.
- `~/work/docs/scheduler-to-unified-parity-guide.md` — the plain-language Scheduler→Unified mapping guide (risks R1–R8, §6 recipe).
- `src/integrationTest/java/com/salesforce/revoman/integration/core/wfs/WfsWritePathParityE2ETest.java` — existing single-org WFS suite whose fixtures/personas the 264 side reuses.

---

## 0. Problem & scope

The 262↔264 report characterized the `1.*` "helper-fitness" family **on the Unified write path only** (single org, one shared engine). The new goal: compare the **older Salesforce Scheduler** (a.k.a. Lightning Scheduler) product against **264 Unified OnSite** for the same `1.*` scenarios — the first cut of a broader Scheduler↔Unified parity effort.

`1.*` scenarios (from the report):
- **1a** Excluded — helper on the account's block-list.
- **1b** Territory — helper's territory doesn't cover the window.
- **1c** Skills — helper lacks a required skill.
- **1d** Working-locations — helper is a Secondary territory member under a primary-only policy.
- **1.4** Required-resources — account demands a resource, met only by a helper.
- **1.5** Availability / double-book — helper is busy at the window.

The single question underneath all six: **does the scheduling engine fitness-check / availability-check a NON-required "helper" resource?** The lever is `AssignedResource.isRequiredResource` (± `isPrimaryResource`).

**In scope (this design):** the two-org differential harness + the `1.5` vertical slice end-to-end. **Out of scope (next steps):** the other five `1.*` scenarios (fan out from the slice template) and the read-only guide risks R1/R6/R7.

---

## 1. Research findings that shaped this design (do NOT re-litigate)

Three code-grounded + docs-grounded research passes established:

1. **Old Salesforce Scheduler HAS the non-required "helper" concept.** Authoritative public object reference on `AssignedResource.IsRequiredResource`: *"If this field is set to false, Salesforce Scheduler considers the resource as available for other appointments."* Plus `IsPrimaryResource` (v47+), "primary must be required," max 5 required (1 primary + 4). Confirmed internally (CSGPAK "required or optional attendee"). *(An earlier read-input-only analysis wrongly concluded the concept was absent; the helper model lives on the write/persist side — AssignedResource rows.)*

2. **Old Salesforce Scheduler HAS a booking path.** `POST /connect/scheduling/service-appointments` (Connect API) with an `assignedResources[]` array carrying `isRequiredResource`/`isPrimaryResource`; also Apex `ConnectApi.createServiceAppointment` and raw DML. Canonical flow: work-type-groups → territories → `getAppointmentCandidates` → `getAppointmentSlots` → POST service-appointments.

3. **The OnSite engines are SEPARATE re-implementations.** Old `scheduling-impl.SchedulingServiceImpl` (serves both the `appointmentbooking` connect API and the public Scheduler `/scheduling/*` REST) vs Unified `unified-scheduling-impl.InBusinessAppointmentSlotCalculator` — near-twin algorithms, **zero shared engine code**, independent feature gates. So parity is **NOT** guaranteed by construction and can drift. *(This inverts the report's "read==write because one shared engine" thesis — here divergence is genuinely possible, which is exactly what makes the test valuable.)*

4. **Old save-time fitness is minimal.** `LightningSchedulerAssignedResourceValidator` (fieldservice-impl) enforces only *primary⇒required* and *one-primary*; **no** skills/territory/availability check at write. Fitness is read-time only. So structurally the old side, like Unified 262, books a non-required helper regardless of fitness.

**Open item to pin during authoring (not blocking):** old `getAppointmentSlots` intersects (AND) all named resources (`containsAll` gate, `SchedulingServiceImpl:517-522`), whereas an internal IAM spike describes multi-resource "any-available" (union). Likely different operations (slots-for-a-crew vs candidates). Resolve when building the read act; pick the operation whose semantics let the helper-vs-required distinction surface.

---

## 2. Architecture

**Thesis.** Because the two OnSite engines are independent re-implementations, `1.*` parity is a real, un-guaranteed surface. Each test diffs the **old-Scheduler** decision against the **264-Unified** decision on BOTH the read (include/exclude) and write (book/refuse) axes, per scenario.

**Parity hypothesis (H0).** Both products treat a non-required helper as non-reserved and non-fitness-checked (helper books regardless of the rule; a required resource is checked). Each test asserts old-decision == 264-decision; a mismatch is the finding.

**Sides:**
- **Old side = scheduler org** (`orgfarm-0c6bcb96c0…crm.dev:6101`), driven via **public Scheduler REST**.
- **264 side = 262 org** (`orgfarm-4dbef90d6c…crm.dev:6101`) as the Unified proxy — endpoints identical 262↔264, reusing the proven `WfsWritePathParityE2ETest` harness.

**Both orgs are local-bound** (verified: both 200 from the same workspace frontdoor host; localhost:6101 up), so ReVoman external-org mode (in-JVM SDB bind) works for both — same harness family as the existing WFS suite. (psql client not currently on PATH — a resolve-at-impl detail, not a blocker.)

```
                 ┌─────────────────── ONE scenario ───────────────────┐
 scheduler org ─▶│ OLD Scheduler REST          │ 262 org ─▶ Unified    │
 (old side)      │  read:  /scheduling/         │  read: get-appointment-slots/-candidates
                 │    getAppointmentSlots        │  write:/unified-scheduling/actions/schedule
                 │    getAppointmentCandidates   │                       │
                 │  write:/connect/scheduling/   │                       │
                 │    service-appointments       │                       │
                 └──────┬───────────────────────┴──────────┬────────────┘
                        ▼                                    ▼
              old {readDecision, writeOutcome}    264 {readDecision, writeOutcome}
                        └──────── assert equal / record divergence ───────┘
```

---

## 3. Components

New package `com.salesforce.revoman.integration.core.scheduler` (sibling to `…/core/wfs`). Four units:

1. **`SchedulerParityConfig`** — the ONLY unit aware of two orgs. Exposes `OLD_SCHEDULER_AUTH` + `UNIFIED_AUTH` credential/baseURL sets, each resolved from `~/.revoman/config.yaml` via the existing `ExternalOrgConfig` overlay; `assumeExternalOrgCreds()` JUnit-skips when creds absent. Mirrors `ReVomanConfigForWfs`.

2. **Old-Scheduler fixtures + acts** (Postman V3 dirs) — the genuinely new content. Per scenario: policy fixture, resource/territory/skill fixture, read act (`getAppointmentSlots`/`getAppointmentCandidates`), write act (`service-appointments`). Modeled on the existing `wfs/booking/*` + `wfs/policies/*` layout.

3. **264-side fixtures + acts** — REUSE existing `WfsWritePathParityE2ETest` fixtures unchanged (for the slice: `DOUBLE_BOOK_*` configs). No new 264 content for the slice.

4. **`SchedulerVsUnifiedParityE2ETest`** — the differential test class. Per scenario: `revUp` old-side + `revUp` 264-side, capture normalized verdicts, assert equal (or characterize divergence).

**Verdict contract (uniform across scenarios):**
- **readDecision** = is resourceB present in returned slots/candidates? `INCLUDED` / `EXCLUDED` (absence-among-a-live-set, stronger than empty).
- **writeOutcome** = `BOOKED` / `REFUSED` / `CRASHED` (+ errorCode captured to env for the report, but excluded from the equality assertion).
- Every scenario carries a **control** (resourceB flipped to required) proving the fixture is live — non-vacuous guardrail.

---

## 4. Vertical slice — `1.5` (Availability / double-book)

**Why 1.5 first:** cleanly constructible on the old read path (per-resource unavailability applies to every pooled resource — research-confirmed) AND the 264 half already has a proven fixture (`DOUBLE_BOOK_*` in `testNonRequiredHelperDoubleBooksE2E`). Only the old-Scheduler half + the two-org diff mechanics are new. Its A/B (flip only `isRequiredResource` on a busy resourceB) is the report's clearest helper-vs-required demonstration — highest-signal slice.

**Method:** `testHelperDoubleBookParity_1_5_E2E`.

```
OLD-SCHEDULER revUp (scheduler org)              264 revUp (262 org)
1. OLD_SCHEDULER_AUTH (SOAP v64 login)           1. UNIFIED_AUTH (AUTH_CONFIG)
2. seed policy + territory/OH/shift              2. AVAILABILITY_OP_HOURS_POLICY_CONFIG
3. resourceA required+primary (free);            3. DOUBLE_BOOK_FIXTURE_CONFIG (reuse)
   resourceB busy at window
4. READ getAppointmentSlots[reqIds=A,B]          4. READ get-appointment-slots → unifiedReadIncluded
   → oldReadIncluded (B present?)
5. WRITE service-appointments, B non-required    5. WRITE schedule B non-req (DOUBLE_BOOK_NON_REQUIRED)
   → oldWriteStatus                                 → unifiedWriteStatus
6. control: B required                           6. control: B required (DOUBLE_BOOK_REQUIRED_CONFLICT)
```

**Assertion:** `oldReadIncluded == unifiedReadIncluded` AND `oldWriteStatus ≈ unifiedWriteStatus` (normalized verdicts). Expected under H0: both {helper busy → BOOKED + INCLUDED; required busy → REFUSED + EXCLUDED}. Agreement → parity confirmed for 1.5; divergence → the finding, characterized faithfully.

**Slice gate:** green `testHelperDoubleBookParity_1_5_E2E` = the both-orgs harness is proven; the remaining five fan out from this exact template.

---

## 5. Data flow, divergence handling, error handling

**Verdict normalization (load-bearing).** Old REST reports `isError`/`errorMessage` + HTTP; Unified reports `schedulingStatus` + per-item `errors[]`. The equality assertion compares NORMALIZED verdicts (`BOOKED/REFUSED/CRASHED`, `INCLUDED/EXCLUDED`), never raw codes (guide R3). Raw codes captured to env for the report only.

**Divergence — three first-class outcomes:**
1. **Agree** → parity confirmed; assert equality.
2. **Diverge, reproducible** → the finding. Assert observed verdicts on each side faithfully (characterization), document in javadoc + report.
3. **Old side un-constructible** for a scenario (e.g. candidates-path-only account preference, primary-only skill matching) → mark `PARTIAL`, assert what IS constructible, log the gap explicitly. No silent skips.

**Error / edge handling:**
- Creds absent → `assumeExternalOrgCreds()` JUnit-skips (both orgs).
- License churn (timestamped users → `LICENSE_LIMIT_EXCEEDED`) → fresh-per-scenario personas + documented deactivate-stale-`@revoman.org` recovery; now applies to BOTH orgs.
- 262-as-264-proxy caveat → stamped in every javadoc: verdicts are 262-observed; a true-264 org may differ on the locked-in crashes (1.4/3). Slice (1.5) is crash-free → unaffected.
- Old-side crash (if old engine NPEs) → captured as `CRASHED`, itself a parity data point.

## 6. Testing

- **Slice gate:** `testHelperDoubleBookParity_1_5_E2E` green in external-org mode (fast loop), confirmed once with both orgs live.
- **Control-first discipline:** verify each control RED for the intended reason before trusting the positive GREEN (vacuous-proof lesson: the required-busy control MUST actually be refused).
- **Registration:** new class → `ftest-inventory.xml` if run via ftest-console; ReVoman JUnit otherwise.
- **Scope honesty:** only the slice ships first; the other five `1.*` + read-only guide risks (R1/R6/R7) are explicit next steps, not silently dropped.

---

## 7. Open questions / next steps

1. Pin the old read operation (slots-AND vs candidates-any-available) so the helper-vs-required distinction surfaces cleanly (§1 open item).
2. Confirm the public Scheduler REST version on the scheduler org (v66 in the appointmentbooking spec `info.version`; verify the live org accepts it; SOAP login uses v64 per prior WFS work).
3. Confirm `MultiResourceScheduling` org pref is enabled on the scheduler org (gates `IsPrimaryResource`; required for the multi-resource book) and `WorkforceSchdMulResSchdPref` on the 262 org.
4. After the slice proves the harness: fan out 1b, 1d (clean), then 1a, 1c, 1.4 (path-dependent partials).
