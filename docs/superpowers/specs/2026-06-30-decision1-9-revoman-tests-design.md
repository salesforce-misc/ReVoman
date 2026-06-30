# Design — ReVoman tests for WFS read↔write parity decisions 1, 1.4, 1.5, 3, 8, 9

**Date:** 2026-06-30
**Status:** IMPLEMENTED on `wfs/decision-1-9-revoman-tests` — all 6 decisions; final review READY WITH MINORS.
See `~/work/impl-decisions/2026-06-30-decision1-9-revoman-tests-persona-auth.md` for divergences, the
deferred persona-retrofit decision, and open questions.
**Source decisions:** `~/work/impl-decisions/2026-06-21-PRODUCT-presentation-read-write-parity.md` (decisions 1, 1.4, 1.5, 3, 8, 9).
**Target module:** `revoman-root` integrationTest source set — `src/integrationTest/java/com/salesforce/revoman/integration/core/wfs/`.

## Goal

Author ReVoman E2E tests that **characterize the live 262 behavior** of six Workforce Scheduling (WFS)
read↔write parity decisions on the provisioned workspace org, each documenting the **264 contrast** in
javadoc (same pattern as the existing `WfsHelperFitnessE2ETest`). The two existing WFS tests are rewritten
fresh under one uniform structure and superseded.

These are off-core ReVoman INTEGRATION tests (not ReVomanFTests): scripted HTTP clients that hit a remote
WFS workspace org and stitch loosely-coupled V3 Postman collections via `ReVoman.revUp(...)`, running as
plain JUnit tests in the integrationTest source set.

## Locked brainstorm decisions (do not re-litigate)

- **Q1 Target release:** assert **262** (the live workspace org we run against); each test documents the
  **264 contrast** in javadoc (262 = Success / current behavior; 264 = flip to ScheduleError + rule code,
  or the proposed read-path semantics).
- **Q2 Scope:** **rewrite all 6 fresh** under one uniform structure; supersede `WfsHelperFitnessE2ETest` and
  `WfsDoubleBookHelperE2ETest` (both deleted). Bake in the two harness fixes (window ≥ duration;
  per-dimension fresh users).
- **Q3 Structure:** **group by path type** — two classes (write-path 1/1.4/1.5/3; read-path 8/9), one
  `@Test` per decision, shared `ReVomanConfigForWfs`.
- **Q4 Decision 8:** **boundary pair** — `limit=0 → empty`, `limit=N(positive) → resources returned`.
  Skip the `null → cap-10` default branch (order/seed-sensitive).
- **Q5 Decision 9:** **full cross-persona repro** — admin-owned Shift rows + manager persona without sharing
  → empty availability / no slots, no error; control proves it's the sharing gate.
- **Q6 Decision 3:** **characterize crash + L142 control** — Act A omits `isRequiredResource` (assert actual
  262 failure); Act B = single `isRequiredResource=true`, no `isPrimaryResource`, FRESH resources → valid
  Schedule.
- **Q7 Run mode:** **external-org only** (revoman-root is not the core repo → no FTestOrg mode). Iterate
  against the live workspace org via `~/.revoman/config.yaml`.
- **Approach A (isolation):** **each independent scenario is its own `revUp` starting with `AUTH_CONFIG`** →
  fresh `mutableEnv` + freshly-minted (`{{$timestamp}}`) users per revUp → the
  ServiceResource `(RelatedRecordId, ResourceType)` uniqueness collision is structurally impossible.

## Architecture & file layout

```
src/integrationTest/java/com/salesforce/revoman/integration/core/wfs/
├── ReVomanConfigForWfs.java          # shared Kick factory (extended; kickFor(...) kept verbatim)
├── WfsWritePathParityE2ETest.java    # Decisions 1, 1.4, 1.5, 3  (schedule/booking write acts)
└── WfsReadPathParityE2ETest.java     # Decisions 8, 9            (GetSlots/GetAvailableResources read acts)
```

