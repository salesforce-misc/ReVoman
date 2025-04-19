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
import com.salesforce.revoman.internal.exe.deepFlattenItems
import com.salesforce.revoman.internal.exe.executePostResJS
import com.salesforce.revoman.internal.exe.executePreReqJS
import com.salesforce.revoman.internal.exe.fireHttpRequest
import com.salesforce.revoman.internal.exe.postStepHookExe
import com.salesforce.revoman.internal.exe.preStepHookExe
import com.salesforce.revoman.internal.exe.shouldHaltExecution
import com.salesforce.revoman.internal.exe.shouldStepBePicked
import com.salesforce.revoman.internal.exe.unmarshallRequest
import com.salesforce.revoman.internal.exe.unmarshallResponse
import com.salesforce.revoman.internal.json.MoshiReVoman
import com.salesforce.revoman.internal.json.MoshiReVoman.Companion.initMoshi
import com.salesforce.revoman.internal.postman.Info
import com.salesforce.revoman.internal.postman.PostmanSDK
import com.salesforce.revoman.internal.postman.RegexReplacer
import com.salesforce.revoman.internal.postman.dynamicVariableGenerator
import com.salesforce.revoman.internal.postman.template.Environment.Companion.mergeEnvs
import com.salesforce.revoman.internal.postman.template.Event
import com.salesforce.revoman.internal.postman.template.Template
import com.salesforce.revoman.output.Rundown
import com.salesforce.revoman.output.report.PostmanCollectionGraph
import com.salesforce.revoman.output.report.PostmanCollectionGraph.Edge
import com.salesforce.revoman.output.report.PostmanCollectionGraph.Node
import com.salesforce.revoman.output.report.Step
import com.salesforce.revoman.output.report.StepReport
import com.salesforce.revoman.output.report.StepReport.Companion.toVavr
import com.salesforce.revoman.output.report.TxnInfo
import com.squareup.moshi.Moshi
import com.squareup.moshi.adapter
import io.github.oshai.kotlinlogging.KotlinLogging
import io.vavr.control.Either.left
import java.io.InputStream
import java.util.*
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
          revUp(
            kick.overrideDynamicEnvironments(
              kick.dynamicEnvironmentsFlattened(),
              accumulatedMutableEnv,
            )
          )
        val accumulatedRundowns = rundowns + rundown
        postExeHook.accept(rundown, accumulatedRundowns)
        rundown.mutableEnv.mutableEnvCopyWithValuesOfType<String>() to accumulatedRundowns
      }
      .second

  @JvmStatic
  @OptIn(ExperimentalStdlibApi::class)
  fun revUp(kick: Kick): Rundown {
    val pmStepsDeepFlattened = deepFlattenPmSteps(kick.templatePaths(), kick.templateInputStreams())
    val regexReplacer =
      RegexReplacer(kick.customDynamicVariableGenerators(), ::dynamicVariableGenerator)
    val moshiReVoman =
      initMoshi(
        kick.globalCustomTypeAdapters(),
        kick.customTypeAdaptersFromRequestConfig() + kick.customTypeAdaptersFromResponseConfig(),
        kick.globalSkipTypes(),
      )
    val mergedEnvironment =
      mergeEnvs(
        kick.environmentPaths(),
        kick.environmentInputStreams(),
        kick.dynamicEnvironmentsFlattened(),
      )
    val pm =
      PostmanSDK(
        moshiReVoman,
        kick.nodeModulesPath(),
        regexReplacer,
        mergedEnvironment.toMutableMap(),
      )
    val stepNameToReport =
      executeStepsSerially(pmStepsDeepFlattened, kick, moshiReVoman, regexReplacer, pm)
    return Rundown(
      stepNameToReport,
      pm.environment,
      kick.haltOnFailureOfTypeExcept(),
      pmStepsDeepFlattened.size,
    )
  }

  @OptIn(ExperimentalStdlibApi::class)
  private fun deepFlattenPmSteps(
    templatePaths: List<String>,
    templateInputStreams: List<InputStream>,
  ): List<Step> {
    val pmTemplateAdapter = Moshi.Builder().build().adapter<Template>()
    val templateBuffers =
      templatePaths.map { bufferFile(it) } + templateInputStreams.map { bufferInputStream(it) }
    val pmStepsDeepFlattened =
      templateBuffers
        .asSequence()
        .mapNotNull { pmTemplateAdapter.fromJson(it) }
        .flatMap { (_, pmSteps, authFromRoot) ->
          deepFlattenItems(
            pmSteps.map { item ->
              item.copy(request = item.request.copy(auth = item.request.auth ?: authFromRoot))
            }
          )
        }
        .toList()
    logger.info {
      val templateCount = templatePaths.size
      "Total Steps from ${if (templateCount > 1) "$templateCount Collections" else "the Collection"} provided: ${pmStepsDeepFlattened.size}"
    }
    return pmStepsDeepFlattened
  }

  private fun executeStepsSerially(
    pmStepsFlattened: List<Step>,
    kick: Kick,
    moshiReVoman: MoshiReVoman,
    regexReplacer: RegexReplacer,
    pm: PostmanSDK,
  ): List<StepReport> {
    var haltExecution = false
    return pmStepsFlattened
      .asSequence()
      .takeWhile { !haltExecution }
      .filter { shouldStepBePicked(it, kick.runOnlySteps(), kick.skipSteps()) }
      .fold(listOf()) { stepReports, step ->
        logger.info { "***** Executing Step: $step *****" }
        pm.environment.currentStep = step
        val itemWithRegex = step.rawPmStep
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
          executePreReqJS(step, itemWithRegex, pm)
            .mapLeft { preStepReport.copy(requestInfo = left(it)) }
            .flatMap { // --------### UNMARSHALL-REQUEST ###--------
              val pmRequest =
                regexReplacer.replaceVariablesInRequestRecursively(itemWithRegex.request, pm)
              unmarshallRequest(step, pmRequest, kick, moshiReVoman, pm).mapLeft {
                preStepReport.copy(requestInfo = left(it))
              }
            }
            .flatMap { requestInfo: TxnInfo<Request> -> // --------### PRE-HOOKS ###--------
              preStepHookExe(step, kick, requestInfo, pm)?.let {
                Left(
                  preStepReport.copy(
                    requestInfo = Right(requestInfo).toVavr(),
                    preStepHookFailure = it,
                  )
                )
              } ?: Right(preStepReport.copy(requestInfo = Right(requestInfo).toVavr()))
            }
            .flatMap { sr: StepReport -> // --------### HTTP-REQUEST ###--------
              pm.currentStepReport = sr
              pm.rundown = pm.rundown.copy(stepReports = pm.rundown.stepReports + sr)
              // * NOTE 15 Mar 2025 gopala.akshintala: Replace again to accommodate variables set by
              // PRE-REQ-JS
              val item = regexReplacer.replaceVariablesInPmItem(itemWithRegex, pm)
              val httpRequest = item.request.toHttpRequest(moshiReVoman)
              fireHttpRequest(
                  step,
                  item.request.auth,
                  httpRequest,
                  kick.insecureHttp(),
                  moshiReVoman,
                )
                .mapLeft { sr.copy(requestInfo = Left(it).toVavr()) }
                .map {
                  sr.copy(
                    requestInfo =
                      sr.requestInfo?.map { txnInfo -> txnInfo.copy(httpMsg = httpRequest) },
                    responseInfo = Right(it).toVavr(),
                  )
                }
            }
            .flatMap { sr: StepReport -> // --------### PRE-RES-JS ###--------
              pm.currentStepReport = sr
              pm.rundown = pm.rundown.copy(stepReports = pm.rundown.stepReports + sr)
              executePostResJS(step, itemWithRegex, pm)
                .mapLeft { sr.copy(responseInfo = left(it)) }
                .map { sr }
            }
            .flatMap { sr: StepReport -> // ---### UNMARSHALL RESPONSE ###---
              unmarshallResponse(kick, moshiReVoman, pm)
                .mapLeft { sr.copy(responseInfo = Left(it).toVavr()) }
                .map { sr.copy(responseInfo = Right(it).toVavr()) }
            }
            .map { sr: StepReport -> // --------### POST-HOOKS ###--------
              pm.currentStepReport = sr
              pm.rundown = pm.rundown.copy(stepReports = pm.rundown.stepReports + sr)
              sr.copy(postStepHookFailure = postStepHookExe(kick, pm))
            }
            .merge()
            .copy(
              pmEnvSnapshot =
                pm.environment.copy(mutableEnv = pm.environment.mutableEnv.toMutableMap())
            )
        haltExecution = shouldHaltExecution(currentStepReport, kick, pm.rundown)
        stepReports + currentStepReport
      }
  }

  fun buildDepGraph(kick: Kick): PostmanCollectionGraph {
    val pmStepsDeepFlattened = deepFlattenPmSteps(kick.templatePaths(), kick.templateInputStreams())
    val nodes =
      pmStepsDeepFlattened.map { step ->
        val setsVariables = step.rawPmStep.event?.let { findSetVariables(it) } ?: emptySet()
        // ! TODO 18 Apr 2025 gopala.akshintala: Use other attributes also like header
        val usesVariables =
          findUsedVariables(step.rawPmStep.request.url.raw) +
            findUsedVariables(step.rawPmStep.request.body?.raw)
        Node(step.index, step.rawPmStep, setsVariables, usesVariables)
      }
    val edges = mutableListOf<Edge>()

    // ! TODO 18 Apr 2025 gopala.akshintala: Optimize
    for (targetNode in nodes) {
      for (useVar in targetNode.uses) {
        for (sourceNode in nodes) {
          if (sourceNode.index != targetNode.index && sourceNode.sets.contains(useVar)) {
            edges.add(Edge(sourceNode, targetNode, useVar))
          }
        }
      }
    }
    val variableChains = mutableMapOf<String, List<Node>>()
    edges.forEach { edge ->
      val chain = mutableListOf(edge.source, edge.target)
      var currentEdge = edge
      while (true) {
        val nextEdge = edges.find { it.target == currentEdge.source }
        if (nextEdge == null) break
        chain.add(0, nextEdge.source)
        currentEdge = nextEdge
      }
      variableChains[edge.connectingVariable] = chain
    }
    val varToTemplate =
      variableChains.mapValues { (variable, nodesForVar) ->
        Template(
          com.salesforce.revoman.internal.postman.template.Info(
            UUID.randomUUID().toString(),
            "postman-collection-for-$variable",
            "https://schema.getpostman.com/json/collection/v2.1.0/collection.json",
            "23827434",
          ),
          nodesForVar.map { it.item },
        )
      }
    return PostmanCollectionGraph(
      "Postman Collection dependency Graph based on how environment variables are set and used",
      varToTemplate,
    )
  }

  private val setVariableRegex =
    """(?:pm\.environment\.set|postman\.setEnvironmentVariable)\s*\(\s*["']([^"']+)["']\s*,"""
      .toRegex()
  private val useVariableRegex = """\{\{([^}]+)}}""".toRegex()

  private fun findSetVariables(events: List<Event>): Set<String> {
    val variables = mutableSetOf<String>()
    events
      .filter { it.listen == "test" }
      .flatMap { it.script.exec }
      .forEach { line ->
        setVariableRegex.findAll(line).forEach { matchResult ->
          if (matchResult.groupValues.size > 1) {
            variables.add(matchResult.groupValues[1])
          }
        }
      }
    return variables
  }

  private fun findUsedVariables(body: String?): Set<String> {
    if (body == null) return emptySet()
    val variables = mutableSetOf<String>()
    useVariableRegex.findAll(body).forEach { matchResult ->
      if (matchResult.groupValues.size > 1) {
        variables.add(matchResult.groupValues[1])
      }
    }
    return variables
  }
}

private val logger = KotlinLogging.logger {}
