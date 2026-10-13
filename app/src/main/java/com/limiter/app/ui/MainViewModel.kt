package com.limiter.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.limiter.app.LimiterApp
import com.limiter.app.data.AppDiscoveryRepository
import com.limiter.app.data.CriticalPackageResolver
import com.limiter.app.data.DiscoveredApp
import com.limiter.app.data.LockStateStore
import com.limiter.app.data.SettingsRepository
import com.limiter.app.data.UsageSnapshotStore
import com.limiter.app.dpm.DpmGateway
import com.limiter.app.model.LockRecord
import com.limiter.app.model.PendingChange
import com.limiter.app.model.TargetAppConfig
import com.limiter.app.model.UsageSnapshot
import com.limiter.app.policy.LockCoordinator
import com.limiter.app.policy.SettingsPolicy
import com.limiter.app.time.TimeSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.limiter.app.R
import android.content.Context

import com.limiter.app.data.SystemStatus
import com.limiter.app.data.SystemStatusChecker
import com.limiter.app.policy.SelfLockPolicy
import com.limiter.app.scheduler.TimeWindowScheduler

data class BatchResult(
    val succeededPackages: List<String> = emptyList(),
    val failedPackages: Map<String, String> = emptyMap(),
    val deferredPackages: List<String> = emptyList()
)

data class MainUiState(
    val isDeviceOwner: Boolean = false,
    val hasUsageAccess: Boolean = false,
    val hasNotificationPermission: Boolean = false,
    val hasExactAlarmPermission: Boolean = false,
    val isUninstallBlocked: Boolean = false,
    val isUserControlDisabled: Boolean = false,
    val targets: Map<String, TargetAppConfig> = emptyMap(),
    val lockStates: Map<String, LockRecord> = emptyMap(),
    val usages: Map<String, UsageSnapshot> = emptyMap(),
    val pendingChanges: List<PendingChange> = emptyList(),
    val availableApps: List<DiscoveredApp> = emptyList(),
    val isLoadingApps: Boolean = false,
    val searchQuery: String = "",
    val selectedPackages: Set<String> = emptySet(),
    val showSystemApps: Boolean = false,
    val batchResult: BatchResult? = null,
    val feedbackMessage: String? = null,
    val selfLockStatus: SelfLockPolicy.SelfLockStatus = SelfLockPolicy.SelfLockStatus(false),
    val currentMillis: Long = 0L
)

private data class CoreState(
    val targets: Map<String, TargetAppConfig>,
    val lockStates: Map<String, LockRecord>,
    val usages: Map<String, UsageSnapshot>,
    val pending: List<PendingChange>
)

private data class PickerState(
    val availableApps: List<DiscoveredApp>,
    val isLoadingApps: Boolean,
    val searchQuery: String,
    val selectedPackages: Set<String>,
    val showSystemApps: Boolean,
    val batchResult: BatchResult?
)