V3 collections live under `src/integrationTest/resources/pm-templates/v3/core/wfs/{auth,policies,fixtures,booking}`.
ReVoman auto-detects V3 from the `templatePath` directory (each folder carries `.resources/definition.yaml`
+ `*.request.yaml`). Shared env: `ws.environment.yaml` (creds blanked in-repo).

### Uniform per-decision structure

- Each decision = one `@Test`. Each issues one or more `ReVoman.revUp(...)` calls.
- **Each independent scenario is its own `revUp` starting with `AUTH_CONFIG`** (Approach A). Decision 1's 4
  fitness dims → 4 separate `revUp` calls in one `@Test`. A/B-control decisions → either two revUps or one
  revUp with both acts when they share a fixture and no SR collision arises.
- Verdict asserted two ways (as today): collection step `test` scripts stash `*SchedulingStatus` (or a
  resource/slot count) into the env; the JUnit assertion checks `rundown.firstUnIgnoredUnsuccessfulStepReport()
  == null` plus Truth assertions on the env.
- Every `@Test` javadoc states the **262 verdict asserted** and the **264 contrast**.

## Write-path class — `WfsWritePathParityE2ETest`

Four `@Test` methods, all asserting live 262, documenting 264 contrast.

### `testNonRequiredHelperFitnessE2E` (Decision 1)

Probes whether a NON-required "helper" resource is fitness-checked on the Schedule write path across four
dimensions. **4 separate `revUp` calls** (Approach A), each `AUTH → policy → fixture → schedule`:

| Dim | Policy | Fixture | Schedule act | 262 verdict |
|---|---|---|---|---|
| EXCLUDED | excluded-resources-availability | excluded-non-required | schedule-excluded-non-required-violating | Success |
| TERRITORY | territory-membership-partial | territory-non-required | schedule-territory-membership-non-required-violating | Success |
| SKILLS | match-skills-non-required | skills-non-required (+ skills-non-required-skill) | schedule-skills-non-required-violating | Success |
| WORKING-LOCATIONS | availability-op-hours | working-locations-secondary-non-required | schedule-working-locations-secondary-non-required-violating | Success |

Each revUp asserts its own `<dim>NonReqSchedulingStatus == "Success"`. **264 contrast:** each → `ScheduleError`
+ rule code (`ExcludedResources` / `MatchTerritory` / `MatchSkills` / `WorkingLocations`).
**Harness fix:** window ≥ WorkType EstimatedDuration in all 4 schedule acts (already fixed in excluded +
working-locations; verify territory + skills).

### `testNonRequiredHelperCannotSatisfyRequiredDemandE2E` (Decision 1.4)

A/B pair. Policy `required-resources-availability`, fixture `required-non-required`:
- **A** `schedule-required-non-required-satisfier-violating`: a required-resource demand on the account, only
  a NON-required helper present → **characterize-live** (helper cannot satisfy the demand).
- **B** control `schedule-required-satisfier-bookable`: a genuine required satisfier present → Success.

**264 contrast:** documented in javadoc per the doc's Decision 1.4 row (helper can't satisfy a required-resource demand).

### `testNonRequiredHelperDoubleBooksE2E` (Decision 1.5)

A/B pair. Policy `availability-op-hours`, fixture `double-book-non-required`:
- **A** `schedule-double-book-non-required-violating`: a busy NON-required helper → **Success** (a
  non-required helper is never availability-checked, so it may double-book).
- **B** control `schedule-double-book-required-conflict`: flip `isRequiredResource=true` on the same
  resourceB → **not-Success** (a required resource IS availability-checked → not-available).

**264 contrast:** the doc flags helper double-book as a gap InField blocks; 264 decision documented in javadoc.

### `testMissingRequiredFlagE2E` (Decision 3)

A/B pair (new collections):
- **A** schedule act **omitting `isRequiredResource` entirely** → assert the live 262 failure
  (**characterize-live**: capture exact errorCode/message on the first run, then pin it). **264 contrast:**
  missing-treated-as-not-required, clean.
