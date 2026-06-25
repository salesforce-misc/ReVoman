/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.internal.postman.sandbox

import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.Base64
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.Value
import org.graalvm.polyglot.proxy.ProxyArray
import org.graalvm.polyglot.proxy.ProxyExecutable
import org.graalvm.polyglot.proxy.ProxyObject

/**
 * Owns a single GraalJS [Context] running Postman's real sandbox bootcode, driven via the uvm
 * bridge protocol. Single-threaded: all eval + loop draining happen on the calling thread.
 *
 * Boot installs browser-global shims (timers via [SandboxEventLoop], atob/btoa, Blob/File/etc.),
 * captures the guest `bridge` Value before the bootcode deletes it, evals the bootcode, and sends
 * `initialize`. Each [dispatchExecute] emits an `execute` event, drains the loop until the sandbox
 * dispatches the terminal `execution.result.<id>`, and decodes the collected guest emits into a
 * [PmExecutionResult].
 */
internal class SandboxBridge {
  private lateinit var ctx: Context
  private lateinit var guestBridge: Value
  private val loop = SandboxEventLoop()
  private val emits = mutableListOf<String>() // raw Flatted strings, guest -> host

  // Lifecycle flags: a single instance boots once and closes once.
  private var booted = false
  private var closed = false

  fun boot() {
    check(!booted) { "sandbox: boot() already called" }
    // Set immediately so a re-entrant/double boot fails fast. Note: a failed boot is terminal for
    // this instance (booted stays true) — discard it and create a fresh SandboxBridge to retry.
    booted = true
    ctx =
      Context.newBuilder("js")
        .allowExperimentalOptions(true)
        .option("js.esm-eval-returns-exports", "true")
        .option("js.ecmascript-version", "2024")
        .allowHostAccess(HostAccess.ALL)
        .allowHostClassLookup { true }
        .build()
    val bindings = ctx.getBindings("js")

    bindings.putMember(
      "__java_setTimer",
      ProxyExecutable { args ->
        val fn = args[0]
        val delay = if (args.size > 1 && args[1].fitsInLong()) args[1].asLong() else 0L
        val extra = if (args.size > 2) args.copyOfRange(2, args.size) else emptyArray()
        loop.schedule({ fn.executeVoid(*extra) }, delay)
      },
    )
    bindings.putMember(
      "__java_clearTimer",
      ProxyExecutable { args ->
        if (args.isNotEmpty() && args[0].fitsInLong()) loop.clear(args[0].asLong())
        null
      },
    )
    bindings.putMember(
      "__java_emit",
      ProxyExecutable { args ->
        emits.add(args[0].asString())
        null
      },
    )
    bindings.putMember(
      "__java_btoa",
      ProxyExecutable { args ->
        Base64.getEncoder().encodeToString(args[0].asString().toByteArray(Charsets.ISO_8859_1))
      },
    )
    bindings.putMember(
      "__java_atob",
      ProxyExecutable { args ->
        String(Base64.getDecoder().decode(args[0].asString()), Charsets.ISO_8859_1)
      },
    )

    ctx.eval(
      "js",
      """
      (function (jSet, jClear, jEmit, jAtob, jBtoa) {
        globalThis.setTimeout = function (fn, d) { return jSet(fn, d | 0, ...Array.prototype.slice.call(arguments, 2)); };
        globalThis.clearTimeout = function (id) { return jClear(id); };
        globalThis.setInterval = function (fn, d) { return jSet(fn, d | 0, ...Array.prototype.slice.call(arguments, 2)); };
        globalThis.clearInterval = function (id) { return jClear(id); };
        globalThis.setImmediate = function (fn) { return jSet(fn, 0, ...Array.prototype.slice.call(arguments, 1)); };
        globalThis.clearImmediate = function (id) { return jClear(id); };
        globalThis.queueMicrotask = function (fn) { return jSet(fn, 0); };
        globalThis.__uvm_emit = function (s) { jEmit(s); };
        globalThis.__uvm_setTimeout = globalThis.setTimeout;
        globalThis.Blob = globalThis.Blob || function Blob() {};
        globalThis.File = globalThis.File || function File() {};
        globalThis.FileReader = globalThis.FileReader || function FileReader() {};
        globalThis.FormData = globalThis.FormData || function FormData() {};
        globalThis.atob = function (s) { return jAtob(s); };
        globalThis.btoa = function (s) { return jBtoa(s); };
      })(__java_setTimer, __java_clearTimer, __java_emit, __java_atob, __java_btoa);
      ${SandboxResources.bridgeClient}
      """
        .trimIndent(),
    )

    guestBridge = bindings.getMember("bridge")
    check(!guestBridge.isNull) { "sandbox: no global bridge after bridge-client" }

    ctx.eval(Source.newBuilder("js", SandboxResources.bootcode, "bootcode.js").build())
    loop.run()

    guestBridge.invokeMember("emit", "initialize", ProxyObject.fromMap(HashMap<String, Any?>()))
    loop.run()
    logger.info { "Postman sandbox booted (postman-sandbox ${SandboxResources.version})" }
  }

