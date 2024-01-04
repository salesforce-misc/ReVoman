/***************************************************************************************************
 *  Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier:
 *           Apache License Version 2.0
 *  For full license text, see the LICENSE file in the repo root or
 *  http://www.apache.org/licenses/LICENSE-2.0
 **************************************************************************************************/

package com.salesforce.revoman.integration.core.pq.connect;

public class PlaceQuoteInputRepresentation implements ConnectInputRepresentationWithGraph {

  private PricingPreferenceEnum pricingPref;

  private Boolean doAsync;
  private ObjectGraphInputRepresentation graph;
  private boolean isSetGraph;

  public Boolean getDoAsync() {
    return doAsync;
  }

  public ObjectGraphInputRepresentation getGraph() {
    return this.graph;
  }

  public void setGraph(ObjectGraphInputRepresentation graph) {
    this.graph = graph;
    this.isSetGraph = true;
  }

  public boolean _isSetGraph() {
    return this.isSetGraph;
  }

  private boolean isSetPricingPref;

  private boolean isSetDoAsync;

  public PricingPreferenceEnum getPricingPref() {
    return pricingPref;
  }

  public void setPricingPref(PricingPreferenceEnum pricingPref) {
    this.pricingPref = pricingPref;
    this.isSetPricingPref = true;
  }

  public void setDoAsync(Boolean doAsync) {
    this.doAsync = doAsync;
    this.isSetDoAsync = true;
  }

  public boolean _isSetPricingPref() {
    return this.isSetPricingPref;
  }
}
