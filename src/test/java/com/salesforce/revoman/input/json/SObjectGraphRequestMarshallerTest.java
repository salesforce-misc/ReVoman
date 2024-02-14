/*
 * Copyright 2023 salesforce.com, inc.
 * All Rights Reserved
 * Company Confidential
 */

package com.salesforce.revoman.input.json;

import static com.salesforce.revoman.input.FileUtils.readFileInResourcesToString;

import com.salesforce.revoman.input.json.adapters.SObjectGraphRequestMarshaller;
import com.salesforce.revoman.input.json.pojo.Entity;
import com.salesforce.revoman.input.json.pojo.SObjectGraphRequest;
import com.salesforce.revoman.input.json.pojo.SObjectWithReferenceRequest;
import java.util.List;
import java.util.Map;
import org.json.JSONException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;

class SObjectGraphRequestMarshallerTest {

  @DisplayName("toJson: SObjectGraphRequest POJO --> PQ Payload JSON")
  @Test
  void sObjectGraphMarshallToPQPayload() throws JSONException {
    final var pqTestInputRepMarshaller =
        SObjectGraphRequestMarshaller.adapter(
            Map.of("pricingPref", "skip", "configurationInput", "skip"));
    final var pqPayloadJsonStr =
        JsonPojoUtils.pojoToJson(
            SObjectGraphRequest.class,
            prepareSObjectGraphReqPojo(),
            List.of(pqTestInputRepMarshaller));
    final var expectedPQPayload = readFileInResourcesToString("json/pq-graph-req.json");
    JSONAssert.assertEquals(expectedPQPayload, pqPayloadJsonStr, JSONCompareMode.STRICT);
  }

  static SObjectGraphRequest prepareSObjectGraphReqPojo() {
    return new SObjectGraphRequest(
        "pq-update-quote",
        List.of(
            new SObjectWithReferenceRequest(
                "refQuote",
                new Entity(
                    Map.of(
                        "attributes",
                        Map.of("type", "Quote", "method", "PATCH", "id", "quoteId"),
                        "Name",
                        "Overfullstack")))));
  }
}
