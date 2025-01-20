/***************************************************************************************************
 *  Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier:
 *           Apache License Version 2.0
 *  For full license text, see the LICENSE file in the repo root or
 *  http://www.apache.org/licenses/LICENSE-2.0
 **************************************************************************************************/

package com.salesforce.revoman.output.postman;

import static com.google.common.truth.Truth.assertThat;

import com.squareup.moshi.Types;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PostmanEnvironmentTest {
	@Test
	@DisplayName("Multi level filtering")
	void multiLevelFiltering() {
		final var postmanEnv =
				new PostmanEnvironment<>(
						Map.of(
								"standardPricebookId", "fakeStandardPricebookId",
								"accessToken", "fakeAccessToken",
								"salesRepPsgId", "fakePsgId",
								"salesRepUserId", "fakeUserId",
								"mockTaxAdapterId", "mockTaxAdapterId"));
		final var filteredEnv =
				postmanEnv
						.mutableEnvCopyExcludingKeys(String.class, Set.of("standardPricebookId"))
						.mutableEnvCopyWithKeysEndingWith(String.class, "Id")
						.mutableEnvCopyWithKeysNotEndingWith(String.class, "PsgId", "UserId");
		assertThat(filteredEnv)
				.containsExactlyEntriesIn(Map.of("mockTaxAdapterId", "mockTaxAdapterId"));
	}

	@Test
	@DisplayName("get Typed Obj")
	void getTypedObj() {
		final var env =
				io.vavr.collection.HashMap.of(
						"key1", 1, "key2", "2", "key3", List.of(1, 2, 3), "key4", Map.of("4", 4), "key5", null);
		final var pm = new PostmanEnvironment<>(env.toJavaMap());
		assertThat(pm.<Integer>getTypedObj("key1", Integer.class)).isEqualTo(env.get("key1").get());
		assertThat(pm.<String>getTypedObj("key2", String.class)).isEqualTo(env.get("key2").get());
		assertThat(
						pm.<List<Integer>>getTypedObj(
								"key3", Types.newParameterizedType(List.class, Integer.class)))
				.containsExactlyElementsIn((Iterable<?>) env.get("key3").get());
		assertThat(
						pm.<Map<String, Integer>>getTypedObj(
								"key4", Types.newParameterizedType(Map.class, String.class, Integer.class)))
				.containsExactlyEntriesIn((Map<?, ?>) env.get("key4").get());
		assertThat(pm.<Object>getTypedObj("key5", Object.class)).isNull();
	}
}
