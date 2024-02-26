package com.salesforce.revoman.notification.reports

data class StepExecutionsResult(val totalSteps: Int, val successStepsCount: Int, val failedStepsCount: Int, val detailedReportUrl: String)
