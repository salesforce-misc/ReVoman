/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman

import arrow.core.Either.Left
import arrow.core.Either.Right
import arrow.core.flatMap
import arrow.core.merge
import com.salesforce.revoman.input.PostExeHook
import com.salesforce.revoman.input.bufferFile
import com.salesforce.revoman.input.bufferInputStream
import com.salesforce.revoman.input.config.Kick
import com.salesforce.revoman.input.isV3Collection
import com.salesforce.revoman.internal.exe.deepFlattenItems
import com.salesforce.revoman.internal.exe.executePolling
import com.salesforce.revoman.internal.exe.executePostResJS
import com.salesforce.revoman.internal.exe.executePreReqJS
import com.salesforce.revoman.internal.exe.fireHttpRequest
import com.salesforce.revoman.internal.exe.ledgerSkipDecision
import com.salesforce.revoman.internal.exe.postStepHookExe
import com.salesforce.revoman.internal.exe.preStepHookExe
import com.salesforce.revoman.internal.exe.renderHttpMsg
import com.salesforce.revoman.internal.exe.shadowedProducerPaths
import com.salesforce.revoman.internal.exe.shouldHaltExecution
import com.salesforce.revoman.internal.exe.shouldStepBePicked
import com.salesforce.revoman.internal.exe.timed
import com.salesforce.revoman.internal.exe.unmarshallRequest
import com.salesforce.revoman.internal.exe.unmarshallResponse
import com.salesforce.revoman.internal.json.MoshiReVoman
import com.salesforce.revoman.internal.json.MoshiReVoman.Companion.initMoshi
import com.salesforce.revoman.internal.log.RevomanLog
import com.salesforce.revoman.internal.log.RunLogContext
import com.salesforce.revoman.internal.postman.Info
import com.salesforce.revoman.internal.postman.PostmanSDK
import com.salesforce.revoman.internal.postman.RegexReplacer
import com.salesforce.revoman.internal.postman.dynamicVariableGenerator
import com.salesforce.revoman.internal.postman.sandbox.PmSandbox
import com.salesforce.revoman.internal.postman.template.Environment.Companion.mergeEnvs
import com.salesforce.revoman.internal.postman.template.Template
import com.salesforce.revoman.internal.postman.template.v3.V3Loader.load
import com.salesforce.revoman.output.ExeType
import com.salesforce.revoman.output.ExeType.HTTP_REQUEST
import com.salesforce.revoman.output.ExeType.POLLING
import com.salesforce.revoman.output.ExeType.POST_RES_JS
import com.salesforce.revoman.output.ExeType.POST_STEP_HOOK
import com.salesforce.revoman.output.ExeType.PRE_REQ_JS
import com.salesforce.revoman.output.ExeType.PRE_STEP_HOOK
import com.salesforce.revoman.output.ExeType.UNMARSHALL_REQUEST
import com.salesforce.revoman.output.ExeType.UNMARSHALL_RESPONSE
import com.salesforce.revoman.output.Rundown
import com.salesforce.revoman.output.ledger.LedgerEntry
import com.salesforce.revoman.output.log.Outcome
import com.salesforce.revoman.output.log.StepEvent
import com.salesforce.revoman.output.report.Step
import com.salesforce.revoman.output.report.StepEnvVars
import com.salesforce.revoman.output.report.StepReport
import com.salesforce.revoman.output.report.StepReport.Companion.toVavr
import com.salesforce.revoman.output.report.TxnInfo
import com.squareup.moshi.Moshi
import com.squareup.moshi.adapter
import io.vavr.control.Either.left
import java.time.Duration
import org.http4k.core.Request

object ReVoman {
  @JvmStatic
  @JvmOverloads
  fun revUp(
    postExeHook: PostExeHook = PostExeHook { _, _ -> },
    dynamicEnvironment: Map<String, String> = emptyMap(),
    vararg kicks: Kick,
  ): List<Rundown> = revUp(kicks.toList(), postExeHook, dynamicEnvironment)

