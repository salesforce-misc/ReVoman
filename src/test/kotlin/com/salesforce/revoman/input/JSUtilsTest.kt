/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.input

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.internal.json.initMoshi
import com.salesforce.revoman.internal.postman.PostmanSDK
import com.salesforce.revoman.internal.postman.RegexReplacer
import com.salesforce.revoman.internal.postman.Response
import com.salesforce.revoman.internal.postman.pm
import io.mockk.mockk
import java.time.LocalDate
import org.http4k.core.Status.Companion.CREATED
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class JSUtilsTest {

  companion object {
    @BeforeAll
    @JvmStatic
    fun setup() {
      initMoshi()
      pm = PostmanSDK(RegexReplacer())
      pm.currentStepReport = mockk()
      pm.rundown = mockk()
      initJSContext("js")
    }
  }

  @Test
  fun `eval JS with moment`() {
    evaluateJS(
      """
          var moment = require('moment')
          pm.environment.set("${"$"}currentDate", moment().format(("YYYY-MM-DD")))
        """
        .trimIndent()
    )
    assertThat(pm.environment).containsEntry("\$currentDate", LocalDate.now().toString())
  }

  @Test
  fun `eval JS with pm variables replaceIn`() {
    evaluateJS(
      """
          const stringWithVars = pm.variables.replaceIn("Hi, my name is {{${"$"}randomFirstName}}");
          console.log(stringWithVars);
        """
        .trimIndent()
    )
  }

  @Test
  fun `eval JS with lodash`() {
    evaluateJS(
      """
          pm.environment.set("${"$"}randomNum", _.random(10))
        """
        .trimIndent()
    )
    assertThat(pm.environment.getInt("\$randomNum")).isWithin(10)
  }

  @Test
  fun xmlSoapParse() {
    val responseBody =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns="urn:partner.soap.sforce.com" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
          <soapenv:Body>
              <loginResponse>
                  <result>
                      <metadataServerUrl>https://trialorgsforu-ec.test1.my.pc-rnd.salesforce.com/services/Soap/m/55.0/00DRN0000009wGZ</metadataServerUrl>
                      <passwordExpired>false</passwordExpired>
                      <sandbox>false</sandbox>
                      <serverUrl>https://trialorgsforu-ec.test1.my.pc-rnd.salesforce.com/services/Soap/u/55.0/00DRN0000009wGZ</serverUrl>
                      <sessionId>session-key-set-in-js</sessionId>
                      <userId>005RN000000bTH9YAM</userId>
                  </result>
              </loginResponse>
          </soapenv:Body>
      </soapenv:Envelope>
    """
        .trimIndent()
    val callingScript =
      """
      var jsonData = xml2Json(responseBody);
      console.log(jsonData);
      var sessionId = jsonData['soapenv:Envelope']['soapenv:Body'].loginResponse.result.sessionId
      pm.environment.set("accessToken", sessionId);
    """
        .trimIndent()
    evaluateJS(callingScript, mapOf("responseBody" to responseBody))
    assertThat(pm.environment).containsEntry("accessToken", "session-key-set-in-js")
  }

  @Test
  fun `pm response to json()`() {
    val testScript =
      """
        var jsonData = pm.response.json()
        var quoteResult = jsonData.compositeResponse[0].body.records[0]
        pm.environment.set("lineItemCount", quoteResult.LineItemCount)
        pm.environment.set("quoteCalculationStatus", quoteResult.CalculationStatus)
        var qlis = jsonData.compositeResponse[1].body.records
        qlis.forEach((record, index) => {
            pm.environment.set("qliCreated" + (index + 1) + "Id", record.Id)
            pm.environment.set("productForQLI" + (index + 1) + "Id", record.Product2Id)
        })
      """
        .trimIndent()
    val httpResponseStr = readFileInResourcesToString("json/composite-query-response.json")
    pm.response =
      Response(
        CREATED.code,
        CREATED.toString(),
        httpResponseStr,
      )
    evaluateJS(testScript)
    assertThat(pm.environment)
      .containsAtLeastEntriesIn(
        mapOf(
          "lineItemCount" to 10,
          "quoteCalculationStatus" to "CompletedWithTax",
          "qliCreated1Id" to "0QLxx0000004D8KGAU",
          "productForQLI1Id" to "01txx0000006ivIAAQ",
          "qliCreated2Id" to "0QLxx0000004D8LGAU",
          "productForQLI2Id" to "01txx0000006iwuAAA",
          "qliCreated3Id" to "0QLxx0000004D8MGAU",
          "productForQLI3Id" to "01txx0000006itgAAA",
          "qliCreated4Id" to "0QLxx0000004D8NGAU",
          "productForQLI4Id" to "01txx0000006itgAAA",
          "qliCreated5Id" to "0QLxx0000004D8OGAU",
          "productForQLI5Id" to "01txx0000006ivIAAQ",
          "qliCreated6Id" to "0QLxx0000004D8PGAU",
          "productForQLI6Id" to "01txx0000006iwuAAA",
          "qliCreated7Id" to "0QLxx0000004D8QGAU",
          "productForQLI7Id" to "01txx0000006itgAAA",
          "qliCreated8Id" to "0QLxx0000004D8RGAU",
          "productForQLI8Id" to "01txx0000006itgAAA",
          "qliCreated9Id" to "0QLxx0000004D8SGAU",
          "productForQLI9Id" to "01txx0000006ivIAAQ",
          "qliCreated10Id" to "0QLxx0000004D8TGAU",
          "productForQLI10Id" to "01txx0000006iwuAAA",
        )
      )
  }
}
