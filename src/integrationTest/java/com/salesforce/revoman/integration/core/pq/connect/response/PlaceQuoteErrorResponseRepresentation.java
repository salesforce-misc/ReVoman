/***************************************************************************************************
 *  Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier:
 *           Apache License Version 2.0
 *  For full license text, see the LICENSE file in the repo root or
 *  http://www.apache.org/licenses/LICENSE-2.0
 **************************************************************************************************/

package com.salesforce.revoman.integration.core.pq.connect.response;

public class PlaceQuoteErrorResponseRepresentation {
	private final String referenceId;
	private final String errorCode;
	private final String message;

	public PlaceQuoteErrorResponseRepresentation(
			final String referenceId, final String errorCode, final String message) {
		super();
		this.referenceId = referenceId;
		this.errorCode = errorCode;
		this.message = message;
	}

	public String getMessage() {
		return message;
	}

	public String getErrorCode() {
		return errorCode;
	}

	public String getReferenceId() {
		return referenceId;
	}
}
