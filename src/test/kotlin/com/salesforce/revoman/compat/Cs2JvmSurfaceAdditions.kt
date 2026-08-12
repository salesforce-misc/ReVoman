/**
 * ************************************************************************************************
 * Copyright (c) 2026, Salesforce, Inc. All rights reserved. SPDX-License-Identifier: Apache License
 * Version 2.0 For full license text, see the LICENSE file in the repo root or
 * http://www.apache.org/licenses/LICENSE-2.0
 * ************************************************************************************************
 */
package com.salesforce.revoman.compat

/** Exact cumulative raw-JAR additions approved after CS2 Task 2. */
internal val CS2_TASK2_RAW_JVM_ADDITIONS: Set<String> =
  setOf(
    "com/salesforce/revoman/internal/runtime/ResourceScopeKt\$resourceScope\$1\tCLASS\t<class>\tLcom/salesforce/revoman/internal/runtime/ResourceScopeKt\$resourceScope\$1;\t0x0019\t0x0000\tfalse\tfalse\tfalse\tfalse",
    "com/salesforce/revoman/internal/runtime/ResourceScopeKt\$resourceScope\$1\tCONSTRUCTOR\t<init>\t()V\t0x0019\t0x0000\tfalse\tfalse\tfalse\tfalse",
    "com/salesforce/revoman/internal/runtime/ResourceScopeKt\$resourceScope\$1\tFIELD\tclosed\tZ\t0x0019\t0x0002\tfalse\tfalse\tfalse\tfalse",
    "com/salesforce/revoman/internal/runtime/ResourceScopeKt\$resourceScope\$1\tFIELD\tresources\tLkotlin/collections/ArrayDeque;\t0x0019\t0x0012\tfalse\tfalse\tfalse\tfalse",
    "com/salesforce/revoman/internal/runtime/ResourceScopeKt\$resourceScope\$1\tMETHOD\tclose\t()V\t0x0019\t0x0001\tfalse\tfalse\tfalse\tfalse",
    "com/salesforce/revoman/internal/runtime/ResourceScopeKt\$resourceScope\$1\tMETHOD\tcloseAfter\t(Ljava/lang/Throwable;)Ljava/lang/Throwable;\t0x0019\t0x0001\tfalse\tfalse\tfalse\tfalse",
    "com/salesforce/revoman/internal/runtime/ResourceScopeKt\$resourceScope\$1\tMETHOD\town\t(Lcom/salesforce/revoman/internal/runtime/InternalCloseable;)Lcom/salesforce/revoman/internal/runtime/InternalCloseable;\t0x0019\t0x0001\tfalse\tfalse\tfalse\tfalse",
    "com/salesforce/revoman/internal/runtime/InternalCloseable\tCLASS\t<class>\tLcom/salesforce/revoman/internal/runtime/InternalCloseable;\t0x0601\t0x0000\tfalse\tfalse\tfalse\ttrue",
    "com/salesforce/revoman/internal/runtime/InternalCloseable\tMETHOD\tclose\t()V\t0x0601\t0x1401\tfalse\ttrue\tfalse\tfalse",
    "com/salesforce/revoman/internal/runtime/InternalCloseableKt\tCLASS\t<class>\tLcom/salesforce/revoman/internal/runtime/InternalCloseableKt;\t0x0031\t0x0000\tfalse\tfalse\tfalse\ttrue",
    "com/salesforce/revoman/internal/runtime/InternalCloseableKt\tMETHOD\tuseInternal\t(Lcom/salesforce/revoman/internal/runtime/InternalCloseable;Lkotlin/jvm/functions/Function1;)Ljava/lang/Object;\t0x0031\t0x1019\tfalse\ttrue\tfalse\tfalse",
    "com/salesforce/revoman/internal/runtime/ResourceScope\tCLASS\t<class>\tLcom/salesforce/revoman/internal/runtime/ResourceScope;\t0x0601\t0x0000\tfalse\tfalse\tfalse\ttrue",
    "com/salesforce/revoman/internal/runtime/ResourceScope\tMETHOD\tclose\t()V\t0x0601\t0x1401\tfalse\ttrue\tfalse\tfalse",
    "com/salesforce/revoman/internal/runtime/ResourceScope\tMETHOD\tcloseAfter\t(Ljava/lang/Throwable;)Ljava/lang/Throwable;\t0x0601\t0x1401\tfalse\ttrue\tfalse\tfalse",
    "com/salesforce/revoman/internal/runtime/ResourceScope\tMETHOD\town\t(Lcom/salesforce/revoman/internal/runtime/InternalCloseable;)Lcom/salesforce/revoman/internal/runtime/InternalCloseable;\t0x0601\t0x1401\tfalse\ttrue\tfalse\tfalse",
    "com/salesforce/revoman/internal/runtime/ResourceScopeKt\tCLASS\t<class>\tLcom/salesforce/revoman/internal/runtime/ResourceScopeKt;\t0x0031\t0x0000\tfalse\tfalse\tfalse\ttrue",
    "com/salesforce/revoman/internal/runtime/ResourceScopeKt\tMETHOD\tresourceScope\t()Lcom/salesforce/revoman/internal/runtime/ResourceScope;\t0x0031\t0x1019\tfalse\ttrue\tfalse\tfalse",
    "com/salesforce/revoman/internal/runtime/SandboxFactory\tCLASS\t<class>\tLcom/salesforce/revoman/internal/runtime/SandboxFactory;\t0x0601\t0x0000\tfalse\tfalse\tfalse\ttrue",
    "com/salesforce/revoman/internal/runtime/SandboxFactory\tMETHOD\tcreate\t()Lcom/salesforce/revoman/internal/runtime/SandboxRuntime;\t0x0601\t0x1401\tfalse\ttrue\tfalse\tfalse",
    "com/salesforce/revoman/internal/runtime/SandboxRuntime\tCLASS\t<class>\tLcom/salesforce/revoman/internal/runtime/SandboxRuntime;\t0x0601\t0x0000\tfalse\tfalse\tfalse\ttrue",
    "com/salesforce/revoman/internal/runtime/SandboxRuntimeKt\tCLASS\t<class>\tLcom/salesforce/revoman/internal/runtime/SandboxRuntimeKt;\t0x0031\t0x0000\tfalse\tfalse\tfalse\ttrue",
    "com/salesforce/revoman/internal/runtime/SandboxRuntimeKt\tFIELD\tSANDBOX_DEFAULT_TIMEOUT_MS\tJ\t0x0031\t0x001A\tfalse\tfalse\tfalse\tfalse",
    "com/salesforce/revoman/internal/runtime/ScriptExecutor\tCLASS\t<class>\tLcom/salesforce/revoman/internal/runtime/ScriptExecutor;\t0x0601\t0x0000\tfalse\tfalse\tfalse\ttrue",
    "com/salesforce/revoman/internal/runtime/ScriptExecutor\tMETHOD\texecute\t(Ljava/lang/String;Lcom/salesforce/revoman/internal/postman/sandbox/ScriptTarget;Lcom/salesforce/revoman/internal/postman/sandbox/PmExecutionContext;J)Lcom/salesforce/revoman/internal/postman/sandbox/PmExecutionResult;\t0x0601\t0x1401\tfalse\ttrue\tfalse\tfalse",
    "com/salesforce/revoman/internal/runtime/ScriptExecutor\tMETHOD\texecute\$default\t(Lcom/salesforce/revoman/internal/runtime/ScriptExecutor;Ljava/lang/String;Lcom/salesforce/revoman/internal/postman/sandbox/ScriptTarget;Lcom/salesforce/revoman/internal/postman/sandbox/PmExecutionContext;JILjava/lang/Object;)Lcom/salesforce/revoman/internal/postman/sandbox/PmExecutionResult;\t0x0601\t0x1009\tfalse\ttrue\tfalse\tfalse",
    "com/salesforce/revoman/internal/runtime/ScriptExecutor\$DefaultImpls\tCLASS\t<class>\tLcom/salesforce/revoman/internal/runtime/ScriptExecutor\$DefaultImpls;\t0x0019\t0x0000\tfalse\tfalse\tfalse\ttrue",
    "com/salesforce/revoman/internal/runtime/ScriptExecutor\$DefaultImpls\tMETHOD\texecute\$default\t(Lcom/salesforce/revoman/internal/runtime/ScriptExecutor;Ljava/lang/String;Lcom/salesforce/revoman/internal/postman/sandbox/ScriptTarget;Lcom/salesforce/revoman/internal/postman/sandbox/PmExecutionContext;JILjava/lang/Object;)Lcom/salesforce/revoman/internal/postman/sandbox/PmExecutionResult;\t0x0019\t0x1009\tfalse\ttrue\tfalse\tfalse",
  )
