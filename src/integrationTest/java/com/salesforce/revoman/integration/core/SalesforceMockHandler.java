/***************************************************************************************************
 *  Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier:
 *           Apache License Version 2.0
 *  For full license text, see the LICENSE file in the repo root or
 *  http://www.apache.org/licenses/LICENSE-2.0
 **************************************************************************************************/

package com.salesforce.revoman.integration.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.regex.Pattern;
import kotlin.jvm.functions.Function1;
import org.http4k.core.Request;
import org.http4k.core.Response;
import org.http4k.core.Status;

/**
 * Reusable mock HTTP handler for Salesforce API patterns. Handles SOAP login, REST version,
 * composite/graph, composite, SObject CRUD, queries, tooling, and connect APIs. Supports
 * test-specific customization via builder pattern.
 */
public final class SalesforceMockHandler {

  private final AtomicInteger idCounter = new AtomicInteger(0);
  private final Map<String, String> referenceIdToMockId = new LinkedHashMap<>();
  private final Map<String, String> sobjectPostIdTracker = new LinkedHashMap<>();
  private final Map<String, Function<Request, Response>> connectApiHandlers;
  private final Map<String, Function<String, String>> sobjectGetHandlers;
  private final List<Function<SalesforceMockHandler, String>> compositeQueryResponseBuilders;
  private int compositeQueryResponseIndex = 0;

  private SalesforceMockHandler(
      Map<String, Function<Request, Response>> connectApiHandlers,
      Map<String, Function<String, String>> sobjectGetHandlers,
      List<Function<SalesforceMockHandler, String>> compositeQueryResponseBuilders) {
    this.connectApiHandlers = connectApiHandlers;
    this.sobjectGetHandlers = sobjectGetHandlers;
    this.compositeQueryResponseBuilders = compositeQueryResponseBuilders;
  }

  public static Builder configure() {
    return new Builder();
  }

  /** Returns the generated mock ID for a given composite graph referenceId. */
  public String mockIdFor(String referenceId) {
    return referenceIdToMockId.get(referenceId);
  }

  /** Returns all IDs generated for SObject POST requests to the given type. */
  public List<String> sobjectPostIds(String sobjectType) {
    return sobjectPostIdTracker.entrySet().stream()
        .filter(e -> e.getValue().equals(sobjectType))
        .map(Map.Entry::getKey)
        .toList();
  }

  /** Returns all referenceId→mockId mappings accumulated during execution. */
  public Map<String, String> allReferenceIds() {
    return Map.copyOf(referenceIdToMockId);
  }

  public String nextMockId() {
    return "mockId_%05d".formatted(idCounter.incrementAndGet());
  }

  private String trackId(String referenceId) {
    var id = nextMockId();
    referenceIdToMockId.put(referenceId, id);
    return id;
  }

  public Function1<Request, Response> handler() {
    return request -> {
      var path = request.getUri().getPath();
      var method = request.getMethod().toString();
      var body = request.bodyString();

      // SOAP Login
      if (path.contains("/services/Soap/")) {
        return soapLoginResponse();
      }

      // REST API Version
      if ((path.endsWith("/services/data/") || path.endsWith("/services/data"))
          && "GET".equals(method)) {
        return versionResponse();
      }

      // Composite Graph
      if (path.contains("composite/graph") && "POST".equals(method)) {
        return compositeGraphResponse(body);
      }

      // Composite (but NOT composite/graph)
      if (path.matches(".*composite/?$") && "POST".equals(method)) {
        return compositeResponse(body);
      }

      // Connect APIs (PQ, PST, BT2BS, Invoice, etc.)
      for (var entry : connectApiHandlers.entrySet()) {
        if (path.contains(entry.getKey())) {
          return entry.getValue().apply(request);
        }
      }

      // Query
      if (path.contains("/query/") && "GET".equals(method)) {
        return queryResponse(request);
      }

      // SObject operations
      if (path.contains("/sobjects/")) {
        return sobjectResponse(path, method);
      }

      // Tooling
      if (path.contains("/tooling/")) {
        return toolingResponse();
      }

      // Default
      return jsonResponse("{}");
    };
  }

  // ---------- SOAP Login ----------

