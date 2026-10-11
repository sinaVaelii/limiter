package com.limiter.app.ui

import android.app.TimePickerDialog
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import com.limiter.app.R
import com.limiter.app.data.DiscoveredApp
import com.limiter.app.model.LockState
import com.limiter.app.model.PendingChange
import com.limiter.app.model.TargetAppConfig
import com.limiter.app.policy.SelfLockPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LimiterScreen(viewModel: MainViewModel) {
    val state by viewModel.uiState.collectAsState()

    // 1. Self-Lock Mode Branching (User prompt & R4 requirement)
    // While self-lock is active during a scheduled window, management controls are restricted
    // and replaced by the full-screen live countdown screen.
    if (state.selfLockStatus.isSelfLocked) {
        SelfLockScreen(state = state, onTick = { viewModel.tickCurrentTime() })
        return
    }

    // 2. Normal Management Dashboard
    var showAppPicker by remember { mutableStateOf(false) }
    var showBatchConfigDialog by remember { mutableStateOf(false) }
    var editingTarget by remember { mutableStateOf<TargetAppConfig?>(null) }
    var lockedReadOnlyTarget by remember { mutableStateOf<Pair<String, LockState.LockedUntil>?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.app_name)) })
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    viewModel.loadAvailableApps()
                    showAppPicker = true
                },
                modifier = Modifier.size(56.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_apps))
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
        ) {
            // Feedback message banner
            if (state.feedbackMessage != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = state.feedbackMessage ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { viewModel.clearFeedbackMessage() },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel))
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // System Status Card (§49 Onboarding/Status)
            StatusCard(state = state)
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.configured_apps_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))

            if (state.targets.isEmpty() && state.pendingChanges.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.no_apps_configured),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 80.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val allPackages = (state.targets.keys + state.pendingChanges.map { it.packageName }).distinct()

                    items(allPackages) { pkg ->
                        val config = state.targets[pkg]
                        val pending = state.pendingChanges.find { it.packageName == pkg }
                        val lockRecord = state.lockStates[pkg]
                        val usage = state.usages[pkg]
                        val lockState = lockRecord?.state ?: LockState.Unlocked

                        AppStatusCard(
                            packageName = pkg,
                            config = config,
                            pendingChange = pending,
                            usedMillis = if (usage is com.limiter.app.model.UsageSnapshot.Available) usage.usedMillis else 0L,
                            lockState = lockState,
                            nowMillis = state.currentMillis,
                            onEdit = {
                                if (lockState is LockState.LockedUntil && state.currentMillis < lockState.untilEpochMillis) {
                                    // Branch to LockedReadOnly per §52
                                    lockedReadOnlyTarget = pkg to lockState
                                } else {
                                    editingTarget = pending?.desired ?: config ?: TargetAppConfig(pkg, 60, 1380, 420)
                                }
                            },
                            onLockedClick = {
                                if (lockState is LockState.LockedUntil) {
                                    lockedReadOnlyTarget = pkg to lockState
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    // 1. App Picker Dialog
    if (showAppPicker) {
        AppPickerDialog(
            state = state,
            onSearchQueryChange = { viewModel.setSearchQuery(it) },
            onTogglePackage = { viewModel.togglePackageSelection(it) },
            onSelectAll = { filtered -> viewModel.selectAllEligible(filtered) },
            onClearSelections = { viewModel.clearSelections() },
            onDismiss = {
                showAppPicker = false
                viewModel.clearSelections()
            },
            onProceedToConfigure = {
                showAppPicker = false
                showBatchConfigDialog = true
            }
        )
    }

    // 2. Batch Configuration Dialog
    if (showBatchConfigDialog) {
        BatchConfigDialog(
            selectedCount = state.selectedPackages.size,
            onDismiss = {
                showBatchConfigDialog = false
                viewModel.clearSelections()
            },
            onApply = { limit, hide, reveal ->
                viewModel.submitBatchConfig(
                    packageNames = state.selectedPackages,
                    dailyLimitMinutes = limit,
                    hideAtMinuteOfDay = hide,
                    revealAtMinuteOfDay = reveal
                )
                showBatchConfigDialog = false
            }
        )
    }

    // 3. Edit Existing Target Dialog
    if (editingTarget != null) {
        val editingPkg = editingTarget!!.packageName
        val effectiveConfig = state.targets[editingPkg]
        val pendingChange = state.pendingChanges.find { it.packageName == editingPkg }

        EditTargetDialog(
            target = editingTarget!!,
            effectiveConfig = effectiveConfig,
            pendingChange = pendingChange,
            onDismiss = { editingTarget = null },
            onSave = { updatedConfig ->
                viewModel.submitConfig(updatedConfig)
                editingTarget = null
            },
            onRemove = { pkg ->
                viewModel.removeConfig(pkg)
                editingTarget = null
            }
        )
    }

    // 4. Locked Read-Only Dialog per §52
    if (lockedReadOnlyTarget != null) {
        val (pkg, lockState) = lockedReadOnlyTarget!!
        LockedReadOnlyDialog(
            packageName = pkg,
            lockState = lockState,
            currentMillis = state.currentMillis,
            onDismiss = { lockedReadOnlyTarget = null }
        )
    }
}

/**
 * Dedicated, full-screen self-lock restricted mode screen.
 */
@Composable
fun SelfLockScreen(state: MainUiState, onTick: () -> Unit = {}) {
    var nowMillis by remember { mutableLongStateOf(state.currentMillis) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000L)
            nowMillis = System.currentTimeMillis()
            onTick()
        }
    }
    val unlockMillis = state.selfLockStatus.unlockTimeMillis ?: 0L
    val remainingMillis = maxOf(0L, unlockMillis - nowMillis)
    val formattedUnlock = remember(unlockMillis) {
        if (unlockMillis > 0L) {
            val dt = Instant.ofEpochMilli(unlockMillis).atZone(ZoneId.systemDefault())
            DateTimeFormatter.ofPattern("EEEE, MMM d, yyyy - HH:mm").format(dt)
        } else {
            ""
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.self_lock_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.self_lock_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(32.dp))

            // Countdown Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = stringResource(R.string.self_lock_countdown_label),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = SelfLockPolicy.formatRemainingTime(remainingMillis),
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    if (formattedUnlock.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.self_lock_expected_unlock, formattedUnlock),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Explanation Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.self_lock_explanation),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (state.selfLockStatus.activeLockedPackages.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.self_lock_active_apps_title),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        state.selfLockStatus.activeLockedPackages.forEach { pkg ->
                            Text(
                                text = "• $pkg",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Onboarding and system status card per AGENTS.md §49.
 */
@Composable
fun StatusCard(state: MainUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.status_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))

            StatusRow(
                label = stringResource(R.string.status_device_owner),
                isActive = state.isDeviceOwner,
                activeText = stringResource(R.string.status_active),
                inactiveText = stringResource(R.string.status_missing)
            )

            StatusRow(
                label = stringResource(R.string.status_usage_access),
                isActive = state.hasUsageAccess,
                activeText = stringResource(R.string.status_granted),
                inactiveText = stringResource(R.string.status_not_granted)
            )

            StatusRow(
                label = stringResource(R.string.status_notifications),
                isActive = state.hasNotificationPermission,
                activeText = stringResource(R.string.status_granted),
                inactiveText = stringResource(R.string.status_not_granted)
            )

            StatusRow(
                label = stringResource(R.string.status_exact_alarm),
                isActive = state.hasExactAlarmPermission,
                activeText = stringResource(R.string.status_granted),
                inactiveText = stringResource(R.string.status_not_granted)
            )

            StatusRow(
                label = stringResource(R.string.status_device_policy),
                isActive = state.isUninstallBlocked && state.isUserControlDisabled,
                activeText = stringResource(R.string.status_blocked),
                inactiveText = stringResource(R.string.status_not_configured)
            )

            if (!state.isDeviceOwner) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.device_owner_cmd_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
fun StatusRow(
    label: String,
    isActive: Boolean,
    activeText: String,
    inactiveText: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (isActive) Icons.Default.Check else Icons.Default.Warning,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = if (isActive) activeText else inactiveText,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
fun AppStatusCard(
    packageName: String,
    config: TargetAppConfig?,
    pendingChange: PendingChange?,
    usedMillis: Long,
    lockState: LockState,
    nowMillis: Long,
    onEdit: () -> Unit,
    onLockedClick: () -> Unit
) {
    val isLocked = lockState is LockState.LockedUntil && nowMillis < lockState.untilEpochMillis

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (isLocked) onLockedClick() else onEdit()
            }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    AppIcon(packageName = packageName, modifier = Modifier.padding(end = 12.dp))
                    Column {
                        Text(text = packageName, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                IconButton(
                    onClick = { if (isLocked) onLockedClick() else onEdit() },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = if (isLocked) Icons.Default.Lock else Icons.Default.Edit,
                        contentDescription = if (isLocked) stringResource(R.string.locked_readonly_title) else stringResource(R.string.edit_target),
                        tint = if (isLocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))

            if (config != null) {
                val usedMins = (usedMillis / 60_000).toInt()
                val remainingMins = maxOf(0, config.dailyLimitMinutes - usedMins)

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(text = stringResource(R.string.target_used_limit, usedMins, config.dailyLimitMinutes))
                    Text(
                        text = if (remainingMins == 0) stringResource(R.string.target_limit_reached)
                        else stringResource(R.string.target_remaining, remainingMins)
                    )
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        text = stringResource(
                            R.string.target_schedule_window,
                            formatTime(config.hideAtMinuteOfDay),
                            formatTime(config.revealAtMinuteOfDay)
                        ),
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
            }

            when (lockState) {
                is LockState.Unlocked -> {
                    Text(
                        text = stringResource(R.string.target_status_unlocked),
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
                is LockState.ReleasePending -> {
                    Text(
                        text = stringResource(R.string.target_status_restoring),
                        color = MaterialTheme.colorScheme.tertiary,
                        fontWeight = FontWeight.Bold
                    )
                }
                is LockState.LockedUntil -> {
                    val formattedTime = DateTimeFormatter.ofPattern("HH:mm")
                        .withZone(ZoneId.systemDefault())
                        .format(Instant.ofEpochMilli(lockState.untilEpochMillis))
                    Text(
                        text = stringResource(R.string.target_status_locked, formattedTime),
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (pendingChange != null) {
                Spacer(modifier = Modifier.height(8.dp))
                if (pendingChange.desired == null) {
                    Text(
                        text = stringResource(R.string.pending_removal),
                        color = MaterialTheme.colorScheme.secondary,
                        fontWeight = FontWeight.Medium
                    )
                } else {
                    Text(
                        text = stringResource(
                            R.string.pending_change_summary,
                            pendingChange.desired.dailyLimitMinutes,
                            formatTime(pendingChange.desired.hideAtMinuteOfDay),
                            formatTime(pendingChange.desired.revealAtMinuteOfDay)
                        ),
                        color = MaterialTheme.colorScheme.secondary,
                        fontWeight = FontWeight.Medium
                    )
                }
                Text(
                    text = stringResource(R.string.pending_applies_epoch_day, pendingChange.eligibleFromEpochDay),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Read-only dialog shown when user inspects a locked application per AGENTS.md §52.
 */
@Composable
fun LockedReadOnlyDialog(
    packageName: String,
    lockState: LockState.LockedUntil,
    currentMillis: Long,
    onDismiss: () -> Unit
) {
    val remainingMillis = maxOf(0L, lockState.untilEpochMillis - currentMillis)
    val formattedUnlock = remember(lockState.untilEpochMillis) {
        val dt = Instant.ofEpochMilli(lockState.untilEpochMillis).atZone(ZoneId.systemDefault())
        DateTimeFormatter.ofPattern("EEEE, MMM d, yyyy - HH:mm").format(dt)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.locked_readonly_title)) },
        text = {
            Column {
                Text(
                    text = packageName,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.locked_readonly_msg),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = SelfLockPolicy.formatRemainingTime(remainingMillis),
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.self_lock_expected_unlock, formattedUnlock),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.height(48.dp)
            ) {
                Text(stringResource(R.string.back))
            }
        }
    )
}

@Composable
fun AppPickerDialog(
    state: MainUiState,
    onSearchQueryChange: (String) -> Unit,
    onTogglePackage: (String) -> Unit,
    onSelectAll: (List<DiscoveredApp>) -> Unit,
    onClearSelections: () -> Unit,
    onDismiss: () -> Unit,
    onProceedToConfigure: () -> Unit
) {
    val filteredApps = remember(state.availableApps, state.searchQuery) {
        val q = state.searchQuery.trim().lowercase()
        if (q.isEmpty()) {
            state.availableApps
        } else {
            state.availableApps.filter {
                it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q)
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.app_picker_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel))
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Search Bar
                OutlinedTextField(
                    value = state.searchQuery,
                    onValueChange = onSearchQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.search_placeholder)) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (state.searchQuery.isNotEmpty()) {
                            IconButton(onClick = { onSearchQueryChange("") }, modifier = Modifier.size(48.dp)) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear")
                            }
                        }
                    },
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Selection Controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.selected_count, state.selectedPackages.size),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row {
                        TextButton(
                            onClick = { onSelectAll(filteredApps) },
                            modifier = Modifier.height(48.dp)
                        ) {
                            Text(stringResource(R.string.select_all))
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        TextButton(
                            onClick = onClearSelections,
                            modifier = Modifier.height(48.dp)
                        ) {
                            Text(stringResource(R.string.clear_selection))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // App List or Loading
                if (state.isLoadingApps) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (filteredApps.isEmpty()) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.no_apps_found))
                    }
                } else {
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(filteredApps, key = { it.packageName }) { app ->
                            AppPickerRow(
                                app = app,
                                isSelected = state.selectedPackages.contains(app.packageName),
                                onToggle = { onTogglePackage(app.packageName) }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Bottom Action
                Button(
                    onClick = onProceedToConfigure,
                    enabled = state.selectedPackages.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    Text(stringResource(R.string.configure_selected))
                }
            }
        }
    }
}

@Composable
fun AppPickerRow(
    app: DiscoveredApp,
    isSelected: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = isSelected,
            onCheckedChange = { onToggle() },
            modifier = Modifier.size(48.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        AppIcon(packageName = app.packageName, modifier = Modifier.size(40.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = app.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(text = app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun BatchConfigDialog(
    selectedCount: Int,
    onDismiss: () -> Unit,
    onApply: (limit: Int, hide: Int, reveal: Int) -> Unit
) {
    val context = LocalContext.current

    var dailyLimit by remember { mutableIntStateOf(60) }
    var hideMinute by remember { mutableIntStateOf(23 * 60) }
    var revealMinute by remember { mutableIntStateOf(7 * 60) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.batch_config_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.selected_count, selectedCount),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = dailyLimit.toString(),
                    onValueChange = { dailyLimit = it.toIntOrNull() ?: 0 },
                    label = { Text(stringResource(R.string.daily_limit_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = stringResource(R.string.hide_time_label))
                    OutlinedButton(
                        onClick = {
                            val h = hideMinute / 60
                            val m = hideMinute % 60
                            TimePickerDialog(context, { _, hourOfDay, minute ->
                                hideMinute = hourOfDay * 60 + minute
                            }, h, m, true).show()
                        },
                        modifier = Modifier.height(48.dp)
                    ) {
                        Text(formatTime(hideMinute))
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = stringResource(R.string.reveal_time_label))
                    OutlinedButton(
                        onClick = {
                            val h = revealMinute / 60
                            val m = revealMinute % 60
                            TimePickerDialog(context, { _, hourOfDay, minute ->
                                revealMinute = hourOfDay * 60 + minute
                            }, h, m, true).show()
                        },
                        modifier = Modifier.height(48.dp)
                    ) {
                        Text(formatTime(revealMinute))
                    }
                }

                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = errorMessage!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (dailyLimit !in 1..1440) {
                        errorMessage = "Daily limit must be between 1 and 1440 minutes"
                        return@Button
                    }
                    if (hideMinute == revealMinute) {
                        errorMessage = "Hide time and reveal time cannot be identical"
                        return@Button
                    }
                    onApply(dailyLimit, hideMinute, revealMinute)
                },
                modifier = Modifier.height(48.dp)
            ) {
                Text(stringResource(R.string.apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.height(48.dp)) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
fun EditTargetDialog(
    target: TargetAppConfig,
    effectiveConfig: TargetAppConfig? = null,
    pendingChange: PendingChange? = null,
    onDismiss: () -> Unit,
    onSave: (TargetAppConfig) -> Unit,
    onRemove: (String) -> Unit
) {
    val context = LocalContext.current

    var dailyLimit by remember(target.packageName) { mutableIntStateOf(target.dailyLimitMinutes) }
    var hideMinute by remember(target.packageName) { mutableIntStateOf(target.hideAtMinuteOfDay) }
    var revealMinute by remember(target.packageName) { mutableIntStateOf(target.revealAtMinuteOfDay) }
    var errorMessage by remember(target.packageName) { mutableStateOf<String?>(null) }
    var showConfirmRemove by remember(target.packageName) { mutableStateOf(false) }

    if (showConfirmRemove) {
        AlertDialog(
            onDismissRequest = { showConfirmRemove = false },
            title = { Text(stringResource(R.string.confirm_remove_title)) },
            text = { Text(stringResource(R.string.confirm_remove_msg, target.packageName)) },
            confirmButton = {
                Button(
                    onClick = {
                        showConfirmRemove = false
                        onRemove(target.packageName)
                    },
                    modifier = Modifier.height(48.dp)
                ) {
                    Text(stringResource(R.string.remove_target))
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmRemove = false }, modifier = Modifier.height(48.dp)) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_config_title)) },
        text = {
            Column {
                Text(text = target.packageName, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                Spacer(modifier = Modifier.height(8.dp))

                if (effectiveConfig != null) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text(
                                text = stringResource(
                                    R.string.edit_current_effective,
                                    effectiveConfig.dailyLimitMinutes,
                                    formatTime(effectiveConfig.hideAtMinuteOfDay),
                                    formatTime(effectiveConfig.revealAtMinuteOfDay)
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (pendingChange != null && pendingChange.desired != null) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = stringResource(
                                        R.string.edit_pending_update,
                                        pendingChange.desired.dailyLimitMinutes,
                                        formatTime(pendingChange.desired.hideAtMinuteOfDay),
                                        formatTime(pendingChange.desired.revealAtMinuteOfDay)
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.secondary,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                OutlinedTextField(
                    value = dailyLimit.toString(),
                    onValueChange = { dailyLimit = it.toIntOrNull() ?: 0 },
                    label = { Text(stringResource(R.string.daily_limit_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = stringResource(R.string.hide_time_label))
                    OutlinedButton(
                        onClick = {
                            val h = hideMinute / 60
                            val m = hideMinute % 60
                            TimePickerDialog(context, { _, hourOfDay, minute ->
                                hideMinute = hourOfDay * 60 + minute
                            }, h, m, true).show()
                        },
                        modifier = Modifier.height(48.dp)
                    ) {
                        Text(formatTime(hideMinute))
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = stringResource(R.string.reveal_time_label))
                    OutlinedButton(
                        onClick = {
                            val h = revealMinute / 60
                            val m = revealMinute % 60
                            TimePickerDialog(context, { _, hourOfDay, minute ->
                                revealMinute = hourOfDay * 60 + minute
                            }, h, m, true).show()
                        },
                        modifier = Modifier.height(48.dp)
                    ) {
                        Text(formatTime(revealMinute))
                    }
                }

                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = errorMessage!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButton(
                    onClick = { showConfirmRemove = true },
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    Text(stringResource(R.string.remove_target), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (dailyLimit !in 1..1440) {
                        errorMessage = context.getString(R.string.error_daily_limit_range)
                        return@Button
                    }
                    if (hideMinute == revealMinute) {
                        errorMessage = context.getString(R.string.error_identical_times)
                        return@Button
                    }
                    onSave(
                        target.copy(
                            dailyLimitMinutes = dailyLimit,
                            hideAtMinuteOfDay = hideMinute,
                            revealAtMinuteOfDay = revealMinute
                        )
                    )
                },
                modifier = Modifier.height(48.dp)
            ) {
                Text(stringResource(R.string.apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.height(48.dp)) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
fun AppIcon(packageName: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var drawable by remember(packageName) { mutableStateOf<Drawable?>(null) }

    LaunchedEffect(packageName) {
        withContext(Dispatchers.IO) {
            try {
                drawable = context.packageManager.getApplicationIcon(packageName)
            } catch (_: Exception) {}
        }
    }

    if (drawable != null) {
        val bitmap = remember(drawable) { drawable!!.toBitmap().asImageBitmap() }
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = modifier.size(40.dp)
        )
    } else {
        Box(
            modifier = modifier
                .size(40.dp)
                .padding(4.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

fun formatTime(minuteOfDay: Int): String {
    val hours = minuteOfDay / 60
    val minutes = minuteOfDay % 60
    return String.format("%02d:%02d", hours, minutes)
}
