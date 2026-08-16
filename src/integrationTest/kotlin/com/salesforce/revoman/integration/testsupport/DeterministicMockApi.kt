/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.integration.testsupport

import com.salesforce.revoman.testing.http.MockHttpHandler
import com.squareup.moshi.Moshi
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
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

private val jsonAdapter = Moshi.Builder().build().adapter(Any::class.java).lenient()

/** Deterministic, thread-safe domain handler for the local integration API. */
class DeterministicMockApi : MockHttpHandler {
  private val stateLock = ReentrantLock()
  private val objects = linkedMapOf<String, Map<String, Any?>>()
  private var nextObjectId = 0

  private val routesHandler =
    routes(
      "/objects" bind GET to { listObjects() },
      "/objects" bind POST to { request -> createObject(request) },
      "/objects/{id}" bind PATCH to { request -> patchObject(request) },
      "/objects/{id}" bind GET to { request -> getObject(request) },
      "/objects/{id}" bind PUT to { request -> putObject(request) },
      "/pokemon" bind GET to { request -> pokemonIndex(request) },
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

  override fun handle(request: Request): Response =
    routesHandler(request).let { response ->
      if (response.status == METHOD_NOT_ALLOWED) Response(NOT_FOUND) else response
    }

  private fun listObjects(): Response {
    val snapshot = stateLock.withLock {
      objects.toSortedMap().values.map { LinkedHashMap(it) }
    }
    return snapshot.toJsonResponse()
  }

  private fun createObject(request: Request): Response {
    val fields = request.toJsonObject() ?: return Response(BAD_REQUEST)
    val created = stateLock.withLock {
      val id = "local-object-${++nextObjectId}"
      linkedMapOf<String, Any?>("id" to id)
        .apply { putAll(fields - "id") }
        .also { objects[id] = it }
        .let { LinkedHashMap(it) }
    }
    return created.toJsonResponse()
  }

  private fun patchObject(request: Request): Response {
    val id = requireNotNull(request.path("id"))
    val fields = request.toJsonObject() ?: return Response(BAD_REQUEST)
    val updated =
      stateLock.withLock {
        objects[id]?.let { existing ->
          existing
            .toMutableMap()
            .apply {
              listOf("name", "data").forEach { field ->
                if (field in fields) put(field, fields[field])
              }
            }
            .also { objects[id] = it }
            .let { LinkedHashMap(it) }
        }
      } ?: return objectNotFoundResponse()
    return updated.toJsonResponse()
  }

  private fun getObject(request: Request): Response {
    val id = requireNotNull(request.path("id"))
    val snapshot =
      stateLock.withLock { objects[id]?.let { LinkedHashMap(it) } }
        ?: return objectNotFoundResponse()
    return snapshot.toJsonResponse()
  }

  private fun putObject(request: Request): Response {
    val id = requireNotNull(request.path("id"))
    val fields = request.toJsonObject() ?: return Response(BAD_REQUEST)
    val replacement =
      stateLock.withLock {
        if (id !in objects) return@withLock null
        linkedMapOf<String, Any?>("id" to id)
          .apply { putAll(fields - "id") }
          .also { objects[id] = it }
          .let { LinkedHashMap(it) }
      } ?: return objectNotFoundResponse()
    return replacement.toJsonResponse()
  }

  private fun pokemonIndex(request: Request): Response =
    if (request.uri.queries() != listOf("limit" to "5")) {
      Response(NOT_FOUND)
    } else {
      mapOf(
          "results" to
            listOf("bulbasaur", "ivysaur", "venusaur", "charmander", "charmeleon").map { name ->
              mapOf("name" to name)
            }
        )
        .toJsonResponse()
    }
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
