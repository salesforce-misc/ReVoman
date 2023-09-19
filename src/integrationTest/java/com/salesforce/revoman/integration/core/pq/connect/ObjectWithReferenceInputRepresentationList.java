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
