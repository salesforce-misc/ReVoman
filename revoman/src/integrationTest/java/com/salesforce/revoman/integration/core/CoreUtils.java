/***************************************************************************************************
 *  Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier:
 *           Apache License Version 2.0
 *  For full license text, see the LICENSE file in the repo root or
 *  http://www.apache.org/licenses/LICENSE-2.0
 **************************************************************************************************/

package com.salesforce.revoman.integration.core;

import static com.salesforce.revoman.input.config.HookConfig.post;
import static com.salesforce.revoman.input.config.ResponseConfig.unmarshallResponse;
import static com.salesforce.revoman.input.config.StepPick.PostTxnStepPick.afterStepContainingURIPathOfAny;
import static com.salesforce.revoman.input.config.StepPick.PostTxnStepPick.afterStepEndingWithURIPathOfAny;
import static com.salesforce.revoman.output.report.StepReport.containsHeader;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.salesforce.revoman.input.config.HookConfig;
import com.salesforce.revoman.input.config.ResponseConfig;
import com.salesforce.revoman.input.json.adapters.salesforce.CompositeGraphResponse;
import com.salesforce.revoman.input.json.adapters.salesforce.CompositeGraphResponse.Graph.ErrorGraph;
import com.salesforce.revoman.input.json.adapters.salesforce.CompositeResponse;
import com.salesforce.revoman.output.report.StepReport;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.yaml.snakeyaml.Yaml;

public class CoreUtils {
  private CoreUtils() {}

  /** The three creds a `core.*` E2E needs before it can hit a real org. */
  private static final List<String> ORG_CRED_KEYS = List.of("baseUrl", "username", "password");

  /**
   * Skip-loud guard for the `integration.core.*` E2Es. These need a real Salesforce/core org, so
   * (unlike a mock-server test) they must NOT hard-fail on a machine without one — e.g. CI, where
   * they're already excluded from aggregate runs, or a dev box running them explicitly via {@code
   * -PincludeCoreIT} before filling creds.
   *
   * <p>Reads the test's OWN Postman env file ([envResourcePath], JSON or YAML — both share the
   * {@code values: [{key, value}]} shape) off the classpath and JUnit-skips (via {@code
   * assumeTrue}) unless {@code baseUrl}, {@code username}, and {@code password} are ALL present and
   * non-blank. The env files ship blank and a developer fills them locally (see each test's setup
   * note). The skip message names the exact file to populate, so a skipped run reads as actionable,
   * not silent.
   */
  public static void assumeOrgCredsPresent(String envResourcePath) {
    final var env = readClasspathEnv(envResourcePath);
    final var missing = ORG_CRED_KEYS.stream().filter(key -> isBlank(env.get(key))).toList();
    assumeTrue(
        missing.isEmpty(),
        () ->
            "SKIPPING core E2E: missing org creds "
                + missing
                + " in classpath env `"
                + envResourcePath
                + "`. This test needs a real Salesforce/core org — fill baseUrl/username/password"
                + " there (or run without -PincludeCoreIT to exclude it from aggregate builds).");
  }

  /**
   * Flatten a Postman-shape env resource ({@code {values: [{key, value}, ...]}}) into a plain
   * key→value map. JSON is a subset of YAML, so one snakeyaml parse covers both the v2 `.json` and
   * v3 `.yaml` env files. A missing/malformed resource yields an empty map (→ the guard skips).
   */
  private static Map<String, String> readClasspathEnv(String envResourcePath) {
    try (final InputStream in =
        CoreUtils.class.getClassLoader().getResourceAsStream(envResourcePath)) {
      if (in == null) {
        return Map.of();
      }
      final Map<String, Object> root = new Yaml().load(in);
      final var values = root == null ? null : root.get("values");
      if (!(values instanceof List<?> entries)) {
        return Map.of();
      }
      return entries.stream()
          .filter(Map.class::isInstance)
          .map(Map.class::cast)
          .filter(entry -> entry.get("key") != null && entry.get("value") != null)
          .collect(
              java.util.stream.Collectors.toMap(
                  entry -> String.valueOf(entry.get("key")),
                  entry -> String.valueOf(entry.get("value")),
                  (first, second) -> second));
    } catch (Exception e) {
      return Map.of();
    }
  }

  private static boolean isBlank(String value) {
    return Objects.requireNonNullElse(value, "").isBlank();
  }

  public static final String COMPOSITE_GRAPH_URI_PATH = "composite/graph";

  /** Reusable Response config for CompositeGraphResponse to enhance debugging experience */
  public static ResponseConfig unmarshallCompositeGraphResponse() {
    return unmarshallResponse(
        afterStepContainingURIPathOfAny(COMPOSITE_GRAPH_URI_PATH),
        CompositeGraphResponse.class,
        CompositeGraphResponse.ADAPTER);
  }

