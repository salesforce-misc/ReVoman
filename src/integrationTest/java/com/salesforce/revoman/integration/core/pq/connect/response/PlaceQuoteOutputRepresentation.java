/***************************************************************************************************
 *  Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier:
 *           Apache License Version 2.0
 *  For full license text, see the LICENSE file in the repo root or
 *  http://www.apache.org/licenses/LICENSE-2.0
 **************************************************************************************************/

package com.salesforce.revoman.integration.core.pq.connect.response;

import java.util.List;

public class PlaceQuoteOutputRepresentation {
	private final String requestIdentifier;
	private String statusURL;
	private final ID quoteId;
	private final Boolean success;
	private final List<PlaceQuoteErrorResponseRepresentation> responseError;

	public PlaceQuoteOutputRepresentation(
			String requestIdentifier,
			String statusURL,
			ID quoteId,
			Boolean success,
			List<PlaceQuoteErrorResponseRepresentation> responseError) {
		super();
		this.requestIdentifier = requestIdentifier;
		this.statusURL = statusURL;
		this.success = success;
		this.responseError = responseError;
		this.quoteId = quoteId;
	}

	public String getRequestIdentifier() {
		return this.requestIdentifier;
	}

	public String getStatusURL() {
		return this.statusURL;
	}

	public void setStatusURL(String statusURL) {
		this.statusURL = statusURL;
	}

	public Boolean getSuccess() {
		return this.success;
	}

	public List<PlaceQuoteErrorResponseRepresentation> getResponseError() {
		return this.responseError;
	}

	public ID getQuoteId() {
		return quoteId;
	}
}
