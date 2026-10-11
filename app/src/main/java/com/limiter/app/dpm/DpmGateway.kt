package com.limiter.app.dpm

/**
 * Thin abstraction over DevicePolicyManager per AGENTS.md §21.
 *
 * The real implementation owns all direct DPM interaction.
 * Unit tests fake this interface.
 * The gateway returns explicit failure states.
 */
interface DpmGateway {

    fun isDeviceOwner(): Boolean

    fun isApplicationHidden(pkg: String): Result<Boolean>

    fun isPackageSuspended(pkg: String): Result<Boolean>

    fun suspend(pkg: String): DpmOperationResult

    fun unsuspend(pkg: String): DpmOperationResult

    fun hide(pkg: String): DpmOperationResult

    fun unhide(pkg: String): DpmOperationResult

    fun setUninstallBlocked(
        pkg: String,
        blocked: Boolean
    ): DpmOperationResult

    fun isUninstallBlocked(pkg: String): Result<Boolean>

    fun setUserControlDisabled(
        packages: List<String>
    ): DpmOperationResult

    fun getUserControlDisabledPackages(): List<String>

    /**
     * Check if a package is installed on the device.
     */
    fun isPackageInstalled(pkg: String): Boolean

    /**
     * Returns a list of all packages on the device that are physically frozen
     * (hidden == true && suspended == true).
     */
    fun getAllFrozenPackages(): List<String>
}
