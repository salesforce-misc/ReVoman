package com.salesforce.revoman.integration.core.pq.connect;

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
