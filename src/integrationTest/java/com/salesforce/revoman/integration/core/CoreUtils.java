/***************************************************************************************************
 *  Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier:
 *           Apache License Version 2.0
 *  For full license text, see the LICENSE file in the repo root or
 *  http://www.apache.org/licenses/LICENSE-2.0
 **************************************************************************************************/

package com.salesforce.revoman.integration.core;

import static com.salesforce.revoman.input.config.HookConfig.post;
import static com.salesforce.revoman.input.config.ResponseConfig.unmarshallResponse;
import static com.salesforce.revoman.input.config.StepPick.PostTxnStepPick.afterStepContainingURIPathOfAny;
import static com.salesforce.revoman.input.config.StepPick.PostTxnStepPick.afterStepEndingWithURIPathOfAny;
import static com.salesforce.revoman.output.report.StepReport.containsHeader;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.salesforce.revoman.input.config.HookConfig;
import com.salesforce.revoman.input.config.ResponseConfig;
import com.salesforce.revoman.input.json.adapters.salesforce.CompositeGraphResponse;
import com.salesforce.revoman.input.json.adapters.salesforce.CompositeGraphResponse.Graph.ErrorGraph;
import com.salesforce.revoman.input.json.adapters.salesforce.CompositeResponse;
import com.salesforce.revoman.output.report.StepReport;
import java.util.List;

public class CoreUtils {
  private CoreUtils() {}

  public static final String COMPOSITE_GRAPH_URI_PATH = "composite/graph";

  /** Reusable Response config for CompositeGraphResponse to enhance debugging experience */
  public static ResponseConfig unmarshallCompositeGraphResponse() {
    return unmarshallResponse(
        afterStepContainingURIPathOfAny(COMPOSITE_GRAPH_URI_PATH),
        CompositeGraphResponse.class,
        CompositeGraphResponse.ADAPTER);
  }

  /**
   * Reusable ReVoman Post-Step hooks that can be added to your configuration to assert
   * CompositeGraphResponse(success/error). For expected failure response, this expects you to add
   * `expectToFail=true` header to your request in Postman If you have to use a different header,
   * compose your own hook using {@link #assertCompositeGraphResponseSuccess(StepReport, String)}
   */
  public static final HookConfig ASSERT_COMPOSITE_GRAPH_RESPONSE_SUCCESS =
      post(
          afterStepContainingURIPathOfAny(COMPOSITE_GRAPH_URI_PATH),
          (stepReport, ignore) -> assertCompositeGraphResponseSuccess(stepReport));

  public static final String EXPECT_TO_FAIL_HEADER = "expectToFail";

  private static final String UNSUCCESSFUL_COMPOSITE_GRAPH_RESPONSE_ERROR_MSG =
      """
            Unsuccessful Composite Graph response for graphId: %s
            {
                "first errorCode": "%s"
                "first errorMessage: "%s"
            }
            StepReport:
            %s""";

  public static void assertCompositeGraphResponseSuccess(StepReport stepReport) {
    assertCompositeGraphResponseSuccess(stepReport, EXPECT_TO_FAIL_HEADER);
  }

  static void assertCompositeGraphResponseSuccess(
      StepReport stepReport, String expectToFailHeader) {
    final var responseTxnInfo = stepReport.responseInfo.get();
    final var graphsResp =
        responseTxnInfo
            .<CompositeGraphResponse>getTypedTxnObj(
                CompositeGraphResponse.class, List.of(CompositeGraphResponse.ADAPTER))
            .getGraphs();
    for (var graphResp : graphsResp) {
      assertTrue(
          graphResp.isSuccessful() || containsHeader(stepReport.requestInfo, expectToFailHeader),
          () -> {
            final var firstErrorResponseBody = ((ErrorGraph) graphResp).firstErrorResponseBody();
            return UNSUCCESSFUL_COMPOSITE_GRAPH_RESPONSE_ERROR_MSG.formatted(
                graphResp.getGraphId(),
                firstErrorResponseBody.getErrorCode(),
                firstErrorResponseBody.getMessage(),
                stepReport);
          });
    }
  }

  public static final String COMPOSITE_URI_PATH = "composite";

  /** Reusable Response config for CompositeGraphResponse to enhance debugging experience */
  public static ResponseConfig unmarshallCompositeResponse() {
    return unmarshallResponse(
        afterStepEndingWithURIPathOfAny(COMPOSITE_URI_PATH),
        CompositeResponse.class,
        CompositeResponse.ADAPTER);
  }

  /**
   * Reusable ReVoman Post-Step hooks that can be added to your configuration to assert
   * CompositeResponse(success/error). For expected failure response, this expects you to add
   * `expectToFail=true` header to your request in Postman If you have to use a different header,
   * compose your own hook using {@link #assertCompositeResponseSuccess(StepReport, String)}
   */
  public static final HookConfig ASSERT_COMPOSITE_RESPONSE_SUCCESS =
      post(
          afterStepEndingWithURIPathOfAny(COMPOSITE_URI_PATH),
          (stepReport, ignore) -> assertCompositeResponseSuccess(stepReport));

  private static final String UNSUCCESSFUL_COMPOSITE_RESPONSE_ERROR_MSG =
      """
            Unsuccessful Composite response:
            {
                "first errorCode": "%s"
                "first errorMessage": "%s"
            }
            StepReport:
            %s""";

  static void assertCompositeResponseSuccess(StepReport stepReport) {
    assertCompositeResponseSuccess(stepReport, EXPECT_TO_FAIL_HEADER);
  }

  static void assertCompositeResponseSuccess(StepReport stepReport, String expectToFailHeader) {
    final var responseTxnInfo = stepReport.responseInfo.get();
    final var compositeResp =
        responseTxnInfo.<CompositeResponse>getTypedTxnObj(
            CompositeResponse.class, List.of(CompositeResponse.ADAPTER));
    assertTrue(
        compositeResp.isSuccessful() || containsHeader(stepReport.requestInfo, expectToFailHeader),
        () -> {
          final var firstErrorResponseBody = compositeResp.firstErrorResponseBody();
          return UNSUCCESSFUL_COMPOSITE_RESPONSE_ERROR_MSG.formatted(
              firstErrorResponseBody.getErrorCode(),
              firstErrorResponseBody.getMessage(),
              stepReport);
        });
  }
}