  fun dispatchExecute(
    id: String,
    script: String,
    target: ScriptTarget,
    context: PmExecutionContext,
    timeoutMs: Long,
  ): PmExecutionResult {
    emits.clear()

    val event: ProxyObject =
      ProxyObject.fromMap(
        linkedMapOf<String, Any?>(
          "listen" to target.listen,
          "script" to
            ProxyObject.fromMap(
              linkedMapOf<String, Any?>(
                "type" to "text/javascript",
                "exec" to ProxyArray.fromArray(script),
              )
            ),
        )
      )
    val ctxObj: ProxyObject =
      ProxyObject.fromMap(
        linkedMapOf<String, Any?>(
            "environment" to scopeToProxy(context.environment),
            "globals" to scopeToProxy(context.globals),
            "collectionVariables" to scopeToProxy(context.collectionVariables),
          )
          .also { m ->
            context.request?.let { m["request"] = ProxyObject.fromMap(it) }
            context.response?.let { m["response"] = ProxyObject.fromMap(it) }
          }
      )
    val options: ProxyObject =
      ProxyObject.fromMap(
        linkedMapOf<String, Any?>(
          // Phase 1: timeoutMs is forwarded to the guest but enforced in VIRTUAL time (timers run
          // on the virtual-time SandboxEventLoop), so it does NOT bound host wall-clock/CPU time.
          // SandboxEventLoop's RUNAWAY_BACKSTOP guards async runaways; real wall-clock timeout
          // enforcement is Phase 2 (when real I/O exists).
          "timeout" to timeoutMs,
          "cursor" to ProxyObject.fromMap(HashMap<String, Any?>()),
          "allowSkipRequest" to (target == ScriptTarget.PRE_REQUEST),
        )
      )

    guestBridge.invokeMember("emit", "execute", id, event, ctxObj, options)
    loop.run()

    return decodeResult(id)
  }

  fun close() {
    if (closed) return
    if (::ctx.isInitialized) ctx.close(true)
    closed = true
  }

  private fun scopeToProxy(scope: PmScope): ProxyObject =
    ProxyObject.fromMap(
      linkedMapOf<String, Any?>(
          "id" to scope.id,
          "values" to
            ProxyArray.fromList(
              scope.values.map { (k, v) ->
                ProxyObject.fromMap(linkedMapOf<String, Any?>("key" to k, "value" to v))
              }
            ),
        )
        // postman-collection's VariableScope carries an optional `name`; forwarding it makes
        // `pm.environment.name` (and other named scopes) readable from scripts. Omit when null so
        // unnamed scopes stay unnamed rather than becoming the string "null".
        .also { m -> scope.name?.let { m["name"] = it } }
    )

