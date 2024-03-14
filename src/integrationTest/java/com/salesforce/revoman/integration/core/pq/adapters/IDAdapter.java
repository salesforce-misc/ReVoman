/***************************************************************************************************
 *  Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier:
 *           Apache License Version 2.0
 *  For full license text, see the LICENSE file in the repo root or
 *  http://www.apache.org/licenses/LICENSE-2.0
 **************************************************************************************************/

package com.salesforce.revoman.integration.core.pq.adapters;

import com.salesforce.revoman.integration.core.pq.connect.response.ID;
import com.squareup.moshi.FromJson;
import com.squareup.moshi.ToJson;

// * NOTE 10 Mar 2024 gopala.akshintala: Custom Type Adapter
public class IDAdapter {
  @FromJson
  ID fromJson(String id) {
    return new ID(id);
  }

  @ToJson
  String toJson(ID id) {
    return id.getId();
  }
}
