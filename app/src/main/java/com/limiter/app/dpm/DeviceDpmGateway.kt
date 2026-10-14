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
 * Owns direct DevicePolicyManager interaction.
 *
 * Freeze order:
 *   1. suspend
 *   2. verify suspended
 *   3. hide
 *   4. verify hidden
 *
 * Unfreeze order:
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
            if (pkg in failedPackages) {
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
            if (pkg in failedPackages) {
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

    override fun getPackageInstallStatus(pkg: String): PackageInstallStatus {
        return try {
            var pmException: Exception? = null
            val packageInfo = try {
                context.packageManager.getPackageInfo(
                    pkg,
                    PackageManager.PackageInfoFlags.of(PackageManager.MATCH_UNINSTALLED_PACKAGES.toLong())
                )
            } catch (e: Exception) {
                pmException = e
                null
            }

            evaluateStatus(
                packageInfo = packageInfo,
                pmException = pmException,
                isDeviceOwner = isDeviceOwner(),
                isApplicationHidden = { isApplicationHidden(pkg) },
                isPackageSuspended = { isPackageSuspended(pkg) }
            )
        } catch (e: Exception) {
            PackageInstallStatus.Unknown("Unexpected error resolving install status for $pkg: ${e.message}", e)
        }
    }

    companion object {
        /**
         * Pure function for evaluating installation status from PackageManager and DPM results.
         * Separated to enable exhaustive isolated JVM unit testing across all representable states.
         */
        fun evaluateStatus(
            packageInfo: android.content.pm.PackageInfo?,
            pmException: Exception?,
            isDeviceOwner: Boolean,
            isApplicationHidden: () -> Result<Boolean>,
            isPackageSuspended: () -> Result<Boolean>
        ): PackageInstallStatus {
            if (pmException != null) {
                if (pmException is PackageManager.NameNotFoundException) {
                    if (!isDeviceOwner) {
                        return PackageInstallStatus.Unknown("Package visibility limitation: not Device Owner and package not visible via queries")
                    }
                    val hiddenRes = isApplicationHidden()
                    val suspendedRes = isPackageSuspended()
                    return if (hiddenRes.getOrNull() == true || suspendedRes.getOrNull() == true) {
                        // Package existence is NOT independently established by PackageManager.
                        // DPM reporting hidden/suspended when PM threw NameNotFoundException is ambiguous/contradictory.
                        // Never classify as Installed solely because isApplicationHidden() returns true!
                        PackageInstallStatus.Unknown("Ambiguous: PM threw NameNotFoundException but DPM reported restricted; package existence not independently established")
                    } else if (hiddenRes.isSuccess && suspendedRes.isSuccess) {
                        PackageInstallStatus.NotInstalled
                    } else {
                        PackageInstallStatus.Unknown("DPM verification inconclusive: hidden=$hiddenRes, suspended=$suspendedRes")
                    }
                } else if (pmException is SecurityException) {
                    return PackageInstallStatus.Unknown("PackageManager lookup rejected with SecurityException: ${pmException.message}", pmException)
                } else {
                    return PackageInstallStatus.Unknown("PackageManager lookup failed: ${pmException.message}", pmException)
                }
            }

            if (packageInfo == null) {
                return PackageInstallStatus.Unknown("PackageInfo is null")
            }

            val appInfo = packageInfo.applicationInfo
                ?: return PackageInstallStatus.Unknown("PackageInfo returned null applicationInfo for ${packageInfo.packageName}")

            // If the package is data-only (APK code was removed, leaving only data), it is NOT installed.
            val isDataOnly = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_IS_DATA_ONLY) != 0
            if (isDataOnly) {
                return PackageInstallStatus.NotInstalled
            }

            val isInstalledForUser = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_INSTALLED) != 0
            if (isInstalledForUser) {
                return PackageInstallStatus.Installed
            }

            // FLAG_INSTALLED is 0 (and not data-only):
            // Under Android, when an app is hidden via DPM (setApplicationHidden), FLAG_INSTALLED is set to 0.
            // If Limiter is Device Owner, verify via DPM whether the package is hidden.
            if (isDeviceOwner) {
                val hiddenRes = isApplicationHidden()
                return if (hiddenRes.getOrNull() == true) {
                    // Package existence is independently established by PackageInfo (not data-only),
                    // and Device Owner confirms it is currently hidden.
                    PackageInstallStatus.Installed
                } else if (hiddenRes.isSuccess) {
                    // Not hidden by DPM and FLAG_INSTALLED is 0: confirmed not installed for this user.
                    PackageInstallStatus.NotInstalled
                } else {
                    PackageInstallStatus.Unknown("DPM hidden verification inconclusive: $hiddenRes")
                }
            }

            return PackageInstallStatus.Unknown("FLAG_INSTALLED is 0 but Device Owner is absent to verify hidden state")
        }
    }

    override fun isPackageInstalled(pkg: String): Boolean {
        return getPackageInstallStatus(pkg) is PackageInstallStatus.Installed
    }

    override fun enforceTimeTamperProtection(): Boolean {
        if (!isDeviceOwner()) return false
        return try {
            dpm.setAutoTimeEnabled(adminComponent, true)
            dpm.setAutoTimeZoneEnabled(adminComponent, true)
            dpm.addUserRestriction(adminComponent, android.os.UserManager.DISALLOW_CONFIG_DATE_TIME)
            true
        } catch (e: Exception) {
            android.util.Log.w("Limiter", "enforceTimeTamperProtection failed: ${e.message}", e)
            false
        }
    }
}