  private Response soapLoginResponse() {
    var sessionId = "mock-session-" + nextMockId();
    var userId = nextMockId();
    var orgId = nextMockId();
    return Response.create(Status.OK)
        .header("Content-Type", "text/xml; charset=utf-8")
        .body(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" \
            xmlns="urn:partner.soap.sforce.com" xmlns:sf="urn:sobject.partner.soap.sforce.com">
              <soapenv:Body>
                <loginResponse>
                  <result>
                    <sessionId>%s</sessionId>
                    <userId>%s</userId>
                    <userInfo>
                      <organizationId>%s</organizationId>
                    </userInfo>
                  </result>
                </loginResponse>
              </soapenv:Body>
            </soapenv:Envelope>"""
                .formatted(sessionId, userId, orgId));
  }

  // ---------- REST Version ----------

  private Response versionResponse() {
    return jsonResponse(
        """
        [{"version":"61.0","label":"Winter '25","url":"/services/data/v61.0"}]""");
  }

  // ---------- Composite Graph ----------

  private Response compositeGraphResponse(String requestBody) {
    var graphPattern = Pattern.compile("\"graphId\"\\s*:\\s*\"([^\"]+)\"");
    var refPattern = Pattern.compile("\"referenceId\"\\s*:\\s*\"([^\"]+)\"");
    var graphIds = new ArrayList<String>();
    var graphMatcher = graphPattern.matcher(requestBody);
    while (graphMatcher.find()) {
      graphIds.add(graphMatcher.group(1));
    }
    var referenceIds = new ArrayList<String>();
    var refMatcher = refPattern.matcher(requestBody);
    while (refMatcher.find()) {
      referenceIds.add(refMatcher.group(1));
    }

    var graphCount = Math.max(graphIds.size(), 1);
    var refsPerGraph = referenceIds.isEmpty() ? 0 : Math.max(referenceIds.size() / graphCount, 1);

    var graphs = new ArrayList<String>();
    for (int g = 0; g < graphCount; g++) {
      var graphId = g < graphIds.size() ? graphIds.get(g) : String.valueOf(g + 1);
      var startIdx = g * refsPerGraph;
      var endIdx =
          (g == graphCount - 1)
              ? referenceIds.size()
              : Math.min(startIdx + refsPerGraph, referenceIds.size());
      var compositeResponses = new ArrayList<String>();
      for (int i = startIdx; i < endIdx; i++) {
        var refId = referenceIds.get(i);
        var mockId = trackId(refId);
        compositeResponses.add(
            """
            {"body":{"id":"%s","success":true,"errors":[]},"httpHeaders":{"Location":"/services/data/v61.0/sobjects/Mock/%s"},"httpStatusCode":201,"referenceId":"%s"}"""
                .formatted(mockId, mockId, refId));
      }
      graphs.add(
          """
          {"graphId":"%s","graphResponse":{"compositeResponse":[%s]},"isSuccessful":true}"""
              .formatted(graphId, String.join(",", compositeResponses)));
    }
    return jsonResponse("{\"graphs\":[%s]}".formatted(String.join(",", graphs)));
  }

  // ---------- Composite ----------

  private Response compositeResponse(String requestBody) {
    // Only use configured responses for composites containing GET subrequests (queries)
    boolean hasGetSubrequest =
        requestBody.contains("\"method\":\"GET\"") || requestBody.contains("\"method\": \"GET\"");
    if (hasGetSubrequest && compositeQueryResponseIndex < compositeQueryResponseBuilders.size()) {
      var builder = compositeQueryResponseBuilders.get(compositeQueryResponseIndex++);
      return jsonResponse(builder.apply(this));
    }
    // Default: parse subrequests and generate responses
    var refPattern = Pattern.compile("\"referenceId\"\\s*:\\s*\"([^\"]+)\"");
    var methodPattern = Pattern.compile("\"method\"\\s*:\\s*\"([^\"]+)\"");
    var refMatcher = refPattern.matcher(requestBody);
    var methodMatcher = methodPattern.matcher(requestBody);
    var responses = new ArrayList<String>();
    while (refMatcher.find() && methodMatcher.find()) {
      var refId = refMatcher.group(1);
      var httpMethod = methodMatcher.group(1);
      if ("GET".equals(httpMethod)) {
        responses.add(
            """
            {"body":{"done":true,"records":[],"totalSize":0},"httpHeaders":{},"httpStatusCode":200,"referenceId":"%s"}"""
                .formatted(refId));
      } else {
        // POST, PATCH, DELETE → 204 (safe for CompositeResponse adapter)
        responses.add(
            """
            {"body":null,"httpHeaders":{},"httpStatusCode":204,"referenceId":"%s"}"""
                .formatted(refId));
      }
    }
    return jsonResponse("{\"compositeResponse\":[%s]}".formatted(String.join(",", responses)));
  }

  // ---------- Query ----------

  private Response queryResponse(Request request) {
    var query = request.getUri().getQuery();
    if (query != null && query.contains("Profile")) {
      var id = nextMockId();
      return jsonResponse(
          """
          {"done":true,"totalSize":1,"records":[{"Id":"%s","attributes":{"type":"Profile","url":"..."}}]}"""
              .formatted(id));
    }
    if (query != null && query.contains("PermissionSet")) {
      var records = new ArrayList<String>();
      for (int i = 0; i < 11; i++) {
        var psId = nextMockId();
        records.add(
            """
            {"Id":"%s","attributes":{"type":"PermissionSet","url":"..."}}"""
                .formatted(psId));
      }
      return jsonResponse(
          "{\"done\":true,\"totalSize\":%d,\"records\":[%s]}"
              .formatted(records.size(), String.join(",", records)));
    }
    if (query != null && query.contains("Pricebook2")) {
      return jsonResponse(
          """
          {"done":true,"totalSize":1,"records":[{"Id":"%s","attributes":{"type":"Pricebook2","url":"..."}}]}"""
              .formatted(nextMockId()));
    }
    if (query != null && query.contains("ProrationPolicy")) {
      return jsonResponse(
          """
          {"done":true,"totalSize":1,"records":[{"Id":"%s","Name":"ReVoman Proration Policy","attributes":{"type":"ProrationPolicy","url":"..."}}]}"""
              .formatted(nextMockId()));
    }
    if (query != null && (query.contains("ApexClass") || query.contains("TaxEngineProvider"))) {
      return jsonResponse(
          """
          {"done":true,"totalSize":1,"records":[{"Id":"%s","attributes":{"type":"Query","url":"..."}}]}"""
              .formatted(nextMockId()));
    }
    if (query != null && query.contains("ProductSellingModel")) {
      return jsonResponse(
          """
          {"done":true,"totalSize":1,"records":[{"Id":"%s","SellingModelType":"OneTime","Status":"Active","attributes":{"type":"ProductSellingModel","url":"..."}}]}"""
              .formatted(nextMockId()));
    }
    // Default: return one generic record (most collection queries expect at least one result)
    var id = nextMockId();
    return jsonResponse(
        """
        {"done":true,"totalSize":1,"records":[{"Id":"%s","attributes":{"type":"SObject","url":"..."}}]}"""
            .formatted(id));
  }

  // ---------- SObject CRUD ----------

  private Response sobjectResponse(String path, String method) {
    if ("POST".equals(method)) {
      var id = nextMockId();
      // Track the sobject type for this ID
      var parts = path.split("/sobjects/");
      if (parts.length > 1) {
        var type = parts[1].replaceAll("/$", "");
        sobjectPostIdTracker.put(id, type);
      }
      return jsonResponse(
          """
          {"id":"%s","success":true,"errors":[]}"""
              .formatted(id));
    }
    if ("PATCH".equals(method)) {
      return Response.create(Status.NO_CONTENT);
    }
    if ("GET".equals(method)) {
      var parts = path.split("/sobjects/");
      if (parts.length > 1) {
        var typePath = parts[1];
        var segments = typePath.split("/");
        var type = segments[0];
        var id = segments.length > 1 ? segments[1] : "";
        if (sobjectGetHandlers.containsKey(type)) {
          return jsonResponse(sobjectGetHandlers.get(type).apply(id));
        }
        if ("SalesTransaction".equals(type)) {
          return jsonResponse(
              """
              {"Id":"%s","Status":"Completed","attributes":{"type":"SalesTransaction","url":"..."}}"""
                  .formatted(id));
        }
        return jsonResponse(
            """
            {"Id":"%s","attributes":{"type":"%s","url":"/services/data/v61.0/sobjects/%s/%s"}}"""
                .formatted(id, type, type, id));
      }
    }
    return jsonResponse("{}");
  }

  // ---------- Tooling ----------

  private Response toolingResponse() {
    return jsonResponse(
        """
        {"id":"%s","success":true,"errors":[]}"""
            .formatted(nextMockId()));
  }

  // ---------- Helpers ----------

  public static Response jsonResponse(String json) {
    return Response.create(Status.OK).header("Content-Type", "application/json").body(json);
  }

  // ---------- Builder ----------

  public static final class Builder {
    private final Map<String, Function<Request, Response>> connectApiHandlers =
        new LinkedHashMap<>();
    private final Map<String, Function<String, String>> sobjectGetHandlers = new LinkedHashMap<>();
    private final List<Function<SalesforceMockHandler, String>> compositeQueryResponseBuilders =
        new ArrayList<>();

    private Builder() {}

    /** Register a custom handler for a connect API endpoint (matched via URL contains). */
    public Builder connectApiHandler(String urlFragment, Function<Request, Response> handler) {
      connectApiHandlers.put(urlFragment, handler);
      return this;
    }

    /** Register a custom GET /sobjects/Type/{id} response. Function receives ID, returns JSON. */
    public Builder sobjectGetResponse(
        String sobjectType, Function<String, String> responseBuilder) {
      sobjectGetHandlers.put(sobjectType, responseBuilder);
      return this;
    }

    /**
     * Register a dynamic composite query response builder. The function receives the handler
     * instance (with all accumulated mock IDs) and returns the composite response JSON. Responses
     * are consumed in order on each POST /composite call.
     */
    public Builder compositeQueryResponse(Function<SalesforceMockHandler, String> responseBuilder) {
      compositeQueryResponseBuilders.add(responseBuilder);
      return this;
    }

    /** Register a static composite query response (consumed in order). */
    public Builder compositeQueryResponse(String responseJson) {
      compositeQueryResponseBuilders.add(handler -> responseJson);
      return this;
    }

    public SalesforceMockHandler build() {
      return new SalesforceMockHandler(
          connectApiHandlers, sobjectGetHandlers, compositeQueryResponseBuilders);
    }
  }
}