- **B** L142 control: a SINGLE assigned resource with `isRequiredResource=true` and **no `isPrimaryResource`
  field**, on FRESH resources → valid Schedule (Success). Re-verifies the doc's open note (L142) that a
  single required, no-primary booking MUST work; `isPrimary` is multi-resource-only plumbing.

## Read-path class — `WfsReadPathParityE2ETest`

Two `@Test` methods. Read-path enforce contract: a rule violation / cap → **EMPTY slots/resources, HTTP 200,
NO 400/exception** (per the existing get-slots act comment).

### `testResourceLimitApptDistributionCapE2E` (Decision 8)

Boundary pair on a default OnSite **load-balancing** policy. One `revUp`:
`AUTH → load-balancing policy → fixture (≥2 eligible resources) → 2 read acts`:
- **Act 0** `get-resources-limit-zero`: `resourceLimitApptDistribution: 0` (under the `AuxiliaryDetails`
  wrapper) → assert **empty** resource list (the `Stream.limit(0)` behavior). Stash count → env, assert `0`.
- **Act N** `get-resources-limit-positive`: explicit limit **above the seeded count** → assert resources
  returned (count > 0). Proves 0-is-not-unlimited with a control.

Read API surface: **GetAvailableResources** (the "By Required Resource" load-balancing list — where the cap
lands). **264 contrast:** if product picks A (no cap), `0` → all eligible resources (surprise empty list
removed). Cite `AppointmentDistributionService.findLeastUtilizedResources` (`limit = resourceLimit != null ?
resourceLimit : DEFAULT_RESOURCE_LIMIT(10)`; `0 → Stream.limit(0) → empty`) and the W-23049589 FTest
workaround.

### `testShiftSharingModeSplitE2E` (Decision 9)

Full cross-persona repro. **Two `revUp` calls** (each fresh AUTH) or one revUp executing the read act under
the manager bearer token:
- **Setup (admin-owned):** admin creates the `availability-overlapping-shifts` fixture → Shift rows owned by
  admin, NOT shared to the manager persona.
- **Probe (manager-mode read):** GetSlots/availability read executed **as the manager persona**
  (`{{managerToken}}`, no sharing on admin Shift rows) → assert **empty availability / no slots, HTTP 200, no
  error** (the `SystemMode.NONE` user-mode shift read sees zero shifts).
- **Control:** the same read **as admin** (or a persona with sharing) → slots returned — proving the empty
  result is the sharing gate, not an empty fixture.

**264 contrast:** decision A documents the gate as contract; B aligns the modes so the manager would see
slots. Cite `UnavailabilityService.loadShiftIntervalsBulk` / `loadFullShiftsBulk` (`SystemMode.NONE`) vs
`loadResourceUnavailabilitySourcesBulk` (`SystemMode.SFDC_FULL`), commit `b02beb2af29b` / W-22502139.

## Shared config — `ReVomanConfigForWfs`

Keep `kickFor(...)` verbatim (composite/graph unmarshalling, `IDAdapter`, node-modules path,
`haltOnFailureOfTypeExcept(HTTP_STATUS, afterStepContainingHeader(IGNORE_HTTP_STATUS_UNSUCCESSFUL))`,
`insecureHttp(true)`). Add new `Kick` constants; prune constants only the deleted tests referenced if
orphaned.

