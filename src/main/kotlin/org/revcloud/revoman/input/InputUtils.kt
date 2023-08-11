/*******************************************************************************
 * Copyright (c) 2023, Salesforce, Inc.
 *  All rights reserved.
 *  SPDX-License-Identifier: BSD-3-Clause
 *  For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 ******************************************************************************/
@file:JvmName("InputUtils")

package org.revcloud.revoman.input

fun pre(stepName: String) = stepName to HookType.PRE

fun post(stepName: String) = stepName to HookType.POST
