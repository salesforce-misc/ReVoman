# Workforce Scheduling — Read/Write Parity Test Report

**Test suites:** `WfsWritePathParityE2ETest` + `WfsReadPathParityE2ETest` + `WfsRulesParityE2ETest`

**Last verified:** 01 Jul 2026, run live against the Workforce Scheduling test org.

**Release under test:** 262 (see glossary). Each test records how the product behaves *today*; the note per scenario says what the next release (264) is expected to change.

---

## In one minute (for anyone)

We ran ~28 real scheduling scenarios against a live Workforce Scheduling org — booking field-service appointments and asking "what time-slots are free?" — to answer one question: **does the "read" side agree with the "write" side?**

- **Read side** = asking the system *"what slots / workers are available?"*
- **Write side** = actually *booking* the appointment.

**Headline: read and write agree everywhere.** A slot the system offers can be booked; a slot it hides is refused. We went looking for places where the two disagree and found **none** — the two things we suspected might disagree turned out, on live testing, to agree after all.

Along the way we uncovered **three genuine crashes** in today's release and pinned them down on purpose, so that the moment the next release fixes them, the matching test will fail and tell us.

Every scenario below has a plain-language description, what the product does today, and what the next release should change.

---

## Glossary (plain language)

| Term | What it means |
|---|---|
| **262 / 264** | Salesforce release versions. **262** is the release running today (what we tested). **264** is the next release, expected to fix several of these behaviors. |
| **Read path / Write path** | Read = "what slots or workers are available?" Write = "book this appointment." |
| **"Read and write agree" (parity)** | A slot the read path *offers* can actually be *booked* by the write path; a slot the read path *hides* is *refused* by the write path. This report set out to confirm they always agree. |
| **Characterization test** | A test that records what the product *actually* does today — bugs and all — rather than what it *should* do. When the behavior later changes, the test fails on purpose, which is the alert we want. |
| **Resource / worker** | A technician assigned to an appointment. |
| **Required resource** | A worker the appointment genuinely depends on; checked against every scheduling rule. |
| **Non-required "helper"** | An extra worker added alongside, marked *not* required. The recurring question: how little rule-checking does a helper get? |
| **Primary resource** | The lead worker on an appointment. An appointment needs exactly one. |
| **Appointment** | The service-appointment record being booked or rescheduled. |
| **Slot** | A bookable time window. |
| **Operating hours / Visiting hours / Shift** | When a worker can work / when a customer accepts visits / a worker's scheduled block of time. |
| **Scheduling rule** | A condition a booking must satisfy. The ones in this report: **Skills** (worker has the needed skill), **Excluded** (worker isn't on the account's block-list), **Territory / Working-locations** (worker covers the area), **Availability** (worker is free), **Required-resources** (a worker the account demands is present), **Visiting-hours** (the time is within the customer's allowed hours), **Start-time-interval** (bookings start on set boundaries, e.g. on the hour). |
| **Crash** | The server errored out (an "HTTP 500 / internal server error"). These are the known bugs we pin. |
| **Rejected** | The server *deliberately* refused the request as invalid ("HTTP 400") — not a crash. |
| **The product doc** | The design document describing how scheduling is *supposed* to behave. Several of its claims turned out to be wrong; those are noted below. |
| **Fresh setup** | Each scenario starts with a new login and brand-new test users, so scenarios can't interfere with one another. |
| **Confirmed with a debugger** | We attached a live debugger to the running server to verify the exact cause. |

*Note on the ✅ in the tables:* a checkmark means **the test ran and confirmed the recorded 262 behavior** — even when that behavior is a crash we are *deliberately* pinning. Green does **not** mean "healthy"; it means "faithfully recorded."

---

## The headline finding, explained

The `WfsRulesParityE2ETest` suite set out to prove the read and write sides apply every scheduling rule the same way — and to surface any place they don't. **Result: they agree on every rule.** The two things originally suspected of disagreeing were both shown wrong by live testing:

- **Required-resources rule:** the read side and the write side **both crash in exactly the same way** on the same bad input. Because both sides run through the *same underlying scheduling engine*, a bug in that engine shows up identically on both — so this is agreement (both crash together), not a disagreement.
- **"No-op" reschedule shortcut:** we thought a reschedule that changes nothing would quietly succeed via a shortcut. Live testing (confirmed with a debugger) showed the shortcut never triggers for a real appointment, so the reschedule re-runs the full calculation and hits a crash instead.

We also corrected an earlier misunderstanding: the "get available resources" read **does** apply the full set of scheduling rules (it is not a stripped-down shortcut). So all four ways of reading are trustworthy mirrors of what a booking would do.

Bottom line: because read and write share one engine, they agree by design — and the tests confirm it. The only oddities are (a) shared crashes that hit both sides equally, and (b) one shortcut that can't be reached today.

---

## Scenario catalogue

### Booking path — `WfsWritePathParityE2ETest`

#### Decision 1 — a non-required "helper" is not rule-checked (four scenarios)

> **The claim:** add a helper who breaks one scheduling rule, and on 262 the booking still succeeds — the helper skips the check. Verified across four different rules. Each runs with its own fresh setup so the four can't collide.
>
> **Test method:** `testNonRequiredHelperFitnessE2E` (all four are parts of this one method).

- **1a — Excluded worker.** Helper is on the account's block-list; only the Excluded rule could stop it. **Today: books anyway** (helper skips the check). *Next release: should be rejected.*
- **1b — Wrong territory.** Helper's territory coverage doesn't fully cover the booking window; only the Territory rule could stop it. **Today: books anyway.** *Next release: rejected.*
- **1c — Missing skill.** The work needs a skill the helper doesn't have; only the Skills rule could stop it. **Today: books anyway.** *Next release: rejected.*
- **1d — Wrong location role.** Helper is a "secondary" area member under a policy that only allows "primary" members; only the Working-locations rule could stop it. **Today: books anyway.** *Next release: rejected.*

#### Decision 1.4 — a helper can't fill a slot the account *requires* (two scenarios)

> **The claim:** the account demands a specific worker be present. If that worker is added only as a helper, it must not count. We expected a clean rejection; today it crashes instead.
>
> **Test method:** `testNonRequiredHelperCannotSatisfyRequiredDemandE2E`.

- **1.4a — The demand is met only by a helper.** **Today: the server crashes** (a known 262 bug), instead of the clean rejection the product doc predicted. We record the crash exactly. *Next release: a clean rejection, no crash — at which point this test flips and alerts us.*
- **1.4b — A genuine required worker (control).** **Today: rejected because the worker isn't free** — importantly, *not* the required-resources error, confirming that path doesn't raise it by mistake.

#### Decision 1.5 — a helper isn't checked for being free, so it can be double-booked (two scenarios)

> **The claim:** if the helper is already busy at that time, that doesn't block the booking — helpers aren't availability-checked. The two runs differ by a single flag.
>
> **Test method:** `testNonRequiredHelperDoubleBooksE2E`.

- **1.5a — Busy helper, marked non-required.** **Today: books anyway** — the busy helper is double-booked, no availability check. *Next release: should be blocked as not-available.*
- **1.5b — Same busy worker, marked required (control).** **Today: rejected as not-available** — a *required* worker **is** checked for being free. This before/after pair is the clearest demonstration of the helper-vs-required difference in the whole suite.

#### Decision 3 — an appointment sent without the "required" flag (two scenarios)

> **The claim:** what happens when a worker is sent with no required-or-not flag at all? Today it crashes. Paired with a control proving the *primary* flag's absence is harmless.
>
> **Test method:** `testMissingRequiredFlagE2E`.

- **3a — Flag missing entirely.** **Today: the server crashes** (a known 262 bug reading the absent flag). Recorded exactly. *Next release: treat a missing flag as "not required" and handle it cleanly — test flips and alerts.*
- **3b — One required worker, no primary flag (control).** **Today: books successfully** — confirms that leaving out the *primary* flag is harmless; only the *required* flag's absence breaks things.

#### Decision 4 — two "primary" workers are refused up front

> **Test method:** `testTwoPrimaryResourcesRejectedE2E`.

- One appointment sent with **two** workers both marked "primary" (an appointment may have only one). **Today: refused immediately** as an invalid request (caught by the input-validation step, before anything is booked); message: "Only one of the provided assigned resource can be a primary resource." *Next release: unchanged — this clean message already shipped, so today and next release behave the same (recorded for completeness).*

#### Decision 5 — a "primary" worker marked "not required" is refused (no silent fix, no double-book)

> **Test method:** `testPrimaryNotRequiredRejectedE2E`.

- **Probe:** a single worker marked "primary" but "not required" — a contradiction. **Today: refused at the final save step**, message "Only an required service resource can be set as a primary service resource." This disproves two worries: the system does **not** silently "fix" the contradiction, and it does **not** let the worker slip through to be double-booked (nothing gets saved). *Next release: unchanged — refusing is the intended behavior.*
- **Control:** same worker, now correctly marked "required." **Today: books successfully** — pinning the refusal specifically to the "not required" flag.

#### Decision 4z — a reschedule *can* leave an appointment with no primary worker (the product doc is wrong here)

> **Test method:** `testRescheduleNoPrimaryE2E`.

- **The claim we disproved:** the product doc says "you can't reschedule an appointment to have no primary worker." That's **wrong for the reschedule path** — the validation step explicitly allows zero primaries. The doc also blames the wrong error.
- **What actually happens:** we book a two-worker appointment, then try two ways to remove the primary:
  - **With the "primary" flag on the removal request → refused** as an invalid request (a rule about which fields are allowed in the request, *not* a rule about crew makeup; the appointment is left untouched).
  - **Without that flag → the removal is allowed by validation, but the booking still can't complete** — it's stopped later by an ordinary "that time isn't available" check, **never** by a "must keep a primary" rule.
- **Takeaway:** this is a **documentation bug** (handed off separately), not a code bug. The open product question stands: *should* a reschedule be allowed to leave no primary? Today's validation allows it.

---

### Read path — `WfsReadPathParityE2ETest`

#### Decision 2 — is an offered slot a real promise? (read and write agree on the cheap checks)

> **Test method:** `testCheapCheckReadWritePromiseE2E`.

- Over a single worker, we compare an **available** window (inside its hours) and an **unavailable** window (outside). **Today:** the read offers slots in the available window *and* the booking succeeds there; the read offers zero slots in the unavailable window *and* the booking is refused there. So an offered slot is a genuine promise on these shared checks.
- **Scope note:** the product doc also asks whether a *shown* slot could still be refused at booking on a costlier "field-match" rule. That half **can't be tested on 262** — those rules only run in a scheduling mode ("OnField") that is unfinished in this release, while the working mode shares read and write. Recorded, not attempted.

#### Decision 8 — the "limit how many workers to return" setting (two checks, one run)

> **Test method:** `testResourceLimitApptDistributionCapE2E`.

- **Limit 0:** **Today: returns an empty list, no error.** So "0" means a literal cap of zero, not "no limit." (We also confirm there was no hidden error, so an error can't masquerade as "empty.")
- **Limit 50 (control):** **Today: returns workers.** Confirms the cap of 0 above really was the cap doing its job. *Next release, if the product chooses "no limit": 0 would return everyone.*

#### Decision 9 — a silent permissions gate on who can see whose shifts (two checks, one run)

> **Test method:** `testShiftSharingModeSplitE2E`.

- **Manager (owns the shifts, control):** **Today: sees slots** — the shift read uses the caller's own permissions, and the manager can see its own shifts. This proves the case-worker's empty result below is the permissions gate, not an empty setup.
- **Case-worker (not shared the manager's private shifts):** identical request. **Today: sees zero slots, no error** — the shift read returns nothing because the case-worker can't see those shifts. The worker is still recognized (other reads use full access), but contributes no slots. *Next release, one option: align the permissions so the case-worker also sees slots.*

---

### Rules read/write agreement — `WfsRulesParityE2ETest`

> **How each test works:** for one rule, we run a **bad case** (rule should fire → read offers zero slots *and* the booking is refused) and a **good case** (rule passes → read offers slots *and* the booking succeeds). We check the read decision and the write decision line up on both. Each bad case is set up so only the one rule under test can cause the outcome, and each good case is proven to work (so a "zero" in the bad case is genuinely the rule, not a broken setup). The Availability rule is already covered by Decision 2 above.

- **Skills** (`testMatchSkillsReadWriteParityE2E`) — worker lacks the needed skill. **Read and write agree.**
- **Excluded** (`testExcludedResourcesReadWriteParityE2E`) — worker on the account's block-list. **Read and write agree.**
- **Territory / Working-locations** (`testWorkingLocationsReadWriteParityE2E`) — worker is a "secondary" area member under a "primary-only" policy. **Read and write agree.**
- **Visiting hours** (`testVisitingHoursReadWriteParityE2E`) — booking time falls outside the customer's allowed hours (but inside the worker's hours, so only this rule can cause it). **Read and write agree.**
- **Start-time interval** (`testStartTimeIntervalReadWriteParityE2E`) — booking starts off the allowed boundary (e.g. 11:30 when only on-the-hour is allowed) vs an on-boundary control. **Read and write agree.**
- **Required-resources** (`testRequiredResourcesReadWriteBothCrashE2E`) — **read and write both crash** the same way (the same known 262 bug), because they share one engine. A good-case control does *not* crash (proving the crash only happens on the bad input). This is the scenario that disproved the "these two disagree" theory — it's a shared crash, not a disagreement.
- **All four read APIs agree** (`testCrossApiRuleAgreementE2E`) — one "missing skill" case run through all four ways of reading *and* the booking: every read drops the skill-less worker (and keeps the qualified one), and the booking is refused. Proves all four reads and the write share one engine, so the per-rule results above apply to every API.
- **"No-op" reschedule shortcut** (`testNoOpRescheduleShortCircuitE2E`) — a reschedule that changes nothing was expected to succeed via a shortcut. **Today it doesn't:** the shortcut is skipped and the reschedule crashes. *Confirmed with a debugger* — the cause is a Salesforce record-ID length mismatch (see the note below), so the system thinks the worker changed when it hasn't.

---

## Results at a glance

*(✅ = the test ran and confirmed today's behavior, including the crashes we pin on purpose.)*

| # | Scenario | What 262 does today | Test |
|---|---|---|---|
| 1a | Helper on the block-list | Books anyway (helper skips the check) | ✅ |
| 1b | Helper in the wrong territory | Books anyway | ✅ |
| 1c | Helper missing the skill | Books anyway | ✅ |
| 1d | Helper in the wrong location role | Books anyway | ✅ |
| 1.4a | Helper asked to fill a *required* slot | **Server crash** (known 262 bug) | ✅ |
| 1.4b | A genuine required worker (control) | Rejected — worker isn't free | ✅ |
| 1.5a | Busy helper, non-required | Books anyway (double-booked) | ✅ |
| 1.5b | Busy worker, required (control) | Rejected — not free | ✅ |
| 3a | Worker sent with no required flag | **Server crash** (known 262 bug) | ✅ |
| 3b | One required worker, no primary flag (control) | Books successfully | ✅ |
| 4 | Two "primary" workers | Refused up front as invalid | ✅ |
| 5 | "Primary" but "not required" (probe) | Refused at save (no silent fix, no double-book) | ✅ |
| 5c | "Primary" and "required" (control) | Books successfully | ✅ |
| 4z-A | Reschedule removes primary, *with* the flag | Refused as an invalid request | ✅ |
| 4z-B | Reschedule removes primary, *without* the flag | Allowed by validation; stopped only by an availability check | ✅ |
| 2 | Are offered slots a real promise? | Yes — offered slot books; hidden slot is refused | ✅ |
| 8a | "Return at most 0 workers" | Empty list, no error | ✅ |
| 8b | "Return at most 50" (control) | Workers returned | ✅ |
| 9a | Manager reading its own shifts (control) | Sees slots | ✅ |
| 9b | Case-worker reading a manager's private shifts | Sees zero slots, no error | ✅ |
| Rule: Skills | Read vs write on a missing skill | Agree | ✅ |
| Rule: Excluded | Read vs write on a blocked worker | Agree | ✅ |
| Rule: Territory | Read vs write on wrong location role | Agree | ✅ |
| Rule: Visiting hours | Read vs write outside allowed hours | Agree | ✅ |
| Rule: Start-time interval | Read vs write on an off-boundary start | Agree | ✅ |
| Rule: Required-resources | Read vs write on the required-slot bug | Agree — **both crash the same way** | ✅ |
| All 4 read APIs agree | Missing skill through every read + the booking | All drop the worker / refuse | ✅ |
| No-op reschedule | Reschedule that changes nothing | Shortcut skipped → crash (record-ID mismatch) | ✅ |

The full rules suite last ran **all 8 tests green together** (about 7 minutes, no license problems).

---

## Bugs found along the way (recorded, handed off — not fixed here)

The tests pin down **three separate crashes** in today's release, all in the shared scheduling engine (details in `~/work/handoff/2026-07-01-wfs-262-slotgen-npe-family.md`):

1. **Booking crash** when a required worker demand is met only by a helper (Decision 1.4a).
2. **Read crash** for the same situation — new evidence the bug affects the read side too, because both share one engine (Required-resources rule test).
3. **Reschedule crash** when re-running a reschedule — a different spot in the code (the no-op reschedule test).

Plus a **documentation bug** (Decision 4z: the product doc's "you can't reschedule without a primary worker" is wrong) — handed off in `~/work/handoff/2026-07-01-wfs-doc-4z-no-primary-contradiction.md`.

These crashes are today's real behavior. We pin them so the suite alerts us the moment the next release fixes them — at that point the test flips from "expects a crash" to failing, which is the signal to update it.

**One extra detail on the reschedule crash (for engineers):** Salesforce record IDs come in a short 15-character form and a longer 18-character form that mean the same record. The reschedule request carries the short form while the stored appointment holds the long form, so the system concludes the worker changed when it hasn't — skips its "nothing to do" shortcut, re-runs the full calculation, and hits the crash. Confirmed with a debugger. This same 15-vs-18 mismatch has bitten this codebase before; fixing it at the boundary would also make the intended shortcut work.

---

## Preconditions (for whoever sets up the test org — skip if that's not you)

These run against a **provisioned Workforce Scheduling org**, not unattended CI, and quietly **skip** if live-org credentials aren't configured. The org needs:

- Multi-resource scheduling turned on (the setting that allows more than one worker per appointment) plus in-business scheduling enabled.
- The Shift "Status" dropdown seeded so "Confirmed" shifts are valid.
- Each worker: a confirmed shift covering the test window, and territory membership that started in the past.
- The test data set up in the 262 style (the scheduling method set to "OnSite" on the relevant records; work types without the extra fields 264 adds).
- The two Workforce Scheduling permission sets (Manager + Resource) assigned to the test users.
- Live-org credentials in `~/.revoman/config.yaml` (URL, username, password). These are applied automatically at run time; the committed config file stays blank, so no credentials are ever committed.

**Operational note:** each run creates timestamped test users, which pile up and can hit the org's user-license limit. When that happens, deactivate the old test users (the `@revoman.org` ones) to free up licenses — never the admin user.

---

## Coverage notes (honest gaps, for the next author)

The scenarios are well-isolated and the crash checks are sharp. Remaining gaps:

1. **We don't assert *who* is making the call.** The tests run as a limited-permission manager (not an admin), and that was verified live — but no test would *fail* if someone quietly switched it back to admin. A "the caller really is the manager" check would lock this in.
2. **The "limit how many workers" test only proves the extremes.** The setup has just two workers, so "limit 50" and "no limit" look the same. It proves "0 returns nothing" but not that a cap *trims* a longer list. A "limit 1 out of 2 → exactly 1" case would prove trimming.
3. **Decision 1's four helper scenarios are one-sided** *(now partly covered)*. Each proves the helper *books*; none has a paired "and the same problem *is* caught when the worker is required" check in that method. The new rules-agreement suite now provides exactly that paired coverage for Skills, Excluded, and Territory, so the risk of a test passing for the wrong reason is much lower at the suite level.
4. **A few checks only say "not a success," not the exact reason.** Where the refusal message is generic (the availability rejections), the test can only confirm "it didn't succeed." Decisions 4 and 5 and the crash tests now check the exact code/message, so this is limited to the generic-refusal rows.
5. **Why does a *satisfied* required-resources read return zero slots?** In the required-resources test, the good-case control proves there's no crash, but it also returns zero slots — why a satisfied request yields none is noted but not yet investigated.

These are next-step suggestions, not defects in what exists today.