| Decision | New Kick constants |
|---|---|
| 1.4 | `REQUIRED_RESOURCES_POLICY_CONFIG`, `REQUIRED_NON_REQUIRED_FIXTURE_CONFIG`, `REQUIRED_NON_REQUIRED_SATISFIER_VIOLATING_SCHEDULE_CONFIG`, `REQUIRED_SATISFIER_BOOKABLE_SCHEDULE_CONFIG` |
| 3 | `MISSING_REQUIRED_FLAG_SCHEDULE_CONFIG` (new), `SINGLE_REQUIRED_NO_PRIMARY_SCHEDULE_CONFIG` (new) |
| 8 | `LOAD_BALANCING_POLICY_CONFIG`, `LOAD_BALANCING_FIXTURE_CONFIG`, `GET_RESOURCES_LIMIT_ZERO_CONFIG`, `GET_RESOURCES_LIMIT_POSITIVE_CONFIG` |
| 9 | `OVERLAPPING_SHIFTS_FIXTURE_CONFIG`, `GET_SLOTS_AS_MANAGER_CONFIG`, `GET_SLOTS_AS_ADMIN_CONTROL_CONFIG` |

## Collections to lift / author

Source root: `~/work/revoman/unified-validation-262/postman/collections`. Target:
`revoman-root/.../pm-templates/v3/core/wfs/`.

- **Exist, lift as-is:** `policies/required-resources-availability-policy`, `fixtures/required-non-required`,
  `booking/schedule-required-non-required-satisfier-violating`, `booking/schedule-required-satisfier-bookable`
  (1.4); `fixtures/availability-overlapping-shifts` (9).
- **Exist, adapt:** `booking/get-slots-availability-stm-oh` as the read-act template for 8/9.
- **Author new:** Decision 3 two schedule acts; Decision 8 load-balancing policy + fixture + two
  get-resources acts; Decision 9 manager/admin read acts.

**Auth (present):** `auth/` mints case-worker + manager personas + `login-as-sysadmin` (managerToken /
adminToken). Reused for fresh-user-per-revUp (Approach A) and the Decision-9 manager probe.

## Run / verify strategy

- **External-org only** (Q7). Create `~/.revoman/config.yaml` → workspace org (baseUrl, username, password).
- Iterate: lift/author collection → run the act standalone (postman-cli, fast verdict) → wire the `@Test` →
  run JUnit against the live org → pin the verdict.
- Tests kept `@Disabled` in-repo (like the existing two); the `@Disabled` annotation documents the WFS
  workspace-org provisioning preconditions (multi-resource pref `WorkforceSchdMulResSchdPref`,
  InBusinessScheduling, `Shift.Status` DynEnum, per-rule `ShiftUsage` param).
- **Verification gate before "done":** every assertion confirmed to actually run green against the live
  workspace org (not merely compile). Characterize-live verdicts (3, 1.4) pinned to observed 262 values, not
  guessed.

## Deletions

- `WfsHelperFitnessE2ETest.java` — folds into `WfsWritePathParityE2ETest.testNonRequiredHelperFitnessE2E`.
- `WfsDoubleBookHelperE2ETest.java` — folds into `WfsWritePathParityE2ETest.testNonRequiredHelperDoubleBooksE2E`.
- Orphaned `ReVomanConfigForWfs` constants pruned.

## Live-confirm items (resolved at write-time; NOT design blockers)

1. **Dec 3 / 1.4 failure shape** — characterize-live; capture exact errorCode/message before asserting.
2. **Dec 8 read API** — confirm GetAvailableResources vs GetAppointmentCandidates is where
   `resourceLimitApptDistribution` actually caps; confirm a load-balancing (non-required-resource) policy is
   the trigger.
3. **Dec 9 manager-token execution** — confirm the V3 mechanism for running an act under `{{managerToken}}`
   (per-act auth override in `.resources/definition.yaml` vs a manager-scoped folder).
4. **Dec 9 Shift OWD** — must be Private (not Public Read) for the sharing gap to bite; verify on the
   workspace org. If shifts are world-readable the repro shows nothing.
5. **WFS PSL** — bumped to 999; Approach A mints more users (4 pairs for Dec 1) — within budget.

## Out of scope (YAGNI)

- Decision 8 `null → cap-10` default branch (order/seed-sensitive).
- Decisions 2, 4, 4z, 5, 6, 7.
- Cleanup/teardown collections — rely on fresh timestamped users per revUp, not teardown.
