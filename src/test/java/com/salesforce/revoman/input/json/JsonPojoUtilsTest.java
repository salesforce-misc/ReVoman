/***************************************************************************************************
 *  Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier:
 *           Apache License Version 2.0
 *  For full license text, see the LICENSE file in the repo root or
 *  http://www.apache.org/licenses/LICENSE-2.0
 **************************************************************************************************/

package com.salesforce.revoman.input.json;

import static com.google.common.truth.Truth.assertThat;
import static com.salesforce.revoman.input.FileUtils.readFileToString;

import com.salesforce.revoman.input.json.adapters.SObjectGraphRequestMarshaller;
import com.salesforce.revoman.input.json.adapters.salesforce.CompositeGraphResponse;
import com.salesforce.revoman.input.json.adapters.salesforce.CompositeGraphResponse.Graph.ErrorGraph;
import com.salesforce.revoman.input.json.adapters.salesforce.CompositeGraphResponse.Graph.SuccessGraph;
import com.salesforce.revoman.input.json.adapters.salesforce.CompositeResponse;
import com.salesforce.revoman.input.json.adapters.salesforce.CompositeResponse.Response.ErrorResponse;
import com.salesforce.revoman.input.json.adapters.salesforce.CompositeResponse.Response.SuccessResponse;
import com.salesforce.revoman.input.json.pojo.SObjectGraphRequest;
import com.salesforce.revoman.input.json.pojo.SObjectGraphRequest.Entity;
import com.salesforce.revoman.input.json.pojo.SObjectGraphRequest.SObjectWithReferenceRequest;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;

class JsonPojoUtilsTest {

	@Test
	@DisplayName("DiMorphic CompositeGraph Success/Error Graph --> POJO --> JSON")
	void compositeGraphResponseDiMorphicMarshallUnmarshall() throws JSONException {
		// JSON --> POJO
		final var jsonFileConfig =
				JsonFile.<CompositeGraphResponse>unmarshall()
						.pojoType(CompositeGraphResponse.class)
						.customAdapter(CompositeGraphResponse.ADAPTER);

		final var successGraphResponse =
				JsonPojoUtils.jsonFileToPojo(
						jsonFileConfig.jsonFilePath("composite/graph/resp/graph-response-success.json").done());
		assertThat(successGraphResponse.getGraphs().getFirst()).isInstanceOf(SuccessGraph.class);

		final var errorGraphResponse =
				JsonPojoUtils.jsonFileToPojo(
						jsonFileConfig.jsonFilePath("composite/graph/resp/graph-response-error.json").done());
		final var errorGraph = errorGraphResponse.getGraphs().getFirst();
		assertThat(errorGraph).isInstanceOf(ErrorGraph.class);
		assertThat(((ErrorGraph) errorGraph).firstErrorResponseBody().getErrorCode())
				.isEqualTo("DUPLICATE_VALUE");

		// POJO --> JSON
		final var pojoToJsonConfig =
				Pojo.<CompositeGraphResponse>marshall()
						.pojoType(CompositeGraphResponse.class)
						.customAdapter(CompositeGraphResponse.ADAPTER);

		final var successGraphResponseUnmarshalled =
				JsonPojoUtils.pojoToJson(pojoToJsonConfig.pojo(successGraphResponse).done());
		JSONAssert.assertEquals(
				readFileToString("composite/graph/resp/graph-response-success.json"),
				successGraphResponseUnmarshalled,
				JSONCompareMode.STRICT);

		final var errorGraphResponseUnmarshalled =
				JsonPojoUtils.pojoToJson(pojoToJsonConfig.pojo(errorGraphResponse).done());
		JSONAssert.assertEquals(
				readFileToString("composite/graph/resp/graph-response-error.json"),
				errorGraphResponseUnmarshalled,
				JSONCompareMode.STRICT);
	}

