package com.limiter.app.dpm

/**
 * Typed result representing package installation presence on the device.
 *
 * Provides a reliable, unambiguous distinction between:
 * - [Installed]: Confirmed installed on the system (via PackageManager or active DPM management).
 * - [NotInstalled]: Confirmed uninstalled / not found on the system.
 * - [Unknown]: State could not be confirmed due to permission, system IPC (e.g. DeadObjectException),
 *   or unexpected runtime exception. Transient errors must NEVER be treated as uninstalled.
 */
sealed interface PackageInstallStatus {
    object Installed : PackageInstallStatus
    object NotInstalled : PackageInstallStatus
    data class Unknown(val message: String? = null, val cause: Throwable? = null) : PackageInstallStatus
}
