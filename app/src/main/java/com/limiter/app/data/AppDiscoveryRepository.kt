package com.limiter.app.data

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Representation of an installed app discovered for potential targeting.
 */
data class DiscoveredApp(
    val packageName: String,
    val label: String
)

/**
 * Interface for discovering eligible user-installed launchable applications.
 */
interface AppDiscoveryRepository {
    suspend fun getEligibleApps(alreadyConfigured: Set<String>): List<DiscoveredApp> =
        getEligibleApps(alreadyConfigured, includeSystemApps = false)

    suspend fun getEligibleApps(
        alreadyConfigured: Set<String>,
        includeSystemApps: Boolean
    ): List<DiscoveredApp> = getEligibleApps(alreadyConfigured)
}

/**
 * Production implementation using PackageManager and CriticalPackageResolver.
 *
 * Uses only legitimate launcher-intent queries per AGENTS.md §51 without QUERY_ALL_PACKAGES.
 */
class DeviceAppDiscoveryRepository(
    private val context: Context,
    private val criticalPackageResolver: CriticalPackageResolver
) : AppDiscoveryRepository {

    override suspend fun getEligibleApps(alreadyConfigured: Set<String>): List<DiscoveredApp> =
        getEligibleApps(alreadyConfigured, includeSystemApps = false)

    override suspend fun getEligibleApps(
        alreadyConfigured: Set<String>,
        includeSystemApps: Boolean
    ): List<DiscoveredApp> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

        val resolveInfos = try {
            pm.queryIntentActivities(launcherIntent, 0)
        } catch (_: Exception) {
            emptyList()
        }

        val protectedPkgs = try {
            criticalPackageResolver.resolveProtected()
        } catch (_: Exception) {
            emptySet()
        }

        val results = mutableListOf<DiscoveredApp>()
        val seen = mutableSetOf<String>()

        for (info in resolveInfos) {
            val pkg = info.activityInfo?.packageName ?: continue
            if (!seen.add(pkg)) continue

            // 1. Exclude already configured targets
            if (pkg in alreadyConfigured) continue

            // 2. Exclude protected packages (Limiter, Launcher, SystemUI, Settings, Dialer, etc.)
            if (pkg in protectedPkgs) continue

            // 3. Exclude system/ineligible packages based on includeSystemApps
            if (!criticalPackageResolver.isEligibleTarget(pkg, includeSystemApps)) continue

            val label = try {
                info.loadLabel(pm)?.toString()?.takeIf { it.isNotBlank() } ?: pkg
            } catch (_: Exception) {
                pkg
            }

            results.add(DiscoveredApp(packageName = pkg, label = label))
        }

        results.sortBy { it.label.lowercase() }
        results
    }
}
