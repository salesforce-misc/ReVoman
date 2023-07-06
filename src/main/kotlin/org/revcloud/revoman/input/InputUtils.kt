@file:JvmName("InputUtils")

package org.revcloud.revoman.input

fun pre(stepName: String) = stepName to HookType.PRE

fun post(stepName: String) = stepName to HookType.POST
