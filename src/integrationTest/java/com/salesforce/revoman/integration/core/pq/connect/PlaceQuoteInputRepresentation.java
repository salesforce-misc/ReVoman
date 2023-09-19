package com.salesforce.revoman.integration.core.pq.connect;

public class PlaceQuoteInputRepresentation extends ConnectInputRepresentationWithGraph {

  private PricingPreferenceEnum pricingPref;

  private Boolean doAsync;

  private boolean isSetPricingPref;

  private boolean isSetDoAsync;

  public PricingPreferenceEnum getPricingPref() {
    return pricingPref;
  }

  public void setPricingPref(PricingPreferenceEnum pricingPref) {
    this.pricingPref = pricingPref;
    this.isSetPricingPref = true;
  }

  public Boolean getDoAsync() {
    return doAsync;
  }

  public void setDoAsync(Boolean doAsync) {
    this.doAsync = doAsync;
    this.isSetDoAsync = true;
  }

  public boolean _isSetPricingPref() {
    return this.isSetPricingPref;
  }
}