class MainViewModel(
    private val dpmGateway: DpmGateway,
    private val settingsRepository: SettingsRepository,
    private val lockStateStore: LockStateStore,
    private val usageSnapshotStore: UsageSnapshotStore,
    val timeSource: TimeSource,
    val lockCoordinator: LockCoordinator?,
    private val appDiscoveryRepository: AppDiscoveryRepository? = null,
    private val criticalPackageResolver: CriticalPackageResolver? = null,
    private val systemStatusChecker: SystemStatusChecker? = null,
    private val timeWindowScheduler: TimeWindowScheduler? = null,
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO,
    private val defaultDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Default,
    private val context: Context? = null
) : ViewModel() {

    private val _availableApps = MutableStateFlow<List<DiscoveredApp>>(emptyList())
    private val _isLoadingApps = MutableStateFlow(false)
    private val _searchQuery = MutableStateFlow("")
    private val _selectedPackages = MutableStateFlow<Set<String>>(emptySet())
    private val _showSystemApps = MutableStateFlow(false)
    private val _batchResult = MutableStateFlow<BatchResult?>(null)
    private val _feedbackMessage = MutableStateFlow<String?>(null)
    private val _currentMillis = MutableStateFlow(timeSource.nowMillis())
    private val _cachedProtectedPackages = MutableStateFlow<Set<String>>(emptySet())
    private val _systemStatus = MutableStateFlow(SystemStatus())

    init {
        viewModelScope.launch(defaultDispatcher) {
            refreshSystemStatusInternal()
        }
    }

    private suspend fun refreshProtectedPackagesInternal() = kotlinx.coroutines.withContext(defaultDispatcher) {
        try {
            _cachedProtectedPackages.value = criticalPackageResolver?.resolveProtected() ?: emptySet()
        } catch (_: Exception) {}
    }

    fun refreshProtectedPackages() {
        viewModelScope.launch(defaultDispatcher) {
            refreshProtectedPackagesInternal()
        }
    }

    fun tickCurrentTime() {
        _currentMillis.value = timeSource.nowMillis()
    }

    private suspend fun refreshSystemStatusInternal() = kotlinx.coroutines.withContext(defaultDispatcher) {
        try {
            criticalPackageResolver?.invalidateCache()
            val status = systemStatusChecker?.checkStatus()
                ?: SystemStatus(isDeviceOwner = dpmGateway.isDeviceOwner())
            _systemStatus.value = status
            refreshProtectedPackagesInternal()
        } catch (_: Exception) {}
    }

    fun refreshSystemStatus() {
        _currentMillis.value = timeSource.nowMillis()
        viewModelScope.launch(defaultDispatcher) {
            refreshSystemStatusInternal()
        }
    }

    val uiState: StateFlow<MainUiState> = combine(
        combine(
            settingsRepository.targets,
            lockStateStore.states,
            usageSnapshotStore.snapshots,
            settingsRepository.pendingChanges
        ) { targets, lockStates, usages, pending ->
            CoreState(targets, lockStates, usages, pending)
        },
        combine(
            _availableApps,
            _isLoadingApps,
            _searchQuery,
            _selectedPackages,
            combine(_showSystemApps, _batchResult) { showSys, batch -> showSys to batch }
        ) { apps, loading, query, selected, (showSys, batch) ->
            PickerState(apps, loading, query, selected, showSys, batch)
        },
        _systemStatus,
        _currentMillis,
        combine(_feedbackMessage, _cachedProtectedPackages) { fb, prot -> fb to prot }
    ) { core, picker, systemStatus, nowMillis, (feedback, protectedPkgs) ->
        val selfLock = SelfLockPolicy.calculateSelfLockStatus(
            targets = core.targets,
            nowMillis = nowMillis,
            zone = timeSource.zone(),
            protectedPackages = protectedPkgs
        )
        MainUiState(
            isDeviceOwner = systemStatus.isDeviceOwner,
            hasUsageAccess = systemStatus.hasUsageAccess,
            hasNotificationPermission = systemStatus.hasNotificationPermission,
            hasExactAlarmPermission = systemStatus.hasExactAlarmPermission,
            isUninstallBlocked = systemStatus.isUninstallBlocked,
            isUserControlDisabled = systemStatus.isUserControlDisabled,
            targets = core.targets,
            lockStates = core.lockStates,
            usages = core.usages,
            pendingChanges = core.pending,
            availableApps = picker.availableApps,
            isLoadingApps = picker.isLoadingApps,
            searchQuery = picker.searchQuery,
            selectedPackages = picker.selectedPackages,
            showSystemApps = picker.showSystemApps,
            batchResult = picker.batchResult,
            feedbackMessage = feedback,
            selfLockStatus = selfLock,
            currentMillis = nowMillis
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = MainUiState(
            isDeviceOwner = dpmGateway.isDeviceOwner(),
            currentMillis = timeSource.nowMillis()
        )
    )

    fun setShowSystemApps(show: Boolean) {
        if (_showSystemApps.value != show) {
            _showSystemApps.value = show
            loadAvailableApps()
        }
    }

    fun loadAvailableApps() {
        val repo = appDiscoveryRepository ?: return
        viewModelScope.launch {
            _isLoadingApps.value = true
            try {
                val isSelfLocked = uiState.value.selfLockStatus.isSelfLocked
                val alreadyConfigured = if (isSelfLocked) emptySet() else settingsRepository.snapshotTargets().keys
                val apps = repo.getEligibleApps(alreadyConfigured, _showSystemApps.value)
                _availableApps.value = apps
            } catch (e: Exception) {
                _feedbackMessage.value = "Failed to load apps: ${e.message}"
            } finally {
                _isLoadingApps.value = false
            }
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun togglePackageSelection(packageName: String) {
        val selfLock = uiState.value.selfLockStatus
        if (selfLock.isSelfLocked && packageName in uiState.value.targets.keys) {
            return
        }
        val current = _selectedPackages.value
        _selectedPackages.value = if (packageName in current) {
            current - packageName
        } else {
            current + packageName
        }
    }

    fun selectAllEligible(filteredList: List<DiscoveredApp>) {
        val selfLock = uiState.value.selfLockStatus
        val currentTargets = uiState.value.targets
        val eligiblePkgs = if (selfLock.isSelfLocked) {
            filteredList.filter { it.packageName !in currentTargets.keys }.map { it.packageName }
        } else {
            filteredList.map { it.packageName }
        }
        val current = _selectedPackages.value
        _selectedPackages.value = current + eligiblePkgs
    }

    fun clearSelections() {
        _selectedPackages.value = emptySet()
    }

    fun clearFeedbackMessage() {
        _feedbackMessage.value = null
    }

    fun clearBatchResult() {
        _batchResult.value = null
    }

    /**
     * Submit batch configuration for multiple selected applications.
     * Evaluates domain rules, performs sequential addition with per-package outcome reporting,
     * reconciles and reschedules.
     */
    fun submitBatchConfig(
        packageNames: Set<String>,
        dailyLimitMinutes: Int,
        hideAtMinuteOfDay: Int,
        revealAtMinuteOfDay: Int
    ) {
        viewModelScope.launch {
            if (packageNames.isEmpty()) {
                _feedbackMessage.value = "No applications selected"
                return@launch
            }
            if (dailyLimitMinutes !in SettingsPolicy.MIN_DAILY_LIMIT_MINUTES..SettingsPolicy.MAX_DAILY_LIMIT_MINUTES) {
                _feedbackMessage.value = getString(R.string.error_daily_limit_range)
                return@launch
            }
            if (hideAtMinuteOfDay !in 0..1439 || revealAtMinuteOfDay !in 0..1439) {
                _feedbackMessage.value = "Times must be between 00:00 and 23:59"
                return@launch
            }
            if (hideAtMinuteOfDay == revealAtMinuteOfDay) {
                _feedbackMessage.value = getString(R.string.error_identical_times)
                return@launch
            }

            val coordinator = lockCoordinator
            if (coordinator == null) {
                _feedbackMessage.value = "Lock coordinator is not available"
                return@launch
            }

            val succeeded = mutableListOf<String>()
            val failed = mutableMapOf<String, String>()
            val deferred = mutableListOf<String>()

            for (pkg in packageNames) {
                try {
                    val config = TargetAppConfig(
                        packageName = pkg,
                        dailyLimitMinutes = dailyLimitMinutes,
                        hideAtMinuteOfDay = hideAtMinuteOfDay,
                        revealAtMinuteOfDay = revealAtMinuteOfDay
                    )
                    val result = coordinator.tryEditConfig(config)
                    when (result) {
                        is SettingsPolicy.EditResult.Applied -> succeeded.add(pkg)
                        is SettingsPolicy.EditResult.Deferred -> deferred.add(pkg)
                        is SettingsPolicy.EditResult.PartialApplied -> succeeded.add(pkg)
                        is SettingsPolicy.EditResult.Protected -> failed[pkg] = "Protected package"
                        is SettingsPolicy.EditResult.Duplicate -> failed[pkg] = "Already configured"
                        is SettingsPolicy.EditResult.NotFound -> failed[pkg] = "Not found"
                        is SettingsPolicy.EditResult.Invalid -> failed[pkg] = "Invalid: ${result.reason}"
                        is SettingsPolicy.EditResult.SelfLocked -> failed[pkg] = getString(R.string.self_lock_controls_locked)
                    }
                } catch (e: Exception) {
                    failed[pkg] = e.message ?: "Configuration error"
                }
            }

            if (succeeded.isNotEmpty() || deferred.isNotEmpty()) {
                coordinator.reconcile()
                timeWindowScheduler?.reschedule()
                loadAvailableApps()
            }

            val res = BatchResult(
                succeededPackages = succeeded,
                failedPackages = failed,
                deferredPackages = deferred
            )
            _batchResult.value = res
            _selectedPackages.value = emptySet()
            
            val totalApplied = succeeded.size + deferred.size
            _feedbackMessage.value = "Added $totalApplied app(s)" +
                    if (failed.isNotEmpty()) " (${failed.size} failed)" else ""
        }
    }

    /**
     * Submit single target configuration (used when editing existing targets per Requirement 2).
     */
    fun submitConfig(config: TargetAppConfig) {
        viewModelScope.launch {
            val coordinator = lockCoordinator ?: return@launch
            val result = coordinator.tryEditConfig(config)
            coordinator.reconcile()
            timeWindowScheduler?.reschedule()
            when (result) {
                is SettingsPolicy.EditResult.Applied -> _feedbackMessage.value = "Updated ${config.packageName}"
                is SettingsPolicy.EditResult.Deferred -> _feedbackMessage.value = "Changes to ${config.packageName} deferred to tomorrow"
                is SettingsPolicy.EditResult.PartialApplied -> _feedbackMessage.value = "Tightening applied; loosening deferred to tomorrow"
                is SettingsPolicy.EditResult.Invalid -> _feedbackMessage.value = "Invalid configuration: ${result.reason}"
                is SettingsPolicy.EditResult.Protected -> _feedbackMessage.value = "${config.packageName} is protected"
                is SettingsPolicy.EditResult.SelfLocked -> _feedbackMessage.value = getString(R.string.self_lock_controls_locked)
                else -> {}
            }
        }
    }

    /**
     * Submit target removal (used when removing existing targets per Requirement 2).
     */
    fun removeConfig(packageName: String) {
        viewModelScope.launch {
            val coordinator = lockCoordinator ?: return@launch
            val result = coordinator.tryRemoveConfig(packageName)
            coordinator.reconcile()
            timeWindowScheduler?.reschedule()
            loadAvailableApps()
            when (result) {
                is SettingsPolicy.RemoveResult.Deferred -> {
                    _feedbackMessage.value = "Removal of $packageName deferred to epoch day ${result.pending.eligibleFromEpochDay}"
                }
                is SettingsPolicy.RemoveResult.NotFound -> {
                    _feedbackMessage.value = "$packageName not found"
                }
                is SettingsPolicy.RemoveResult.SelfLocked -> {
                    _feedbackMessage.value = getString(R.string.self_lock_controls_locked)
                }
            }
        }
    }

    private fun getString(resId: Int, vararg args: Any): String {
        return context?.getString(resId, *args) ?: when (resId) {
            R.string.error_daily_limit_range -> "Daily limit must be between 1 and 720 minutes"
            R.string.error_identical_times -> "Hide time and reveal time cannot be identical"
            R.string.self_lock_controls_locked -> "Management controls are locked during active restriction window"
            else -> ""
        }
    }

    class Factory(private val app: LimiterApp) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MainViewModel(
                dpmGateway = app.dpmGateway,
                settingsRepository = app.settingsRepository,
                lockStateStore = app.lockStateStore,
                usageSnapshotStore = app.usageSnapshotStore,
                timeSource = app.timeSource,
                lockCoordinator = app.lockCoordinator,
                appDiscoveryRepository = app.appDiscoveryRepository,
                criticalPackageResolver = app.criticalPackageResolver,
                systemStatusChecker = app.systemStatusChecker,
                timeWindowScheduler = app.timeWindowScheduler,
                ioDispatcher = kotlinx.coroutines.Dispatchers.IO,
                defaultDispatcher = kotlinx.coroutines.Dispatchers.Default,
                context = app
            ) as T
        }
    }
}
