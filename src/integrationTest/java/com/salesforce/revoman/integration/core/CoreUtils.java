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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.salesforce.revoman.input.config.HookConfig;
import com.salesforce.revoman.input.config.ResponseConfig;
import com.salesforce.revoman.input.json.adapters.salesforce.CompositeGraphResponse;
import com.salesforce.revoman.input.json.adapters.salesforce.CompositeGraphResponse.Graph.ErrorGraph;
import com.salesforce.revoman.output.report.StepReport;
import java.util.List;
import kotlin.collections.CollectionsKt;

public class CoreUtils {
	private CoreUtils() {}

	public static final String COMPOSITE_GRAPH_URI_PATH = "composite/graph";

	public static final HookConfig ASSERT_COMPOSITE_GRAPH_RESPONSE_SUCCESS =
			post(
					afterStepContainingURIPathOfAny(COMPOSITE_GRAPH_URI_PATH),
					(stepReport, ignore) -> assertCompositeGraphResponseSuccess(stepReport));

	public static ResponseConfig unmarshallCompositeGraphResponse() {
		return unmarshallResponse(
				afterStepContainingURIPathOfAny(COMPOSITE_GRAPH_URI_PATH),
				CompositeGraphResponse.class,
				CompositeGraphResponse.ADAPTER);
	}

	public static void assertCompositeGraphResponseSuccess(StepReport stepReport) {
		final var responseTxnInfo = stepReport.responseInfo.get();
		final var graphResp =
				responseTxnInfo
						.<CompositeGraphResponse>getTypedTxnObj(
								CompositeGraphResponse.class, List.of(CompositeGraphResponse.ADAPTER))
						.getGraphs()
						.get(0);
		assertTrue(
				graphResp.isSuccessful(),
				() -> {
					final var firstErrorResponse =
							CollectionsKt.first(((ErrorGraph) graphResp).errorResponses);
					final var firstErrorResponseBody = ((ErrorGraph) graphResp).firstErrorResponseBody;
					return String.format(
							"Unsuccessful Composite Graph response%n{%n  first error ReferenceId: %s%n  first errorCode: %s%n  first errorMessage: %s%n}%n%s",
							firstErrorResponse.getReferenceId(),
							firstErrorResponseBody.getErrorCode(),
							firstErrorResponseBody.getMessage(),
							stepReport);
				});
	}
}
