package com.salesforce.revoman.integration.core.pq.connect;

public class ConnectInputRepresentationWithGraph {
  private ObjectGraphInputRepresentation graph;
  private boolean isSetGraph;

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
}
