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

/** Exact cumulative raw-JAR additions approved after CS2 Task 3. */
internal val CS2_TASK3_RAW_JVM_ADDITIONS: Set<String> =
  CS2_TASK2_RAW_JVM_ADDITIONS +
    setOf(
      "com/salesforce/revoman/internal/postman/sandbox/SandboxBridge\tFIELD\tafterContextCreated\tLkotlin/jvm/functions/Function0;\t0x0031\t0x0002\tfalse\tfalse\tfalse\tfalse",
      "com/salesforce/revoman/internal/postman/sandbox/SandboxBridge\tFIELD\tcloseContext\tLkotlin/jvm/functions/Function1;\t0x0031\t0x0002\tfalse\tfalse\tfalse\tfalse",
      "com/salesforce/revoman/internal/postman/sandbox/SandboxBridge\tMETHOD\tafterContextCreated\u0024lambda\u00240\t()Lkotlin/Unit;\t0x0031\t0x001A\tfalse\tfalse\tfalse\tfalse",
      "com/salesforce/revoman/internal/postman/sandbox/SandboxBridge\tMETHOD\tcloseContext\u0024lambda\u00240\t(Lorg/graalvm/polyglot/Context;)Lkotlin/Unit;\t0x0031\t0x001A\tfalse\tfalse\tfalse\tfalse",
      "com/salesforce/revoman/internal/postman/sandbox/SandboxBridge\tMETHOD\twithBootHooks\u0024com_salesforce_revoman_revoman\t(Lkotlin/jvm/functions/Function0;Lkotlin/jvm/functions/Function1;)Lcom/salesforce/revoman/internal/postman/sandbox/SandboxBridge;\t0x0031\t0x1011\tfalse\ttrue\tfalse\tfalse",
      "com/salesforce/revoman/internal/postman/sandbox/SandboxResources\tMETHOD\tgetBootSource\u0024com_salesforce_revoman_revoman\t()Lorg/graalvm/polyglot/Source;\t0x0031\t0x1011\tfalse\ttrue\tfalse\tfalse",
      "com/salesforce/revoman/internal/runtime/KickExecution\tCLASS\t<class>\tLcom/salesforce/revoman/internal/runtime/KickExecution;\t0x0601\t0x0000\tfalse\tfalse\tfalse\ttrue",
      "com/salesforce/revoman/internal/runtime/KickExecution\tMETHOD\tclose\t()V\t0x0601\t0x1401\tfalse\ttrue\tfalse\tfalse",
      "com/salesforce/revoman/internal/runtime/KickExecution\tMETHOD\tgetSandboxInitialized\t()Z\t0x0601\t0x1401\tfalse\ttrue\tfalse\tfalse",
      "com/salesforce/revoman/internal/runtime/KickExecution\tMETHOD\tgetScripts\t()Lcom/salesforce/revoman/internal/runtime/ScriptExecutor;\t0x0601\t0x1401\tfalse\ttrue\tfalse\tfalse",
      "com/salesforce/revoman/internal/runtime/KickExecutionKt\tCLASS\t<class>\tLcom/salesforce/revoman/internal/runtime/KickExecutionKt;\t0x0031\t0x0000\tfalse\tfalse\tfalse\ttrue",
      "com/salesforce/revoman/internal/runtime/KickExecutionKt\tFIELD\tDEFAULT_SANDBOX_FACTORY\tLcom/salesforce/revoman/internal/runtime/SandboxFactory;\t0x0031\t0x001A\tfalse\tfalse\tfalse\tfalse",
      "com/salesforce/revoman/internal/runtime/KickExecutionKt\tMETHOD\t<clinit>\t()V\t0x0031\t0x0008\tfalse\tfalse\tfalse\tfalse",
      "com/salesforce/revoman/internal/runtime/KickExecutionKt\tMETHOD\tDEFAULT_SANDBOX_FACTORY\u0024lambda\u00240\t()Lcom/salesforce/revoman/internal/runtime/SandboxRuntime;\t0x0031\t0x001A\tfalse\tfalse\tfalse\tfalse",
      "com/salesforce/revoman/internal/runtime/KickExecutionKt\tMETHOD\tkickExecution\t(Lcom/salesforce/revoman/internal/runtime/SandboxFactory;)Lcom/salesforce/revoman/internal/runtime/KickExecution;\t0x0031\t0x1019\tfalse\ttrue\tfalse\tfalse",
      "com/salesforce/revoman/internal/runtime/KickExecutionKt\tMETHOD\tkickExecution\u0024default\t(Lcom/salesforce/revoman/internal/runtime/SandboxFactory;ILjava/lang/Object;)Lcom/salesforce/revoman/internal/runtime/KickExecution;\t0x0031\t0x1009\tfalse\ttrue\tfalse\tfalse",
      "com/salesforce/revoman/internal/runtime/KickExecutionKt\u0024kickExecution\u00241\tCLASS\t<class>\tLcom/salesforce/revoman/internal/runtime/KickExecutionKt\u0024kickExecution\u00241;\t0x0019\t0x0000\tfalse\tfalse\tfalse\tfalse",
      "com/salesforce/revoman/internal/runtime/KickExecutionKt\u0024kickExecution\u00241\tCONSTRUCTOR\t<init>\t(Lcom/salesforce/revoman/internal/runtime/ResourceScope;Lcom/salesforce/revoman/internal/runtime/SandboxFactory;)V\t0x0019\t0x0000\tfalse\tfalse\tfalse\tfalse",
      "com/salesforce/revoman/internal/runtime/KickExecutionKt\u0024kickExecution\u00241\tFIELD\t\u0024scope\tLcom/salesforce/revoman/internal/runtime/ResourceScope;\t0x0019\t0x1010\tfalse\ttrue\tfalse\tfalse",
      "com/salesforce/revoman/internal/runtime/KickExecutionKt\u0024kickExecution\u00241\tFIELD\tclosed\tZ\t0x0019\t0x0002\tfalse\tfalse\tfalse\tfalse",
      "com/salesforce/revoman/internal/runtime/KickExecutionKt\u0024kickExecution\u00241\tFIELD\texecutor\tLcom/salesforce/revoman/internal/runtime/KickExecutionKt\u0024kickExecution\u00241\u0024executor\u00241;\t0x0019\t0x0012\tfalse\tfalse\tfalse\tfalse",
      "com/salesforce/revoman/internal/runtime/KickExecutionKt\u0024kickExecution\u00241\tFIELD\tsandbox\tLcom/salesforce/revoman/internal/runtime/SandboxRuntime;\t0x0019\t0x0002\tfalse\tfalse\tfalse\tfalse",
      "com/salesforce/revoman/internal/runtime/KickExecutionKt\u0024kickExecution\u00241\tMETHOD\taccess\u0024getClosed\u0024p\t(Lcom/salesforce/revoman/internal/runtime/KickExecutionKt\u0024kickExecution\u00241;)Z\t0x0019\t0x1019\tfalse\ttrue\tfalse\tfalse",
      "com/salesforce/revoman/internal/runtime/KickExecutionKt\u0024kickExecution\u00241\tMETHOD\taccess\u0024getSandbox\u0024p\t(Lcom/salesforce/revoman/internal/runtime/KickExecutionKt\u0024kickExecution\u00241;)Lcom/salesforce/revoman/internal/runtime/SandboxRuntime;\t0x0019\t0x1019\tfalse\ttrue\tfalse\tfalse",
      "com/salesforce/revoman/internal/runtime/KickExecutionKt\u0024kickExecution\u00241\tMETHOD\taccess\u0024setSandbox\u0024p\t(Lcom/salesforce/revoman/internal/runtime/KickExecutionKt\u0024kickExecution\u00241;Lcom/salesforce/revoman/internal/runtime/SandboxRuntime;)V\t0x0019\t0x1019\tfalse\ttrue\tfalse\tfalse",
      "com/salesforce/revoman/internal/runtime/KickExecutionKt\u0024kickExecution\u00241\tMETHOD\tclose\t()V\t0x0019\t0x0001\tfalse\tfalse\tfalse\tfalse",
      "com/salesforce/revoman/internal/runtime/KickExecutionKt\u0024kickExecution\u00241\tMETHOD\tgetSandboxInitialized\t()Z\t0x0019\t0x0001\tfalse\tfalse\tfalse\tfalse",
      "com/salesforce/revoman/internal/runtime/KickExecutionKt\u0024kickExecution\u00241\tMETHOD\tgetScripts\t()Lcom/salesforce/revoman/internal/runtime/ScriptExecutor;\t0x0019\t0x0001\tfalse\tfalse\tfalse\tfalse",
      "com/salesforce/revoman/internal/runtime/KickExecutionKt\u0024kickExecution\u00241\u0024executor\u00241\tCLASS\t<class>\tLcom/salesforce/revoman/internal/runtime/KickExecutionKt\u0024kickExecution\u00241\u0024executor\u00241;\t0x0019\t0x0000\tfalse\tfalse\tfalse\tfalse",
      "com/salesforce/revoman/internal/runtime/KickExecutionKt\u0024kickExecution\u00241\u0024executor\u00241\tCONSTRUCTOR\t<init>\t(Lcom/salesforce/revoman/internal/runtime/KickExecutionKt\u0024kickExecution\u00241;Lcom/salesforce/revoman/internal/runtime/ResourceScope;Lcom/salesforce/revoman/internal/runtime/SandboxFactory;)V\t0x0019\t0x0000\tfalse\tfalse\tfalse\tfalse",
      "com/salesforce/revoman/internal/runtime/KickExecutionKt\u0024kickExecution\u00241\u0024executor\u00241\tFIELD\t\u0024sandboxFactory\tLcom/salesforce/revoman/internal/runtime/SandboxFactory;\t0x0019\t0x1010\tfalse\ttrue\tfalse\tfalse",
      "com/salesforce/revoman/internal/runtime/KickExecutionKt\u0024kickExecution\u00241\u0024executor\u00241\tFIELD\t\u0024scope\tLcom/salesforce/revoman/internal/runtime/ResourceScope;\t0x0019\t0x1010\tfalse\ttrue\tfalse\tfalse",
      "com/salesforce/revoman/internal/runtime/KickExecutionKt\u0024kickExecution\u00241\u0024executor\u00241\tFIELD\tthis\u00240\tLcom/salesforce/revoman/internal/runtime/KickExecutionKt\u0024kickExecution\u00241;\t0x0019\t0x1010\tfalse\ttrue\tfalse\tfalse",
      "com/salesforce/revoman/internal/runtime/KickExecutionKt\u0024kickExecution\u00241\u0024executor\u00241\tMETHOD\texecute\t(Ljava/lang/String;Lcom/salesforce/revoman/internal/postman/sandbox/ScriptTarget;Lcom/salesforce/revoman/internal/postman/sandbox/PmExecutionContext;J)Lcom/salesforce/revoman/internal/postman/sandbox/PmExecutionResult;\t0x0019\t0x0001\tfalse\tfalse\tfalse\tfalse",
      "com/salesforce/revoman/internal/postman/sandbox/PmSandbox\tFIELD\tbridgeForTest\tLcom/salesforce/revoman/internal/postman/sandbox/SandboxBridge;\t0x0031\t0x0002\tfalse\tfalse\tfalse\tfalse",
      "com/salesforce/revoman/internal/postman/sandbox/PmSandbox\tMETHOD\tactiveBridge\t()Lcom/salesforce/revoman/internal/postman/sandbox/SandboxBridge;\t0x0031\t0x0012\tfalse\tfalse\tfalse\tfalse",
      "com/salesforce/revoman/internal/postman/sandbox/PmSandbox\tMETHOD\twithBridgeForTest\u0024com_salesforce_revoman_revoman\t(Lcom/salesforce/revoman/internal/postman/sandbox/SandboxBridge;)Lcom/salesforce/revoman/internal/postman/sandbox/PmSandbox;\t0x0031\t0x1011\tfalse\ttrue\tfalse\tfalse",
      "com/salesforce/revoman/internal/postman/sandbox/SandboxBridge\tFIELD\truntimeObserver\tLkotlin/jvm/functions/Function2;\t0x0031\t0x0002\tfalse\tfalse\tfalse\tfalse",
      "com/salesforce/revoman/internal/postman/sandbox/SandboxBridge\tMETHOD\tobserveRuntime\u0024com_salesforce_revoman_revoman\t(Lkotlin/jvm/functions/Function2;)Lcom/salesforce/revoman/internal/postman/sandbox/SandboxBridge;\t0x0031\t0x1011\tfalse\ttrue\tfalse\tfalse",
      "com/salesforce/revoman/internal/postman/sandbox/SandboxResources\tFIELD\tbootSource\u0024delegate\tLkotlin/Lazy;\t0x0031\t0x001A\tfalse\tfalse\tfalse\tfalse",
      "com/salesforce/revoman/internal/postman/sandbox/SandboxResources\tMETHOD\tbootSource_delegate\u0024lambda\u00240\t(Ljava/lang/String;)Ljava/lang/String;\t0x0031\t0x001A\tfalse\tfalse\tfalse\tfalse",
      "com/salesforce/revoman/internal/postman/sandbox/SandboxResources\tMETHOD\tbootSource_delegate\u0024lambda\u00241\t(Ljava/lang/String;)Ljava/lang/String;\t0x0031\t0x001A\tfalse\tfalse\tfalse\tfalse",
      "com/salesforce/revoman/internal/postman/sandbox/SandboxResources\tMETHOD\tbootSource_delegate\u0024lambda\u00242\t(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Lorg/graalvm/polyglot/Source;\t0x0031\t0x001A\tfalse\tfalse\tfalse\tfalse",
      "com/salesforce/revoman/internal/postman/sandbox/SandboxResources\tMETHOD\tlazyBootSource\u0024com_salesforce_revoman_revoman\t(Lkotlin/jvm/functions/Function1;Lkotlin/jvm/functions/Function1;Lkotlin/jvm/functions/Function3;)Lkotlin/Lazy;\t0x0031\t0x1011\tfalse\ttrue\tfalse\tfalse",
      "com/salesforce/revoman/internal/postman/sandbox/SandboxResources\tMETHOD\tlazyBootSource\u0024lambda\u00240\t(Lkotlin/jvm/functions/Function3;Lkotlin/jvm/functions/Function1;Lkotlin/jvm/functions/Function1;)Lorg/graalvm/polyglot/Source;\t0x0031\t0x001A\tfalse\tfalse\tfalse\tfalse",
    )

/** The sole approved frozen raw-JAR bridge removed by CS2 Task 3. */
internal val CS2_TASK3_RAW_JVM_REMOVALS: Set<String> =
  setOf(
    "com/salesforce/revoman/internal/postman/sandbox/PmSandbox\tMETHOD\texecute\u0024default\t(Lcom/salesforce/revoman/internal/postman/sandbox/PmSandbox;Ljava/lang/String;Lcom/salesforce/revoman/internal/postman/sandbox/ScriptTarget;Lcom/salesforce/revoman/internal/postman/sandbox/PmExecutionContext;JILjava/lang/Object;)Lcom/salesforce/revoman/internal/postman/sandbox/PmExecutionResult;\t0x0031\t0x1009\tfalse\ttrue\tfalse\tfalse"
  )
