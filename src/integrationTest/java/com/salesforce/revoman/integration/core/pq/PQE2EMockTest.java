/***************************************************************************************************
 *  Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier:
 *           Apache License Version 2.0
 *  For full license text, see the LICENSE file in the repo root or
 *  http://www.apache.org/licenses/LICENSE-2.0
 **************************************************************************************************/

package com.salesforce.revoman.integration.core.pq;

import static com.google.common.truth.Truth.assertThat;
import static com.salesforce.revoman.input.config.HookConfig.post;
import static com.salesforce.revoman.input.config.HookConfig.pre;
import static com.salesforce.revoman.input.config.PollingConfig.poll;
import static com.salesforce.revoman.input.config.RequestConfig.unmarshallRequest;
import static com.salesforce.revoman.input.config.ResponseConfig.unmarshallResponse;
import static com.salesforce.revoman.input.config.StepPick.PostTxnStepPick.afterStepContainingHeader;
import static com.salesforce.revoman.input.config.StepPick.PostTxnStepPick.afterStepContainingURIPathOfAny;
import static com.salesforce.revoman.input.config.StepPick.PreTxnStepPick.beforeStepContainingURIPathOfAny;
import static com.salesforce.revoman.integration.core.CoreUtils.assertCompositeGraphResponseSuccess;
import static com.salesforce.revoman.integration.core.SalesforceMockHandler.jsonResponse;
import static com.salesforce.revoman.integration.core.adapters.ConnectInputRepWithGraphAdapter.adapter;
import static com.salesforce.revoman.output.ExeType.HTTP_STATUS;
import static com.salesforce.revoman.output.report.StepReport.containsHeader;
import static com.salesforce.revoman.output.report.StepReport.uriPathContains;

import com.salesforce.revoman.ReVoman;
import com.salesforce.revoman.input.config.Kick;
import com.salesforce.revoman.input.json.adapters.salesforce.CompositeGraphResponse;
import com.salesforce.revoman.integration.core.SalesforceMockHandler;
import com.salesforce.revoman.integration.core.adapters.IDAdapter;
import com.salesforce.revoman.integration.core.pq.connect.request.PlaceQuoteInputRepresentation;
import com.salesforce.revoman.integration.core.pq.connect.response.PlaceQuoteOutputRepresentation;
import com.salesforce.revoman.output.report.StepReport;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import kotlin.random.Random;
import org.http4k.core.Method;
import org.http4k.core.Request;
import org.http4k.core.Response;
import org.http4k.core.Status;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mock server companion test for {@link PQE2EWithSMTest}. Exercises the same Postman collections
 * and Kick configuration against a mock HTTP handler instead of a real Salesforce server. Validates
 * that API metadata (variable chaining, JS scripts, hooks, unmarshalling, polling) works correctly.
 */
class PQE2EMockTest {
  private static final Logger LOGGER = LoggerFactory.getLogger(PQE2EMockTest.class);
  private static final String PQ_COLLECTION_PATH = "pm-templates/core/pq";
  private static final List<String> PQ_TEMPLATE_PATHS =
      List.of(
          PQ_COLLECTION_PATH + "/[sm] user-creation-with-ps-and-setup-pq.postman_collection.json",
          PQ_COLLECTION_PATH + "/pre-salesRep.postman_collection.json",
          PQ_COLLECTION_PATH + "/[sm] pq.postman_collection.json");
  private static final String PQ_ENV_PATH = PQ_COLLECTION_PATH + "/pq-env.postman_environment.json";
  private static final String PQ_URI_PATH = "commerce/quotes/actions/place";
  private static final String COMPOSITE_GRAPH_URI_PATH = "composite/graph";
  private static final String IS_SYNC_HEADER = "isSync";
  private static final String SYNC_ERROR_FOLDER_NAME = "errors|>sync";

  private static final String MOCK_QUOTE_SKIP_ID = "0Q0MOCK_SKIP_001";
  private static final String MOCK_QUOTE_ID = "0Q0MOCK_REG_001";

