package com.salesforce.revoman.input.json.adapters

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.input.json.adapters.CompositeGraphResponse.Graph
import com.salesforce.revoman.input.json.adapters.CompositeGraphResponse.Graph.ErrorGraph
import com.salesforce.revoman.input.json.adapters.CompositeGraphResponse.Graph.SuccessGraph
import com.salesforce.revoman.input.json.factories.DiMorphicAdapter
import com.salesforce.revoman.input.readFileInResourcesToString
import com.squareup.moshi.Moshi
import com.squareup.moshi.adapter
import org.junit.jupiter.api.Test

class DiMorphicAdapterTest {
  @OptIn(ExperimentalStdlibApi::class)
  @Test
  fun `Graph Success --) POJO`() {
    val graphFactory =
      DiMorphicAdapter.of(
        Graph::class.java,
        "isSuccessful",
        true,
        SuccessGraph::class.java,
        ErrorGraph::class.java
      )
    val graphResponseAdapter =
      Moshi.Builder().add(graphFactory).build().adapter<CompositeGraphResponse>()
    val successGraphResponse =
      graphResponseAdapter.fromJson(
        readFileInResourcesToString("composite/graph/resp/graph-success-response.json")
      )
    assertThat(successGraphResponse?.graphs?.get(0)).isInstanceOf(SuccessGraph::class.java)
    val errorGraphResponse =
      graphResponseAdapter.fromJson(
        readFileInResourcesToString("composite/graph/resp/graph-error-response.json")
      )
    assertThat(errorGraphResponse?.graphs?.get(0)).isInstanceOf(ErrorGraph::class.java)
  }
}
