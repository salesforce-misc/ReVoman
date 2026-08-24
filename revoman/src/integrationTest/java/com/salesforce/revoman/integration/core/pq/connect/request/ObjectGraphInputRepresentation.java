/***************************************************************************************************
 *  Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier:
 *           Apache License Version 2.0
 *  For full license text, see the LICENSE file in the repo root or
 *  http://www.apache.org/licenses/LICENSE-2.0
 **************************************************************************************************/

package com.salesforce.revoman.integration.core.pq.connect.request;

public class ObjectGraphInputRepresentation {

  private String graphId;

  private ObjectWithReferenceInputRepresentationList records;

  private boolean isSetGraphId;
  private boolean isSetRecords;

  public String getGraphId() {
    return this.graphId;
  }

  public void setGraphId(String graphId) {
    this.graphId = graphId;
    this.isSetGraphId = true;
  }

  public ObjectWithReferenceInputRepresentationList getRecords() {
    return this.records;
  }

  public void setRecords(ObjectWithReferenceInputRepresentationList records) {
    this.records = records;
    this.isSetRecords = true;
  }

  public boolean _isSetGraphId() {
    return this.isSetGraphId;
  }

  public boolean _isSetRecords() {
    return this.isSetRecords;
  }
}
