/***************************************************************************************************
 *  Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier:
 *           Apache License Version 2.0
 *  For full license text, see the LICENSE file in the repo root or
 *  http://www.apache.org/licenses/LICENSE-2.0
 **************************************************************************************************/

package com.salesforce.revoman.input.json.pojo;

import java.util.List;

public class SObjectGraphRequest {

  private final String graphId;
  private final List<SObjectWithReferenceRequest> records;

  public SObjectGraphRequest(String graphId, List<SObjectWithReferenceRequest> records) {
    this.graphId = graphId;
    this.records = records;
  }

  public String getGraphId() {
    return this.graphId;
  }

  public List<SObjectWithReferenceRequest> getRecords() {
    return this.records;
  }
}
