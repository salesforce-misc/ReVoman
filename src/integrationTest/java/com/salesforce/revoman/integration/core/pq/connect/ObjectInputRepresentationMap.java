package com.salesforce.revoman.integration.core.pq.connect;

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
