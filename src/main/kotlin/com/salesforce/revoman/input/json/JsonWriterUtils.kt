/***************************************************************************************************
 *  Copyright (c) 2023, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: 
 *           Apache License Version 2.0 
 *  For full license text, see the LICENSE file in the repo root or
 *  http://www.apache.org/licenses/LICENSE-2.0
 **************************************************************************************************/

@file:JvmName("JsonWriterUtils")

package com.salesforce.revoman.input.json

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import java.util.function.Consumer
import org.springframework.beans.BeanUtils

fun <T> objW(name: String, obj: T, writer: JsonWriter, block: Consumer<T>): Unit =
  with(writer) {
    name(name)
    objW(obj, writer, block)
  }

fun <T> objW(obj: T, writer: JsonWriter, block: Consumer<T>): Unit =
  with(writer) {
    if (obj == null) {
      nullValue()
    } else {
      beginObject()
      block.accept(obj)
      endObject()
    }
  }

fun string(name: String, value: String?, writer: JsonWriter): Unit =
  with(writer) {
    name(name)
    value?.also(::value) ?: nullValue()
  }

fun JsonWriter.string(name: String, value: String?) {
  name(name)
  value?.also(::value) ?: nullValue()
}

fun bool(name: String, value: Boolean?, writer: JsonWriter): Unit =
  with(writer) {
    name(name)
    value?.also(::value) ?: nullValue()
  }

fun JsonWriter.bool(name: String, value: Boolean?) {
  name(name)
  value?.also(::value) ?: nullValue()
}

fun integer(name: String, value: Int?, writer: JsonWriter): Unit =
  with(writer) {
    name(name)
    value?.also(::value) ?: nullValue()
  }

fun JsonWriter.integer(name: String, value: Int?) {
  name(name)
  value?.also(::value) ?: nullValue()
}

fun doubl(name: String, value: Double?, writer: JsonWriter): Unit =
  with(writer) {
    name(name)
    value?.also(::value) ?: nullValue()
  }

fun JsonWriter.doubl(name: String, value: Double?) {
  name(name)
  value?.also(::value) ?: nullValue()
}

fun lng(name: String, value: Long?, writer: JsonWriter): Unit =
  with(writer) {
    name(name)
    value?.also(::value) ?: nullValue()
  }

fun JsonWriter.lng(name: String, value: Long?) {
  name(name)
  value?.also(::value) ?: nullValue()
}

fun <T> listW(name: String, list: List<T>?, writer: JsonWriter, block: Consumer<T>): JsonWriter =
  with(writer) {
    name(name)
    listW(list, this, block)
  }

fun <T> listW(list: List<T>?, writer: JsonWriter, block: Consumer<T>): JsonWriter =
  with(writer) {
    when (list) {
      null -> nullValue()
      else -> {
        beginArray()
        list.forEach(block)
        endArray()
      }
    }
  }

// ! TODO 18/10/23 gopala.akshintala: Abstract it into a common artifact to be used in ReVoman

fun <T> JsonWriter.writeProps(
  pojoType: Class<T>,
  bean: T,
  excludePropTypes: Set<Class<*>>,
  moshi: Moshi
) =
  BeanUtils.getPropertyDescriptors(pojoType)
    .asSequence()
    .filterNot {
      it.propertyType.name == "java.lang.Class" || excludePropTypes.contains(it.propertyType)
    }
    .forEach {
      checkNotNull(it.readMethod) {
        throw IllegalStateException("Please add a getter for the Property: `${it.name}`")
      }
      name(it.name)
      val delegate = moshi.adapter(it.propertyType) as JsonAdapter<Any>
      delegate.toJson(this, it.readMethod(bean))
    }
