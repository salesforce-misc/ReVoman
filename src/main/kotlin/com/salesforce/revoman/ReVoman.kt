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
import com.salesforce.revoman.output.postman.PostmanEnvironment
import com.salesforce.revoman.output.report.PostmanCollectionGraph
import com.salesforce.revoman.output.report.PostmanCollectionGraph.Edge
import com.salesforce.revoman.output.report.PostmanCollectionGraph.Node
import com.salesforce.revoman.output.report.Step
import com.salesforce.revoman.output.report.StepReport
import com.salesforce.revoman.output.report.StepReport.Companion.toVavr
import com.salesforce.revoman.output.report.TxnInfo
import com.squareup.moshi.Moshi
import com.squareup.moshi.adapter
import io.exoquery.pprint
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
    return execute(kick, pmStepsDeepFlattened)
  }

  private fun execute(kick: Kick, pmStepsDeepFlattened: List<Step>): Rundown {
    val mergedEnvironment: MutableMap<String, Any?> =
      mergeEnvs(
          kick.environmentPaths(),
          kick.environmentInputStreams(),
          kick.dynamicEnvironmentsFlattened(),
        )
        .toMutableMap()
    val regexReplacer =
      RegexReplacer(kick.customDynamicVariableGenerators(), ::dynamicVariableGenerator)
    val moshiReVoman =
      initMoshi(
        kick.globalCustomTypeAdapters(),
        kick.customTypeAdaptersFromRequestConfig() + kick.customTypeAdaptersFromResponseConfig(),
        kick.globalSkipTypes(),
      )
    val pm = PostmanSDK(moshiReVoman, kick.nodeModulesPath(), regexReplacer, mergedEnvironment)
    val stepNameToReport =
      executeStepsSerially(pmStepsDeepFlattened, kick, moshiReVoman, regexReplacer, pm)
    val rundown =
      Rundown(
        stepNameToReport,
        pm.environment,
        kick.haltOnFailureOfTypeExcept(),
        pmStepsDeepFlattened.size,
        moshiReVoman,
      )
    logger.info { "🏁Execution finished. Stats:\n ${pprint(rundown.stats.toJson())}" }
    return rundown
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
      "Total Steps from ${if (templateCount > 1) "$templateCount Collections" else "the Collection"} provided: ${pprint(pmStepsDeepFlattened.size)}"
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
            moshiReVoman,
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

  fun exeChain(variableName: String, kick: Kick): List<Node> {
    val nodes = itemsAsNodes(kick)
    val executionChain = mutableListOf<Node>()
    for (node in nodes) {
      executionChain.add(node)
      if (variableName in node.setsVariables) {
        return executionChain
      }
    }
    return emptyList()
  }

  fun queryChainForVariable(variableName: String, kick: Kick): PostmanCollectionGraph {
    val exeChain = exeChain(variableName, kick)
    check(exeChain.isNotEmpty()) { "Variable $variableName not found in any node's setsVariables" }
    return PostmanCollectionGraph(
      "Execution chain to set variable: $variableName",
      mapOf(
        variableName to
          Template(
            com.salesforce.revoman.internal.postman.template.Info(
              UUID.randomUUID().toString(),
              "postman-collection-for-$variableName",
              "https://schema.getpostman.com/json/collection/v2.1.0/collection.json",
              "23827434",
            ),
            exeChain.map { it.step.rawPmStep },
          )
      ),
    )
  }

  fun exeChainForVariable(variableName: String, kick: Kick): PostmanEnvironment<Any?> {
    val exeChain = exeChain(variableName, kick)
    check(exeChain.isNotEmpty()) { "Variable $variableName not found in any node's setsVariables" }
    val rundown = execute(kick, exeChain.map { it.step })
    return rundown.mutableEnv
  }

  private fun diffExeChain(prevVariableName: String, variableName: String, kick: Kick): List<Node> {
    val nodes = itemsAsNodes(kick)
    return nodes
      .dropWhile { prevVariableName !in it.usesVariables }
      .takeWhile { variableName !in it.setsVariables }
      .let { chain ->
        if (chain.isNotEmpty() && variableName in chain.last().setsVariables) chain else emptyList()
      }
  }

  fun diffExeChainForVariable(
    prevVariableName: String,
    variableName: String,
    kick: Kick,
  ): PostmanEnvironment<Any?> {
    val exeChain = diffExeChain(prevVariableName, variableName, kick)
    check(exeChain.isNotEmpty()) { "Variable $variableName not found in any node's setsVariables" }
    val rundown = execute(kick, exeChain.map { it.step })
    return rundown.mutableEnv
  }

  fun findMissingUsesVariables(kick: Kick): List<String> {
    val nodes = itemsAsNodes(kick)
    val allSetVariables = nodes.flatMap { it.setsVariables }.toSet()
    return nodes
      .flatMap { node -> node.usesVariables.filter { variable -> variable !in allSetVariables } }
      .distinct()
  }

  fun buildDepGraph(kick: Kick): PostmanCollectionGraph {
    val nodes = itemsAsNodes(kick)
    val edges =
      nodes.flatMap { node1 ->
        nodes.flatMap { node2 ->
          node1.usesVariables.intersect(node2.setsVariables).map { Edge(node2, node1, it) }
        }
      }
    val edgeGroups: Map<String, Pair<Node, List<Node>>> =
      edges
        .groupBy { it.connectingVariable to it.source }
        .mapValues { (_, edges) -> edges.map { it.target } }
        .map { (key, value) -> key.first to (key.second to value) }
        .toMap()

    val variableToTemplate =
      edgeGroups.entries
        .associate { (key, value) -> key to exeChainBfs(key, edgeGroups) + value.first }
        .mapValues { (variable, node) ->
          Template(
            com.salesforce.revoman.internal.postman.template.Info(
              UUID.randomUUID().toString(),
              "postman-collection-for-$variable",
              "https://schema.getpostman.com/json/collection/v2.1.0/collection.json",
              "23827434",
            ),
            node.map { it.step.rawPmStep },
          )
        }

    return PostmanCollectionGraph(
      "A map of variable to Postman collection template to execute to set that variable",
      variableToTemplate,
    )
  }

  private fun itemsAsNodes(kick: Kick): List<Node> {
    val pmStepsDeepFlattened = deepFlattenPmSteps(kick.templatePaths(), kick.templateInputStreams())
    val nodes =
      pmStepsDeepFlattened.map { step ->
        val setsVariables = step.rawPmStep.event?.let { findSetVariables(it) } ?: emptySet()
        // ! TODO 18 Apr 2025 gopala.akshintala: Use other attributes also like header
        val usesVariables =
          findUsedVariables(step.rawPmStep.request.url.raw) +
            findUsedVariables(step.rawPmStep.request.body?.raw)
        Node(step, setsVariables, usesVariables)
      }
    return nodes
  }

  private fun exeChainBfs(
    variable: String,
    edgeGroups: Map<String, Pair<Node, List<Node>>>,
  ): List<Node> {
    val (varSetNode, _) = edgeGroups[variable] ?: return emptyList()
    val levelNodes =
      edgeGroups.filterKeys { it in varSetNode.usesVariables }.values.map { it.first }.distinct()
    return (levelNodes.flatMap {
        it.usesVariables.flatMap { useVar -> exeChainBfs(useVar, edgeGroups) }
      } + levelNodes)
      .distinct()
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
