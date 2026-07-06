# Workforce Scheduling — Read/Write Agreement Test Report

**Test suites:** `WfsWritePathParityE2ETest` + `WfsReadPathParityE2ETest` + `WfsRulesParityE2ETest`

**Last verified:** 01 Jul 2026, run live against the Workforce Scheduling test org.

**Release under test:** 262 (see Glossary). Each test records how the product behaves *today*.

---

## In one minute (for anyone)

We ran about 28 real scheduling scenarios against a live Workforce Scheduling org — booking field-service appointments and asking *"which time-slots are free?"* — to answer one question: **does the "read" side agree with the "write" side?**

- **Read side** = asking the system *"which slots / workers are free?"*
- **Write side** = actually *booking* the appointment.

**Headline: read and write agree everywhere.** A slot the system offers can be booked; a slot it hides is refused. We went looking for places where the two disagree and found **none** — the two things we suspected might disagree turned out, on live testing, to agree.

Along the way we found **three genuine crashes** in today's release and locked each one into a test on purpose, so the moment the behavior changes, that test fails and tells us.

Every scenario below has a plain-language description and what the product does today.

---

## Glossary (plain language)

Every term below is either everyday English or is defined here. If a word in the report looks technical and is not in this table, treat it as a mistake to fix.

| Term | What it means |
|---|---|
| **262** | A Salesforce release version. **262** is the release running today — the one under test. |
| **Read side / Write side** | Read = asking *"which slots or workers are free?"* Write = *"book this appointment."* |
| **Read and write agree (the goal of this report)** | A slot the read side *offers* can actually be *booked* by the write side; a slot the read side *hides* is *refused* by the write side. This report set out to confirm they always agree. |
| **Scheduling engine** | The one shared piece of code that works out which workers and time-slots are free. Both the read side and the write side call it — which is *why* they agree. |
| **Availability search** | The step where the scheduling engine looks for free time-slots for an appointment. If it finds a matching slot the booking can go through; if it finds none, the booking is refused. |
| **Characterization test** | A test that records what the product *actually* does today — bugs and all — not what it *should* do. When the behavior later changes, the test fails on purpose. That deliberate failure is the alert we want. |
| **Lock in / locked in (a behavior)** | To write a characterization test around today's behavior so that if it ever changes, the test fails and warns us. (Earlier drafts called this "pinning.") |
| **Control case** | A second case built identically to the main test case **except for the one thing being tested**. It proves the result came from that one thing and not from a broken setup. Example: a "busy worker marked *required*" control run beside a "busy worker marked *non-required*" test shows the difference is the *required* flag, not the busyness. |
| **Worker (resource)** | A technician who can be assigned to an appointment. |
| **Required worker** | A worker the appointment genuinely depends on. Checked against every scheduling rule. |
| **Non-required "helper"** | An extra worker added alongside, marked *not* required. The recurring question in this report: how little rule-checking does a helper get? |
| **Primary worker** | The lead worker on an appointment. An appointment may have exactly one. |
| **Crew** | All the workers assigned to one appointment. An "empty crew" means every worker was removed. |
| **Appointment** | The service-appointment record being booked or rescheduled. |
| **Account** | The customer record an appointment is booked for. |
| **Slot** | A bookable time window. |
| **Operating hours / Visiting hours / Shift** | When a worker can work / when a customer accepts visits / a worker's scheduled block of time. |
| **Scheduling rule** | A condition a booking must satisfy. The ones in this report: **Skills** (the worker has the needed skill), **Excluded** (the worker isn't on the account's block-list), **Territory / Working-locations** (the worker covers the area), **Availability** (the worker is free), **Required-resources** (a worker the account demands is present), **Visiting-hours** (the time is within the customer's allowed hours), **Start-time-interval** (bookings start on set boundaries, e.g. on the hour). |
| **Scheduling policy** | A named bundle of scheduling rules the system applies to a request. A request can name a policy to use; if it names none, the org's **default policy** is used instead. |
| **Default policy** | The policy the system falls back to when the request names none. On the org we tested, that default bundle *includes* the Required-resources rule (this matters for scenario 4z). |
| **Scheduling method (OnSite / OnField)** | Which scheduling mode runs. **OnSite** is the working mode on 262. **OnField** is a second mode that is unfinished on 262, so some checks that only run in it can't be tested yet. |
| **Required-worker preference** | An account setting that names a specific worker the account requires on its appointments. If that worker is missing from the crew, the Required-resources rule fails. |
| **Validation vs the availability search** | Two stages of handling a booking. **Validation** checks the request itself (its shape and simple rules) up front. The **availability search** then looks for a free slot. A request can pass validation and still be refused by the availability search. |
| **Crash** | The server errored out (an "HTTP 500 / internal server error"). These are the known bugs we lock in. |
| **Refused** | The server *deliberately* turned the request down as invalid ("HTTP 400") — not a crash. |
| **Functional test ("func test")** | A test that drives the real running server from end to end. A "Core func test" is one that lives in the Salesforce Core codebase. |
| **ReVoman test** | Our own test that sends real requests to a live org over the network and checks the responses, without needing the server's own test harness. |
| **Manual observations from the team** | Notes the team wrote by hand (in shared docs) on how scheduling behaves, before these tests existed. Several turned out to be wrong; those are highlighted below. |
| **Fresh setup** | Each scenario starts with a new login and brand-new test users, so scenarios can't interfere with one another. |
| **Confirmed with a debugger** | We attached a live debugger to the running server to verify the exact cause. |

*Note on the ✅ in the tables:* a checkmark means **the test ran and confirmed the recorded 262 behavior** — even when that behavior is a crash we are *deliberately* locking in. Green does **not** mean "healthy"; it means "faithfully recorded."

---

## The headline finding, explained

The `WfsRulesParityE2ETest` suite set out to prove the read and write sides apply every scheduling rule the same way — and to surface any place they don't. **Result: they agree on every rule.** The two things originally suspected of disagreeing were both shown wrong by live testing:

- **Required-resources rule:** the read side and the write side **both crash in exactly the same way** on the same bad input. Because both sides run through the *same scheduling engine*, a bug in that engine shows up identically on both — so this is agreement (both crash together), not a disagreement.
- **A reschedule that changes nothing:** we thought such a reschedule would quietly succeed by skipping the work. Live testing (confirmed with a debugger) showed the skip never triggers for a real appointment, so the reschedule re-runs the full calculation and hits a crash instead.

We also corrected an earlier misunderstanding: the "get available workers" read **does** apply the full set of scheduling rules (it is not a stripped-down version). So all four ways of reading are trustworthy mirrors of what a booking would do.

Bottom line: because read and write share one engine, they agree by design — and the tests confirm it. The only oddities are (a) shared crashes that hit both sides equally, and (b) one skip that can't be reached today.

---

## How our findings compare to the manual observations from the team

Before these tests were written, the team recorded manual observations for scenarios 2, 4, 4z, and 5. This section shows where our live tests **confirmed** those observations and where they **deviated**.

**Summary.** The one real deviation is **4z**: the manual observation's headline conclusion is wrong, and our live tests plus the code prove it — that one is a documentation bug. **Update (02 Jul 2026):** the deviation is stronger than first written — a live 262 Core func test (`testRescheduleAppointmentDeleteAllAssignedResources`) shows a reschedule that removes every worker **succeeds on 262**, so the observation is wrong on its *end result* today, not just its reason. Scenarios **4** and **5** confirm the observations. Scenario **2** confirms the part we could test; the other half was not testable on this release, so it is neither confirmed nor contradicted.

### Scenario 2 — is a shown slot a promise or a suggestion? **CONFIRMED (in part)**
- The manual observation said the everyday checks (skill, territory, free/busy, location, block-list) are a promise, while the costlier "does every field match" checks are not re-run for each slot, so a shown slot can still be turned down on those at booking time.
- We found the promise half is true: when the read offers a slot the booking goes through, and when the read hides a slot the booking is refused. We could not exercise the "shown but later rejected" half.
- Where this falls short: those field-by-field checks only run in the OnField scheduling method, which is unfinished on this release, so there was no way to make a slot show up and then get rejected on those checks. This is a test-coverage limit, not a disagreement — that half remains untested rather than contradicted.

### Scenario 4 — two primary workers on the final appointment. **CONFIRMED**
- The manual observation said the interface will not let you pick two primary workers, and the service turns the request down with a clear "only one can be primary" message.
- We found the same: two primaries are turned down cleanly and up front, with a message that means the same thing.
- The only difference is tiny wording — this org phrases it in the singular ("assigned resource"). Same meaning, no behavior difference.

### Scenario 4z — a reschedule that leaves no primary worker. **DEVIATION (on the reason, and on the result)**
- The manual observation concluded it is simply not possible to reschedule without a primary worker, and quoted a specific "cannot be set for Delete" error as the reason.
- **What we found:** the observation's *reason* is wrong on today's release — and, newly confirmed, its *result* is wrong on today's release too. A reschedule **can** end with no primary (indeed with no workers at all) on **262**; the Core func test proves it.
- **The reason is wrong.** There is no rule forcing you to keep a primary. When rescheduling, the validation step **explicitly allows zero primaries** (confirmed in the code, and matching the contradictory older test the observation itself flagged). The quoted "cannot be set for Delete" error only appears in one narrow case — when the removal request explicitly includes the primary flag — and it is a rule about which fields the request may contain, not a "you must keep a primary" rule.
- **The result is wrong too (new finding, 02 Jul 2026).** We ran the Core func test `testRescheduleAppointmentDeleteAllAssignedResources` against a live **262** server: **it PASSES** (about 470s, clean, workers verified removed). A reschedule that removes *every* worker **succeeds on 262**, leaving an appointment with no workers and no primary. So "you can't complete a reschedule with no primary" is false on today's release.
- **Why two 262 setups disagree — root cause, isolated live and confirmed by reading the org's configuration.** On the *workspace* org, our ReVoman test was refused ("the service resources are not available for the requested slot"); on the *local* 262 server, the Core func test succeeds. Same release, opposite outcome — so the deciding factor is **not** the release. It is **which scheduling policy the availability search runs under**, and it comes down to one rule: **Required-resources**. Both the Core func test and the ReVoman test send **no policy name**, so the search falls back to the org's **default policy**. We queried both orgs' configuration directly: the default policy carries **seven rules — including Required-resources** — while the good policy the ReVoman booking used carries only **Availability + Working-locations** (no Required-resources). The test's account setup names **one worker as required** (a required-worker preference), so once the crew is emptied that required worker is absent → the Required-resources rule fails → zero slots → refusal. Re-running the ReVoman remove-all under the good two-rule policy **succeeded on 262**; a diagnostic run under a policy that *adds* Required-resources reproduced the exact refusal — pinpointing the rule. **The "shift-usage" setting is NOT the difference:** the configuration query confirmed **both** policies' Availability rule carry the identical shift-usage setting. (Adding a fresh appointment time also changed nothing; the time was never the deciding factor.) Two ReVoman tests now lock in each half: `testRescheduleDeleteAllLeavesNoPrimaryE2E` (no policy → default policy with Required-resources → refused) and `testRescheduleDeleteAllWithPolicySucceedsE2E` (good policy, no Required-resources → succeeds).
- **Correction note:** earlier drafts of this report — and the ReVoman test's own code comment — blamed the remove-all refusal first on a next-release change, then on the org's territory/shift *data*, then on a missing shift-usage setting. All three are wrong. The isolated, configuration-confirmed cause is the presence of the **Required-resources rule** in the fallback policy combined with the account's required-worker preference; an empty crew fails that rule. 262 can already leave an appointment with no primary and no workers; whether the availability search allows it depends only on whether the policy's rules accept an empty crew. Our tests also surfaced a separate crash the observation never mentioned: a reschedule that changes nothing crashes on this release. Net: the observation's conclusion is a documentation bug — wrong reason **and** wrong result on today's release.

### Scenario 5 — can a primary worker be optional? **CONFIRMED**
- The manual observation said the service does not quietly fix a "primary but not required" request as the ticket assumed; instead it turns the request down at the save step, so there is no double-booking.
- We found exactly that: the request is turned down at the save step with the same message, it is not quietly corrected, and no double-booking happens.
- One coverage note: our test covers a single worker with an open time-slot. Several sub-cases the observation lists — a busy slot failing on availability, a two-worker request with an optional primary, and booking with only an optional worker — were not separately tested. Those remain uncovered, not contradicted.

---

## Scenario catalogue

### Booking — `WfsWritePathParityE2ETest`

#### Decision 1 — a non-required "helper" is not rule-checked (four scenarios)

> **The claim:** add a helper who breaks one scheduling rule, and on 262 the booking still succeeds — the helper skips the check. Verified across four different rules. Each runs with its own fresh setup so the four can't collide.
>
> **Test method:** `testNonRequiredHelperFitnessE2E` (all four are parts of this one method).

- **1a — Excluded worker.** Helper is on the account's block-list; only the Excluded rule could stop it. **Today: books anyway** (helper skips the check).
- **1b — Wrong territory.** Helper's territory coverage doesn't fully cover the booking window; only the Territory rule could stop it. **Today: books anyway.**
- **1c — Missing skill.** The work needs a skill the helper doesn't have; only the Skills rule could stop it. **Today: books anyway.**
- **1d — Wrong location role.** Helper is a "secondary" area member under a policy that only allows "primary" members; only the Working-locations rule could stop it. **Today: books anyway.**

#### Decision 1.4 — a helper can't fill a slot the account *requires* (two scenarios)

> **The claim:** the account demands a specific worker be present. If that worker is added only as a helper, it must not count. We expected a clean refusal; today it crashes instead.
>
> **Test method:** `testNonRequiredHelperCannotSatisfyRequiredDemandE2E`.

- **1.4a — The demand is met only by a helper.** **Today: the server crashes** (a known 262 bug), instead of the clean refusal the team's manual observation predicted. We record the crash exactly. (The characterization test locks in this crash, so it will fail and alert us if the behavior later changes.)
- **1.4b — A genuine required worker (control case).** **Today: refused because the worker isn't free** — importantly, *not* the required-resources error, confirming the required-worker handling doesn't raise that error by mistake.

#### Decision 1.5 — a helper isn't checked for being free, so it can be double-booked (two scenarios)

> **The claim:** if the helper is already busy at that time, that doesn't block the booking — helpers aren't checked for being free. The two runs differ by a single flag.
>
> **Test method:** `testNonRequiredHelperDoubleBooksE2E`.

- **1.5a — Busy helper, marked non-required.** **Today: books anyway** — the busy helper is double-booked, no free/busy check.
- **1.5b — Same busy worker, marked required (control case).** **Today: refused as not-available** — a *required* worker **is** checked for being free. This before/after pair is the clearest demonstration of the helper-vs-required difference in the whole suite.

#### Decision 3 — an appointment sent without the "required" flag (two scenarios)

> **The claim:** what happens when a worker is sent with no required-or-not flag at all? Today it crashes. Paired with a control case proving the *primary* flag's absence is harmless.
>
> **Test method:** `testMissingRequiredFlagE2E`.

- **3a — Flag missing entirely.** **Today: the server crashes** (a known 262 bug reading the absent flag). Recorded exactly. (The characterization test locks in this crash, so it will fail and alert us if the behavior later changes.)
- **3b — One required worker, no primary flag (control case).** **Today: books successfully** — confirms that leaving out the *primary* flag is harmless; only the *required* flag's absence breaks things.

#### Decision 4 — two "primary" workers are refused up front

> **Test method:** `testTwoPrimaryResourcesRejectedE2E`.

- One appointment sent with **two** workers both marked "primary" (an appointment may have only one). **Today: refused immediately** as an invalid request (caught by the validation step, before anything is booked); message: "Only one of the provided assigned resource can be a primary resource."

#### Decision 5 — a "primary" worker marked "not required" is refused (no silent fix, no double-book)

> **Test method:** `testPrimaryNotRequiredRejectedE2E`.

- **Main case:** a single worker marked "primary" but "not required" — a contradiction. **Today: refused at the final save step**, message "Only an required service resource can be set as a primary service resource." This disproves two worries: the system does **not** silently "fix" the contradiction, and it does **not** let the worker slip through to be double-booked (nothing gets saved).
- **Control case:** same worker, now correctly marked "required." **Today: books successfully** — pinning the refusal specifically to the "not required" flag.
- **Where the refusal happens (in the data layer, not just this API):** this rule is enforced when the assigned-worker record is saved — inside the platform's standard save process, not only in the scheduling API. That means it fires on **every** way of creating or updating an assigned worker — this scheduling API, an Apex script, a direct record write, or a bulk load. It is active whenever the org has multi-resource scheduling turned on (the same org setting this whole area depends on). *(For engineers: the save-time check is `AssignedResourceFunctions.saveHook_ValidateOnce` → `LightningSchedulerAssignedResourceValidator` in `fieldservice-impl`, bound to the AssignedResource entity by naming convention rather than explicit registration, and gated by the multi-resource org preference. So it is a genuine save-time validation on the record, not a scheduling-service-only check.)*

#### Decision 4z — a reschedule *can* leave an appointment with no primary worker (the team's manual observation is wrong here)

> **Test method:** `testRescheduleNoPrimaryE2E`.
>
> **Also:** `testRescheduleDeleteAllLeavesNoPrimaryE2E` and `testRescheduleDeleteAllWithPolicySucceedsE2E` (the "remove every worker" case, run two ways).

- **The claim we disproved (on the reason):** the team's manual observation says "you can't reschedule an appointment to have no primary worker," blaming a specific "cannot be set for Delete" error. The *reason* is wrong — the validation step explicitly allows zero primaries, and that error is a narrow field rule, not a "must keep a primary" rule.
- **⚠️ Correction (02 Jul 2026) — the result is also wrong on 262.** An earlier version of this section (and the ReVoman test below) claimed every remove path is blocked on 262. **That is incorrect.** The Core func test `testRescheduleAppointmentDeleteAllAssignedResources` **passes on a live 262 server** — a reschedule that removes every worker **succeeds on 262**, leaving an appointment with no workers and no primary. The bullets below record what our ReVoman test saw on the workspace org; the difference from the func test is the **scheduling policy** the availability search runs under (see the "why they disagree" note), not the release.
- **What happens on the workspace org, all three ways to remove the primary — none blocked by a primary rule:**
  - **Remove the primary WITH the "primary" flag on the removal request → refused** as an invalid request (a rule about which fields are allowed in the request, *not* about the crew's makeup; the appointment is left untouched). *(This case is release- and policy-independent — a plain check on which fields a removal request may include.)*
  - **Remove the primary WITHOUT that flag, keeping another required worker → allowed by validation, then stopped** by the availability search ("that time isn't available") — a search that (like the remove-all case below) ran under the org's default policy.
  - **Remove EVERY worker → allowed by validation; our ReVoman test (sending no policy name) was stopped by that same availability search** (`testRescheduleDeleteAllLeavesNoPrimaryE2E`). **But the Core func test runs the identical operation and SUCCEEDS** (`testRescheduleAppointmentDeleteAllAssignedResources`, PASS), and re-running the ReVoman test with a good policy named also **SUCCEEDS** (`testRescheduleDeleteAllWithPolicySucceedsE2E`). So a reschedule that empties the crew **can** complete on 262 — the availability search's refusal depends on the policy, it isn't a hard block.
- **Why the two 262 results disagree (root cause, isolated live and confirmed by reading the org's configuration).** The availability search runs under whatever scheduling policy the request names; when the request names none (both the func test and the ReVoman test leave it out), it falls back to the org's **default policy**. We queried the configuration directly: that default carries a **Required-resources rule** (seven rules total), while the good policy carries only **Availability + Working-locations**. The account setup names **one worker as required**, so an empty crew fails Required-resources → zero slots → refusal. Naming the good two-rule policy (no Required-resources) flips the ReVoman remove-all to **success**; a diagnostic run under a policy that adds Required-resources reproduces the refusal. **The shift-usage setting is not the difference** — both policies' Availability rule carry the identical shift-usage setting. Same 262 code, opposite outcome: **the deciding factor is whether the fallback policy's rules (here, Required-resources) accept an empty crew, not the release** (and not the appointment time — a fresh time changed nothing).
- **The one genuinely under-guarded case: two-or-more workers left with no primary.** There is a related shape we did *not* build a test for and that today's checks do *not* guard: a reschedule that leaves **two or more** workers but **no primary** among them (by demoting the primary, or by removing the primary from an appointment that started with three workers). The booking rule for a brand-new appointment already refuses this ("if there's more than one worker, exactly one must be primary"); rescheduling has no matching rule. Two points to be precise about:
  - **We now have a live test for it** (`testRescheduleDemotePrimaryTwoCrewNoPrimaryE2E`): demote the primary so two workers remain, neither primary. **Result on today's release: refused by the availability search** (HTTP 400, "the service resources are not available for the requested slot") — the same block the remove routes hit. It does **not** crash, and it does **not** save a no-primary crew. So on today's release this bad state is genuinely **unreachable** — good news, but note *why*: nothing *guards against it*; only the availability search happens to block it. Validation itself still allows zero primaries. (Our earlier draft guessed the demote route would crash on the record-ID bug — the live test corrected that: the availability search refuses first.)
  - **This is the gap a fix would need to close.** If the availability search were repaired so it found the surviving worker's slot, this shape would become reachable, and nothing would stop a multi-worker appointment from being left with no primary. The recommended fix is to give rescheduling the **same final-crew rule as new bookings**, checked on the resulting crew: two-or-more workers with no primary → refuse; more than one primary → refuse; zero or one worker → allow. (Captured in the handoff as an open recommendation.)
- **Takeaway:** this is a **documentation bug** (handed off separately), not a code bug today — the observation is wrong on the reason **and** (per the 02 Jul 2026 live 262 func-test result, plus the good-policy ReVoman test) wrong on the result: a reschedule that removes every worker already succeeds with no primary on 262 when the fallback policy's rules accept an empty crew. The open product question stands and is now more urgent: *should* a reschedule be allowed to leave no primary? The validation already allows it; the only thing that blocks it today is the availability search, and only when the policy carries a Required-resources rule and the account requires a now-absent worker. The "two-or-more workers, no primary" case is under-guarded. The recommended final-crew rule (handed off as an open recommendation) is worth applying, since the bad state is reachable on 262 today.

---

### Reading availability — `WfsReadPathParityE2ETest`

#### Decision 2 — is an offered slot a real promise? (read and write agree on the everyday checks)

> **Test method:** `testCheapCheckReadWritePromiseE2E`.

- Over a single worker, we compare an **available** window (inside its hours) and an **unavailable** window (outside). **Today:** the read offers slots in the available window *and* the booking succeeds there; the read offers zero slots in the unavailable window *and* the booking is refused there. So an offered slot is a genuine promise on these shared checks.
- **Scope note:** the team's manual observation also asks whether a *shown* slot could still be refused at booking on a costlier "every field matches" rule. That half **can't be tested on 262** — those rules only run in the OnField scheduling method, which is unfinished on this release, while the working (OnSite) mode shares read and write. Recorded, not attempted.

#### Decision 8 — the "limit how many workers to return" setting (two checks, one run)

> **Test method:** `testResourceLimitApptDistributionCapE2E`.

- **Limit 0:** **Today: returns an empty list, no error.** So "0" means a literal cap of zero, not "no limit." (We also confirm there was no hidden error, so an error can't disguise itself as "empty.")
- **Limit 50 (control case):** **Today: returns workers.** Confirms the cap of 0 above really was the cap doing its job. *(Open product question: should "0" mean a literal cap of zero, as it does today, or "no limit"?)*

#### Decision 9 — a silent permissions block on who can see whose shifts (two checks, one run)

> **Test method:** `testShiftSharingModeSplitE2E`.

- **Manager (owns the shifts, control case):** **Today: sees slots** — the shift read uses the caller's own permissions, and the manager can see its own shifts. This proves the case-worker's empty result below is the permissions block, not an empty setup.
- **Case-worker (not given access to the manager's private shifts):** identical request. **Today: sees zero slots, no error** — the shift read returns nothing because the case-worker can't see those shifts. The worker is still recognized (the other reads use full access), but contributes no slots. *(Open product question — see the two options below.)*

**What "the case-worker should see slots" really means — and what the tension is.** This is *not* a claim that one worker should see another worker's calendar. When the system looks for free slots, one call reads several things at once: who the workers are, their existing appointments, their time off, their calendar, and their **shifts**. Today every one of those reads ignores record-level sharing **except the shifts read**, which is filtered by what the requesting user personally has permission to see. So a worker is fully recognized as a candidate (because the other reads ignore sharing) yet contributes **no** availability (because the shifts read, honoring sharing, comes back empty for this user). The caller gets a successful, empty response with no explanation. **The problem is the inconsistency inside one operation** — the system half-applies sharing (enforced on shifts, ignored everywhere else), so the same call is half-secured and half-open, producing a silent blank instead of either real slots or a clear "you don't have access."

**Is there a read-vs-write contradiction — could a case-worker *book* a slot it can't *see*? No.** Booking runs the *same* set of reads to check availability, in the same modes, so a user cannot book a slot the read never showed them — the booking is simply refused with "that time isn't available." Read (blocked) and write (blocked) stay consistent; the block applies identically at both ends.

**The two product options:**
- **Option A — keep the permissions block, but stop failing silently:** if shift visibility is a real access boundary, return a clear "you don't have visibility into this worker's availability" reason instead of a bare empty result.
- **Option B — line up the reads:** make the shifts read use the same sharing mode as its siblings (all full-access, or all user-permissions), so availability doesn't silently vanish for one input while every other input ignores sharing — and the case-worker sees the same slots the owning manager does.

---

### Read/write agreement per rule — `WfsRulesParityE2ETest`

> **How each test works:** for one rule, we run a **bad case** (rule should fire → read offers zero slots *and* the booking is refused) and a **good case** (rule passes → read offers slots *and* the booking succeeds). We check the read decision and the write decision line up on both. Each bad case is set up so only the one rule under test can cause the outcome, and each good case is proven to work (so a "zero" in the bad case is genuinely the rule, not a broken setup). The Availability rule is already covered by Decision 2 above.

- **Skills** (`testMatchSkillsReadWriteParityE2E`) — worker lacks the needed skill. **Read and write agree.**
- **Excluded** (`testExcludedResourcesReadWriteParityE2E`) — worker on the account's block-list. **Read and write agree.**
- **Territory / Working-locations** (`testWorkingLocationsReadWriteParityE2E`) — worker is a "secondary" area member under a "primary-only" policy. **Read and write agree.**
- **Visiting hours** (`testVisitingHoursReadWriteParityE2E`) — booking time falls outside the customer's allowed hours (but inside the worker's hours, so only this rule can cause it). **Read and write agree.**
- **Start-time interval** (`testStartTimeIntervalReadWriteParityE2E`) — booking starts off the allowed boundary (e.g. 11:30 when only on-the-hour is allowed) vs an on-boundary control case. **Read and write agree.**
- **Required-resources** (`testRequiredResourcesReadWriteBothCrashE2E`) — **read and write both crash** the same way (the same known 262 bug), because they share one engine. A good-case control does *not* crash (proving the crash only happens on the bad input). This is the scenario that disproved the "these two disagree" theory — it's a shared crash, not a disagreement.
- **All four read APIs agree** (`testCrossApiRuleAgreementE2E`) — one "missing skill" case run through all four ways of reading *and* the booking: every read drops the skill-less worker (and keeps the qualified one), and the booking is refused. Proves all four reads and the write share one engine, so the per-rule results above apply to every API.
- **A reschedule that changes nothing** (`testNoOpRescheduleShortCircuitE2E`) — this was expected to succeed by skipping the work. **Today it doesn't:** the skip is bypassed and the reschedule crashes. *Confirmed with a debugger* — the cause is a Salesforce record-ID length mismatch (see the note below), so the system thinks the worker changed when it hasn't.

---

## Results at a glance

*(✅ = the test ran and confirmed today's behavior, including the crashes we lock in on purpose.)*

| # | Scenario | What 262 does today | Test |
|---|---|---|---|
| 1a | Helper on the block-list | Books anyway (helper skips the check) | ✅ |
| 1b | Helper in the wrong territory | Books anyway | ✅ |
| 1c | Helper missing the skill | Books anyway | ✅ |
| 1d | Helper in the wrong location role | Books anyway | ✅ |
| 1.4a | Helper asked to fill a *required* slot | **Server crash** (known 262 bug) | ✅ |
| 1.4b | A genuine required worker (control case) | Refused — worker isn't free | ✅ |
| 1.5a | Busy helper, non-required | Books anyway (double-booked) | ✅ |
| 1.5b | Busy worker, required (control case) | Refused — not free | ✅ |
| 3a | Worker sent with no required flag | **Server crash** (known 262 bug) | ✅ |
| 3b | One required worker, no primary flag (control case) | Books successfully | ✅ |
| 4 | Two "primary" workers | Refused up front as invalid | ✅ |
| 5 | "Primary" but "not required" (main case) | Refused at save (no silent fix, no double-book) | ✅ |
| 5c | "Primary" and "required" (control case) | Books successfully | ✅ |
| 4z-A | Reschedule removes primary, *with* the flag | Refused as an invalid request | ✅ |
| 4z-B | Reschedule removes primary, *without* the flag (keeps another worker) | Allowed by validation; stopped only by the availability search | ✅ |
| 4z-C | Reschedule removes *every* worker (empty crew) | **Depends on the policy on 262:** allowed by validation; **succeeds** under a policy without a Required-resources rule (Core func test `testRescheduleAppointmentDeleteAllAssignedResources` — PASS; ReVoman `testRescheduleDeleteAllWithPolicySucceedsE2E` — PASS), refused under the org's default policy which carries Required-resources — the account requires a worker the empty crew no longer has (ReVoman `testRescheduleDeleteAllLeavesNoPrimaryE2E`). Not the shift-usage setting (identical in both policies); not release-dependent | ✅ |
| 2 | Are offered slots a real promise? | Yes — offered slot books; hidden slot is refused | ✅ |
| 8a | "Return at most 0 workers" | Empty list, no error | ✅ |
| 8b | "Return at most 50" (control case) | Workers returned | ✅ |
| 9a | Manager reading its own shifts (control case) | Sees slots | ✅ |
| 9b | Case-worker reading a manager's private shifts | Sees zero slots, no error | ✅ |
| Rule: Skills | Read vs write on a missing skill | Agree | ✅ |
| Rule: Excluded | Read vs write on a blocked worker | Agree | ✅ |
| Rule: Territory | Read vs write on wrong location role | Agree | ✅ |
| Rule: Visiting hours | Read vs write outside allowed hours | Agree | ✅ |
| Rule: Start-time interval | Read vs write on an off-boundary start | Agree | ✅ |
| Rule: Required-resources | Read vs write on the required-slot bug | Agree — **both crash the same way** | ✅ |
| All 4 read APIs agree | Missing skill through every read + the booking | All drop the worker / refuse | ✅ |
| Reschedule that changes nothing | (no-change reschedule) | Skip bypassed → crash (record-ID mismatch) | ✅ |

The full rules suite last ran **all 8 tests green together** (about 7 minutes, no license problems).

---

## Bugs found along the way (recorded, handed off — not fixed here)

The tests lock in **three separate crashes** in today's release, all in the shared scheduling engine (details in `~/work/handoff/2026-07-01-wfs-262-slotgen-npe-family.md`):

1. **Booking crash** when a required-worker demand is met only by a helper (Decision 1.4a).
2. **Read crash** for the same situation — new evidence the bug affects the read side too, because both share one engine (Required-resources rule test).
3. **Reschedule crash** when re-running a reschedule — a different spot in the code (the no-change reschedule test).

Plus a **documentation bug** (Decision 4z: the team's manual observation "you can't reschedule without a primary worker" is wrong) — handed off in `~/work/handoff/2026-07-01-wfs-doc-4z-no-primary-contradiction.md`.

These crashes are today's real behavior. We lock them in so the suite alerts us the moment the behavior changes — at that point the test flips from "expects a crash" to failing, which is the signal to update it.

**One extra detail on the reschedule crash (for engineers):** Salesforce record IDs come in a short 15-character form and a longer 18-character form that mean the same record. The reschedule request carries the short form while the stored appointment holds the long form, so the system concludes the worker changed when it hasn't — bypasses its "nothing to do" skip, re-runs the full calculation, and hits the crash. Confirmed with a debugger. This same 15-vs-18 mismatch has bitten this codebase before; fixing it at the boundary would also make the intended skip work.

---

## Preconditions (for whoever sets up the test org — skip if that's not you)

These run against a **provisioned Workforce Scheduling org**, not unattended CI, and quietly **skip** if live-org credentials aren't configured. The org needs:

- Multi-resource scheduling turned on (the setting that allows more than one worker per appointment) plus in-business scheduling enabled.
- The Shift "Status" dropdown seeded so "Confirmed" shifts are valid.
- Each worker: a confirmed shift covering the test window, and territory membership that started in the past.
- The test data set up in the 262 style (the scheduling method set to "OnSite" on the relevant records; work types configured as 262 expects).
- The two Workforce Scheduling permission sets (Manager + Resource) assigned to the test users.
- Live-org credentials in `~/.revoman/config.yaml` (URL, username, password). These are applied automatically at run time; the committed config file stays blank, so no credentials are ever committed.

**Operational note:** each run creates timestamped test users, which pile up and can hit the org's user-license limit. When that happens, deactivate the old test users (the `@revoman.org` ones) to free up licenses — never the admin user.

---

## Coverage notes (honest gaps, for the next author)

The scenarios are well-isolated and the crash checks are sharp. Remaining gaps:

1. **We don't check *who* is making the call.** The tests run as a limited-permission manager (not an admin), and that was verified live — but no test would *fail* if someone quietly switched it back to admin. A "the caller really is the manager" check would lock this in.
2. **The "limit how many workers" test only proves the extremes.** The setup has just two workers, so "limit 50" and "no limit" look the same. It proves "0 returns nothing" but not that a cap *trims* a longer list. A "limit 1 out of 2 → exactly 1" case would prove trimming.
3. **Decision 1's four helper scenarios are one-sided** *(now partly covered)*. Each proves the helper *books*; none has a paired "and the same problem *is* caught when the worker is required" check in that method. The new per-rule agreement suite now provides exactly that paired coverage for Skills, Excluded, and Territory, so the risk of a test passing for the wrong reason is much lower at the suite level.
4. **A few checks only say "not a success," not the exact reason.** Where the refusal message is generic (the availability refusals), the test can only confirm "it didn't succeed." Decisions 4 and 5 and the crash tests now check the exact code/message, so this is limited to the generic-refusal rows.
5. **Why does a *satisfied* required-resources read return zero slots?** In the required-resources test, the good-case control proves there's no crash, but it also returns zero slots — why a satisfied request yields none is noted but not yet investigated.
6. **The "two-or-more workers, no primary" reschedule is now live-tested (was previously only reasoned).** `testRescheduleDemotePrimaryTwoCrewNoPrimaryE2E` demotes the primary leaving two workers, neither primary. Verified result: **refused by the availability search** (HTTP 400), no crash, no saved no-primary crew — so today's release does not allow the bad state. The remaining gap is that nothing *guards against it* (only the availability search blocks it); the recommended final-crew rule would need to close that if the availability search were repaired. This resolves the earlier "reasoned but not verified" caveat.

These are next-step suggestions, not defects in what exists today.