  @JvmStatic
  @JvmOverloads
  fun revUp(
    kicks: List<Kick>,
    postExeHook: PostExeHook = PostExeHook { _, _ -> },
    dynamicEnvironment: Map<String, String> = emptyMap(),
  ): List<Rundown> =
    kicks
      .fold(dynamicEnvironment to listOf<Rundown>()) { (accumulatedMutableEnv, rundowns), kick ->
        val rundown =
          revUp(kick.overrideDynamicEnvironment(kick.dynamicEnvironment() + accumulatedMutableEnv))
        val accumulatedRundowns = rundowns + rundown
        postExeHook.accept(rundown, accumulatedRundowns)
        rundown.mutableEnv.mutableEnvCopyWithValuesOfType<String>() to accumulatedRundowns
      }
      .second

  @JvmStatic
  @OptIn(ExperimentalStdlibApi::class)
  fun revUp(kick: Kick): Rundown {
    // BORROW the sink for this run only: install on the ThreadLocal, remove in finally. Do NOT
    // close() it — the caller OWNS the sink's lifecycle. A single caller-supplied sink commonly
    // spans MANY revUp calls (persona-creation, general-setup, the test body, cleanup); closing it
    // here would shut the writer after the first revUp and silently drop every later run's output.
    RunLogContext.install(kick.runLogSink())
    try {
      return revUpInternal(kick)
    } finally {
      RunLogContext.remove()
    }
  }

  @OptIn(ExperimentalStdlibApi::class)
  private fun revUpInternal(kick: Kick): Rundown {
    val pmTemplateAdapter = Moshi.Builder().build().adapter<Template>()
    val itemsFromPaths: List<com.salesforce.revoman.internal.postman.template.Item> =
      kick.templatePaths().flatMap { path ->
        if (isV3Collection(path)) {
          load(path)
        } else {
          pmTemplateAdapter.fromJson(bufferFile(path))?.let { (pmSteps, authFromRoot) ->
            pmSteps.map { item ->
              item.copy(request = item.request.copy(auth = item.request.auth ?: authFromRoot))
            }
          } ?: emptyList()
        }
      }
    val itemsFromStreams: List<com.salesforce.revoman.internal.postman.template.Item> =
      kick.templateInputStreams().flatMap { stream ->
        pmTemplateAdapter.fromJson(bufferInputStream(stream))?.let { (pmSteps, authFromRoot) ->
          pmSteps.map { item ->
            item.copy(request = item.request.copy(auth = item.request.auth ?: authFromRoot))
          }
        } ?: emptyList()
      }
    val pmStepsDeepFlattened = deepFlattenItems(itemsFromPaths + itemsFromStreams)
    RevomanLog.info {
      val templateCount = kick.templatePaths().size
      "Total Steps from ${if (templateCount > 1) "$templateCount Collections" else "the Collection"} provided: ${pmStepsDeepFlattened.size}"
    }
    val regexReplacer =
      RegexReplacer(kick.customDynamicVariableGenerators(), ::dynamicVariableGenerator)
    val moshiReVoman =
      initMoshi(
        kick.globalCustomTypeAdapters(),
        kick.customTypeAdaptersFromRequestConfig() + kick.customTypeAdaptersFromResponseConfig(),
        kick.globalSkipTypes(),
      )
    // Seed the ledger snapshot's produced-key values as the lowest-precedence FLOOR: a real warm
    // run satisfies the `ledgerSkipDecision` env-superset precondition only if the produced keys
    // are
    // already present in the env. `Map.plus` lets the right-hand side win on key clash, so the real
    // env (mergeEnvs) OVERRIDES the ledger values — ledger is only a fallback. For non-ledger runs
    // `kick.ledger().values` is empty (LedgerSnapshot.EMPTY), so this prepends an empty map =
    // no-op.
    val ledgerValues: Map<String, Any?> = kick.ledger().values
    val environment =
      ledgerValues +
        mergeEnvs(
          kick.environmentPaths(),
          kick.environmentInputStreams(),
          kick.dynamicEnvironment(),
        )
    val pm =
      PostmanSDK(moshiReVoman, kick.nodeModulesPath(), regexReplacer, environment.toMutableMap())
    val stepNameToReport =
      PmSandbox().use { sandbox ->
        executeStepsSerially(pmStepsDeepFlattened, kick, moshiReVoman, regexReplacer, pm, sandbox)
      }
    // --- LEDGER CAPTURE CONTRACT (what becomes a ledgered producer) ---
    // A step's `envVars` is snapshotted at the END of its fold iteration (below), AFTER its
    // post-step hooks run, so a var a step-qualified PostStepHook/PreStepHook `.set()`s IS captured
    // as produced BY that triggering step — matching the design intent that a hook-set var belongs
    // to the step that qualified the hook. Two writes are intentionally NOT captured: (1) the
    // delegated index-set `mutableEnv[k]=` (the same bypass the warm-skip inject uses, so reused
    // keys are not re-recorded as produced), and (2) a var set in the collection-level PostExeHook,
    // which fires in the OUTER kick-fold AFTER this learnedLedger is already frozen — it has no
    // single triggering step, so it is excluded rather than mis-attributed. Hook producers that
    // want to be ledgered must use `pm.environment.set(...)` from a step-qualified hook.
    val learnedLedger =
      stepNameToReport
        .filter { it.envVars.produced.isNotEmpty() }
        .associate {
          it.step.path to LedgerEntry(it.envVars.produced, it.step.sourceHash, it.envVars.consumed)
        }
    return Rundown(
      stepNameToReport,
      pm.environment,
      kick.haltOnFailureOfTypeExcept(),
      pmStepsDeepFlattened.size,
      learnedLedger,
    )
  }

