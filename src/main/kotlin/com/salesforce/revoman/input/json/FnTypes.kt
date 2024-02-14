/**
 * ************************************************************************************************
 * Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.input.json

fun interface NestedNodeWriter<T> {
  @Throws(Throwable::class) fun write(t: T)
}

fun interface NestedNodeReader<T> {
  @Throws(Throwable::class) fun read(t: T, s: String)
}
