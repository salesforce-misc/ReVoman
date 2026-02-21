/***************************************************************************************************
 *  Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier:
 *           Apache License Version 2.0
 *  For full license text, see the LICENSE file in the repo root or
 *  http://www.apache.org/licenses/LICENSE-2.0
 **************************************************************************************************/

package com.salesforce.revoman.integration.core.pq.connect.request;

public class ObjectWithReferenceInputRepresentation {

  private String referenceId;
  private ObjectInputRepresentationMap record;

  private boolean isSetReferenceId;
  private boolean isSetRecord;

  public ObjectInputRepresentationMap getRecord() {
    return this.record;
  }

  public void setRecord(ObjectInputRepresentationMap record) {
    this.record = record;
    this.isSetRecord = true;
  }

  public String getReferenceId() {
    return this.referenceId;
  }

  public void setReferenceId(String referenceId) {
    this.referenceId = referenceId;
    this.isSetReferenceId = true;
  }

  public boolean _isSetRecord() {
    return this.isSetRecord;
  }

  public boolean _isSetReferenceId() {
    return this.isSetReferenceId;
  }
}