  private fun executeStepsSerially(
    pmStepsFlattened: List<Step>,
    kick: Kick,
    moshiReVoman: MoshiReVoman,
    regexReplacer: RegexReplacer,
    pm: PostmanSDK,
    sandbox: PmSandbox,
  ): List<StepReport> {
    var haltExecution = false
    val pickedSteps =
      pmStepsFlattened.filter { shouldStepBePicked(it, kick.runOnlySteps(), kick.skipSteps()) }
    // Collision guard (computed once over the picked steps in execution order): a key produced by
    // >1 step is only safely ledger-skippable at its LAST producer. Earlier producers of a re-set
    // key must always run, else a skipped earlier step would be injected with the LATER value and
    // an intermediate consumer of the earlier value would read it wrong. Empty for collision-free
    // collections (every key produced once) — zero behavior change there.
    val shadowedPaths = shadowedProducerPaths(pickedSteps, kick.ledger())
    return pickedSteps
      .asSequence()
      .takeWhile { !haltExecution }
      .fold(listOf<StepReport>()) { stepReports, step ->
        pm.environment.currentStep = step
        // --------### LEDGER WARM-PATH: skip+inject / warn-and-run ###--------
        val ledger = kick.ledger()
        val entry = ledger.steps[step.path]
        val envKeys = pm.environment.keys
        if (
          step.path !in shadowedPaths &&
            ledgerSkipDecision(step, ledger, envKeys, kick.ledgerOptOutSteps())
        ) {
          val skipEntry = entry!!
          RevomanLog.info { "***** Ledger-skip Step (reusing ${skipEntry.produces}): $step *****" }
          RevomanLog.event(StepEvent.LedgerSkipped(step.path, skipEntry.produces))
          // Inject ledgered values via the delegated index-set (NOT `set()`), so the reused keys
          // are NOT recorded as "produced" by this skipped step in the live per-step capture. A
          // produced key absent from `ledger.values` (partial/corrupt ledger) is NOT injected as a
          // silent null (which would stringify to "null" downstream): warn and leave the existing
          // env value, which the env-superset precondition guarantees is present.
          skipEntry.produces.forEach { key ->
            if (ledger.values.containsKey(key)) {
              pm.environment[key] = ledger.values[key]
            } else {
              RevomanLog.warn {
                "[ledger] ${step.path} reuses produced key '$key' but it is missing from " +
                  "ledger.values -> keeping existing env value, not injecting null"
              }
            }
          }
          return@fold stepReports +
            StepReport.ledgerSkipped(step, skipEntry.produces, pm.environment, skipEntry.consumed)
        }
        if (
          entry != null &&
            entry.produces.isNotEmpty() &&
            entry.hash.isNotEmpty() &&
            step.sourceHash.isNotEmpty() &&
            entry.hash != step.sourceHash &&
            envKeys.containsAll(entry.produces)
        ) {
          RevomanLog.warn {
            "[ledger] stale: ${step.path} producer def changed " +
              "(hash ${entry.hash} -> ${step.sourceHash}) -> running step, refreshing entry"
          }
        }
        RevomanLog.info { "***** Executing Step: $step *****" }
        RevomanLog.event(StepEvent.StepStarted(step.path, step.name))
        val exeTimings: MutableMap<ExeType, Duration> = mutableMapOf()
        val itemWithRegex = step.rawPMStep
        val preStepReport =
          StepReport(
            step = step,
            requestInfo =
              Right(
                TxnInfo(
                  httpMsg = itemWithRegex.request.toHttpRequest(null),
                  moshiReVoman = moshiReVoman,
                )
              ),
            pmEnvSnapshot = pm.environment,
          )
        pm.info = Info(step.name)
        pm.currentStepReport = preStepReport
        pm.rundown =
          Rundown(
            stepReports + preStepReport,
            pm.environment,
            kick.haltOnFailureOfTypeExcept(),
            pmStepsFlattened.size,
          )
        pm.environment.putAll(regexReplacer.replaceVariablesInEnv(pm))
        val currentStepReport: StepReport = // --------### PRE-REQ-JS ###--------
          timed(step, exeTimings, PRE_REQ_JS) {
              executePreReqJS(step, itemWithRegex, preStepReport, pm, sandbox)
            }
            .mapLeft { preStepReport.copy(requestInfo = left(it)) }
            .flatMap { // --------### UNMARSHALL-REQUEST ###--------
              timed(step, exeTimings, UNMARSHALL_REQUEST) {
                  val pmRequest =
                    regexReplacer.replaceVariablesInRequestRecursively(itemWithRegex.request, pm)
                  unmarshallRequest(step, pmRequest, kick, moshiReVoman, pm.rundown)
                }
                .mapLeft { preStepReport.copy(requestInfo = left(it)) }
            }
            .flatMap { requestInfo: TxnInfo<Request> -> // --------### PRE-HOOKS ###--------
              timed(step, exeTimings, PRE_STEP_HOOK) {
                  preStepHookExe(step, kick, requestInfo, pm.rundown)
                }
                ?.let {
                  Left(
                    preStepReport.copy(
                      requestInfo = Right(requestInfo).toVavr(),
                      preStepHookFailure = it,
                    )
                  )
                } ?: Right(preStepReport.copy(requestInfo = Right(requestInfo).toVavr()))
            }
            .flatMap { sr: StepReport -> // --------### HTTP-REQUEST ###--------
              pm.syncProgress(sr)
              // * NOTE 15 Mar 2025 gopala.akshintala: Replace again to accommodate variables set by
              // PRE-REQ-JS
              val item = regexReplacer.replaceVariablesInPmItem(itemWithRegex, pm)
              val httpRequest = item.request.toHttpRequest(moshiReVoman)
              timed(step, exeTimings, HTTP_REQUEST) {
                  fireHttpRequest(step, httpRequest, kick.insecureHttp(), moshiReVoman)
                }
                .mapLeft { sr.copy(requestInfo = Left(it).toVavr()) }
                .map {
                  sr.copy(
                    requestInfo =
                      sr.requestInfo?.map { txnInfo -> txnInfo.copy(httpMsg = httpRequest) },
                    responseInfo = Right(it).toVavr(),
                  )
                }
            }
            .flatMap { sr: StepReport -> // --------### POST-RES-JS ###--------
              pm.syncProgress(sr)
              timed(step, exeTimings, POST_RES_JS) {
                  executePostResJS(step, itemWithRegex, sr, pm, sandbox)
                }
                .mapLeft { sr.copy(responseInfo = left(it)) }
                .map { sr }
            }
            .flatMap { sr: StepReport -> // ---### UNMARSHALL RESPONSE ###---
              timed(step, exeTimings, UNMARSHALL_RESPONSE) {
                  unmarshallResponse(kick, moshiReVoman, sr, pm.rundown)
                }
                .mapLeft { sr.copy(responseInfo = Left(it).toVavr()) }
                .map { sr.copy(responseInfo = Right(it).toVavr()) }
            }
            .map { sr: StepReport -> // --------### POST-HOOKS ###--------
              pm.syncProgress(sr)
              val postHookFailure =
                timed(step, exeTimings, POST_STEP_HOOK) { postStepHookExe(kick, sr, pm.rundown) }
              sr.copy(postStepHookFailure = postHookFailure)
            }
            .flatMap { sr: StepReport -> // --------### POLLING ###--------
              timed(step, exeTimings, POLLING) {
                  executePolling(kick.pollingConfig(), sr, pm.rundown, pm, kick.insecureHttp())
                }
                .mapLeft { sr.copy(pollingFailure = it) }
                .map { pollingReport -> pollingReport?.let { sr.copy(pollingReport = it) } ?: sr }
            }
            .merge()
            .copy(
              exeTimings = exeTimings,
              pmEnvSnapshot =
                pm.environment.copy(mutableEnv = pm.environment.mutableEnv.toMutableMap()),
              envVars =
                StepEnvVars(
                  produced = pm.environment.producedKeysFor(step),
                  consumed = pm.environment.consumedKeysFor(step),
                ),
            )
        haltExecution = shouldHaltExecution(currentStepReport, kick, pm.rundown)
        val captureForSink = RunLogContext.hasActiveSink()
        RevomanLog.event(
          StepEvent.StepFinished(
            path = step.path,
            httpStatus =
              if (currentStepReport.responseInfo != null && currentStepReport.responseInfo.isRight)
                currentStepReport.responseInfo.get().httpMsg.status.code
              else null,
            produced = currentStepReport.envVars.produced,
            consumed = currentStepReport.envVars.consumed,
            tookMs = currentStepReport.exeTimings.values.sumOf { it.toMillis() },
            outcome = if (currentStepReport.isSuccessful) Outcome.SUCCESS else Outcome.FAILED,
            requestMsg =
              if (
                captureForSink &&
                  currentStepReport.requestInfo != null &&
                  currentStepReport.requestInfo.isRight
              )
                renderHttpMsg(currentStepReport.requestInfo.get().httpMsg)
              else null,
            responseMsg =
              if (
                captureForSink &&
                  currentStepReport.responseInfo != null &&
                  currentStepReport.responseInfo.isRight
              )
                renderHttpMsg(currentStepReport.responseInfo.get().httpMsg)
              else null,
            producedValues =
              if (captureForSink)
                currentStepReport.envVars.produced.associateWith {
                  currentStepReport.pmEnvSnapshot[it]?.toString()
                }
              else emptyMap(),
            consumedValues =
              if (captureForSink)
                currentStepReport.envVars.consumed.associateWith {
                  currentStepReport.pmEnvSnapshot[it]?.toString()
                }
              else emptyMap(),
          )
        )
        stepReports + currentStepReport
      }
  }
}
