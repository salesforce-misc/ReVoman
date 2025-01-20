/***************************************************************************************************
 *  Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier:
 *           Apache License Version 2.0
 *  For full license text, see the LICENSE file in the repo root or
 *  http://www.apache.org/licenses/LICENSE-2.0
 **************************************************************************************************/

package com.salesforce.revoman.integration.core.pq.connect.request;

import java.util.HashMap;
import java.util.Map;

public class ObjectInputRepresentationMap {

	private Map<String, Object> recordBody;
	private boolean isSetRecordBody;

	public ObjectInputRepresentationMap() {
		this.recordBody = new HashMap<>();
	}

	public Map<String, Object> getRecordBody() {
		return this.recordBody;
	}

	public void setRecordBody(Map<String, Object> recordBody) {
		this.recordBody = recordBody;
		this.isSetRecordBody = true;
	}

	public boolean _isSetRecordBody() {
		return this.isSetRecordBody;
	}
}