	@Test
	@DisplayName("DiMorphic Composite Success/Error Response --> POJO --> JSON")
	void compositeResponseDiMorphicMarshallUnmarshall() throws JSONException {
		// JSON --> POJO
		final var jsonFileConfig =
				JsonFile.<CompositeResponse>unmarshall()
						.pojoType(CompositeResponse.class)
						.customAdapter(CompositeResponse.ADAPTER);

		final var successCompositeQueryResponse =
				JsonPojoUtils.jsonFileToPojo(
						jsonFileConfig
								.jsonFilePath("composite/query/resp/query-response-all-success.json")
								.done());
		assertThat(successCompositeQueryResponse.isSuccessful()).isTrue();
		successCompositeQueryResponse
				.getCompositeResponse()
				.forEach(
						successResponse -> assertThat(successResponse).isInstanceOf(SuccessResponse.class));

		final var errorCompositeQueryResponse =
				JsonPojoUtils.jsonFileToPojo(
						jsonFileConfig
								.jsonFilePath("composite/query/resp/query-response-all-error.json")
								.done());
		assertThat(errorCompositeQueryResponse.isSuccessful()).isFalse();
		errorCompositeQueryResponse
				.getCompositeResponse()
				.forEach(errorResponse -> assertThat(errorResponse).isInstanceOf(ErrorResponse.class));
		assertThat(errorCompositeQueryResponse.firstErrorResponseBody().getMessage())
				.contains("Invalid reference specified");

		final var partialSuccessCompositeQueryResponse =
				JsonPojoUtils.jsonFileToPojo(
						jsonFileConfig
								.jsonFilePath("composite/query/resp/query-response-partial-success.json")
								.done());
		assertThat(partialSuccessCompositeQueryResponse.isSuccessful()).isFalse();
		assertThat(partialSuccessCompositeQueryResponse.getCompositeResponse().get(0))
				.isInstanceOf(SuccessResponse.class);
		assertThat(partialSuccessCompositeQueryResponse.getCompositeResponse().get(1))
				.isInstanceOf(ErrorResponse.class);
		assertThat(partialSuccessCompositeQueryResponse.getCompositeResponse().get(2))
				.isInstanceOf(ErrorResponse.class);
		assertThat(partialSuccessCompositeQueryResponse.firstErrorResponseBody().getMessage())
				.contains("Invalid reference specified");

		// POJO --> JSON
		final var pojoToJsonConfig =
				Pojo.<CompositeResponse>marshall()
						.pojoType(CompositeResponse.class)
						.customAdapter(CompositeResponse.ADAPTER);

		final var successCompositeQueryResponseUnmarshalled =
				JsonPojoUtils.pojoToJson(pojoToJsonConfig.pojo(successCompositeQueryResponse).done());
		JSONAssert.assertEquals(
				readFileToString("composite/query/resp/query-response-all-success.json"),
				successCompositeQueryResponseUnmarshalled,
				JSONCompareMode.STRICT);

		final var errorCompositeQueryResponseUnmarshalled =
				JsonPojoUtils.pojoToJson(pojoToJsonConfig.pojo(errorCompositeQueryResponse).done());
		JSONAssert.assertEquals(
				readFileToString("composite/query/resp/query-response-all-error.json"),
				errorCompositeQueryResponseUnmarshalled,
				JSONCompareMode.STRICT);

		final var partialSuccessCompositeQueryResponseUnmarshalled =
				JsonPojoUtils.pojoToJson(
						pojoToJsonConfig.pojo(partialSuccessCompositeQueryResponse).done());
		JSONAssert.assertEquals(
				readFileToString("composite/query/resp/query-response-partial-success.json"),
				partialSuccessCompositeQueryResponseUnmarshalled,
				JSONCompareMode.STRICT);
	}

