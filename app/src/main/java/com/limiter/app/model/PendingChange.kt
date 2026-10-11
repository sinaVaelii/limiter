package com.limiter.app.model

/**
 * A pending settings change for a single package.
 *
 * [desired] == null means the package is being removed.
 * [eligibleFromEpochDay] is the local epoch day from which this change
 * can be applied (loosening edits and removals are deferred to the next day).
 */
data class PendingChange(
    val packageName: String,
    val desired: TargetAppConfig?,
    val eligibleFromEpochDay: Long
)
