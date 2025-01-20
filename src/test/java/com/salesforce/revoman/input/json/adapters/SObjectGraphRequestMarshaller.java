/*
 * Copyright 2023 salesforce.com, inc.
 * All Rights Reserved
 * Company Confidential
 */

package com.salesforce.revoman.input.json.adapters;

import static com.salesforce.revoman.input.json.JsonWriterUtils.listW;
import static com.salesforce.revoman.input.json.JsonWriterUtils.mapW;
import static com.salesforce.revoman.input.json.JsonWriterUtils.objW;
import static com.salesforce.revoman.input.json.JsonWriterUtils.string;
import static java.util.Collections.emptySet;

import com.salesforce.revoman.input.json.pojo.SObjectGraphRequest;
import com.squareup.moshi.FromJson;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonReader;
import com.squareup.moshi.JsonWriter;
import com.squareup.moshi.ToJson;
import java.util.Map;
import java.util.Set;
import kotlin.collections.CollectionsKt;
import kotlin.collections.MapsKt;

/** A light-weight Marshaller/Deserializer to convert SObjectGraphRequest --> JSON */
public class SObjectGraphRequestMarshaller {

	static final String MASK = "******";
	private final Map<String, ?> paramsToWrite;
	private final Set<String> fieldNamesToMaskValues;

	private SObjectGraphRequestMarshaller(
			Map<String, ?> paramsToWrite, Set<String> fieldNamesToMaskValues) {
		this.paramsToWrite = paramsToWrite;
		this.fieldNamesToMaskValues = fieldNamesToMaskValues;
	}

	/**
	 * @param paramsToWrite Any extra params to insert inside the JSON, at the same level and outside
	 *     `graph`
	 */
	public static SObjectGraphRequestMarshaller adapter(Map<String, ?> paramsToWrite) {
		return new SObjectGraphRequestMarshaller(paramsToWrite, emptySet());
	}

	public static SObjectGraphRequestMarshaller adapter(
			Map<String, ?> paramsToWrite, Set<String> fieldNamesToMaskValues) {
		return new SObjectGraphRequestMarshaller(paramsToWrite, fieldNamesToMaskValues);
	}

	/** Marshals SObjectGraphRequest to PQ payload */
	@SuppressWarnings("unused")
	@ToJson
	public void toJson(
			JsonWriter writer,
			SObjectGraphRequest sObjectGraphRequest,
			JsonAdapter<Object> dynamicJsonAdapter) {
		objW(
				sObjectGraphRequest,
				writer,
				sogr -> {
					mapW(writer, paramsToWrite, dynamicJsonAdapter);
					objW(
							"graph",
							sogr,
							writer,
							sog -> {
								string("graphId", sog.getGraphId(), writer);
								listW(
										"records",
										sog.getRecords(),
										writer,
										sObj ->
												objW(
														sObj,
														writer,
														sowrr -> {
															string("referenceId", sowrr.getReferenceId(), writer);
															writer.name("record");
															final var fieldNameToValue = sowrr.getRecord().getFields();
															final var fieldNameToValueMaskedConditionally =
																	MapsKt.mapValues(
																			fieldNameToValue,
																			// * NOTE 17 Apr 2024 gopala.akshintala: Case insensitive
																			// comparison,
																			// because SObjectGraph is Case Insensitive
																			entry ->
																					CollectionsKt.any(
																									fieldNamesToMaskValues,
																									fieldName ->
																											fieldName.equalsIgnoreCase(entry.getKey()))
																							? MASK
																							: entry.getValue());
															dynamicJsonAdapter.toJson(
																	writer, fieldNameToValueMaskedConditionally);
														}));
							});
				});
	}

	@FromJson
	public SObjectGraphRequest fromJson(JsonReader ignore) {
		return null; // noop
	}
}