	@DisplayName("toJson: SObjectGraphRequest POJO --> PQ Payload JSON")
	@Test
	void sObjectGraphMarshallToPQPayload() throws JSONException {
		final var pqTestInputRepMarshaller =
				SObjectGraphRequestMarshaller.adapter(
						Map.of("pricingPref", "skip", "configurationInput", "skip"), Set.of("name"));
		final var pqPayloadJsonStr =
				JsonPojoUtils.pojoToJson(
						SObjectGraphRequest.class,
						prepareSObjectGraphReqPojo(),
						List.of(pqTestInputRepMarshaller));
		final var expectedPQPayload = readFileToString("json/pq-graph-req-masked.json");
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

	@Test
	@DisplayName("json file To Pojo")
	void jsonFileToPojo() {
		final var nestedBeanFromJson =
				JsonPojoUtils.<NestedBean>jsonFileToPojo(NestedBean.class, "json/nested-bean.json");
		assertThat(nestedBeanFromJson).isNotNull();
		assertThat(nestedBeanFromJson.getName()).isEqualTo("container");
		assertThat(nestedBeanFromJson.getBean().getItems()).hasSize(2);
	}

	@Test
	@DisplayName("Simple JSON to Map")
	void simpleJsonToMap() {
		// language=json
		final var json =
				"""
				{
					"key1": "value1",
					"key2": "value2"
				}
				""";
		final var mapFromJSON = JsonPojoUtils.<Map<String, String>>jsonToPojo(Map.class, json);
		assertThat(mapFromJSON).isNotNull();
		assertThat(mapFromJSON).containsExactlyEntriesIn(Map.of("key1", "value1", "key2", "value2"));
	}

	@Test
	@DisplayName("json with Epoch Date To Pojo")
	void jsonWithEpochDateToPojo() {
		final var epochDate = 1604216172747L;
		final var beanWithDate =
				JsonPojoUtils.<BeanWithDate>jsonToPojo(BeanWithDate.class, "{\"date\": " + epochDate + "}");
		assertThat(beanWithDate).isNotNull();
		assertThat(beanWithDate.date.toInstant().toEpochMilli()).isEqualTo(epochDate);
		final var beanWithDateJson = JsonPojoUtils.pojoToJson(BeanWithDate.class, beanWithDate);
		assertThat(beanWithDateJson).isNotNull();
	}

	@Test
	@DisplayName("json with ISO Date To Pojo")
	void jsonWithISODateToPojo() throws ParseException {
		final var date = "2015-09-01";
		final var beanWithDate =
				JsonPojoUtils.<BeanWithDate>jsonToPojo(BeanWithDate.class, "{\"date\": \"" + date + "\"}");
		assertThat(beanWithDate).isNotNull();
		final var formatter = new SimpleDateFormat("yyyy-MM-dd");
		assertThat(beanWithDate.date).isEqualTo(formatter.parse(date));
	}

	@Test
	@DisplayName("pojo to json")
	void pojoToJson() {
		final var nestedBean = new NestedBean("container", new Bean("bean", List.of("item1", "item2")));
		final var nestedBeanJson = JsonPojoUtils.pojoToJson(NestedBean.class, nestedBean);
		System.out.println(nestedBeanJson);
		assertThat(nestedBeanJson).isNotEmpty();
	}

	private static class Bean {
		private final String name;
		private final List<String> items;

		private Bean(String name, List<String> items) {
			this.name = name;
			this.items = items;
		}

		public String getName() {
			return name;
		}

		public List<String> getItems() {
			return items;
		}
	}

	private static class NestedBean {
		private final String name;
		private final Bean bean;

		private NestedBean(String name, Bean bean) {
			this.name = name;
			this.bean = bean;
		}

		public String getName() {
			return name;
		}

		public Bean getBean() {
			return bean;
		}
	}

	private static class BeanWithDate {
		private final Date date;

		private BeanWithDate(Date date) {
			this.date = date;
		}

		public Date getDate() {
			return date;
		}

		@Override
		public String toString() {
			return "BeanWithDate{" + "date=" + date + '}';
		}
	}
}
