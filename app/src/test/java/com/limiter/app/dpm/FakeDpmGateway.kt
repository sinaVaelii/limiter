package com.limiter.app.dpm

/**
 * Fake [DpmGateway] for unit testing per AGENTS.md §46.
 *
 * Simulates DPM state without Android framework dependencies.
 */
class FakeDpmGateway : DpmGateway {

    var deviceOwner: Boolean = true
    val hiddenPackages = mutableSetOf<String>()
    val suspendedPackages = mutableSetOf<String>()
    val uninstallBlockedPackages = mutableSetOf<String>()
    var disabledUserControlPackages = mutableListOf<String>()
    val installedPackages = mutableSetOf<String>()

    /** If true, next suspend call will fail for the given package. */
    val suspendFailPackages = mutableSetOf<String>()
    /** If true, next hide call will fail. */
    val hideFailPackages = mutableSetOf<String>()
    /** If true, next unhide call will fail. */
    val unhideFailPackages = mutableSetOf<String>()
    /** If true, next unsuspend call will fail. */
    val unsuspendFailPackages = mutableSetOf<String>()
    /** If true, next setUninstallBlocked call will fail for the given package. */
    val uninstallBlockedFailPackages = mutableSetOf<String>()
    /** If true, next setUserControlDisabled call will fail. */
    var userControlFail: Boolean = false

    override fun isDeviceOwner(): Boolean = deviceOwner

    override fun isApplicationHidden(pkg: String): Result<Boolean> {
        if (!deviceOwner) return Result.failure(SecurityException("Not device owner"))
        return Result.success(pkg in hiddenPackages)
    }

    override fun isPackageSuspended(pkg: String): Result<Boolean> {
        if (!deviceOwner) return Result.failure(SecurityException("Not device owner"))
        return Result.success(pkg in suspendedPackages)
    }

    override fun suspend(pkg: String): DpmOperationResult {
        if (!deviceOwner) return DpmOperationResult.NotDeviceOwner
        if (pkg in suspendFailPackages) {
            return DpmOperationResult.Failed("Suspend failed for $pkg")
        }
        suspendedPackages.add(pkg)
        return DpmOperationResult.Ok
    }

    override fun unsuspend(pkg: String): DpmOperationResult {
        if (!deviceOwner) return DpmOperationResult.NotDeviceOwner
        if (pkg in unsuspendFailPackages) {
            return DpmOperationResult.Failed("Unsuspend failed for $pkg")
        }
        suspendedPackages.remove(pkg)
        return DpmOperationResult.Ok
    }

    override fun hide(pkg: String): DpmOperationResult {
        if (!deviceOwner) return DpmOperationResult.NotDeviceOwner
        if (pkg in hideFailPackages) {
            return DpmOperationResult.Failed("Hide failed for $pkg")
        }
        hiddenPackages.add(pkg)
        return DpmOperationResult.Ok
    }

    override fun unhide(pkg: String): DpmOperationResult {
        if (!deviceOwner) return DpmOperationResult.NotDeviceOwner
        if (pkg in unhideFailPackages) {
            return DpmOperationResult.Failed("Unhide failed for $pkg")
        }
        hiddenPackages.remove(pkg)
        return DpmOperationResult.Ok
    }

    override fun setUninstallBlocked(pkg: String, blocked: Boolean): DpmOperationResult {
        if (!deviceOwner) return DpmOperationResult.NotDeviceOwner
        if (pkg in uninstallBlockedFailPackages) {
            return DpmOperationResult.Failed("setUninstallBlocked failed for $pkg")
        }
        if (blocked) uninstallBlockedPackages.add(pkg) else uninstallBlockedPackages.remove(pkg)
        return DpmOperationResult.Ok
    }

    override fun isUninstallBlocked(pkg: String): Result<Boolean> {
        if (!deviceOwner) return Result.failure(SecurityException("Not device owner"))
        return Result.success(pkg in uninstallBlockedPackages)
    }

    override fun setUserControlDisabled(packages: List<String>): DpmOperationResult {
        if (!deviceOwner) return DpmOperationResult.NotDeviceOwner
        if (userControlFail) {
            return DpmOperationResult.Failed("setUserControlDisabled failed")
        }
        disabledUserControlPackages = packages.toMutableList()
        return DpmOperationResult.Ok
    }

    override fun getUserControlDisabledPackages(): List<String> {
        return if (deviceOwner) disabledUserControlPackages else emptyList()
    }

    val packageInstallStatusMap: MutableMap<String, PackageInstallStatus> = mutableMapOf()

    override fun getPackageInstallStatus(pkg: String): PackageInstallStatus {
        packageInstallStatusMap[pkg]?.let { return it }
        return if (pkg in installedPackages) {
            PackageInstallStatus.Installed
        } else {
            PackageInstallStatus.NotInstalled
        }
    }

    override fun isPackageInstalled(pkg: String): Boolean {
        return getPackageInstallStatus(pkg) is PackageInstallStatus.Installed
    }

    var timeTamperProtectionEnforced: Boolean = false

    override fun enforceTimeTamperProtection(): Boolean {
        if (!deviceOwner) return false
        timeTamperProtectionEnforced = true
        return true
    }
}
