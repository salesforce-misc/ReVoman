/***************************************************************************************************
 *  Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier:
 *           Apache License Version 2.0
 *  For full license text, see the LICENSE file in the repo root or
 *  http://www.apache.org/licenses/LICENSE-2.0
 **************************************************************************************************/

package com.salesforce.revoman.input.json;

import static com.salesforce.revoman.input.FileUtils.readFileInResourcesToString;
import static com.salesforce.revoman.integration.core.pq.adapters.ConnectInputRepWithGraphAdapter.adapter;
import static org.assertj.core.api.Assertions.assertThat;

import com.salesforce.revoman.integration.core.pq.connect.PlaceQuoteInputRepresentation;
import java.util.List;
import org.json.JSONException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;

class JsonWriterUtilsTest {

  @Test
  @DisplayName("toJson: PQ Payload JSON -> PlaceQuoteInputRep -> PQ Payload JSON")
  void pqInputRepToPQPayloadJson() throws JSONException {
    final var pqAdapter = adapter(PlaceQuoteInputRepresentation.class);
    final var pqPayloadJsonFilePath = "json/pq-payload.json";
    final var pqInputRep =
        JsonPojoUtils.<PlaceQuoteInputRepresentation>jsonFileToPojo(
            PlaceQuoteInputRepresentation.class, pqPayloadJsonFilePath, List.of(pqAdapter));
    assertThat(pqInputRep).isNotNull();
    final var pqPayloadJsonStr =
        JsonPojoUtils.pojoToJson(
            PlaceQuoteInputRepresentation.class, pqInputRep, List.of(pqAdapter));
    final var expectedPQPayload = readFileInResourcesToString(pqPayloadJsonFilePath);
    JSONAssert.assertEquals(expectedPQPayload, pqPayloadJsonStr, JSONCompareMode.STRICT);
  }
}
