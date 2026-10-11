package com.limiter.app.dpm

/**
 * Result of a DevicePolicyManager operation.
 *
 * Explicit failure states instead of exceptions.
 */
sealed interface DpmOperationResult {
    data object Ok : DpmOperationResult
    data class Failed(val reason: String) : DpmOperationResult
    data object NotDeviceOwner : DpmOperationResult
}
