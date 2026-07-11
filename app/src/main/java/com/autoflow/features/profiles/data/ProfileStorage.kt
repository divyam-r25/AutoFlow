package com.autoflow.features.profiles.data

import android.content.Context
import android.net.Uri
import com.autoflow.automation.models.ActionType
import com.autoflow.automation.models.ExecutionMode
import com.autoflow.features.dashboard.model.ClickActionModel
import com.autoflow.features.profiles.model.AutomationProfile

class ProfileStorage(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadProfiles(): List<AutomationProfile> {
        val raw = prefs.getString(KEY_PROFILES, null)
        val profiles = raw
            ?.split(";;")
            ?.mapNotNull(::decodeProfile)
            .orEmpty()

        if (profiles.isNotEmpty()) {
            return profiles
        }

        val defaults = listOf(
            AutomationProfile(
                id = "default",
                name = "Default",
                executionMode = ExecutionMode.SEQUENTIAL,
                defaultDurationMs = 100L,
                defaultDelayMs = 300L
            ),
            AutomationProfile(
                id = "fast",
                name = "Fast",
                executionMode = ExecutionMode.SEQUENTIAL,
                defaultDurationMs = 50L,
                defaultDelayMs = 150L
            )
        )
        saveProfiles(defaults)
        return defaults
    }

    fun saveProfiles(profiles: List<AutomationProfile>) {
        prefs.edit().putString(
            KEY_PROFILES,
            profiles.joinToString(separator = ";;") { encodeProfile(it) }
        ).apply()
    }

    fun loadSelectedProfileId(): String? = prefs.getString(KEY_SELECTED_PROFILE_ID, null)

    fun saveSelectedProfileId(profileId: String) {
        prefs.edit().putString(KEY_SELECTED_PROFILE_ID, profileId).apply()
    }

    fun loadClickActions(profileId: String): List<ClickActionModel> {
        val raw = prefs.getString(KEY_ACTIONS_PREFIX + profileId, null)
        val actions = raw
            ?.split(";;")
            ?.mapNotNull(::decodeAction)
            .orEmpty()

        if (actions.isNotEmpty()) {
            return actions
        }

        val defaultActions = listOf(
            ClickActionModel(
                id = 1,
                label = "Primary Tap",
                actionType = ActionType.CLICK,
                durationMs = 100L,
                delayMs = 300L
            )
        )
        saveClickActions(profileId, defaultActions)
        return defaultActions
    }

    fun saveClickActions(profileId: String, actions: List<ClickActionModel>) {
        prefs.edit().putString(
            KEY_ACTIONS_PREFIX + profileId,
            actions.joinToString(separator = ";;") { encodeAction(it) }
        ).apply()
    }

    private fun encodeProfile(profile: AutomationProfile): String {
        return listOf(
            Uri.encode(profile.id),
            Uri.encode(profile.name),
            profile.executionMode.name,
            profile.defaultDurationMs.toString(),
            profile.defaultDelayMs.toString()
        ).joinToString("|")
    }

    private fun decodeProfile(raw: String): AutomationProfile? {
        val parts = raw.split("|")
        if (parts.size != 5) return null
        return runCatching {
            AutomationProfile(
                id = Uri.decode(parts[0]),
                name = Uri.decode(parts[1]),
                executionMode = ExecutionMode.valueOf(parts[2]),
                defaultDurationMs = parts[3].toLong(),
                defaultDelayMs = parts[4].toLong()
            )
        }.getOrNull()
    }

    private fun encodeAction(action: ClickActionModel): String {
        return listOf(
            action.id.toString(),
            Uri.encode(action.label),
            action.actionType.name,
            action.durationMs.toString(),
            action.delayMs.toString()
        ).joinToString("|")
    }

    private fun decodeAction(raw: String): ClickActionModel? {
        val parts = raw.split("|")
        if (parts.size != 5) return null
        return runCatching {
            ClickActionModel(
                id = parts[0].toInt(),
                label = Uri.decode(parts[1]),
                actionType = ActionType.valueOf(parts[2]),
                durationMs = parts[3].toLong(),
                delayMs = parts[4].toLong()
            )
        }.getOrNull()
    }

    private companion object {
        const val PREFS_NAME = "autoflow_profiles"
        const val KEY_PROFILES = "profiles"
        const val KEY_SELECTED_PROFILE_ID = "selected_profile_id"
        const val KEY_ACTIONS_PREFIX = "actions_"
    }
}
