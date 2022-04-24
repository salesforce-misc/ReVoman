package org.revcloud.response.types.salesforce

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class SObject(val id: String, val success: Boolean, val errors: List<String>)
