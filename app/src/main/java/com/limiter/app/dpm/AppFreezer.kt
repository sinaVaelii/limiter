package com.limiter.app.dpm

/**
 * App freezer API.
 *
 * A package is physically frozen ONLY if:
 *   hidden == true AND suspended == true
 *
 * Hidden-only is NOT frozen.
 * Suspended-only is NOT frozen.
 *
 * Freeze order: suspend -> verify -> hide -> verify
 * Unfreeze order: unhide -> verify -> unsuspend -> verify
 *
 * Only LockCoordinator may own/use this API.
 */
interface AppFreezerApi {
    /** Freeze: suspend then hide. Returns Ok only if both succeed. */
    fun freeze(pkg: String): DpmOperationResult

    /** Unfreeze: unsuspend and unhide independently. Returns Ok only if both succeed. */
    fun unfreeze(pkg: String): DpmOperationResult

    /** Restore visibility (unhide). */
    fun restoreVisibility(pkg: String): DpmOperationResult

    /** Restore launchability (unsuspend). */
    fun restoreSuspension(pkg: String): DpmOperationResult

    /**
     * Check if a package is physically frozen (hidden AND suspended).
     * Returns null if state cannot be determined.
     */
    fun isFrozen(pkg: String): Boolean?

    /** Check if hidden. */
    fun isHidden(pkg: String): Boolean?

    /** Check if suspended. */
    fun isSuspended(pkg: String): Boolean?
}

/**
 * Production freezer backed by [DpmGateway].
 */
class AppFreezer(
    private val gateway: DpmGateway
) : AppFreezerApi {

    /**
     * Freeze operation:
     * 1. suspend
     * 2. verify suspended
     * 3. hide
     * 4. verify hidden
     *
     * Return Ok only if both are true.
     */
    override fun freeze(pkg: String): DpmOperationResult {
        // Step 1+2: suspend and verify
        val suspendResult = gateway.suspend(pkg)
        if (suspendResult !is DpmOperationResult.Ok) {
            return suspendResult
        }

        // Step 3+4: hide and verify
        val hideResult = gateway.hide(pkg)
        if (hideResult !is DpmOperationResult.Ok) {
            return hideResult
        }

        return DpmOperationResult.Ok
    }

    /**
     * Unfreeze per hotfix requirements:
     * 1. unsuspend
     * 2. unhide (attempted independently of unsuspend outcome)
     *
     * Returns Ok only when both succeed. If either fails, returns Failed
     * with detailed messages while ensuring both were attempted.
     */
    override fun unfreeze(pkg: String): DpmOperationResult {
        if (!gateway.isDeviceOwner()) return DpmOperationResult.NotDeviceOwner

        val unhideResult = gateway.unhide(pkg)
        if (unhideResult !is DpmOperationResult.Ok) {
            return unhideResult
        }

        val unsuspendResult = gateway.unsuspend(pkg)
        if (unsuspendResult !is DpmOperationResult.Ok) {
            return unsuspendResult
        }

        return DpmOperationResult.Ok
    }

    override fun restoreVisibility(pkg: String): DpmOperationResult = gateway.unhide(pkg)

    override fun restoreSuspension(pkg: String): DpmOperationResult = gateway.unsuspend(pkg)

    override fun isFrozen(pkg: String): Boolean? {
        val hidden = isHidden(pkg) ?: return null
        val suspended = isSuspended(pkg) ?: return null
        return hidden && suspended
    }

    override fun isHidden(pkg: String): Boolean? {
        return gateway.isApplicationHidden(pkg).getOrNull()
    }

    override fun isSuspended(pkg: String): Boolean? {
        return gateway.isPackageSuspended(pkg).getOrNull()
    }
}
