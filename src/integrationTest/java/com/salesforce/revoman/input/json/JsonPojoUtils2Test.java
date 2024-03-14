/***************************************************************************************************
 *  Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier:
 *           Apache License Version 2.0
 *  For full license text, see the LICENSE file in the repo root or
 *  http://www.apache.org/licenses/LICENSE-2.0
 **************************************************************************************************/

package com.salesforce.revoman.input.json;

import static com.google.common.truth.Truth.assertThat;
import static com.salesforce.revoman.input.FileUtils.readFileInResourcesToString;
import static com.salesforce.revoman.integration.core.pq.adapters.ConnectInputRepWithGraphAdapter.adapter;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.salesforce.revoman.integration.core.pq.adapters.IDAdapter;
import com.salesforce.revoman.integration.core.pq.connect.request.PlaceQuoteInputRepresentation;
import com.salesforce.revoman.integration.core.pq.connect.request.PricingPreferenceEnum;
import com.salesforce.revoman.integration.core.pq.connect.response.PlaceQuoteOutputRepresentation;
import com.squareup.moshi.JsonDataException;
import java.util.List;
import org.json.JSONException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;

class JsonPojoUtils2Test {

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

  @Test
  @DisplayName("fromJson: PQ payload JSON --> PlaceQuoteInputRep")
  void pqPayloadJsonToPQInputRep() {
    final var pqAdapter = adapter(PlaceQuoteInputRepresentation.class);
    final var pqInputRep =
        JsonPojoUtils.<PlaceQuoteInputRepresentation>jsonFileToPojo(
            PlaceQuoteInputRepresentation.class, "json/pq-payload.json", List.of(pqAdapter));
    assertThat(pqInputRep).isNotNull();
    assertThat(pqInputRep.getPricingPref()).isEqualTo(PricingPreferenceEnum.System);
    assertThat(pqInputRep.getDoAsync()).isTrue();
    final var graph = pqInputRep.getGraph();
    assertThat(graph).isNotNull();
    assertThat(graph.getRecords().getRecordsList()).hasSize(6);
  }

  @Test
  @DisplayName("JSON --> PQ Response --> JSON")
  void unmarshallMarshallPqResponse() throws JSONException {
    final var pqResponseFromJson = readFileInResourcesToString("json/pq-response.json");
    final var pqResp =
        JsonPojoUtils.<PlaceQuoteOutputRepresentation>jsonToPojo(
            PlaceQuoteOutputRepresentation.class, pqResponseFromJson, List.of(new IDAdapter()));
    assertNotNull(pqResp);
    final var pqOutputRepJson =
        JsonPojoUtils.pojoToJson(
            PlaceQuoteOutputRepresentation.class, pqResp, List.of(new IDAdapter()));
    JSONAssert.assertEquals(pqResponseFromJson, pqOutputRepJson, JSONCompareMode.STRICT);
  }

  @Test
  @DisplayName("Read Enum Case Insensitive")
  void readEnumCaseInsensitive() {
    final var pojoWithEnum =
        JsonPojoUtils.<PojoWithEnum>jsonToPojo(
            PojoWithEnum.class, "{\n" + "  \"pricingPref\": \"SYSTEM\"\n" + "}");
    assertThat(pojoWithEnum).isNotNull();
    assertThat(pojoWithEnum.pricingPref).isEqualTo(PricingPreferenceEnum.System);
  }

  @Test
  @DisplayName("No Enum match found")
  void noEnumFound() {
    final var jsonDataException =
        assertThrows(
            JsonDataException.class,
            () -> {
              JsonPojoUtils.<PojoWithEnum>jsonToPojo(
                  PojoWithEnum.class, "{\n" + "  \"pricingPref\": \"XYZ\"\n" + "}");
            });
    assertThat(jsonDataException)
        .hasMessageThat()
        .contains("Expected one of [Force, Skip, System] but was XYZ at path $.pricingPref");
  }

  private static class PojoWithEnum {
    PricingPreferenceEnum pricingPref;
  }
}