  @Test
  void revUpPQWithMockServer() {
    var pqCallCount = new AtomicInteger(0);
    var mockHandler =
        SalesforceMockHandler.configure()
            // PQ connect API: return success for normal steps, error for ignoreHTTPStatus steps
            .connectApiHandler(
                PQ_URI_PATH,
                request -> {
                  boolean isErrorStep = request.header("ignoreHTTPStatusUnsuccessful") != null;
                  if (isErrorStep) {
                    return Response.create(Status.BAD_REQUEST)
                        .header("Content-Type", "application/json")
                        .body(
                            """
                            {"quoteId":null,"requestIdentifier":"mockReqId","statusURL":null,"success":false,"responseError":[{"referenceId":"mock","errorCode":"INVALID_INPUT","message":"Mock error"}]}""");
                  }
                  var callNum = pqCallCount.incrementAndGet();
                  var quoteId = (callNum == 1) ? MOCK_QUOTE_SKIP_ID : MOCK_QUOTE_ID;
                  return jsonResponse(
                      """
                      {"quoteId":"%s","requestIdentifier":"mockReqId_%d","statusURL":"/services/data/v61.0/sobjects/RevenueAsyncOperation/mock","success":true,"responseError":[]}"""
                          .formatted(quoteId, callNum));
                })
            // Quote GET: return CalculationStatus based on quote ID
            .sobjectGetResponse(
                "Quote",
                id -> {
                  var status = id.contains("SKIP") ? "CompletedWithoutPricing" : "CompletedWithTax";
                  return """
                      {"Id":"%s","CalculationStatus":"%s","LineItemCount":10,"attributes":{"type":"Quote","url":"..."}}"""
                      .formatted(id, status);
                })
            // Composite query for "query-quote-and-related-records" step
            .compositeQueryResponse(
                handler -> {
                  // Build 10 QLI records using product IDs that were generated during setup
                  var productIds = handler.sobjectPostIds("Product2/");
                  var qliRecords = new StringBuilder();
                  for (int i = 0; i < 10; i++) {
                    if (i > 0) qliRecords.append(",");
                    var productId =
                        i < productIds.size()
                            ? productIds.get(i % productIds.size())
                            : handler.nextMockId();
                    qliRecords.append(
                        """
                        {"Id":"0QL_MOCK_%03d","Product2Id":"%s","attributes":{"type":"QuoteLineItem","url":"..."}}"""
                            .formatted(i + 1, productId));
                  }
                  // Build 3 QLR records
                  var qlrRecords =
                      """
                      {"Id":"0yQ_MOCK_001","QuoteId":"%s","MainQuoteLineId":"0QL_MOCK_001","AssociatedQuoteLineId":"0QL_MOCK_002","attributes":{"type":"QuoteLineRelationship","url":"..."}},\
                      {"Id":"0yQ_MOCK_002","QuoteId":"%s","MainQuoteLineId":"0QL_MOCK_002","AssociatedQuoteLineId":"0QL_MOCK_003","attributes":{"type":"QuoteLineRelationship","url":"..."}},\
                      {"Id":"0yQ_MOCK_003","QuoteId":"%s","MainQuoteLineId":"0QL_MOCK_003","AssociatedQuoteLineId":"0QL_MOCK_004","attributes":{"type":"QuoteLineRelationship","url":"..."}}"""
                          .formatted(MOCK_QUOTE_ID, MOCK_QUOTE_ID, MOCK_QUOTE_ID);
                  return """
                      {"compositeResponse":[\
                      {"body":{"done":true,"records":[{"CalculationStatus":"CompletedWithTax","LineItemCount":10,"attributes":{"type":"Quote","url":"..."}}],"totalSize":1},"httpHeaders":{},"httpStatusCode":200,"referenceId":"quote"},\
                      {"body":{"done":true,"records":[%s],"totalSize":10},"httpHeaders":{},"httpStatusCode":200,"referenceId":"qlis"},\
                      {"body":{"done":true,"records":[%s],"totalSize":3},"httpHeaders":{},"httpStatusCode":200,"referenceId":"qlrs"}\
                      ]}"""
                      .formatted(qliRecords, qlrRecords);
                })
            .build();

    final var pqRundown =
        ReVoman.revUp(
            Kick.configure()
                .templatePaths(PQ_TEMPLATE_PATHS)
                .environmentPath(PQ_ENV_PATH)
                .dynamicEnvironment(
                    Map.of(
                        "$quoteFieldsToQuery", "LineItemCount, CalculationStatus",
                        "$qliFieldsToQuery", "Id, Product2Id",
                        "$qlrFieldsToQuery", "Id, QuoteId, MainQuoteLineId, AssociatedQuoteLineId"))
                .customDynamicVariableGenerator(
                    "$unitPrice",
                    (ignore1, ignore2, ignore3) -> String.valueOf(Random.Default.nextInt(999) + 1))
                .nodeModulesPath("js")
                .haltOnFailureOfTypeExcept(
                    HTTP_STATUS, afterStepContainingHeader("ignoreHTTPStatusUnsuccessful"))
                .requestConfig(
                    unmarshallRequest(
                        beforeStepContainingURIPathOfAny(PQ_URI_PATH),
                        PlaceQuoteInputRepresentation.class,
                        adapter(PlaceQuoteInputRepresentation.class)))
                .responseConfig(
                    unmarshallResponse(
                        afterStepContainingURIPathOfAny(PQ_URI_PATH),
                        PlaceQuoteOutputRepresentation.class),
                    unmarshallResponse(
                        afterStepContainingURIPathOfAny(COMPOSITE_GRAPH_URI_PATH),
                        CompositeGraphResponse.class,
                        CompositeGraphResponse.ADAPTER))
                .hooks(
                    pre(
                        beforeStepContainingURIPathOfAny(PQ_URI_PATH),
                        (step, requestInfo, rundown) -> {
                          if (requestInfo.containsHeader(IS_SYNC_HEADER)) {
                            LOGGER.info("This is a Sync step: {}", step);
                          }
                        }),
                    post(
                        afterStepContainingURIPathOfAny(PQ_URI_PATH),
                        (stepReport, ignore) -> validatePQResponse(stepReport)),
                    post(
                        afterStepContainingURIPathOfAny(COMPOSITE_GRAPH_URI_PATH),
                        (stepReport, ignore) -> assertCompositeGraphResponseSuccess(stepReport)))
                .pollingConfig(
                    poll((stepReport, rundown) ->
                            uriPathContains(stepReport.requestInfo, PQ_URI_PATH)
                                && !containsHeader(stepReport.requestInfo, IS_SYNC_HEADER))
                        .request(
                            (stepReport, env) ->
                                Request.create(
                                    Method.GET,
                                    "%s/%s/sobjects/Quote/%s"
                                        .formatted(
                                            env.getAsString("baseUrl"),
                                            env.getAsString("versionPath"),
                                            env.getAsString("quoteId"))))
                        .every(Duration.ofMillis(100))
                        .timeout(Duration.ofSeconds(5))
                        .until((response, env) -> response.bodyString().contains("Completed")))
                .globalCustomTypeAdapter(IDAdapter.INSTANCE)
                .httpHandler(mockHandler.handler())
                .off());

    assertThat(pqRundown.firstUnIgnoredUnsuccessfulStepReport()).isNull();
    assertThat(pqRundown.mutableEnv)
        .containsAtLeastEntriesIn(
            Map.of(
                "quoteCalculationStatusForSkipPricing", "CompletedWithoutPricing",
                "quoteCalculationStatus", "CompletedWithTax",
                "quoteCalculationStatusAfterAllUpdates", "CompletedWithTax"));
  }

  private static void validatePQResponse(StepReport stepReport) {
    final var pqOutputRep =
        stepReport.responseInfo.get().<PlaceQuoteOutputRepresentation>getTypedTxnObj();
    final var successRespProp = pqOutputRep.getSuccess();
    final var isStepExpectedToFail = stepReport.step.isInFolder(SYNC_ERROR_FOLDER_NAME);
    assertThat(successRespProp).isEqualTo(!isStepExpectedToFail);
  }
}
