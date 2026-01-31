/***************************************************************************************************
 *  Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: 
 *           Apache License Version 2.0 
 *  For full license text, see the LICENSE file in the repo root or
 *  http://www.apache.org/licenses/LICENSE-2.0
 **************************************************************************************************/
package com.salesforce.revoman.internal.postman

import com.google.common.truth.Truth.assertThat
import com.salesforce.revoman.internal.json.MoshiReVoman.Companion.initMoshi
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PostmanSDKResetTest {
  private val moshiReVoman = initMoshi()
  
  @Test
  fun `test resetJSContext removes user-defined variables`() {
    val sdk = PostmanSDK(moshiReVoman)
    
    // Define a variable in the context
    sdk.evaluateJS("var userVar = 'test value'")
    
    // Verify the variable exists
    val result1 = sdk.evaluateJS("userVar")
    assertThat(result1.asString()).isEqualTo("test value")
    
    // Reset the context
    sdk.resetJSContext()
    
    // Verify the variable is now undefined
    val result2 = sdk.evaluateJS("typeof userVar")
    assertThat(result2.asString()).isEqualTo("undefined")
  }
  
  @Test
  fun `test resetJSContext preserves SDK bindings`() {
    val sdk = PostmanSDK(moshiReVoman)
    
    // Define user variables
    sdk.evaluateJS("var userVar1 = 10")
    sdk.evaluateJS("var userVar2 = 20")
    
    // Reset the context
    sdk.resetJSContext()
    
    // Verify SDK bindings still exist
    val pmResult = sdk.evaluateJS("typeof pm")
    assertThat(pmResult.asString()).isEqualTo("object")
    
    val xml2JsonResult = sdk.evaluateJS("typeof xml2Json")
    assertThat(xml2JsonResult.asString()).isEqualTo("function")
    
    val clientResult = sdk.evaluateJS("typeof client")
    assertThat(clientResult.asString()).isEqualTo("object")
    
    // Verify user variables are now undefined
    val userVar1Result = sdk.evaluateJS("typeof userVar1")
    assertThat(userVar1Result.asString()).isEqualTo("undefined")
    
    val userVar2Result = sdk.evaluateJS("typeof userVar2")
    assertThat(userVar2Result.asString()).isEqualTo("undefined")
  }
  
  @Test
  fun `test evaluateJSIsolated prevents variable pollution`() {
    val sdk = PostmanSDK(moshiReVoman)
    
    // Evaluate in isolated scope
    sdk.evaluateJSIsolated("var isolatedVar = 'isolated'")
    
    // Verify the variable doesn't exist in the main context
    assertThrows<Exception> {
      sdk.evaluateJS("isolatedVar")
    }
    
    // Regular evaluation should still pollute the context
    sdk.evaluateJS("var globalVar = 'global'")
    val result = sdk.evaluateJS("globalVar")
    assertThat(result.asString()).isEqualTo("global")
  }
  
  @Test
  fun `test evaluateJSIsolated with bindings`() {
    val sdk = PostmanSDK(moshiReVoman)
    
    // Evaluate with bindings in isolated scope
    val bindings = mapOf("inputValue" to 42)
    val result = sdk.evaluateJSIsolated("return inputValue * 2", bindings)
    assertThat(result.asInt()).isEqualTo(84)
    
    // Verify the binding doesn't persist
    val inputValueResult = sdk.evaluateJS("typeof inputValue")
    assertThat(inputValueResult.asString()).isEqualTo("undefined")
  }
  
  @Test
  fun `test variable clash scenario without reset`() {
    val sdk = PostmanSDK(moshiReVoman)
    
    // First execution
    sdk.evaluateJS("var counter = 1")
    val result1 = sdk.evaluateJS("counter")
    assertThat(result1.asInt()).isEqualTo(1)
    
    // Second execution - would normally clash
    sdk.evaluateJS("counter = counter + 1")
    val result2 = sdk.evaluateJS("counter")
    assertThat(result2.asInt()).isEqualTo(2) // Variable persisted
    
    // With reset between executions
    sdk.resetJSContext()
    
    // Verify counter is now undefined
    val counterTypeResult = sdk.evaluateJS("typeof counter")
    assertThat(counterTypeResult.asString()).isEqualTo("undefined")
    
    // Create fresh counter
    sdk.evaluateJS("var counter = 10") // No clash, fresh start
    val result3 = sdk.evaluateJS("counter")
    assertThat(result3.asInt()).isEqualTo(10)
  }
}
