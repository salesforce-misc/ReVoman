/***************************************************************************************************
 *  Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: 
 *           Apache License Version 2.0 
 *  For full license text, see the LICENSE file in the repo root or
 *  http://www.apache.org/licenses/LICENSE-2.0
 **************************************************************************************************/

package com.salesforce.revoman.integration.core.pq.connect;

import java.util.ArrayList;
import java.util.List;

public class ObjectWithReferenceInputRepresentationList {

  private List<ObjectWithReferenceInputRepresentation> recordsList;
  private boolean isSetRecordsList;

  public ObjectWithReferenceInputRepresentationList() {
    super();
    this.recordsList = new ArrayList<>();
  }

  public List<ObjectWithReferenceInputRepresentation> getRecordsList() {
    return this.recordsList;
  }

  public void setRecordsList(List<ObjectWithReferenceInputRepresentation> recordsList) {
    this.recordsList = recordsList;
    this.isSetRecordsList = true;
  }

  public boolean _isSetRecordsList() {
    return this.isSetRecordsList;
  }
}
