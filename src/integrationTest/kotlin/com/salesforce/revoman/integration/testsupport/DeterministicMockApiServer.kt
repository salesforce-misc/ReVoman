/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.integration.testsupport

import com.squareup.moshi.Moshi
import com.sun.net.httpserver.HttpServer
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.http4k.core.Body
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.Method.GET
import org.http4k.core.Method.PATCH
import org.http4k.core.Method.POST
import org.http4k.core.Method.PUT
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status.Companion.BAD_REQUEST
import org.http4k.core.Status.Companion.METHOD_NOT_ALLOWED
import org.http4k.core.Status.Companion.NOT_FOUND
import org.http4k.core.Status.Companion.OK
import org.http4k.core.queries
import org.http4k.routing.bind
import org.http4k.routing.path
import org.http4k.routing.routes
import org.http4k.server.HttpExchangeHandler

private const val LOOPBACK_ADDRESS = "127.0.0.1"
private const val EXECUTOR_STOP_TIMEOUT_SECONDS = 5L
private val fixtureIds = AtomicInteger()
private val jsonAdapter = Moshi.Builder().build().adapter(Any::class.java).lenient()

private data class RecordedApiRequest(
  val method: Method,
  val path: String,
  val query: String?,
  val body: ByteArray,
)

/** A deterministic local API fixture that is reachable only through a real IPv4 loopback socket. */
class DeterministicMockApiServer
private constructor(
  private val server: HttpServer,
  private val executor: ExecutorService,
  private val requests: ConcurrentLinkedQueue<RecordedApiRequest>,
) : AutoCloseable {
  private val closed = AtomicBoolean()

  /** Base URL for this fixture's ephemeral IPv4 loopback endpoint. */
  val baseUrl: String
    get() =
      server.address.let { address ->
        "http://${address.address.hostAddress}:${address.port}"
      }

  /** Returns real-wire requests in their received order. */
  fun requestSignatures(): List<String> = requests.map { request ->
    buildString {
      append(request.method)
      append(' ')
      append(request.path)
      if (request.query != null) append('?').append(request.query)
    }
  }

  /** Returns the number of real-wire requests received for [path]. */
  fun hitCount(path: String): Int = requests.count { it.path == path }

  override fun close() {
    if (!closed.compareAndSet(false, true)) return
    server.stop(0)
    executor.shutdown()
    if (!executor.awaitTermination(EXECUTOR_STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
      executor.shutdownNow()
      check(executor.awaitTermination(EXECUTOR_STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        "deterministic mock API worker did not stop"
      }
    }
  }

  companion object {
    /** Starts an isolated deterministic API fixture. */
    @JvmStatic fun start(): DeterministicMockApiServer = startFixture()

    private fun startFixture(): DeterministicMockApiServer {
      val requests = ConcurrentLinkedQueue<RecordedApiRequest>()
      val objects = ConcurrentHashMap<String, Map<String, Any?>>()
      val objectIds = AtomicInteger()
      val routesHandler =
        routes(
          "/objects" bind GET to { objects.toSortedMap().values.toList().toJsonResponse() },
          "/objects" bind
            POST to
            { request ->
              request.toJsonObject()?.let { fields ->
                val id = "local-object-${objectIds.incrementAndGet()}"
                linkedMapOf<String, Any?>("id" to id)
                  .apply { putAll(fields - "id") }
                  .also { objects[id] = it }
                  .toJsonResponse()
              } ?: Response(BAD_REQUEST)
            },
          "/objects/{id}" bind
            PATCH to
            { request ->
              val id = request.path("id")!!
              request.toJsonObject()?.let { fields ->
                objects[id]?.let { existing ->
                  existing
                    .toMutableMap()
                    .apply {
                      listOf("name", "data").forEach { field ->
                        if (field in fields) put(field, fields[field])
                      }
                    }
                    .also { objects[id] = it }
                    .toJsonResponse()
                } ?: objectNotFoundResponse()
              } ?: Response(BAD_REQUEST)
            },
          "/objects/{id}" bind
            GET to
            { request ->
              objects[request.path("id")!!]?.toJsonResponse() ?: objectNotFoundResponse()
            },
          "/objects/{id}" bind
            PUT to
            { request ->
              val id = request.path("id")!!
              request.toJsonObject()?.let { fields ->
                objects[id]?.let {
                  linkedMapOf<String, Any?>("id" to id)
                    .apply { putAll(fields - "id") }
                    .also { objects[id] = it }
                    .toJsonResponse()
                } ?: objectNotFoundResponse()
              } ?: Response(BAD_REQUEST)
            },
          "/pokemon" bind
            GET to
            { request ->
              if (request.uri.queries() != listOf("limit" to "5")) {
                Response(NOT_FOUND)
              } else {
                mapOf(
                    "results" to
                      listOf(
                          "bulbasaur",
                          "ivysaur",
                          "venusaur",
                          "charmander",
                          "charmeleon",
                        )
                        .map { name -> mapOf("name" to name) }
                  )
                  .toJsonResponse()
              }
            },
          "/pokemon/bulbasaur" bind
            GET to
            {
              mapOf("id" to 1, "name" to "bulbasaur").toJsonResponse()
            },
          "/pokemon-species/bulbasaur" bind
            GET to
            {
              mapOf("id" to 1, "name" to "bulbasaur").toJsonResponse()
            },
        )
      val handler: HttpHandler = { request ->
        routesHandler(request).let { response ->
          if (response.status == METHOD_NOT_ALLOWED) Response(NOT_FOUND) else response
        }
      }
      val fixtureId = fixtureIds.incrementAndGet()
      val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "revoman-deterministic-mock-api-$fixtureId").apply { isDaemon = false }
      }
      val server =
        runCatching {
            HttpServer.create(InetSocketAddress(LOOPBACK_ADDRESS, 0), 0).apply {
              createContext(
                "/",
                HttpExchangeHandler { request ->
                  val (recorded, replayable) = request.recordedAndReplayable()
                  requests.add(recorded)
                  handler(replayable)
                },
              )
              this.executor = executor
            }
          }
          .getOrElse { failure ->
            executor.shutdownNow()
            throw failure
          }
      return runCatching {
          server.start()
          check(
            server.address.address is Inet4Address &&
              server.address.address.hostAddress == LOOPBACK_ADDRESS
          ) {
            "deterministic mock API must bind exact IPv4 loopback, got ${server.address.address}"
          }
          check(server.address.port > 0) { "deterministic mock API must select a nonzero port" }
          DeterministicMockApiServer(server, executor, requests)
        }
        .getOrElse { failure ->
          server.stop(0)
          executor.shutdownNow()
          throw failure
        }
    }
  }
}

private fun Request.recordedAndReplayable(): Pair<RecordedApiRequest, Request> {
  val buffer = body.payload.asReadOnlyBuffer()
  val bytes = ByteArray(buffer.remaining()).also(buffer::get)
  return RecordedApiRequest(
    method,
    uri.path,
    uri.query.takeIf(String::isNotEmpty),
    bytes.copyOf(),
  ) to body(Body(ByteBuffer.wrap(bytes)))
}

private fun Request.toJsonObject(): Map<String, Any?>? =
  runCatching { jsonAdapter.fromJson(bodyString()) }
    .getOrNull()
    ?.let { value ->
      (value as? Map<*, *>)?.entries?.associate { (key, fieldValue) ->
        key.toString() to fieldValue
      }
    }

private fun Any.toJsonResponse(): Response = Response(OK).body(jsonAdapter.toJson(this))

private fun objectNotFoundResponse(): Response =
  Response(NOT_FOUND).body(jsonAdapter.toJson(mapOf("error" to "object not found")))
