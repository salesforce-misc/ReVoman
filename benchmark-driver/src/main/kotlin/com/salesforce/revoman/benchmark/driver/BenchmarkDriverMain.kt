/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver

fun main(args: Array<String>) {
    require(args.contentEquals(arrayOf("version"))) { "Usage: benchmark-driver version" }
    println("revoman-benchmark/v1")
}
