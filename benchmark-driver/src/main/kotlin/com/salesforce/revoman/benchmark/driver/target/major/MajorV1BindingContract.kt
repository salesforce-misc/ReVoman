/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * https://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.benchmark.driver.target.major

import com.salesforce.revoman.benchmark.driver.target.PreparedTargetMethod
import com.salesforce.revoman.benchmark.driver.target.ReflectiveTarget

/** Exact owner, member, and JVM-descriptor contract for the first major lifecycle surface. */
object MajorV1BindingContract {
    const val KICK_OWNER: String = "com/salesforce/revoman/input/config/Kick"
    const val BUILDER_OWNER: String = "com/salesforce/revoman/input/config/Kick\$Builder"
    const val REVOMAN_OWNER: String = "com/salesforce/revoman/ReVoman"
    const val RUNDOWN_OWNER: String = "com/salesforce/revoman/output/Rundown"
    const val LIFECYCLE_DIAGNOSTICS_OWNER: String =
        "com/salesforce/revoman/internal/runtime/ExecutionLifecycleDiagnostics"

    val configure = Member(KICK_OWNER, "configure", "()L$BUILDER_OWNER;", Invocation.STATIC)
    val templatePath =
        Member(
            BUILDER_OWNER,
            "templatePath",
            "(Ljava/lang/String;)L$BUILDER_OWNER;",
            Invocation.VIRTUAL,
        )
    val dynamicEnvironment =
        Member(
            BUILDER_OWNER,
            "dynamicEnvironment",
            "(Ljava/lang/String;Ljava/lang/Object;)L$BUILDER_OWNER;",
            Invocation.VIRTUAL,
        )
    val insecureHttp =
        Member(BUILDER_OWNER, "insecureHttp", "(Z)L$BUILDER_OWNER;", Invocation.VIRTUAL)
    val off = Member(BUILDER_OWNER, "off", "()L$KICK_OWNER;", Invocation.VIRTUAL)
    val revUp =
        Member(
            REVOMAN_OWNER,
            "revUp",
            "(L$KICK_OWNER;)L$RUNDOWN_OWNER;",
            Invocation.STATIC,
        )
    val executedStepCount = Member(RUNDOWN_OWNER, "executedStepCount", "()I", Invocation.VIRTUAL)
    val unsuccessfulStepCount =
        Member(RUNDOWN_OWNER, "unsuccessfulStepCount", "()I", Invocation.VIRTUAL)
    val drainLifecycleDiagnostics =
        Member(
            LIFECYCLE_DIAGNOSTICS_OWNER,
            "drain",
            "()[Ljava/lang/Object;",
            Invocation.STATIC,
        )

    /** Distinguishes JVM static invocation from receiver-bearing virtual invocation. */
    enum class Invocation {
        STATIC,
        VIRTUAL,
    }

    /** One exact reflective member binding expressed in JVM internal-name form. */
    data class Member(
        val owner: String,
        val name: String,
        val descriptor: String,
        val invocation: Invocation,
    ) {
        internal fun bind(target: ReflectiveTarget): PreparedTargetMethod =
            target.method(
                ownerInternalName = owner,
                name = name,
                descriptor = descriptor,
                isStatic = invocation == Invocation.STATIC,
            )
    }
}
