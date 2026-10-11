package com.limiter.app.data

import com.limiter.app.model.PendingChange
import com.limiter.app.model.TargetAppConfig

/**
 * Minimal JSON codec for persisting TargetAppConfig and PendingChange.
 *
 * No external JSON library is used — keeps the dependency graph small.
 * Format is simple and deterministic for round-trip testing.
 */
object Codec {

    // ---- TargetAppConfig ----

    fun encodeTargets(targets: Map<String, TargetAppConfig>): String {
        return targets.entries.joinToString(separator = "\n") { (_, config) ->
            "${config.packageName}|${config.dailyLimitMinutes}|${config.hideAtMinuteOfDay}|${config.revealAtMinuteOfDay}"
        }
    }

    fun decodeTargets(raw: String): Map<String, TargetAppConfig> {
        if (raw.isBlank()) return emptyMap()
        return raw.lines().filter { it.isNotBlank() }.mapNotNull { line ->
            try {
                val parts = line.split("|")
                if (parts.size != 4) return@mapNotNull null
                val config = TargetAppConfig(
                    packageName = parts[0],
                    dailyLimitMinutes = parts[1].toInt(),
                    hideAtMinuteOfDay = parts[2].toInt(),
                    revealAtMinuteOfDay = parts[3].toInt()
                )
                config.packageName to config
            } catch (e: Exception) {
                null
            }
        }.toMap()
    }

    // ---- PendingChange ----

    fun encodePending(changes: List<PendingChange>): String {
        return changes.joinToString(separator = "\n") { change ->
            val desiredPart = if (change.desired != null) {
                "${change.desired.packageName}|${change.desired.dailyLimitMinutes}|${change.desired.hideAtMinuteOfDay}|${change.desired.revealAtMinuteOfDay}"
            } else {
                "null"
            }
            "${change.packageName}~${desiredPart}~${change.eligibleFromEpochDay}"
        }
    }

    fun decodePending(raw: String): List<PendingChange> {
        if (raw.isBlank()) return emptyList()
        return raw.lines().filter { it.isNotBlank() }.mapNotNull { line ->
            try {
                val parts = line.split("~")
                if (parts.size != 3) return@mapNotNull null
                val packageName = parts[0]
                val desired = if (parts[1] == "null") {
                    null
                } else {
                    val dParts = parts[1].split("|")
                    if (dParts.size != 4) return@mapNotNull null
                    TargetAppConfig(
                        packageName = dParts[0],
                        dailyLimitMinutes = dParts[1].toInt(),
                        hideAtMinuteOfDay = dParts[2].toInt(),
                        revealAtMinuteOfDay = dParts[3].toInt()
                    )
                }
                PendingChange(
                    packageName = packageName,
                    desired = desired,
                    eligibleFromEpochDay = parts[2].toLong()
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
