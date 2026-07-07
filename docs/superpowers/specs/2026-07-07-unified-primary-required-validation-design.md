# Unified schedule-action primary/required-resource validation — coverage tests

Date: 2026-07-07
Branch: `wfs/scheduler-vs-unified-1x-parity`

## Goal

Close two coverage gaps in the Connect Unified Scheduling `schedule` action's primary/required-resource
validation, on release 262, with live ReVoman tests. Both are Unified-internal (no Scheduler parity axis).

## Terminology (kept strictly separate)

- **`WorkforceSchdMulResSchdPref`** — an ORG PREFERENCE. When ON (our test org has it ON), it enables
  multi-resource scheduling: a request may carry more than one `assignedResource`, and the persist-layer
  primary checks in `LightningSchedulerAssignedResourceValidator` (fieldservice-impl:58-59) are active.
  When OFF, requests are effectively single-resource and that persist validator is skipped entirely.
- **"more than one assignedResource"** — a REQUEST-PAYLOAD fact (`assignedResources.size() > 1`). This is
  the actual trigger the input-validation rules key off — NOT the pref directly.

The pref is the precondition; the request count is the trigger. Never conflate them in code or sheet text.

## Verified code facts (unified-scheduling-impl, 262 — already confirmed, do not re-explore)

- `ScheduleCommonValidator.validatePrimaryResourceConstraints` (.../request/validators/schedule/ScheduleCommonValidator.java:168-182):
  - :177-178 `if (assignedResources.size() > 1 && primaryCount == 0)` → INVALID_INPUT "NoPrimary"
  - :179-180 `else if (primaryCount > 1)` → INVALID_INPUT "MultiplePrimary" (no size guard)
  - primaryCount via ResourceValidationUtils.countPrimaryResources:77-89
- Optional-primary (isPrimary=true, isRequired=false): NO input-level correlation check. Rejected only at
  PERSIST via the shared save hook LightningSchedulerAssignedResourceValidator.validateAssignedResourceOnSave
  (fieldservice-impl:82-88), label "AssignedResourceSavePrimaryAndRequired" = "Only a required service
  resource can be set as a primary service resource". COUNT-AGNOSTIC.
- Execution order: UnifiedSchedulingApiExecutionManager.process validatePayload(:64) → availability(:66) →
  persistAppointments(:77).

## Scope

IN:
- **#5 multi zero-primary** — request has >1 assignedResource, primaryCount 0 → up-front INVALID_INPUT NoPrimary.
- **#6 multi optional-primary** — request has >1 assignedResource, exactly one primary that is not required →
  passes input validation, rejected at persist (message-checked). Proves the optional-primary reject is
  request-count-agnostic (same path as the single-resource case, sheet row 11), NOT a single-vs-multi
  asymmetry as the original defect description framed it.

OUT (user decision):
- #7 pref-OFF silent hole (test org has the pref ON; would need a second org or pref-flip fixture).
- #8 validation-ordering out-of-hours proof (deferred).

## Already covered (verified, no new work)

- Multi two-primary → INVALID_INPUT MultiplePrimary: `testTwoPrimaryParity_4_E2E` + WFS SCHEDULE_TWO_PRIMARY_CONFIG.
- Single optional-primary → persist reject: `testPrimaryNotRequiredParity_5_E2E`.
- Single no-primary → books (Success): WfsWritePathParityE2ETest asserts singleRequiredNoPrimaryStatus==Success
  (SINGLE_REQUIRED_NO_PRIMARY_SCHEDULE_CONFIG). This is the genuine single-vs-multi asymmetry: the NoPrimary
  rule's `size() > 1` guard skips single-resource requests.

## Design

### Collections (V3, under pm-templates/v3/core/wfs/booking/)

- `schedule-multi-no-primary/` (drafted; fix wording per terminology) — 2 assignedResources, both
  isPrimaryResource=false + isRequiredResource=true. Captures multiNoPrimaryStatus / ...ErrorCode /
  ...ErrorMessage / ...HttpCode.
- `schedule-multi-optional-primary/` (new) — 2 assignedResources: one isPrimaryResource=true +
  isRequiredResource=false (the optional primary), one isRequiredResource=true. Captures
  multiOptionalPrimaryStatus / ...ErrorCode / ...ErrorMessage / ...HttpCode.

Both clone schedule-two-primary shape (same policy availabilityOpHoursPolicyId, fixture
requiredNonReqResource A/B, tomorrow 11:00-12:00 window, x-revoman-ledger off, ignoreHTTPStatusUnsuccessful).

### Kicks (ReVomanConfigForWfs)

`SCHEDULE_MULTI_NO_PRIMARY_CONFIG`, `SCHEDULE_MULTI_OPTIONAL_PRIMARY_CONFIG` (public if consumed cross-package).

### Test methods (WfsWritePathParityE2ETest)

- multi zero-primary: assert status != "Success", errorCode == INVALID_INPUT, http 400 (up-front NoPrimary).
- multi optional-primary: assert status != "Success" AND the error is the PERSIST-layer primary message
  ("... required service resource ... primary ..."), distinct from INVALID_INPUT/NoPrimary → proves late,
  count-agnostic reject.

Reuse AUTH_CONFIG + the availability-op-hours policy + required-non-required fixture Kicks.

### Sheet (annotate, no new rows) — tab "multi-resource (scheduler vs Unified (onsite))"

- Row 9 (two primary) note += multi ZERO-primary is also rejected up front (NoPrimary) → the multi path
  (request carries >1 assignedResource, WorkforceSchdMulResSchdPref enabled) enforces exactly-one-primary.
- Row 11 (primary-not-required) note += the optional-primary reject is request-count-agnostic (fires for a
  single assignedResource AND for >1); rejected at persist (save hook), not up front. Distinguish the pref
  from the request count.

## Run / verify

System `gradle` OFFLINE (./gradlew wrapper download SSL-blocked in sandbox):
`cd ~/code-clones/work/revoman-root && gradle integrationTest --offline --console=plain --tests "*WfsWritePathParityE2ETest.<method>"`
Live 262 org via ~/.revoman/config.yaml. Confirm #5 rejected UP FRONT (INVALID_INPUT, no availability/persist
error) and #6 rejected at PERSIST (primary message, not INVALID_INPUT).

## Commit

On branch wfs/scheduler-vs-unified-1x-parity, subject `test(scheduler-parity): <subject>`.