  /**
   * Reusable ReVoman Post-Step hooks that can be added to your configuration to assert
   * CompositeGraphResponse(success/error). For expected failure response, this expects you to add
   * `expectToFail=true` header to your request in Postman If you have to use a different header,
   * compose your own hook using {@link #assertCompositeGraphResponseSuccess(StepReport, String)}
   */
  public static final HookConfig ASSERT_COMPOSITE_GRAPH_RESPONSE_SUCCESS =
      post(
          afterStepContainingURIPathOfAny(COMPOSITE_GRAPH_URI_PATH),
          (stepReport, ignore) -> assertCompositeGraphResponseSuccess(stepReport));

  public static final String EXPECT_TO_FAIL_HEADER = "expectToFail";

  private static final String UNSUCCESSFUL_COMPOSITE_GRAPH_RESPONSE_ERROR_MSG =
      """
            Unsuccessful Composite Graph response for graphId: %s
            {
                "first errorCode": "%s"
                "first errorMessage: "%s"
            }
            StepReport:
            %s""";

  public static void assertCompositeGraphResponseSuccess(StepReport stepReport) {
    assertCompositeGraphResponseSuccess(stepReport, EXPECT_TO_FAIL_HEADER);
  }

  static void assertCompositeGraphResponseSuccess(
      StepReport stepReport, String expectToFailHeader) {
    final var responseTxnInfo = stepReport.responseInfo.get();
    final var graphsResp =
        responseTxnInfo
            .<CompositeGraphResponse>getTypedTxnObj(
                CompositeGraphResponse.class, List.of(CompositeGraphResponse.ADAPTER))
            .getGraphs();
    for (var graphResp : graphsResp) {
      assertTrue(
          graphResp.isSuccessful() || containsHeader(stepReport.requestInfo, expectToFailHeader),
          () -> {
            final var firstErrorResponseBody = ((ErrorGraph) graphResp).firstErrorResponseBody();
            return UNSUCCESSFUL_COMPOSITE_GRAPH_RESPONSE_ERROR_MSG.formatted(
                graphResp.getGraphId(),
                firstErrorResponseBody.getErrorCode(),
                firstErrorResponseBody.getMessage(),
                stepReport);
          });
    }
  }

  public static final String COMPOSITE_URI_PATH = "composite";

  /** Reusable Response config for CompositeGraphResponse to enhance debugging experience */
  public static ResponseConfig unmarshallCompositeResponse() {
    return unmarshallResponse(
        afterStepEndingWithURIPathOfAny(COMPOSITE_URI_PATH),
        CompositeResponse.class,
        CompositeResponse.ADAPTER);
  }

  /**
   * Reusable ReVoman Post-Step hooks that can be added to your configuration to assert
   * CompositeResponse(success/error). For expected failure response, this expects you to add
   * `expectToFail=true` header to your request in Postman If you have to use a different header,
   * compose your own hook using {@link #assertCompositeResponseSuccess(StepReport, String)}
   */
  public static final HookConfig ASSERT_COMPOSITE_RESPONSE_SUCCESS =
      post(
          afterStepEndingWithURIPathOfAny(COMPOSITE_URI_PATH),
          (stepReport, ignore) -> assertCompositeResponseSuccess(stepReport));

  private static final String UNSUCCESSFUL_COMPOSITE_RESPONSE_ERROR_MSG =
      """
            Unsuccessful Composite response:
            {
                "first errorCode": "%s"
                "first errorMessage": "%s"
            }
            StepReport:
            %s""";

  static void assertCompositeResponseSuccess(StepReport stepReport) {
    assertCompositeResponseSuccess(stepReport, EXPECT_TO_FAIL_HEADER);
  }

  static void assertCompositeResponseSuccess(StepReport stepReport, String expectToFailHeader) {
    final var responseTxnInfo = stepReport.responseInfo.get();
    final var compositeResp =
        responseTxnInfo.<CompositeResponse>getTypedTxnObj(
            CompositeResponse.class, List.of(CompositeResponse.ADAPTER));
    assertTrue(
        compositeResp.isSuccessful() || containsHeader(stepReport.requestInfo, expectToFailHeader),
        () -> {
          final var firstErrorResponseBody = compositeResp.firstErrorResponseBody();
          return UNSUCCESSFUL_COMPOSITE_RESPONSE_ERROR_MSG.formatted(
              firstErrorResponseBody.getErrorCode(),
              firstErrorResponseBody.getMessage(),
              stepReport);
        });
  }
}
