/***************************************************************************************************
 *  Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier:
 *           Apache License Version 2.0
 *  For full license text, see the LICENSE file in the repo root or
 *  http://www.apache.org/licenses/LICENSE-2.0
 **************************************************************************************************/

package com.salesforce.revoman.input.json;

import static com.salesforce.revoman.integration.core.pq.adapters.ConnectInputRepWithGraphAdapter.adapter;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.salesforce.revoman.integration.core.pq.connect.ObjectWithReferenceInputRepresentation;
import com.salesforce.revoman.integration.core.pq.connect.PlaceQuoteInputRepresentation;
import com.salesforce.revoman.integration.core.pq.connect.PricingPreferenceEnum;
import com.squareup.moshi.JsonDataException;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JsonReaderUtilsTest {

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
    assertThatExceptionOfType(JsonDataException.class)
        .isThrownBy(
            () -> {
              JsonPojoUtils.<PojoWithEnum>jsonToPojo(
                  PojoWithEnum.class, "{\n" + "  \"pricingPref\": \"XYZ\"\n" + "}");
            })
        .withMessage("Expected one of [Force, Skip, System] but was XYZ at path $.pricingPref");
  }

  private static class PojoWithEnum {
    PricingPreferenceEnum pricingPref;
  }

  @Test
  @DisplayName("Transform PQInputRep")
  void transformPqInputRep() {
    final var pqAdapter = adapter(PlaceQuoteInputRepresentation.class);
    final var pqInputRep =
        JsonPojoUtils.<PlaceQuoteInputRepresentation>jsonFileToPojo(
            PlaceQuoteInputRepresentation.class, "json/pq-payload.json", List.of(pqAdapter));
    assertThat(pqInputRep).isNotNull();
    final var pqInputRepTx = toJsonPreValueTransformer(pqInputRep);
    System.out.println(pqInputRepTx);
  }

  static List<ObjectWithReferenceInputRepresentation> toJsonPreValueTransformer(
      PlaceQuoteInputRepresentation placeQuoteInputRepresentation) {
    final var records = placeQuoteInputRepresentation.getGraph().getRecords().getRecordsList();
    return Collections.nCopies(5, records.get(0)).stream()
        .peek(rec -> rec.setReferenceId(rec.getReferenceId() + "x"))
        .collect(Collectors.toList());
  }
}