  private fun decodeResult(id: String): PmExecutionResult {
    val assertions = mutableListOf<PmAssertion>()
    var error: Throwable? = null
    var environment: Map<String, Any?> = emptyMap()
    var globals: Map<String, Any?> = emptyMap()
    var collectionVariables: Map<String, Any?> = emptyMap()
    var nextRequest: String? = null
    var skipRequest = false

    for (raw in emits) {
      val parsed = Flatted.parse(raw) as? List<*> ?: continue
      val name = parsed.firstOrNull() as? String ?: continue
      when (name) {
        "execution.assertion.$id" -> {
          (parsed.lastOrNull() as? List<*>)?.forEach { a ->
            val m = a as? Map<*, *> ?: return@forEach
            assertions.add(
              PmAssertion(
                name = m["name"] as? String ?: "",
                index = (m["index"] as? Number)?.toInt() ?: 0,
                passed = m["passed"] as? Boolean ?: false,
                skipped = m["skipped"] as? Boolean ?: false,
                error =
                  (m["error"] as? Map<*, *>)?.get("message") as? String ?: m["error"] as? String,
              )
            )
          }
        }
        "execution.skipRequest.$id" -> skipRequest = true
        "execution.error.$id" -> {
          val errObj = parsed.getOrNull(2)
          val errMap = errObj as? Map<*, *>
          val msg = errMap?.get("message") as? String ?: errObj?.toString() ?: "sandbox error"
          val stack = errMap?.get("stack") as? String
          error = RuntimeException(if (stack != null) "$msg\n$stack" else msg)
        }
        // Phase 1: pm.sendRequest dispatches execution.request.<id> expecting a host HTTP
        // responder.
        // None is wired yet, so the script's await never resumes and no execution.result arrives.
        // Surface a crisp, intentional error instead of silently returning empty scopes. Wiring the
        // http4k responder is Phase 2.
        "execution.request.$id" ->
          error = UnsupportedOperationException("pm.sendRequest is not supported yet (Phase 2)")
        "execution.result.$id" -> {
          val execution = parsed.getOrNull(2) as? Map<*, *> ?: continue
          environment = scopeValues(execution["environment"])
          globals = scopeValues(execution["globals"])
          collectionVariables = scopeValues(execution["collectionVariables"])
          // `pm.execution.setNextRequest(name)` writes `execution.return.nextRequest`. Capture it
          // as
          // a control-flow directive; the step sequencer consumes it in Phase 2.
          nextRequest = (execution["return"] as? Map<*, *>)?.get("nextRequest") as? String
        }
      }
    }
    return PmExecutionResult(
      environment,
      globals,
      collectionVariables,
      assertions,
      error,
      nextRequest,
      skipRequest,
    )
  }

  /**
   * Reads a returned VariableScope's `values: [{key,value}]` (or `{key:value}`) into a flat map.
   */
  private fun scopeValues(scope: Any?): Map<String, Any?> {
    val m = scope as? Map<*, *> ?: return emptyMap()
    return when (val values = m["values"]) {
      is List<*> ->
        values
          .mapNotNull {
            val e = it as? Map<*, *> ?: return@mapNotNull null
            val k = e["key"] as? String ?: return@mapNotNull null
            k to normalizeNumber(e["value"])
          }
          .toMap()
      is Map<*, *> ->
        values.entries
          .mapNotNull { (k, v) -> (k as? String)?.let { it to normalizeNumber(v) } }
          .toMap()
      else -> emptyMap()
    }
  }

  /**
   * The bridge decodes all JSON numbers as `Double` (JSON has no int/double distinction). ReVoman's
   * env, the old in-JS path, and consumer assertions/`getInt` expect integral values to stay `Int`/
   * `Long`. Narrow integral doubles back so an unchanged `limit=1` round-trips as `1`, not `1.0` —
   * which also keeps [diffScopes] from spuriously flagging untouched numeric keys as produced.
   */
  private fun normalizeNumber(value: Any?): Any? =
    when (value) {
      is Double ->
        if (value % 1.0 == 0.0 && !value.isInfinite()) {
          if (value in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()) value.toInt()
          else value.toLong()
        } else value
      else -> value
    }

  private companion object {
    private val logger = KotlinLogging.logger {}
  }
}
