/***************************************************************************************************
 *  Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier:
 *           Apache License Version 2.0
 *  For full license text, see the LICENSE file in the repo root or
 *  http://www.apache.org/licenses/LICENSE-2.0
 **************************************************************************************************/

package com.salesforce.revoman.input.json.pojo;

import java.util.List;
import java.util.Map;

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

  public static class Entity {
    private final Map<String, Object> fields;

    public Entity(Map<String, Object> fields) {
      this.fields = fields;
    }

    public Map<String, Object> getFields() {
      return fields;
    }
  }

  public static class SObjectWithReferenceRequest {
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
}
