package com.limiter.app.data

/**
 * Interface for resolving which packages are protected / critical
 * and which user-installed packages are eligible targets.
 *
 * Implementations should dynamically resolve against the actual device.
 * Tests use a fake implementation.
 */
interface CriticalPackageResolver {
    /** Returns the set of package names that are protected and must not be locked. */
    fun resolveProtected(): Set<String>

    /** Returns true if [packageName] is a user-installed non-system app eligible for targeting. */
    fun isEligibleTarget(packageName: String): Boolean
}
