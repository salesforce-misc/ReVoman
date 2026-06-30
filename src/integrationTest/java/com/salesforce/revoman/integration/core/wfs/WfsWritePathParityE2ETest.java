/*
 * Copyright 2025 salesforce.com, inc.
 * All Rights Reserved
 * Company Confidential
 */

package com.salesforce.revoman.integration.core.wfs;

import org.junit.jupiter.api.Disabled;

/**
 * WFS read↔write parity write-path characterization (live 262; 264 contrast in each method's javadoc).
 * Supersedes WfsHelperFitnessE2ETest (Decision 1) and WfsDoubleBookHelperE2ETest (Decision 1.5).
 *
 * <p>Decisions covered: 1 (helper fitness), 1.4 (helper can't satisfy a required-resource demand),
 * 1.5 (helper double-books), 3 (missing isRequiredResource flag + the L142 single-required-no-primary
 * control). Each scenario is its own {@code ReVoman.revUp(...)} starting with {@code AUTH_CONFIG}
 * (fresh env + fresh timestamped users → no ServiceResource (RelatedRecordId, ResourceType) collision).
 */
@Disabled(
    "needs a WFS workspace org: multi-resource pref (WorkforceSchdMulResSchdPref) + InBusinessScheduling"
        + " enabled + Shift.Status DynEnum seeded + each Availability rule's ShiftUsage param. See"
        + " ReVomanConfigForWfs.")
class WfsWritePathParityE2ETest {}
