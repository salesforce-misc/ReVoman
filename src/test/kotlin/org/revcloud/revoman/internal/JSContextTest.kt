/**
 * ****************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or
 * https://opensource.org/licenses/BSD-3-Clause
 * ****************************************************************************
 */
package org.revcloud.revoman.internal

import org.graalvm.polyglot.Source
import org.junit.jupiter.api.Test

class JSContextTest {

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
                      <sessionId>00DRN0000009wGZ!ARMAQGPcIyUAsjgF36rdz4CPfXHy4gEGwB4DxQvxqqJEQEHNVXRrPUuwLwznzBISJtqAW49V9ASkvnh0dtP4i1L3w34ljA0.</sessionId>
                      <userId>005RN000000bTH9YAM</userId>
                  </result>
              </loginResponse>
          </soapenv:Body>
      </soapenv:Envelope>
    """
        .trimIndent()
    val callingScript =
      """
      var jsonData=xml2Json(responseBody);
      console.log(jsonData);
      var sessionId = jsonData['soapenv:Envelope']['soapenv:Body'].loginResponse.result.sessionId
      pm.environment.set("accessToken", sessionId);
    """
        .trimIndent()
    val source = Source.newBuilder("js", callingScript, "myScript.js").build()
    val jsBindings = jsContext.getBindings("js")
    jsBindings.putMember("responseBody", responseBody)
    jsContext.eval(source)
  }
}
