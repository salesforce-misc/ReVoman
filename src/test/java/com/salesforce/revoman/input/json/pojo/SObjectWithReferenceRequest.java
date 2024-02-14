/***************************************************************************************************
 *  Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier:
 *           Apache License Version 2.0
 *  For full license text, see the LICENSE file in the repo root or
 *  http://www.apache.org/licenses/LICENSE-2.0
 **************************************************************************************************/

package com.salesforce.revoman.input.json.pojo;

public class SObjectWithReferenceRequest {
  private final String referenceId;
  private final Entity record;

  public SObjectWithReferenceRequest(String referenceId, Entity record) {
    this.referenceId = referenceId;
    this.record = record;
  }

  public String getReferenceId() {
    return this.referenceId;
  }

  public Entity getRecord() {
    return this.record;
  }
}
