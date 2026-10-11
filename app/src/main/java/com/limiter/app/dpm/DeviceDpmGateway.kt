package com.limiter.app.dpm

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.limiter.app.admin.LimiterAdminReceiver

/**
 * Production [DpmGateway] implementation.
 *
 * Owns all direct DevicePolicyManager interaction per AGENTS.md §21-23.
 *
 * Freeze order (§22):
 *   1. suspend
 *   2. verify suspended
 *   3. hide
 *   4. verify hidden
 *
 * Unfreeze order (§23):
 *   1. unhide
 *   2. verify hidden == false
 *   3. unsuspend
 *   4. verify suspended == false
 */
class DeviceDpmGateway(
    private val context: Context
) : DpmGateway {

    private val dpm: DevicePolicyManager by lazy {
        context.getSystemService(DevicePolicyManager::class.java)
    }

    private val adminComponent: ComponentName by lazy {
        ComponentName(context, LimiterAdminReceiver::class.java)
    }

    override fun isDeviceOwner(): Boolean {
        return try {
            dpm.isDeviceOwnerApp(context.packageName)
        } catch (_: Exception) {
            false
        }
    }

    override fun isApplicationHidden(pkg: String): Result<Boolean> {
        return try {
            if (!isDeviceOwner()) return Result.failure(SecurityException("Not device owner"))
            Result.success(dpm.isApplicationHidden(adminComponent, pkg))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun isPackageSuspended(pkg: String): Result<Boolean> {
        return try {
            if (!isDeviceOwner()) return Result.failure(SecurityException("Not device owner"))
            Result.success(dpm.isPackageSuspended(adminComponent, pkg))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun suspend(pkg: String): DpmOperationResult {
        if (!isDeviceOwner()) return DpmOperationResult.NotDeviceOwner
        return try {
            val failedPackages = dpm.setPackagesSuspended(
                adminComponent,
                arrayOf(pkg),
                true
            )
            if (failedPackages != null && pkg in failedPackages) {
                DpmOperationResult.Failed("Package $pkg appeared in failed-package array after suspend")
            } else {
                // Verify suspended state
                val suspended = dpm.isPackageSuspended(adminComponent, pkg)
                if (suspended) {
                    DpmOperationResult.Ok
                } else {
                    DpmOperationResult.Failed("Package $pkg not suspended after setPackagesSuspended")
                }
            }
        } catch (e: Exception) {
            DpmOperationResult.Failed("suspend($pkg): ${e.message}")
        }
    }

    override fun unsuspend(pkg: String): DpmOperationResult {
        if (!isDeviceOwner()) return DpmOperationResult.NotDeviceOwner
        return try {
            val failedPackages = dpm.setPackagesSuspended(
                adminComponent,
                arrayOf(pkg),
                false
            )
            if (failedPackages != null && pkg in failedPackages) {
                DpmOperationResult.Failed("Package $pkg appeared in failed-package array after unsuspend")
            } else {
                // Verify unsuspended
                val suspended = dpm.isPackageSuspended(adminComponent, pkg)
                if (!suspended) {
                    DpmOperationResult.Ok
                } else {
                    DpmOperationResult.Failed("Package $pkg still suspended after unsuspend")
                }
            }
        } catch (e: Exception) {
            DpmOperationResult.Failed("unsuspend($pkg): ${e.message}")
        }
    }

    override fun hide(pkg: String): DpmOperationResult {
        if (!isDeviceOwner()) return DpmOperationResult.NotDeviceOwner
        return try {
            val result = dpm.setApplicationHidden(adminComponent, pkg, true)
            if (!result) {
                return DpmOperationResult.Failed("setApplicationHidden($pkg, true) returned false")
            }
            // Verify hidden
            val hidden = dpm.isApplicationHidden(adminComponent, pkg)
            if (hidden) {
                DpmOperationResult.Ok
            } else {
                DpmOperationResult.Failed("Package $pkg not hidden after setApplicationHidden")
            }
        } catch (e: Exception) {
            DpmOperationResult.Failed("hide($pkg): ${e.message}")
        }
    }

    override fun unhide(pkg: String): DpmOperationResult {
        if (!isDeviceOwner()) return DpmOperationResult.NotDeviceOwner
        return try {
            dpm.setApplicationHidden(adminComponent, pkg, false)
            // Verify visible
            val hidden = dpm.isApplicationHidden(adminComponent, pkg)
            if (!hidden) {
                DpmOperationResult.Ok
            } else {
                DpmOperationResult.Failed("Package $pkg still hidden after unhide")
            }
        } catch (e: Exception) {
            DpmOperationResult.Failed("unhide($pkg): ${e.message}")
        }
    }

    override fun setUninstallBlocked(pkg: String, blocked: Boolean): DpmOperationResult {
        if (!isDeviceOwner()) return DpmOperationResult.NotDeviceOwner
        return try {
            dpm.setUninstallBlocked(adminComponent, pkg, blocked)
            DpmOperationResult.Ok
        } catch (e: Exception) {
            DpmOperationResult.Failed("setUninstallBlocked($pkg, $blocked): ${e.message}")
        }
    }

    override fun isUninstallBlocked(pkg: String): Result<Boolean> {
        return try {
            if (!isDeviceOwner()) return Result.failure(SecurityException("Not device owner"))
            Result.success(dpm.isUninstallBlocked(adminComponent, pkg))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun setUserControlDisabled(packages: List<String>): DpmOperationResult {
        if (!isDeviceOwner()) return DpmOperationResult.NotDeviceOwner
        return try {
            dpm.setUserControlDisabledPackages(adminComponent, packages)
            DpmOperationResult.Ok
        } catch (e: Exception) {
            DpmOperationResult.Failed("setUserControlDisabled: ${e.message}")
        }
    }

    override fun getUserControlDisabledPackages(): List<String> {
        return try {
            if (!isDeviceOwner()) return emptyList()
            dpm.getUserControlDisabledPackages(adminComponent)
        } catch (_: Exception) {
            emptyList()
        }
    }

    override fun isPackageInstalled(pkg: String): Boolean {
        // 1. Direct package manager lookup with MATCH_UNINSTALLED_PACKAGES
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    pkg,
                    PackageManager.PackageInfoFlags.of(PackageManager.MATCH_UNINSTALLED_PACKAGES.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES)
            }
            return true
        } catch (_: PackageManager.NameNotFoundException) {
            // Fall through to DPM check below
        } catch (_: Exception) {
            // Any other exception (e.g. system service temporary failure) is ambiguous
            // and must NOT be treated as definitive evidence of uninstallation.
            return true
        }

        // 2. Fallback to DPM: If Device Owner is active, check if DPM still manages/sees this package.
        // When an app is hidden or suspended by Device Owner, it is physically installed
        // even if local PackageManager visibility rules hide it.
        if (isDeviceOwner()) {
            try {
                val hidden = isApplicationHidden(pkg).getOrNull() == true
                val suspended = isPackageSuspended(pkg).getOrNull() == true
                if (hidden || suspended) {
                    return true
                }
            } catch (_: Exception) {
                // Ambiguous result, preserve safe non-removal
                return true
            }
        }

        return false
    }

    override fun getAllFrozenPackages(): List<String> {
        if (!isDeviceOwner()) return emptyList()
        val pm = context.packageManager
        val packages = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledPackages(
                    PackageManager.PackageInfoFlags.of(PackageManager.MATCH_UNINSTALLED_PACKAGES.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledPackages(PackageManager.MATCH_UNINSTALLED_PACKAGES)
            }
        } catch (_: Exception) {
            emptyList()
        }
        val frozen = mutableListOf<String>()
        for (pkgInfo in packages) {
            val pkg = pkgInfo.packageName ?: continue
            try {
                val hidden = dpm.isApplicationHidden(adminComponent, pkg)
                val suspended = dpm.isPackageSuspended(adminComponent, pkg)
                if (hidden || suspended) {
                    frozen.add(pkg)
                }
            } catch (_: Exception) {
                // Ignore errors for individual packages
            }
        }
        return frozen
    }
}
