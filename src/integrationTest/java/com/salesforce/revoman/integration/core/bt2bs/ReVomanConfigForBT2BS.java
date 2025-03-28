/*
 * Copyright 2025 salesforce.com, inc.
 * All Rights Reserved
 * Company Confidential
 */

package com.salesforce.revoman.integration.core.bt2bs;

import static com.salesforce.revoman.input.config.HookConfig.post;
import static com.salesforce.revoman.input.config.StepPick.PostTxnStepPick.afterStepContainingHeader;
import static com.salesforce.revoman.input.config.StepPick.PostTxnStepPick.afterStepContainingURIPathOfAny;
import static com.salesforce.revoman.integration.core.CoreUtils.ASSERT_COMPOSITE_GRAPH_RESPONSE_SUCCESS;
import static com.salesforce.revoman.integration.core.CoreUtils.ASSERT_COMPOSITE_RESPONSE_SUCCESS;
import static com.salesforce.revoman.integration.core.CoreUtils.unmarshallCompositeGraphResponse;
import static com.salesforce.revoman.integration.core.CoreUtils.unmarshallCompositeResponse;
import static com.salesforce.revoman.output.ExeType.HTTP_STATUS;

import com.salesforce.revoman.input.config.HookConfig;
import com.salesforce.revoman.input.config.Kick;
import com.salesforce.revoman.integration.core.adapters.IDAdapter;

public final class ReVomanConfigForBT2BS {

	private ReVomanConfigForBT2BS() {}

	static final String ENV_PATH = "pm-templates/core/milestone/env.postman_environment.json";
	static final String IGNORE_HTTP_STATUS_UNSUCCESSFUL = "ignoreHTTPStatusUnsuccessful";
	static final String NODE_MODULE_RELATIVE_PATH = "js";

	// ## User creation and Setup
	static final String COLLECTION_PATH = "pm-templates/core/milestone/";
	private static final String PERSONA_CREATION_AND_SETUP_COLLECTION_PATH =
			COLLECTION_PATH + "persona-creation-and-setup.postman_collection.json";
	static final Kick PERSONA_CREATION_AND_SETUP_CONFIG =
			Kick.configure()
					.templatePath(PERSONA_CREATION_AND_SETUP_COLLECTION_PATH)
					.environmentPath(ENV_PATH)
					.responseConfig(unmarshallCompositeGraphResponse(), unmarshallCompositeResponse())
					.hooks(ASSERT_COMPOSITE_GRAPH_RESPONSE_SUCCESS, ASSERT_COMPOSITE_RESPONSE_SUCCESS)
					.nodeModulesPath(NODE_MODULE_RELATIVE_PATH)
					.haltOnFailureOfTypeExcept(
							HTTP_STATUS, afterStepContainingHeader(IGNORE_HTTP_STATUS_UNSUCCESSFUL))
					.insecureHttp(true)
					.off();

	// ## Hooks
	static final String PST = "connect/rev/sales-transaction/actions/place";
	static final String STANDALONE_INVOICE_IA =
			"commerce/invoicing/invoices/collection/actions/generate";
	static final String ASSETIZE_IA = "actions/standard/createOrUpdateAssetFromOrder";
	static final String AMEND_API = "connect/revenue-management/assets/actions/amend";
	static final String CANCEL_API = "connect/revenue-management/assets/actions/cancel";
	public static final HookConfig MEMQ_AWAIT =
			post(
					afterStepContainingURIPathOfAny(
							PST, STANDALONE_INVOICE_IA, ASSETIZE_IA, AMEND_API, CANCEL_API),
					(ignore1, ignore2) -> Thread.sleep(5000));

	// ## Milestone Setup Config
	private static final String MB_SETUP_POSTMAN_COLLECTION_PATH =
			COLLECTION_PATH + "milestone-setup.postman_collection.json";
	static final Kick MILESTONE_SETUP_CONFIG =
			Kick.configure()
					.templatePath(MB_SETUP_POSTMAN_COLLECTION_PATH)
					.haltOnFailureOfTypeExcept(
							HTTP_STATUS, afterStepContainingHeader(IGNORE_HTTP_STATUS_UNSUCCESSFUL))
					.responseConfig(unmarshallCompositeGraphResponse(), unmarshallCompositeResponse())
					.hooks(ASSERT_COMPOSITE_GRAPH_RESPONSE_SUCCESS, ASSERT_COMPOSITE_RESPONSE_SUCCESS)
					.haltOnFailureOfTypeExcept(
							HTTP_STATUS, afterStepContainingHeader(IGNORE_HTTP_STATUS_UNSUCCESSFUL))
					.globalCustomTypeAdapter(IDAdapter.INSTANCE)
					.nodeModulesPath(NODE_MODULE_RELATIVE_PATH)
					.off();

	// ## Milestone Config
	private static final String MB_POSTMAN_COLLECTION_PATH =
			COLLECTION_PATH + "bmp-create-runtime.postman_collection.json";
	static final Kick MILESTONE_CONFIG =
			Kick.configure()
					.templatePath(MB_POSTMAN_COLLECTION_PATH)
					.responseConfig(unmarshallCompositeGraphResponse(), unmarshallCompositeResponse())
					.hooks(
							MEMQ_AWAIT,
							ASSERT_COMPOSITE_GRAPH_RESPONSE_SUCCESS,
							ASSERT_COMPOSITE_RESPONSE_SUCCESS)
					.haltOnFailureOfTypeExcept(
							HTTP_STATUS, afterStepContainingHeader(IGNORE_HTTP_STATUS_UNSUCCESSFUL))
					.globalCustomTypeAdapter(IDAdapter.INSTANCE)
					.nodeModulesPath(NODE_MODULE_RELATIVE_PATH)
					.off();
}
